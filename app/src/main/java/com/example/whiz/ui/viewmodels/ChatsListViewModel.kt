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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val repository: WhizRepository
) : ViewModel() {

    private val TAG = "ChatsListViewModel"

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // All chats, ordered by most recent first
    val chats: StateFlow<List<ChatEntity>> = repository.getAllChatsFlow()
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

    // Force a complete refresh by clearing sync timestamps
    fun forceRefresh() {
        viewModelScope.launch {
            repository.forceFullRefresh()
        }
    }

    // Pull-to-refresh: incremental sync
    fun refreshChats() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Log.d(TAG, "refreshChats: Starting pull-to-refresh incremental sync")
                repository.performIncrementalSync()
                Log.d(TAG, "refreshChats: Pull-to-refresh completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "refreshChats: Error during pull-to-refresh", e)
                // Error is handled by the repository and UI
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}