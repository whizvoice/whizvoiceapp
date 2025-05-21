package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val repository: WhizRepository
) : ViewModel() {

    // All chats, ordered by most recent first
    val chats: StateFlow<List<ChatEntity>> = repository.getAllChats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Create a new chat
    suspend fun createNewChat(title: String = "New Chat"): Long {
        return repository.createChat(title)
    }

    // Clear all chat history
    fun clearAllChatHistory() {
        viewModelScope.launch {
            repository.deleteAllChats()
        }
    }
}