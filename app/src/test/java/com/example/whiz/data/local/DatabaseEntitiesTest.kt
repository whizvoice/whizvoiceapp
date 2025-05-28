package com.example.whiz.data.local

import com.example.whiz.TestData
import com.example.whiz.data.api.ApiService
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DatabaseEntitiesTest {

    @Test
    fun `ChatEntity should have correct default values`() {
        // When
        val chatEntity = ChatEntity(
            id = TestData.TEST_CONVERSATION_ID,
            title = TestData.TEST_CONVERSATION_TITLE,
            lastMessageTime = 123456789L
        )

        // Then
        assertThat(chatEntity.id).isEqualTo(TestData.TEST_CONVERSATION_ID)
        assertThat(chatEntity.title).isEqualTo(TestData.TEST_CONVERSATION_TITLE)
        assertThat(chatEntity.lastMessageTime).isEqualTo(123456789L)
    }

    @Test
    fun `MessageEntity should have correct MessageType enum values`() {
        // When
        val userMessage = MessageEntity(
            id = 1L,
            chatId = TestData.TEST_CONVERSATION_ID,
            content = "User message",
            type = MessageType.USER,
            timestamp = 123456789L
        )

        val assistantMessage = MessageEntity(
            id = 2L,
            chatId = TestData.TEST_CONVERSATION_ID,
            content = "Assistant message",
            type = MessageType.ASSISTANT,
            timestamp = 123456790L
        )

        // Then
        assertThat(userMessage.type).isEqualTo(MessageType.USER)
        assertThat(assistantMessage.type).isEqualTo(MessageType.ASSISTANT)
    }

    @Test
    fun `ApiService ConversationResponse should convert to ChatEntity correctly`() {
        // Given
        val apiConversation = ApiService.ConversationResponse(
            id = TestData.TEST_CONVERSATION_ID,
            user_id = "test_user",
            title = TestData.TEST_CONVERSATION_TITLE,
            created_at = "2023-12-15T10:30:00Z",
            last_message_time = "2023-12-15T10:35:00Z",
            source = "voice"
        )

        // When
        val chatEntity = apiConversation.toChatEntity()

        // Then
        assertThat(chatEntity.id).isEqualTo(TestData.TEST_CONVERSATION_ID)
        assertThat(chatEntity.title).isEqualTo(TestData.TEST_CONVERSATION_TITLE)
    }

    @Test
    fun `ApiService MessageResponse should convert to MessageEntity correctly`() {
        // Given
        val apiMessage = ApiService.MessageResponse(
            id = 1L,
            conversation_id = TestData.TEST_CONVERSATION_ID,
            content = TestData.TEST_MESSAGE_CONTENT,
            message_type = "USER",
            timestamp = "2023-12-15T10:30:00Z"
        )

        // When
        val messageEntity = apiMessage.toMessageEntity()

        // Then
        assertThat(messageEntity.id).isEqualTo(1L)
        assertThat(messageEntity.chatId).isEqualTo(TestData.TEST_CONVERSATION_ID)
        assertThat(messageEntity.content).isEqualTo(TestData.TEST_MESSAGE_CONTENT)
        assertThat(messageEntity.type).isEqualTo(MessageType.USER)
    }

    @Test
    fun `ApiService MessageResponse with ASSISTANT type should convert correctly`() {
        // Given
        val apiMessage = ApiService.MessageResponse(
            id = 2L,
            conversation_id = TestData.TEST_CONVERSATION_ID,
            content = "Assistant response",
            message_type = "ASSISTANT",
            timestamp = "2023-12-15T10:30:00Z"
        )

        // When
        val messageEntity = apiMessage.toMessageEntity()

        // Then
        assertThat(messageEntity.type).isEqualTo(MessageType.ASSISTANT)
    }

    @Test
    fun `ChatEntity should convert to ConversationCreate correctly`() {
        // Given
        val chatEntity = ChatEntity(
            id = TestData.TEST_CONVERSATION_ID,
            title = TestData.TEST_CONVERSATION_TITLE,
            lastMessageTime = 123456789L
        )

        // When
        val conversationCreate = chatEntity.toConversationCreate()

        // Then
        assertThat(conversationCreate.title).isEqualTo(TestData.TEST_CONVERSATION_TITLE)
        assertThat(conversationCreate.source).isEqualTo("app")
    }

    @Test
    fun `MessageEntity should convert to MessageCreate correctly`() {
        // Given
        val messageEntity = MessageEntity(
            id = 1L,
            chatId = TestData.TEST_CONVERSATION_ID,
            content = TestData.TEST_MESSAGE_CONTENT,
            type = MessageType.USER,
            timestamp = 123456789L
        )

        // When
        val messageCreate = messageEntity.toMessageCreate()

        // Then
        assertThat(messageCreate.content).isEqualTo(TestData.TEST_MESSAGE_CONTENT)
        assertThat(messageCreate.message_type).isEqualTo("USER")
        assertThat(messageCreate.conversation_id).isEqualTo(TestData.TEST_CONVERSATION_ID)
    }

    @Test
    fun `MessageType enum should have correct string values`() {
        // When/Then
        assertThat(MessageType.USER.name).isEqualTo("USER")
        assertThat(MessageType.ASSISTANT.name).isEqualTo("ASSISTANT")
    }

    @Test
    fun `MessageEntity with empty content should be valid`() {
        // When
        val messageEntity = MessageEntity(
            id = 1L,
            chatId = TestData.TEST_CONVERSATION_ID,
            content = "",
            type = MessageType.USER,
            timestamp = 123456789L
        )

        // Then
        assertThat(messageEntity.content).isEmpty()
        assertThat(messageEntity.id).isEqualTo(1L)
    }

    @Test
    fun `ChatEntity with very long title should be valid`() {
        // Given
        val longTitle = "A".repeat(1000)

        // When
        val chatEntity = ChatEntity(
            id = TestData.TEST_CONVERSATION_ID,
            title = longTitle,
            lastMessageTime = 123456789L
        )

        // Then
        assertThat(chatEntity.title).hasLength(1000)
        assertThat(chatEntity.title).isEqualTo(longTitle)
    }
} 