package com.example.whiz.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.whiz.data.repository.WhizRepository
// SpeechRecognitionService is now accessed via VoiceManager
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



@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // Added OptIn annotation
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // Inject Context
    private val repository: WhizRepository,
    private val whizServerRepository: WhizServerRepository,
    private val authRepository: AuthRepository, // Add this
    private val userPreferences: UserPreferences,
    savedStateHandle: SavedStateHandle,
    private val ttsManager: TTSManager,
    private val appLifecycleService: com.example.whiz.services.AppLifecycleService,
    private val voiceManager: VoiceManager,
) : ViewModel() { // Removed TextToSpeech.OnInitListener

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
                
                // 🔧 DEDUPLICATION FIX: Remove duplicate messages based on request ID for user messages
                val deduplicatedMessages = messagesList.distinctBy { message ->
                    // Use request ID for user messages (when available) to handle optimistic UI transitions
                    val key = when {
                        message.type == MessageType.USER && message.requestId != null -> {
                            // For user messages with request ID: use requestId + type as unique key
                            Pair(message.requestId, message.type)
                        }
                        else -> {
                            // For assistant messages or user messages without requestId: use content + type
                            Pair(message.content.trim(), message.type)
                        }
                    }
                    Log.d(TAG, "🔍 DEDUP_DEBUG: Creating key for message ID:${message.id} RequestID:${message.requestId} -> $key")
                    key
                }
                
                if (deduplicatedMessages.size != messagesList.size) {
                    Log.w(TAG, "🔍 DEDUP_DEBUG: Removed ${messagesList.size - deduplicatedMessages.size} duplicate messages")
                    
                    // Log which messages were removed
                    val removedMessages = messagesList - deduplicatedMessages.toSet()
                    removedMessages.forEach { removed ->
                        Log.w(TAG, "🔍 DEDUP_DEBUG: REMOVED -> ID:${removed.id} Type:${removed.type} RequestID:${removed.requestId} Timestamp:${removed.timestamp} Content:'${removed.content.take(30)}...'")
                    }
                    
                    // Log final deduplicated list
                    Log.d(TAG, "🔍 DEDUP_DEBUG: Final deduplicated list (${deduplicatedMessages.size} messages):")
                    deduplicatedMessages.forEachIndexed { index, message ->
                        Log.d(TAG, "🔍 DEDUP_DEBUG:   FINAL[$index] ID:${message.id} Type:${message.type} RequestID:${message.requestId} Timestamp:${message.timestamp} Content:'${message.content.take(30)}...'")
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
    private val _isVoiceResponseEnabled = MutableStateFlow(false) // Default to off
    val isVoiceResponseEnabled = _isVoiceResponseEnabled.asStateFlow()

    // Chat persistence jobs
    private var persistenceJob: Job? = null

    // --- WebSocket State ---
    private val _isConnectedToServer = MutableStateFlow(false)
    val isConnectedToServer = _isConnectedToServer.asStateFlow()
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

    // State to trigger navigation to Login screen
    private val _navigateToLogin = MutableStateFlow(false)
    val navigateToLogin: StateFlow<Boolean> = _navigateToLogin.asStateFlow()

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
                onAppBackgrounded()
            }
        }

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
                        if (currentChatId != -1L) {
                            Log.d(TAG, "WebSocketEvent.Connected: Syncing messages for chat $currentChatId (reconnect=$isReconnectingAfterDisconnect)")
                            viewModelScope.launch {
                                try {
                                    // Fetch any messages we might have missed
                                    // Server now handles optimistic chat IDs via optimistic_chat_id column
                                    val serverMessages = repository.fetchMessagesWithDeduplication(currentChatId)
                                    Log.d(TAG, "WebSocketEvent.Connected: Retrieved ${serverMessages.size} messages from server for chat $currentChatId")
                                    
                                    // The fetchMessagesWithDeduplication already handles storing messages
                                    // Just trigger UI refresh
                                    repository.refreshMessages()
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
                        else if (errorMessage.contains("after") && errorMessage.contains("attempts")) {
                            // This is a final failure after all retries - show to user
                            _connectionError.value = "Failed to send message. Please check your connection and try again."
                            if (_chatId.value > 0) { // Only add if a chat is active
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
                            
                            // Clear responding state for temporary connection errors too
                            // When message retries have failed completely (~6-8 seconds of trying),
                            // clear the responding state to allow user to interact with the microphone button
                            if (_isResponding.value) {
                                Log.d(TAG, "Clearing responding state due to connection error to unblock UI")
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
                        // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                        updateRespondingStateForCurrentChat()
                    }
                    is WebSocketEvent.Interrupted -> {
                        Log.d(TAG, "Previous request was interrupted: ${event.message}")
                        
                        // 🔧 INTERRUPTION HANDLING: All user messages remain in chat, no assistant responses
                        // When multiple requests are sent rapidly, the server cancels previous ones
                        // All user messages stay in chat but only the latest gets a response
                        val interruptedRequests = pendingRequests.keys.toList()
                        Log.d(TAG, "🔧 INTERRUPTION: ${interruptedRequests.size} user messages remain in chat (no assistant responses)")
                        interruptedRequests.forEach { requestId ->
                            Log.d(TAG, "🔧 INTERRUPTION: User message for request $requestId remains in chat (no assistant response)")
                        }
                        
                        // The backend has cancelled previous requests automatically
                        // Clear all pending requests since they were cancelled
                        Log.d(TAG, "🔥 INTERRUPTION: CLEARING all pending requests: $pendingRequests")
                        pendingRequests.clear()
                        Log.d(TAG, "🔥 INTERRUPTION: Pending requests map after clearing: $pendingRequests")
                        // 🔧 CONCURRENT MODE: Removed currentActiveRequestId tracking
                        updateRespondingStateForCurrentChat()
                        Log.d(TAG, "Cleared ${interruptedRequests.size} pending requests due to interrupt")
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
                            var speakThisMessage = _isVoiceResponseEnabled.value
                            var effectiveConversationId: Long? = null // Declare outside try block

                            // 🔧 FIXED: Use conversation_id from WebSocketEvent (already parsed by WhizServerRepo)
                            // Don't try to re-parse the response text as JSON since it's just the response content
                            effectiveConversationId = event.conversationId
                            
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
                                            // 🔧 REGISTER MIGRATION: Track optimistic→real chat ID conversion for deduplication
                                            val oldChatId = _chatId.value
                                            if (oldChatId < 0 && effectiveConversationId > 0) {
                                                repository.registerChatMigration(oldChatId, effectiveConversationId)
                                                Log.d(TAG, "🔄 Registered chat migration: $oldChatId → $effectiveConversationId")
                                            }
                                            
                                            Log.d(TAG, "🔧 CHAT_ID_UPDATE: Updating _chatId from ${_chatId.value} to $effectiveConversationId")
                                            _chatId.value = effectiveConversationId
                                            
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
                                
                                // 🔧 REGISTER MIGRATION: Check if this is actually an optimistic→real conversion
                                if (originalChatId < 0 && effectiveConversationId > 0) {
                                    repository.registerChatMigration(originalChatId, effectiveConversationId)
                                    Log.d(TAG, "🔄 Registered chat migration (scenario 2): $originalChatId → $effectiveConversationId")
                                }
                                
                                // No input text preservation needed - this is just ID sync
                                _chatId.value = effectiveConversationId
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
                                        // 🔧 RECONNECTION FIX: Use client_conversation_id if available when pendingRequests is lost
                                        if (event.clientConversationId != null) {
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
                                            Log.d(TAG, "🐛 VOICE_DEBUG: RequestId not in pendingRequests and no clientConversationId - discarding message to prevent cross-chat contamination")
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
                                        repository.registerChatMigration(event.clientConversationId, effectiveConversationId)
                                        
                                        // If the current chat is the optimistic one, update it to the server ID
                                        if (_chatId.value == event.clientConversationId) {
                                            Log.d(TAG, "📝 Updating current chat ID from ${_chatId.value} to $effectiveConversationId")
                                            _chatId.value = effectiveConversationId
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
                                val shouldSpeak = _isVoiceResponseEnabled.value && 
                                                _isTTSInitialized.value && 
                                                speakThisMessage && 
                                                messageContentForChat.isNotBlank() &&
                                                isResponseForCurrentChat && 
                                                targetChatId != 0L && // Allow speaking for both positive (server) and negative (optimistic) chat IDs
                                                targetChatId == _chatId.value && // Double-check current chat
                                                isMessageFresh // Only speak fresh messages
                                
                                
                                if (shouldSpeak) {
                                    if (isListening.value) {
                                        wasListeningBeforeTTS = true
                                        voiceManager.stopListening() // Stop STT before TTS speaks
                                    }
                                    val utteranceId = UUID.randomUUID().toString()
                                    ttsManager.speak(messageContentForChat, "chat_message")
                                    // TTSManager will handle the isSpeaking state
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
        if (_isVoiceResponseEnabled.value && _isTTSInitialized.value) {
            speakAgentResponse(responseText)
        }
    }

    // --- Public Functions ---

    fun loadChat(chatId: Long) {
        loadChatWithVoiceMode(chatId, false)
    }
    
    fun loadChatWithVoiceMode(chatId: Long, isVoiceModeActivation: Boolean = false) {
        Log.d(TAG, "🔥 loadChatWithVoiceMode STARTED for chatId: $chatId, voiceMode: $isVoiceModeActivation")
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
                
                // 🔧 Clear pending requests and log what we're clearing
                try {
                    if (pendingRequests.isNotEmpty()) {
                        Log.w(TAG, "🔥 loadChat: CLEARING ${pendingRequests.size} pending requests: ${pendingRequests.keys}")
                        Log.w(TAG, "🔥 loadChat: Pending requests map before clearing: $pendingRequests")
                        pendingRequests.clear()
                        Log.w(TAG, "🔥 loadChat: Pending requests map after clearing: $pendingRequests")
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
                    if (_isVoiceResponseEnabled.value) {
                        Log.d(TAG, "[LOG] loadChat: Resetting voice responses to OFF (was ON)")
                        _isVoiceResponseEnabled.value = false
                    } else {
                        Log.d(TAG, "[LOG] loadChat: Voice responses already OFF")
                    }
                } else {
                    Log.d(TAG, "[LOG] loadChat: Voice mode activation - preserving voice response state (current: ${_isVoiceResponseEnabled.value})")
                }
                
                try {
                    Log.d(TAG, "loadChat: Stopping speech recognition service (isListening: ${isListening.value})")
                    voiceManager.stopListening()
                    Log.d(TAG, "loadChat: Stopping TTS (isSpeaking: ${isSpeaking.value})")
                    ttsManager.stop()
                    Log.d(TAG, "loadChat: Speech services stopped successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping speech services during loadChat", e)
                }

                // Refresh messages to ensure we have latest data
                if (_chatId.value > 0) {
                    try {
                        Log.d(TAG, "loadChat: Performing sync for existing chat ${_chatId.value}")
                        // Use existing deduplicated sync method to get messages from server
                        val serverMessages = repository.fetchMessagesWithDeduplication(_chatId.value)
                        Log.d(TAG, "loadChat: Retrieved ${serverMessages.size} messages from server for chat ${_chatId.value}")
                        
                        // The fetchMessagesWithDeduplication method already handles storing messages and deduplication
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
                if (configUseRemoteAgent && _chatId.value > 0) {
                    checkAndRetryOrphanedMessages(_chatId.value)
                }

                // 🎙️ VOICE APP BEHAVIOR: Update permission state and let UI layer control all microphone behavior
                // Since this is a voice app, the UI always enables continuous listening by default
                val actualPermissionState = PermissionHandler.hasMicrophonePermission(context)
                if (_micPermissionGranted.value != actualPermissionState) {
                    Log.d(TAG, "[LOG] Updating permission state from ${_micPermissionGranted.value} to $actualPermissionState")
                    _micPermissionGranted.value = actualPermissionState
                }
                
                Log.d(TAG, "[LOG] Chat load complete - UI layer will handle voice app default behavior (always enable microphone)")
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
            Log.w(TAG, "[LOG] Microphone permission not granted")
            _errorState.value = "Microphone permission required" 
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
            Log.w(TAG, "[LOG] Cannot ensure continuous listening - no microphone permission")
            return
        }
        
        // If continuous listening is already enabled, don't disable it
        if (voiceManager.isContinuousListeningEnabled.value) {
            Log.d(TAG, "[LOG] Continuous listening already enabled, ensuring it's active")
            // If not currently listening and not busy, start listening
            if (!isListening.value && !isSpeaking.value && !_isResponding.value) {
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
        
        // Start listening if not busy
        if (!isSpeaking.value && !_isResponding.value) {
            startContinuousListening()
        }
    }

    private fun startContinuousListening() {
        Log.d(TAG, "[LOG] startContinuousListening called. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, isResponding=${_isResponding.value}")
        
        // Headphone-aware listening behavior
        if (ttsManager.isSpeaking.value) {
            val headphonesConnected = ttsManager.areHeadphonesConnected()
            if (!headphonesConnected) {
                // Without headphones: Don't listen during TTS to prevent feedback
                Log.d(TAG, "[LOG] startContinuousListening: Skipping start while TTS is speaking without headphones (will restart when TTS completes)")
                return
            } else {
                // With headphones: Continue listening even during TTS
                Log.d(TAG, "[LOG] startContinuousListening: Allowing listening during TTS with headphones connected")
            }
        }
        
        voiceManager.startListening { finalText ->
            Log.d(TAG, "[LOG] startContinuousListening: got transcription. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, text='$finalText', isResponding=${_isResponding.value}")
            
            // Always set transcribed text to input field when we have valid text
            // This ensures text is preserved even when user manually stops the microphone
            if (finalText.isNotBlank()) {
                Log.d(TAG, "[LOG] startContinuousListening: Setting transcribed text to input field: '$finalText'")
            updateInputText(finalText, fromVoice = true)
                
                // Only auto-send if continuous listening is still enabled
                if (voiceManager.isContinuousListeningEnabled.value) {
                    Log.d(TAG, "[LOG] startContinuousListening: Auto-sending transcription (continuous listening enabled)")
            sendUserInput(finalText) // Send the transcription
                } else {
                    Log.d(TAG, "[LOG] startContinuousListening: Preserving transcription in input field (continuous listening disabled, user can manually send)")
                }
            }
            
            // Always restart listening if continuous listening is enabled, regardless of responding state
            if (voiceManager.isContinuousListeningEnabled.value) {
                Log.d(TAG, "[LOG] Continuous listening: restarting after result (isResponding=${_isResponding.value})")
                viewModelScope.launch {
                    // Small delay to ensure the previous listening session is fully stopped
                    delay(100L)
                    if (voiceManager.isContinuousListeningEnabled.value && !isSpeaking.value) {
                        startContinuousListening() // This will check isSpeaking again
                    }
                }
            } else {
                Log.d(TAG, "[LOG] Continuous listening disabled, not restarting")
            }
        }
    }

    fun sendUserInput(text: String = _inputText.value) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) return
        
        // Always send messages - server will handle interrupts automatically based on its state

        viewModelScope.launch {
            try {
            // 🔧 CRITICAL FIX: Capture chat ID at the start and use it consistently
            // This prevents race conditions where _chatId.value changes during execution
            val originalChatId = _chatId.value
            var currentChatId = originalChatId
            
            // 🔧 NEW: Generate request ID early for optimistic UI pairing
            val requestId = if (configUseRemoteAgent) java.util.UUID.randomUUID().toString() else null

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
                
                // Add the message first
                val localMessageId = repository.addUserMessageOptimistic(tempChatId, trimmedText, requestId)
                
                // Now update the chat ID - the message is already in the database
                _chatId.value = tempChatId
                _chatTitle.value = tempTitle

                // Connect to WebSocket if not connected
                if(!whizServerRepository.isConnected() && !whizServerRepository.persistentDisconnectForTest()) {
                    // Pass the optimistic chat ID we just created - the server will handle it as a client_conversation_id
                    whizServerRepository.connect(currentChatId)
                }
            } else {
                // Existing chat - add message normally
                val actualChatId = _chatId.value
                // Always use optimistic UI since configUseRemoteAgent is always true
                val localMessageId = repository.addUserMessageOptimistic(actualChatId, trimmedText, requestId)
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
                
                val success = whizServerRepository.sendMessage(trimmedText, nonNullRequestId, chatIdForWebSocket)
                
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
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendUserInput", e)
            }
        }
    }

    fun toggleVoiceResponse() {
        _isVoiceResponseEnabled.update { !it }
        if (!_isVoiceResponseEnabled.value) {
            ttsManager.stop() // Stop speaking if toggled off
            
            // If continuous listening is enabled, restart it immediately when TTS is stopped
            if (voiceManager.isContinuousListeningEnabled.value && !_isResponding.value) {
                Log.d(TAG, "[LOG] Voice response disabled, restarting continuous listening immediately")
                viewModelScope.launch {
                    delay(50L) // Very short delay to ensure TTS stop is processed
                    if (!_isResponding.value && !isSpeaking.value && voiceManager.isContinuousListeningEnabled.value) {
                        startContinuousListening()
                    }
                }
            }
        }
        Log.d(TAG, "Voice Response Enabled: ${_isVoiceResponseEnabled.value}")
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
        if (_isVoiceResponseEnabled.value && _isTTSInitialized.value) {
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
        
        // Disconnect WebSocket
        if (configUseRemoteAgent) {
            Log.d(TAG, "onCleared: Disconnecting WebSocket")
            whizServerRepository.disconnect()
        }
        serverMessageCollectorJob?.cancel() // Stop collecting events
        
        // Reset states
        _isResponding.value = false
        _isConnectedToServer.value = false
        isReconnectingAfterDisconnect = false
        voiceManager.updateContinuousListeningEnabled(false)
        
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
        
        // Record when we're backgrounding to prevent TTS replay of old messages
        lastBackgroundedTime = System.currentTimeMillis()
        Log.d(TAG, "[LOG] Set lastBackgroundedTime to $lastBackgroundedTime")
        
        // Stop TTS when app goes to background
        if (ttsManager.isSpeaking.value) {
            Log.d(TAG, "[LOG] Stopping TTS as app is going to background")
            ttsManager.stop()
        }
        
        // VoiceManager now handles stopping continuous listening on background
    }

    // Called when app comes back to foreground - restart continuous listening if it was enabled
    fun onAppForegrounded() {
        Log.d(TAG, "[LOG] onAppForegrounded called. continuousListeningEnabled=${voiceManager.isContinuousListeningEnabled.value}, micPermissionGranted=${_micPermissionGranted.value}, chatId=${_chatId.value}")
        Log.d(TAG, "[LOG] Current states - isListening: ${isListening.value}, isSpeaking: ${isSpeaking.value}, isResponding: ${_isResponding.value}")
        
        // Only restart if we have permission, are in a chat, and continuous listening was enabled before backgrounding
        if (_micPermissionGranted.value && _chatId.value > 0 && voiceManager.isContinuousListeningEnabled.value) {
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
        } else {
            Log.d(TAG, "[LOG] Not restarting continuous listening - permission: ${_micPermissionGranted.value}, chatId: ${_chatId.value}, continuousEnabled: ${voiceManager.isContinuousListeningEnabled.value}")
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
                        // TTSManager handles its own isSpeaking state
                    },
                    onCompleted = {
                        Log.d(TAG, "TTS completed - audio focus released")
                        // TTSManager handles its own isSpeaking state
                        // Handle continuous listening if enabled and headphones are connected
                        if (voiceManager.isContinuousListeningEnabled.value && ttsManager.areHeadphonesConnected()) {
                            Log.d(TAG, "TTS completed with continuous listening and headphones - auto-resuming listening")
                            startContinuousListening()
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
            
            // Current time and 2-minute threshold
            val currentTime = System.currentTimeMillis()
            val retryThresholdMs = 2 * 60 * 1000L // 2 minutes
            
            // Find orphaned user messages that need retry
            val orphanedMessages = allMessages
                .filter { message ->
                    message.type == MessageType.USER &&
                    message.timestamp > lastAssistantMessageTime && // After last assistant response
                    (currentTime - message.timestamp) > retryThresholdMs && // Older than 2 minutes
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
                val success = whizServerRepository.sendMessage(
                    message.content, 
                    requestId, 
                    chatId
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