package com.example.whiz.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.example.whiz.MainDispatcherRule
import com.example.whiz.TestData
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq

@OptIn(ExperimentalCoroutinesApi::class)
class WhizRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    private lateinit var apiService: ApiService

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var sharedPreferences: SharedPreferences
    
    @Mock
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    private lateinit var repository: WhizRepository

    // Test data
    private val testConversationResponse = ApiService.ConversationResponse(
        id = TestData.TEST_CONVERSATION_ID,
        user_id = "test_user_123",
        title = TestData.TEST_CONVERSATION_TITLE,
        created_at = "2023-01-01T00:00:00Z",
        last_message_time = "2023-01-01T00:00:00Z",
        source = "app",
        google_session_id = null
    )

    private val testMessageResponse = ApiService.MessageResponse(
        id = 1L,
        conversation_id = TestData.TEST_CONVERSATION_ID,
        content = TestData.TEST_MESSAGE_CONTENT,
        message_type = "USER",
        timestamp = "2023-01-01T00:00:00Z"
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        // Mock SharedPreferences and Editor
        whenever(context.getSharedPreferences("sync_metadata", Context.MODE_PRIVATE))
            .thenReturn(sharedPreferences)
        whenever(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor)
        whenever(sharedPreferencesEditor.putLong(any(), any())).thenReturn(sharedPreferencesEditor)
        whenever(sharedPreferencesEditor.apply()).then { }
        
        repository = WhizRepository(apiService, context)
    }

    @Test
    fun `getAllChats should return conversations from API`() = runTest {
        // Given
        val expectedConversations = listOf(testConversationResponse)
        whenever(apiService.getConversations()).thenReturn(expectedConversations)

        // When
        val result = repository.getAllChats()

        // Then
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0].id).isEqualTo(TestData.TEST_CONVERSATION_ID)
        assertThat(result[0].title).isEqualTo(TestData.TEST_CONVERSATION_TITLE)
        verify(apiService, atLeastOnce()).getConversations()
    }

    @Test
    fun `getAllChats should handle API errors gracefully`() = runTest {
        // Given
        whenever(apiService.getConversations()).thenThrow(RuntimeException("Network error"))

        // When
        val result = repository.getAllChats()

        // Then
        assertThat(result.size).isEqualTo(0)
        verify(apiService, atLeastOnce()).getConversations()
    }

    @Test
    fun `getMessagesForChat should return messages from API`() = runTest {
        // Given
        val expectedMessages = listOf(testMessageResponse)
        val incrementalResponse = ApiService.MessagesResponse(
            messages = expectedMessages,
            conversation_id = TestData.TEST_CONVERSATION_ID,
            server_timestamp = "2023-01-01T00:00:00Z",
            is_incremental = false,
            count = 1,
            has_more = false
        )
        whenever(apiService.getMessagesIncremental(TestData.TEST_CONVERSATION_ID, null, 100)).thenReturn(incrementalResponse)

        // When
        val result = repository.getMessagesForChat(TestData.TEST_CONVERSATION_ID)

        // Then
        result.test {
            // Trigger the messages refresh to make the flow emit
            repository.refreshMessages()
            
            val messages = awaitItem()
            assertThat(messages.size).isEqualTo(1)
            assertThat(messages[0].chatId).isEqualTo(TestData.TEST_CONVERSATION_ID)
            assertThat(messages[0].content).isEqualTo(TestData.TEST_MESSAGE_CONTENT)
            assertThat(messages[0].type).isEqualTo(MessageType.USER)
            
            cancelAndIgnoreRemainingEvents()
        }
        
        verify(apiService).getMessagesIncremental(TestData.TEST_CONVERSATION_ID, null, 100)
    }

    @Test
    fun `createChat should create new conversation via API`() = runTest {
        // Given
        val title = "New Chat"
        whenever(apiService.createConversation(any())).thenReturn(testConversationResponse)

        // When
        val result = repository.createChat(title)

        // Then
        assertThat(result).isEqualTo(TestData.TEST_CONVERSATION_ID)
        verify(apiService).createConversation(any())
    }

    @Test
    fun `createChat should handle API errors`() = runTest {
        // Given
        val title = "New Chat"
        whenever(apiService.createConversation(any())).thenThrow(RuntimeException("Network error"))

        // When
        val result = repository.createChat(title)

        // Then
        assertThat(result).isEqualTo(-1)
        verify(apiService).createConversation(any())
    }

    @Test
    fun `getChatById should return specific chat from API`() = runTest {
        // Given
        whenever(apiService.getConversation(TestData.TEST_CONVERSATION_ID)).thenReturn(testConversationResponse)

        // When
        val result = repository.getChatById(TestData.TEST_CONVERSATION_ID)

        // Then
        assertThat(result).isNotNull()
        assertThat(result?.id).isEqualTo(TestData.TEST_CONVERSATION_ID)
        assertThat(result?.title).isEqualTo(TestData.TEST_CONVERSATION_TITLE)
        verify(apiService).getConversation(TestData.TEST_CONVERSATION_ID)
    }

    @Test
    fun `getChatById should handle API errors`() = runTest {
        // Given
        whenever(apiService.getConversation(TestData.TEST_CONVERSATION_ID))
            .thenThrow(RuntimeException("Network error"))

        // When
        val result = repository.getChatById(TestData.TEST_CONVERSATION_ID)

        // Then
        assertThat(result).isNull()
        verify(apiService).getConversation(TestData.TEST_CONVERSATION_ID)
    }

    @Test
    fun `updateChatTitle should call API to update conversation`() = runTest {
        // Given
        val newTitle = "Updated Title"

        // When
        repository.updateChatTitle(TestData.TEST_CONVERSATION_ID, newTitle)

        // Then
        verify(apiService).updateConversation(eq(TestData.TEST_CONVERSATION_ID), any())
    }

    @Test
    fun `deleteAllChats should call API to delete all conversations`() = runTest {
        // When
        repository.deleteAllChats()

        // Then
        verify(apiService).deleteAllConversations()
    }
} 