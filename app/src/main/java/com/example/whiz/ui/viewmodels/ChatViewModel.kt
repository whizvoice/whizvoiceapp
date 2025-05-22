package com.example.whiz.ui.viewmodels

import android.content.Context
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

// Add necessary imports at the top of ChatViewModel.kt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map // Ensure map is imported
import kotlinx.coroutines.flow.stateIn
import com.example.whiz.data.local.MessageEntity // Ensure MessageEntity is imported
import com.example.whiz.data.local.MessageType // Ensure MessageType is imported
import kotlinx.coroutines.ExperimentalCoroutinesApi // Import for OptIn
import org.json.JSONObject // For basic JSON parsing
import org.json.JSONException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // Added OptIn annotation
@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // Inject Context
    private val repository: WhizRepository,
    private val speechRecognitionService: SpeechRecognitionService,
    private val whizServerRepository: WhizServerRepository,
    private val authRepository: AuthRepository // Add this
) : ViewModel(), TextToSpeech.OnInitListener { // Implement OnInitListener

    private val TAG = "ChatViewModel"

    // Config state
    val configUseRemoteAgent = true;

    // Chat state
    private val _chatId = MutableStateFlow<Long>(-1)
    val chatId: StateFlow<Long> = _chatId.asStateFlow()
    private val _chatTitle = MutableStateFlow<String>("New Chat")
    val chatTitle = _chatTitle.asStateFlow()

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

    private var continuousListeningEnabled = false

    init {
        // Check if the app already has microphone permission
        _micPermissionGranted.value = PermissionHandler.hasMicrophonePermission(context)
        
        speechRecognitionService.initialize()
        // Initialize TTS
        tts = TextToSpeech(context, this)
        Log.d(TAG, "TTS Initialization requested.")

        if (configUseRemoteAgent) {
            observeServerMessages()
        }
    }

    // Observe messages from the WebSocket
    private fun observeServerMessages() {
        serverMessageCollectorJob?.cancel()
        serverMessageCollectorJob = viewModelScope.launch {
            whizServerRepository.webSocketEvents.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        Log.d(TAG, "WebSocketEvent.Connected: Called.")
                        _isConnectedToServer.value = true
                        _connectionError.value = null
                        viewModelScope.launch {
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
                    }
                    is WebSocketEvent.Error -> {
                        _isConnectedToServer.value = false
                        _connectionError.value = "Connection error: ${event.error.message ?: "Unknown connection failure"}"
                        _showAuthErrorDialog.value = null
                        _navigateToLogin.value = false
                        if (_chatId.value > 0) { // Only add if a chat is active
                            repository.addAssistantMessage(
                                chatId = _chatId.value,
                                content = "Error: ${event.error.message ?: "Unknown connection error"}"
                                // Timestamp is handled by repository or MessageEntity default
                            )
                        }
                        _isResponding.value = false
                    }
                    is WebSocketEvent.AuthError -> {
                        Log.d(TAG, "WebSocketEvent.AuthError received: ${event.message}.")
                        _isConnectedToServer.value = false
                        viewModelScope.launch {
                            val refreshSuccessful = authRepository.refreshAccessToken()
                            if (refreshSuccessful) {
                                Log.i(TAG, "Token refresh successful after WebSocket AuthError. Attempting to reconnect WebSocket.")
                                isDisconnectingForAuthError = false
                                _navigateToLogin.value = false
                                whizServerRepository.connect()
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
                    }
                    is WebSocketEvent.Message -> {
                        Log.d(TAG, "[LOG] WebSocketEvent.Message received. continuousListeningEnabled=$continuousListeningEnabled, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}, isTTSInitialized=${_isTTSInitialized.value}")
                        var isErrorHandled = false
                        var messageContentForChat = event.text
                        var speakThisMessage = _isVoiceResponseEnabled.value

                        // Create a unique event identifier for logging, e.g., based on event.text and current time
                        val eventLogId = "[EventTextHash:${event.text.hashCode()}-Time:${System.currentTimeMillis()}]"
                        Log.d(TAG, "$eventLogId Processing WebSocketEvent.Message.")

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
                                val errorMessageFromServer = jsonObject.optString("message", "An error occurred.")
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
                        }

                        // Add the message to chat
                        Log.d(TAG, "$eventLogId PRE-CALL addAssistantMessage. Chat ID: ${_chatId.value}, Content: '$messageContentForChat'")
                        if (_chatId.value > 0) {
                            viewModelScope.launch {
                                val messageId = repository.addAssistantMessage(
                                    chatId = _chatId.value,
                                    content = messageContentForChat
                                )
                                Log.d(TAG, "$eventLogId POST-CALL addAssistantMessage. Message ID: $messageId, Content: '$messageContentForChat'")
                            }
                        } else {
                            Log.w(TAG, "$eventLogId SKIPPED addAssistantMessage because chatId is not > 0 (Value: ${_chatId.value})")
                        }

                        if (_isVoiceResponseEnabled.value && _isTTSInitialized.value && speakThisMessage && messageContentForChat.isNotBlank()) {
                            if (isListening.value) {
                                wasListeningBeforeTTS = true
                                speechRecognitionService.stopListening() // Stop STT before TTS speaks
                            }
                            val utteranceId = UUID.randomUUID().toString()
                            _isSpeaking.value = true // Indicate TTS is starting
                            tts?.speak(messageContentForChat, TextToSpeech.QUEUE_ADD, null, utteranceId)
                            // Note: _isSpeaking will be reset to false in UtteranceProgressListener callbacks
                        } else {
                            // If not speaking, ensure STT resumes if it was interrupted by a previous TTS cycle that has now completed.
                            if (wasListeningBeforeTTS && !_isSpeaking.value) {
                                Log.d(TAG, "[LOG] Not speaking, wasListeningBeforeTTS=true, restarting ASR.")
                                speechRecognitionService.startListening { finalTranscription ->
                                    if (finalTranscription.isNotBlank()) {
                                        sendUserInput(finalTranscription)
                                    }
                                }
                                wasListeningBeforeTTS = false
                            }
                            // Always restart continuous listening after assistant reply if enabled
                            if (continuousListeningEnabled) {
                                Log.d(TAG, "[LOG] Restarting continuous listening after assistant reply.")
                                startContinuousListening()
                            }
                        }
                        _isResponding.value = false
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
                _isTTSInitialized.value = true
            }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS onStart for $utteranceId")
                        _isSpeaking.value = true
                    if (speechRecognitionService.isListening.value) {
                        wasListeningBeforeTTS = true
                        speechRecognitionService.stopListening() 
                        Log.d(TAG, "TTS starting, stopping ASR. wasListeningBeforeTTS = true")
                    } else {
                        wasListeningBeforeTTS = false
                        Log.d(TAG, "TTS starting, ASR was not active. wasListeningBeforeTTS = false")
                    }
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "[LOG] TTS onDone for $utteranceId. continuousListeningEnabled=$continuousListeningEnabled, wasListeningBeforeTTS=$wasListeningBeforeTTS, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}")
                        _isSpeaking.value = false
                        if (wasListeningBeforeTTS && _isVoiceResponseEnabled.value) {
                            Log.d(TAG, "[LOG] TTS done, wasListeningBeforeTTS=true and voice response enabled, restarting ASR.")
                            speechRecognitionService.startListening { transcription ->
                                this@ChatViewModel.processAndSendTranscription(transcription)
                            }
                        } else if (continuousListeningEnabled) {
                            Log.d(TAG, "[LOG] TTS done, continuous listening enabled, restarting ASR.")
                            speechRecognitionService.startListening { transcription ->
                                this@ChatViewModel.processAndSendTranscription(transcription)
                            }
                        } else {
                            Log.d(TAG, "[LOG] TTS done, wasListeningBeforeTTS=$wasListeningBeforeTTS or voice response disabled, not restarting ASR.")
                        }
                        wasListeningBeforeTTS = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "[LOG] TTS onError for $utteranceId. continuousListeningEnabled=$continuousListeningEnabled, wasListeningBeforeTTS=$wasListeningBeforeTTS, isVoiceResponseEnabled=${_isVoiceResponseEnabled.value}")
                        _isSpeaking.value = false
                        if (wasListeningBeforeTTS && _isVoiceResponseEnabled.value) {
                            Log.d(TAG, "[LOG] TTS error, wasListeningBeforeTTS=true and voice response enabled, restarting ASR.")
                            speechRecognitionService.startListening { transcription ->
                                this@ChatViewModel.processAndSendTranscription(transcription)
                            }
                        } else if (continuousListeningEnabled) {
                            Log.d(TAG, "[LOG] TTS error, continuous listening enabled, restarting ASR.")
                            speechRecognitionService.startListening { transcription ->
                                this@ChatViewModel.processAndSendTranscription(transcription)
                            }
                        }
                        wasListeningBeforeTTS = false 
                    }
                })
        } else {
            Log.e(TAG, "TTS Initialization Failed! Status: $status")
            _isTTSInitialized.value = false
        }
    }

    // --- Public Functions ---

    fun loadChat(chatId: Long) {
        viewModelScope.launch {
            // Disconnect from server if switching chats or loading new
            if (configUseRemoteAgent) {
                whizServerRepository.disconnect()
                _isConnectedToServer.value = false
            }

            if (chatId <= 0) {
                // ... (handle new chat creation as before)
                _chatId.value = -1
                _chatTitle.value = "New Chat"
            } else {
                // ... (handle loading existing chat as before)
                Log.d(TAG, "Loading chat with ID: $chatId")
                val chat = repository.getChatById(chatId)
                if (chat != null) {
                    _chatId.value = chatId
                    _chatTitle.value = chat.title
                    Log.d(TAG, "Loaded chat: ${chat.title} (ID: $chatId)")
                } else {
                    _chatId.value = -1
                    _chatTitle.value = "New Chat"
                    Log.d(TAG, "Chat not found, creating new chat")
                }
            }
            // Reset states
            Log.d(TAG, "[LOG] loadChat: Clearing _inputText.value. Previous value: '${_inputText.value}'")
            _inputText.value = ""
            _isResponding.value = false
            speechRecognitionService.stopListening()
            tts?.stop()
            _isSpeaking.value = false

            // Refresh messages to ensure we have latest data
            if (_chatId.value > 0) {
                repository.refreshMessages()
            }

            // Connect to server if needed *after* chat ID is set
            if (configUseRemoteAgent && _chatId.value != 0L) { // Connect for new (-1) or existing chats
                delay(100) // Small delay to ensure state propagation
                whizServerRepository.connect()
            }
        }
    }

    fun updateInputText(text: String) {
        Log.d(TAG, "[LOG] updateInputText called. Setting _inputText.value to: '$text'")
        _inputText.value = text
    }

    fun toggleSpeechRecognition() {
        Log.d(TAG, "[LOG] toggleSpeechRecognition called. isSpeaking=${_isSpeaking.value}, micPermissionGranted=${_micPermissionGranted.value}, isListening=${isListening.value}")
        if (_isSpeaking.value) return // Don't allow mic toggle while speaking
        if (!_micPermissionGranted.value) {
            Log.w(TAG, "[LOG] Microphone permission not granted")
            _errorState.value = "Microphone permission required" 
            return
        }
        if (isListening.value) {
            Log.d(TAG, "[LOG] Stopping speech recognition and continuous listening")
            continuousListeningEnabled = false
            speechRecognitionService.continuousListeningEnabled = false
            speechRecognitionService.stopListening()
        } else {
            Log.d(TAG, "[LOG] Starting speech recognition and enabling continuous listening. Clearing _inputText.value. Previous value: '${_inputText.value}'")
            _inputText.value = ""
            continuousListeningEnabled = true
            speechRecognitionService.continuousListeningEnabled = true
            startContinuousListening()
        }
    }

    private fun startContinuousListening() {
        Log.d(TAG, "[LOG] startContinuousListening called. continuousListeningEnabled=$continuousListeningEnabled")
        speechRecognitionService.startListening { finalText ->
            Log.d(TAG, "[LOG] startContinuousListening: got transcription. continuousListeningEnabled=$continuousListeningEnabled, text='$finalText'")
            Log.d(TAG, "[LOG] startContinuousListening: Clearing _inputText.value before sending. Previous value: '${_inputText.value}'")
            _inputText.value = "" // Clear the input bar before sending
            sendUserInput(finalText)
            if (continuousListeningEnabled) {
                Log.d(TAG, "[LOG] Continuous listening: restarting after result")
                startContinuousListening()
            } else {
                Log.d(TAG, "[LOG] Continuous listening disabled, not restarting")
            }
        }
    }

    fun sendUserInput(text: String = _inputText.value) {
        val trimmedText = text.trim()
        if (trimmedText.isBlank() || _isResponding.value) return

        viewModelScope.launch {
            var currentChatId = _chatId.value

            // Create a new chat if needed (this part remains the same)
            if (currentChatId <= 0) {
                val chatTitle = repository.deriveChatTitle(trimmedText)
                val newChatId = repository.createChat(chatTitle)
                _chatId.value = newChatId
                _chatTitle.value = chatTitle
                currentChatId = newChatId

                // If this was a new chat and we use remote agent, connect now
                if(configUseRemoteAgent && !_isConnectedToServer.value) {
                    whizServerRepository.connect()
                }
            }

            // Add user message (remains the same)
            repository.addUserMessage(currentChatId, trimmedText)

            // Clear input (remains the same)
            Log.d(TAG, "[LOG] sendUserInput: Clearing _inputText.value after sending. Previous value: '${_inputText.value}'")
            _inputText.value = ""

            // --- Server Interaction ---
            if (configUseRemoteAgent) {
                Log.d(TAG, "sendUserInput: Using remote agent. Connected: ${_isConnectedToServer.value}")
                if (_isConnectedToServer.value) {
                    _isResponding.value = true // Show thinking indicator
                    Log.d(TAG, "sendUserInput: Sending message via WebSocket: '$trimmedText'")
                    val success = whizServerRepository.sendMessage(trimmedText)
                    if (!success) {
                        _isResponding.value = false // Stop indicator if send failed
                        _connectionError.value = "Failed to send message. Please try again."
                        Log.e(TAG, "Failed to send message via WebSocket")
                    } else {
                        Log.d(TAG, "sendUserInput: Message sent successfully via WebSocket")
                    }
                    // Response handling is done in observeServerMessages
                } else {
                    Log.w(TAG, "Cannot send message: Not connected to server. Attempting to connect...")
                    _connectionError.value = "Not connected to server. Connecting..."
                    // Try to connect
                    whizServerRepository.connect()
                    // Use local fallback for now
                    generateAssistantResponse(currentChatId) 
                }
            } else {
                // --- Local Fallback ---
                Log.d(TAG, "sendUserInput: Using local fallback")
                generateAssistantResponse(currentChatId)
            }

            // Schedule persistence check (remains the same)
            schedulePersistenceCheck(currentChatId)
        }
    }

    fun toggleVoiceResponse() {
        _isVoiceResponseEnabled.update { !it }
        if (!_isVoiceResponseEnabled.value) {
            tts?.stop() // Stop speaking if toggled off
            _isSpeaking.value = false
        }
        Log.d(TAG, "Voice Response Enabled: ${_isVoiceResponseEnabled.value}")
    }

    // --- Internal Helper Functions ---

    private fun processAndSendTranscription(transcription: String) {
        Log.d(TAG, "[LOG] processAndSendTranscription called. Setting _inputText.value to transcription: '$transcription'")
        _inputText.value = transcription
        sendInputText() 
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
        _isResponding.value = false

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

    private fun clearInputText() {
        Log.d(TAG, "[LOG] clearInputText called. Clearing _inputText.value. Previous value: '${_inputText.value}'")
        _inputText.value = ""
    }

    // --- ViewModel Lifecycle ---
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Releasing resources.")
        speechRecognitionService.release()
        tts?.stop()
        tts?.shutdown()
        tts = null
        persistenceJob?.cancel()
        // Disconnect WebSocket
        if (configUseRemoteAgent) {
            whizServerRepository.disconnect()
        }
        serverMessageCollectorJob?.cancel() // Stop collecting events
        Log.d(TAG, "ChatViewModel cleared, TTS shutdown, SpeechRecognitionService destroyed, WebSocket disconnected.")
    }

    // Moved sendInputText here and made explicitly public
    public fun sendInputText() {
        val textToSend = _inputText.value.trim()
        if (textToSend.isBlank()) return

        _isResponding.value = true

        viewModelScope.launch {
            if (_chatId.value <= 0) {
                val newChatId = repository.createChat(repository.deriveChatTitle(textToSend))
                _chatId.value = newChatId
                if (newChatId <=0) {
                    Log.e(TAG, "Failed to create new chat.")
                    _isResponding.value = false
                    _errorState.value = "Failed to create chat. Please try again."
                    return@launch
                }
            }
            repository.addUserMessage(_chatId.value, textToSend)

            if (configUseRemoteAgent) {
                val success = whizServerRepository.sendMessage(textToSend)
                if (!success) {
                    _isResponding.value = false
                    _errorState.value = "Failed to send message. Check connection."
                     repository.addAssistantMessage(
                        _chatId.value, 
                        "System: Failed to send message. Please check your connection and try again."
                    )
                }
            } else {
                _isResponding.value = false 
            }
        }
        Log.d(TAG, "[LOG] sendInputText: Clearing _inputText.value after sending. Previous value: '${_inputText.value}'")
        clearInputText()
    }

    // Called when permission is granted from UI
    fun onMicrophonePermissionGranted() {
        _micPermissionGranted.value = true
        _errorState.value = null
        
        // When permission is granted, make sure SpeechRecognitionService is properly initialized
        // But don't start listening automatically
        try {
            Log.d(TAG, "Microphone permission granted, initializing speech service")
            speechRecognitionService.initialize()
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
}