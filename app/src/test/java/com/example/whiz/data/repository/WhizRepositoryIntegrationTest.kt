package com.example.whiz.data.repository

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.example.whiz.MainDispatcherRule
import com.example.whiz.TestData
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.local.MessageType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.Mockito.lenient

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class WhizRepositoryIntegrationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    private lateinit var mockApiService: ApiService

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockSharedPreferences: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var repository: WhizRepository

    @Before
    fun setup() {
        lenient().`when`(mockContext.getSharedPreferences(any(), any())).thenReturn(mockSharedPreferences)
        lenient().`when`(mockSharedPreferences.edit()).thenReturn(mockEditor)
        lenient().`when`(mockEditor.putString(any(), any())).thenReturn(mockEditor)
        lenient().`when`(mockEditor.putLong(any(), any())).thenReturn(mockEditor)
        lenient().`when`(mockEditor.remove(any())).thenReturn(mockEditor)
        lenient().`when`(mockEditor.clear()).thenReturn(mockEditor)
        lenient().`when`(mockEditor.apply()).then { }

        repository = WhizRepository(mockApiService, mockContext)
    }

    @Test
    fun `createChat should call API and return chat ID`() = runTest {
        // Given
        val expectedResponse = TestData.createConversationResponse()
        whenever(mockApiService.createConversation(any())).thenReturn(expectedResponse)

        // When
        val result = repository.createChat(TestData.TEST_CHAT_TITLE)

        // Then
        assertThat(result).isEqualTo(TestData.TEST_CHAT_ID)
        verify(mockApiService).createConversation(any())
    }

    @Test
    fun `createChat should return -1 on API error`() = runTest {
        // Given
        whenever(mockApiService.createConversation(any())).thenThrow(RuntimeException("Network error"))
        
        // When
        val result = repository.createChat(TestData.TEST_CHAT_TITLE)
        
        // Then
        assertThat(result).isEqualTo(-1L)
    }

    @Test
    fun `getAllChats should fetch from API and update StateFlow`() = runTest {
        // Given
        val expectedConversations = listOf(TestData.createConversationResponse())
        val expectedResponse = TestData.createConversationsResponse(expectedConversations)
        whenever(mockApiService.getConversationsIncremental(since = null))
            .thenReturn(expectedResponse)

        // When
        val result = repository.getAllChats()

        // Then
        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo(TestData.TEST_CHAT_TITLE)
        verify(mockApiService).getConversationsIncremental(since = null)
    }

    @Test
    fun `getAllChats should return cached data on API error`() = runTest {
        // Given
        whenever(mockApiService.getConversationsIncremental(since = null))
            .thenThrow(RuntimeException("Network error"))
        whenever(mockApiService.getConversations())
            .thenThrow(RuntimeException("Fallback also failed"))
        
        // When
        val result = repository.getAllChats()
        
        // Then
        // Should not crash and return empty list (cached data)
        assertThat(result).isEmpty()
    }

    @Test
    fun `updateChatTitle should call API`() = runTest {
        // Given
        val expectedResponse = TestData.createConversationResponse(title = "Updated Title")
        whenever(mockApiService.updateConversation(eq(TestData.TEST_CHAT_ID), any()))
            .thenReturn(expectedResponse)

        // When
        repository.updateChatTitle(TestData.TEST_CHAT_ID, "Updated Title")

        // Then
        verify(mockApiService).updateConversation(eq(TestData.TEST_CHAT_ID), any())
    }

    @Test
    fun `deleteAllChats should call API and clear cache`() = runTest {
        // Given
        whenever(mockApiService.deleteAllConversations()).thenReturn(mapOf("status" to "success"))

        // When
        repository.deleteAllChats()

        // Then
        verify(mockApiService).deleteAllConversations()
    }

    @Test
    fun `addUserMessage should call API and trigger refresh`() = runTest {
        // Given
        val expectedResponse = TestData.createMessageResponse()
        whenever(mockApiService.createMessage(any())).thenReturn(expectedResponse)

        // When
        val result = repository.addUserMessage(TestData.TEST_CHAT_ID, TestData.TEST_USER_MESSAGE)

        // Then
        assertThat(result).isEqualTo(TestData.TEST_MESSAGE_ID)
        verify(mockApiService).createMessage(any())
    }

    @Test
    fun `addUserMessage should return -1 on API error`() = runTest {
        // Given
        whenever(mockApiService.createMessage(any())).thenThrow(RuntimeException("Network error"))
        
        // When
        val result = repository.addUserMessage(TestData.TEST_CHAT_ID, TestData.TEST_USER_MESSAGE)
        
        // Then
        assertThat(result).isEqualTo(-1L)
    }

    @Test
    fun `addAssistantMessage should call API with correct message type`() = runTest {
        // Given
        val expectedResponse = TestData.createMessageResponse(
            messageType = MessageType.ASSISTANT.name
        )
        whenever(mockApiService.createMessage(any())).thenReturn(expectedResponse)

        // When
        val result = repository.addAssistantMessage(TestData.TEST_CHAT_ID, TestData.TEST_ASSISTANT_MESSAGE)

        // Then
        assertThat(result).isEqualTo(TestData.TEST_MESSAGE_ID)
        verify(mockApiService).createMessage(any())
    }

    @Test
    fun `getMessagesForChat should fetch from API via Flow`() = runTest {
        // Given
        val expectedMessages = listOf(TestData.createMessageResponse())
        val expectedResponse = TestData.createMessagesResponse(expectedMessages)
        whenever(mockApiService.getMessagesIncremental(TestData.TEST_CHAT_ID, since = null))
            .thenReturn(expectedResponse)
        
        // When & Then
        repository.getMessagesForChat(TestData.TEST_CHAT_ID).test {
            val messages = awaitItem()
            assertThat(messages).hasSize(1)
            assertThat(messages[0].content).isEqualTo(TestData.TEST_USER_MESSAGE)
        }
    }

    @Test
    fun `getMessagesForChat should emit empty list on API error`() = runTest {
        // Given
        whenever(mockApiService.getMessagesIncremental(TestData.TEST_CHAT_ID, since = null))
            .thenThrow(RuntimeException("Network error"))
        
        // When & Then
        repository.getMessagesForChat(TestData.TEST_CHAT_ID).test {
            val messages = awaitItem()
            assertThat(messages).isEmpty()
        }
    }

    @Test
    fun `getMessageCountForChat should return count from API`() = runTest {
        // Given
        val expectedResponse = ApiService.MessageCountResponse(count = 5)
        whenever(mockApiService.getMessageCount(TestData.TEST_CHAT_ID)).thenReturn(expectedResponse)

        // When
        val count = repository.getMessageCountForChat(TestData.TEST_CHAT_ID)

        // Then
        assertThat(count).isEqualTo(5)
        verify(mockApiService).getMessageCount(TestData.TEST_CHAT_ID)
    }

    @Test
    fun `getMessageCountForChat should return 0 on API error`() = runTest {
        // Given
        whenever(mockApiService.getMessageCount(TestData.TEST_CHAT_ID)).thenThrow(RuntimeException("Network error"))
        
        // When
        val count = repository.getMessageCountForChat(TestData.TEST_CHAT_ID)
        
        // Then
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `performIncrementalSync should fetch and update conversations`() = runTest {
        // Given
        val expectedConversations = listOf(TestData.createConversationResponse())
        val expectedResponse = TestData.createConversationsResponse(expectedConversations)
        whenever(mockApiService.getConversationsIncremental(since = null))
            .thenReturn(expectedResponse)

        // When
        val result = repository.performIncrementalSync()

        // Then
        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo(TestData.TEST_CHAT_TITLE)
        verify(mockApiService, atLeastOnce()).getConversationsIncremental(since = null)
    }

    @Test
    fun `forceFullRefresh should clear sync timestamps and fetch all data`() = runTest {
        // Given
        val expectedConversations = listOf(TestData.createConversationResponse())
        val expectedResponse = TestData.createConversationsResponse(expectedConversations)
        whenever(mockApiService.getConversationsIncremental(since = null))
            .thenReturn(expectedResponse)

        // When
        repository.forceFullRefresh()

        // Then
        verify(mockApiService).getConversationsIncremental(since = null)
    }

    @Test
    fun `shouldPersistChat should return true when chat has 3 or more messages`() = runTest {
        // Given
        val expectedResponse = ApiService.MessageCountResponse(count = 5)
        whenever(mockApiService.getMessageCount(TestData.TEST_CHAT_ID)).thenReturn(expectedResponse)

        // When
        val shouldPersist = repository.shouldPersistChat(TestData.TEST_CHAT_ID)

        // Then
        assertThat(shouldPersist).isTrue()
    }

    @Test
    fun `shouldPersistChat should return false when chat has less than 3 messages`() = runTest {
        // Given
        val expectedResponse = ApiService.MessageCountResponse(count = 2)
        whenever(mockApiService.getMessageCount(TestData.TEST_CHAT_ID)).thenReturn(expectedResponse)

        // When
        val shouldPersist = repository.shouldPersistChat(TestData.TEST_CHAT_ID)

        // Then
        assertThat(shouldPersist).isFalse()
    }

    @Test
    fun `deriveChatTitle should use first line of multiline message`() = runTest {
        // Given
        val multilineMessage = "First line\nSecond line\nThird line"
        
        // When
        val title = repository.deriveChatTitle(multilineMessage)
        
        // Then
        assertThat(title).isEqualTo("First line")
    }

    @Test
    fun `deriveChatTitle should truncate long messages`() = runTest {
        // Given
        val longMessage = "This is a very long message that should be truncated to fit within the title length limit"
        
        // When
        val title = repository.deriveChatTitle(longMessage)
        
        // Then
        assertThat(title).isEqualTo("This is a very long ...")
    }
} 