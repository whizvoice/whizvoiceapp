package com.example.whiz.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the active conversation state across the app.
 * This allows the WebSocket reconnection logic to know which conversation
 * is currently active, even when ViewModels are destroyed during navigation.
 *
 * Conversations are identified by their client-minted chat id (UUID string).
 */
@Singleton
class ConnectionStateManager @Inject constructor() {
    private val TAG = "ConnectionStateManager"

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _isOnChatScreen = MutableStateFlow(false)
    val isOnChatScreen: StateFlow<Boolean> = _isOnChatScreen.asStateFlow()

    /**
     * Set the currently active conversation.
     * This should be called when entering a chat screen.
     */
    fun setActiveConversation(id: String?) {
        Log.d(TAG, "Setting active conversation to: $id")
        _activeConversationId.value = id
        if (id != null) {
            _isOnChatScreen.value = true
            // Store this as the last active conversation for reconnection purposes
            lastActiveConversationId = id
            Log.d(TAG, "Updated last active conversation to: $id")
        }
    }

    /**
     * Clear the active conversation.
     * This should be called when leaving the chat screen.
     * Note: Does NOT clear lastActiveConversationId - that stays tied to the WebSocket
     */
    fun clearActiveConversation() {
        Log.d(TAG, "Clearing active conversation (keeping last active: $lastActiveConversationId for WebSocket)")
        _activeConversationId.value = null
        _isOnChatScreen.value = false
    }

    /**
     * Get the last non-null conversation ID that was active.
     * Useful for reconnection even after navigating away.
     */
    private var lastActiveConversationId: String? = null

    fun getLastActiveConversationId(): String? {
        // Update last active if current is non-null
        val current = _activeConversationId.value
        if (current != null) {
            lastActiveConversationId = current
        }
        return lastActiveConversationId
    }

    /**
     * Clear the WebSocket's conversation binding.
     * This should only be called when truly disconnecting the WebSocket,
     * not when navigating between screens.
     */
    fun clearWebSocketConversation() {
        Log.d(TAG, "Clearing WebSocket conversation binding")
        lastActiveConversationId = null
    }
}
