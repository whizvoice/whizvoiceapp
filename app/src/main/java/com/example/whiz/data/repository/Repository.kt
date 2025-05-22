package com.example.whiz.data.repository

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
    private val apiService: ApiService
) {
    private val TAG = "WhizRepository"

    // Reactive state for data invalidation
    private val _conversationsRefreshTrigger = MutableStateFlow(0L)
    private val _messagesRefreshTrigger = MutableStateFlow(0L)
    
    // Current conversations cache
    private val _conversations = MutableStateFlow<List<ChatEntity>>(emptyList())
    val conversations: StateFlow<List<ChatEntity>> = _conversations.asStateFlow()

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
    fun getAllChats(): Flow<List<ChatEntity>> = _conversationsRefreshTrigger.flatMapLatest {
        flow {
            try {
                Log.d(TAG, "getAllChats: fetching from API (triggered)")
                val conversations = apiService.getConversations()
                val chatEntities = conversations.map { it.toChatEntity() }
                Log.d(TAG, "getAllChats: retrieved ${chatEntities.size} chats from API")
                _conversations.value = chatEntities // Update cache
                emit(chatEntities)
            } catch (e: Exception) {
                Log.e(TAG, "Error getting chats from API", e)
                emit(_conversations.value) // Emit cached data on error
            }
        }
    }.catch { e ->
        Log.e(TAG, "Error in getAllChats flow", e)
        emit(_conversations.value) // Emit cached data on error
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
                    val messages = apiService.getMessages(chatId)
                    val messageEntities = messages.map { it.toMessageEntity() }
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
}