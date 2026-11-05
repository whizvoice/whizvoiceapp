package com.example.whiz.data.api

import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.toChatEntity
import com.example.whiz.data.local.toMessageEntity
import com.example.whiz.data.local.toConversationCreate
import org.junit.Test
import org.junit.Assert.*

/**
 * Pure unit tests for ApiService - no mocks, just testing real response parsing logic.
 * This follows your friend's approach of testing real components.
 */
class ApiServiceTest {

    @Test
    fun conversationResponse_toChatEntity_mapsFieldsCorrectly() {
        // Arrange - real API response object
        val apiResponse = ApiService.ConversationResponse(
            id = 123L,
            client_chat_id = "chat-uuid-123",
            user_id = "test_user_456",
            title = "Important Business Meeting",
            created_at = "2023-12-15T10:30:00Z",
            last_message_time = "2023-12-15T10:35:00Z",
            source = "voice"
        )
        
        // Act - test real conversion logic
        val chatEntity = apiResponse.toChatEntity()
        
        // Assert - verify all fields mapped correctly
        assertEquals("Local ID should be the client chat id", "chat-uuid-123", chatEntity.id)
        assertEquals("Server ID should be mapped", 123L, chatEntity.serverId)
        assertEquals("Title should be mapped", "Important Business Meeting", chatEntity.title)
        assertTrue("Created time should be parsed and recent", chatEntity.createdAt > 0)
        assertTrue("Last message time should be parsed and recent", chatEntity.lastMessageTime > 0)
    }

    @Test
    fun messageResponse_toMessageEntity_userType() {
        // Test USER message type conversion
        val apiMessage = ApiService.MessageResponse(
            id = 456L,
            conversation_id = 789L,
            content = "Hello, can you help me with the quarterly report?",
            message_type = "USER",
            timestamp = "2023-12-15T10:30:00Z"
        )
        
        val messageEntity = apiMessage.toMessageEntity(chatId = "chat-uuid-789")

        assertEquals(0L, messageEntity.id)  // We now auto-generate IDs, not using server IDs
        assertEquals("chat-uuid-789", messageEntity.chatId)
        assertEquals("Hello, can you help me with the quarterly report?", messageEntity.content)
        assertEquals(MessageType.USER, messageEntity.type)
        assertTrue("Timestamp should be parsed", messageEntity.timestamp > 0)
    }

    @Test
    fun messageResponse_toMessageEntity_assistantType() {
        // Test ASSISTANT message type conversion
        val apiMessage = ApiService.MessageResponse(
            id = 789L,
            conversation_id = 123L,
            content = "I'd be happy to help you with the quarterly report. Let me break this down into sections...",
            message_type = "ASSISTANT", 
            timestamp = "2023-12-15T10:31:00Z"
        )
        
        val messageEntity = apiMessage.toMessageEntity(chatId = "chat-uuid-123")
        
        assertEquals(MessageType.ASSISTANT, messageEntity.type)
        assertEquals("I'd be happy to help you with the quarterly report. Let me break this down into sections...", 
                    messageEntity.content)
    }

    @Test
    fun messageResponse_toMessageEntity_unknownType_defaultsToUser() {
        // Test handling of unknown message types
        val apiMessage = ApiService.MessageResponse(
            id = 999L,
            conversation_id = 123L,
            content = "Test message with unknown type",
            message_type = "UNKNOWN_TYPE",
            timestamp = "2023-12-15T10:32:00Z"
        )
        
        val messageEntity = apiMessage.toMessageEntity(chatId = "chat-uuid-123")
        
        // Should default to USER type for unknown types
        assertEquals(MessageType.USER, messageEntity.type)
    }

    @Test
    fun timestampParsing_handlesISOFormat() {
        // Test that ISO timestamp parsing works correctly
        val testTimestamp = "2023-12-15T14:30:45Z"
        val apiMessage = ApiService.MessageResponse(
            id = 1L,
            conversation_id = 1L,
            content = "Timestamp test",
            message_type = "USER",
            timestamp = testTimestamp
        )
        
        val messageEntity = apiMessage.toMessageEntity(chatId = "chat-uuid-123")
        
        // Should be converted to Unix timestamp
        assertTrue("Timestamp should be positive Unix timestamp", messageEntity.timestamp > 0)
        // Should be reasonable recent time (after 2020)
        assertTrue("Timestamp should be recent", messageEntity.timestamp > 1577836800000L)
    }

    @Test
    fun createConversationRequest_buildsCorrectPayload() {
        // Test the reverse - creating API requests
        val title = "Weekly Status Update"
        val source = "app"
        
        val request = ApiService.ConversationCreate(
            title = title,
            client_chat_id = "chat-uuid-weekly",
            source = source
        )

        assertEquals(title, request.title)
        assertEquals("chat-uuid-weekly", request.client_chat_id)
        assertEquals(source, request.source)
    }

    @Test
    fun createMessageRequest_buildsCorrectPayload() {
        val conversationId = 456L
        val content = "What's our budget for Q1?"
        val messageType = "USER"
        
        val request = ApiService.MessageCreate(
            conversation_id = conversationId,
            content = content,
            message_type = messageType
        )
        
        assertEquals(conversationId, request.conversation_id)
        assertEquals(content, request.content)  
        assertEquals(messageType, request.message_type)
    }

    @Test
    fun entityConversions_roundTrip_preserveData() {
        // Test that we can convert API -> Entity -> API without data loss
        val originalResponse = ApiService.ConversationResponse(
            id = 100L,
            client_chat_id = "chat-uuid-100",
            user_id = "user123",
            title = "Round Trip Test",
            created_at = "2023-12-15T10:30:00Z",
            last_message_time = "2023-12-15T10:35:00Z", 
            source = "voice"
        )
        
        val chatEntity = originalResponse.toChatEntity()
        val createRequest = chatEntity.toConversationCreate()
        
        // Title should be preserved
        assertEquals(originalResponse.title, createRequest.title)
        // Client chat id should be preserved
        assertEquals(originalResponse.client_chat_id, createRequest.client_chat_id)
        // Source should default to "app" for created conversations
        assertEquals("app", createRequest.source)
    }
} 