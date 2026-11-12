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

    // True once the first chats sync has finished (success or failure)
    val isFirstSyncComplete: StateFlow<Boolean> = repository.isFirstSyncComplete

    // Connection status - true when we had to use cached data
    private val _isShowingCachedData = MutableStateFlow(false)
    val isShowingCachedData: StateFlow<Boolean> = _isShowingCachedData.asStateFlow()

    // Wake word state
    val isWakeWordEnabled: StateFlow<Boolean> = wakeWordPreferences.isEnabled

    fun toggleWakeWord() {
        viewModelScope.launch {
            try {
                val newEnabled = !wakeWordPreferences.isEnabled.value
                Log.d(TAG, "toggleWakeWord: ${!newEnabled} -> $newEnabled (from ChatsList)")
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
    val chats: StateFlow<List<ChatEntity>> = repository.observeChats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Clean up stale never-synced chats on initial load
        viewModelScope.launch {
            repository.cleanupStaleUnsyncedChats()
        }
    }

    // Delete a specific chat
    fun deleteChat(chatId: String) {
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

    // Pull-to-refresh: sync
    fun refreshChats() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                Log.d(TAG, "refreshChats: Starting sync to pick up new chats")
                val result = repository.syncChats()
                Log.d(TAG, "refreshChats: Sync completed successfully")

                // Clean up stale never-synced chats
                repository.cleanupStaleUnsyncedChats()

                // Check if we're showing cached data (result will indicate this)
                _isShowingCachedData.value = result.isCachedData

                // If the REST API sync succeeded (not cached), try reconnecting WebSocket
                // This handles the case where internet was restored after max reconnect attempts
                if (!result.isCachedData && !whizServerRepository.isConnected()) {
                    Log.d(TAG, "refreshChats: REST sync succeeded but WebSocket disconnected, attempting reconnect")
                    whizServerRepository.connect(forPriming = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "refreshChats: Error during sync", e)
                // Even on error, we show cached data
                _isShowingCachedData.value = true
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}