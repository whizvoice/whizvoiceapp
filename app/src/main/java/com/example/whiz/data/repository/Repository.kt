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
    
    init {
        // Initialize reactive data loading
        setupReactiveLoading()
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
            // Use incremental sync by default
            val result = getAllChatsIncremental(forceFullSync)
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
        // Return a flow that refreshes when trigger changes and always fetches fresh data
        return _messagesRefreshTrigger.flatMapLatest {
            flow {
                try {
                    Log.d(TAG, "getMessagesForChat: fetching messages for chat $chatId from API (triggered)")
                    // Use incremental sync endpoint (without since parameter for full messages)
                    val response = apiService.getMessagesIncremental(chatId, since = null)
                    val messageEntities = response.messages.map { it.toMessageEntity() }
                    Log.d(TAG, "getMessagesForChat: retrieved ${messageEntities.size} messages for chat $chatId")
                    
                    emit(messageEntities)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting messages for chat $chatId from API", e)
                    // Emit empty list on error for now
                    emit(emptyList<MessageEntity>())
                }
            }
        }.catch { e ->
            Log.e(TAG, "Error in getMessagesForChat flow", e)
            emit(emptyList<MessageEntity>()) // Emit empty list on error
        }
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
            
            // Small delay to ensure the API call is fully processed
            delay(50)
            
            // Trigger refresh so the UI updates immediately
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
            
            // Small delay to ensure the API call is fully processed
            delay(50)
            
            // Trigger refresh so the UI updates immediately
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
        triggerConversationsRefresh()
    }

    suspend fun refreshMessages() {
        triggerMessagesRefresh()
    }

    // Force a complete refresh by clearing sync timestamps
    suspend fun forceFullRefresh() {
        Log.d(TAG, "forceFullRefresh: clearing all sync timestamps")
        syncPrefs.edit().clear().apply()
        _conversations.value = emptyList()
        triggerConversationsRefresh()
        triggerMessagesRefresh()
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
            
            val response = if (lastSync != null) {
                apiService.getConversationsIncremental(since = lastSync)
            } else {
                apiService.getConversationsIncremental(since = null)
            }
            
            Log.d("Repository", "Incremental sync returned ${response.count} conversations (incremental: ${response.is_incremental})")
            
            // Update sync timestamp
            updateLastSyncTimestamp("conversations", response.server_timestamp)
            
            val newConversations = response.conversations.map { it.toChatEntity() }
            
            // If this is incremental sync, merge with existing conversations
            if (response.is_incremental && lastSync != null) {
                val existingConversations = _conversations.value.toMutableList()
                
                // If we have no cached conversations and incremental sync returns 0, force full sync
                if (existingConversations.isEmpty() && newConversations.isEmpty()) {
                    Log.d("Repository", "Empty cache + 0 incremental results = forcing full sync")
                    return getAllChatsIncremental(forceFullSync = true)
                }
                
                // Update or add new conversations
                newConversations.forEach { newConversation ->
                    val existingIndex = existingConversations.indexOfFirst { it.id == newConversation.id }
                    if (existingIndex >= 0) {
                        // Update existing conversation
                        existingConversations[existingIndex] = newConversation
                        Log.d("Repository", "Updated existing conversation ${newConversation.id}")
                    } else {
                        // Add new conversation at the beginning (most recent first)
                        existingConversations.add(0, newConversation)
                        Log.d("Repository", "Added new conversation ${newConversation.id}")
                    }
                }
                
                // Sort by last message time (most recent first)
                val mergedConversations = existingConversations.sortedByDescending { it.lastMessageTime }
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