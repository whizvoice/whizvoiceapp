package com.example.whiz.data.repository

import android.content.Context
import android.util.Log
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
import retrofit2.HttpException
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WhizRepository @Inject constructor(
    private val apiService: ApiService,
    private val context: Context,
    private val messageDao: com.example.whiz.data.local.MessageDao,
    private val chatDao: com.example.whiz.data.local.ChatDao
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
    
    // Track optimistic chat ID → real chat ID migrations for deduplication
    private val chatMigrationMapping = mutableMapOf<Long, Long>()
    private val migrationTimestamps = mutableMapOf<Long, Long>() // Track when migrations happened for cleanup
    
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    
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
            // For 404, return null (chat doesn't exist)
            if (e.code() == 404) {
                Log.d(TAG, "Chat with id $chatId not found (404)")
                null
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

    suspend fun addUserMessage(chatId: Long, content: String): Long {
        return try {
            Log.d(TAG, "addUserMessage: adding user message to chat $chatId via API")
            val createRequest = ApiService.MessageCreate(
                conversation_id = chatId,
                content = content,
                message_type = MessageType.USER.name
            )
            val message = apiService.createMessage(createRequest)
            Log.d(TAG, "addUserMessage: added user message ${message.id} to chat $chatId")
            
            // Remove arbitrary delay - trigger immediate refresh
            triggerMessagesRefresh()
            triggerConversationsRefresh() // Also refresh conversations for lastMessageTime
            
            message.id
        } catch (e: Exception) {
            Log.e(TAG, "Error adding user message to chat $chatId via API", e)
            -1
        }
    }

    /**
     * Add user message for optimistic UI - only stores locally, doesn't make API call.
     * Used when we want immediate UI feedback before server processes the message.
     * The actual server message will be received via WebSocket and deduplicated.
     */
    suspend fun addUserMessageOptimistic(chatId: Long, content: String, requestId: String? = null): Long {
        return try {
            Log.d(TAG, "addUserMessageOptimistic: adding optimistic user message to chat $chatId (local only) with requestId: $requestId")
            
            // 🔧 FIXED: Ensure chat exists locally before adding optimistic message
            // This prevents foreign key constraint failures for server-only chats
            val existingChat = chatDao.getChatById(chatId)
            if (existingChat == null) {
                Log.w(TAG, "addUserMessageOptimistic: Chat $chatId not found locally, creating placeholder chat for optimistic UI")
                // Create a minimal chat entity to satisfy foreign key constraint
                val placeholderChat = ChatEntity(
                    id = chatId,
                    title = "Loading...", // Will be updated when server data arrives
                    createdAt = System.currentTimeMillis(),
                    lastMessageTime = System.currentTimeMillis()
                )
                chatDao.insertChat(placeholderChat)
                Log.d(TAG, "addUserMessageOptimistic: Created placeholder chat $chatId for optimistic UI")
            }
            
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
            
            // Create optimistic message entity
            val messageEntity = MessageEntity(
                id = 0,
                chatId = chatId,
                content = content,
                type = MessageType.USER,
                timestamp = System.currentTimeMillis(),
                requestId = requestId // 🔧 NEW: Store requestId for pairing with response
            )
            
            val messageId = messageDao.insertMessage(messageEntity)
            Log.d(TAG, "addUserMessageOptimistic: added optimistic message ${messageId} to chat $chatId with requestId: $requestId")
            
            // Room will automatically notify the Flow - no manual trigger needed
            
            messageId
        } catch (e: Exception) {
            Log.e(TAG, "Error adding optimistic user message to chat $chatId: ${e.message}", e)
            -1
        }
    }

    suspend fun addAssistantMessage(chatId: Long, content: String): Long {
        return try {
            Log.d(TAG, "addAssistantMessage: adding assistant message to chat $chatId via API")
            val createRequest = ApiService.MessageCreate(
                conversation_id = chatId,
                content = content,
                message_type = MessageType.ASSISTANT.name
            )
            val message = apiService.createMessage(createRequest)
            Log.d(TAG, "addAssistantMessage: added assistant message ${message.id} to chat $chatId")
            
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
            Log.d(TAG, "addAssistantMessageOptimistic: adding optimistic assistant message to chat $chatId (local only) with requestId: $requestId")
            
            // 🔧 Ensure chat exists locally before adding optimistic message
            val existingChat = chatDao.getChatById(chatId)
            if (existingChat == null) {
                Log.w(TAG, "addAssistantMessageOptimistic: Chat $chatId not found locally, creating placeholder chat for optimistic UI")
                val placeholderChat = ChatEntity(
                    id = chatId,
                    title = "Loading...",
                    createdAt = System.currentTimeMillis(),
                    lastMessageTime = System.currentTimeMillis()
                )
                chatDao.insertChat(placeholderChat)
                Log.d(TAG, "addAssistantMessageOptimistic: Created placeholder chat $chatId for optimistic UI")
            }
            
            // Create optimistic assistant message entity
            val messageEntity = MessageEntity(
                id = 0,
                chatId = chatId,
                content = content,
                type = MessageType.ASSISTANT,
                timestamp = System.currentTimeMillis(),
                requestId = requestId // 🔧 NEW: Store requestId for pairing with user message
            )
            
            val messageId = messageDao.insertMessage(messageEntity)
            Log.d(TAG, "addAssistantMessageOptimistic: added optimistic assistant message ${messageId} to chat $chatId with requestId: $requestId")
            
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
            Log.d(TAG, "addAssistantMessageAfterRequest: adding assistant message after request $requestId in chat $chatId")
            
            // Find the user message with this requestId
            val userMessage = messageDao.getUserMessageByRequestId(chatId, requestId)
            if (userMessage != null) {
                Log.d(TAG, "addAssistantMessageAfterRequest: found user message ${userMessage.id} with requestId $requestId")
                
                // Create assistant message with timestamp after the user message
                val assistantMessage = MessageEntity(
                    id = 0,
                    chatId = chatId,
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
                return addAssistantMessageOptimistic(chatId, content, requestId)
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
        return try {
            Log.d(TAG, "migrateChatMessages: 🔄 STARTING migration from chat $fromChatId to chat $toChatId")
            
            // 🔧 DEBUG: Check all messages in database first
            try {
                val allMessages = messageDao.getAllMessages()
                Log.d(TAG, "migrateChatMessages: 🔍 DEBUG - Total messages in DB: ${allMessages.size}")
                Log.d(TAG, "migrateChatMessages: 🔍 DEBUG - All messages: ${allMessages.map { "ID:${it.id} ChatID:${it.chatId} Type:${it.type} Content:'${it.content.take(30)}...'" }}")
            } catch (e: Exception) {
                Log.e(TAG, "migrateChatMessages: Error getting all messages for debug", e)
            }
            
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
            
            if (messagesToMigrate.isNotEmpty()) {
                Log.d(TAG, "migrateChatMessages: 📋 Messages to migrate: ${messagesToMigrate.map { "ID:${it.id} Type:${it.type} Content:'${it.content.take(30)}...'" }}")
                
                // 🔧 PERFORMANCE FIX: Use batch SQL update instead of individual message updates
                // This prevents multiple Flow emissions that cause UI recompositions
                try {
                    Log.d(TAG, "migrateChatMessages: 🚀 BATCH: Migrating ${messagesToMigrate.size} messages from chat $fromChatId to $toChatId in single operation")
                    val updateCount = messageDao.migrateChatIdForMessages(fromChatId, toChatId)
                    Log.d(TAG, "migrateChatMessages: ✅ BATCH: Successfully migrated $updateCount messages in single operation")
                } catch (e: Exception) {
                    Log.e(TAG, "migrateChatMessages: ❌ BATCH: Error in batch migration", e)
                    throw e // Re-throw to fail the migration
                }
            } else {
                Log.w(TAG, "migrateChatMessages: ⚠️ No messages found for chat $fromChatId - this might be a timing issue")
            }
            
            if (messagesToMigrate.isNotEmpty()) {
                // Delete the old optimistic chat if it was temporary (negative ID means optimistic)
                if (fromChatId < 0) {
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
                
                Log.d(TAG, "migrateChatMessages: ✅ COMPLETED migration of ${messagesToMigrate.size} messages from chat $fromChatId to $toChatId")
                return true
            } else {
                Log.d(TAG, "migrateChatMessages: ✅ COMPLETED (no messages to migrate from chat $fromChatId)")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "migrateChatMessages: ❌ DETAILED ERROR migrating messages from chat $fromChatId to $toChatId", e)
            Log.e(TAG, "migrateChatMessages: ❌ Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "migrateChatMessages: ❌ Error message: ${e.message}")
            Log.e(TAG, "migrateChatMessages: ❌ Error cause: ${e.cause}")
            e.printStackTrace()
            return false
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
                
                // Deduplicate with local optimistic messages
                val deduplicatedMessages = deduplicateMessages(chatId, serverMessages)
                Log.d(TAG, "fetchMessagesWithDeduplication: After deduplication: ${deduplicatedMessages.size} messages for chat $chatId")
                
                deduplicatedMessages
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
     * This mapping is used for cross-chat deduplication.
     */
    fun registerChatMigration(optimisticChatId: Long, realChatId: Long) {
        if (optimisticChatId >= 0) {
            Log.w(TAG, "registerChatMigration: optimisticChatId $optimisticChatId is not negative, ignoring")
            return
        }
        if (realChatId <= 0) {
            Log.w(TAG, "registerChatMigration: realChatId $realChatId is not positive, ignoring")
            return
        }
        
        chatMigrationMapping[optimisticChatId] = realChatId
        migrationTimestamps[optimisticChatId] = System.currentTimeMillis()
        Log.d(TAG, "registerChatMigration: Registered migration $optimisticChatId → $realChatId")
        
        // Clean up old mappings (older than 1 hour)
        cleanupOldMigrations()
    }
    
    /**
     * Clean up old migration mappings to prevent memory leaks.
     */
    private fun cleanupOldMigrations() {
        val oneHourAgo = System.currentTimeMillis() - 3600000 // 1 hour
        val toRemove = migrationTimestamps.filterValues { it < oneHourAgo }.keys
        toRemove.forEach { optimisticChatId ->
            chatMigrationMapping.remove(optimisticChatId)
            migrationTimestamps.remove(optimisticChatId)
        }
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "cleanupOldMigrations: Cleaned up ${toRemove.size} old migration mappings")
        }
    }
    
    /**
     * Get the optimistic chat ID that was migrated to this real chat ID.
     */
    private fun getOptimisticChatId(realChatId: Long): Long? {
        return chatMigrationMapping.entries.find { it.value == realChatId }?.key
    }

    /**
     * Deduplicate server messages with local optimistic messages.
     * Removes local optimistic messages that have corresponding server messages.
     */
    private suspend fun deduplicateMessages(chatId: Long, serverMessages: List<MessageEntity>): List<MessageEntity> {
        try {
            // 🔧 FIX: Ensure chat exists in local database before inserting messages
            val existingChat = chatDao.getChatById(chatId)
            if (existingChat == null) {
                Log.d(TAG, "deduplicateMessages: Chat $chatId doesn't exist locally, fetching from server")
                try {
                    val serverChat = apiService.getConversation(chatId)
                    val chatEntity = serverChat.toChatEntity()
                    chatDao.insertChat(chatEntity)
                    Log.d(TAG, "deduplicateMessages: Created local chat record for chat $chatId")
                } catch (e: Exception) {
                    Log.e(TAG, "deduplicateMessages: Failed to fetch chat $chatId from server, creating placeholder", e)
                    // Create a placeholder chat record so messages can be stored
                    val placeholderChat = ChatEntity(
                        id = chatId,
                        title = "Chat $chatId",
                        lastMessageTime = System.currentTimeMillis()
                    )
                    chatDao.insertChat(placeholderChat)
                    Log.d(TAG, "deduplicateMessages: Created placeholder chat record for chat $chatId")
                }
            }
            
            // Get current local messages for this chat
            val localMessages = messageDao.getMessagesForChatFlow(chatId).first()
            
            // Check if this real chat was migrated from an optimistic chat
            val optimisticChatId = getOptimisticChatId(chatId)
            val optimisticMessages = if (optimisticChatId != null) {
                Log.d(TAG, "deduplicateMessages: Found migration mapping $optimisticChatId → $chatId, checking optimistic messages")
                messageDao.getMessagesForChatFlow(optimisticChatId).first()
            } else {
                Log.d(TAG, "deduplicateMessages: No migration mapping found for chat $chatId")
                emptyList()
            }
            
            // Combine messages from both current chat and migrated optimistic chat
            val allLocalMessages = localMessages + optimisticMessages
            Log.d(TAG, "deduplicateMessages: Checking ${localMessages.size} current + ${optimisticMessages.size} optimistic = ${allLocalMessages.size} total local messages")
            
            // Find optimistic messages that have server counterparts
            val messagesToRemove = mutableListOf<Long>()
            
            for (serverMessage in serverMessages) {
                // Look for local messages with same content and type that could be duplicates
                val duplicateLocal = allLocalMessages.find { localMsg ->
                    localMsg.content.trim() == serverMessage.content.trim() &&
                    localMsg.type == serverMessage.type &&
                    // Only consider recent local messages as potential optimistic duplicates
                    (serverMessage.timestamp - localMsg.timestamp).let { timeDiff ->
                        timeDiff >= 0 && timeDiff < 60000 // Server message should be within 60 seconds after local
                    }
                }
                
                if (duplicateLocal != null) {
                    Log.d(TAG, "deduplicateMessages: Found duplicate - removing local message ${duplicateLocal.id} (from chat ${duplicateLocal.chatId}) for server message ${serverMessage.id}")
                    messagesToRemove.add(duplicateLocal.id)
                }
            }
            
            // Remove duplicate local messages
            if (messagesToRemove.isNotEmpty()) {
                messagesToRemove.forEach { messageId ->
                    messageDao.deleteMessage(messageId)
                }
                Log.d(TAG, "deduplicateMessages: Removed ${messagesToRemove.size} duplicate local messages")
            }
            
            // Store server messages in database
            serverMessages.forEach { serverMessage ->
                messageDao.insertMessage(serverMessage)
            }
            
            // Return all messages for this chat (fresh from database after deduplication)
            return messageDao.getMessagesForChatFlow(chatId).first()
            
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

    // Incremental sync for pull-to-refresh - actually waits for completion
    suspend fun performIncrementalSync(): List<ChatEntity> {
        Log.d(TAG, "performIncrementalSync: starting incremental sync operation")
        return try {
            // For chat list refreshes, be more aggressive about syncing new chats
            // If we have an active WebSocket connection, new chats might have been created
            // Use slightly older timestamp to ensure we capture any timing gaps
            val shouldUseAggressiveSync = true // Always use aggressive sync for chat list
            
            // Use deduplication helper for incremental sync
            val conversations = fetchConversationsWithDeduplication(
                forceFullSync = false, 
                useAggressiveSync = shouldUseAggressiveSync
            )
            _conversations.value = conversations
            Log.d(TAG, "performIncrementalSync: completed, got ${conversations.size} conversations")
            
            // Trigger messages refresh for any active chat flows
            triggerMessagesRefresh()
            Log.d(TAG, "performIncrementalSync: incremental sync completed successfully")
            
            conversations
        } catch (e: Exception) {
            Log.e(TAG, "performIncrementalSync: error during incremental sync", e)
            // Trigger normal refresh as fallback
            triggerConversationsRefresh()
            triggerMessagesRefresh()
            throw e // Re-throw so the UI can handle the error
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
                val result = getAllChats(forceFullSync)
                emit(result)
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