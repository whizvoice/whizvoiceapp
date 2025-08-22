package com.example.whiz.data.repository

import android.content.Context
import android.util.Log
import com.example.whiz.data.ConnectionStateManager
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.toChatEntity
import com.example.whiz.data.local.toConversationCreate
import com.example.whiz.data.local.toMessageEntity
import com.example.whiz.data.local.toMessageCreate
import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WhizRepository @Inject constructor(
    private val apiService: ApiService,
    private val context: Context,
    private val messageDao: com.example.whiz.data.local.MessageDao,
    private val chatDao: com.example.whiz.data.local.ChatDao,
    private val connectionStateManager: ConnectionStateManager
) {
    private val TAG = "WhizRepository"

    // Reactive state for data invalidation
    private val _conversationsRefreshTrigger = MutableStateFlow(0L)
    private val _messagesRefreshTrigger = MutableStateFlow(0L)
    
    // Current conversations cache
    private val _conversations = MutableStateFlow<List<ChatEntity>>(emptyList())
    val conversations: StateFlow<List<ChatEntity>> = _conversations.asStateFlow()

    private val syncPrefs = context.getSharedPreferences("sync_metadata", Context.MODE_PRIVATE)
    
    // Track ongoing API requests to prevent duplicates
    private val ongoingMessageRequests = mutableMapOf<Long, kotlinx.coroutines.Deferred<List<MessageEntity>>>()
    private val ongoingConversationRequests = mutableMapOf<String, kotlinx.coroutines.Deferred<List<ChatEntity>>>()
    
    // Chat migration tracking is now handled by ConnectionStateManager
    
    // Flow to notify when a chat migration occurs (optimistic ID -> server ID)
    private val _chatMigrationEvents = MutableSharedFlow<Pair<Long, Long>>()
    val chatMigrationEvents: Flow<Pair<Long, Long>> = _chatMigrationEvents
    
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    
    // Migration locks to prevent concurrent migrations of the same chat
    private val migrationLocks = mutableMapOf<Pair<Long, Long>, Mutex>()
    private val migrationLocksMutex = Mutex()
    
    init {
        repositoryScope.launch {
            // Remove arbitrary delay - initialize immediately
            isInitialized = true
        }
        
        // Initialize reactive data loading
        setupReactiveLoading()
        
        // Trigger initial conversations load so the UI shows existing chats on app startup
        // Use a small delay to ensure the app is fully initialized
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                delay(100) // Small delay to ensure app initialization is complete
                
                // Force a full sync on app startup to ensure we get all conversations
                val conversations = getAllChatsIncremental(forceFullSync = true)
                _conversations.value = conversations
                
                // Trigger refresh to notify any observers
                triggerConversationsRefresh()
            } catch (e: Exception) {
                Log.e(TAG, "Error during initial conversations load", e)
                // Fallback to normal trigger if initial load fails
                triggerConversationsRefresh()
            }
        }
    }

    private fun setupReactiveLoading() {
        // Set up conversations loading that responds to refresh trigger
        // This approach avoids complex flow builders in the constructor
    }

    // Trigger refresh for conversations
    private fun triggerConversationsRefresh() {
        _conversationsRefreshTrigger.value = System.currentTimeMillis()
    }
    
    private fun triggerMessagesRefresh() {
        _messagesRefreshTrigger.value = System.currentTimeMillis()
    }

    // Chat operations
    suspend fun getAllChats(forceFullSync: Boolean = false): List<ChatEntity> {
        return try {
            // Use deduplication helper to prevent multiple concurrent API calls
            val result = fetchConversationsWithDeduplication(forceFullSync)
            Log.d(TAG, "🔍 getAllChats: Got ${result.size} chats from fetchConversationsWithDeduplication")
            result.forEach { chat ->
                if (chat.id > 0 && chat.id < 10000) {
                    Log.w(TAG, "🚨 WARNING: Chat ${chat.id} has suspiciously low positive ID - might be locally generated!")
                }
            }
            _conversations.value = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chats from API", e)
            // Return cached data on error
            _conversations.value
        }
    }

    suspend fun getChatById(chatId: Long): ChatEntity? {
        return try {
            val conversation = apiService.getConversation(chatId)
            val chatEntity = conversation.toChatEntity()
            chatEntity
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                if (chatId < 0) {
                    // For optimistic chats (negative IDs), fall back to local database
                    Log.d(TAG, "Chat with optimistic id $chatId not found on server (404), checking local database")
                    try {
                        chatDao.getChatById(chatId)
                    } catch (localError: Exception) {
                        Log.e(TAG, "Error getting local chat for optimistic id $chatId", localError)
                        null
                    }
                } else {
                    // For server chats, 404 means it doesn't exist
                    Log.d(TAG, "Chat with id $chatId not found (404)")
                    null
                }
            } else {
                // For other HTTP errors (500, 401, etc), throw to trigger error state
                Log.e(TAG, "HTTP error getting chat with id $chatId: ${e.code()} ${e.message()}", e)
                throw e
            }
        } catch (e: java.io.IOException) {
            // Network errors should trigger error state
            Log.e(TAG, "Network error getting chat with id $chatId", e)
            throw e
        } catch (e: Exception) {
            // Other unexpected errors should also trigger error state
            Log.e(TAG, "Unexpected error getting chat with id $chatId", e)
            throw e
        }
    }

    suspend fun createChat(title: String): Long {
        return try {
            Log.d(TAG, "createChat: creating chat with title '$title' via API")
            val createRequest = ApiService.ConversationCreate(
                title = title,
                source = "app"
            )
            val conversation = apiService.createConversation(createRequest)
            Log.d(TAG, "createChat: created chat with id ${conversation.id} and title $title")
            
            // Trigger refresh so the UI updates
            triggerConversationsRefresh()
            
            conversation.id
        } catch (e: Exception) {
            Log.e(TAG, "Error creating chat with title $title via API", e)
            -1
        }
    }

    /**
     * Create a local chat for optimistic UI - stores only in local database.
     * Used when we want immediate UI feedback before server processes the chat creation.
     * The actual server chat will be created later and synced.
     */
    suspend fun createChatOptimistic(title: String): Long {
        return try {
            Log.d(TAG, "createChatOptimistic: creating local chat with title '$title' for optimistic UI")
            
            // Use negative timestamp as unique negative ID for optimistic chats
            // This makes it clear the chat hasn't been synced with server yet
            val optimisticChatId = -System.currentTimeMillis()
            
            // Create local chat entity with negative ID
            val chatEntity = ChatEntity(
                id = optimisticChatId,
                title = title,
                createdAt = System.currentTimeMillis(),
                lastMessageTime = System.currentTimeMillis()
            )
            
            chatDao.insertChat(chatEntity)
            Log.d(TAG, "createChatOptimistic: created optimistic local chat with NEGATIVE id $optimisticChatId and title '$title'")
            
            // Don't trigger API refresh - this is just for immediate UI
            // The real chat will be created via server and synced later
            
            optimisticChatId
        } catch (e: Exception) {
            Log.e(TAG, "Error creating optimistic local chat with title '$title'", e)
            -1
        }
    }

    suspend fun updateChatTitle(chatId: Long, title: String) {
        try {
            Log.d(TAG, "updateChatTitle: updating chat $chatId title to '$title' via API")
            val updateRequest = ApiService.ConversationUpdate(title = title)
            apiService.updateConversation(chatId, updateRequest)
            Log.d(TAG, "updateChatTitle: updated chat $chatId title to $title")
            
            // Trigger refresh so the UI updates
            triggerConversationsRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId title via API", e)
        }
    }

    suspend fun updateChatLastMessageTime(chatId: Long) {
        try {
            Log.d(TAG, "updateChatLastMessageTime: updating chat $chatId last message time via API")
            apiService.updateConversationLastMessageTime(chatId)
            Log.d(TAG, "updateChatLastMessageTime: updated chat $chatId last message time")
            
            // Trigger refresh so the UI updates
            triggerConversationsRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chat $chatId last message time via API", e)
        }
    }

    suspend fun deleteChat(chatId: Long) {
        try {
            Log.d(TAG, "deleteChat: deleting chat $chatId via API")
            apiService.deleteConversation(chatId)
            Log.d(TAG, "deleteChat: deleted chat $chatId and its messages")
            
            // Use incremental sync to handle deletion via tombstone records
            // No need to clear cache - incremental sync will detect and remove the deleted conversation
            ongoingConversationRequests.clear()
            ongoingMessageRequests.clear()
            Log.d(TAG, "deleteChat: cleared request tracking for fresh incremental sync")
            
            // Trigger incremental refresh - tombstone record will remove the deleted chat
            triggerConversationsRefresh()
            triggerMessagesRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting chat $chatId via API", e)
        }
    }

    suspend fun deleteAllChats() {
        try {
            Log.d(TAG, "deleteAllChats: deleting all chats via API")
            apiService.deleteAllConversations()
            Log.d(TAG, "deleteAllChats: deleted all chats and messages")
            
            // Clear caches and trigger refresh
            _conversations.value = emptyList()
            triggerConversationsRefresh()
            triggerMessagesRefresh()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting all chats via API", e)
        }
    }

    // Message operations with reactive updates
    fun getMessagesForChat(chatId: Long): Flow<List<MessageEntity>> {
        // 🔧 SIMPLIFIED: Remove flatMapLatest to allow Room's automatic updates
        // Room will automatically emit when messages are inserted/updated/deleted
        Log.d(TAG, "🔥 REPOSITORY_DEBUG: getMessagesForChat called for chatId=$chatId")
        return messageDao.getMessagesForChatFlow(chatId)
            .catch { e ->
                Log.e(TAG, "Error in getMessagesForChat flow", e)
                emit(emptyList<MessageEntity>()) // Emit empty list on error
            }.shareIn(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                started = SharingStarted.WhileSubscribed(5000L), // Keep active for 5 seconds after last subscriber
                replay = 1 // Always replay the latest value to new subscribers
            )
    }

    suspend fun getMessageCountForChat(chatId: Long): Int {
        return try {
            val response = apiService.getMessageCount(chatId)
            response.count
        } catch (e: Exception) {
            Log.e(TAG, "Error getting message count for chat $chatId from API", e)
            0
        }
    }

    /**
     * Add user message for optimistic UI - only stores locally, doesn't make API call.
     * Used when we want immediate UI feedback before server processes the message.
     * The actual server message will be received via WebSocket and deduplicated.
     */
    suspend fun addUserMessageOptimistic(chatId: Long, content: String, requestId: String? = null): Long {
        return try {
            // Check if this chat has been migrated to a server-backed chat
            val actualChatId = getActualChatId(chatId)
            
            Log.d(TAG, "addUserMessageOptimistic: adding optimistic user message to chat $actualChatId (original: $chatId, migrated: ${actualChatId != chatId}) with requestId: $requestId")
            
            // 🔧 FIXED: Ensure chat exists locally before adding optimistic message
            // This prevents foreign key constraint failures for server-only chats
            val existingChat = chatDao.getChatById(actualChatId)
            if (existingChat == null) {
                Log.w(TAG, "addUserMessageOptimistic: Chat $actualChatId not found locally, creating placeholder chat for optimistic UI")
                // Create a minimal chat entity to satisfy foreign key constraint
                val placeholderChat = ChatEntity(
                    id = actualChatId,
                    title = "Loading...", // Will be updated when server data arrives
                    createdAt = System.currentTimeMillis(),
                    lastMessageTime = System.currentTimeMillis()
                )
                chatDao.insertChat(placeholderChat)
                Log.d(TAG, "addUserMessageOptimistic: Created placeholder chat $actualChatId for optimistic UI")
            }
            
            // Check if this message already exists to prevent duplicates
            val existingMessages = messageDao.getMessagesForChatFlow(actualChatId).first()
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
            
            // Create optimistic message entity
            val messageEntity = MessageEntity(
                id = 0,
                chatId = actualChatId,
                content = content,
                type = MessageType.USER,
                timestamp = System.currentTimeMillis(),
                requestId = requestId // 🔧 NEW: Store requestId for pairing with response
            )
            
            val messageId = messageDao.insertMessage(messageEntity)
            Log.d(TAG, "addUserMessageOptimistic: added optimistic message ${messageId} to chat $actualChatId with requestId: $requestId")
            
            // Room will automatically notify the Flow - no manual trigger needed
            
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding optimistic user message to chat $chatId: ${e.message}", e)
            -1
        }
    }

    suspend fun addAssistantMessage(chatId: Long, content: String): Long {
        return try {
            // 🔧 MIGRATION FIX: Get the actual chat ID in case of migration
            val actualChatId = getActualChatId(chatId)
            Log.d(TAG, "addAssistantMessage: adding assistant message to chat $actualChatId (original: $chatId) via API")
            val createRequest = ApiService.MessageCreate(
                conversation_id = actualChatId,
                content = content,
                message_type = MessageType.ASSISTANT.name
            )
            val message = apiService.createMessage(createRequest)
            Log.d(TAG, "addAssistantMessage: added assistant message ${message.id} to chat $actualChatId")
            
            // Remove arbitrary delay - trigger immediate refresh
            triggerMessagesRefresh()
            triggerConversationsRefresh() // Also refresh conversations for lastMessageTime
            
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
    suspend fun addAssistantMessageOptimistic(chatId: Long, content: String, requestId: String? = null): Long {
        return try {
            // 🔧 MIGRATION FIX: Get the actual chat ID in case of migration
            val actualChatId = getActualChatId(chatId)
            Log.d(TAG, "addAssistantMessageOptimistic: adding optimistic assistant message to chat $actualChatId (original: $chatId) with requestId: $requestId")
            
            // 🔧 Ensure chat exists locally before adding optimistic message
            val existingChat = chatDao.getChatById(actualChatId)
            if (existingChat == null) {
                if (actualChatId > 0) {
                    Log.e(TAG, "🚨 WARNING: Creating placeholder chat with POSITIVE ID $actualChatId - this should only happen for real server IDs!")
                    Log.e(TAG, "🚨 Stack trace:", Exception("Stack trace for positive ID creation"))
                }
                Log.w(TAG, "addAssistantMessageOptimistic: Chat $actualChatId not found locally, creating placeholder chat for optimistic UI")
                val placeholderChat = ChatEntity(
                    id = actualChatId,
                    title = "Loading...",
                    createdAt = System.currentTimeMillis(),
                    lastMessageTime = System.currentTimeMillis()
                )
                chatDao.insertChat(placeholderChat)
                Log.d(TAG, "addAssistantMessageOptimistic: Created placeholder chat $actualChatId for optimistic UI")
            }
            
            // Create optimistic assistant message entity
            val messageEntity = MessageEntity(
                id = 0,
                chatId = actualChatId,
                content = content,
                type = MessageType.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                requestId = requestId // 🔧 NEW: Store requestId for pairing with user message
            )
            
            val messageId = messageDao.insertMessage(messageEntity)
            Log.d(TAG, "addAssistantMessageOptimistic: added optimistic assistant message ${messageId} to chat $actualChatId with requestId: $requestId")
            
            // Room will automatically notify the Flow - no manual trigger needed
            
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding optimistic assistant message to chat $chatId: ${e.message}", e)
            -1
        }
    }

    /**
     * 🔧 NEW: Add assistant message after the user message with matching requestId.
     * This ensures responses appear in the correct order relative to their user messages.
     */
    suspend fun addAssistantMessageAfterRequest(chatId: Long, content: String, requestId: String): Long {
        return try {
            // 🔧 MIGRATION FIX: Get the actual chat ID in case of migration
            val actualChatId = getActualChatId(chatId)
            Log.d(TAG, "addAssistantMessageAfterRequest: adding assistant message after request $requestId in chat $actualChatId (original: $chatId)")
            
            // Find the user message with this requestId
            val userMessage = messageDao.getUserMessageByRequestId(actualChatId, requestId)
            if (userMessage != null) {
                Log.d(TAG, "addAssistantMessageAfterRequest: found user message ${userMessage.id} with requestId $requestId")
                
                // Create assistant message with timestamp after the user message
                val assistantMessage = MessageEntity(
                    id = 0,
                    chatId = actualChatId,
                    content = content,
                    type = MessageType.ASSISTANT,
                    timestamp = userMessage.timestamp + 1, // Ensure it goes after the user message
                    requestId = requestId // Link to the user message
                )
                
                val messageId = messageDao.insertMessage(assistantMessage)
                Log.d(TAG, "addAssistantMessageAfterRequest: added assistant message $messageId after user message ${userMessage.id}")
                
                // Room will automatically notify the Flow - no manual trigger needed
                
                return messageId
            } else {
                Log.w(TAG, "addAssistantMessageAfterRequest: no user message found with requestId $requestId, adding at end")
                // Fallback: add at the end if no matching user message found
                return addAssistantMessageOptimistic(actualChatId, content, requestId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding assistant message after request $requestId in chat $chatId: ${e.message}", e)
            -1
        }
    }

    /**
     * Migrate messages from one chat to another (used when server assigns different conversation_id)
     * This ensures optimistic local messages appear in the correct server conversation
     */
    suspend fun migrateChatMessages(fromChatId: Long, toChatId: Long): Boolean {
        // Get or create a lock for this specific migration
        val migrationKey = fromChatId to toChatId
        val lock = migrationLocksMutex.withLock {
            migrationLocks.getOrPut(migrationKey) { Mutex() }
        }
        
        // Try to acquire the lock - if another migration is in progress, wait for it
        return lock.withLock {
            try {
                // CRITICAL FIX: Check if messages have actually been migrated, not just registered
                // First check if the source chat still has messages
                val sourceMessages = messageDao.getMessagesForChatFlow(fromChatId).first()
                if (sourceMessages.isEmpty()) {
                    // Check if target chat has messages (indicating migration already done)
                    val targetMessages = messageDao.getMessagesForChatFlow(toChatId).first()
                    if (targetMessages.isNotEmpty()) {
                        Log.d(TAG, "migrateChatMessages: Migration from $fromChatId to $toChatId already completed - source empty, target has ${targetMessages.size} messages")
                        // Ensure registration is complete (in case it wasn't)
                        registerChatMigration(fromChatId, toChatId)
                        return@withLock true
                    }
                }
                
                Log.d(TAG, "migrateChatMessages: 🔄 STARTING migration from chat $fromChatId to chat $toChatId (${sourceMessages.size} messages to migrate)")
            
                // 🔧 DEBUG: Check all messages in database first
                try {
                    val allMessages = messageDao.getAllMessages()
                    Log.d(TAG, "migrateChatMessages: 🔍 DEBUG - Total messages in DB: ${allMessages.size}")
                    Log.d(TAG, "migrateChatMessages: 🔍 DEBUG - All messages: ${allMessages.map { "ID:${it.id} ChatID:${it.chatId} Type:${it.type} Content:'${it.content.take(30)}...'" }}")
                } catch (debugError: Exception) {
                    Log.e(TAG, "migrateChatMessages: Error getting all messages for debug", debugError)
                }

                // CRITICAL FIX: Register migration FIRST to prevent any new messages being added to the old chat
                // This tells all parts of the system to use the new chat ID immediately
                registerChatMigration(fromChatId, toChatId)
                Log.d(TAG, "migrateChatMessages: ✅ Registered migration $fromChatId → $toChatId - no new messages should be added to old chat")
            
                // 🔑 CRITICAL FIX: Ensure target chat exists before migrating messages
                val targetChatExists = chatDao.getChatById(toChatId) != null
                if (!targetChatExists) {
                    Log.d(TAG, "migrateChatMessages: 🏗️ Target chat $toChatId doesn't exist, creating it first")
                    
                    // Get the source chat to copy its title and metadata
                    val sourceChat = chatDao.getChatById(fromChatId)
                    if (sourceChat != null) {
                        val targetChat = sourceChat.copy(
                            id = toChatId,
                            lastMessageTime = System.currentTimeMillis()
                        )
                        chatDao.insertChat(targetChat)
                        Log.d(TAG, "migrateChatMessages: ✅ Created target chat $toChatId with title '${targetChat.title}'")
                    } else {
                        // Create a default chat if source doesn't exist
                        val defaultChat = ChatEntity(
                            id = toChatId,
                            title = "Chat $toChatId",
                            createdAt = System.currentTimeMillis(),
                            lastMessageTime = System.currentTimeMillis()
                        )
                        chatDao.insertChat(defaultChat)
                        Log.d(TAG, "migrateChatMessages: ✅ Created default target chat $toChatId")
                    }
                } else {
                    Log.d(TAG, "migrateChatMessages: ✅ Target chat $toChatId already exists")
                }
                
                // Get all messages from the source chat
                val messagesToMigrate = messageDao.getMessagesForChatFlow(fromChatId).first()
                Log.d(TAG, "migrateChatMessages: 📊 Found ${messagesToMigrate.size} messages to migrate from chat $fromChatId")
                
                // 🔧 RACE CONDITION FIX: Also check if any messages were already added to the target chat
                // This can happen if ConnectionStateManager returns the new chat ID while migration is in progress
                val existingTargetMessages = messageDao.getMessagesForChatFlow(toChatId).first()
                Log.d(TAG, "migrateChatMessages: 📊 Found ${existingTargetMessages.size} messages already in target chat $toChatId")
                
                if (messagesToMigrate.isNotEmpty()) {
                    Log.d(TAG, "migrateChatMessages: 📋 Messages to migrate: ${messagesToMigrate.map { "ID:${it.id} Type:${it.type} Content:'${it.content.take(30)}...'" }}")
                    
                    // 🔧 PERFORMANCE FIX: Use batch SQL update instead of individual message updates
                    // This prevents multiple Flow emissions that cause UI recompositions
                    try {
                        Log.d(TAG, "migrateChatMessages: 🚀 BATCH: Migrating ${messagesToMigrate.size} messages from chat $fromChatId to $toChatId in single operation")
                        val updateCount = messageDao.migrateChatIdForMessages(fromChatId, toChatId)
                        Log.d(TAG, "migrateChatMessages: ✅ BATCH: Successfully migrated $updateCount messages in single operation")
                        
                        // Log the final state
                        if (existingTargetMessages.isNotEmpty()) {
                            Log.d(TAG, "migrateChatMessages: ℹ️ Target chat already had ${existingTargetMessages.size} messages - these are preserved")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "migrateChatMessages: ❌ BATCH: Error in batch migration", e)
                        throw e // Re-throw to fail the migration
                    }
                } else {
                    Log.d(TAG, "migrateChatMessages: ✅ COMPLETED (no messages to migrate from chat $fromChatId)")
                    return@withLock true
                }
                val totalMigrated = messagesToMigrate.size
                Log.d(TAG, "migrateChatMessages: ✅ COMPLETED migration of $totalMigrated messages from chat $fromChatId to $toChatId")
                
                // Ensure messages actually moved
                val finalSourceMessages = messageDao.getMessagesForChatFlow(fromChatId).first()
                val finalTargetMessages = messageDao.getMessagesForChatFlow(toChatId).first()
                
                if (finalSourceMessages.isNotEmpty()) {
                    Log.e(TAG, "migrateChatMessages: ⚠️ WARNING: ${finalSourceMessages.size} messages still in source chat after migration!")
                    Log.e(TAG, "migrateChatMessages: Source messages: ${finalSourceMessages.map { "ID:${it.id} Type:${it.type}" }}")
                }
                
                if (finalTargetMessages.isEmpty() && totalMigrated > 0) {
                    Log.e(TAG, "migrateChatMessages: ❌ ERROR: Target chat has no messages after migration!")
                    return@withLock false
                }
                
                Log.d(TAG, "migrateChatMessages: ✅ Verification: source has ${finalSourceMessages.size} messages, target has ${finalTargetMessages.size} messages")
                
                // Delete the old optimistic chat
                if (fromChatId < 0) {
                    // Final check for any messages that were added AFTER migration was registered
                    // This should NEVER happen - if it does, we have a bug
                    val lastCheckMessages = messageDao.getMessagesForChatFlow(fromChatId).first()
                    if (lastCheckMessages.isNotEmpty()) {
                        Log.e(TAG, "migrateChatMessages: ❌ BUG DETECTED: ${lastCheckMessages.size} messages were added to chat $fromChatId AFTER migration was registered!")
                        Log.e(TAG, "migrateChatMessages: Messages added after migration: ${lastCheckMessages.map { "ID:${it.id} Type:${it.type} Content:'${it.content.take(30)}...'" }}")
                        
                        // This is a critical error - the system should not be adding messages to a migrated chat
                        throw IllegalStateException("Messages were added to optimistic chat $fromChatId after migration to $toChatId was registered. This indicates a race condition bug.")
                    }
                    
                    try {
                        chatDao.deleteChat(fromChatId)
                        Log.d(TAG, "migrateChatMessages: deleted optimistic chat $fromChatId")
                    } catch (e: Exception) {
                        Log.w(TAG, "migrateChatMessages: could not delete optimistic chat $fromChatId", e)
                        // Not critical - continue
                    }
                } else if (fromChatId == -1L) {
                    Log.d(TAG, "migrateChatMessages: skipping deletion of new chat placeholder (ID: $fromChatId)")
                }
                
                return@withLock true
            } catch (e: Exception) {
                Log.e(TAG, "migrateChatMessages: ❌ DETAILED ERROR migrating messages from chat $fromChatId to $toChatId", e)
                Log.e(TAG, "migrateChatMessages: ❌ Error type: ${e.javaClass.simpleName}")
                Log.e(TAG, "migrateChatMessages: ❌ Error message: ${e.message}")
                Log.e(TAG, "migrateChatMessages: ❌ Error cause: ${e.cause}")
                e.printStackTrace()
                return@withLock false
            }
        }
    }

    // Auto-save logic - now based on API call
    suspend fun shouldPersistChat(chatId: Long): Boolean {
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

    // Manual refresh functions for UI to call
    suspend fun refreshConversations() {
        // Clear ongoing conversation request tracking to allow fresh requests
        ongoingConversationRequests.clear()
        Log.d(TAG, "refreshConversations: cleared ongoing conversation request tracking")
        
        triggerConversationsRefresh()
    }

    suspend fun refreshMessages() {
        // Clear ongoing request tracking to allow fresh requests
        ongoingMessageRequests.clear()
        ongoingConversationRequests.clear()
        Log.d(TAG, "refreshMessages: cleared ongoing request tracking")
        
        triggerMessagesRefresh()
    }
    
    // Deduplicated message fetching - prevents multiple concurrent API calls for the same chat
    suspend fun fetchMessagesWithDeduplication(chatId: Long): List<MessageEntity> {
        // Check if there's already an ongoing request for this chat
        val ongoing = ongoingMessageRequests[chatId]
        if (ongoing != null && ongoing.isActive) {
            Log.d(TAG, "fetchMessagesWithDeduplication: Reusing ongoing request for chat $chatId")
            return ongoing.await()
        }
        
        // Start a new request and track it
        val deferred = CoroutineScope(Dispatchers.IO).async {
            try {
                Log.d(TAG, "fetchMessagesWithDeduplication: Starting new API request for chat $chatId")
                val response = apiService.getMessagesIncremental(chatId, since = null)
                val serverMessages = response.messages.map { it.toMessageEntity() }
                Log.d(TAG, "fetchMessagesWithDeduplication: Retrieved ${serverMessages.size} messages for chat $chatId")
                // Debug: Log the conversation IDs of the messages
                serverMessages.forEach { msg ->
                    Log.d(TAG, "fetchMessagesWithDeduplication: Message ${msg.id} has chatId=${msg.chatId}")
                }
                
                // Deduplicate with local optimistic messages
                val deduplicatedMessages = deduplicateMessages(chatId, serverMessages)
                Log.d(TAG, "fetchMessagesWithDeduplication: After deduplication: ${deduplicatedMessages.size} messages for chat $chatId")
                
                deduplicatedMessages
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 404 && chatId < 0) {
                    // For optimistic chats (negative IDs), a 404 is expected if the server hasn't created it yet
                    // First check if there's a server-backed version of this chat
                    Log.d(TAG, "fetchMessagesWithDeduplication: Got 404 for optimistic chat $chatId, checking for server-backed version")
                    
                    try {
                        // Check if we already have a migration mapping
                        val migratedChatId = connectionStateManager.getMigratedChatId(chatId)
                        if (migratedChatId != null) {
                            // We already know this chat was migrated, fetch from the server chat
                            Log.d(TAG, "fetchMessagesWithDeduplication: Found existing migration $chatId → $migratedChatId, fetching from server chat")
                            val response = apiService.getMessagesIncremental(migratedChatId, since = null)
                            val serverMessages = response.messages.map { it.toMessageEntity() }
                            Log.d(TAG, "fetchMessagesWithDeduplication: Retrieved ${serverMessages.size} messages from migrated chat $migratedChatId")
                            serverMessages
                        } else {
                            // No known migration, check if server has a chat that references this optimistic ID
                            Log.d(TAG, "fetchMessagesWithDeduplication: No known migration for $chatId, checking server conversations")
                            val conversationsResponse = apiService.getConversationsIncremental(since = null)
                            val serverChatWithOptimisticId = conversationsResponse.conversations.find { 
                                it.optimistic_chat_id?.toLongOrNull() == chatId 
                            }
                            
                            if (serverChatWithOptimisticId != null) {
                                // Found server chat that references this optimistic chat!
                                Log.d(TAG, "fetchMessagesWithDeduplication: Found server chat ${serverChatWithOptimisticId.id} that references optimistic chat $chatId")
                                
                                // Migrate messages (which will also register the migration)
                                val migrationSuccess = migrateChatMessages(chatId, serverChatWithOptimisticId.id)
                                if (!migrationSuccess) {
                                    Log.e(TAG, "fetchMessagesWithDeduplication: Failed to migrate $chatId to ${serverChatWithOptimisticId.id}")
                                }
                                
                                // Fetch messages from the server chat
                                val response = apiService.getMessagesIncremental(serverChatWithOptimisticId.id, since = null)
                                val serverMessages = response.messages.map { it.toMessageEntity() }
                                Log.d(TAG, "fetchMessagesWithDeduplication: Retrieved ${serverMessages.size} messages from server chat ${serverChatWithOptimisticId.id}")
                                
                                // Trigger message migration asynchronously
                                repositoryScope.launch {
                                    try {
                                        migrateChatMessages(chatId, serverChatWithOptimisticId.id)
                                        Log.d(TAG, "fetchMessagesWithDeduplication: Message migration completed for $chatId → ${serverChatWithOptimisticId.id}")
                                    } catch (migrationError: Exception) {
                                        Log.e(TAG, "fetchMessagesWithDeduplication: Failed to migrate messages", migrationError)
                                    }
                                }
                                
                                serverMessages
                            } else {
                                // No server chat found, fall back to local data
                                Log.d(TAG, "fetchMessagesWithDeduplication: No server chat found for optimistic chat $chatId, falling back to local data")
                                val localMessages = messageDao.getMessagesForChatFlow(chatId).first()
                                Log.d(TAG, "fetchMessagesWithDeduplication: Found ${localMessages.size} local messages for optimistic chat $chatId")
                                localMessages
                            }
                        }
                    } catch (syncError: Exception) {
                        Log.e(TAG, "Error checking for server-backed version of optimistic chat $chatId", syncError)
                        // Fall back to local data
                        try {
                            val localMessages = messageDao.getMessagesForChatFlow(chatId).first()
                            Log.d(TAG, "fetchMessagesWithDeduplication: Fallback - found ${localMessages.size} local messages for optimistic chat $chatId")
                            localMessages
                        } catch (localError: Exception) {
                            Log.e(TAG, "Error getting local messages for optimistic chat $chatId", localError)
                            emptyList()
                        }
                    }
                } else {
                    // For server chats or other HTTP errors, log and return empty
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
     * Register that an optimistic chat has been migrated to a real server chat.
     * Delegates to ConnectionStateManager as the single source of truth.
     */
    fun registerChatMigration(optimisticChatId: Long, realChatId: Long) {
        // Delegate to ConnectionStateManager
        connectionStateManager.registerChatMigration(optimisticChatId, realChatId)
        
        // Emit migration event so ChatViewModel can update its chat ID
        repositoryScope.launch {
            _chatMigrationEvents.emit(optimisticChatId to realChatId)
        }
    }
    
    /**
     * Clean up old migration mappings to prevent memory leaks.
     * Note: This is now handled by ConnectionStateManager internally.
     */
    private fun cleanupOldMigrations() {
        // Migration cleanup is now handled by ConnectionStateManager
        // This method is kept for backward compatibility but does nothing
    }
    
    /**
     * Get the optimistic chat ID that was migrated to this real chat ID.
     */
    private fun getOptimisticChatId(realChatId: Long): Long? {
        return connectionStateManager.getOptimisticChatId(realChatId)
    }
    
    /**
     * Get the actual chat ID, checking if this chat was migrated.
     * If the chat was migrated, returns the new chat ID, otherwise returns the original.
     */
    fun getActualChatId(chatId: Long): Long {
        // Delegate to ConnectionStateManager which is the single source of truth
        return connectionStateManager.getEffectiveChatId(chatId) ?: chatId
    }
    
    /**
     * Check if two chat IDs are related through migration.
     * Returns true if chatId1 was migrated to chatId2 or vice versa.
     */
    fun areChatsMigrated(chatId1: Long, chatId2: Long): Boolean {
        // Delegate to ConnectionStateManager which is the single source of truth
        return connectionStateManager.areChatsMigrated(chatId1, chatId2)
    }
    
    /**
     * Get the migrated chat ID for an optimistic chat.
     * Returns the server-backed chat ID if this optimistic chat was migrated, null otherwise.
     */
    fun getMigratedChatId(optimisticChatId: Long): Long? {
        return connectionStateManager.getMigratedChatId(optimisticChatId)
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
    private suspend fun deduplicateMessages(chatId: Long, serverMessages: List<MessageEntity>): List<MessageEntity> {
        try {
            // Use the effective chat ID from ConnectionStateManager (single source of truth)
            var effectiveChatId = connectionStateManager.getEffectiveChatId(chatId) ?: chatId
            
            // Check if server messages have a different chat ID (server resolved optimistic to real ID)
            val serverChatId = serverMessages.firstOrNull()?.chatId
            if (serverChatId != null && serverChatId != chatId && chatId < 0 && serverChatId > 0) {
                Log.d(TAG, "deduplicateMessages: Server returned messages for chat $serverChatId instead of requested $chatId - triggering migration")
                
                // This is an optimistic->real chat migration scenario
                // Migrate messages (which will also register the migration atomically)
                
                Log.d(TAG, "deduplicateMessages: Detected migration needed $chatId -> $serverChatId, performing migration")
                
                // Perform the migration (registration happens inside migrateChatMessages)
                // This handles:
                // 1. Creating the target chat if needed
                // 2. Migrating all existing messages
                val migrationSuccess = migrateChatMessages(chatId, serverChatId)
                
                if (migrationSuccess) {
                    Log.d(TAG, "deduplicateMessages: Successfully migrated chat $chatId to $serverChatId")
                    // CRITICAL FIX: Recalculate effective chat ID after migration
                    // The migration has moved all messages to the new chat ID, so we must
                    // use the new ID for all subsequent operations
                    effectiveChatId = serverChatId
                    Log.d(TAG, "deduplicateMessages: Updated effectiveChatId to $effectiveChatId after migration")
                } else {
                    Log.e(TAG, "deduplicateMessages: Failed to migrate chat $chatId to $serverChatId")
                    // Migration failed, but keep the registration since new messages should still go to the server chat
                    // Still update effectiveChatId since the registration was successful
                    effectiveChatId = serverChatId
                    Log.d(TAG, "deduplicateMessages: Migration failed but updated effectiveChatId to $effectiveChatId for consistency")
                }
            }
            
            // 🔧 FIX: Ensure chat exists in local database before inserting messages
            // Use effectiveChatId to check if the chat exists (handles migration)
            val existingChat = chatDao.getChatById(effectiveChatId)
            if (existingChat == null) {
                Log.d(TAG, "deduplicateMessages: Chat $effectiveChatId doesn't exist locally, fetching from server")
                try {
                    val serverChat = apiService.getConversation(effectiveChatId)
                    val chatEntity = serverChat.toChatEntity()
                    
                    // Check if this server chat is linked to an existing optimistic chat
                    serverChat.optimistic_chat_id?.let { optimisticIdStr ->
                        try {
                            val optimisticId = optimisticIdStr.toLong()
                            val optimisticChat = chatDao.getChatById(optimisticId)
                            if (optimisticChat != null) {
                                Log.d(TAG, "deduplicateMessages: Server chat $effectiveChatId is linked to existing optimistic chat $optimisticId")
                                // Migrate messages (which will also register the migration atomically)
                                val migrationSuccess = migrateChatMessages(optimisticId, effectiveChatId)
                                if (!migrationSuccess) {
                                    Log.e(TAG, "deduplicateMessages: Failed to migrate messages from $optimisticId to $effectiveChatId")
                                }
                            }
                        } catch (e: NumberFormatException) {
                            Log.e(TAG, "deduplicateMessages: Invalid optimistic_chat_id format: $optimisticIdStr", e)
                        }
                    }
                    
                    chatDao.insertChat(chatEntity)
                    Log.d(TAG, "deduplicateMessages: Created local chat record for chat $effectiveChatId")
                } catch (e: Exception) {
                    Log.e(TAG, "deduplicateMessages: Failed to fetch chat $effectiveChatId from server, creating placeholder", e)
                    // Create a placeholder chat record so messages can be stored
                    val placeholderChat = ChatEntity(
                        id = effectiveChatId,
                        title = "Chat $effectiveChatId",
                        lastMessageTime = System.currentTimeMillis()
                    )
                    chatDao.insertChat(placeholderChat)
                    Log.d(TAG, "deduplicateMessages: Created placeholder chat record for chat $effectiveChatId")
                }
            }
            
            // Get current local messages for this chat
            // Use effectiveChatId to get messages from the correct chat after migration
            val localMessages = messageDao.getMessagesForChatFlow(effectiveChatId).first()
            
            // Check if this real chat was migrated from an optimistic chat
            // Use effectiveChatId since it may have been updated after migration
            val optimisticChatId = getOptimisticChatId(effectiveChatId)
            val optimisticMessages = if (optimisticChatId != null) {
                Log.d(TAG, "deduplicateMessages: Found migration mapping $optimisticChatId → $effectiveChatId, checking optimistic messages")
                messageDao.getMessagesForChatFlow(optimisticChatId).first()
            } else {
                Log.d(TAG, "deduplicateMessages: No migration mapping found for chat $effectiveChatId")
                emptyList()
            }
            
            // Combine messages from both current chat and migrated optimistic chat
            val allLocalMessages = localMessages + optimisticMessages
            Log.d(TAG, "deduplicateMessages: Checking ${localMessages.size} current + ${optimisticMessages.size} optimistic = ${allLocalMessages.size} total local messages")
            
            // 🔧 IMPROVED: Smart deduplication that preserves pending messages
            // Build maps for efficient lookups
            // Use requestId + type as key since USER and ASSISTANT messages can share requestIds
            val serverMessagesByRequestIdAndType = serverMessages
                .filter { it.requestId != null }
                .associateBy { "${it.requestId}_${it.type}" }
            
            val localMessagesByRequestIdAndType = allLocalMessages
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
                        // This can happen after migration when messages from different contexts are compared
                        if (localMatch.content.trim() == serverMessage.content.trim()) {
                            // Found a local message with same request ID, type, AND content - it's a duplicate
                            Log.d(TAG, "deduplicateMessages: Found duplicate by requestId - REPLACING local ${localMatch.type} message ${localMatch.id} with server ${serverMessage.type} message ${serverMessage.id} (requestId: ${serverMessage.requestId})")
                            messagesToRemove.add(localMatch.id)
                            // CRITICAL FIX: Always ensure the server message replaces the local duplicate
                            // This prevents messages from disappearing during sync
                            serverMessagesToInsert.add(serverMessage)
                            Log.d(TAG, "deduplicateMessages: Server message ${serverMessage.id} will replace local message ${localMatch.id}")
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
                        // CRITICAL FIX: Still need to add the ASSISTANT message when there's a type mismatch
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
                        Log.d(TAG, "deduplicateMessages: Found duplicate by content - REPLACING local message ${duplicateLocal.id} with server message ${serverMessage.id}")
                        messagesToRemove.add(duplicateLocal.id)
                        // CRITICAL FIX: Always ensure the server message replaces the local duplicate
                        // This prevents messages from disappearing during sync
                        serverMessagesToInsert.add(serverMessage)
                        Log.d(TAG, "deduplicateMessages: Server message ${serverMessage.id} will replace local message ${duplicateLocal.id}")
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
            
            // 🔧 KEY IMPROVEMENT: Identify local messages that need to be preserved
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
                val insertedId = messageDao.insertMessage(message)
                Log.d(TAG, "deduplicateMessages: Inserted server message ${message.id} (ID: $insertedId) for chat ${message.chatId}")
            }
            
            // 🔧 CRITICAL FIX: Re-insert preserved local messages that might have been lost
            // This handles the case where optimistic messages with low IDs need to be kept
            for (preservedMsg in preservedLocalMessages) {
                // Check if this message still exists in the database
                val existingMsg = messageDao.getMessageById(preservedMsg.id)
                if (existingMsg == null) {
                    // Message was somehow lost, re-insert it
                    Log.w(TAG, "deduplicateMessages: Re-inserting lost optimistic message ${preservedMsg.id}: '${preservedMsg.content.take(30)}...'")
                    messageDao.insertMessage(preservedMsg)
                } else {
                    // Make sure the message is in the correct chat (in case of migration)
                    // CRITICAL: Use effectiveChatId which may have been updated after migration
                    if (existingMsg.chatId != effectiveChatId) {
                        Log.d(TAG, "deduplicateMessages: Updating message ${preservedMsg.id} to chat $effectiveChatId (was ${existingMsg.chatId})")
                        messageDao.updateMessageChatId(preservedMsg.id, effectiveChatId)
                    }
                }
            }
            
            if (serverMessagesToInsert.isEmpty() && messagesToRemove.isEmpty() && preservedLocalMessages.isEmpty()) {
                Log.d(TAG, "deduplicateMessages: No changes needed - all messages already synced")
            }
            
            // Return all messages for this chat (fresh from database after deduplication)
            // CRITICAL: Use effectiveChatId which may have been updated after migration
            val finalMessages = messageDao.getMessagesForChatFlow(effectiveChatId).first()
            Log.d(TAG, "deduplicateMessages: Returning ${finalMessages.size} messages from database for chat $effectiveChatId (original: $chatId)")
            return finalMessages
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in deduplicateMessages for chat $chatId", e)
            // Fallback: just return server messages
            return serverMessages
        }
    }
    
    // Deduplicated conversation fetching - prevents multiple concurrent API calls
    private suspend fun fetchConversationsWithDeduplication(
        forceFullSync: Boolean,
        useAggressiveSync: Boolean = false
    ): List<ChatEntity> {
        val requestKey = if (forceFullSync) "full_sync" else "incremental_sync"
        
        // Check if there's already an ongoing request
        val ongoing = ongoingConversationRequests[requestKey]
        if (ongoing != null && ongoing.isActive) {
            Log.d(TAG, "fetchConversationsWithDeduplication: Reusing ongoing $requestKey request")
            return ongoing.await()
        }
        
        // Start a new request and track it
        val deferred = CoroutineScope(Dispatchers.IO).async {
            try {
                Log.d(TAG, "fetchConversationsWithDeduplication: Starting new $requestKey API request (aggressive: $useAggressiveSync)")
                val result = getAllChatsIncremental(forceFullSync, useAggressiveSync)
                Log.d(TAG, "fetchConversationsWithDeduplication: Retrieved ${result.size} conversations via $requestKey")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchConversationsWithDeduplication for $requestKey", e)
                emptyList<ChatEntity>()
            } finally {
                // Clean up the tracking when done
                ongoingConversationRequests.remove(requestKey)
                Log.d(TAG, "fetchConversationsWithDeduplication: Cleaned up $requestKey request tracking")
            }
        }
        
        ongoingConversationRequests[requestKey] = deferred
        return deferred.await()
    }
    
    // Version that returns status about whether cached data was used
    private suspend fun fetchConversationsWithDeduplicationAndStatus(
        forceFullSync: Boolean,
        useAggressiveSync: Boolean = false
    ): SyncResult {
        val requestKey = if (forceFullSync) "full_sync" else "incremental_sync"
        
        // Check if there's already an ongoing request
        val ongoing = ongoingConversationRequests[requestKey]
        if (ongoing != null && ongoing.isActive) {
            Log.d(TAG, "fetchConversationsWithDeduplicationAndStatus: Reusing ongoing request for $requestKey")
            val result = ongoing.await()
            return SyncResult(result, isCachedData = false) // If request succeeded, it's not cached
        }
        
        // Start a new request
        val deferred = CoroutineScope(Dispatchers.IO).async {
            fetchConversationsIncrementallyWithStatus(forceFullSync, useAggressiveSync)
        }
        
        // Store a deferred that extracts just the chats for other functions that need it
        ongoingConversationRequests[requestKey] = CoroutineScope(Dispatchers.IO).async {
            try {
                deferred.await().chats
            } finally {
                // Clean up the tracking when done
                ongoingConversationRequests.remove(requestKey)
                Log.d(TAG, "fetchConversationsWithDeduplicationAndStatus: Cleaned up $requestKey request tracking")
            }
        }
        
        return deferred.await()
    }
    
    private suspend fun fetchConversationsIncrementallyWithStatus(
        forceFullSync: Boolean,
        useAggressiveSync: Boolean
    ): SyncResult {
        // Track if we successfully made an API call
        var apiCallSucceeded = false
        
        return try {
            val lastSync = if (forceFullSync) null else getLastSyncTimestamp("conversations")
            
            // For incremental sync, use a slightly older timestamp to catch any edge cases
            val effectiveSince = if (!forceFullSync && useAggressiveSync && lastSync != null) {
                try {
                    val instant = java.time.Instant.parse(lastSync)
                    val adjustedInstant = instant.minusSeconds(30)
                    adjustedInstant.toString()
                } catch (e: Exception) {
                    Log.e("Repository", "Error adjusting sync timestamp", e)
                    lastSync
                }
            } else {
                lastSync
            }
            
            Log.d("Repository", "Fetching conversations incrementally since: $effectiveSince (forceFullSync=$forceFullSync, aggressive=$useAggressiveSync)")
            
            // Try API call directly
            val response = apiService.getConversationsIncremental(since = effectiveSince)
            apiCallSucceeded = true
            
            if (forceFullSync || lastSync == null) {
                // Full sync: replace all local data
                val newConversations = response.conversations.map { it.toChatEntity() }
                Log.d(TAG, "🔍 getAllChatsIncremental FULL SYNC: Got ${newConversations.size} chats from server")
                newConversations.forEach { chat ->
                    Log.d(TAG, "  - Server chat from API: id=${chat.id}, title='${chat.title}', optimisticChatId=${chat.optimisticChatId}")
                }
                chatDao.deleteAllChats()
                newConversations.forEach { chat ->
                    chatDao.insertChat(chat)
                }
                
                // Update sync timestamp
                updateLastSyncTimestamp("conversations", response.server_timestamp)
                
                Log.d("Repository", "Full sync: returning ${newConversations.size} conversations")
                return SyncResult(newConversations, isCachedData = false)
            } else {
                // Incremental sync
                val updates = response.conversations.map { it.toChatEntity() }
                val deletedIds = response.conversations
                    .filter { it.deleted_at != null }
                    .map { it.id }
                
                // Process updates
                if (updates.isNotEmpty()) {
                    Log.d(TAG, "🔍 getAllChatsIncremental INCREMENTAL: Processing ${updates.size} updates")
                    updates.forEach { chat ->
                        Log.d(TAG, "  - Update chat from API: id=${chat.id}, title='${chat.title}', optimisticChatId=${chat.optimisticChatId}")
                        chatDao.insertChat(chat)
                    }
                    Log.d("Repository", "Incremental sync: upserted ${updates.size} conversations")
                }
                
                // Process deletions
                if (deletedIds.isNotEmpty()) {
                    deletedIds.forEach { id ->
                        chatDao.deleteChat(id)
                    }
                    Log.d("Repository", "Incremental sync: deleted ${deletedIds.size} conversations")
                }
                
                // Update sync timestamp
                updateLastSyncTimestamp("conversations", response.server_timestamp)
                
                // Get all chats from local database
                val allChats = chatDao.getAllChatsFlow().first()
                Log.d("Repository", "Incremental sync: returning ${allChats.size} total conversations")
                return SyncResult(allChats, isCachedData = false)
            }
            
        } catch (e: Exception) {
            Log.e("Repository", "Error in incremental sync for chats", e)
            // Return existing cached data on any error
            val cachedChats = _conversations.value
            Log.d("Repository", "Returning ${cachedChats.size} cached conversations due to error")
            return SyncResult(cachedChats, isCachedData = true)
        }
    }

    // Incremental sync for pull-to-refresh - actually waits for completion
    suspend fun performIncrementalSync(): SyncResult {
        Log.d(TAG, "performIncrementalSync: starting incremental sync operation")
        return try {
            // For chat list refreshes, be more aggressive about syncing new chats
            // If we have an active WebSocket connection, new chats might have been created
            // Use slightly older timestamp to ensure we capture any timing gaps
            val shouldUseAggressiveSync = true // Always use aggressive sync for chat list
            
            // Use deduplication helper for incremental sync
            val result = fetchConversationsWithDeduplicationAndStatus(
                forceFullSync = false, 
                useAggressiveSync = shouldUseAggressiveSync
            )
            _conversations.value = result.chats
            Log.d(TAG, "performIncrementalSync: completed, got ${result.chats.size} conversations, cached=${result.isCachedData}")
            
            // Trigger messages refresh for any active chat flows
            triggerMessagesRefresh()
            Log.d(TAG, "performIncrementalSync: incremental sync completed successfully")
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "performIncrementalSync: error during incremental sync", e)
            // Return cached data on error
            val cachedChats = _conversations.value
            Log.d(TAG, "performIncrementalSync: returning ${cachedChats.size} cached conversations due to error")
            SyncResult(cachedChats, isCachedData = true)
        }
    }

    // Force a complete refresh by clearing sync timestamps
    suspend fun forceFullRefresh() {
        Log.d(TAG, "forceFullRefresh: clearing all sync timestamps")
        syncPrefs.edit().clear().apply()
        _conversations.value = emptyList()
        
        Log.d(TAG, "forceFullRefresh: starting actual network sync operations...")
        try {
            // Use deduplication helper for full sync
            val conversations = fetchConversationsWithDeduplication(forceFullSync = true)
            _conversations.value = conversations
            Log.d(TAG, "forceFullRefresh: completed conversations sync, got ${conversations.size} conversations")
            
            // Trigger messages refresh for any active chat flows
            triggerMessagesRefresh()
            Log.d(TAG, "forceFullRefresh: triggered messages refresh")
            
            Log.d(TAG, "forceFullRefresh: hard sync completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "forceFullRefresh: error during hard sync", e)
            // Trigger normal refresh as fallback
            triggerConversationsRefresh()
            triggerMessagesRefresh()
            throw e // Re-throw so the UI can show the error
        }
    }

    // ================== INCREMENTAL SYNC SUPPORT ==================
    
    private fun getLastSyncTimestamp(entityType: String, entityId: Long? = null): String? {
        val key = if (entityId != null) "${entityType}_${entityId}" else entityType
        return syncPrefs.getString("last_sync_$key", null)
    }
    
    private fun updateLastSyncTimestamp(entityType: String, timestamp: String, entityId: Long? = null) {
        val key = if (entityId != null) "${entityType}_${entityId}" else entityType
        syncPrefs.edit()
            .putString("last_sync_$key", timestamp)
            .apply()
        Log.d("Repository", "Updated sync timestamp for $key: $timestamp")
    }
    
    // Modified getAllChats to support incremental sync
    suspend fun getAllChatsIncremental(
        forceFullSync: Boolean = false,
        useAggressiveSync: Boolean = false
    ): List<ChatEntity> {
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
                    Log.w("Repository", "Could not parse timestamp for aggressive sync, using original: $rawLastSync")
                    rawLastSync
                }
            } else {
                rawLastSync
            }
            
            Log.d("Repository", "getAllChatsIncremental: lastSync = $lastSync (raw: $rawLastSync), forceFullSync = $forceFullSync, aggressive = $useAggressiveSync")
            
            // If cache is empty and we're not forcing full sync, force it anyway to ensure we get all conversations
            val existingConversations = _conversations.value
            val shouldForceFullSync = forceFullSync || existingConversations.isEmpty()
            
            if (shouldForceFullSync && !forceFullSync) {
                Log.d("Repository", "Cache is empty after app restart - forcing full sync to get all conversations")
                return getAllChatsIncremental(forceFullSync = true, useAggressiveSync = useAggressiveSync)
            }
            
            val response = if (lastSync != null && !shouldForceFullSync) {
                apiService.getConversationsIncremental(since = lastSync)
            } else {
                apiService.getConversationsIncremental(since = null)
            }
            
            Log.d("Repository", "Incremental sync returned ${response.count} conversations (incremental: ${response.is_incremental})")
            
            // Update sync timestamp
            updateLastSyncTimestamp("conversations", response.server_timestamp)
            
            val newConversations = response.conversations
                .filter { it.deleted_at == null }  // Filter out soft-deleted conversations
                .map { it.toChatEntity() }
            
            // If this is incremental sync, merge with existing conversations
            if (response.is_incremental && lastSync != null && !shouldForceFullSync) {
                val existingConversationsList = existingConversations.toMutableList()
                
                // Handle both updates and deletions from incremental sync
                response.conversations.forEach { apiConversation ->
                    val existingIndex = existingConversationsList.indexOfFirst { it.id == apiConversation.id }
                    
                    if (apiConversation.deleted_at != null) {
                        // Remove deleted conversation from local cache
                        if (existingIndex >= 0) {
                            existingConversationsList.removeAt(existingIndex)
                            Log.d("Repository", "Removed deleted conversation ${apiConversation.id}")
                        }
                    } else {
                        // Update or add non-deleted conversation
                        val chatEntity = apiConversation.toChatEntity()
                        if (existingIndex >= 0) {
                            // Update existing conversation
                            existingConversationsList[existingIndex] = chatEntity
                            Log.d("Repository", "Updated existing conversation ${chatEntity.id}")
                        } else {
                            // Add new conversation at the beginning (most recent first)
                            existingConversationsList.add(0, chatEntity)
                            Log.d("Repository", "Added new conversation ${chatEntity.id}")
                        }
                    }
                }
                
                // Sort by last message time (most recent first)
                val mergedConversations = existingConversationsList.sortedByDescending { it.lastMessageTime }
                Log.d("Repository", "Merged result: ${mergedConversations.size} total conversations")
                return mergedConversations
            } else {
                // Full sync - return all conversations as-is
                Log.d("Repository", "Full sync: returning ${newConversations.size} conversations")
                return newConversations
            }
            
        } catch (e: Exception) {
            Log.e("Repository", "Error in incremental sync for chats", e)
            // Fall back to regular API call on error
            try {
                apiService.getConversations().map { it.toChatEntity() }
            } catch (fallbackException: Exception) {
                Log.e("Repository", "Fallback also failed", fallbackException)
                // Return existing cached data if both API calls fail
                _conversations.value
            }
        }
    }
    
    // Modified getMessagesForChat to support incremental sync
    suspend fun getMessagesForChatIncremental(chatId: Long, forceFullSync: Boolean = false): List<MessageEntity> {
        return try {
            val lastSync = if (forceFullSync) null else getLastSyncTimestamp("messages", chatId)
            Log.d("Repository", "getMessagesForChatIncremental: chatId=$chatId, lastSync=$lastSync, forceFullSync=$forceFullSync")
            
            val response = if (lastSync != null) {
                apiService.getMessagesIncremental(chatId, since = lastSync)
            } else {
                apiService.getMessagesIncremental(chatId, since = null)
            }
            
            Log.d("Repository", "Incremental sync returned ${response.count} messages for chat $chatId (incremental: ${response.is_incremental})")
            
            // Update sync timestamp
            updateLastSyncTimestamp("messages", response.server_timestamp, chatId)
            
            // Return the messages mapped to MessageEntity
            response.messages.map { it.toMessageEntity() }
            
        } catch (e: Exception) {
            Log.e("Repository", "Error in incremental sync for messages", e)
            // Fall back to regular API call on error
            try {
                apiService.getMessages(chatId).map { it.toMessageEntity() }
            } catch (fallbackException: Exception) {
                Log.e("Repository", "Fallback also failed", fallbackException)
                emptyList()
            }
        }
    }

    // Reactive flow version for UI
    fun getAllChatsFlow(forceFullSync: Boolean = false): Flow<List<ChatEntity>> = _conversationsRefreshTrigger.flatMapLatest {
        flow {
            try {
                // Get chats from server
                val serverChats = getAllChats(forceFullSync)
                Log.d(TAG, "🔍 getAllChatsFlow: Got ${serverChats.size} chats from server")
                serverChats.forEach { chat ->
                    Log.d(TAG, "  - Server chat: id=${chat.id}, title='${chat.title}', optimisticChatId=${chat.optimisticChatId}")
                }
                
                // Get optimistic chats from local database (IDs < -1)
                val localChats = chatDao.getAllChatsFlow().first()
                Log.d(TAG, "🔍 getAllChatsFlow: Got ${localChats.size} chats from local database")
                localChats.forEach { chat ->
                    if (chat.id > 0 && chat.id < 10000) {
                        Log.w(TAG, "🚨 LOCAL DB: Chat with positive ID ${chat.id} found - title='${chat.title}', optimisticChatId=${chat.optimisticChatId}")
                    }
                }
                val optimisticChats = localChats.filter { it.id < -1 }
                
                // Check all server chats for any that reference our optimistic chats
                serverChats.forEach { serverChat ->
                    // Check if this server chat has an optimistic_chat_id field
                    val optimisticId = serverChat.optimisticChatId
                    if (optimisticId != null && optimisticId < 0 && connectionStateManager.getMigratedChatId(optimisticId) == null) {
                        // This server chat is linked to an optimistic chat that hasn't been migrated yet
                        Log.e(TAG, "🚨 MIGRATION TRIGGER: Server chat ${serverChat.id} is linked to optimistic chat $optimisticId")
                        Log.e(TAG, "🚨 Is ${serverChat.id} a real server ID or locally generated? Stack trace:", Exception("Stack trace for migration trigger"))
                        
                        // Trigger migration asynchronously
                        repositoryScope.launch {
                            try {
                                // Migrate the messages if the optimistic chat exists
                                val optimisticChatExists = optimisticChats.any { it.id == optimisticId }
                                if (optimisticChatExists) {
                                    Log.e(TAG, "🚨 CALLING migrateChatMessages($optimisticId, ${serverChat.id})")
                                    val migrationSuccess = migrateChatMessages(optimisticId, serverChat.id)
                                    if (migrationSuccess) {
                                        Log.d(TAG, "getAllChatsFlow: Migration completed for $optimisticId -> ${serverChat.id}")
                                    } else {
                                        Log.e(TAG, "getAllChatsFlow: Migration failed for $optimisticId -> ${serverChat.id}")
                                    }
                                } else {
                                    // If optimistic chat doesn't exist, we can safely register the mapping
                                    // since there are no messages to migrate
                                    registerChatMigration(optimisticId, serverChat.id)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "getAllChatsFlow: Failed to migrate chat $optimisticId to ${serverChat.id}", e)
                            }
                        }
                    }
                }
                
                // Check which optimistic chats haven't been migrated yet
                val unmigratedOptimisticChats = optimisticChats.filter { optimisticChat ->
                    // Check if this optimistic chat has been migrated
                    val migratedId = connectionStateManager.getMigratedChatId(optimisticChat.id)
                    if (migratedId != null) {
                        // This chat has been migrated, don't include it
                        Log.d(TAG, "getAllChatsFlow: Optimistic chat ${optimisticChat.id} already migrated to $migratedId")
                        false
                    } else {
                        // Check if any server chat references this optimistic chat
                        val hasServerChat = serverChats.any { serverChat ->
                            serverChat.optimisticChatId == optimisticChat.id
                        }
                        
                        if (hasServerChat) {
                            // This optimistic chat has a server counterpart, don't include it
                            // Migration will be triggered above
                            Log.d(TAG, "getAllChatsFlow: Optimistic chat ${optimisticChat.id} has server counterpart, excluding from list")
                            false
                        } else {
                            // This chat hasn't been migrated yet and has no server counterpart in current list
                            // For now, include it. The server check will happen when the chat is opened
                            // via fetchMessagesWithDeduplication which already has the logic to find
                            // server-backed versions of optimistic chats
                            Log.d(TAG, "getAllChatsFlow: Including unmigrated optimistic chat ${optimisticChat.id}")
                            true
                        }
                    }
                }
                
                // Sort optimistic chats by timestamp (most recent first)
                val sortedOptimisticChats = unmigratedOptimisticChats.sortedByDescending { it.lastMessageTime }
                
                // Server chats are already sorted by the API
                // Combine: optimistic chats first, then server chats
                val combinedChats = sortedOptimisticChats + serverChats
                
                Log.d(TAG, "getAllChatsFlow: Returning ${sortedOptimisticChats.size} optimistic + ${serverChats.size} server = ${combinedChats.size} total chats")
                emit(combinedChats)
            } catch (e: Exception) {
                Log.e(TAG, "Error in getAllChatsFlow", e)
                emit(_conversations.value) // Emit cached data on error
            }
        }
    }.catch { e ->
        Log.e(TAG, "Error in getAllChatsFlow", e)
        emit(_conversations.value) // Emit cached data on error
    }
    
    // Expose ChatDao for testing purposes
    fun getChatDao(): com.example.whiz.data.local.ChatDao = chatDao
}

// Result class to indicate if we're returning cached data
data class SyncResult(
    val chats: List<ChatEntity>,
    val isCachedData: Boolean
)