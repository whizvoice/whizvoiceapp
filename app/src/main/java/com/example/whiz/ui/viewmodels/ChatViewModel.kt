package com.example.whiz.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.ConnectionStateManager
// SpeechRecognitionService is now accessed via VoiceManager
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.ListeningMode
import com.example.whiz.services.TTSManager

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject

import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.data.remote.WebSocketEvent
import com.example.whiz.permissions.PermissionHandler
import kotlinx.coroutines.flow.catch
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.local.MessageEntity // Ensure MessageEntity is imported
import com.example.whiz.data.local.MessageType // Ensure MessageType is imported
import kotlinx.coroutines.ExperimentalCoroutinesApi // Import for OptIn
import org.json.JSONObject // For basic JSON parsing
import org.json.JSONException
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.tools.ToolExecutor
import com.example.whiz.tools.ToolExecutionResult



@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // Added OptIn annotation
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // Inject Context
    private val repository: WhizRepository,
    private val whizServerRepository: WhizServerRepository,
    private val authRepository: AuthRepository, // Add this
    private val userPreferences: UserPreferences,
    private val connectionStateManager: ConnectionStateManager,
    savedStateHandle: SavedStateHandle,
    private val ttsManager: TTSManager,
    private val appLifecycleService: com.example.whiz.services.AppLifecycleService,
    private val voiceManager: VoiceManager,
    private val toolExecutor: ToolExecutor,
) : ViewModel() { // Removed TextToSpeech.OnInitListener

    companion object {
        /**
         * Flag to prevent old ChatViewModel from disabling continuous listening
         * when transitioning to a new chat via assistant long-press.
         * Set to true by AssistantActivity before launching MainActivity.
         */
        @Volatile
        var isTransitioning = false
    }

    private val TAG = "ChatViewModel"

    // Config state
    val configUseRemoteAgent = true;

    // Chat state - initialize from navigation argument
    private val initialChatId = savedStateHandle.get<Long>("chatId") ?: -1L
    private val _chatId = MutableStateFlow<Long>(initialChatId)
    val chatId: StateFlow<Long> = _chatId.asStateFlow()
    private val _chatTitle = MutableStateFlow<String>("New Chat")
    val chatTitle = _chatTitle.asStateFlow()
    
    // Track multiple pending WebSocket requests by request ID
    private val pendingRequests = mutableMapOf<String, Long>() // requestId -> chatId
    
    /**
     * Check if a specific request ID is in the pending requests list
     * This is used for testing to verify WebSocket message sending
     */
    fun hasPendingRequest(requestId: String): Boolean {
        return pendingRequests.containsKey(requestId)
    }
    
    /**
     * Get all pending request IDs for testing purposes
     */
    fun getPendingRequestIds(): Set<String> {
        return pendingRequests.keys.toSet()
    }

    /**
     * Check if a request is in the WebSocket retry queue (for testing purposes)
     */
    fun hasRequestInRetryQueue(requestId: String): Boolean {
        return whizServerRepository.hasMessageInRetryQueue(requestId)
    }
    
    /**
     * Get all request IDs in the WebSocket retry queue (for testing purposes)
     */
    fun getRetryQueueRequestIds(): Set<String> {
        return whizServerRepository.getRetryQueueRequestIds()
    }

    // Track locally-saved interrupt messages to prevent server duplication

    // Scroll-to-bottom event for UI - emitted when new messages are added (not during sync/load)
    private val _scrollToBottomEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottomEvent: SharedFlow<Unit> = _scrollToBottomEvent.asSharedFlow()

    // Messages in the current chat with deduplication to handle optimistic UI transitions
    val messages = _chatId
        .flatMapLatest { id ->
        Log.d(TAG, "🔥 messages flow: Chat ID changed to $id")
        if (id != 0L) { // 🔧 OPTIMISTIC UI FIX: Handle both positive AND negative chat IDs
            repository.getMessagesForChat(id).map { messagesList ->
                // 🔧 DEDUPLICATION DEBUG: Log all messages before deduplication
                Log.d(TAG, "🔍 DEDUP_DEBUG: Processing ${messagesList.size} messages for chat $id:")
                Log.d(TAG, "🔥 MESSAGES_FLOW_DEBUG: Emitting ${messagesList.size} messages to UI for chat $id")
                messagesList.forEachIndexed { index, message ->
                    Log.d(TAG, "🔍 DEDUP_DEBUG:   [$index] ID:${message.id} Type:${message.type} RequestID:${message.requestId} Timestamp:${message.timestamp} Content:'${message.content.take(30)}...'")
                }
                
                // 🔧 DEDUPLICATION FIX: Remove duplicate messages, keeping the LATEST (highest timestamp)
                // Group messages by their dedup key
                val grouped = messagesList.groupBy { message ->
                    val key = when {
                        message.requestId != null -> {
                            // For messages with request ID (both USER and ASSISTANT): use requestId + type as unique key
                            Pair(message.requestId, message.type)
                        }
                        else -> {
                            // For messages without requestId: use content + type
                            Pair(message.content.trim(), message.type)
                        }
                    }
                    Log.d(TAG, "🔍 DEDUP_DEBUG: Creating key for message ID:${message.id} RequestID:${message.requestId} -> $key")
                    key
                }

                // For each group, keep the LATEST message (highest timestamp, then LOWEST id as tiebreaker)
                // Using lowest id when timestamps are equal keeps the first-arriving message,
                // which is more stable as new duplicates arrive (prevents UI flickering)
                val deduplicatedMessages = grouped.values.mapNotNull { duplicates ->
                    duplicates.maxWithOrNull(compareBy({ it.timestamp }, { -it.id }))
                }.sortedBy { it.timestamp }  // Re-sort by timestamp for display order
                
                if (deduplicatedMessages.size != messagesList.size) {
                    Log.w(TAG, "🔍 DEDUP_DEBUG: Removed ${messagesList.size - deduplicatedMessages.size} duplicate messages")
                    
                    // Log which messages were removed
                    val removedMessages = messagesList - deduplicatedMessages.toSet()
                    removedMessages.forEach { removed ->
                        Log.w(TAG, "🔍 DEDUP_DEBUG: REMOVED -> ID:${removed.id} Type:${removed.type} RequestID:${removed.requestId} Timestamp:${removed.timestamp} Content:'${removed.content.take(30)}...'")
                    }
                    
                    // Log final deduplicated list (using Log.w so it shows in test logcat)
                    Log.w(TAG, "🔍 DEDUP_DEBUG: Final deduplicated list (${deduplicatedMessages.size} messages):")
                    deduplicatedMessages.forEachIndexed { index, message ->
                        Log.w(TAG, "🔍 DEDUP_DEBUG:   FINAL[$index] ID:${message.id} Type:${message.type} RequestID:${message.requestId} Timestamp:${message.timestamp} Content:'${message.content.take(30)}...'")
                    }
                }
                deduplicatedMessages
            }
        } else {
            flowOf(emptyList())
        }
    }.distinctUntilChanged() // Prevent duplicate emissions
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly, // Changed from WhileSubscribed to Eagerly
        initialValue = emptyList()
    )

    // Helper function to update responding state based on current chat's pending requests
    private fun updateRespondingStateForCurrentChat() {
        try {
            val currentChatId = _chatId.value
            val hasPendingRequests = pendingRequests.values.any { it == currentChatId }
            val wasResponding = _isResponding.value
            
            // 🔧 CONCURRENT MODE: Only show responding state for UI feedback, don't block input
            // The UI can still allow new messages even when there are pending requests
            _isResponding.value = hasPendingRequests
            
            // 🔧 If we just finished responding and continuous listening is enabled, restart microphone immediately
            if (wasResponding && !hasPendingRequests && voiceManager.isContinuousListeningEnabled.value && !isSpeaking.value) {
                Log.d(TAG, "[LOG] Just finished computing - restarting continuous listening immediately")
                viewModelScope.launch {
                    // Small delay to ensure state propagation
                    delay(50L)
                    if (!_isResponding.value && !isSpeaking.value && voiceManager.isContinuousListeningEnabled.value) {
                        startContinuousListening()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateRespondingStateForCurrentChat", e)
            _errorState.value = "Error updating chat state: ${e.message}"
        }
    }

    // --- Speech Recognition State ---
    // Delegate to VoiceManager for single source of truth
    val transcriptionState = voiceManager.transcriptionState
    val isListening = voiceManager.isListening
    val speechError = voiceManager.speechError
    // Track if the user *intended* to be listening before TTS started
    private var wasListeningBeforeTTS = false
    private var pendingTTSMessage: String? = null  // Queue TTS message if user is speaking
    
    // Track when app was last backgrounded to prevent TTS replay of old messages
    private var lastBackgroundedTime = 0L

    // Responses are in progress
    private val _isResponding = MutableStateFlow(false)
    val isResponding = _isResponding.asStateFlow()

    // --- Text-to-Speech State ---
    private val _isTTSInitialized = MutableStateFlow(false)
    val isTTSInitialized = _isTTSInitialized.asStateFlow() // Expose for testing
    // Derive speaking state from TTSManager (single source of truth)
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

    // --- Voice Response Setting ---
    // Use VoiceManager as single source of truth for voice response state
    // This prevents race conditions when bubble service updates the state
    val isVoiceResponseEnabled: StateFlow<Boolean> = voiceManager.isVoiceResponseEnabled

    // Chat persistence jobs
    private var persistenceJob: Job? = null

    // --- WebSocket State ---
    private val _isConnectedToServer = MutableStateFlow(false)
    val isConnectedToServer = _isConnectedToServer.asStateFlow()
    
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()
    private var serverMessageCollectorJob: Job? = null

    // State to track permission status
    private val _micPermissionGranted = MutableStateFlow(false)
    val micPermissionGranted = _micPermissionGranted.asStateFlow()

    // Error state for the view model
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState = _errorState.asStateFlow()
    
    // Chat loading error state - separate from general errors
    private val _chatLoadError = MutableStateFlow<String?>(null)
    val chatLoadError = _chatLoadError.asStateFlow()

    // Connection error state
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()

    // Authentication error state for API keys
    private val _showAuthErrorDialog = MutableStateFlow<String?>(null)
    val showAuthErrorDialog = _showAuthErrorDialog.asStateFlow()

    // New state for Asana specific setup dialog
    private val _showAsanaSetupDialog = MutableStateFlow(false)
    val showAsanaSetupDialog = _showAsanaSetupDialog.asStateFlow()
    
    // New state for overlay permission dialog
    private val _showOverlayPermissionDialog = MutableStateFlow(false)
    val showOverlayPermissionDialog = _showOverlayPermissionDialog.asStateFlow()

    // State to trigger navigation to Login screen
    private val _navigateToLogin = MutableStateFlow(false)
    val navigateToLogin: StateFlow<Boolean> = _navigateToLogin.asStateFlow()
    
    // Callback for requesting microphone permission from UI layer
    var onRequestMicrophonePermission: (() -> Unit)? = null

    private var isDisconnectingForAuthError = false
    
    // Track whether WebSocket is reconnecting (true) vs connecting fresh after chat load (false)
    private var isReconnectingAfterDisconnect = false
    // Track which chat was active when disconnection happened
    private var chatIdWhenDisconnected: Long? = null

    // Expose VoiceManager's continuous listening state to UI
    val isContinuousListeningEnabled = voiceManager.isContinuousListeningEnabled

    // Track the current voice settings state to know when we need to reset
    private var currentVoiceSettings: com.example.whiz.data.preferences.VoiceSettings? = null

    // UI state for the text input
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // Track whether current input text is from typing vs voice
    private val _isInputFromVoice = MutableStateFlow(false)
    val isInputFromVoice = _isInputFromVoice.asStateFlow()
    

    init {
        // Check if the app already has microphone permission
        _micPermissionGranted.value = PermissionHandler.hasMicrophonePermission(context)
        
        // VoiceManager is a singleton that manages its own initialization
        
        // Initialize TTS using the new event-driven approach
        initializeTTS()

        // Start observing messages immediately
        if (configUseRemoteAgent) {
            Log.d(TAG, "Init: Using remote agent. WebSocket will connect when sending first message or loading existing chat.")
            // Don't connect here - let sendMessage or loadChatWithVoiceMode handle it
            // This prevents duplicate connections when navigating to existing chats
        }
        
        // Track active conversation in ConnectionStateManager
        viewModelScope.launch {
            _chatId.collect { id ->
                if (id != 0L) { // Only update for valid chat IDs
                    connectionStateManager.setActiveConversation(id)
                    Log.d(TAG, "Updated active conversation in ConnectionStateManager: $id")
                }
            }
        }
        
        // Observe voice settings changes and apply them to TTS
        viewModelScope.launch {
            userPreferences.voiceSettings.collect { voiceSettings ->
                if (_isTTSInitialized.value) {
                    applyVoiceSettings(voiceSettings)
                }
            }
        }
        
        // Observe chat migration events from repository
        viewModelScope.launch {
            repository.chatMigrationEvents.collect { (optimisticId, serverId) ->
                // Check if this migration affects the current chat
                if (_chatId.value == optimisticId) {
                    Log.d(TAG, "Chat migration detected: updating chat ID from $optimisticId to $serverId")
                    _chatId.value = serverId
                    // The messages flow will automatically update since it observes _chatId
                    
                    // No longer needed - chatId is passed directly to sendMessage
                    Log.d(TAG, "Chat ID migration complete: $serverId")
                }
            }
        }
        
        // Observe app foreground events to restart continuous listening
        Log.d(TAG, "Setting up app foreground event collection")
        viewModelScope.launch {
            Log.d(TAG, "Started collecting app foreground events")
            appLifecycleService.appForegroundEvent.collect {
                Log.d(TAG, "App foreground event received")
                onAppForegrounded()
            }
        }
        
        // Observe app background events to sync state
        Log.d(TAG, "Setting up app background event collection")
        viewModelScope.launch {
            Log.d(TAG, "Started collecting app background events")
            appLifecycleService.appBackgroundEvent.collect {
                Log.d(TAG, "App background event received")
                // Check if app is actually in background - if not, this is a stale replayed event
                if (appLifecycleService.isInForeground()) {
                    Log.d(TAG, "Ignoring stale background event - app is actually in foreground")
                } else {
                    onAppBackgrounded()
                }
            }
        }

        // Subscribe to bubble mode transcriptions and send them to the server
        // Only process when bubble service is actually active (app in background)
        viewModelScope.launch {
            Log.d(TAG, "Started collecting bubble transcriptions")
            BubbleOverlayService.userTranscriptionFlow
                .distinctUntilChanged() // Prevent duplicate consecutive emissions
                .collect { transcription ->
                    if (transcription.isNotBlank() && BubbleOverlayService.isActive) {
                        Log.d(TAG, "Received transcription from bubble mode (bubble active): '$transcription'")
                        sendUserInput(transcription)
                    } else if (transcription.isNotBlank()) {
                        Log.d(TAG, "Ignoring bubble transcription - bubble not active (main app handling it): '$transcription'")
                    }
                }
        }

        // NOTE: ChatScreen collects from voiceManager.transcriptionFlow for regular (non-bubble) transcriptions
        // and calls sendUserInput() there. We don't duplicate that logic here to avoid sending messages twice.

        // Enhanced server message collection with interrupt handling
        serverMessageCollectorJob = viewModelScope.launch {
            Log.d(TAG, "🧵 THREAD DEBUG: WebSocket collector starting on thread: ${Thread.currentThread().name}")
            whizServerRepository.webSocketEvents.collect { event ->
                Log.d(TAG, "🧵 THREAD DEBUG: Processing WebSocket event on thread: ${Thread.currentThread().name}")
                when (event) {
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocketEvent.Connected: Called. isReconnectingAfterDisconnect=$isReconnectingAfterDisconnect")
                        // Remove arbitrary delays and handle connection state immediately
                        _isConnectedToServer.value = true
                        _connectionError.value = null // Clear any connection errors when successfully connected
                        Log.d(TAG, "WebSocketEvent.Connected: Resetting isDisconnectingForAuthError to false.")
                        isDisconnectingForAuthError = false
                        
                        // Always sync messages when WebSocket connects to ensure we have the latest
                        val currentChatId = _chatId.value
                        if (currentChatId != -1L && currentChatId != 0L) {
                            Log.d(TAG, "WebSocketEvent.Connected: Syncing messages for chat $currentChatId (reconnect=$isReconnectingAfterDisconnect)")
                            viewModelScope.launch {
                                try {
                                    // Fetch any messages we might have missed
                                    // Server now handles optimistic chat IDs via optimistic_chat_id column
                                    // Use retry mechanism to handle race conditions with optimistic chats
                                    val serverMessages = repository.fetchMessagesWithRetry(currentChatId)
                                    Log.d(TAG, "WebSocketEvent.Connected: Retrieved ${serverMessages.size} messages from server for chat $currentChatId")
                                    
                                    // The fetchMessagesWithDeduplication already handles storing messages
                                    // Just trigger UI refresh
                                    repository.refreshMessages()
                                    
                                    // Check for orphaned messages that need to be sent
                                    // This handles messages that were created while offline
                                    checkAndRetryOrphanedMessages(currentChatId)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error syncing messages for chat $currentChatId", e)
                                    // Don't show error to user - this is a background sync
                                }
                            }
                        } else {
                            Log.d(TAG, "WebSocketEvent.Connected: New chat (chatId=-1), skipping sync")
                        }
                        
                        // Reset the flags for next time
                        isReconnectingAfterDisconnect = false
                        chatIdWhenDisconnected = null
                    }
                    is WebSocketEvent.Reconnecting -> {
                        Log.d(TAG, "WebSocketEvent.Reconnecting: Called.")
                        _isConnectedToServer.value = false
                        // Mark that we're reconnecting so we know to sync messages when connected
                        isReconnectingAfterDisconnect = true
                        // Remember which chat was active when we disconnected
                        chatIdWhenDisconnected = _chatId.value
                        Log.d(TAG, "WebSocketEvent.Reconnecting: Saved chatIdWhenDisconnected=$chatIdWhenDisconnected")
                        
                        // IMPORTANT: Actually attempt to reconnect with the current chat ID
                        val currentChatId = _chatId.value
                        if (currentChatId != -1L && currentChatId != 0L) {
                            Log.d(TAG, "WebSocketEvent.Reconnecting: Attempting to reconnect with chatId=$currentChatId")
                            viewModelScope.launch {
                                try {
                                    // Don't reset persistentDisconnectForTest flag here - let the test control it
                                    whizServerRepository.connect(currentChatId)
                                    Log.d(TAG, "WebSocketEvent.Reconnecting: Reconnection initiated for chatId=$currentChatId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "WebSocketEvent.Reconnecting: Failed to reconnect", e)
                                }
                            }
                        } else {
                            Log.d(TAG, "WebSocketEvent.Reconnecting: No valid chat ID to reconnect with (chatId=$currentChatId)")
                        }
                        
                        // Don't show "Connection lost" message to user - handle reconnection silently
                        // _connectionError.value = "Connection lost. Attempting to reconnect..."
                        _isResponding.value = false
                    }
                    is WebSocketEvent.Closed -> {
                        Log.d(TAG, "WebSocketEvent.Closed: Called. isDisconnectingForAuthError = $isDisconnectingForAuthError, _navigateToLogin = ${_navigateToLogin.value}")
                        _isConnectedToServer.value = false
                        pendingRequests.clear() // 🔧 Clear all pending requests on connection close
                        
                        // Only set reconnecting flag if this is NOT an intentional disconnect
                        // (loadChat calls disconnect() which is intentional)
                        if (isDisconnectingForAuthError) {
                            Log.d(TAG, "WebSocketEvent.Closed: Intentional disconnect due to AuthError. Not queueing further actions here.")
                            isReconnectingAfterDisconnect = false
                        } else if (_navigateToLogin.value) {
                            Log.d(TAG, "WebSocketEvent.Closed: Not attempting client-side reconnect as navigation to login is pending.")
                            isReconnectingAfterDisconnect = false
                        } else {
                            // Don't show "Connection closed" message to user - handle reconnection silently
                            // if (_connectionError.value == null || _connectionError.value?.contains("reconnect") == false) {
                            //     _connectionError.value = "Connection closed."
                            // }
                            Log.d(TAG, "WebSocketEvent.Closed: Repository should be handling retries if applicable.")
                            // Note: isReconnectingAfterDisconnect will be set to true by WebSocketEvent.Reconnecting
                        }
                        // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                    }
                    is WebSocketEvent.Error -> {
                        _isConnectedToServer.value = false
                        
                        // Only show error to user if it's a persistent failure after retries
                        val errorMessage = event.error.message ?: "Unknown connection failure"
                        
                        // 🔧 CRITICAL FIX: Check if this is an authentication error
                        if (errorMessage.contains("Authentication required") || errorMessage.contains("Please log in again")) {
                            Log.w(TAG, "🔥 AUTHENTICATION ERROR DETECTED: $errorMessage - Navigating to login")
                            _navigateToLogin.value = true
                            isDisconnectingForAuthError = true
                            
                            // Clear all pending requests on auth error
                            Log.d(TAG, "🔥 AUTH ERROR: CLEARING all pending requests: $pendingRequests")
                            pendingRequests.clear()
                            Log.d(TAG, "🔥 AUTH ERROR: Pending requests map after clearing: $pendingRequests")
                            _isResponding.value = false
                            
                            // Sign out to clear any invalid tokens
                            authRepository.signOut()
                            Log.d(TAG, "AuthError: Called authRepository.signOut() after authentication error")
                            
                            _showAuthErrorDialog.value = null
                            _showAsanaSetupDialog.value = false
                            _connectionError.value = null
                        }
                        // Check if this is a final retry failure (contains "after X attempts")
                        // or a final connection loss after max reconnect attempts
                        else if ((errorMessage.contains("after") && errorMessage.contains("attempts")) ||
                                 errorMessage.contains("Connection lost")) {
                            // This is a final failure after all retries - show to user
                            _connectionError.value = "Failed to send message. Please check your connection and try again."
                            if (_chatId.value != 0L && _chatId.value != -1L) { // Only add if a chat is active (includes temporary negative IDs)
                                repository.addAssistantMessage(
                                    chatId = _chatId.value,
                                    content = "Error: Unable to send message. Please try again."
                                )
                            }
                            // Clear all pending requests on final connection error
                            Log.d(TAG, "🔥 CONNECTION ERROR: CLEARING all pending requests: $pendingRequests")
                            pendingRequests.clear()
                            Log.d(TAG, "🔥 CONNECTION ERROR: Pending requests map after clearing: $pendingRequests")
                            _isResponding.value = false
                            // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                        } else {
                            // This is likely a temporary connection issue - handle silently
                            Log.w(TAG, "Temporary connection error (will retry): $errorMessage")

                            // Only clear responding state if there are no messages waiting in the retry queue
                            // This keeps the typing indicator visible while retries are still happening
                            if (_isResponding.value && whizServerRepository.isRetryQueueEmpty()) {
                                Log.d(TAG, "Clearing responding state due to connection error (no pending retries)")
                                Log.d(TAG, "🔥 TEMPORARY CONNECTION ERROR: CLEARING all pending requests: $pendingRequests")
                                _isResponding.value = false
                                pendingRequests.clear()
                                Log.d(TAG, "🔥 TEMPORARY CONNECTION ERROR: Pending requests map after clearing: $pendingRequests")
                                // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                            }
                        }
                        
                        _showAuthErrorDialog.value = null
                        if (!errorMessage.contains("Authentication required")) {
                            _navigateToLogin.value = false
                        }
                    }
                    is WebSocketEvent.AuthError -> {
                        Log.d(TAG, "WebSocketEvent.AuthError received: ${event.message}.")
                        _isConnectedToServer.value = false
                        Log.d(TAG, "🔥 AUTH ERROR: CLEARING all pending requests: $pendingRequests")
                        pendingRequests.clear() // 🔧 Clear all pending requests on auth error
                        Log.d(TAG, "🔥 AUTH ERROR: Pending requests map after clearing: $pendingRequests")
                        viewModelScope.launch {
                            val refreshSuccessful = authRepository.refreshAccessToken()
                            if (refreshSuccessful) {
                                Log.i(TAG, "Token refresh successful after WebSocket AuthError. Attempting to reconnect WebSocket.")
                                isDisconnectingForAuthError = false
                                _navigateToLogin.value = false
                                // Reconnect to the same conversation we were in
                                // Don't reconnect only for the "new chat" state (chatId = -1)
                                // DO reconnect for existing chats (chatId > 0) and optimistic chats (chatId < -1)
                                if (_chatId.value != -1L && _chatId.value != 0L) {
                                    whizServerRepository.connect(_chatId.value)
                                }
                            } else {
                                Log.w(TAG, "Token refresh failed after WebSocket AuthError. Navigating to login.")
                                _navigateToLogin.value = true 
                                isDisconnectingForAuthError = true 
                                
                                whizServerRepository.disconnect() 
                                
                                authRepository.signOut()
                                Log.d(TAG, "AuthError: Called authRepository.signOut() after failed refresh.")
                                
                                _showAuthErrorDialog.value = null
                                _showAsanaSetupDialog.value = false
                                _connectionError.value = null
                                if (_chatId.value > 0) { // Only add if a chat is active
                                    repository.addAssistantMessage(
                                        chatId = _chatId.value,
                                        content = "Authentication failed. Please log in again."
                                    )
                                }
                            }
                        }
                        _isResponding.value = false
                        // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                    }
                    is WebSocketEvent.ToolExecution -> {
                        Log.i(TAG, "🔧 TOOL EXECUTION EVENT RECEIVED IN CHATVIEWMODEL")
                        Log.i(TAG, "🔧 Tool request: ${event.toolRequest}")
                        Log.i(TAG, "🔧 Tool name: ${event.toolRequest.optString("tool", "unknown")}")
                        Log.i(TAG, "🔧 Request ID: ${event.toolRequest.optString("request_id", "none")}")
                        
                        // Execute the tool
                        Log.i(TAG, "🔧 Calling toolExecutor.executeToolFromJson")
                        toolExecutor.executeToolFromJson(
                            toolRequest = event.toolRequest,
                            voiceManager = voiceManager,
                            chatViewModel = this@ChatViewModel
                        )
                    }
                    is WebSocketEvent.Cancelled -> {
                        Log.d(TAG, "Request ${event.cancelledRequestId} was cancelled successfully")
                        
                        // 🔧 CANCELLATION HANDLING: User message remains in chat, no assistant response
                        // The user message with this requestId will stay in the chat but won't get a response
                        // This creates the natural conversation flow: user message -> (no response) -> next user message
                        Log.d(TAG, "🔧 CANCELLATION: User message for request ${event.cancelledRequestId} remains in chat (no assistant response)")
                        
                        // Remove the cancelled request from pending requests
                        Log.d(TAG, "🔥 CANCELLATION: REMOVING from pendingRequests: requestId=${event.cancelledRequestId}")
                        pendingRequests.remove(event.cancelledRequestId)
                        Log.d(TAG, "🔥 CANCELLATION: Pending requests map after removing: $pendingRequests")
                        // 🔧 DON'T update responding state here - animation should continue until
                        // a real (non-cancelled) response arrives. This prevents the typing indicator
                        // from stopping when a request is cancelled but no response text was shown.
                    }
                    is WebSocketEvent.DeleteMessage -> {
                        Log.d(TAG, "🗑️ Delete message notification: messageId=${event.messageId}, conversationId=${event.conversationId}, requestId=${event.requestId}, reason=${event.reason}")

                        // Only delete if this message is for the current chat
                        if (event.conversationId == _chatId.value) {
                            viewModelScope.launch {
                                try {
                                    if (event.requestId != null) {
                                        // Delete the assistant message by request_id
                                        Log.d(TAG, "🗑️ Deleting assistant message with requestId=${event.requestId} from chat ${event.conversationId}")
                                        val deletedCount = withContext(Dispatchers.IO) {
                                            repository.deleteAssistantMessageByRequestId(event.conversationId, event.requestId)
                                        }
                                        Log.d(TAG, "🗑️ Deleted $deletedCount assistant message(s) for request ${event.requestId}")
                                    } else {
                                        // Fallback: if no request_id, trigger a refresh to sync with server
                                        Log.w(TAG, "🗑️ No request_id in delete notification, falling back to message refresh")
                                        repository.fetchMessagesWithDeduplication(event.conversationId)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error handling delete message event", e)
                                }
                            }
                        } else {
                            Log.d(TAG, "🗑️ Ignoring delete message for different conversation: ${event.conversationId} (current: ${_chatId.value})")
                        }
                    }
                    is WebSocketEvent.Message -> {
                        val processingStartTime = System.currentTimeMillis()
                        val messageReceivedTime = System.currentTimeMillis()
                        val processingDelay = processingStartTime - messageReceivedTime
                        if (processingDelay > 100) {
                            Log.w(TAG, "Message processing delay: ${processingDelay}ms")
                        }
                        
                        Log.d(TAG, "🔧 WEBSOCKET_MESSAGE: Received message event with conversationId=${event.conversationId}, requestId=${event.requestId}")
                        
                        try {
                            var isErrorHandled = false
                            var messageContentForChat = event.text
                            // Check both voice response setting AND bubble TTS mode
                            var speakThisMessage = isVoiceResponseEnabled.value ||
                                (BubbleOverlayService.isActive && BubbleOverlayService.bubbleListeningMode == ListeningMode.TTS_WITH_LISTENING)
                            var effectiveConversationId: Long? = null // Declare outside try block

                            // 🔧 FIXED: Use conversation_id from WebSocketEvent (already parsed by WhizServerRepo)
                            // Don't try to re-parse the response text as JSON since it's just the response content
                            effectiveConversationId = event.conversationId
                            
                            // 🔧 CRITICAL FIX: Immediately update conversation ID when we receive a real ID for the CURRENT chat
                            // This ensures reconnections use the correct ID even if they happen during migration
                            val currentChatId = _chatId.value
                            if (effectiveConversationId != null && effectiveConversationId > 0 && currentChatId < 0) {
                                // We have an optimistic ID for the current chat and received a real ID
                                // Check if this message is actually for our current chat (might be a broadcast from another session)
                                val isForCurrentChat = event.clientConversationId == currentChatId || 
                                                       event.requestId in pendingRequests
                                
                                if (isForCurrentChat) {
                                    Log.d(TAG, "🔄 IMMEDIATE MIGRATION: Received real conversation ID $effectiveConversationId for current optimistic chat $currentChatId")
                                    
                                    // Migration will be registered when migrateChatMessages is called
                                    // No need to register here
                                    
                                    // Update pendingRequests to use the new chat ID
                                    val requestsToUpdate = pendingRequests.filter { it.value == currentChatId }
                                    requestsToUpdate.forEach { (requestId, _) ->
                                        pendingRequests[requestId] = effectiveConversationId
                                        Log.d(TAG, "📝 Updated pending request $requestId from chat $currentChatId to $effectiveConversationId")
                                    }
                                    
                                    // Update the chat ID immediately so reconnections use the correct ID
                                    _chatId.value = effectiveConversationId
                                    
                                    // Update ConnectionStateManager with the real conversation ID
                                    connectionStateManager.setActiveConversation(effectiveConversationId)
                                    
                                    Log.d(TAG, "🔄 IMMEDIATE MIGRATION: Updated chat ID from $currentChatId to $effectiveConversationId BEFORE message migration")
                                    
                                    // Message migration will happen later in the flow if needed
                                }
                            }
                            
                            // Only attempt JSON parsing for error handling if the text looks like JSON
                            try {
                                if (event.text.trimStart().startsWith("{")) {
                                    val jsonObject = JSONObject(event.text)
                                    
                                    if (jsonObject.has("error") && jsonObject.has("status_code")) {
                                    val errorMsg = jsonObject.getString("error")
                                    val statusCode = jsonObject.getInt("status_code")

                                    if (statusCode == 401 && errorMsg.contains("Asana authentication failed")) {
                                        Log.w(TAG, "Detected Asana 401 error from tool result. Showing Asana setup dialog.")
                                        messageContentForChat = "Asana authentication failed. Please update your token in Settings."
                                        _showAsanaSetupDialog.value = true // Show dialog instead of direct navigation
                                        isErrorHandled = true 
                                        speakThisMessage = false 
                                    }
                                }
                                else if (jsonObject.has("type") && jsonObject.getString("type") == "error" && jsonObject.has("code")) {
                                    val errorCode = jsonObject.getString("code")
                                    val errorMessageFromServer = jsonObject.optString("message", "Server error occurred")
                                    messageContentForChat = errorMessageFromServer 
                                    speakThisMessage = false 
                                    isErrorHandled = true 

                                    when (errorCode) {
                                        "ASANA_AUTH_ERROR", "AsanaAuthErrorHandled" -> {
                                            Log.w(TAG, "Handling structured ASANA_AUTH_ERROR from server: $errorMessageFromServer")
                                            _showAsanaSetupDialog.value = true // Show dialog
                                        }
                                        "CLAUDE_AUTHENTICATION_ERROR", "CLAUDE_API_KEY_MISSING" -> {
                                            Log.w(TAG, "Handling Claude authentication error ($errorCode) from server: $errorMessageFromServer")
                                            _showAuthErrorDialog.value = errorMessageFromServer 
                                        }
                                        else -> {
                                            val errorDetail = jsonObject.optString("detail", "")
                                            messageContentForChat = "Server Error: $errorMessageFromServer ${if (errorDetail.isNotEmpty()) "- $errorDetail" else ""}"
                                            Log.e(TAG, "Structured server error (unhandled code '$errorCode'): $messageContentForChat")
                                        }
                                    }
                                    } else {
                                        isErrorHandled = false 
                                    }
                                } else {
                                    // Text doesn't look like JSON, treat as regular response
                                    isErrorHandled = false
                                }
                            } catch (e: JSONException) {
                                // Message JSON parsing failed (expected for normal responses)
                                isErrorHandled = false 
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing JSON message", e)
                                isErrorHandled = false
                            }

                            // 🔧 Enhanced request ID validation with error handling and conversation_id sync
                            val targetChatId = try {
                                // 🔧 VOICE MESSAGE DEBUG: Comprehensive logging for targetChatId calculation
                                Log.d(TAG, "🐛 VOICE_DEBUG: Starting targetChatId calculation")
                                Log.d(TAG, "🐛 VOICE_DEBUG: event.requestId = ${event.requestId}")
                                Log.d(TAG, "🐛 VOICE_DEBUG: effectiveConversationId = $effectiveConversationId")
                                Log.d(TAG, "🐛 VOICE_DEBUG: current _chatId.value = ${_chatId.value}")
                                Log.d(TAG, "🐛 VOICE_DEBUG: pendingRequests = $pendingRequests")
                                
                                if (event.requestId != null) {
                                    if (pendingRequests.containsKey(event.requestId)) {
                                        val originalChatId = pendingRequests[event.requestId]!!
                                        Log.d(TAG, "🐛 VOICE_DEBUG: Found requestId in pendingRequests, originalChatId = $originalChatId")
                                        // Remove completed request from pending requests
                                        pendingRequests.remove(event.requestId) // Remove completed request
                                        
                        // 🔧 NEW: Handle new chat creation with server-assigned conversation_id
                        if (originalChatId == -1L && effectiveConversationId != null) {
                            // Update local chat ID to match server-assigned ID
                            _chatId.value = effectiveConversationId
                            // Update WhizServerRepository so reconnections use the correct ID
                            // No longer needed - chatId is passed directly to sendMessage
                            // Updated local chat ID to server-assigned conversation ID
                            effectiveConversationId
                        } else if (effectiveConversationId != null && originalChatId != effectiveConversationId) {
                            // Handle case where local chat has temp ID but server provides real ID
                            
                            // 🔧 FIXED: Distinguish between chat migration and regular message processing
                            // Only migrate when transitioning from optimistic chat to server chat
                            val isOptimisticChat = originalChatId < 0
                            val hasServerId = effectiveConversationId > 0
                            val isChatTransition = isOptimisticChat && hasServerId && originalChatId != effectiveConversationId
                            
                            Log.d(TAG, "🔧 CHAT_TRANSITION_CHECK: isOptimisticChat=$isOptimisticChat, hasServerId=$hasServerId, originalChatId=$originalChatId, effectiveConversationId=$effectiveConversationId, isChatTransition=$isChatTransition")
                            
                            if (isChatTransition) {
                                // Starting migration to sync with server conversation ID
                                

                                
                                // 🔧 CRITICAL: Migrate local messages from optimistic chat to server conversation
                                // Use withContext(Dispatchers.IO) to move database operations to background thread
                                try {
                                    val migrationStartTime = System.currentTimeMillis()
                                    val migrationSuccess = withContext(Dispatchers.IO) {
                                        repository.migrateChatMessages(originalChatId, effectiveConversationId)
                                    }
                                    val migrationDuration = System.currentTimeMillis() - migrationStartTime
                                    if (migrationSuccess) {
                                        
                                        // 🔧 REMOVED: Manual refresh is unnecessary - messages flow updates automatically
                                        // The database update from migration will trigger the flow naturally
                                        
                                        // 🔧 CROSS-CHAT MIGRATION: Handle UI switching during rapid messaging
                                        val currentChatIsOptimistic = _chatId.value < 0
                                        val shouldSwitchToMigratedChat = currentChatIsOptimistic || _chatId.value == originalChatId
                                        
                                        Log.d(TAG, "🔧 CHAT_ID_UPDATE: currentChatIsOptimistic=$currentChatIsOptimistic, shouldSwitch=$shouldSwitchToMigratedChat, _chatId=${_chatId.value}, originalChatId=$originalChatId, effectiveConversationId=$effectiveConversationId")
                                        
                                        if (shouldSwitchToMigratedChat) {
                                            // Check if migration was already done by immediate migration above
                                            val alreadyMigrated = _chatId.value == effectiveConversationId
                                            if (!alreadyMigrated) {
                                                // 🔧 Migration will be registered when migrateChatMessages is called
                                                val oldChatId = _chatId.value
                                                if (oldChatId < 0 && effectiveConversationId > 0) {
                                                    // No need to register here - migrateChatMessages will handle it
                                                    Log.d(TAG, "🔄 Chat migration needed: $oldChatId → $effectiveConversationId")
                                                    
                                                    // Update pendingRequests to use the new chat ID
                                                    val requestsToUpdate = pendingRequests.filter { it.value == oldChatId }
                                                    requestsToUpdate.forEach { (requestId, _) ->
                                                        pendingRequests[requestId] = effectiveConversationId
                                                        Log.d(TAG, "📝 Updated pending request $requestId from chat $oldChatId to $effectiveConversationId")
                                                    }
                                                }
                                                
                                                Log.d(TAG, "🔧 CHAT_ID_UPDATE: Updating _chatId from ${_chatId.value} to $effectiveConversationId")
                                                _chatId.value = effectiveConversationId
                                                connectionStateManager.setActiveConversation(effectiveConversationId)
                                                
                                                // Update WhizServerRepository so reconnections use the correct ID
                                                // No longer needed - chatId is passed directly to sendMessage
                                                Log.d(TAG, "Updated WhizServerRepository conversation ID to $effectiveConversationId for proper reconnection")
                                            } else {
                                                Log.d(TAG, "🔧 CHAT_ID_UPDATE: Chat ID already migrated to $effectiveConversationId")
                                            }
                                            
                                            // Note: Input text preservation removed - StateFlow maintains input across recomposition
                                            // The old preservation logic could cause issues if user sent message during migration
                                            
                                        } else {
                                        }
                                    } else {
                                        // Don't update _chatId if migration failed - keep using original chat ID
                                    }
                                } catch (e: Exception) {
                                    // Don't update _chatId if migration threw an exception
                                }
                            } else if (effectiveConversationId != null && originalChatId != effectiveConversationId) {
                                // 🔧 SCENARIO 2: Regular message processing with request ID pairing
                                // This is NOT a migration - just updating chat ID for consistency
                                
                                // 🔧 Migration will be registered when migrateChatMessages is called
                                if (originalChatId < 0 && effectiveConversationId > 0) {
                                    // No need to register here - migrateChatMessages will handle it
                                    Log.d(TAG, "🔄 Chat migration needed (scenario 2): $originalChatId → $effectiveConversationId")
                                    
                                    // Update pendingRequests to use the new chat ID
                                    val requestsToUpdate = pendingRequests.filter { it.value == originalChatId }
                                    requestsToUpdate.forEach { (requestId, _) ->
                                        pendingRequests[requestId] = effectiveConversationId
                                        Log.d(TAG, "📝 Updated pending request $requestId from chat $originalChatId to $effectiveConversationId")
                                    }
                                }
                                
                                // No input text preservation needed - this is just ID sync
                                _chatId.value = effectiveConversationId
                                connectionStateManager.setActiveConversation(effectiveConversationId)
                                // Update WhizServerRepository so reconnections use the correct ID
                                // No longer needed - chatId is passed directly to sendMessage
                            } else {
                                // 🔧 SCENARIO 3: Same chat ID - regular message processing
                            }
                        }
                        
                        // Return the appropriate chat ID after all migration logic
                        if (effectiveConversationId != null && effectiveConversationId != originalChatId) {
                            Log.d(TAG, "🐛 VOICE_DEBUG: Migration scenario - returning effectiveConversationId = $effectiveConversationId")
                            effectiveConversationId
                        } else {
                            Log.d(TAG, "🐛 VOICE_DEBUG: No migration needed - returning originalChatId = $originalChatId")
                            originalChatId
                        }
                    } else {
                                        // Request ID provided but not found in pending requests
                                        Log.w(TAG, "Request ID ${event.requestId} not found in pending requests")

                                        // 🔧 FIX: Accept message if it's for the current chat, even if request completed
                                        // This prevents race condition where request is removed from pendingRequests
                                        // before all WebSocket messages for that request arrive
                                        if (effectiveConversationId != null && effectiveConversationId == _chatId.value) {
                                            Log.d(TAG, "🐛 VOICE_DEBUG: Message is for current chat (conversationId=$effectiveConversationId matches _chatId=${_chatId.value}) - accepting despite request not in pendingRequests")
                                            effectiveConversationId
                                        } else if (event.clientConversationId != null) {
                                            // 🔧 RECONNECTION FIX: Use client_conversation_id if available when pendingRequests is lost
                                            Log.d(TAG, "🐛 VOICE_DEBUG: Using clientConversationId from server: ${event.clientConversationId}")
                                            // Check if this optimistic chat has been migrated to a server-backed ID
                                            val migratedId = repository.getMigratedChatId(event.clientConversationId)
                                            if (migratedId != null) {
                                                Log.d(TAG, "🐛 VOICE_DEBUG: Found migration for clientConversationId ${event.clientConversationId} → $migratedId")
                                                migratedId
                                            } else {
                                                event.clientConversationId
                                            }
                                        } else {
                                            Log.d(TAG, "🐛 VOICE_DEBUG: RequestId not in pendingRequests, conversationId doesn't match current chat, and no clientConversationId - discarding message to prevent cross-chat contamination")
                                            // Discard the message by returning null - will be handled below
                                            null
                                        }
                                    }
                                } else {
                                    // No request ID - check if we have clientConversationId
                                    if (event.clientConversationId != null) {
                                        Log.d(TAG, "🐛 VOICE_DEBUG: No requestId but have clientConversationId: ${event.clientConversationId}")
                                        // Check if this optimistic chat has been migrated to a server-backed ID
                                        val migratedId = repository.getMigratedChatId(event.clientConversationId)
                                        if (migratedId != null) {
                                            Log.d(TAG, "🐛 VOICE_DEBUG: Found migration for clientConversationId ${event.clientConversationId} → $migratedId")
                                            migratedId
                                        } else {
                                            event.clientConversationId
                                        }
                                    } else {
                                        // No request ID or client conversation ID - discard message
                                        Log.w(TAG, "No request ID or client conversation ID provided. Discarding message to prevent cross-chat contamination.")
                                        Log.d(TAG, "🐛 VOICE_DEBUG: No requestId or clientConversationId - discarding message")
                                        null
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing request ID validation", e)
                                _errorState.value = "Error processing server response: ${e.message}"
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }
                            
                            // 🔧 VOICE MESSAGE DEBUG: Log final calculated targetChatId
                            Log.d(TAG, "🐛 VOICE_DEBUG: Final calculated targetChatId = $targetChatId")
                            
                            // 🔧 Handle null targetChatId - discard message
                            if (targetChatId == null) {
                                Log.w(TAG, "Cannot determine target chat ID for message. Discarding to prevent cross-chat contamination.")
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }
                            
                            // 🔧 Check if we need to register a migration (optimistic to server ID)
                            if (event.clientConversationId != null && effectiveConversationId != null && 
                                event.clientConversationId != effectiveConversationId &&
                                event.clientConversationId < 0 && effectiveConversationId > 0) {
                                // This is a migration from optimistic to server-backed chat
                                Log.d(TAG, "🔧 Detected chat migration via WebSocket: ${event.clientConversationId} → $effectiveConversationId")
                                
                                // Migrate messages from optimistic to server chat
                                try {
                                    val migrationSuccess = withContext(Dispatchers.IO) {
                                        repository.migrateChatMessages(event.clientConversationId, effectiveConversationId)
                                    }
                                    
                                    if (migrationSuccess) {
                                        Log.d(TAG, "✅ Successfully migrated messages from ${event.clientConversationId} to $effectiveConversationId")
                                        // Migration already registered by migrateChatMessages
                                        
                                        // Update pendingRequests to use the new chat ID
                                        val requestsToUpdate = pendingRequests.filter { it.value == event.clientConversationId }
                                        requestsToUpdate.forEach { (requestId, _) ->
                                            pendingRequests[requestId] = effectiveConversationId
                                            Log.d(TAG, "📝 Updated pending request $requestId from chat ${event.clientConversationId} to $effectiveConversationId")
                                        }
                                        
                                        // If the current chat is the optimistic one, update it to the server ID
                                        if (_chatId.value == event.clientConversationId) {
                                            Log.d(TAG, "📝 Updating current chat ID from ${_chatId.value} to $effectiveConversationId")
                                            _chatId.value = effectiveConversationId
                                            connectionStateManager.setActiveConversation(effectiveConversationId)
                                            // Update WhizServerRepository so reconnections use the correct ID
                                            // No longer needed - chatId is passed directly to sendMessage
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error migrating chat messages", e)
                                }
                            }
                            
                            // 🔧 FIXED: Check if response is for current chat AFTER chat ID synchronization
                            val isResponseForCurrentChat = (targetChatId == _chatId.value)
                            
                            if (!isResponseForCurrentChat) {
                                Log.w(TAG, "Response is for chat $targetChatId but current chat is ${_chatId.value}. Skipping processing to prevent cross-chat contamination.")
                                // 🔧 Update responding state for current chat since we're not processing this response
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }
                            
                            if (targetChatId != 0L) {
                                // Only save assistant message to local DB if NOT using remote agent
                                // Remote agent (WebSocket server) handles message persistence
                                if (!configUseRemoteAgent) {
                                    try {
                                        viewModelScope.launch {
                                            val messageId = repository.addAssistantMessage(
                                                chatId = targetChatId,
                                                content = messageContentForChat
                                            )
                                        }
                                    } catch (e: Exception) {
                                        _errorState.value = "Error saving response: ${e.message}"
                                    }
                                } else {
                                    // For remote agent, we need to manually refresh to show the server-saved message
                                    
                                    // Update bubble overlay if active
                                    Log.d(TAG, "[BUBBLE_DEBUG] Checking bubble status: isActive=${com.example.whiz.services.BubbleOverlayService.isActive}, isPendingStart=${com.example.whiz.services.BubbleOverlayService.isPendingStart}")
                                    if (com.example.whiz.services.BubbleOverlayService.isActive || com.example.whiz.services.BubbleOverlayService.isPendingStart) {
                                        Log.d(TAG, "[BUBBLE_DEBUG] Updating bubble with bot response: '$messageContentForChat'")
                                        com.example.whiz.services.BubbleOverlayService.updateBotResponse(messageContentForChat)
                                    }
                                    
                                    try {
                                        viewModelScope.launch {
                                            // 🔧 NEW: Use request ID pairing to insert assistant message after corresponding user message
                                            // This ensures responses appear in the correct order relative to their user messages
                                            val messageId = if (event.requestId != null) {
                                                repository.addAssistantMessageAfterRequest(targetChatId, messageContentForChat, event.requestId)
                                            } else {
                                                // Fallback: add at end if no request ID
                                                repository.addAssistantMessageOptimistic(targetChatId, messageContentForChat)
                                            }
                                            _scrollToBottomEvent.tryEmit(Unit) // Scroll to show bot response
                                        }
                                    } catch (e: Exception) {
                                    }
                                    
                                    // Only refresh conversations if a new one might have been created
                                    if (targetChatId <= 0) {
                                        try {
                                            viewModelScope.launch {
                                                // Remove arbitrary delay - refresh immediately when needed
                                                repository.refreshConversations()
                                            }
                                        } catch (e: Exception) {
                                        }
                                    }
                                }
                            } else {
                            }

                            // TTS and UI updates (only for current chat)
                            // 🔧 Update responding state FIRST, before trying to restart listening
                            updateRespondingStateForCurrentChat()
                            
                            try {
                                // Determine if this message is fresh enough to speak
                                val currentTime = System.currentTimeMillis()
                                val timeSinceBackgrounding = if (lastBackgroundedTime > 0) {
                                    currentTime - lastBackgroundedTime
                                } else {
                                    Long.MAX_VALUE // Never backgrounded
                                }
                                
                                val isMessageFresh = if (timeSinceBackgrounding <= 3000L) {
                                    // We JUST returned from background (within 3 seconds)
                                    // Only speak messages that arrived AFTER backgrounding
                                    val isFresh = messageReceivedTime > lastBackgroundedTime
                                    if (!isFresh) {
                                        Log.d(TAG, "[LOG] Skipping TTS - message received at $messageReceivedTime before backgrounding at $lastBackgroundedTime")
                                    }
                                    isFresh
                                } else {
                                    // Either never backgrounded OR backgrounded long ago (>3 seconds)
                                    // Only speak recent messages (arrived within last 3 seconds)
                                    val messageAge = currentTime - messageReceivedTime
                                    val isFresh = messageAge <= 3000L
                                    if (!isFresh) {
                                        Log.d(TAG, "[LOG] Skipping TTS - message is ${messageAge}ms old (too old to speak)")
                                    }
                                    isFresh
                                }
                                
                                // 🔧 Additional validation: Only speak if this is truly for the current visible chat
                                // and the message is actually being displayed to the user
                                // Use VoiceManager's shouldEnableTTS which checks bubble mode properly
                                val ttsEnabled = voiceManager.shouldEnableTTS()
                                val shouldSpeak = ttsEnabled && 
                                                _isTTSInitialized.value && 
                                                speakThisMessage && 
                                                messageContentForChat.isNotBlank() &&
                                                isResponseForCurrentChat && 
                                                targetChatId != 0L && // Allow speaking for both positive (server) and negative (optimistic) chat IDs
                                                targetChatId == _chatId.value && // Double-check current chat
                                                isMessageFresh // Only speak fresh messages
                                
                                Log.d(TAG, "TTS Decision: ttsEnabled=$ttsEnabled, ttsInit=${_isTTSInitialized.value}, " +
                                        "speakThis=$speakThisMessage, fresh=$isMessageFresh, shouldSpeak=$shouldSpeak")
                                
                                if (shouldSpeak) {
                                    // Check if user has active partial transcription
                                    val hasPartialTranscription = voiceManager.transcriptionState.value.isNotBlank()

                                    if (hasPartialTranscription) {
                                        Log.d(TAG, "User is speaking - queueing TTS instead of starting immediately: '$messageContentForChat'")
                                        // Always update to the latest message - replaces any existing queued message
                                        pendingTTSMessage = messageContentForChat
                                        // Don't stop listening, let user finish
                                    } else {
                                        // No active speech - start TTS immediately
                                        if (isListening.value) {
                                            wasListeningBeforeTTS = true
                                            voiceManager.stopListening() // Stop STT before TTS speaks
                                        }
                                        val utteranceId = UUID.randomUUID().toString()
                                        ttsManager.speak(messageContentForChat, "chat_message")
                                        // TTSManager will handle the isSpeaking state
                                    }
                                } else {
                                    // Always restart continuous listening after assistant reply if enabled and not speaking
                                    if (voiceManager.isContinuousListeningEnabled.value && !isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Restarting continuous listening after assistant reply.")
                                        startContinuousListening()
                                    } else if (wasListeningBeforeTTS && !isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not speaking, wasListeningBeforeTTS=true, restarting ASR.")
                                        voiceManager.startListening { finalTranscription ->
                                            if (finalTranscription.isNotBlank()) {
                                                sendUserInput(finalTranscription)
                                            }
                                        }
                                        wasListeningBeforeTTS = false
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in TTS/listening handling", e)
                            }
                            
                            val processingEndTime = System.currentTimeMillis()
                            val totalProcessingTime = processingEndTime - messageReceivedTime
                            
                            if (totalProcessingTime > 500) {
                                Log.w(TAG, "Slow WebSocket message processing: ${totalProcessingTime}ms")
                            }

                            // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing WebSocket message", e)
                            _errorState.value = "Error processing server message: ${e.message}"
                            updateRespondingStateForCurrentChat()
                        }
                    }
                }
            }
        }
        
        // Collect tool execution results and send them back to server
        viewModelScope.launch {
            Log.i(TAG, "[TOOL_COLLECTOR] Starting tool result collector")
            toolExecutor.toolResults.collect { result ->
                Log.i(TAG, "[TOOL_COLLECTOR] Received tool execution result: $result")
                
                val currentChatId = _chatId.value
                if (currentChatId == -1L || currentChatId == 0L) {
                    Log.w(TAG, "[TOOL_COLLECTOR] Cannot send tool result - no active chat (chatId=$currentChatId)")
                    return@collect
                }
                
                when (result) {
                    is ToolExecutionResult.Success -> {
                        // Check if overlay permission is required
                        if (result.toolName == "launch_app" && 
                            result.result.has("overlayPermissionRequired") && 
                            result.result.getBoolean("overlayPermissionRequired")) {
                            // Show overlay permission dialog
                            _showOverlayPermissionDialog.value = true
                        }
                        
                        // Add status field to the result
                        val resultWithStatus = org.json.JSONObject().apply {
                            put("status", "success")
                            // Copy all fields from the original result
                            val keys = result.result.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, result.result.get(key))
                            }
                        }
                        
                        val toolResultTimestamp = System.currentTimeMillis()
                        Log.i(TAG, "📮 [TOOL_COLLECTOR] Sending tool result to server: requestId=${result.requestId}, chatId=$currentChatId, timestamp=$toolResultTimestamp")
                        Log.i(TAG, "📮 [TOOL_COLLECTOR] Tool name: ${result.toolName}")
                        Log.i(TAG, "📮 [TOOL_COLLECTOR] Result: ${resultWithStatus.toString(2)}")

                        val success = whizServerRepository.sendToolResult(
                            toolName = result.toolName,
                            requestId = result.requestId,
                            result = resultWithStatus,
                            chatId = currentChatId,
                            timestamp = toolResultTimestamp
                        )
                        if (!success) {
                            Log.e(TAG, "❌ [TOOL_COLLECTOR] Failed to send tool result to server for requestId=${result.requestId}")
                        } else {
                            Log.i(TAG, "✅ [TOOL_COLLECTOR] Successfully sent tool result to server for requestId=${result.requestId}")
                        }
                    }
                    is ToolExecutionResult.Error -> {
                        val errorResult = org.json.JSONObject().apply {
                            put("status", "error")
                            put("error", result.error)
                        }
                        val success = whizServerRepository.sendToolResult(
                            toolName = result.toolName,
                            requestId = result.requestId,
                            result = errorResult,
                            chatId = currentChatId,
                            timestamp = System.currentTimeMillis()
                        )
                        if (!success) {
                            Log.e(TAG, "Failed to send tool error result to server")
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleServerMessage(responseText: String) {
        var currentChatId = _chatId.value // Get current ID before modification

        // Check if a chat needs to be created
        if (currentChatId <= 0) {
            Log.d(TAG, "handleServerMessage: No active chat. Creating new chat for initial server message.")
            // Derive a title from the server message or use a default
            val chatTitle = repository.deriveChatTitle(responseText).ifBlank { "Assistant Chat" } // Use helper or default
            val newChatId = repository.createChat(chatTitle)

            // Update ViewModel state IMMEDIATELY
            _chatId.value = newChatId
            _chatTitle.value = chatTitle
            currentChatId = newChatId // Update local variable to use the new ID below

            Log.d(TAG, "handleServerMessage: Created chat $currentChatId with title '$chatTitle'")
            // Note: The `messages` flow should automatically switch to this new chat ID
            // because it uses `_chatId.flatMapLatest`.
        }

        // Now we are guaranteed to have a valid currentChatId
        Log.d(TAG, "handleServerMessage: Adding assistant message to chat $currentChatId")
        repository.addAssistantMessage(currentChatId, responseText)

        _isResponding.value = false // Agent has responded
        
        // Note: Input text clearing is handled when optimistic message appears in UI

        // Speak the response if enabled
        if (isVoiceResponseEnabled.value && _isTTSInitialized.value) {
            speakAgentResponse(responseText)
        }
    }

    // --- Public Functions ---

    fun loadChat(chatId: Long) {
        loadChatWithVoiceMode(chatId, false)
    }
    
    fun loadChatWithVoiceMode(chatId: Long, isVoiceModeActivation: Boolean = false) {
        Log.d(TAG, "🔥 loadChatWithVoiceMode STARTED for chatId: $chatId, voiceMode: $isVoiceModeActivation")
        Log.d(TAG, "🔥 STACK TRACE for loadChatWithVoiceMode:", Exception("Stack trace"))
        Log.d(TAG, "[RACE_DEBUG] loadChatWithVoiceMode: Current input text: '${_inputText.value}'")
        // Clear any previous chat load error when starting to load a new chat
        _chatLoadError.value = null
        viewModelScope.launch {
            try {
                // 🔧 Enhanced cleanup when switching chats
                Log.d(TAG, "Loading chat $chatId. Current chat: ${_chatId.value}, Pending requests: ${pendingRequests.size}")
                
                // Disconnect from server if switching chats or loading new
                if (configUseRemoteAgent) {
                    try {
                        // Clear reconnection flag since this is an intentional disconnect
                        isReconnectingAfterDisconnect = false
                        chatIdWhenDisconnected = null
                        whizServerRepository.disconnect()
                        _isConnectedToServer.value = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disconnecting WebSocket during loadChat", e)
                    }
                }
                
                // 🔧 Clear pending requests for OTHER chats only - preserve requests for current chat
                // This fixes the bug where thinking indicator disappears when navigating away and back
                try {
                    if (pendingRequests.isNotEmpty()) {
                        val requestsForOtherChats = pendingRequests.filter { it.value != chatId }
                        if (requestsForOtherChats.isNotEmpty()) {
                            Log.w(TAG, "🔥 loadChat: Clearing ${requestsForOtherChats.size} pending requests for other chats: ${requestsForOtherChats.keys}")
                            pendingRequests.entries.removeIf { it.value != chatId }
                        }
                        val remainingRequests = pendingRequests.filter { it.value == chatId }
                        if (remainingRequests.isNotEmpty()) {
                            Log.d(TAG, "🔥 loadChat: Preserving ${remainingRequests.size} pending requests for current chat: ${remainingRequests.keys}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing pending requests during loadChat", e)
                }

                if (chatId == -1L) {
                    // Handle new chat creation - immediately reset responding state since this is a fresh chat
                    // Note: Only treat -1 as "new chat", not all negative numbers (optimistic IDs are negative)
                    _chatId.value = -1
                    _chatTitle.value = "New Chat"
                    _isResponding.value = false // 🔧 Immediately set to false for new chats
                    Log.d(TAG, "🔥 loadChat: Setup for new chat (ID: -1), responding state reset to false")

                    // Prime WebSocket connection early for new chats - saves 150-250ms when user sends first message
                    // Connection will be associated with conversation when first message includes client_conversation_id
                    if (configUseRemoteAgent) {
                        try {
                            Log.d(TAG, "🔥 loadChat: Priming WebSocket connection for new chat")
                            whizServerRepository.primeConnection()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to prime WebSocket connection", e)
                            // Non-fatal - connection will be established on first message
                        }
                    }

                    // Refresh conversations list when going to home page
                    try {
                        repository.refreshConversations()
                        Log.d(TAG, "loadChat: Triggered conversations refresh for home page")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refreshing conversations during loadChat for home page", e)
                    }
                } else {
                    // ... (handle loading existing chat as before)
                    Log.d(TAG, "Loading chat with ID: $chatId")
                    try {
                        val chat = repository.getChatById(chatId)
                        if (chat != null) {
                            _chatId.value = chatId
                            _chatTitle.value = chat.title
                            Log.d(TAG, "🔥 loadChat: LOADED chat: ${chat.title} (ID: $chatId)")
                            // 🔧 For existing chats, update based on actual pending requests
                            updateRespondingStateForCurrentChat()
                        } else {
                            // Check if this is an optimistic chat ID (negative but not -1)
                            if (chatId < -1) {
                                // This is an optimistic chat that hasn't been persisted yet
                                // Keep the optimistic ID - don't reset to -1
                                _chatId.value = chatId
                                _chatTitle.value = "New Chat"
                                Log.d(TAG, "🔥 loadChat: Optimistic chat ID $chatId - keeping it, not resetting to -1")
                                updateRespondingStateForCurrentChat()

                                // Prime WebSocket connection for optimistic chats too
                                if (configUseRemoteAgent) {
                                    try {
                                        Log.d(TAG, "🔥 loadChat: Priming WebSocket connection for optimistic chat $chatId")
                                        whizServerRepository.primeConnection()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to prime WebSocket connection", e)
                                    }
                                }
                            } else {
                                // Only reset to -1 if we couldn't find a positive chat ID
                                _chatId.value = -1
                                _chatTitle.value = "New Chat"
                                _isResponding.value = false // 🔧 Immediately set to false for new chats
                                Log.d(TAG, "🔥 loadChat: Chat not found, creating new chat, responding state reset to false")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading chat from repository", e)
                        // Set chat load error instead of starting new chat
                        _chatLoadError.value = "Couldn't load this chat"
                        _chatId.value = chatId // Keep the requested chat ID
                        _chatTitle.value = "Chat"
                        _isResponding.value = false
                        _errorState.value = null // Clear general error state
                    }
                }
                
                // Don't clear input text - if it's the same chat, preserve what user was typing
                // If it's a different chat, we have a new ViewModel instance anyway
                Log.d(TAG, "[LOG] loadChat: Preserving input text. Current value: '${_inputText.value}'")
                // 🔧 Update responding state based on current chat's pending requests
                updateRespondingStateForCurrentChat()
                _errorState.value = null // 🔧 Clear any error states when switching chats
                _connectionError.value = null // 🔧 Clear connection errors too
                // Don't clear _chatLoadError here - it may have been set in the catch block above
                
                // 🔧 Reset voice responses to default (off) when loading a chat, UNLESS voice mode is being activated
                // This prevents voice responses from staying on from previous sessions
                // Voice mode will explicitly re-enable it if needed
                if (!isVoiceModeActivation) {
                    if (isVoiceResponseEnabled.value) {
                        Log.d(TAG, "[LOG] loadChat: Resetting voice responses to OFF (was ON)")
                        voiceManager.setVoiceResponseEnabled(false)
                    } else {
                        Log.d(TAG, "[LOG] loadChat: Voice responses already OFF")
                    }
                } else {
                    Log.d(TAG, "[LOG] loadChat: Voice mode activation - preserving voice response state (current: ${isVoiceResponseEnabled.value})")
                }
                
                try {
                    // Don't stop continuous listening - let it keep running across chat navigation
                    if (voiceManager.isListening.value && !voiceManager.isContinuousListeningEnabled.value) {
                        // Only stop if it's a one-time listening session (not continuous)
                        Log.d(TAG, "loadChat: Stopping one-time listening session")
                        voiceManager.stopListening()
                    } else {
                        Log.d(TAG, "loadChat: Keeping listening state as-is (continuous or not listening)")
                    }
                    Log.d(TAG, "loadChat: Stopping TTS (isSpeaking: ${isSpeaking.value})")
                    ttsManager.stop()
                    Log.d(TAG, "loadChat: Speech services handled successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error managing speech services during loadChat", e)
                }

                // Refresh messages to ensure we have latest data
                // Fetch for both real chats (>0) and optimistic chats (<-1) that might have been migrated
                // Skip only for new chat placeholder (-1) and uninitialized (0)
                if (_chatId.value != 0L && _chatId.value != -1L) {
                    try {
                        Log.d(TAG, "loadChat: Performing sync for chat ${_chatId.value}")
                        // Use retry mechanism to handle race conditions with optimistic chats
                        val serverMessages = repository.fetchMessagesWithRetry(_chatId.value)
                        Log.d(TAG, "loadChat: Retrieved ${serverMessages.size} messages from server for chat ${_chatId.value}")
                        
                        // The fetchMessagesWithRetry method already handles storing messages and deduplication
                        // Just trigger messages refresh to update UI
                        repository.refreshMessages()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing messages during loadChat", e)
                        _errorState.value = "Failed to sync messages: ${e.message}"
                    }
                }

                // Connect to server if needed *after* chat ID is set
                // BUT respect manual disconnect flag (for testing connection errors)
                Log.d(TAG, "🔌 Checking WebSocket reconnect: configUseRemoteAgent=$configUseRemoteAgent, _chatId.value=${_chatId.value}, isConnected=${whizServerRepository.isConnected()}, persistentDisconnectForTest=${whizServerRepository.persistentDisconnectForTest()}")
                // Connect for existing chats (chatId > 0) and optimistic chats (chatId < -1)
                // Don't connect only for the "new chat" placeholder (chatId = -1) or uninitialized state (chatId = 0)
                // New chats will connect when sending the first message with an optimistic ID
                if (configUseRemoteAgent && _chatId.value != -1L && _chatId.value != 0L && !whizServerRepository.persistentDisconnectForTest()) {
                    try {
                        Log.d(TAG, "🔌 Reconnecting WebSocket after loadChat for chat ${_chatId.value}...")
                        Log.d(TAG, "🔌 WEBSOCKET CONNECT TRACE - chatId: ${_chatId.value}, requested chatId: $chatId", Exception("WebSocket connect stack trace"))
                        delay(100) // Small delay to ensure state propagation
                        // Connect with the specific chat ID
                        whizServerRepository.connect(_chatId.value)
                        Log.d(TAG, "🔌 WebSocket reconnect initiated for chat ${_chatId.value}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error connecting to WebSocket during loadChat", e)
                        _connectionError.value = "Failed to connect to server: ${e.message}"
                    }
                } else if (whizServerRepository.persistentDisconnectForTest()) {
                    Log.d(TAG, "🔌 Skipping WebSocket reconnect - manually disconnected")
                }
                
                // Check for orphaned messages that need retry
                // Include optimistic chats (negative IDs) that may have unsent messages
                if (configUseRemoteAgent && _chatId.value != 0L && _chatId.value != -1L) {
                    checkAndRetryOrphanedMessages(_chatId.value)
                }

                // 🎙️ VOICE APP BEHAVIOR: Update permission state
                val actualPermissionState = PermissionHandler.hasMicrophonePermission(context)
                if (_micPermissionGranted.value != actualPermissionState) {
                    Log.d(TAG, "[LOG] Updating permission state from ${_micPermissionGranted.value} to $actualPermissionState")
                    _micPermissionGranted.value = actualPermissionState
                }

                // Note: We no longer stop/restart continuous listening in loadChat
                // If continuous listening is enabled, it stays running throughout the chat load
                // This avoids race conditions with async stop/start IPC calls
                Log.d(TAG, "[LOG] Chat load complete - continuous listening preserved if it was enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in loadChat", e)
                _errorState.value = "Failed to load chat: ${e.message}"
                // Try to recover to a safe state
                try {
                    _chatId.value = -1
                    _chatTitle.value = "New Chat"
                    updateRespondingStateForCurrentChat()
                } catch (recoveryError: Exception) {
                    Log.e(TAG, "Error during loadChat recovery", recoveryError)
                }
            }
            Log.d(TAG, "🔥 loadChatWithVoiceMode COMPLETED for chatId: $chatId, final _chatId.value: ${_chatId.value}")
            Log.d(TAG, "[RACE_DEBUG] loadChatWithVoiceMode COMPLETED: Final input text: '${_inputText.value}'")
        }
    }

    /**
     * Sync messages when returning to a chat screen
     * This is lighter weight than loadChat - it just fetches new messages without resetting state
     */
    fun syncMessagesIfNeeded(chatId: Long) {
        viewModelScope.launch {
            try {
                // Only sync if this is the current chat
                if (_chatId.value == chatId && chatId != 0L && chatId != -1L) {
                    Log.d(TAG, "📥 Syncing messages for chat $chatId on screen resume")
                    
                    // Use the retry mechanism to fetch any new messages
                    val serverMessages = repository.fetchMessagesWithRetry(chatId)
                    Log.d(TAG, "📥 Sync complete: Retrieved ${serverMessages.size} messages for chat $chatId")
                    
                    // The fetchMessagesWithRetry method already handles storing messages and deduplication
                    // Just trigger messages refresh to update UI
                    repository.refreshMessages()
                } else {
                    Log.d(TAG, "📥 Skipping sync - chat ID mismatch or invalid. Current: ${_chatId.value}, Requested: $chatId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing messages on resume", e)
                // Don't show error to user - this is a background sync
            }
        }
    }
    
    /**
     * Migrate chat ID without disconnecting WebSocket - used for chat state transitions
     * like -1 to negative (new chat creation) or negative to positive (optimistic to server-backed)
     */
    fun migrateChatId(oldId: Long, newId: Long) {
        Log.d(TAG, "📝 Migrating chat ID from $oldId to $newId (keeping WebSocket connected)")
        
        // Just update the chat ID - the messages flow will automatically update
        // since it's derived from _chatId
        _chatId.value = newId
        
        // Note: Messages are stored in the database and automatically filtered by chatId
        // through the messages flow, so we don't need to update them manually
        
        // The WebSocket connection remains intact, which is the key benefit
        Log.d(TAG, "✅ Chat ID migration complete. WebSocket remains connected: ${_isConnectedToServer.value}")
        
        // Note: We don't need to handle any in-flight messages here because:
        // - If a message was sent successfully, it's already on the server
        // - If a message failed and is in the retry queue, processRetryQueue() will handle it automatically
        // - The retry queue is processed automatically on WebSocket reconnection
        // - Messages are never "lost" during migration - they're either sent or queued
    }

    fun updateInputText(text: String, fromVoice: Boolean = false) {
        Log.d(TAG, "[LOG] updateInputText called. Setting text from '${_inputText.value}' to: '$text', fromVoice: $fromVoice")
        Log.d(TAG, "[RACE_DEBUG] updateInputText: Stack trace: ${Thread.currentThread().stackTrace.take(5).joinToString { it.toString() }}")
        
        _inputText.value = text
        Log.d(TAG, "[RACE_DEBUG] updateInputText: Text actually set to: '${_inputText.value}'")
        _isInputFromVoice.value = fromVoice
        
        // 🔧 NEW: Auto-disable continuous listening when user starts typing
        // This ensures the send button appears when user types text
        if (!fromVoice && text.isNotEmpty()) {
            if (voiceManager.isContinuousListeningEnabled.value) {
                Log.d(TAG, "[LOG] 🔥 updateInputText: User started typing - auto-disabling continuous listening")
                voiceManager.updateContinuousListeningEnabled(false)
            }
            // Stop any active listening (regular or continuous) when user types
            if (voiceManager.isListening.value) {
                Log.d(TAG, "[LOG] 🔥 updateInputText: User started typing - stopping active listening")
                voiceManager.stopListening()
            }
        }
        
        // Clear voice flag when text is empty (reset state)
        if (text.isEmpty()) {
            _isInputFromVoice.value = false
        }
    }

    fun toggleSpeechRecognition() {
        Log.d(TAG, "[LOG] toggleSpeechRecognition called. isSpeaking=${isSpeaking.value}, isResponding=${_isResponding.value}, micPermissionGranted=${_micPermissionGranted.value}, isListening=${isListening.value}, continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}")
        if (!_micPermissionGranted.value) {
            Log.w(TAG, "[LOG] Microphone permission not granted, requesting permission")
            // Request permission instead of just showing an error
            onRequestMicrophonePermission?.invoke()
            return
        }
        
        // Case 1: Microphone is actively listening - always allow stopping
        if (isListening.value) {
            Log.d(TAG, "[LOG] Stopping active speech recognition and continuous listening")
            voiceManager.updateContinuousListeningEnabled(false)
            voiceManager.stopListening()
            return
        }
        
        // Case 2: Continuous listening is enabled but mic not actively listening
        // Allow user to disable continuous listening mode
        if (voiceManager.isContinuousListeningEnabled.value) {
            Log.d(TAG, "[LOG] Disabling continuous listening mode (was enabled but not actively listening)")
            voiceManager.updateContinuousListeningEnabled(false)
            return
        }
        
        // Case 3: Want to enable continuous listening - only block during TTS speaking
        // 🔧 FIXED: Allow microphone during server responses, only block during TTS
        if (ttsManager.isSpeaking.value) {
            Log.d(TAG, "[LOG] Cannot start listening while assistant is speaking (TTS)")
            _errorState.value = "Cannot start listening while assistant is speaking"
            return
        }
        
        // Case 4: Enable continuous listening and start immediately unless TTS is active
        Log.d(TAG, "[LOG] Starting speech recognition and enabling continuous listening. Clearing _inputText.value. Previous value: '${_inputText.value}'")
        Log.d(TAG, "[RACE_DEBUG] startContinuousListening: About to clear input text. Stack trace: ${Thread.currentThread().stackTrace.take(5).joinToString { it.toString() }}")
        _inputText.value = ""
        Log.d(TAG, "[RACE_DEBUG] startContinuousListening: Input text cleared to: '${_inputText.value}'")
        voiceManager.updateContinuousListeningEnabled(true)
        
        // 🔧 IMPROVED: Start listening immediately unless TTS is speaking
        // Allow listening during server responses for better UX
        if (!isSpeaking.value) {
            Log.d(TAG, "[LOG] Starting microphone immediately (isSpeaking=${isSpeaking.value}, isResponding=${_isResponding.value})")
            voiceManager.startListening { recognizedText ->
                Log.d(TAG, "[LOG] toggleSpeechRecognition startListening callback. recognizedText: '$recognizedText'")
                if (recognizedText.isNotBlank()) {
                    sendUserInput(recognizedText)
                }
            }
        } else {
            Log.d(TAG, "[LOG] Delaying microphone start - TTS is speaking (${isSpeaking.value})")
        }
    }

    // Method specifically for ensuring continuous listening is enabled (for voice mode activation)
    fun ensureContinuousListeningEnabled() {
        // Add stack trace to debug why this is called during typing
        val stackTrace = Thread.currentThread().stackTrace
        Log.d(TAG, "[LOG] ensureContinuousListeningEnabled called. micPermissionGranted=${_micPermissionGranted.value}, continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, isListening=${isListening.value}, isSpeaking=${isSpeaking.value}, isResponding=${_isResponding.value}")
        Log.d(TAG, "[LOG] STACK TRACE: Called from:")
        for (i in 3..minOf(8, stackTrace.size - 1)) { // Show 5 levels of stack trace
            Log.d(TAG, "[LOG]   ${stackTrace[i].className}.${stackTrace[i].methodName}:${stackTrace[i].lineNumber}")
        }
        
        if (!_micPermissionGranted.value) {
            Log.w(TAG, "[LOG] Cannot ensure continuous listening - no microphone permission, requesting permission")
            // Request permission instead of just returning
            onRequestMicrophonePermission?.invoke()
            return
        }
        
        // If continuous listening is already enabled, don't disable it
        if (voiceManager.isContinuousListeningEnabled.value) {
            Log.d(TAG, "[LOG] Continuous listening already enabled, ensuring it's active")
            // If not currently listening and not busy, start listening
            // Note: We don't check isResponding here - user should be able to speak while waiting for response
            if (!isListening.value && !isSpeaking.value) {
                startContinuousListening()
            }
            return
        }
        
        // Enable continuous listening if not already enabled
        Log.d(TAG, "[LOG] Enabling continuous listening for voice mode")
        
        Log.d(TAG, "[RACE_DEBUG] enableContinuousListening: About to clear input text. Stack trace: ${Thread.currentThread().stackTrace.take(5).joinToString { it.toString() }}")
        _inputText.value = ""
        Log.d(TAG, "[RACE_DEBUG] enableContinuousListening: Input text cleared to: '${_inputText.value}'")
        voiceManager.updateContinuousListeningEnabled(true)
        // continuousListeningEnabled is already set via voiceManager

        // Reset transition flag now that new ViewModel has successfully enabled listening
        if (isTransitioning) {
            Log.d(TAG, "Resetting isTransitioning flag after enabling continuous listening")
            isTransitioning = false
        }
        
        // Start listening if not busy (only check isSpeaking, not isResponding)
        if (!isSpeaking.value) {
            startContinuousListening()
        }
    }

    private fun startContinuousListening() {
        Log.d(TAG, "[LOG] startContinuousListening called. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, isResponding=${_isResponding.value}")

        // Chat-specific headphone-aware listening behavior
        // Prevents audio feedback when TTS is speaking without headphones
        if (ttsManager.isSpeaking.value) {
            val headphonesConnected = ttsManager.areHeadphonesConnected()
            if (!headphonesConnected) {
                Log.d(TAG, "[LOG] Skipping start while TTS is speaking without headphones (will restart when TTS completes)")
                return
            } else {
                Log.d(TAG, "[LOG] Allowing listening during TTS with headphones connected")
            }
        }

        // Delegate to VoiceManager which has proper safety checks (shouldBeListening, etc.)
        // and handles auto-restart logic. Transcriptions will be received via transcriptionFlow.
        voiceManager.startContinuousListening()
    }

    fun sendUserInput(text: String = _inputText.value) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) return
        
        // 🔧 CRITICAL LOGGING: Log what we're about to send
        Log.d(TAG, "📤 SEND_USER_INPUT: Starting send for content='$trimmedText'")
        
        // Always send messages - server will handle interrupts automatically based on its state

        viewModelScope.launch {
            try {
            // 🔧 CRITICAL FIX: Capture chat ID at the start and use it consistently
            // This prevents race conditions where _chatId.value changes during execution
            val originalChatId = _chatId.value
            var currentChatId = originalChatId
            
            // 🔧 NEW: Generate request ID early for optimistic UI pairing
            val requestId = if (configUseRemoteAgent) java.util.UUID.randomUUID().toString() else null
            
            // Capture timestamp for message consistency between local and server
            val messageTimestamp = System.currentTimeMillis()

            // 🔧 FIXED: Use existing chat ID for all messages in a conversation
            // Only create a new chat if we don't have any chat yet (first message)
            // Note: Negative chat IDs are valid optimistic chats, not "no chat"
            if (currentChatId == -1L) {
                // Create optimistic chat only for the first message
                // Let the WebSocket server create it and then sync
                val tempTitle = repository.deriveChatTitle(trimmedText)
                val tempChatId = repository.createChatOptimistic(tempTitle)
                // Don't update _chatId yet to avoid race condition
                currentChatId = tempChatId
                
                // Add the message first with the captured timestamp
                val localMessageId = repository.addUserMessageOptimistic(tempChatId, trimmedText, requestId, messageTimestamp)
                _scrollToBottomEvent.tryEmit(Unit) // Scroll to show new user message

                // Now update the chat ID - the message is already in the database
                _chatId.value = tempChatId
                _chatTitle.value = tempTitle

                // Connect to WebSocket with the new optimistic chat ID
                if(!whizServerRepository.isConnected() && !whizServerRepository.persistentDisconnectForTest()) {
                    // Pass the optimistic chat ID we just created - the server will handle it as a client_conversation_id
                    whizServerRepository.connect(tempChatId)
                } else if (whizServerRepository.isConnected()) {
                    // Already connected but possibly to a different chat - reconnect with new chat ID
                    whizServerRepository.connect(tempChatId)
                }
            } else {
                // Existing chat - check for migration first
                val originalChatId = _chatId.value
                val actualChatId = repository.getActualChatId(originalChatId)
                
                // If the chat was migrated, update our local reference
                if (actualChatId != originalChatId) {
                    Log.d(TAG, "Chat was migrated: using chat ID $actualChatId instead of $originalChatId for new message")
                    _chatId.value = actualChatId
                }
                
                // Always use optimistic UI since configUseRemoteAgent is always true
                val localMessageId = repository.addUserMessageOptimistic(actualChatId, trimmedText, requestId, messageTimestamp)
                _scrollToBottomEvent.tryEmit(Unit) // Scroll to show new user message

                // Ensure WebSocket is connected to the correct conversation
                // This handles the case where we're switching between existing chats
                if (!whizServerRepository.isConnected() && !whizServerRepository.persistentDisconnectForTest()) {
                    whizServerRepository.connect(actualChatId)
                    // After connecting, check for unsent messages
                    viewModelScope.launch {
                        // Wait for the WebSocket to actually connect
                        whizServerRepository.webSocketEvents
                            .first { it is WebSocketEvent.Connected }
                        checkAndRetryOrphanedMessages(actualChatId)
                    }
                } else if (whizServerRepository.isConnected()) {
                    // Already connected but possibly to a different chat - reconnect with correct chat ID
                    whizServerRepository.connect(actualChatId)
                    // After reconnecting, check for unsent messages
                    viewModelScope.launch {
                        // Wait for the WebSocket to actually connect
                        whizServerRepository.webSocketEvents
                            .first { it is WebSocketEvent.Connected }
                        checkAndRetryOrphanedMessages(actualChatId)
                    }
                }
            }
            
            // Clear input text after optimistic message is added (consistent UX for all input types)
            // Wait for the message to actually appear in the messages flow rather than using a fixed delay
            try {
                Log.d(TAG, "🧵 THREAD DEBUG: About to wait for message in flow on thread: ${Thread.currentThread().name}")
                val flowWaitStartTime = System.currentTimeMillis()
                withTimeout(1000) {
                    // Wait for the messages to be updated with our new message
                    messages.first { messageList ->
                        messageList.any { message -> 
                            message.content.trim() == trimmedText.trim() && 
                            message.type == com.example.whiz.data.local.MessageType.USER
                        }
                    }
                    val flowWaitDuration = System.currentTimeMillis() - flowWaitStartTime
                    Log.d(TAG, "[LOG] sendUserInput: Message appeared in flow after ${flowWaitDuration}ms, clearing input field")
                    Log.d(TAG, "🧵 THREAD DEBUG: Message appeared on thread: ${Thread.currentThread().name}")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "[LOG] sendUserInput: Timeout waiting for message to appear, clearing input anyway")
            }
            
            // Only clear the input if it still contains the message we just sent
            // This prevents clearing text that the user is currently typing
            if (_inputText.value.trim() == trimmedText) {
                Log.d(TAG, "[LOG] sendUserInput: Clearing input field after optimistic message added: '${_inputText.value}'")
                Log.d(TAG, "[RACE_DEBUG] sendUserInput: Current input matches sent message ('${_inputText.value}' == '$trimmedText'), clearing...")
                _inputText.value = ""
                Log.d(TAG, "[RACE_DEBUG] sendUserInput: Input text cleared to: '${_inputText.value}'")
                _isInputFromVoice.value = false
            } else {
                Log.d(TAG, "[RACE_DEBUG] sendUserInput: Input text has changed ('${_inputText.value}' != '$trimmedText'), NOT clearing to avoid race condition")
            }

            // Note: We don't update bubble transcription here to avoid circular loop
            // The bubble flow is for incoming transcriptions only, not for messages being sent

            // Send to agent (local or remote)
            // Note: Don't return early if not connected - let sendMessage handle retry queueing

            if (configUseRemoteAgent) {
                // 🔧 CRITICAL FIX: Check if we have a server token before attempting to send
                val serverToken = authRepository.serverToken.firstOrNull()
                
                if (serverToken == null) {
                    Log.w(TAG, "No server token available - navigating to login")
                    _navigateToLogin.value = true
                    authRepository.signOut()
                    return@launch
                }
                
                // 🔧 CONCURRENT REQUESTS: Don't block UI with _isResponding = true
                // Instead, track individual requests and allow multiple concurrent messages
                // Note: requestId is already generated above for optimistic UI pairing
                
                // 🔧 CRITICAL FIX: Use the updated chat ID after optimistic UI creation
                // If optimistic UI created a new chat, _chatId.value will be the new local chat ID (could be negative for optimistic chats)
                val chatIdForWebSocket = if (_chatId.value != -1L) _chatId.value else currentChatId
                
                // 🔧 FIX: Ensure requestId is non-null for WebSocket operations
                val nonNullRequestId = requestId ?: java.util.UUID.randomUUID().toString()
                pendingRequests[nonNullRequestId] = chatIdForWebSocket
                
                // 🔧 CRITICAL: Update responding state immediately after adding to pending requests
                updateRespondingStateForCurrentChat()
                
                // Pass the chatId directly - WhizServerRepository will handle whether to send as
                // conversation_id (positive) or client_conversation_id (negative)
                // 🔧 CRITICAL LOGGING: Log what we're sending to the server
                Log.d(TAG, "📤 CALLING whizServerRepository.sendMessage: requestId=$nonNullRequestId, content='$trimmedText', chatId=$chatIdForWebSocket, timestamp=$messageTimestamp")
                val success = whizServerRepository.sendMessage(trimmedText, nonNullRequestId, chatIdForWebSocket, timestamp = messageTimestamp)
                
                if (!success) {
                    // Message was queued for retry, don't clear the request tracking yet
                    // The retry mechanism will handle this transparently
                    Log.d(TAG, "Message queued for retry")
                }
                
                // For new chats, refresh conversations but don't switch - we already created a local chat for optimistic UI
                if (currentChatId == -1L) {
                    try {
                        viewModelScope.launch {
                            repository.refreshConversations()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refreshing conversations for new chat", e)
                    }
                }
                
                // 🔧 REMOVED: Manual refresh is unnecessary - messages flow updates automatically
                // The database updates from optimistic UI and server responses will trigger the flow naturally
                
                // Response handling is done in observeServerMessages
            } else {
                // --- Local Fallback ---
                if (currentChatId > 0) {
                    generateAssistantResponse(currentChatId)
                }
            }

            // Schedule persistence check (only for local agent)
            if (!configUseRemoteAgent && currentChatId > 0) {
                schedulePersistenceCheck(currentChatId)
            }

            // Start queued TTS if user was speaking when assistant message arrived
            startQueuedTTS()
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendUserInput", e)
            }
        }
    }

    fun dismissOverlayPermissionDialog() {
        _showOverlayPermissionDialog.value = false
    }
    
    fun requestOverlayPermission() {
        // This will be called from the UI to open the system settings
        // The actual intent launching will be handled in the UI layer
        _showOverlayPermissionDialog.value = false
    }
    
    fun toggleVoiceResponse() {
        val newValue = !isVoiceResponseEnabled.value
        voiceManager.setVoiceResponseEnabled(newValue)
        if (!newValue) {
            ttsManager.stop() // Stop speaking if toggled off

            // If continuous listening is enabled, restart it immediately when TTS is stopped
            if (voiceManager.isContinuousListeningEnabled.value) {
                Log.d(TAG, "[LOG] Voice response disabled, restarting continuous listening immediately")
                viewModelScope.launch {
                    delay(50L) // Very short delay to ensure TTS stop is processed
                    if (!isSpeaking.value && voiceManager.isContinuousListeningEnabled.value) {
                        startContinuousListening()
                    }
                }
            }
        }
        Log.d(TAG, "Voice Response Enabled: ${isVoiceResponseEnabled.value}")
    }

    fun setVoiceResponseEnabled(enabled: Boolean) {
        voiceManager.setVoiceResponseEnabled(enabled)
        if (!enabled) {
            ttsManager.stop() // Stop speaking if disabled

            // If continuous listening is enabled, restart it immediately when TTS is stopped
            if (voiceManager.isContinuousListeningEnabled.value) {
                Log.d(TAG, "[LOG] Voice response disabled via setter, restarting continuous listening immediately")
                viewModelScope.launch {
                    delay(50L) // Very short delay to ensure TTS stop is processed
                    if (!isSpeaking.value && voiceManager.isContinuousListeningEnabled.value) {
                        startContinuousListening()
                    }
                }
            }
        }
        Log.d(TAG, "Voice Response Enabled set to: $enabled")
    }

    // --- Internal Helper Functions ---

    private fun processAndSendTranscription(transcription: String) {
        Log.d(TAG, "[LOG] processAndSendTranscription called with transcription: '$transcription'")
        // 🔧 Don't set _inputText.value as this causes the transcription to appear in the input box
        // and can cause previously sent messages to reappear. Instead, send the transcription directly.
        sendUserInput(transcription) // Pass the transcription directly instead of setting input text
    }

    private suspend fun generateAssistantResponse(chatId: Long) {
        if (chatId <= 0 || configUseRemoteAgent) return // Don't run if using remote or invalid ID

        _isResponding.value = true
                        delay(1000L)

        // ... (keep the rest of the local response generation logic)
        val responses = listOf(
            "Local: I understand.",
            "Local: That's interesting.",
            "Local: Let me think.",
            // ... add more distinct local responses if needed
        )
        val responseText = responses.random()

        repository.addAssistantMessage(chatId, responseText)
        updateRespondingStateForCurrentChat() // Update based on remaining requests

        // Speak the response if enabled
        if (isVoiceResponseEnabled.value && _isTTSInitialized.value) {
            speakAgentResponse(responseText)
        }
    }

    private fun speakAgentResponse(text: String) {
        if (!_isTTSInitialized.value || text.isBlank()) {
            Log.w(TAG, "Skipping speak request: TTS not ready or text is blank.")
            return
        }
        // Generate a unique ID for this utterance to track it
        val utteranceId = UUID.randomUUID().toString()
        Log.d(TAG, "Requesting TTS speak: [$utteranceId] '$text'")
        // QUEUE_FLUSH cancels previous utterances, QUEUE_ADD adds to the end
        ttsManager.speak(text, "chat_message")
    }

    private fun schedulePersistenceCheck(chatId: Long) {
        if (chatId <= 0) return
        persistenceJob?.cancel()
        persistenceJob = viewModelScope.launch {
            if (repository.shouldPersistChat(chatId)) {
                repository.updateChatLastMessageTime(chatId)
            }
        }
    }

    // --- ViewModel Lifecycle ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Releasing resources.")
        
        // 🔧 Enhanced cleanup logging
        if (pendingRequests.isNotEmpty()) {
            Log.w(TAG, "🔥 onCleared: CLEARING ${pendingRequests.size} pending requests: ${pendingRequests.keys}")
            Log.w(TAG, "🔥 onCleared: Pending requests map before clearing: $pendingRequests")
            pendingRequests.clear()
            Log.w(TAG, "🔥 onCleared: Pending requests map after clearing: $pendingRequests")
        }
        
        // VoiceManager is a singleton that manages its own cleanup
        ttsManager.stop()
        ttsManager.shutdown()
        persistenceJob?.cancel()
        
        // Clear active conversation in ConnectionStateManager
        // BUT DO NOT disconnect WebSocket - it should stay connected for retry logic
        if (configUseRemoteAgent) {
            Log.d(TAG, "onCleared: Clearing active conversation (NOT disconnecting WebSocket for retry continuity)")
            connectionStateManager.clearActiveConversation()
            // whizServerRepository.disconnect() // REMOVED - let retries continue
        }
        serverMessageCollectorJob?.cancel() // Stop collecting events
        
        // Reset states
        _isResponding.value = false
        _isConnectedToServer.value = false
        isReconnectingAfterDisconnect = false

        // Only disable continuous listening if we're NOT transitioning to a new chat
        // (e.g., via assistant long-press while app is open)
        if (isTransitioning) {
            Log.d(TAG, "onCleared: Skipping continuous listening disable - transitioning to new chat")
            // DON'T reset flag here - multiple ViewModels may be cleared during transition
            // Flag will be reset when new ViewModel enables continuous listening
        } else {
            voiceManager.updateContinuousListeningEnabled(false)
        }

        Log.d(TAG, "ChatViewModel cleared, TTS shutdown, SpeechRecognitionService destroyed, WebSocket disconnected, pending requests cleared.")
    }


    // Called when permission is granted from UI
    fun onMicrophonePermissionGranted() {
        _micPermissionGranted.value = true
        _errorState.value = null
        
        // When permission is granted, make sure SpeechRecognitionService is properly initialized
        try {
            Log.d(TAG, "Microphone permission granted")
            // VoiceManager is already initialized as a singleton
            
            // Auto-enable continuous listening when permission is granted
            if (_chatId.value != 0L && !voiceManager.isContinuousListeningEnabled.value) {
                Log.d(TAG, "[LOG] Auto-enabling continuous listening after permission granted")
                viewModelScope.launch {
                    delay(100L) // Small delay to ensure service is initialized
                    voiceManager.updateContinuousListeningEnabled(true)
                    // continuousListeningEnabled is already set via voiceManager
                    startContinuousListening()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech service after permission granted", e)
            _errorState.value = "Error initializing speech. Please try again."
        }
    }
    
    // Called when permission is denied
    fun onMicrophonePermissionDenied() {
        _micPermissionGranted.value = false
        _errorState.value = "Microphone permission is required for voice input"
        
        // If speech recognition was active, make sure to stop it
        if (isListening.value) {
            try {
                voiceManager.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition after permission denied", e)
            }
        }
    }

    // Called when app goes to background
    fun onAppBackgrounded() {
        Log.d(TAG, "[LOG] onAppBackgrounded called. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}")

        // IMPORTANT: Save TTS state FIRST before we disable it
        // BubbleOverlayService will use this to decide whether to enable TTS mode
        voiceManager.ttsStateBeforeBackground = isVoiceResponseEnabled.value
        Log.d(TAG, "[LOG] Saved TTS state before background: ${voiceManager.ttsStateBeforeBackground}")

        // Record when we're backgrounding to prevent TTS replay of old messages
        lastBackgroundedTime = System.currentTimeMillis()
        Log.d(TAG, "[LOG] Set lastBackgroundedTime to $lastBackgroundedTime")

        // Stop TTS audio if currently speaking
        if (ttsManager.isSpeaking.value) {
            Log.d(TAG, "[LOG] Stopping TTS audio as app is going to background")
            ttsManager.stop()
        }

        // Disable TTS when backgrounding for better UX (avoid jarring auto-speech on foreground)
        // Exception: If bubble overlay is active or pending, preserve TTS state so the bubble can speak
        if (!BubbleOverlayService.isActive && !BubbleOverlayService.isPendingStart) {
            Log.d(TAG, "[LOG] Disabling TTS to prevent auto-speech on foreground (bubble can restore if needed)")
            voiceManager.setVoiceResponseEnabled(false)
        } else {
            Log.d(TAG, "[LOG] Bubble overlay active/pending - preserving TTS state for bubble")
        }

        // VoiceManager handles stopping continuous listening on background
    }

    // Called when app comes back to foreground - restart continuous listening if it was enabled
    fun onAppForegrounded() {
        Log.d(TAG, "[LOG] onAppForegrounded called. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, micPermissionGranted=${_micPermissionGranted.value}, chatId=${_chatId.value}")

        // Only restart if we have permission, are in a chat, and continuous listening was enabled before backgrounding
        if (_micPermissionGranted.value && _chatId.value != 0L && voiceManager.isContinuousListeningEnabled.value) {
            try {
                viewModelScope.launch {
                    delay(200L) // Brief delay to ensure app is fully resumed
                    
                    // Don't try to "fix" TTS state - MainActivity.onPause already stops TTS properly
                    // Just restart continuous listening if needed
                    if (!isListening.value) {
                        Log.d(TAG, "[LOG] Restarting continuous listening after app foregrounded")
                        startContinuousListening()
                    } else {
                        Log.d(TAG, "[LOG] Already listening, no restart needed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting continuous listening after app foregrounded", e)
            }
        }
    }

    fun clearAuthErrorDialog() {
        _showAuthErrorDialog.value = null
    }

    fun onLoginNavigationComplete() { // Renamed for clarity
        _navigateToLogin.value = false
    }

    fun onAsanaSetupDialogDismissed() {
        _showAsanaSetupDialog.value = false
    }

    fun onAuthErrorDialogDismissed() {
        _showAuthErrorDialog.value = null
    }
    
    fun retryChatLoad() {
        _chatLoadError.value = null
        val currentChatId = _chatId.value
        if (currentChatId > 0) {
            loadChat(currentChatId)
        }
    }
    
    fun refreshMessages() {
        val currentChatId = _chatId.value
        if (currentChatId != 0L && currentChatId != -1L) {
            viewModelScope.launch {
                _isRefreshing.value = true
                try {
                    Log.d(TAG, "refreshMessages: Starting refresh for chat $currentChatId")
                    
                    // Use fetchMessagesWithRetry which includes deduplication and retry logic
                    val serverMessages = repository.fetchMessagesWithRetry(currentChatId)
                    Log.d(TAG, "refreshMessages: Retrieved ${serverMessages.size} messages from server")
                    
                    // The fetchMessagesWithRetry already handles storing messages with deduplication
                    // Just trigger UI refresh
                    repository.refreshMessages()
                    
                    _errorState.value = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error refreshing messages for chat $currentChatId", e)
                    _errorState.value = "Failed to refresh messages: ${e.message}"
                } finally {
                    _isRefreshing.value = false
                }
            }
        } else {
            Log.d(TAG, "refreshMessages: Skipping refresh - invalid chat ID: $currentChatId")
            _isRefreshing.value = false
        }
    }

    private fun applyVoiceSettings(voiceSettings: com.example.whiz.data.preferences.VoiceSettings) {
        try {
            val previousSettings = currentVoiceSettings
            currentVoiceSettings = voiceSettings
            
            if (!voiceSettings.useSystemDefaults) {
                Log.d(TAG, "Applying custom voice settings: speechRate=${voiceSettings.speechRate}, pitch=${voiceSettings.pitch}")
                ttsManager.setSpeechRate(voiceSettings.speechRate)
                ttsManager.setPitch(voiceSettings.pitch)
            } else {
                // Check if we're switching from custom to system defaults
                if (previousSettings != null && !previousSettings.useSystemDefaults) {
                    Log.d(TAG, "Switching from custom to system TTS settings - reinitializing TTS engine")
                    // We need to reinitialize the TTS engine to clear custom settings
                    viewModelScope.launch {
                        try {
                            ttsManager.stop()
                            ttsManager.shutdown()
                            ttsManager.initialize { success ->
                                if (success) {
                                    Log.d(TAG, "TTS reinitialized successfully")
                                    _isTTSInitialized.value = true
                                } else {
                                    Log.e(TAG, "Failed to reinitialize TTS")
                                    _isTTSInitialized.value = false
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reinitializing TTS", e)
                            _isTTSInitialized.value = false
                        }
                    }
                } else {
                    Log.d(TAG, "Using system default TTS settings - not overriding speech rate or pitch")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying voice settings", e)
        }
    }




    /**
     * Send the current input text (server will handle interruption if needed)
     */
    fun interruptResponse() {
        val currentInput = _inputText.value
        if (currentInput.isBlank()) {
            Log.w(TAG, "interruptResponse: No input text to send")
            return
        }
        
        Log.d(TAG, "interruptResponse: Sending message: '$currentInput'")
        sendUserInput(currentInput)
    }

    /**
     * Start queued TTS message after user finishes speaking.
     * Called when user's message is sent via sendUserInput().
     */
    private fun startQueuedTTS() {
        pendingTTSMessage?.let { message ->
            Log.d(TAG, "Starting queued TTS after user finished speaking: '$message'")
            viewModelScope.launch(Dispatchers.Main) {
                ttsManager.speak(message, "chat_message")
            }
            pendingTTSMessage = null  // Clear the queue
        }
    }

    private fun initializeTTS() {
        // Use event-driven approach without delays
        ttsManager.initialize { success ->
            if (success) {
                Log.d(TAG, "TTS initialized successfully")
                _isTTSInitialized.value = true
                
                // Apply current voice settings if available
                currentVoiceSettings?.let { settings ->
                    applyVoiceSettings(settings)
                }
                
                // Set up event callbacks for TTS coordination
                ttsManager.setAudioEventCallbacks(
                    onStarted = {
                        Log.d(TAG, "TTS started - audio focus acquired")

                        // Stop listening to prevent mic from picking up TTS audio (unless headphones connected)
                        // Must run on main thread for speech recognizer
                        if (!ttsManager.areHeadphonesConnected()) {
                            // Check if continuous listening is ENABLED (the user's intent)
                            // not if we're currently listening (which can be transiently false during restarts)
                            if (voiceManager.isContinuousListeningEnabled.value) {
                                Log.d(TAG, "Stopping listening to prevent TTS echo (no headphones)")
                                wasListeningBeforeTTS = true
                            }
                            viewModelScope.launch(Dispatchers.Main) {
                                voiceManager.stopListening()
                            }
                        }
                        // TTSManager handles its own isSpeaking state
                    },
                    onCompleted = {
                        Log.d(TAG, "TTS completed - audio focus released")
                        // TTSManager handles its own isSpeaking state
                        // Restart listening if we stopped it when TTS started
                        // Must run on main thread for speech recognizer
                        viewModelScope.launch(Dispatchers.Main) {
                            if (wasListeningBeforeTTS && voiceManager.isContinuousListeningEnabled.value) {
                                Log.d(TAG, "TTS completed - restarting listening that was paused for TTS")
                                wasListeningBeforeTTS = false
                                startContinuousListening()
                            } else if (voiceManager.isContinuousListeningEnabled.value && ttsManager.areHeadphonesConnected()) {
                                // Headphones case: always restart
                                Log.d(TAG, "TTS completed with continuous listening and headphones - auto-resuming listening")
                                startContinuousListening()
                            }
                        }
                    },
                    onError = {
                        Log.e(TAG, "TTS error - audio focus released")
                        // TTSManager handles its own isSpeaking state
                    }
                )
            } else {
                Log.e(TAG, "Failed to initialize TTS")
                _isTTSInitialized.value = false
            }
        }
    }

    private fun speakText(text: String) {
        if (!_isTTSInitialized.value) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            return
        }
        
        Log.d(TAG, "[LOG] Speaking text: $text, continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}")
        ttsManager.speak(text, "chat_message")
    }
    
    /**
     * Check for orphaned user messages in a chat that need to be retried.
     * An orphaned message is a user message that was sent more than 2 minutes ago
     * but never received an assistant response.
     * For optimistic chats (negative IDs), retry all user messages immediately.
     */
    private suspend fun checkAndRetryOrphanedMessages(chatId: Long) {
        try {
            Log.d(TAG, "Checking for orphaned messages in chat $chatId")
            
            // Get all messages for this chat
            val allMessages = messages.value
            if (allMessages.isEmpty()) {
                Log.d(TAG, "No messages in chat $chatId, skipping orphaned message check")
                return
            }
            
            // Find the timestamp of the last assistant message (if any)
            val lastAssistantMessageTime = allMessages
                .filter { it.type == MessageType.ASSISTANT }
                .maxByOrNull { it.timestamp }
                ?.timestamp ?: 0L
            
            // Current time and threshold
            val currentTime = System.currentTimeMillis()
            val retryThresholdMs = if (chatId < 0) {
                // For optimistic chats, retry messages immediately (1 second threshold)
                1 * 1000L
            } else {
                // For server-backed chats, use 2-minute threshold
                2 * 60 * 1000L
            }
            
            // Find orphaned user messages that need retry
            val orphanedMessages = allMessages
                .filter { message ->
                    message.type == MessageType.USER &&
                    message.timestamp > lastAssistantMessageTime && // After last assistant response
                    (currentTime - message.timestamp) > retryThresholdMs && // Older than threshold
                    message.requestId != null // Has a request ID for retry
                }
                .sortedBy { it.timestamp } // Process in chronological order
            
            if (orphanedMessages.isEmpty()) {
                Log.d(TAG, "No orphaned messages found in chat $chatId")
                return
            }
            
            Log.d(TAG, "Found ${orphanedMessages.size} orphaned messages to retry in chat $chatId")
            
            // Check each orphaned message
            for (message in orphanedMessages) {
                val requestId = message.requestId ?: continue
                
                // Check if it's already in the retry queue
                if (whizServerRepository.hasMessageInRetryQueue(requestId)) {
                    Log.d(TAG, "Message ${message.id} (requestId: $requestId) is already in retry queue, skipping")
                    continue
                }
                
                // Check if it's in pending requests (currently being processed)
                if (pendingRequests.containsKey(requestId)) {
                    Log.d(TAG, "Message ${message.id} (requestId: $requestId) is in pending requests, skipping")
                    continue
                }
                
                // This message needs to be retried
                Log.d(TAG, "Retrying orphaned message ${message.id} (requestId: $requestId): ${message.content.take(50)}...")
                
                // Add to pending requests to track it
                pendingRequests[requestId] = chatId
                updateRespondingStateForCurrentChat()
                
                // Send the message again
                // Pass the chatId directly - WhizServerRepository will handle whether to send as
                // conversation_id (positive) or client_conversation_id (negative)
                val success = whizServerRepository.sendMessage(
                    message.content, 
                    requestId, 
                    chatId,
                    timestamp = message.timestamp
                )
                
                if (!success) {
                    Log.d(TAG, "Message ${message.id} queued for retry (requestId: $requestId)")
                } else {
                    Log.d(TAG, "Message ${message.id} sent successfully (requestId: $requestId)")
                }
                
                // Small delay between retries to avoid overwhelming the server
                delay(100)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for orphaned messages in chat $chatId", e)
        }
    }
}