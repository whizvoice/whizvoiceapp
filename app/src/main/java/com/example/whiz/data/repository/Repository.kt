package com.example.whiz.data.repository

import android.content.Context
import android.util.Log
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.WhizDatabase
import androidx.room.withTransaction
import com.example.whiz.data.local.toChatEntity
import com.example.whiz.data.local.toMessageEntity
import com.example.whiz.data.local.parseTimestampToMillis
import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Room is the single source of truth for chats and messages. Chats are identified by a
 * client-minted UUID for their whole life; the server's integer id is stored as
 * [ChatEntity.serverId] for diagnostics only. The UI observes Room ([observeChats],
 * [observeMessages]); [syncChats] and [syncMessages] merge server state into Room.
 */
@Singleton
class WhizRepository @Inject constructor(
    private val apiService: ApiService,
    private val context: Context,
    private val messageDao: com.example.whiz.data.local.MessageDao,
    private val chatDao: com.example.whiz.data.local.ChatDao,
    private val database: WhizDatabase,
    // Provider to mirror TokenAuthenticator's pattern and avoid DI cycles via OkHttp
    private val authRepositoryProvider: javax.inject.Provider<com.example.whiz.data.auth.AuthRepository>
) {
    private val TAG = "WhizRepository"

    private val syncPrefs = context.getSharedPreferences("sync_metadata", Context.MODE_PRIVATE)

    // Runs a block as one Room transaction. A seam so unit tests (which mock the DAOs and can't
    // drive RoomDatabase.withTransaction) can observe the transaction boundary.
    internal var transactionRunner: TransactionRunner = object : TransactionRunner {
        override suspend fun <R> run(block: suspend () -> R): R = database.withTransaction(block)
    }

    // Track ongoing API requests to prevent duplicates
    private val ongoingMessageRequests = ConcurrentHashMap<String, Deferred<List<MessageEntity>>>()
    private val ongoingConversationRequests = ConcurrentHashMap<String, Deferred<ConversationsFetchResult>>()

    // True once the first syncChats() has finished (successfully or not). The chat list shows
    // "Loading chats..." only while this is false and Room has no rows.
    private val _isFirstSyncComplete = MutableStateFlow(false)
    val isFirstSyncComplete: StateFlow<Boolean> = _isFirstSyncComplete.asStateFlow()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Sync chats on app startup so the list reflects the server.
        repositoryScope.launch {
            try {
                delay(100) // Small delay to ensure app initialization is complete

                // Wait until a user is signed in before syncing. A sync without a token is
                // a guaranteed 401, and TokenAuthenticator answers unrefreshable 401s with
                // signOut() — which can race with (and wipe) a sign-in happening in between.
                authRepositoryProvider.get().serverToken.first { it != null }

                syncChats(forceFull = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error during initial chats sync", e)
            }
        }
    }

    // ================== CHATS ==================

    fun observeChats(): Flow<List<ChatEntity>> = chatDao.getAllChatsFlow()

    suspend fun getChatById(chatId: String): ChatEntity? = chatDao.getChatById(chatId)

    /**
     * Merge the server's chats into Room.
     * - Rows are written with upsert (never REPLACE, which would cascade-delete messages).
     * - For a row that already exists: title comes from the server,
     *   lastMessageTime = max(local, server) so a chat the user just wrote in doesn't move down.
     * - Tombstones (deleted_at != null) delete the row.
     * - On [forceFull], synced rows the server no longer returns are deleted; unsynced rows
     *   (serverId == null) are never pruned.
     */
    suspend fun syncChats(forceFull: Boolean = false): SyncResult {
        try {
            val fetch = fetchConversationsWithDeduplication(
                forceFullSync = forceFull,
                // Incremental syncs re-read the last 30s to catch chats created between
                // sync timestamps (same behaviour the chat list's pull-to-refresh always had).
                useAggressiveSync = !forceFull
            )
            if (fetch.isCachedData) {
                Log.d(TAG, "syncChats: fetch failed, returning Room rows as cached data")
                return SyncResult(chatDao.getAllChatsFlow().first(), isCachedData = true)
            }

            transactionRunner.run { applySyncWrites(fetch, forceFull) }

            return SyncResult(chatDao.getAllChatsFlow().first(), isCachedData = false)
        } catch (e: Exception) {
            Log.e(TAG, "syncChats: error", e)
            val cached = try {
                chatDao.getAllChatsFlow().first()
            } catch (localError: Exception) {
                emptyList()
            }
            return SyncResult(cached, isCachedData = true)
        } finally {
            _isFirstSyncComplete.value = true
        }
    }

    /**
     * The Room write phase of [syncChats]. Callers run it inside [transactionRunner] so the local
     * rows it reads (for the lastMessageTime max-merge and the prune guard) cannot change before
     * its writes land.
     */
    internal suspend fun applySyncWrites(fetch: ConversationsFetchResult, forceFull: Boolean) {
        val (tombstones, live) = fetch.conversations.partition { it.deleted_at != null }

        tombstones.forEach { chatDao.deleteChat(it.client_chat_id) }
        if (tombstones.isNotEmpty()) {
            Log.d(TAG, "syncChats: deleted ${tombstones.size} tombstoned chats")
        }

        val localById = chatDao.getAllChatsSnapshot().associateBy { it.id }
        val merged = live.map { conversation ->
            val server = conversation.toChatEntity()
            val local = localById[server.id]
            if (local == null) {
                server
            } else {
                server.copy(lastMessageTime = maxOf(local.lastMessageTime, server.lastMessageTime))
            }
        }
        if (merged.isNotEmpty()) {
            chatDao.upsertAll(merged)
        }
        Log.d(TAG, "syncChats: upserted ${merged.size} chats (forceFull=$forceFull, incremental=${fetch.isIncremental})")

        if (forceFull && !fetch.isIncremental) {
            // The server's list is a snapshot taken when the request started. Keep rows
            // created or written to locally since then: their server row may postdate it.
            val recentLocalIds = localById.values
                .filter { maxOf(it.createdAt, it.lastMessageTime) >= fetch.requestStartedAt }
                .map { it.id }
            val keepIds = (merged.map { it.id } + recentLocalIds).distinct()
            val pruned = if (keepIds.isEmpty()) {
                // NOT IN () would match every synced row; say so explicitly instead.
                chatDao.deleteAllSyncedChats()
            } else {
                chatDao.deleteSyncedChatsNotIn(keepIds)
            }
            Log.d(TAG, "syncChats: full sync pruned $pruned synced chats not returned by server")
        }
    }

    // Force a complete refresh by clearing sync timestamps
    suspend fun forceFullRefresh() {
        Log.d(TAG, "forceFullRefresh: clearing all sync timestamps")
        syncPrefs.edit().clear().apply()
        syncChats(forceFull = true)
    }

    /** Create a chat row locally (no REST call); the server row is created on first send. */
    suspend fun createChatLocal(id: String, title: String) {
        val now = System.currentTimeMillis()
        // INSERT OR IGNORE: if a sync wrote this row first, keep it (and its serverId) untouched.
        val rowId = chatDao.insertIfAbsent(
            ChatEntity(
                id = id,
                serverId = null,
                title = title,
                createdAt = now,
                lastMessageTime = now
            )
        )
        if (rowId == -1L) {
            Log.d(TAG, "createChatLocal: chat $id already exists, leaving it as is")
        } else {
            Log.d(TAG, "createChatLocal: created chat $id with title '$title'")
        }
    }

    /**
     * A message arrived for [chatId]. If the row is missing, sync first (the chat may exist on
     * the server, with its real title); only if it is still missing create it from the message.
     */
    suspend fun ensureChatRowForIncoming(chatId: String, firstLine: String) {
        if (chatDao.getChatById(chatId) != null) return
        Log.d(TAG, "ensureChatRowForIncoming: chat $chatId not in Room, syncing first")
        syncChats()
        if (chatDao.getChatById(chatId) == null) {
            Log.d(TAG, "ensureChatRowForIncoming: chat $chatId still missing after sync, creating it locally")
            createChatLocal(chatId, deriveChatTitle(firstLine))
        }
    }

    /** Record the server's integer id for diagnostics, if the row doesn't have one yet. */
    suspend fun recordServerId(chatId: String, serverId: Long) {
        val chat = chatDao.getChatById(chatId) ?: return
        if (chat.serverId == null) {
            chatDao.setServerId(chatId, serverId)
            Log.d(TAG, "recordServerId: chat $chatId -> server id $serverId")
        }
    }

    suspend fun updateChatTitle(chatId: String, title: String) {
        try {
            Log.d(TAG, "updateChatTitle: updating chat $chatId title to '$title' via API")
            apiService.updateConversation(chatId, ApiService.ConversationUpdate(title = title))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId title via API", e)
        }
        chatDao.updateTitle(chatId, title)
    }

    // REST-only bump of the server's last_message_time (Room is bumped by each message insert).
    suspend fun updateChatLastMessageTime(chatId: String) {
        try {
            Log.d(TAG, "updateChatLastMessageTime: updating chat $chatId last message time via API")
            apiService.updateConversationLastMessageTime(chatId)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId last message time via API", e)
        }
    }

    /**
     * Delete a chat. Unsynced rows (serverId == null) are deleted locally only. For synced rows
     * the REST delete must succeed (or 404) first, otherwise the next sync would bring it back.
     */
    suspend fun deleteChat(chatId: String) {
        try {
            val chat = chatDao.getChatById(chatId)
            if (chat?.serverId != null) {
                try {
                    Log.d(TAG, "deleteChat: deleting chat $chatId (server id ${chat.serverId}) via API")
                    apiService.deleteConversation(chatId)
                } catch (e: retrofit2.HttpException) {
                    if (e.code() != 404) throw e
                    Log.d(TAG, "deleteChat: chat $chatId already gone on server (404)")
                }
            } else {
                Log.d(TAG, "deleteChat: chat $chatId has no server id, deleting locally only")
            }
            chatDao.deleteChat(chatId) // messages cascade
            ongoingMessageRequests.remove(chatId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat $chatId", e)
        }
    }

    suspend fun deleteAllChats() {
        try {
            Log.d(TAG, "deleteAllChats: deleting all chats via API")
            apiService.deleteAllConversations()
            chatDao.deleteAllChats() // messages cascade
            ongoingConversationRequests.clear()
            ongoingMessageRequests.clear()
            Log.d(TAG, "deleteAllChats: deleted all chats and messages")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all chats via API", e)
        }
    }

    /** Delete never-synced chats older than an hour that never got a message. */
    suspend fun cleanupStaleUnsyncedChats() {
        try {
            val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
            val staleChats = chatDao.getUnsyncedChatsOlderThan(oneHourAgo)
                .filter { messageDao.getMessageCountForChat(it.id) == 0 }

            if (staleChats.isNotEmpty()) {
                Log.d(TAG, "cleanupStaleUnsyncedChats: deleting ${staleChats.size} stale unsynced chats")
                staleChats.forEach { chat -> chatDao.deleteChat(chat.id) }
            } else {
                Log.d(TAG, "cleanupStaleUnsyncedChats: no stale unsynced chats found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up stale unsynced chats", e)
        }
    }

    // ================== MESSAGES ==================

    fun observeMessages(chatId: String): Flow<List<MessageEntity>> {
        // Room automatically emits when messages are inserted/updated/deleted
        Log.d(TAG, "observeMessages called for chatId=$chatId")
        return messageDao.getMessagesForChatFlow(chatId)
            .catch { e ->
                Log.e(TAG, "Error in observeMessages flow", e)
                emit(emptyList<MessageEntity>()) // Emit empty list on error
            }.shareIn(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                started = SharingStarted.WhileSubscribed(5000L), // Keep active for 5 seconds after last subscriber
                replay = 1 // Always replay the latest value to new subscribers
            )
    }

    suspend fun getMessageCountForChat(chatId: String): Int {
        return try {
            val response = apiService.getMessageCount(chatId)
            response.count
        } catch (e: Exception) {
            Log.e(TAG, "Error getting message count for chat $chatId from API", e)
            0
        }
    }

    suspend fun getPendingRequests(chatId: String): List<String> {
        return try {
            val response = apiService.getPendingRequests(chatId)
            if (response.hasPending) response.requestIds else emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking pending requests for chat $chatId", e)
            emptyList()
        }
    }

    /**
     * Add user message for optimistic UI - only stores locally, doesn't make API call.
     * The chat row must already exist (callers create it with [createChatLocal]).
     * The actual server message will be received via WebSocket and deduplicated.
     * @param timestamp Optional timestamp in milliseconds. If not provided, uses current time.
     */
    suspend fun addUserMessageOptimistic(chatId: String, content: String, requestId: String? = null, timestamp: Long? = null): Long {
        return try {
            Log.d(TAG, "addUserMessageOptimistic: adding optimistic user message to chat $chatId with requestId: $requestId, timestamp: $timestamp")

            // Check if this message already exists to prevent duplicates
            val existingMessages = messageDao.getMessagesForChatFlow(chatId).first()
            val duplicateMessage = existingMessages.find {
                it.content.trim() == content.trim() &&
                it.type == MessageType.USER &&
                // Only consider recent messages (within last 30 seconds) as potential duplicates
                (System.currentTimeMillis() - it.timestamp) < 30000
            }

            if (duplicateMessage != null) {
                Log.d(TAG, "addUserMessageOptimistic: duplicate message detected, returning existing ID ${duplicateMessage.id}")
                return duplicateMessage.id
            }

            val messageTimestamp = timestamp ?: System.currentTimeMillis()
            val messageEntity = MessageEntity(
                id = 0,
                chatId = chatId,
                content = content,
                type = MessageType.USER,
                timestamp = messageTimestamp,
                requestId = requestId // Store requestId for pairing with response
            )

            val messageId = insertMessageAndBumpChat(messageEntity)
            Log.d(TAG, "addUserMessageOptimistic: added optimistic message $messageId to chat $chatId with requestId: $requestId")
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding optimistic user message to chat $chatId: ${e.message}", e)
            -1
        }
    }

    // Creates the message on the server via REST (needs the server's integer id).
    suspend fun addAssistantMessage(chatId: String, content: String): Long {
        return try {
            val serverId = chatDao.getChatById(chatId)?.serverId
            if (serverId == null) {
                Log.w(TAG, "addAssistantMessage: chat $chatId has no server id yet, cannot create message via API")
                return -1
            }
            Log.d(TAG, "addAssistantMessage: adding assistant message to chat $chatId (server id $serverId) via API")
            val createRequest = ApiService.MessageCreate(
                conversation_id = serverId,
                content = content,
                message_type = MessageType.ASSISTANT.name
            )
            val message = apiService.createMessage(createRequest)
            Log.d(TAG, "addAssistantMessage: added assistant message ${message.id} to chat $chatId")
            message.id
        } catch (e: Exception) {
            Log.e(TAG, "Error adding assistant message to chat $chatId via API", e)
            -1
        }
    }

    /**
     * Add assistant message for optimistic UI - only stores locally, doesn't make API call.
     * Used when we receive WebSocket responses and want immediate UI feedback.
     */
    suspend fun addAssistantMessageOptimistic(chatId: String, content: String, requestId: String? = null): Long {
        return try {
            Log.d(TAG, "addAssistantMessageOptimistic: adding optimistic assistant message to chat $chatId with requestId: $requestId")

            val messageTimestamp = System.currentTimeMillis()
            val messageEntity = MessageEntity(
                id = 0,
                chatId = chatId,
                content = content,
                type = MessageType.ASSISTANT,
                timestamp = messageTimestamp,
                requestId = requestId // Store requestId for pairing with user message
            )

            val messageId = insertMessageAndBumpChat(messageEntity)
            Log.d(TAG, "addAssistantMessageOptimistic: added optimistic assistant message $messageId to chat $chatId with requestId: $requestId")
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding optimistic assistant message to chat $chatId: ${e.message}", e)
            -1
        }
    }

    /**
     * Add assistant message after the user message with matching requestId.
     * This ensures responses appear in the correct order relative to their user messages.
     */
    suspend fun addAssistantMessageAfterRequest(chatId: String, content: String, requestId: String, serverTimestamp: String? = null): Long {
        return try {
            Log.d(TAG, "addAssistantMessageAfterRequest: adding assistant message after request $requestId in chat $chatId")

            // Find the user message with this requestId
            val userMessage = messageDao.getUserMessageByRequestId(chatId, requestId)
            if (userMessage != null) {
                Log.d(TAG, "addAssistantMessageAfterRequest: found user message ${userMessage.id} with requestId $requestId")

                // Adopt the server's authoritative timestamp when provided; otherwise fall back to
                // the local +1ms-after-user rule. This keeps live ordering identical to the reload
                // path (which already uses the server timestamp) and to the server's stored order,
                // so the client no longer maintains a separate ordering calculation.
                val assistantTimestamp = serverTimestamp?.let { parseTimestampToMillis(it) }
                    ?: (userMessage.timestamp + 1) // Ensure it goes after the user message

                val assistantMessage = MessageEntity(
                    id = 0,
                    chatId = chatId,
                    content = content,
                    type = MessageType.ASSISTANT,
                    timestamp = assistantTimestamp,
                    requestId = requestId // Link to the user message
                )

                val messageId = insertMessageAndBumpChat(assistantMessage)
                Log.d(TAG, "addAssistantMessageAfterRequest: added assistant message $messageId after user message ${userMessage.id}")
                messageId
            } else {
                Log.w(TAG, "addAssistantMessageAfterRequest: no user message found with requestId $requestId, adding at end")
                // Fallback: add at the end if no matching user message found
                addAssistantMessageOptimistic(chatId, content, requestId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding assistant message after request $requestId in chat $chatId: ${e.message}", e)
            -1
        }
    }

    // Insert a message and bump its chat's lastMessageTime (never lowered) as one transaction.
    private suspend fun insertMessageAndBumpChat(message: MessageEntity): Long =
        transactionRunner.run {
            val messageId = messageDao.insertMessage(message)
            chatDao.updateChatLastMessageTime(message.chatId, message.timestamp)
            messageId
        }

    /**
     * Delete assistant message by request ID.
     * Used when server notifies that a message has been cancelled/superseded.
     */
    suspend fun deleteAssistantMessageByRequestId(chatId: String, requestId: String): Int {
        return try {
            val deletedCount = messageDao.deleteAssistantMessageByRequestId(chatId, requestId)
            Log.d(TAG, "deleteAssistantMessageByRequestId: Deleted $deletedCount message(s) for requestId $requestId in chat $chatId")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting assistant message by requestId $requestId in chat $chatId: ${e.message}", e)
            0
        }
    }

    // Auto-save logic - now based on API call
    suspend fun shouldPersistChat(chatId: String): Boolean {
        val count = getMessageCountForChat(chatId)
        val shouldPersist = count >= 3
        Log.d(TAG, "shouldPersistChat: chat $chatId has $count messages, should persist: $shouldPersist")
        return shouldPersist
    }

    // Helper to get chat title based on first message or default title
    fun deriveChatTitle(userMessage: String): String {
        // Extract first line or first few words for the title
        val firstLine = userMessage.trim().split("\n").first()
        return if (firstLine.length > 20) {
            "${firstLine.take(20)}..."
        } else {
            firstLine
        }
    }

    suspend fun refreshMessages() {
        // Clear ongoing request tracking to allow fresh requests
        ongoingMessageRequests.clear()
        ongoingConversationRequests.clear()
        Log.d(TAG, "refreshMessages: cleared ongoing request tracking")
    }

    /** Fetch the chat's messages from the server and merge them into Room. */
    suspend fun syncMessages(chatId: String) {
        fetchMessagesWithDeduplication(chatId)
    }

    /**
     * Sync messages and return the chat's messages from Room. Kept for ChatViewModel callers that
     * log the count; the optimistic-chat retry loop it used to run is gone (a client id never
     * needs to wait for the server to assign a real one).
     */
    suspend fun fetchMessagesWithRetry(chatId: String): List<MessageEntity> {
        return fetchMessagesWithDeduplication(chatId)
    }

    // Deduplicated message fetching - prevents multiple concurrent API calls for the same chat
    private suspend fun fetchMessagesWithDeduplication(chatId: String): List<MessageEntity> {
        // Check if there's already an ongoing request for this chat
        val ongoing = ongoingMessageRequests[chatId]
        if (ongoing != null && ongoing.isActive) {
            Log.d(TAG, "fetchMessagesWithDeduplication: Reusing ongoing request for chat $chatId")
            return ongoing.await()
        }

        // Start a new request and track it
        val deferred = CoroutineScope(Dispatchers.IO).async {
            try {
                // Calculate the proper 'since' timestamp based on existing messages
                val sinceString = calculateSyncTimestampString(chatId)

                Log.d(TAG, "fetchMessagesWithDeduplication: Starting new API request for chat $chatId with since=$sinceString")
                val response = apiService.getMessagesIncremental(chatId, since = sinceString)

                // Log all messages from server including cancelled status
                response.messages.forEachIndexed { index, msg ->
                    Log.d(TAG, "fetchMessagesWithDeduplication: RAW[$index] id=${msg.id} type=${msg.message_type} cancelled=${msg.cancelled} requestId=${msg.request_id} content='${msg.content.take(50)}...'")
                }

                // Filter out cancelled messages before converting to entities
                val nonCancelledMessages = response.messages.filter { it.cancelled == null }
                val cancelledMessages = response.messages.filter { it.cancelled != null }

                // Log which messages were filtered out
                if (cancelledMessages.isNotEmpty()) {
                    Log.w(TAG, "fetchMessagesWithDeduplication: Filtering out ${cancelledMessages.size} cancelled messages:")
                    cancelledMessages.forEach { msg ->
                        Log.w(TAG, "  CANCELLED: id=${msg.id} type=${msg.message_type} cancelled=${msg.cancelled} requestId=${msg.request_id} content='${msg.content.take(50)}...'")
                    }
                }

                val serverMessages = nonCancelledMessages.map { it.toMessageEntity(chatId) }
                Log.d(TAG, "fetchMessagesWithDeduplication: Retrieved ${response.messages.size} messages (${nonCancelledMessages.size} non-cancelled) for chat $chatId")

                // Deduplicate with local optimistic messages
                val deduplicatedMessages = deduplicateMessages(chatId, serverMessages)
                Log.d(TAG, "fetchMessagesWithDeduplication: After deduplication: ${deduplicatedMessages.size} messages for chat $chatId")

                deduplicatedMessages
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404) {
                    // The chat isn't on the server yet (created locally, first send pending):
                    // Room already holds everything there is.
                    Log.d(TAG, "fetchMessagesWithDeduplication: chat $chatId not on server (404), using local messages")
                    try {
                        messageDao.getMessagesForChatFlow(chatId).first()
                    } catch (localError: Exception) {
                        Log.e(TAG, "Error getting local messages for chat $chatId", localError)
                        emptyList()
                    }
                } else {
                    Log.e(TAG, "HTTP error in fetchMessagesWithDeduplication for chat $chatId: ${e.code()}", e)
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchMessagesWithDeduplication for chat $chatId", e)
                emptyList()
            } finally {
                // Clean up the tracking when done
                ongoingMessageRequests.remove(chatId)
                Log.d(TAG, "fetchMessagesWithDeduplication: Cleaned up request tracking for chat $chatId")
            }
        }

        ongoingMessageRequests[chatId] = deferred
        return deferred.await()
    }

    /**
     * Deduplicate server messages with local optimistic messages.
     *
     * Smart deduplication that:
     * 1. Matches messages by request_id first (most reliable)
     * 2. Falls back to content matching for messages without request_id
     * 3. PRESERVES local messages that haven't synced to server yet (pending messages)
     * 4. Only removes local messages when there's a matching server message
     *
     * This ensures messages sent during poor connectivity aren't lost during sync.
     */
    private suspend fun deduplicateMessages(chatId: String, serverMessages: List<MessageEntity>): List<MessageEntity> {
        try {
            // Messages need their chat row (foreign key). If it's missing, fetch it from the server.
            if (chatDao.getChatById(chatId) == null) {
                Log.d(TAG, "deduplicateMessages: Chat $chatId doesn't exist locally, fetching from server")
                try {
                    chatDao.upsert(apiService.getConversation(chatId).toChatEntity())
                    Log.d(TAG, "deduplicateMessages: Created local chat record for chat $chatId")
                } catch (e: Exception) {
                    Log.e(TAG, "deduplicateMessages: Failed to fetch chat $chatId from server; not storing its messages", e)
                    return serverMessages
                }
            }

            // Get current local messages for this chat
            val allLocalMessages = messageDao.getMessagesForChatFlow(chatId).first()
            Log.d(TAG, "deduplicateMessages: Checking ${allLocalMessages.size} local messages")

            // Smart deduplication that preserves pending messages
            // Use requestId + type as key since USER and ASSISTANT messages can share requestIds
            val serverMessagesByRequestIdAndType = serverMessages
                .filter { it.requestId != null }
                .associateBy { "${it.requestId}_${it.type}" }

            // Also keep simple requestId maps for the lookup logic
            val localMessagesByRequestId = allLocalMessages
                .filter { it.requestId != null }
                .groupBy { it.requestId!! }

            val messagesToRemove = mutableListOf<Long>()
            val serverMessagesToInsert = mutableListOf<MessageEntity>()

            // Process server messages to find duplicates and new messages
            for (serverMessage in serverMessages) {
                var foundDuplicate = false

                // First try to match by request ID (most reliable)
                // CRITICAL: Also check message type to avoid removing USER messages when ASSISTANT messages arrive
                if (serverMessage.requestId != null) {
                    // Look for local messages with the same requestId
                    val localMatches = localMessagesByRequestId[serverMessage.requestId] ?: emptyList()

                    // Find a match with the same type
                    val localMatch = localMatches.find { it.type == serverMessage.type }

                    if (localMatch != null && localMatch.id != serverMessage.id) {
                        // CRITICAL: Also verify content matches to avoid removing different messages with reused requestIds
                        if (localMatch.content.trim() == serverMessage.content.trim()) {
                            // Found a local message with same request ID, type, AND content - it's a duplicate
                            messagesToRemove.add(localMatch.id)
                            // Always ensure the server message replaces the local duplicate
                            // This prevents messages from disappearing during sync
                            serverMessagesToInsert.add(serverMessage)
                            foundDuplicate = true
                        } else {
                            // Same requestId and type but different content - not a duplicate!
                            Log.d(TAG, "deduplicateMessages: SKIPPING removal - same requestId ${serverMessage.requestId} but different content:")
                            Log.d(TAG, "  Local message ${localMatch.id}: '${localMatch.content.take(50)}...'")
                            Log.d(TAG, "  Server message ${serverMessage.id}: '${serverMessage.content.take(50)}...'")
                        }
                    } else if (localMatches.isNotEmpty() && localMatch == null) {
                        // Log when we have matches but skip due to type mismatch (for debugging)
                        val typesFound = localMatches.map { it.type }.distinct().joinToString(", ")
                        Log.d(TAG, "deduplicateMessages: Skipping removal - type mismatch: local types [$typesFound] vs server ${serverMessage.type} (requestId: ${serverMessage.requestId})")
                        // Still need to add the ASSISTANT message when there's a type mismatch
                        // This handles the case where USER message exists but ASSISTANT response needs to be added
                        if (serverMessage.type == MessageType.ASSISTANT) {
                            Log.d(TAG, "deduplicateMessages: Adding ASSISTANT message with requestId ${serverMessage.requestId} (no local ASSISTANT found)")
                            serverMessagesToInsert.add(serverMessage)
                            foundDuplicate = true // Mark as handled so we don't try to insert again
                        }
                    }
                }

                // If no requestId match, try content matching as fallback (for backward compatibility)
                if (!foundDuplicate) {
                    val duplicateLocal = allLocalMessages.find { localMsg ->
                        // Skip if this is the exact same message (same ID means it's already from server)
                        localMsg.id != serverMessage.id &&
                        localMsg.requestId == null && // Only content-match messages without requestId
                        localMsg.content.trim() == serverMessage.content.trim() &&
                        localMsg.type == serverMessage.type &&
                        // Only consider recent local messages as potential optimistic duplicates
                        (serverMessage.timestamp - localMsg.timestamp).let { timeDiff ->
                            timeDiff >= 0 && timeDiff < 60000 // Server message should be within 60 seconds after local
                        }
                    }

                    if (duplicateLocal != null) {
                        messagesToRemove.add(duplicateLocal.id)
                        // Always ensure the server message replaces the local duplicate
                        serverMessagesToInsert.add(serverMessage)
                        foundDuplicate = true
                    }
                }

                // Check if this server message already exists in database
                // Skip this check if we already added it as a replacement for a duplicate
                if (!foundDuplicate) {
                    val existingMessage = messageDao.getMessageById(serverMessage.id)
                    if (existingMessage == null) {
                        serverMessagesToInsert.add(serverMessage)
                    }
                }
            }

            // Identify local messages that need to be preserved
            val preservedLocalMessages = allLocalMessages.filter { localMsg ->
                !messagesToRemove.contains(localMsg.id) &&
                localMsg.requestId != null &&
                // Check if there's a server message with same requestId AND type
                !serverMessagesByRequestIdAndType.containsKey("${localMsg.requestId}_${localMsg.type}")
            }

            if (preservedLocalMessages.isNotEmpty()) {
                Log.d(TAG, "deduplicateMessages: Preserving ${preservedLocalMessages.size} local messages that haven't synced yet:")
                preservedLocalMessages.forEach { msg ->
                    Log.d(TAG, "  - Message ${msg.id}: '${msg.content.take(50)}...' (requestId: ${msg.requestId})")
                }
            }

            // Remove duplicate local messages
            if (messagesToRemove.isNotEmpty()) {
                messagesToRemove.forEach { messageId ->
                    messageDao.deleteMessage(messageId)
                }
                Log.d(TAG, "deduplicateMessages: Removed ${messagesToRemove.size} duplicate local messages")
            }

            // Store new server messages in database
            for (message in serverMessagesToInsert) {
                messageDao.insertMessage(message)
            }

            // Re-insert preserved local messages that might have been lost
            for (preservedMsg in preservedLocalMessages) {
                if (messageDao.getMessageById(preservedMsg.id) == null) {
                    Log.w(TAG, "deduplicateMessages: Re-inserting lost optimistic message ${preservedMsg.id}: '${preservedMsg.content.take(30)}...'")
                    messageDao.insertMessage(preservedMsg)
                }
            }

            if (serverMessagesToInsert.isEmpty() && messagesToRemove.isEmpty() && preservedLocalMessages.isEmpty()) {
                Log.d(TAG, "deduplicateMessages: No changes needed - all messages already synced")
            }

            // Return all messages for this chat (fresh from database after deduplication)
            val finalMessages = messageDao.getMessagesForChatFlow(chatId).first()
            Log.d(TAG, "deduplicateMessages: Returning ${finalMessages.size} messages from database for chat $chatId")
            return finalMessages

        } catch (e: Exception) {
            Log.e(TAG, "Error in deduplicateMessages for chat $chatId", e)
            // Fallback: just return server messages
            return serverMessages
        }
    }

    // ================== CONVERSATION FETCH ==================

    // Deduplicated conversation fetching - prevents multiple concurrent API calls
    // Uses CompletableDeferred with atomic putIfAbsent to avoid race conditions
    private suspend fun fetchConversationsWithDeduplication(
        forceFullSync: Boolean,
        useAggressiveSync: Boolean = false
    ): ConversationsFetchResult {
        val requestKey = if (forceFullSync) "full_sync" else "incremental_sync"

        while (true) {
            // Check if there's already an ongoing request
            val existing = ongoingConversationRequests[requestKey]
            if (existing != null) {
                try {
                    Log.d(TAG, "fetchConversationsWithDeduplication: Reusing ongoing $requestKey request")
                    return existing.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Request was cancelled, try again
                    Log.d(TAG, "fetchConversationsWithDeduplication: Ongoing $requestKey was cancelled, retrying")
                    continue
                }
            }

            // Create a new CompletableDeferred and try to claim ownership
            val newDeferred = CompletableDeferred<ConversationsFetchResult>()
            val wasSet = ongoingConversationRequests.putIfAbsent(requestKey, newDeferred) == null

            if (!wasSet) {
                // Another coroutine won the race, use their deferred
                Log.d(TAG, "fetchConversationsWithDeduplication: Lost race for $requestKey, using winner's deferred")
                continue
            }

            // We own this request - execute it
            Log.d(TAG, "fetchConversationsWithDeduplication: Starting new $requestKey API request (aggressive: $useAggressiveSync)")
            return try {
                val result = getAllChatsIncremental(forceFullSync, useAggressiveSync)
                Log.d(TAG, "fetchConversationsWithDeduplication: Retrieved ${result.conversations.size} conversations via $requestKey (cached=${result.isCachedData})")
                newDeferred.complete(result)
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchConversationsWithDeduplication for $requestKey", e)
                val failed = ConversationsFetchResult.failed()
                newDeferred.complete(failed)
                failed
            } finally {
                // Only remove our specific deferred (atomic remove with value check)
                ongoingConversationRequests.remove(requestKey, newDeferred)
                Log.d(TAG, "fetchConversationsWithDeduplication: Cleaned up $requestKey request tracking")
            }
        }
    }

    /**
     * Fetch conversations from the server: full (since=null) when forced or never synced,
     * otherwise incremental since the stored timestamp. Returns the raw server rows, tombstones
     * included; [syncChats] does all Room writes. On error returns [ConversationsFetchResult.failed].
     */
    private suspend fun getAllChatsIncremental(
        forceFullSync: Boolean = false,
        useAggressiveSync: Boolean = false
    ): ConversationsFetchResult {
        val requestStartedAt = System.currentTimeMillis()
        return try {
            val rawLastSync = getLastSyncTimestamp("conversations")

            // For aggressive sync, use an older timestamp to catch any timing gaps
            // This helps when new chats are created via WebSocket between sync timestamps
            val lastSync = if (forceFullSync) {
                null
            } else if (useAggressiveSync && rawLastSync != null) {
                // Go back 30 seconds to catch any potential timing gaps
                try {
                    val instant = java.time.Instant.parse(rawLastSync)
                    val olderInstant = instant.minusSeconds(30)
                    olderInstant.toString()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not parse timestamp for aggressive sync, using original: $rawLastSync")
                    rawLastSync
                }
            } else {
                rawLastSync
            }

            Log.d(TAG, "getAllChatsIncremental: lastSync = $lastSync (raw: $rawLastSync), forceFullSync = $forceFullSync, aggressive = $useAggressiveSync")

            val response = apiService.getConversationsIncremental(since = lastSync)

            val tombstoneCount = response.conversations.count { it.deleted_at != null }
            Log.d(TAG, "getAllChatsIncremental: server returned ${response.count} conversations ($tombstoneCount tombstones, incremental: ${response.is_incremental})")

            // Update sync timestamp
            updateLastSyncTimestamp("conversations", response.server_timestamp)

            ConversationsFetchResult(
                conversations = response.conversations,
                isIncremental = response.is_incremental,
                serverTimestamp = response.server_timestamp,
                isCachedData = false,
                requestStartedAt = requestStartedAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in incremental sync for chats", e)
            ConversationsFetchResult.failed(requestStartedAt)
        }
    }

    // ================== SYNC TIMESTAMP MANAGEMENT ==================

    /**
     * Get the last sync timestamp for a given entity type (conversations or messages:chatId)
     * Used by getAllChatsIncremental for conversation sync
     */
    private fun getLastSyncTimestamp(entityType: String): String? {
        return syncPrefs.getString("last_sync_$entityType", null)
    }

    /**
     * Update the last sync timestamp for a given entity type
     * Used by getAllChatsIncremental to track conversation sync progress
     */
    private fun updateLastSyncTimestamp(entityType: String, timestamp: String?) {
        if (timestamp != null) {
            syncPrefs.edit().putString("last_sync_$entityType", timestamp).apply()
        }
    }

    // ================== MESSAGE-BASED SYNC SUPPORT ==================

    /**
     * Calculate the proper 'since' timestamp for message fetching:
     * - Return timestamp of the most recent (last) user message that has a bot response after it
     * - If none exists, return timestamp of the first (earliest) user message
     * - Returns null if no messages exist (fetch all)
     */
    private suspend fun calculateMessageSyncTimestamp(chatId: String): Long? {
        try {
            val messages = messageDao.getMessagesForChatFlow(chatId).first()
            if (messages.isEmpty()) return null

            // Find the LAST (most recent) user message that has a bot response after it
            var lastUserMessageWithResponse: MessageEntity? = null
            for (i in messages.indices) {
                val msg = messages[i]
                if (msg.type == MessageType.USER) {
                    // Check if there's a bot message after this
                    val hasResponseAfter = messages.drop(i + 1).any { it.type == MessageType.ASSISTANT }
                    if (hasResponseAfter) {
                        // Keep updating to get the LAST one
                        lastUserMessageWithResponse = msg
                    }
                }
            }

            // Use the last user message with response, or first user message if none have responses
            val syncMessage = if (lastUserMessageWithResponse != null) {
                Log.d(TAG, "Using timestamp of last user message with bot response for sync: ${lastUserMessageWithResponse.timestamp}")
                lastUserMessageWithResponse
            } else {
                // No user messages have responses, use the first user message
                val firstUserMessage = messages.firstOrNull { it.type == MessageType.USER }
                if (firstUserMessage != null) {
                    Log.d(TAG, "No user messages have bot responses, using first user message timestamp: ${firstUserMessage.timestamp}")
                }
                firstUserMessage
            }

            return syncMessage?.timestamp
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating message sync timestamp for chat $chatId", e)
            return null
        }
    }

    /**
     * Calculate the proper 'since' timestamp string for API calls
     * Converts the timestamp from calculateMessageSyncTimestamp to ISO-8601 format
     * Returns null if no timestamp should be used (fetch all messages)
     */
    private suspend fun calculateSyncTimestampString(chatId: String): String? {
        val timestampMillis = calculateMessageSyncTimestamp(chatId)
        return timestampMillis?.let {
            try {
                java.time.Instant.ofEpochMilli(it).toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error converting timestamp to ISO-8601 for chat $chatId", e)
                null
            }
        }
    }

    // ================== SUBSCRIPTION ==================

    suspend fun getSubscriptionStatus(): com.example.whiz.data.local.SubscriptionStatus {
        return try {
            apiService.getSubscriptionStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting subscription status", e)
            throw e
        }
    }

    suspend fun createCheckoutSession(successUrl: String, cancelUrl: String): String {
        return try {
            val response = apiService.createCheckoutSession(
                com.example.whiz.data.api.ApiService.CreateCheckoutSessionRequest(
                    success_url = successUrl,
                    cancel_url = cancelUrl
                )
            )
            response.checkout_url
        } catch (e: Exception) {
            Log.e(TAG, "Error creating checkout session", e)
            throw e
        }
    }

    suspend fun cancelSubscription() {
        try {
            apiService.cancelSubscription()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling subscription", e)
            throw e
        }
    }
}

// Result of syncChats: the chats in Room afterwards, and whether the server fetch failed
// (so the UI can show its offline snackbar).
data class SyncResult(
    val chats: List<ChatEntity>,
    val isCachedData: Boolean
)

internal interface TransactionRunner {
    suspend fun <R> run(block: suspend () -> R): R
}

// Raw server conversations from one fetch (tombstones included). requestStartedAt bounds the
// server snapshot so a full-sync prune can spare rows created locally after it.
internal data class ConversationsFetchResult(
    val conversations: List<ApiService.ConversationResponse>,
    val isIncremental: Boolean,
    val serverTimestamp: String?,
    val isCachedData: Boolean,
    val requestStartedAt: Long
) {
    companion object {
        fun failed(requestStartedAt: Long = System.currentTimeMillis()) = ConversationsFetchResult(
            conversations = emptyList(),
            isIncremental = false,
            serverTimestamp = null,
            isCachedData = true,
            requestStartedAt = requestStartedAt
        )
    }
}
