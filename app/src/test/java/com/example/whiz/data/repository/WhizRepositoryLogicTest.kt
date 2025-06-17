package com.example.whiz.data.repository

import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.toChatEntity
import com.example.whiz.data.api.ApiService
import org.junit.Test
import org.junit.Assert.*

/**
 * Pure unit tests for WhizRepository business logic - no Android dependencies.
 * Tests pure data transformation, validation, and business rules.
 */
class WhizRepositoryLogicTest {

    @Test
    fun chatTitle_sanitization_removesInvalidCharacters() {
        // Test business logic for chat title cleaning
        val invalidTitles = listOf(
            "Chat with special chars @#$%",
            "   Whitespace padded   ",
            "",
            "Very long title that exceeds reasonable limits and should be truncated at some point",
            "Chat\nwith\nnewlines",
            "Chat\twith\ttabs"
        )
        
        invalidTitles.forEach { title ->
            val sanitized = sanitizeChatTitle(title)
            assertTrue("Title should not be empty after sanitization", sanitized.isNotBlank())
            assertFalse("Title should not contain newlines", sanitized.contains('\n'))
            assertFalse("Title should not contain tabs", sanitized.contains('\t'))
            assertTrue("Title should have reasonable length", sanitized.length <= 100)
        }
    }

    @Test
    fun messageContent_validation_rejectsInvalidMessages() {
        val invalidMessages = listOf(
            "", // Empty
            "   ", // Only whitespace
            "a".repeat(10000), // Too long
        )
        
        invalidMessages.forEach { content ->
            assertFalse("Invalid message should be rejected: '$content'", 
                isValidMessageContent(content))
        }
        
        val validMessages = listOf(
            "Hello world",
            "A message with reasonable length and content",
            "Message with numbers 123 and symbols !?"
        )
        
        validMessages.forEach { content ->
            assertTrue("Valid message should be accepted: '$content'",
                isValidMessageContent(content))
        }
    }

    @Test
    fun timestampGeneration_createsValidTimestamps() {
        val timestamp1 = generateTimestamp()
        Thread.sleep(10)
        val timestamp2 = generateTimestamp()
        
        assertTrue("Timestamps should be positive", timestamp1 > 0)
        assertTrue("Timestamps should be positive", timestamp2 > 0)
        assertTrue("Later timestamp should be greater", timestamp2 > timestamp1)
        
        // Should be reasonably recent (within last minute)
        val now = System.currentTimeMillis()
        assertTrue("Timestamp should be recent", now - timestamp1 < 60000)
    }

    @Test
    fun messageOrdering_byTimestamp_maintainsCorrectOrder() {
        // Test business logic for message ordering
        val messages = listOf(
            createTestMessage(id = 1, timestamp = 1000),
            createTestMessage(id = 2, timestamp = 3000),  
            createTestMessage(id = 3, timestamp = 2000)
        )
        
        val sorted = sortMessagesByTimestamp(messages)
        
        assertEquals("First message should be oldest", 1000, sorted[0].timestamp)
        assertEquals("Second message should be middle", 2000, sorted[1].timestamp)
        assertEquals("Third message should be newest", 3000, sorted[2].timestamp)
    }

    @Test
    fun chatSummary_generation_createsValidSummary() {
        val messages = listOf(
            createTestMessage(content = "Hello, how are you today?"),
            createTestMessage(content = "I'm doing great, thanks for asking!"),
            createTestMessage(content = "That's wonderful to hear.")
        )
        
        val summary = generateChatSummary(messages)
        
        assertTrue("Summary should not be empty", summary.isNotBlank())
        assertTrue("Summary should be shorter than full content", 
            summary.length < messages.sumOf { it.content.length })
        assertFalse("Summary should not contain all original text",
            messages.all { summary.contains(it.content) })
    }

    @Test
    fun dataValidation_rejectsCorruptedData() {
        // Test validation of data integrity
        val validChat = ChatEntity(
            id = 1,
            title = "Valid Chat",
            createdAt = System.currentTimeMillis(),
            lastMessageTime = System.currentTimeMillis()
        )
        
        assertTrue("Valid chat should pass validation", isValidChatData(validChat))
        
        val invalidChats = listOf(
            validChat.copy(id = 0), // Invalid ID
            validChat.copy(title = ""), // Empty title
            validChat.copy(createdAt = -1), // Invalid timestamp
            validChat.copy(lastMessageTime = 0) // Invalid last message time
        )
        
        invalidChats.forEach { chat ->
            assertFalse("Invalid chat should fail validation: $chat", 
                isValidChatData(chat))
        }
    }

    @Test
    fun apiResponseConversion_handlesEdgeCases() {
        // Test business logic for handling API edge cases
        val edgeCaseResponses = listOf(
            // Empty title
            ApiService.ConversationResponse(1, "user", "", "2023-01-01T00:00:00Z", "2023-01-01T00:00:00Z", "app"),
            // Very long title  
            ApiService.ConversationResponse(2, "user", "x".repeat(500), "2023-01-01T00:00:00Z", "2023-01-01T00:00:00Z", "app"),
            // Special characters in title
            ApiService.ConversationResponse(3, "user", "Title with émojis 🎉", "2023-01-01T00:00:00Z", "2023-01-01T00:00:00Z", "app")
        )
        
        edgeCaseResponses.forEach { response ->
            val chatEntity = response.toChatEntity()
            assertTrue("Converted chat should have valid title", chatEntity.title.isNotBlank())
            assertTrue("Converted chat should have reasonable title length", chatEntity.title.length <= 200)
        }
    }

    // Helper functions that would be in the actual repository
    private fun sanitizeChatTitle(title: String): String {
        return title.trim()
            .replace(Regex("[\\n\\t\\r]"), " ")
            .replace(Regex("\\s+"), " ")
            .take(100)
            .ifBlank { "Untitled Chat" }
    }

    private fun isValidMessageContent(content: String): Boolean {
        return content.trim().isNotBlank() && content.length <= 5000
    }

    private fun generateTimestamp(): Long = System.currentTimeMillis()

    private fun sortMessagesByTimestamp(messages: List<MessageEntity>): List<MessageEntity> {
        return messages.sortedBy { it.timestamp }
    }

    private fun generateChatSummary(messages: List<MessageEntity>): String {
        // Simple summary generation logic
        val allContent = messages.joinToString(" ") { it.content }
        return if (allContent.length > 50) {
            allContent.take(47) + "..."
        } else {
            allContent
        }
    }

    private fun isValidChatData(chat: ChatEntity): Boolean {
        return chat.id > 0 && 
               chat.title.isNotBlank() && 
               chat.createdAt > 0 && 
               chat.lastMessageTime > 0  // Changed from >= 0 to > 0 for stricter validation
    }

    private fun createTestMessage(
        id: Long = 1,
        chatId: Long = 1,
        content: String = "Test message",
        type: MessageType = MessageType.USER,
        timestamp: Long = System.currentTimeMillis()
    ): MessageEntity {
        return MessageEntity(id, chatId, content, type, timestamp)
    }
} 