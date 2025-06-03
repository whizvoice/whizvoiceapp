package com.example.whiz.ui.viewmodels

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.* // ktlint-disable no-wildcard-imports
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import java.util.Locale
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
) : ViewModel(), TextToSpeech.OnInitListener { // Implement OnInitListener

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
    private val locallyStoredInterruptMessages = mutableSetOf<String>()

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
                    delay(50)
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
    private var tts: TextToSpeech? = null
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
            tts?.stop()
            _isSpeaking.value = false
            
            // Enable continuous listening and start immediately
            // This allows the user to speak and interrupt TTS
            continuousListeningEnabled = true
            speechRecognitionService.continuousListeningEnabled = true
            
            viewModelScope.launch {
                // Small delay to ensure TTS stop is processed
                delay(100)
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
        // Initialize TTS
        tts = TextToSpeech(context, this)
        Log.d(TAG, "TTS Initialization requested.")

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

        // Enhanced server message collection with interrupt handling
        serverMessageCollectorJob = viewModelScope.launch {
            whizServerRepository.webSocketEvents.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocketEvent.Connected: Called.")
                        viewModelScope.launch {
                            // Increased delay to ensure WebSocket is fully ready during server instability
                            delay(300)
                            _isConnectedToServer.value = true
                            _connectionError.value = null
                            delay(200) 
                            if (_isConnectedToServer.value) { 
                                Log.d(TAG, "WebSocketEvent.Connected: DELAYED - Resetting isDisconnectingForAuthError to false.")
                                isDisconnectingForAuthError = false
                            }
                        }
                    }
                    is WebSocketEvent.Reconnecting -> {
                        Log.d(TAG, "WebSocketEvent.Reconnecting: Called.")
                        _isConnectedToServer.value = false
                        _connectionError.value = "Connection lost. Attempting to reconnect..."
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
                            if (_connectionError.value == null || _connectionError.value?.contains("reconnect") == false) {
                                _connectionError.value = "Connection closed."
                            }
                            Log.d(TAG, "WebSocketEvent.Closed: Repository should be handling retries if applicable. Current connectionError: ${_connectionError.value}")
                        }
                        currentActiveRequestId = null // Clear active request on disconnect
                    }
                    is WebSocketEvent.Error -> {
                        _isConnectedToServer.value = false
                        _connectionError.value = "Connection error: ${event.error.message ?: "Unknown connection failure"}"
                        _showAuthErrorDialog.value = null
                        _navigateToLogin.value = false
                        pendingRequests.clear() // 🔧 Clear all pending requests on connection error
                        if (_chatId.value > 0) { // Only add if a chat is active
                            repository.addAssistantMessage(
                                chatId = _chatId.value,
                                content = "Error: ${event.error.message ?: "Connection error occurred"}"
                                // Timestamp is handled by repository or MessageEntity default
                            )
                        }
                        _isResponding.value = false
                        currentActiveRequestId = null // Clear active request on error
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
                                        Log.w(TAG, "$eventLogId Request ID ${event.requestId} not found in pending requests. This response might be for a different chat or session. Available: ${pendingRequests.keys}")
                                        // Don't process this message as it's likely for a different chat/session
                                        Log.w(TAG, "$eventLogId Skipping message processing - orphaned response")
                                        updateRespondingStateForCurrentChat()
                                        return@collect // Skip processing this message entirely
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
                                            delay(100) // Brief delay for server processing
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
                                                delay(200) // Brief delay for server processing
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
                                    tts?.speak(messageContentForChat, TextToSpeech.QUEUE_ADD, null, utteranceId)
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

    // --- TextToSpeech.OnInitListener Implementation ---
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US) // Set desired language
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language is not supported or missing data.")
                _isTTSInitialized.value = false
            } else {
                Log.d(TAG, "TTS Initialized successfully.")
                
                // Apply voice settings after ensuring they're loaded
                viewModelScope.launch {
                    try {
                        // Ensure voice settings are loaded from server before applying them
                        userPreferences.loadVoiceSettings()
                        val voiceSettings = userPreferences.voiceSettings.value
                        Log.d(TAG, "Applying voice settings after TTS init: $voiceSettings")
                        applyVoiceSettings(voiceSettings)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading and applying voice settings", e)
                        // Continue with default settings if there's an error
                    }
                }
                
                _isTTSInitialized.value = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart for $utteranceId")
                        _isSpeaking.value = true
                        
                        val headphonesConnected = areHeadphonesConnected()
                        Log.d(TAG, "TTS starting. Headphones connected: $headphonesConnected")
                        
                        if (headphonesConnected) {
                            // With headphones: Keep continuous listening on (no feedback risk)
                            Log.d(TAG, "TTS with headphones - keeping continuous listening active")
                            wasListeningBeforeTTS = false // Don't need to track since we're not stopping
                        } else {
                            // Without headphones: Turn OFF continuous listening completely (cleaner UX)
                            if (continuousListeningEnabled) {
                                Log.d(TAG, "TTS with speakers - disabling continuous listening for cleaner UX")
                                wasListeningBeforeTTS = true // Remember it was enabled
                                continuousListeningEnabled = false
                                speechRecognitionService.continuousListeningEnabled = false
                                speechRecognitionService.stopListening()
                            } else {
                                wasListeningBeforeTTS = false
                                Log.d(TAG, "TTS with speakers - continuous listening was already off")
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "[LOG] TTS onDone for $utteranceId. continuousListeningEnabled=$continuousListeningEnabled, wasListeningBeforeTTS=$wasListeningBeforeTTS, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}")
                        _isSpeaking.value = false
                        
                        // Add minimal delay for audio resource cleanup, then restart immediately if not computing
                        viewModelScope.launch {
                            try {
                                // Minimal delay for TTS to release audio resources
                                delay(150) // Reduced from 300ms to 150ms for faster response
                                
                                // Restore continuous listening if it was disabled during TTS
                                if (wasListeningBeforeTTS) {
                                    Log.d(TAG, "[LOG] TTS done, restoring continuous listening (was disabled during TTS)")
                                    continuousListeningEnabled = true
                                    speechRecognitionService.continuousListeningEnabled = true
                                    wasListeningBeforeTTS = false
                                    
                                    // Start listening if not computing
                                    if (!_isResponding.value && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not computing - restarting continuous listening immediately")
                                        startContinuousListening()
                                    } else {
                                        Log.d(TAG, "[LOG] Still computing (responding=${_isResponding.value}, speaking=${_isSpeaking.value}) - will restart when done")
                                    }
                                } else if (continuousListeningEnabled) {
                                    // Continuous listening was already enabled (e.g., with headphones)
                                    Log.d(TAG, "[LOG] TTS done, continuous listening was already enabled, checking if we can restart immediately.")
                                    
                                    // Check immediately if we can restart (not computing)
                                    if (!_isResponding.value && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not computing - restarting continuous listening immediately")
                                        startContinuousListening()
                                    } else {
                                        Log.d(TAG, "[LOG] Still computing (responding=${_isResponding.value}, speaking=${_isSpeaking.value}) - will restart when done")
                                    }
                                } else {
                                    Log.d(TAG, "[LOG] TTS done, continuous listening remains disabled")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[LOG] Error in TTS onDone callback", e)
                                wasListeningBeforeTTS = false
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "[LOG] TTS onError for $utteranceId. continuousListeningEnabled=$continuousListeningEnabled, wasListeningBeforeTTS=$wasListeningBeforeTTS, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}")
                        _isSpeaking.value = false
                        
                        // Add minimal delay for audio resource cleanup, then restart immediately if not computing
                        viewModelScope.launch {
                            try {
                                // Minimal delay for TTS to release audio resources
                                delay(150) // Reduced from 300ms to 150ms for faster response
                                
                                // Restore continuous listening if it was disabled during TTS
                                if (wasListeningBeforeTTS) {
                                    Log.d(TAG, "[LOG] TTS error, restoring continuous listening (was disabled during TTS)")
                                    continuousListeningEnabled = true
                                    speechRecognitionService.continuousListeningEnabled = true
                                    wasListeningBeforeTTS = false
                                    
                                    // Start listening if not computing
                                    if (!_isResponding.value && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not computing - restarting continuous listening immediately")
                                        startContinuousListening()
                                    } else {
                                        Log.d(TAG, "[LOG] Still computing (responding=${_isResponding.value}, speaking=${_isSpeaking.value}) - will restart when done")
                                    }
                                } else if (continuousListeningEnabled) {
                                    // Continuous listening was already enabled (e.g., with headphones)
                                    Log.d(TAG, "[LOG] TTS error, continuous listening was already enabled, checking if we can restart immediately.")
                                    
                                    // Check immediately if we can restart (not computing)
                                    if (!_isResponding.value && !_isSpeaking.value) {
                                        Log.d(TAG, "[LOG] Not computing - restarting continuous listening immediately")
                                        startContinuousListening()
                                    } else {
                                        Log.d(TAG, "[LOG] Still computing (responding=${_isResponding.value}, speaking=${_isSpeaking.value}) - will restart when done")
                                    }
                                } else {
                                    Log.d(TAG, "[LOG] TTS error, continuous listening remains disabled")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[LOG] Error in TTS onError callback", e)
                                wasListeningBeforeTTS = false
                            }
                        }
                    }
                })
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed! Status: $status")
            _isTTSInitialized.value = false
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
                    tts?.stop()
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
                        delay(500) // Increased delay to ensure speech service is fully stopped
                        
                        // Always enable continuous listening and start it, regardless of current state
                        continuousListeningEnabled = true
                        speechRecognitionService.continuousListeningEnabled = true
                        
                        // Force start continuous listening, but add additional delay to ensure clean state
                        delay(200) // Additional delay to ensure isListening state is updated
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
        
        // Case 2: Continuous listening is enabled but mic not actively listening (e.g., during responses)
        // Allow user to disable continuous listening mode
        if (continuousListeningEnabled) {
            Log.d(TAG, "[LOG] Disabling continuous listening mode (was enabled but not actively listening)")
            continuousListeningEnabled = false
            speechRecognitionService.continuousListeningEnabled = false
            return
        }
        
        // Case 3: Want to enable continuous listening - prevent during speaking/responding
        if (_isSpeaking.value || _isResponding.value) {
            Log.d(TAG, "[LOG] Cannot start listening while assistant is speaking or responding")
            _errorState.value = "Cannot start listening while assistant is busy"
            return
        }
        
        // Case 4: Enable continuous listening
        Log.d(TAG, "[LOG] Starting speech recognition and enabling continuous listening. Clearing _inputText.value. Previous value: '${_inputText.value}'")
        _inputText.value = ""
        continuousListeningEnabled = true
        speechRecognitionService.continuousListeningEnabled = true
        startContinuousListening()
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
            
            // 🔧 Set transcribed text to input field and send immediately
            Log.d(TAG, "[LOG] startContinuousListening: Setting transcribed text to input field for UX: '$finalText'")
            _inputText.value = finalText
            sendUserInput(finalText) // Send the transcription
            
            // Always restart listening if continuous listening is enabled, regardless of responding state
            if (continuousListeningEnabled) {
                Log.d(TAG, "[LOG] Continuous listening: restarting after result (isResponding=${_isResponding.value})")
                viewModelScope.launch {
                    // Small delay to ensure the previous listening session is fully stopped
                    delay(100)
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
            return
        }
        
        if (isInterrupt && canInterrupt()) {
            sendInterruptMessage(text)
            return
        }
        
        // Only block sending if responding AND this is not an interrupt scenario
        if (_isResponding.value && !shouldInterrupt) {
            Log.d(TAG, "sendUserInput: Blocked - bot is responding and this is not an interrupt")
            return
        }

        // 🔧 Clear input text immediately when sending message
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

            // Only save user message to local DB if NOT using remote agent
            // Remote agent (WebSocket server) handles message persistence
            if (!configUseRemoteAgent && currentChatId > 0) {
                repository.addUserMessage(currentChatId, trimmedText)
            } else {
                Log.d(TAG, "sendUserInput: Skipping local user message save - remote agent will handle persistence")
            }

            // --- Server Interaction ---
            if (configUseRemoteAgent) {
                Log.d(TAG, "sendUserInput: Using remote agent. Connected: ${_isConnectedToServer.value}")
                if (_isConnectedToServer.value) {
                    _isResponding.value = true // Show thinking indicator
                    // 🔧 Generate unique request ID and track which chat this request belongs to
                    val requestId = java.util.UUID.randomUUID().toString()
                    currentActiveRequestId = requestId // Track the active request
                    pendingRequests[requestId] = currentChatId
                    Log.d(TAG, "sendUserInput: Sending message via WebSocket: '$trimmedText' for chat: $currentChatId with requestId: $requestId")
                    val success = whizServerRepository.sendMessage(trimmedText, requestId)
                    if (!success) {
                        pendingRequests.remove(requestId) // Clear tracking on failure
                        currentActiveRequestId = null
                        updateRespondingStateForCurrentChat() // Update based on remaining requests
                        // Don't show error to user for brief disconnections - handle silently
                        // _connectionError.value = "Failed to send message. Please try again."
                        Log.e(TAG, "Failed to send message via WebSocket")
                    } else {
                        Log.d(TAG, "sendUserInput: Message sent successfully via WebSocket for chat: $currentChatId with requestId: $requestId")
                        // For new chats, we need to refresh conversations list after server creates it
                        if (currentChatId <= 0) {
                            try {
                                viewModelScope.launch {
                                    delay(500) // Delay to ensure server creates conversation
                                    repository.refreshConversations()
                                    Log.d(TAG, "sendUserInput: Triggered conversations refresh for new chat")
                                    
                                    // Try to find the newly created conversation and switch to it
                                    val conversations = repository.conversations.value
                                    if (conversations.isNotEmpty()) {
                                        val newestConversation = conversations.first() // Most recent
                                        _chatId.value = newestConversation.id
                                        _chatTitle.value = newestConversation.title
                                        Log.d(TAG, "sendUserInput: Switched to new conversation ${newestConversation.id}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "sendUserInput: Error refreshing conversations for new chat", e)
                            }
                        }
                        
                        // Trigger UI refresh to show user message saved by WebSocket server
                        try {
                            viewModelScope.launch {
                                delay(500) // Single delay to ensure server processes the message
                                repository.refreshMessages()
                                Log.d(TAG, "sendUserInput: Triggered single messages refresh after sending user message")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "sendUserInput: Error triggering messages refresh", e)
                        }
                    }
                    // Response handling is done in observeServerMessages
                } else {
                    Log.w(TAG, "Cannot send message: Not connected to server. Attempting to connect...")
                    // Don't show error to user for brief disconnections - handle silently
                    // _connectionError.value = "Not connected to server. Connecting..."
                    
                    // Clear input text even on connection failure to prevent stuck state
                    Log.d(TAG, "[LOG] 🔥 sendUserInput: Clearing input text due to connection failure (was: '${_inputText.value}')")
                    _inputText.value = ""
                    
                    // Try to connect with current conversation ID (automatic reconnection)
                    val conversationId = if (_chatId.value > 0) _chatId.value else null
                    whizServerRepository.connect(conversationId)
                    
                    // For voice-first UX: Don't use local fallback, just let user speak again after reconnection
                    Log.d(TAG, "sendUserInput: WebSocket reconnecting in background. User can speak again when ready.")
                }
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
            tts?.stop() // Stop speaking if toggled off
            _isSpeaking.value = false
            
            // If continuous listening is enabled, restart it immediately when TTS is stopped
            if (continuousListeningEnabled && !_isResponding.value) {
                Log.d(TAG, "[LOG] Voice response disabled, restarting continuous listening immediately")
                viewModelScope.launch {
                    delay(50) // Very short delay to ensure TTS stop is processed
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
        delay(1000)

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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
        tts?.stop()
        tts?.shutdown()
        tts = null
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
            
            Log.d(TAG, "🔥 sendInputText: Adding user message to chat ${_chatId.value}: '$textToSend'")
            // Only save user message to local DB if NOT using remote agent
            // Remote agent (WebSocket server) handles message persistence
            if (!configUseRemoteAgent && _chatId.value > 0) {
                repository.addUserMessage(_chatId.value, textToSend)
            } else {
                Log.d(TAG, "sendInputText: Skipping local user message save - remote agent will handle persistence")
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
                    pendingRequests.remove(requestId) // Clear tracking on failure
                    currentActiveRequestId = null
                    updateRespondingStateForCurrentChat() // Update based on remaining requests
                    // Don't show error to user for brief disconnections - handle silently
                    // _connectionError.value = "Failed to send message. Please try again."
                    Log.e(TAG, "Failed to send message via WebSocket")
                } else {
                    // For new chats, refresh conversations after server creates it
                    if (_chatId.value <= 0) {
                        try {
                            viewModelScope.launch {
                                delay(500) // Delay to ensure server creates conversation
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
                    delay(100) // Small delay to ensure service is initialized
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

    // Called when app comes back to foreground - restart continuous listening if it was enabled
    fun onAppForegrounded() {
        Log.d(TAG, "[LOG] onAppForegrounded called. continuousListeningEnabled=$continuousListeningEnabled, micPermissionGranted=${_micPermissionGranted.value}, chatId=${_chatId.value}")
        
        // Only restart if we have permission, are in a chat, and continuous listening was enabled
        if (_micPermissionGranted.value && _chatId.value > 0 && continuousListeningEnabled) {
            try {
                // Re-enable continuous listening in the service
                speechRecognitionService.continuousListeningEnabled = true
                
                // Start listening if not already listening and not busy
                if (!isListening.value && !_isSpeaking.value && !_isResponding.value) {
                    Log.d(TAG, "[LOG] Restarting continuous listening after app foregrounded")
                    viewModelScope.launch {
                        delay(200) // Small delay to ensure app is fully resumed
                        if (continuousListeningEnabled && !_isSpeaking.value && !_isResponding.value) {
                            startContinuousListening()
                        }
                    }
                } else {
                    Log.d(TAG, "[LOG] Not restarting continuous listening - isListening: ${isListening.value}, isSpeaking: ${_isSpeaking.value}, isResponding: ${_isResponding.value}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting continuous listening after app foregrounded", e)
            }
        } else {
            Log.d(TAG, "[LOG] Not restarting continuous listening - permission: ${_micPermissionGranted.value}, chatId: ${_chatId.value}, continuousEnabled: $continuousListeningEnabled")
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
                tts?.setSpeechRate(voiceSettings.speechRate)
                tts?.setPitch(voiceSettings.pitch)
            } else {
                // Check if we're switching from custom to system defaults
                if (previousSettings != null && !previousSettings.useSystemDefaults) {
                    Log.d(TAG, "Switching from custom to system TTS settings - reinitializing TTS engine")
                    // We need to reinitialize the TTS engine to clear custom settings
                    viewModelScope.launch {
                        try {
                            tts?.stop()
                            tts?.shutdown()
                            tts = TextToSpeech(context, this@ChatViewModel)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error reinitializing TTS for system defaults", e)
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

        if (!_isConnectedToServer.value) {
            Log.w(TAG, "sendInterruptMessage: Cannot send interrupt - not connected to server")
            // Don't show error to user for brief disconnections - handle silently
            // _connectionError.value = "Not connected to server"
            return
        }

        Log.d(TAG, "sendInterruptMessage: Sending interrupt message: '$trimmedText'")
        
        // Generate new request ID for the interrupt
        val requestId = java.util.UUID.randomUUID().toString()
        currentActiveRequestId = requestId
        
        // Send the interrupt message (backend will automatically cancel active requests)
        val success = whizServerRepository.sendInterruptMessage(trimmedText, requestId)
        
        if (success) {
            // Track this new request
            pendingRequests[requestId] = _chatId.value
            Log.d(TAG, "sendInterruptMessage: Interrupt sent successfully with requestId: $requestId")
            
            // Clear input text immediately after sending interrupt (like normal sendUserInput)
            Log.d(TAG, "[LOG] 🔥 sendInterruptMessage: Clearing input text immediately after sending (was: '${_inputText.value}')")
            _inputText.value = ""
            
            // Immediately save interrupt message to local chat UI for instant feedback
            // Track it to prevent duplication when server response arrives
            val messageKey = "${_chatId.value}_${trimmedText}_interrupt"
            locallyStoredInterruptMessages.add(messageKey)
            
            if (_chatId.value > 0) {
                viewModelScope.launch {
                    try {
                        repository.addUserMessage(_chatId.value, trimmedText)
                        Log.d(TAG, "sendInterruptMessage: Added interrupt message to local chat UI: '$trimmedText'")
                    } catch (e: Exception) {
                        Log.e(TAG, "sendInterruptMessage: Error saving interrupt message to local UI", e)
                        // Remove from tracking set if save failed
                        locallyStoredInterruptMessages.remove(messageKey)
                    }
                }
            }
            
            // Update UI state
            _isResponding.value = true
            
        } else {
            currentActiveRequestId = null
            // Don't show error to user for brief disconnections - handle silently
            // _connectionError.value = "Failed to send interrupt message"
            Log.e(TAG, "sendInterruptMessage: Failed to send interrupt via WebSocket")
        }
    }

    /**
     * Check if there are any active requests that can be interrupted
     */
    fun canInterrupt(): Boolean {
        return configUseRemoteAgent && 
               _isConnectedToServer.value && 
               _isResponding.value && 
               pendingRequests.isNotEmpty()
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
}