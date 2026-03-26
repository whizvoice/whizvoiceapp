package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.preferences.WakeWordPreferences
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.services.WakeWordService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class ChatsListViewModel @Inject constructor(
    private val repository: WhizRepository,
    private val whizServerRepository: WhizServerRepository,
    private val wakeWordPreferences: WakeWordPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val TAG = "ChatsListViewModel"

    // Pull-to-refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Initial load state — true until first sync completes
    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()
    
    // Connection status - true when we had to use cached data
    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    // Wake word state
    val isWakeWordEnabled: StateFlow<Boolean> = wakeWordPreferences.isEnabled

    fun toggleWakeWord() {
        viewModelScope.launch {
            try {
                val newEnabled = !wakeWordPreferences.isEnabled.value
                wakeWordPreferences.setEnabled(newEnabled)
                if (newEnabled) {
                    WakeWordService.start(context)
                } else {
                    WakeWordService.stop(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling wake word", e)
            }
        }
    }

    // All chats, ordered by most recent first
    val chats: StateFlow<List<ChatEntity>> = repository.getAllChatsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Ensure conversations are loaded when the ViewModel is created
        viewModelScope.launch {
            try {
                Log.d(TAG, "ChatsListViewModel init: Checking if conversations need to be loaded")

                // Clean up stale optimistic chats on initial load
                repository.cleanupStaleOptimisticChats()

                // If we don't have any conversations cached, trigger a refresh
                if (repository.conversations.value.isEmpty()) {
                    Log.d(TAG, "ChatsListViewModel init: No conversations cached, triggering refresh")
                    repository.refreshConversations()
                } else {
                    Log.d(TAG, "ChatsListViewModel init: ${repository.conversations.value.size} conversations already cached")
                    _isInitialLoad.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "ChatsListViewModel init: Error checking conversations", e)
                _isInitialLoad.value = false
            }
        }

        // Clear isInitialLoad once chats flow emits non-empty data
        viewModelScope.launch {
            chats.first { it.isNotEmpty() }
            _isInitialLoad.value = false
        }
    }

    // Create a new chat
    suspend fun createNewChat(title: String = "New Chat"): Long {
        return repository.createChat(title)
    }

    // Create a new chat with optimistic UI (for voice launches and immediate feedback)
    suspend fun createNewChatOptimistic(title: String = "New Chat"): Long {
        return repository.createChatOptimistic(title)
    }

    // Delete a specific chat
    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            repository.deleteChat(chatId)
        }
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
                Log.d(TAG, "refreshChats: Starting incremental sync to pick up new chats")
                val result = repository.performIncrementalSync()
                Log.d(TAG, "refreshChats: Incremental sync completed successfully")

                // Clean up stale optimistic chats (empty chats older than 1 hour)
                repository.cleanupStaleOptimisticChats()

                // Check if we're showing cached data (result will indicate this)
                _isShowingCachedData.value = result.isCachedData

                // If the REST API sync succeeded (not cached), try reconnecting WebSocket
                // This handles the case where internet was restored after max reconnect attempts
                if (!result.isCachedData && !whizServerRepository.isConnected()) {
                    Log.d(TAG, "refreshChats: REST sync succeeded but WebSocket disconnected, attempting reconnect")
                    whizServerRepository.connect(forPriming = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshChats: Error during incremental sync", e)
                // Even on error, we show cached data
                _isShowingCachedData.value = true
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}