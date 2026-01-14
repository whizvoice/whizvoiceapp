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
 */
@Singleton
class ConnectionStateManager @Inject constructor() {
    private val TAG = "ConnectionStateManager"
    
    private val _activeConversationId = MutableStateFlow<Long?>(null)
    val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()
    
    private val _isOnChatScreen = MutableStateFlow(false)
    val isOnChatScreen: StateFlow<Boolean> = _isOnChatScreen.asStateFlow()
    
    // Track optimistic chat ID → real chat ID migrations
    // This is the single source of truth for chat ID resolution
    // Using ConcurrentHashMap for thread-safety across coroutines
    private val chatMigrationMapping = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val migrationTimestamps = java.util.concurrent.ConcurrentHashMap<Long, Long>() // Track when migrations happened for cleanup
    
    /**
     * Set the currently active conversation.
     * This should be called when entering a chat screen.
     */
    fun setActiveConversation(id: Long?) {
        Log.d(TAG, "Setting active conversation to: $id")
        _activeConversationId.value = id
        if (id != null && id != -1L) {
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
    private var lastActiveConversationId: Long? = null
    
    fun getLastActiveConversationId(): Long? {
        // Update last active if current is non-null and valid
        val current = _activeConversationId.value
        if (current != null && current != -1L) {
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
    
    /**
     * Register a chat migration from an optimistic ID to a server ID.
     * This is the single source of truth for all chat ID resolution.
     */
    fun registerChatMigration(optimisticChatId: Long, realChatId: Long) {
        if (optimisticChatId >= 0) {
            Log.w(TAG, "registerChatMigration: optimisticChatId $optimisticChatId is not negative, ignoring")
            return
        }
        if (realChatId <= 0) {
            Log.w(TAG, "registerChatMigration: realChatId $realChatId is not positive, ignoring")
            return
        }
        
        chatMigrationMapping[optimisticChatId] = realChatId
        migrationTimestamps[optimisticChatId] = System.currentTimeMillis()
        Log.d(TAG, "registerChatMigration: Registered migration $optimisticChatId → $realChatId")
        
        // If the current active conversation is the optimistic ID, update it
        if (_activeConversationId.value == optimisticChatId) {
            Log.d(TAG, "registerChatMigration: Updating active conversation from $optimisticChatId to $realChatId")
            setActiveConversation(realChatId)
        }
        
        // Clean up old mappings (older than 1 hour)
        cleanupOldMigrations()
    }
    
    /**
     * Get the effective chat ID, resolving any migrations.
     * This is the primary method for getting the correct chat ID.
     * 
     * @param chatId The chat ID to resolve (might be optimistic or real)
     * @return The effective (real) chat ID if migrated, otherwise the original ID
     */
    fun getEffectiveChatId(chatId: Long?): Long? {
        if (chatId == null) return null
        
        // Check if this chat was migrated to a new ID
        val migratedId = chatMigrationMapping[chatId]
        if (migratedId != null) {
            Log.d(TAG, "getEffectiveChatId: Chat $chatId was migrated to $migratedId")
            return migratedId
        }
        return chatId
    }
    
    /**
     * Get the currently active effective chat ID.
     * This resolves any migrations for the current active conversation.
     */
    fun getCurrentEffectiveChatId(): Long? {
        return getEffectiveChatId(_activeConversationId.value)
    }
    
    /**
     * Check if two chat IDs represent the same chat (one might be migrated).
     */
    fun areChatsMigrated(chatId1: Long, chatId2: Long): Boolean {
        // Check if chatId1 was migrated to chatId2
        if (chatMigrationMapping[chatId1] == chatId2) {
            return true
        }
        // Check if chatId2 was migrated to chatId1
        if (chatMigrationMapping[chatId2] == chatId1) {
            return true
        }
        return false
    }
    
    /**
     * Get the migrated chat ID for an optimistic chat.
     */
    fun getMigratedChatId(optimisticChatId: Long): Long? {
        return chatMigrationMapping[optimisticChatId]
    }
    
    /**
     * Get the optimistic chat ID that was migrated to this real chat ID.
     * Thread-safe: ConcurrentHashMap's entries iterator is weakly consistent.
     */
    fun getOptimisticChatId(realChatId: Long): Long? {
        // ConcurrentHashMap's entries.find is safe - weakly consistent iterator
        return chatMigrationMapping.entries.find { it.value == realChatId }?.key
    }
    
    /**
     * Clean up old migration mappings to prevent memory leaks.
     * Thread-safe: makes a snapshot copy before iterating.
     */
    private fun cleanupOldMigrations() {
        val oneHourAgo = System.currentTimeMillis() - 3600000 // 1 hour
        // Take a snapshot copy to avoid ConcurrentModificationException
        val timestampsCopy = migrationTimestamps.toMap()
        val toRemove = timestampsCopy.filterValues { it < oneHourAgo }.keys
        var cleanedCount = 0
        toRemove.forEach { optimisticChatId ->
            chatMigrationMapping.remove(optimisticChatId)
            migrationTimestamps.remove(optimisticChatId)
            cleanedCount++
        }
        if (cleanedCount > 0) {
            Log.d(TAG, "cleanupOldMigrations: Cleaned up $cleanedCount old migration mappings")
        }
    }
}