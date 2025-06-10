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

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WhizRepository @Inject constructor(
    private val apiService: ApiService,
    private val context: Context
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
    
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    
    init {
        repositoryScope.launch {
            // Remove arbitrary delay - initialize immediately
            Log.d(TAG, "Repository initialized")
            isInitialized = true
        }
        
        // Initialize reactive data loading
        setupReactiveLoading()
        
        // Trigger initial conversations load so the UI shows existing chats on app startup
        // Use a small delay to ensure the app is fully initialized
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                delay(100) // Small delay to ensure app initialization is complete
                Log.d(TAG, "Repository init: Starting initial conversations load")
                
                // Force a full sync on app startup to ensure we get all conversations
                val conversations = getAllChatsIncremental(forceFullSync = true)
                _conversations.value = conversations
                Log.d(TAG, "Repository init: Loaded ${conversations.size} conversations on startup")
                
                // Trigger refresh to notify any observers
                triggerConversationsRefresh()
            } catch (e: Exception) {
                Log.e(TAG, "Repository init: Error during initial conversations load", e)
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
        Log.d(TAG, "Triggered conversations refresh")
    }

    // Trigger refresh for messages
    private fun triggerMessagesRefresh() {
        val newValue = System.currentTimeMillis()
        _messagesRefreshTrigger.value = newValue
        Log.d(TAG, "Triggered messages refresh with value: $newValue")
    }

    // Chat operations
    suspend fun getAllChats(forceFullSync: Boolean = false): List<ChatEntity> {
        Log.d(TAG, "getAllChats: fetching from API (triggered)")
        return try {
            // Use deduplication helper to prevent multiple concurrent API calls
            val result = fetchConversationsWithDeduplication(forceFullSync)
            _conversations.value = result
            Log.d(TAG, "getAllChats: retrieved ${result.size} chats from API")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chats from API", e)
            // Return cached data on error
            _conversations.value
        }
    }

    suspend fun getChatById(chatId: Long): ChatEntity? {
        return try {
            Log.d(TAG, "getChatById: fetching chat $chatId from API")
            val conversation = apiService.getConversation(chatId)
            val chatEntity = conversation.toChatEntity()
            Log.d(TAG, "getChatById: retrieved chat with id $chatId: ${chatEntity.title}")
            chatEntity
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat with id $chatId from API", e)
            null
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
        return _messagesRefreshTrigger.flatMapLatest { triggerValue ->
            flow {
                Log.d(TAG, "getMessagesForChat: Flow triggered for chat $chatId (trigger: $triggerValue)")
                // Use deduplication helper to prevent multiple concurrent API calls
                val messageEntities = fetchMessagesWithDeduplication(chatId)
                emit(messageEntities)
            }
        }.catch { e ->
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
            Log.d(TAG, "getMessageCountForChat: fetching count for chat $chatId from API")
            val response = apiService.getMessageCount(chatId)
            Log.d(TAG, "getMessageCountForChat: chat $chatId has ${response.count} messages")
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
    private suspend fun fetchMessagesWithDeduplication(chatId: Long): List<MessageEntity> {
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
                val messageEntities = response.messages.map { it.toMessageEntity() }
                Log.d(TAG, "fetchMessagesWithDeduplication: Retrieved ${messageEntities.size} messages for chat $chatId")
                messageEntities
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchMessagesWithDeduplication for chat $chatId", e)
                emptyList<MessageEntity>()
            } finally {
                // Clean up the tracking when done
                ongoingMessageRequests.remove(chatId)
                Log.d(TAG, "fetchMessagesWithDeduplication: Cleaned up request tracking for chat $chatId")
            }
        }
        
        ongoingMessageRequests[chatId] = deferred
        return deferred.await()
    }

    // Deduplicated conversation fetching - prevents multiple concurrent API calls
    private suspend fun fetchConversationsWithDeduplication(forceFullSync: Boolean): List<ChatEntity> {
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
                Log.d(TAG, "fetchConversationsWithDeduplication: Starting new $requestKey API request")
                val result = getAllChatsIncremental(forceFullSync)
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
            // Use deduplication helper for incremental sync
            val conversations = fetchConversationsWithDeduplication(forceFullSync = false)
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
    suspend fun getAllChatsIncremental(forceFullSync: Boolean = false): List<ChatEntity> {
        return try {
            val lastSync = if (forceFullSync) null else getLastSyncTimestamp("conversations")
            Log.d("Repository", "getAllChatsIncremental: lastSync = $lastSync, forceFullSync = $forceFullSync")
            
            // If cache is empty and we're not forcing full sync, force it anyway to ensure we get all conversations
            val existingConversations = _conversations.value
            val shouldForceFullSync = forceFullSync || existingConversations.isEmpty()
            
            if (shouldForceFullSync && !forceFullSync) {
                Log.d("Repository", "Cache is empty after app restart - forcing full sync to get all conversations")
                return getAllChatsIncremental(forceFullSync = true)
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
}