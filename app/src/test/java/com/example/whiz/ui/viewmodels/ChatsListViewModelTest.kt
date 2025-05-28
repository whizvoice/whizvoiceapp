package com.example.whiz.ui.viewmodels

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.whiz.MainDispatcherRule
import com.example.whiz.TestData
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.repository.WhizRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsListViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock
    private lateinit var repository: WhizRepository

    private lateinit var viewModel: ChatsListViewModel

    // Test data
    private val testChats = listOf(
        ChatEntity(
            id = 1L,
            title = "Test Chat 1",
            lastMessageTime = System.currentTimeMillis()
        ),
        ChatEntity(
            id = 2L,
            title = "Test Chat 2",
            lastMessageTime = System.currentTimeMillis() - 1000
        )
    )

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        
        // Setup default mock behavior to prevent initialization issues
        whenever(repository.getAllChatsFlow()).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.conversations).thenReturn(MutableStateFlow(testChats))
    }

    @Test
    fun `createNewChat should return chat ID from repository`() = runTest {
        // Given
        val expectedChatId = 123L
        val chatTitle = "New Test Chat"
        whenever(repository.createChat(chatTitle)).thenReturn(expectedChatId)
        
        // Create viewModel after mocks are set up
        viewModel = ChatsListViewModel(repository)

        // When
        val result = viewModel.createNewChat(chatTitle)

        // Then
        assertThat(result).isEqualTo(expectedChatId)
        verify(repository).createChat(chatTitle)
    }

    @Test
    fun `createNewChat with default title should use default value`() = runTest {
        // Given
        val expectedChatId = 456L
        whenever(repository.createChat("New Chat")).thenReturn(expectedChatId)
        
        // Create viewModel after mocks are set up
        viewModel = ChatsListViewModel(repository)

        // When
        val result = viewModel.createNewChat()

        // Then
        assertThat(result).isEqualTo(expectedChatId)
        verify(repository).createChat("New Chat")
    }

    @Test
    fun `clearAllChatHistory should call repository deleteAllChats`() = runTest {
        // Given
        viewModel = ChatsListViewModel(repository)

        // When
        viewModel.clearAllChatHistory()

        // Then
        verify(repository).deleteAllChats()
    }

    @Test
    fun `forceRefresh should call repository forceFullRefresh`() = runTest {
        // Given
        viewModel = ChatsListViewModel(repository)

        // When
        viewModel.forceRefresh()

        // Then
        verify(repository).forceFullRefresh()
    }

    @Test
    fun `refreshChats should call repository performIncrementalSync`() = runTest {
        // Given
        viewModel = ChatsListViewModel(repository)

        // When
        viewModel.refreshChats()

        // Then
        verify(repository).performIncrementalSync()
    }
} 