package com.example.whiz.ui.viewmodels

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.launch
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
    private val speechRecognitionService: SpeechRecognitionService,
    private val whizServerRepository: WhizServerRepository,
    private val authRepository: AuthRepository, // Add this
    private val userPreferences: UserPreferences,
    private val ttsManager: TTSManager,
    private val appLifecycleService: com.example.whiz.services.AppLifecycleService,
) : ViewModel() { // Removed TextToSpeech.OnInitListener

    private val TAG = "ChatViewModel"

    // Config state
    val configUseRemoteAgent = true;

    // Chat state
    private val _chatId = MutableStateFlow<Long>(-1)
    val chatId: StateFlow<Long> = _chatId.asStateFlow()
    private val _chatTitle = MutableStateFlow<String>("New Chat")
    val chatTitle = _chatTitle.asStateFlow()
    
    // Track multiple pending WebSocket requests by request ID
    private val pendingRequests = mutableMapOf<String, Long>() // requestId -> chatId

    // Track which request we should cancel if user sends an interrupt
    private var currentActiveRequestId: String? = null

    // Track locally-saved interrupt messages to prevent server duplication


    // Helper function to update responding state based on current chat's pending requests
    private fun updateRespondingStateForCurrentChat() {
        try {
            val currentChatId = _chatId.value
            val hasPendingRequests = pendingRequests.values.any { it == currentChatId }
            val wasResponding = _isResponding.value
            _isResponding.value = hasPendingRequests
            Log.d(TAG, "updateRespondingStateForCurrentChat: Chat $currentChatId has pending requests: $hasPendingRequests (was responding: $wasResponding)")
            
            // 🔧 If we just finished responding and continuous listening is enabled, restart microphone immediately
            if (wasResponding && !hasPendingRequests && continuousListeningEnabled && !_isSpeaking.value) {
                Log.d(TAG, "[LOG] Just finished computing - restarting continuous listening immediately")
                viewModelScope.launch {
                    // Small delay to ensure state propagation
                    delay(50L)
                    if (!_isResponding.value && !_isSpeaking.value && continuousListeningEnabled) {
                        startContinuousListening()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateRespondingStateForCurrentChat", e)
            _errorState.value = "Error updating chat state: ${e.message}"
        }
    }

    // UI state for the text input
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // Messages in the current chat
    val messages = _chatId.flatMapLatest { id ->
        if (id > 0) {
            repository.getMessagesForChat(id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly, // Changed from WhileSubscribed to Eagerly
        initialValue = emptyList()
    )

    // --- Speech Recognition State ---
    val transcriptionState = speechRecognitionService.transcriptionState
    val isListening = speechRecognitionService.isListening
    val speechError = speechRecognitionService.errorState
    // Track if the user *intended* to be listening before TTS started
    private var wasListeningBeforeTTS = false

    // Responses are in progress
    private val _isResponding = MutableStateFlow(false)
    val isResponding = _isResponding.asStateFlow()

    // --- Text-to-Speech State ---
    private val _isTTSInitialized = MutableStateFlow(false)
    private val _isSpeaking = MutableStateFlow(false) // Track if TTS is currently speaking
    val isSpeaking = _isSpeaking.asStateFlow()

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

    // Continuous listening state - exposed to UI
    private val _isContinuousListeningEnabled = MutableStateFlow(false)
    val isContinuousListeningEnabled = _isContinuousListeningEnabled.asStateFlow()
    
    private var continuousListeningEnabled: Boolean
        get() = _isContinuousListeningEnabled.value
        set(value) {
            _isContinuousListeningEnabled.value = value
        }

    // Track the current voice settings state to know when we need to reset
    private var currentVoiceSettings: com.example.whiz.data.preferences.VoiceSettings? = null

    // Helper method to detect if headphones are connected
    private fun areHeadphonesConnected(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Check for wired headphones or Bluetooth audio
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headphone status", e)
            false // Default to false (safer - assume speakers)
        }
    }

    // Helper method to determine if mic button should be shown during TTS
    fun shouldShowMicButtonDuringTTS(): Boolean {
        // Show mic button during TTS without headphones to allow interruption
        // Remove the !continuousListeningEnabled condition to allow interruption always
        return _isSpeaking.value && !areHeadphonesConnected()
    }
    
    // Handle mic button click during TTS - pause TTS and start listening
    fun handleMicClickDuringTTS() {
        if (_isSpeaking.value && !areHeadphonesConnected()) {
            Log.d(TAG, "handleMicClickDuringTTS: Pausing TTS and starting listening. continuousListening before: $continuousListeningEnabled")
            
            // Pause/stop TTS (but keep voice response enabled for future messages)
            ttsManager.stop()
            _isSpeaking.value = false
            
            // Enable continuous listening and start immediately
            // This allows the user to speak and interrupt TTS
            continuousListeningEnabled = true
            speechRecognitionService.continuousListeningEnabled = true
            
            viewModelScope.launch {
                // Small delay to ensure TTS stop is processed
                delay(100L)
                if (!_isResponding.value && !_isSpeaking.value) {
                    startContinuousListening()
                }
            }
        } else {
            Log.w(TAG, "handleMicClickDuringTTS: Called but conditions not met - isSpeaking: ${_isSpeaking.value}, headphones: ${areHeadphonesConnected()}")
        }
    }

    init {
        // Check if the app already has microphone permission
        _micPermissionGranted.value = PermissionHandler.hasMicrophonePermission(context)
        
        speechRecognitionService.initialize()
        
        // Initialize TTS using the new event-driven approach
        initializeTTS()

        // Start observing messages immediately
        if (configUseRemoteAgent) {
            Log.d(TAG, "Init: Using remote agent. Attempting WebSocket connection.")
            whizServerRepository.connect()
            // Enhanced server message collection with interrupt handling - moved inline above
        }
        
        // Observe voice settings changes and apply them to TTS
        viewModelScope.launch {
            userPreferences.voiceSettings.collect { voiceSettings ->
                if (_isTTSInitialized.value) {
                    applyVoiceSettings(voiceSettings)
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
            whizServerRepository.webSocketEvents.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocketEvent.Connected: Called.")
                        // Remove arbitrary delays and handle connection state immediately
                        _isConnectedToServer.value = true
                        _connectionError.value = null // Clear any connection errors when successfully connected
                        Log.d(TAG, "WebSocketEvent.Connected: Resetting isDisconnectingForAuthError to false.")
                        isDisconnectingForAuthError = false
                    }
                    is WebSocketEvent.Reconnecting -> {
                        Log.d(TAG, "WebSocketEvent.Reconnecting: Called.")
                        _isConnectedToServer.value = false
                        // Don't show "Connection lost" message to user - handle reconnection silently
                        // _connectionError.value = "Connection lost. Attempting to reconnect..."
                        _isResponding.value = false
                    }
                    is WebSocketEvent.Closed -> {
                        Log.d(TAG, "WebSocketEvent.Closed: Called. isDisconnectingForAuthError = $isDisconnectingForAuthError, _navigateToLogin = ${_navigateToLogin.value}")
                        _isConnectedToServer.value = false
                        pendingRequests.clear() // 🔧 Clear all pending requests on connection close
                        if (isDisconnectingForAuthError) {
                            Log.d(TAG, "WebSocketEvent.Closed: Intentional disconnect due to AuthError. Not queueing further actions here.")
                        } else if (_navigateToLogin.value) {
                            Log.d(TAG, "WebSocketEvent.Closed: Not attempting client-side reconnect as navigation to login is pending.")
                        } else {
                            // Don't show "Connection closed" message to user - handle reconnection silently
                            // if (_connectionError.value == null || _connectionError.value?.contains("reconnect") == false) {
                            //     _connectionError.value = "Connection closed."
                            // }
                            Log.d(TAG, "WebSocketEvent.Closed: Repository should be handling retries if applicable.")
                        }
                        currentActiveRequestId = null // Clear active request on disconnect
                    }
                    is WebSocketEvent.Error -> {
                        _isConnectedToServer.value = false
                        
                        // Only show error to user if it's a persistent failure after retries
                        val errorMessage = event.error.message ?: "Unknown connection failure"
                        
                        // Check if this is a final retry failure (contains "after X attempts")
                        if (errorMessage.contains("after") && errorMessage.contains("attempts")) {
                            // This is a final failure after all retries - show to user
                            _connectionError.value = "Failed to send message. Please check your connection and try again."
                            if (_chatId.value > 0) { // Only add if a chat is active
                                repository.addAssistantMessage(
                                    chatId = _chatId.value,
                                    content = "Error: Unable to send message. Please try again."
                                )
                            }
                            // Clear all pending requests on final connection error
                            pendingRequests.clear()
                            _isResponding.value = false
                            currentActiveRequestId = null
                        } else {
                            // This is likely a temporary connection issue - handle silently
                            Log.w(TAG, "Temporary connection error (will retry): $errorMessage")
                            
                            // Clear responding state for temporary connection errors too
                            // When message retries have failed completely (~6-8 seconds of trying),
                            // clear the responding state to allow user to interact with the microphone button
                            if (_isResponding.value) {
                                Log.d(TAG, "Clearing responding state due to connection error to unblock UI")
                                _isResponding.value = false
                                pendingRequests.clear()
                                currentActiveRequestId = null
                            }
                        }
                        
                        _showAuthErrorDialog.value = null
                        _navigateToLogin.value = false
                    }
                    is WebSocketEvent.AuthError -> {
                        Log.d(TAG, "WebSocketEvent.AuthError received: ${event.message}.")
                        _isConnectedToServer.value = false
                        pendingRequests.clear() // 🔧 Clear all pending requests on auth error
                        viewModelScope.launch {
                            val refreshSuccessful = authRepository.refreshAccessToken()
                            if (refreshSuccessful) {
                                Log.i(TAG, "Token refresh successful after WebSocket AuthError. Attempting to reconnect WebSocket.")
                                isDisconnectingForAuthError = false
                                _navigateToLogin.value = false
                                val conversationId = if (_chatId.value > 0) _chatId.value else null
                                whizServerRepository.connect(conversationId)
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
                        currentActiveRequestId = null // Clear active request on auth error
                    }
                    is WebSocketEvent.Cancelled -> {
                        Log.d(TAG, "Request ${event.cancelledRequestId} was cancelled successfully")
                        // Remove the cancelled request from pending requests
                        pendingRequests.remove(event.cancelledRequestId)
                        if (currentActiveRequestId == event.cancelledRequestId) {
                            currentActiveRequestId = null
                        }
                        updateRespondingStateForCurrentChat()
                    }
                    is WebSocketEvent.Interrupted -> {
                        Log.d(TAG, "Previous request was interrupted: ${event.message}")
                        // The backend has cancelled previous requests automatically
                        // Clear all pending requests since they were cancelled
                        val interruptedRequests = pendingRequests.keys.toList()
                        pendingRequests.clear()
                        currentActiveRequestId = null
                        updateRespondingStateForCurrentChat()
                        Log.d(TAG, "Cleared ${interruptedRequests.size} pending requests due to interrupt")
                    }
                    is WebSocketEvent.Message -> {
                        Log.d(TAG, "[LOG] WebSocketEvent.Message received. continuousListeningEnabled=$continuousListeningEnabled, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}, isTTSInitialized=${_isTTSInitialized.value}")
                        
                        // Create a unique event identifier for logging
                        val eventLogId = "[EventTextHash:${event.text.hashCode()}-Time:${System.currentTimeMillis()}]"
                        Log.d(TAG, "$eventLogId Processing WebSocketEvent.Message.")
                        
                        try {
                            var isErrorHandled = false
                            var messageContentForChat = event.text
                            var speakThisMessage = _isVoiceResponseEnabled.value

                            // Attempt to parse as JSON first
                            try {
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
                            } catch (e: JSONException) {
                                Log.d(TAG, "Message is not a JSON object or failed to parse: ${event.text}")
                                isErrorHandled = false 
                            } catch (e: Exception) {
                                Log.e(TAG, "$eventLogId Error parsing JSON message", e)
                                isErrorHandled = false
                            }

                            // 🔧 Enhanced request ID validation with error handling
                            val targetChatId = try {
                                if (event.requestId != null) {
                                    if (pendingRequests.containsKey(event.requestId)) {
                                        val chatId = pendingRequests[event.requestId]!!
                                        pendingRequests.remove(event.requestId) // Remove completed request
                                        Log.d(TAG, "$eventLogId Request ID ${event.requestId} mapped to chat $chatId (current: ${_chatId.value})")
                                        chatId
                                    } else {
                                        // Request ID provided but not found in pending requests
                                        Log.w(TAG, "$eventLogId Request ID ${event.requestId} not found in pending requests. Available: ${pendingRequests.keys}")
                                        // This could be a race condition or resumed session response
                                        // Process the message for current chat as fallback instead of skipping entirely
                                        Log.d(TAG, "$eventLogId Processing orphaned response for current chat as fallback")
                                        _chatId.value
                                    }
                                } else {
                                    // No request ID - this is a legacy message or server-initiated message
                                    Log.w(TAG, "$eventLogId No request ID provided. Using current chat as fallback.")
                                    _chatId.value
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "$eventLogId Error processing request ID validation", e)
                                _errorState.value = "Error processing server response: ${e.message}"
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }
                            
                            val isResponseForCurrentChat = (targetChatId == _chatId.value)
                            
                            // 🔧 Additional validation: only process if target chat is current chat
                            if (!isResponseForCurrentChat) {
                                Log.w(TAG, "$eventLogId Response is for chat $targetChatId but current chat is ${_chatId.value}. Skipping processing to prevent cross-chat contamination.")
                                // 🔧 Update responding state for current chat since we're not processing this response
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }
                            
                            Log.d(TAG, "$eventLogId PRE-CALL addAssistantMessage. Target Chat ID: $targetChatId, Current Chat ID: ${_chatId.value}, Request ID: ${event.requestId}, Is Current Chat: $isResponseForCurrentChat")
                            
                            if (targetChatId > 0) {
                                // Only save assistant message to local DB if NOT using remote agent
                                // Remote agent (WebSocket server) handles message persistence
                                if (!configUseRemoteAgent) {
                                    try {
                                        viewModelScope.launch {
                                            val messageId = repository.addAssistantMessage(
                                                chatId = targetChatId,
                                                content = messageContentForChat
                                            )
                                            Log.d(TAG, "$eventLogId POST-CALL addAssistantMessage. Message ID: $messageId added to chat: $targetChatId")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "$eventLogId Error adding assistant message to repository", e)
                                        _errorState.value = "Error saving response: ${e.message}"
                                    }
                                } else {
                                    Log.d(TAG, "$eventLogId Skipping local assistant message save - remote agent handles persistence")
                                    // For remote agent, we need to manually refresh to show the server-saved message
                                    Log.d(TAG, "$eventLogId Remote agent - triggering manual refresh to show server-saved message")
                                    
                                    try {
                                        viewModelScope.launch {
                                            // Remove arbitrary delay - refresh immediately when server responds
                                            repository.refreshMessages()
                                            Log.d(TAG, "$eventLogId Triggered messages refresh for remote agent response")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "$eventLogId Error refreshing messages for remote agent response", e)
                                    }
                                    
                                    // Only refresh conversations if a new one might have been created
                                    if (targetChatId <= 0) {
                                        try {
                                            viewModelScope.launch {
                                                // Remove arbitrary delay - refresh immediately when needed
                                                repository.refreshConversations()
                                                Log.d(TAG, "$eventLogId Refreshed conversations for potential new chat creation")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "$eventLogId Error refreshing conversations", e)
                                        }
                                    }
                                }
                            } else {
                                Log.w(TAG, "$eventLogId SKIPPED addAssistantMessage because target chatId is not > 0 (Value: $targetChatId)")
                            }

                            // TTS and UI updates (only for current chat)
                            // 🔧 Update responding state FIRST, before trying to restart listening
                            updateRespondingStateForCurrentChat()
                            
                            try {
                                // 🔧 Additional validation: Only speak if this is truly for the current visible chat
                                // and the message is actually being displayed to the user
                                val shouldSpeak = _isVoiceResponseEnabled.value && 
                                                _isTTSInitialized.value && 
                                                speakThisMessage && 
                                                messageContentForChat.isNotBlank() &&
                                                isResponseForCurrentChat && 
                                                targetChatId > 0 && // Only speak for real chats, not new chat creation
                                                targetChatId == _chatId.value // Double-check current chat
                                
                                Log.d(TAG, "$eventLogId TTS Decision: shouldSpeak=$shouldSpeak, voiceEnabled=${_isVoiceResponseEnabled.value}, ttsInit=${_isTTSInitialized.value}, speakFlag=$speakThisMessage, isForCurrentChat=$isResponseForCurrentChat, targetChat=$targetChatId, currentChat=${_chatId.value}")
                                
                                if (shouldSpeak) {
                                    if (isListening.value) {
                                        wasListeningBeforeTTS = true
                                        speechRecognitionService.stopListening() // Stop STT before TTS speaks
                                    }
                                    val utteranceId = UUID.randomUUID().toString()
                                    _isSpeaking.value = true // Indicate TTS is starting
                                    Log.d(TAG, "$eventLogId Starting TTS for message: '${messageContentForChat.take(50)}...'")
                                    ttsManager.speak(messageContentForChat, "chat_message")
                                    // Note: _isSpeaking will be reset to false in UtteranceProgressListener callbacks
                                } else {
                                    Log.d(TAG, "$eventLogId Skipping TTS - conditions not met")
                                    // Always restart continuous listening after assistant reply if enabled and not speaking
                                    if (continuousListeningEnabled && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Restarting continuous listening after assistant reply.")
                                        startContinuousListening()
                                    } else if (wasListeningBeforeTTS && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not speaking, wasListeningBeforeTTS=true, restarting ASR.")
                                        speechRecognitionService.startListening { finalTranscription ->
                                            if (finalTranscription.isNotBlank()) {
                                                sendUserInput(finalTranscription)
                                            }
                                        }
                                        wasListeningBeforeTTS = false
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "$eventLogId Error in TTS/listening handling", e)
                            }
                            
                            // 🔧 Add logging to track input text state after response processing
                            Log.d(TAG, "$eventLogId After response processing: _inputText.value = '${_inputText.value}', _isResponding = ${_isResponding.value}")

                            // Clear the active request ID when we receive a response
                            if (event.requestId != null && event.requestId == currentActiveRequestId) {
                                currentActiveRequestId = null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "$eventLogId Unexpected error processing WebSocket message", e)
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
        viewModelScope.launch {
            try {
                // 🔧 Enhanced cleanup when switching chats
                Log.d(TAG, "Loading chat $chatId. Current chat: ${_chatId.value}, Pending requests: ${pendingRequests.size}")
                
                // Disconnect from server if switching chats or loading new
                if (configUseRemoteAgent) {
                    try {
                        whizServerRepository.disconnect()
                        _isConnectedToServer.value = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disconnecting WebSocket during loadChat", e)
                    }
                }
                
                // 🔧 Clear pending requests and log what we're clearing
                try {
                    if (pendingRequests.isNotEmpty()) {
                        Log.w(TAG, "loadChat: Clearing ${pendingRequests.size} pending requests: ${pendingRequests.keys}")
                        pendingRequests.clear()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing pending requests during loadChat", e)
                }

                if (chatId <= 0) {
                    // Handle new chat creation - immediately reset responding state since this is a fresh chat
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
                            _chatId.value = -1
                            _chatTitle.value = "New Chat"
                            _isResponding.value = false // 🔧 Immediately set to false for new chats
                            Log.d(TAG, "🔥 loadChat: Chat not found, creating new chat, responding state reset to false")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading chat from repository", e)
                        _chatId.value = -1
                        _chatTitle.value = "New Chat"
                        _isResponding.value = false // 🔧 Immediately set to false on error
                        _errorState.value = "Error loading chat: ${e.message}"
                    }
                }
                
                // Reset states
                Log.d(TAG, "[LOG] loadChat: Clearing _inputText.value. Previous value: '${_inputText.value}'")
                _inputText.value = ""
                // 🔧 Update responding state based on current chat's pending requests
                updateRespondingStateForCurrentChat()
                _errorState.value = null // 🔧 Clear any error states when switching chats
                _connectionError.value = null // 🔧 Clear connection errors too
                
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
                    speechRecognitionService.stopListening()
                    Log.d(TAG, "loadChat: Stopping TTS (isSpeaking: ${_isSpeaking.value})")
                    ttsManager.stop()
                    _isSpeaking.value = false
                    Log.d(TAG, "loadChat: Speech services stopped successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping speech services during loadChat", e)
                }

                // Refresh messages to ensure we have latest data
                if (_chatId.value > 0) {
                    try {
                        // Skip manual refresh - the reactive flow will update automatically when chatId changes
                        Log.d(TAG, "loadChat: Skipping manual messages refresh - reactive flow will handle it")
                        // repository.refreshMessages() // Commented out to prevent duplicate requests
                    } catch (e: Exception) {
                        Log.e(TAG, "Error refreshing messages during loadChat", e)
                    }
                }

                // Connect to server if needed *after* chat ID is set
                if (configUseRemoteAgent && _chatId.value != 0L) { // Connect for new (-1) or existing chats
                    try {
                        delay(100) // Small delay to ensure state propagation
                        // Pass the conversation ID for existing chats, null for new chats (chatId = -1)
                        val conversationId = if (_chatId.value > 0) _chatId.value else null
                        whizServerRepository.connect(conversationId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error connecting to WebSocket during loadChat", e)
                        _connectionError.value = "Failed to connect to server: ${e.message}"
                    }
                }

                // Auto-enable continuous listening if we have microphone permission
                // 🔧 Double-check permission state to ensure it's current
                val actualPermissionState = PermissionHandler.hasMicrophonePermission(context)
                if (_micPermissionGranted.value != actualPermissionState) {
                    Log.d(TAG, "[LOG] Updating permission state from ${_micPermissionGranted.value} to $actualPermissionState")
                    _micPermissionGranted.value = actualPermissionState
                }
                
                if (_micPermissionGranted.value) {
                    try {
                        Log.d(TAG, "[LOG] Auto-enabling continuous listening on chat load (permission granted)")
                        delay(500L) // Increased delay to ensure speech service is fully stopped
                        
                        // Always enable continuous listening and start it, regardless of current state
                        continuousListeningEnabled = true
                        speechRecognitionService.continuousListeningEnabled = true
                        
                        // Force start continuous listening, but add additional delay to ensure clean state
                        delay(200L) // Additional delay to ensure isListening state is updated
                        val currentListening = isListening.value
                        Log.d(TAG, "[LOG] About to start continuous listening - isListening: $currentListening, speaking: ${_isSpeaking.value}, responding: ${_isResponding.value}")
                        
                        if (!currentListening && !_isSpeaking.value && !_isResponding.value) {
                            Log.d(TAG, "[LOG] Starting continuous listening (conditions met)")
                            startContinuousListening()
                        } else {
                            Log.d(TAG, "[LOG] Force starting continuous listening despite state - isListening: $currentListening")
                            // Force start anyway since we just loaded a new chat
                            startContinuousListening()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting continuous listening during loadChat", e)
                    }
                } else {
                    Log.w(TAG, "[LOG] Cannot auto-enable continuous listening - no microphone permission")
                }
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
        }
    }

    fun updateInputText(text: String) {
        Log.d(TAG, "[LOG] 🔥 updateInputText called. SETTING _inputText.value from '${_inputText.value}' to: '$text'")
        Log.d(TAG, "[LOG] 🔥 updateInputText stack trace:", Exception("Stack trace for updateInputText"))
        
        // 🔧 Prevent setting input text back if we just cleared it due to assistant response
        // This prevents UI recomposition from overriding our intentional clearing
        if (_inputText.value.isEmpty() && text.isNotEmpty() && _isResponding.value) {
            Log.d(TAG, "[LOG] 🔥 updateInputText BLOCKED: Preventing input restoration during response processing (would set to: '$text')")
            return
        }
        
        _inputText.value = text
    }

    fun toggleSpeechRecognition() {
        Log.d(TAG, "[LOG] toggleSpeechRecognition called. isSpeaking=${_isSpeaking.value}, isResponding=${_isResponding.value}, micPermissionGranted=${_micPermissionGranted.value}, isListening=${isListening.value}, continuousListeningEnabled=$continuousListeningEnabled")
        if (!_micPermissionGranted.value) {
            Log.w(TAG, "[LOG] Microphone permission not granted")
            _errorState.value = "Microphone permission required" 
            return
        }
        
        // Case 1: Microphone is actively listening - always allow stopping
        if (isListening.value) {
            Log.d(TAG, "[LOG] Stopping active speech recognition and continuous listening")
            continuousListeningEnabled = false
            speechRecognitionService.continuousListeningEnabled = false
            speechRecognitionService.stopListening()
            return
        }
        
        // Case 2: Continuous listening is enabled but mic not actively listening
        // Allow user to disable continuous listening mode
        if (continuousListeningEnabled) {
            Log.d(TAG, "[LOG] Disabling continuous listening mode (was enabled but not actively listening)")
            continuousListeningEnabled = false
            speechRecognitionService.continuousListeningEnabled = false
            return
        }
        
        // Case 3: Want to enable continuous listening - only block during TTS speaking
        // 🔧 FIXED: Allow microphone during server responses, only block during TTS
        if (_isSpeaking.value) {
            Log.d(TAG, "[LOG] Cannot start listening while assistant is speaking (TTS)")
            _errorState.value = "Cannot start listening while assistant is speaking"
            return
        }
        
        // Case 4: Enable continuous listening and start immediately unless TTS is active
        Log.d(TAG, "[LOG] Starting speech recognition and enabling continuous listening. Clearing _inputText.value. Previous value: '${_inputText.value}'")
        _inputText.value = ""
        continuousListeningEnabled = true
        speechRecognitionService.continuousListeningEnabled = true
        
        // 🔧 IMPROVED: Start listening immediately unless TTS is speaking
        // Allow listening during server responses for better UX
        if (!_isSpeaking.value) {
            Log.d(TAG, "[LOG] Starting microphone immediately (isSpeaking=${_isSpeaking.value}, isResponding=${_isResponding.value})")
            speechRecognitionService.startListening { recognizedText ->
                Log.d(TAG, "[LOG] toggleSpeechRecognition startListening callback. recognizedText: '$recognizedText'")
                if (recognizedText.isNotBlank()) {
                    sendUserInput(recognizedText)
                }
            }
        } else {
            Log.d(TAG, "[LOG] Delaying microphone start - TTS is speaking (${_isSpeaking.value})")
        }
    }

    // Method specifically for ensuring continuous listening is enabled (for voice mode activation)
    fun ensureContinuousListeningEnabled() {
        Log.d(TAG, "[LOG] ensureContinuousListeningEnabled called. micPermissionGranted=${_micPermissionGranted.value}, continuousListeningEnabled=$continuousListeningEnabled, isListening=${isListening.value}, isSpeaking=${_isSpeaking.value}, isResponding=${_isResponding.value}")
        
        if (!_micPermissionGranted.value) {
            Log.w(TAG, "[LOG] Cannot ensure continuous listening - no microphone permission")
            return
        }
        
        // If continuous listening is already enabled, don't disable it
        if (continuousListeningEnabled) {
            Log.d(TAG, "[LOG] Continuous listening already enabled, ensuring it's active")
            // If not currently listening and not busy, start listening
            if (!isListening.value && !_isSpeaking.value && !_isResponding.value) {
                startContinuousListening()
            }
            return
        }
        
        // Enable continuous listening if not already enabled
        Log.d(TAG, "[LOG] Enabling continuous listening for voice mode")
        _inputText.value = ""
        continuousListeningEnabled = true
        speechRecognitionService.continuousListeningEnabled = true
        
        // Start listening if not busy
        if (!_isSpeaking.value && !_isResponding.value) {
            startContinuousListening()
        }
    }

    private fun startContinuousListening() {
        Log.d(TAG, "[LOG] startContinuousListening called. continuousListeningEnabled=$continuousListeningEnabled, isResponding=${_isResponding.value}")
        
        // Headphone-aware listening behavior
        if (_isSpeaking.value) {
            val headphonesConnected = areHeadphonesConnected()
            if (!headphonesConnected) {
                // Without headphones: Don't listen during TTS to prevent feedback
                Log.d(TAG, "[LOG] startContinuousListening: Skipping start while TTS is speaking without headphones (will restart when TTS completes)")
                return
            } else {
                // With headphones: Continue listening even during TTS
                Log.d(TAG, "[LOG] startContinuousListening: Allowing listening during TTS with headphones connected")
            }
        }
        
        speechRecognitionService.startListening { finalText ->
            Log.d(TAG, "[LOG] startContinuousListening: got transcription. continuousListeningEnabled=$continuousListeningEnabled, text='$finalText', isResponding=${_isResponding.value}")
            
            // Always set transcribed text to input field when we have valid text
            // This ensures text is preserved even when user manually stops the microphone
            if (finalText.isNotBlank()) {
                Log.d(TAG, "[LOG] startContinuousListening: Setting transcribed text to input field: '$finalText'")
            _inputText.value = finalText
                
                // Only auto-send if continuous listening is still enabled
                if (continuousListeningEnabled) {
                    Log.d(TAG, "[LOG] startContinuousListening: Auto-sending transcription (continuous listening enabled)")
            sendUserInput(finalText) // Send the transcription
                } else {
                    Log.d(TAG, "[LOG] startContinuousListening: Preserving transcription in input field (continuous listening disabled, user can manually send)")
                }
            }
            
            // Always restart listening if continuous listening is enabled, regardless of responding state
            if (continuousListeningEnabled) {
                Log.d(TAG, "[LOG] Continuous listening: restarting after result (isResponding=${_isResponding.value})")
                viewModelScope.launch {
                    // Small delay to ensure the previous listening session is fully stopped
                    delay(100L)
                    if (continuousListeningEnabled && !_isSpeaking.value) {
                        startContinuousListening() // This will check isSpeaking again
                    }
                }
            } else {
                Log.d(TAG, "[LOG] Continuous listening disabled, not restarting")
            }
        }
    }

    fun sendUserInput(text: String = _inputText.value, isInterrupt: Boolean = false) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) return
        
        // Check if this should be treated as an interrupt (bot is responding and we have new input)
        val shouldInterrupt = !isInterrupt && _isResponding.value && canInterrupt()
        
        if (shouldInterrupt) {
            Log.d(TAG, "sendUserInput: Auto-detecting interrupt condition, routing to sendInterruptMessage")
            sendInterruptMessage(trimmedText)
            return // 🔧 Return immediately - sendInterruptMessage handles everything
        }
        
        if (isInterrupt && canInterrupt()) {
            sendInterruptMessage(text)
            return // 🔧 Return immediately - sendInterruptMessage handles everything
        }
        
        // Only block sending if responding AND this is not an interrupt scenario
        if (_isResponding.value && !shouldInterrupt) {
            Log.d(TAG, "sendUserInput: Blocked - bot is responding and this is not an interrupt")
            return
        }

        // 🔧 Clear input text immediately when sending message (only for non-interrupt flow)
        Log.d(TAG, "[LOG] 🔥 sendUserInput: Clearing input text immediately after sending (was: '${_inputText.value}')")
        _inputText.value = ""

        viewModelScope.launch {
            var currentChatId = _chatId.value

            // Handle new chat creation differently for remote vs local agent
            if (currentChatId <= 0) {
                if (configUseRemoteAgent) {
                    // For remote agent: Don't create conversation locally
                    // Let the WebSocket server create it and then sync
                    Log.d(TAG, "sendUserInput: Using remote agent for new chat - server will create conversation")
                    currentChatId = -1 // Keep as new chat indicator
                } else {
                    // For local agent: Create conversation locally as before
                    val chatTitle = repository.deriveChatTitle(trimmedText)
                    val newChatId = repository.createChat(chatTitle)
                    _chatId.value = newChatId
                    _chatTitle.value = chatTitle
                    currentChatId = newChatId
                }

                // Connect to WebSocket if using remote agent and not connected
                if(configUseRemoteAgent && !_isConnectedToServer.value) {
                    // For new chats, we don't have a conversation_id yet, so pass null
                    whizServerRepository.connect(null)
                }
            }

            // 🔧 OPTIMISTIC UI: Always show user messages immediately for good UX
            // Repository will handle deduplication when server messages arrive
            try {
                val actualChatId = if (currentChatId > 0) currentChatId else {
                    // For new chats, create a temporary local chat to show the message immediately
                    val tempTitle = repository.deriveChatTitle(trimmedText)
                    val tempChatId = repository.createChat(tempTitle)
                    _chatId.value = tempChatId
                    _chatTitle.value = tempTitle
                    tempChatId
                }
                val localMessageId = if (configUseRemoteAgent) {
                    // For remote agent: use optimistic UI (local only, no API call)
                    repository.addUserMessageOptimistic(actualChatId, trimmedText)
                } else {
                    // For local agent: use regular method (creates via API)
                    repository.addUserMessage(actualChatId, trimmedText)
                }
                Log.d(TAG, "sendUserInput: Added ${if (configUseRemoteAgent) "optimistic" else "regular"} user message (chatId: $actualChatId, messageId: $localMessageId, agent: ${if (configUseRemoteAgent) "remote" else "local"})")
            } catch (e: Exception) {
                Log.e(TAG, "sendUserInput: Failed to add optimistic user message", e)
            }

            // Send to agent (local or remote)
            if (configUseRemoteAgent && !_isConnectedToServer.value) {
                Log.w(TAG, "sendUserInput: Remote agent not connected, cannot send message")
                _errorState.value = "Not connected to server. Please check your connection."
                return@launch
            }

            if (configUseRemoteAgent) {
                Log.d(TAG, "sendUserInput: Using remote agent. Connected: ${_isConnectedToServer.value}")
                
                // Always attempt to send the message regardless of connection status
                // The WebSocket repository will handle queueing and retry automatically
                _isResponding.value = true // Show thinking indicator
                val requestId = java.util.UUID.randomUUID().toString()
                currentActiveRequestId = requestId // Track the active request
                pendingRequests[requestId] = currentChatId
                
                Log.d(TAG, "sendUserInput: Sending message via WebSocket: '$trimmedText' for chat: $currentChatId with requestId: $requestId")
                val success = whizServerRepository.sendMessage(trimmedText, requestId)
                
                if (!success) {
                    // Message was queued for retry, don't clear the request tracking yet
                    // The retry mechanism will handle this transparently
                    Log.d(TAG, "sendUserInput: Message queued for retry - keeping request tracking")
                } else {
                    Log.d(TAG, "sendUserInput: Message sent successfully via WebSocket for chat: $currentChatId with requestId: $requestId")
                }
                
                // For new chats, refresh conversations but don't switch - we already created a local chat for optimistic UI
                if (currentChatId <= 0) {
                    try {
                        viewModelScope.launch {
                            repository.refreshConversations()
                            Log.d(TAG, "sendUserInput: Triggered conversations refresh for new chat (staying with local chat ${_chatId.value})")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "sendUserInput: Error refreshing conversations for new chat", e)
                    }
                }
                
                // 🔧 SMART DEDUPLICATION: Trigger refresh to reconcile with server, repository will deduplicate
                // This ensures we get the authoritative server message while avoiding duplicates
                try {
                    viewModelScope.launch {
                        delay(100) // Small delay to let server process
                        repository.refreshMessages()
                        Log.d(TAG, "sendUserInput: Triggered messages refresh for server reconciliation with deduplication")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendUserInput: Error triggering messages refresh", e)
                }
                
                // Response handling is done in observeServerMessages
            } else {
                // --- Local Fallback ---
                Log.d(TAG, "sendUserInput: Using local fallback")
                if (currentChatId > 0) {
                    generateAssistantResponse(currentChatId)
                }
            }

            // Schedule persistence check (only for local agent)
            if (!configUseRemoteAgent && currentChatId > 0) {
                schedulePersistenceCheck(currentChatId)
            }
        }
    }

    fun toggleVoiceResponse() {
        _isVoiceResponseEnabled.update { !it }
        if (!_isVoiceResponseEnabled.value) {
            ttsManager.stop() // Stop speaking if toggled off
            _isSpeaking.value = false
            
            // If continuous listening is enabled, restart it immediately when TTS is stopped
            if (continuousListeningEnabled && !_isResponding.value) {
                Log.d(TAG, "[LOG] Voice response disabled, restarting continuous listening immediately")
                viewModelScope.launch {
                    delay(50L) // Very short delay to ensure TTS stop is processed
                    if (!_isResponding.value && !_isSpeaking.value && continuousListeningEnabled) {
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
            Log.w(TAG, "onCleared: Clearing ${pendingRequests.size} pending requests: ${pendingRequests.keys}")
            pendingRequests.clear()
        }
        
        speechRecognitionService.release()
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
        _isSpeaking.value = false
        continuousListeningEnabled = false
        
        Log.d(TAG, "ChatViewModel cleared, TTS shutdown, SpeechRecognitionService destroyed, WebSocket disconnected, pending requests cleared.")
    }

    // Moved sendInputText here and made explicitly public
    public fun sendInputText() {
        val textToSend = _inputText.value.trim()
        if (textToSend.isBlank()) return

        Log.d(TAG, "🔥 sendInputText called with text: '$textToSend', current chatId: ${_chatId.value}")
        _isResponding.value = true

        viewModelScope.launch {
            if (_chatId.value <= 0) {
                if (configUseRemoteAgent) {
                    // For remote agent: Don't create conversation locally
                    // Let the WebSocket server create it and then sync
                    Log.d(TAG, "sendInputText: Using remote agent for new chat - server will create conversation")
                    // Keep _chatId.value as -1 for now
                } else {
                    // For local agent: Create conversation locally as before
                    Log.w(TAG, "🔥 sendInputText: chatId is ${_chatId.value}, creating NEW chat")
                    val newChatId = repository.createChat(repository.deriveChatTitle(textToSend))
                    _chatId.value = newChatId
                    Log.d(TAG, "🔥 sendInputText: Created new chat with ID: $newChatId")
                    if (newChatId <=0) {
                        Log.e(TAG, "Failed to create new chat.")
                        updateRespondingStateForCurrentChat() // Update based on remaining requests
                        _errorState.value = "Failed to create chat. Please try again."
                        return@launch
                    }
                }
            } else {
                Log.d(TAG, "🔥 sendInputText: Using existing chatId: ${_chatId.value}")
            }
            
            Log.d(TAG, "🔥 sendInputText: Adding optimistic user message to chat ${_chatId.value}: '$textToSend'")
            // Always save user message for immediate display (optimistic UI)
            // Repository will handle deduplication when server messages arrive
            if (_chatId.value > 0) {
                if (configUseRemoteAgent) {
                    // For remote agent: use optimistic UI (local only, no API call)
                    repository.addUserMessageOptimistic(_chatId.value, textToSend)
                    Log.d(TAG, "sendInputText: Added optimistic user message for remote agent")
                } else {
                    // For local agent: use regular method (creates via API)
                    repository.addUserMessage(_chatId.value, textToSend)
                    Log.d(TAG, "sendInputText: Added user message for local agent")
                }
            } else {
                Log.d(TAG, "sendInputText: Skipping message save - no valid chat ID")
            }

            // 🔧 Input will be cleared when bot responds (better UX)
            Log.d(TAG, "[LOG] sendInputText: Input kept for UX feedback (current input: '${_inputText.value}')")

            if (configUseRemoteAgent) {
                // Generate request ID and track this request
                val requestId = java.util.UUID.randomUUID().toString()
                currentActiveRequestId = requestId // Track the active request
                pendingRequests[requestId] = _chatId.value
                val success = whizServerRepository.sendMessage(textToSend, requestId)
                if (!success) {
                    // Message was queued for retry, don't clear the request tracking yet
                    // The retry mechanism will handle this transparently
                    Log.d(TAG, "sendInputText: Message queued for retry - keeping request tracking")
                } else {
                    Log.d(TAG, "sendInputText: Message sent successfully via WebSocket")
                }
                
                // For new chats, refresh conversations after server creates it
                if (_chatId.value <= 0) {
                    try {
                        viewModelScope.launch {
                            // Remove arbitrary delay - server should acknowledge immediately
                            repository.refreshConversations()
                            Log.d(TAG, "sendInputText: Triggered conversations refresh for new chat")
                            
                            // Try to find the newly created conversation and switch to it
                            val conversations = repository.conversations.value
                            if (conversations.isNotEmpty()) {
                                val newestConversation = conversations.first() // Most recent
                                _chatId.value = newestConversation.id
                                _chatTitle.value = newestConversation.title
                                Log.d(TAG, "sendInputText: Switched to new conversation ${newestConversation.id}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "sendInputText: Error refreshing conversations for new chat", e)
                    }
                }
            } else {
                updateRespondingStateForCurrentChat() // Update based on remaining requests (should be none for local)
            }
        }
        // 🔧 Don't clear input when sending - will be cleared when bot responds
        Log.d(TAG, "[LOG] sendInputText: NOT clearing input (better UX - kept for feedback)")
    }

    // Called when permission is granted from UI
    fun onMicrophonePermissionGranted() {
        _micPermissionGranted.value = true
        _errorState.value = null
        
        // When permission is granted, make sure SpeechRecognitionService is properly initialized
        try {
            Log.d(TAG, "Microphone permission granted, initializing speech service")
            speechRecognitionService.initialize()
            
            // Auto-enable continuous listening when permission is granted
            if (_chatId.value != 0L && !continuousListeningEnabled) {
                Log.d(TAG, "[LOG] Auto-enabling continuous listening after permission granted")
                viewModelScope.launch {
                    delay(100L) // Small delay to ensure service is initialized
                    continuousListeningEnabled = true
                    speechRecognitionService.continuousListeningEnabled = true
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
                speechRecognitionService.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition after permission denied", e)
            }
        }
    }

    // Called when app goes to background - sync the continuous listening state
    fun onAppBackgrounded() {
        Log.d(TAG, "[LOG] onAppBackgrounded called. continuousListeningEnabled=$continuousListeningEnabled")
        
        // Stop continuous listening cleanly and sync state
        if (continuousListeningEnabled) {
            Log.d(TAG, "[LOG] Stopping continuous listening due to app backgrounded")
            try {
                continuousListeningEnabled = false
                speechRecognitionService.continuousListeningEnabled = false
                speechRecognitionService.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping continuous listening on background", e)
            }
        }
    }

    // Called when app comes back to foreground - restart continuous listening if it was enabled
    fun onAppForegrounded() {
        Log.d(TAG, "[LOG] onAppForegrounded called. continuousListeningEnabled=$continuousListeningEnabled, micPermissionGranted=${_micPermissionGranted.value}, chatId=${_chatId.value}")
        Log.d(TAG, "[LOG] Current states - isListening: ${isListening.value}, isSpeaking: ${_isSpeaking.value}, isResponding: ${_isResponding.value}")
        
        // Only restart if we have permission, are in a chat, and we're in voice mode
        if (_micPermissionGranted.value && _chatId.value > 0) {
            try {
                // Re-enable continuous listening
                continuousListeningEnabled = true
                speechRecognitionService.continuousListeningEnabled = true
                
                // More aggressive restart logic to handle edge cases
                viewModelScope.launch {
                    delay(300L) // Slightly longer delay to ensure app is fully resumed
                    
                    // Reset potentially stuck states that might prevent restart
                    if (_isSpeaking.value) {
                        Log.d(TAG, "[LOG] Detected stuck speaking state, clearing it")
                        _isSpeaking.value = false
                        ttsManager.stop() // Force stop TTS to clear speaking state
                    }
                    
                    // Double-check conditions and force restart if needed
                    if (continuousListeningEnabled && !isListening.value) {
                        Log.d(TAG, "[LOG] Force restarting continuous listening after app foregrounded")
                        try {
                            // Force stop any existing listening first
                            speechRecognitionService.stopListening()
                            delay(100L) // Brief pause
                            startContinuousListening()
                        } catch (e: Exception) {
                            Log.e(TAG, "[LOG] Error in force restart, trying direct service restart", e)
                            // Fallback: try direct service restart
                            speechRecognitionService.stopListening()
                            delay(100L)
                            if (continuousListeningEnabled) {
                                speechRecognitionService.startListening { finalText ->
                                    if (finalText.isNotBlank()) {
                                        _inputText.value = finalText
                                        if (continuousListeningEnabled) {
                                            sendUserInput(finalText)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (isListening.value) {
                        Log.d(TAG, "[LOG] Already listening, no restart needed")
                    } else {
                        Log.d(TAG, "[LOG] Not restarting - continuousListeningEnabled: $continuousListeningEnabled, isListening: ${isListening.value}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting continuous listening after app foregrounded", e)
            }
        } else {
            Log.d(TAG, "[LOG] Not restarting continuous listening - permission: ${_micPermissionGranted.value}, chatId: ${_chatId.value}")
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
     * Send an interrupt message while there's an active request
     */
    fun sendInterruptMessage(message: String) {
        val trimmedText = message.trim()
        if (trimmedText.isBlank()) {
            Log.w(TAG, "sendInterruptMessage: Attempted to send blank interrupt message")
            return
        }

        if (!configUseRemoteAgent) {
            Log.w(TAG, "sendInterruptMessage: Interrupts only supported with remote agent")
            return
        }

        Log.d(TAG, "sendInterruptMessage: Sending interrupt message: '$trimmedText'")
        
        // 🔧 OPTIMISTIC UI: Always show interrupt messages immediately for good UX
        // Server will handle deduplication if needed
        val currentChatId = _chatId.value
        viewModelScope.launch {
            try {
                val actualChatId = if (currentChatId > 0) currentChatId else {
                    // For new chats, create a temporary local chat to show the message immediately
                    val tempTitle = repository.deriveChatTitle(trimmedText)
                    val tempChatId = repository.createChat(tempTitle)
                    _chatId.value = tempChatId
                    _chatTitle.value = tempTitle
                    tempChatId
                }
                val localMessageId = if (configUseRemoteAgent) {
                    // For remote agent: use optimistic UI (local only, no API call)
                    repository.addUserMessageOptimistic(actualChatId, trimmedText)
                } else {
                    // For local agent: use regular method (creates via API)
                    repository.addUserMessage(actualChatId, trimmedText)
                }
                Log.d(TAG, "sendInterruptMessage: Added ${if (configUseRemoteAgent) "optimistic" else "regular"} user message (chatId: $actualChatId, messageId: $localMessageId)")
            } catch (e: Exception) {
                Log.e(TAG, "sendInterruptMessage: Failed to add local user message", e)
            }
        }
        
        // Clear input text immediately after sending interrupt (like normal sendUserInput)
        Log.d(TAG, "[LOG] 🔥 sendInterruptMessage: Clearing input text immediately after sending (was: '${_inputText.value}')")
        _inputText.value = ""
        
        // Generate new request ID for the interrupt
        val requestId = java.util.UUID.randomUUID().toString()
        currentActiveRequestId = requestId
        
        // Send the interrupt message (backend will automatically cancel active requests)
        val success = whizServerRepository.sendInterruptMessage(trimmedText, requestId)
        
        if (success) {
            // Track this new request
            pendingRequests[requestId] = _chatId.value
            Log.d(TAG, "sendInterruptMessage: Interrupt sent successfully with requestId: $requestId")
            
            // Update UI state
            _isResponding.value = true
            
        } else {
            currentActiveRequestId = null
            Log.d(TAG, "sendInterruptMessage: Interrupt queued for retry - message already visible in UI")
        }
        
        // Trigger UI refresh to reconcile with server messages (will deduplicate automatically)
        try {
            viewModelScope.launch {
                repository.refreshMessages()
                Log.d(TAG, "sendInterruptMessage: Triggered messages refresh for server reconciliation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendInterruptMessage: Error triggering messages refresh", e)
        }
    }

    /**
     * Check if there are any active requests that can be interrupted
     */
    fun canInterrupt(): Boolean {
        return configUseRemoteAgent && 
               _isResponding.value && 
               _chatId.value > 0  // Allow interrupts even when disconnected - optimistic UI will handle it
    }

    /**
     * Interrupt the current response with the current input text
     */
    fun interruptResponse() {
        val currentInput = _inputText.value
        if (currentInput.isBlank()) {
            Log.w(TAG, "interruptResponse: No input text to send as interrupt")
            return
        }
        
        if (!canInterrupt()) {
            Log.w(TAG, "interruptResponse: Cannot interrupt - conditions not met")
            return
        }
        
        Log.d(TAG, "interruptResponse: Interrupting with message: '$currentInput'")
        sendUserInput(currentInput, isInterrupt = true)
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
                        _isSpeaking.value = true
                    },
                    onCompleted = {
                        Log.d(TAG, "TTS completed - audio focus released")
                        _isSpeaking.value = false
                        // Handle continuous listening if enabled and headphones are connected
                        if (continuousListeningEnabled && areHeadphonesConnected()) {
                            Log.d(TAG, "TTS completed with continuous listening and headphones - auto-resuming listening")
                            startContinuousListening()
                        }
                    },
                    onError = {
                        Log.e(TAG, "TTS error - audio focus released")
                        _isSpeaking.value = false
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
        
        Log.d(TAG, "[LOG] Speaking text: $text, continuousListeningEnabled=$continuousListeningEnabled")
        ttsManager.speak(text, "chat_message")
    }
}