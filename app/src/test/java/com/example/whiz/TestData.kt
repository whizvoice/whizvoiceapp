package com.example.whiz

import com.example.whiz.data.api.ApiService
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType

/**
 * Test data constants and factory methods for creating mock objects
 */
object TestData {
    
    // Test user data
    const val TEST_USER_ID = "test_user_123"
    const val TEST_USER_EMAIL = "test@example.com"
    const val TEST_USER_NAME = "Test User"
    const val TEST_CHAT_ID = 1L
    const val TEST_CONVERSATION_ID = 123L
    const val TEST_MESSAGE_ID = 1L
    
    // Test timestamps
    const val TEST_TIMESTAMP = "2024-01-15T10:30:00Z"
    const val TEST_TIMESTAMP_MILLIS = 1705316200000L
    
    // Test content
    const val TEST_CHAT_TITLE = "Test Chat"
    const val TEST_CONVERSATION_TITLE = "Test Conversation"
    const val TEST_USER_MESSAGE = "Hello, this is a test message"
    const val TEST_MESSAGE_CONTENT = "Test message content"
    const val TEST_ASSISTANT_MESSAGE = "Hello! How can I help you today?"
    
    // Factory methods for API responses
    fun createConversationResponse(
        id: Long = TEST_CHAT_ID,
        title: String = TEST_CHAT_TITLE,
        userId: String = TEST_USER_ID
    ) = ApiService.ConversationResponse(
        id = id,
        user_id = userId,
        title = title,
        created_at = TEST_TIMESTAMP,
        last_message_time = TEST_TIMESTAMP,
        source = "app",
        google_session_id = null
    )
    
    fun createMessageResponse(
        id: Long = TEST_MESSAGE_ID,
        conversationId: Long = TEST_CHAT_ID,
        content: String = TEST_USER_MESSAGE,
        messageType: String = MessageType.USER.name
    ) = ApiService.MessageResponse(
        id = id,
        conversation_id = conversationId,
        content = content,
        message_sender = messageType,
        timestamp = TEST_TIMESTAMP
    )
    
    fun createConversationsResponse(
        conversations: List<ApiService.ConversationResponse> = listOf(createConversationResponse()),
        isIncremental: Boolean = false
    ) = ApiService.ConversationsResponse(
        conversations = conversations,
        server_timestamp = TEST_TIMESTAMP,
        is_incremental = isIncremental,
        count = conversations.size
    )
    
    fun createMessagesResponse(
        messages: List<ApiService.MessageResponse> = listOf(createMessageResponse()),
        conversationId: Long = TEST_CHAT_ID,
        isIncremental: Boolean = false
    ) = ApiService.MessagesResponse(
        messages = messages,
        conversation_id = conversationId,
        server_timestamp = TEST_TIMESTAMP,
        is_incremental = isIncremental,
        count = messages.size,
        has_more = false
    )
    
    // Factory methods for local entities
    fun createChatEntity(
        id: Long = TEST_CHAT_ID,
        title: String = TEST_CHAT_TITLE
    ) = ChatEntity(
        id = id,
        title = title,
        createdAt = TEST_TIMESTAMP_MILLIS,
        lastMessageTime = TEST_TIMESTAMP_MILLIS
    )
    
    fun createMessageEntity(
        id: Long = TEST_MESSAGE_ID,
        chatId: Long = TEST_CHAT_ID,
        content: String = TEST_USER_MESSAGE,
        type: MessageType = MessageType.USER
    ) = MessageEntity(
        id = id,
        chatId = chatId,
        content = content,
        type = type,
        timestamp = TEST_TIMESTAMP_MILLIS
    )
    
    // Factory methods for API requests
    fun createConversationCreate(
        title: String = TEST_CHAT_TITLE
    ) = ApiService.ConversationCreate(
        title = title,
        source = "app"
    )
    
    fun createMessageCreate(
        conversationId: Long = TEST_CHAT_ID,
        content: String = TEST_USER_MESSAGE,
        messageType: String = MessageType.USER.name
    ) = ApiService.MessageCreate(
        conversation_id = conversationId,
        content = content,
        message_type = messageType
    )
} 