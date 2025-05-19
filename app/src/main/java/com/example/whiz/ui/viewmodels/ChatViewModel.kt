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
        started = SharingStarted.WhileSubscribed(5000),
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
    val navigateToLogin = _navigateToLogin.asStateFlow()

    private var isDisconnectingForAuthError = false

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
                        Log.d(TAG, "Message received from server: ${event.text}")
                        var isErrorHandled = false
                        var messageContentForChat = event.text 
                        var isAsanaAuthError = false
                        var speakThisMessage = _isVoiceResponseEnabled.value 

                        // Attempt to parse as JSON first, as both Asana and Claude auth errors are JSON
                        try {
                            val jsonObject = JSONObject(event.text)
                            if (jsonObject.has("type") && jsonObject.getString("type") == "error" && jsonObject.has("code")) {
                                val errorCode = jsonObject.getString("code")
                                val errorMessage = jsonObject.optString("message", "An authentication error occurred.") // Use optString for safety

                                when (errorCode) {
                                    "ASANA_AUTH_ERROR", "AsanaAuthErrorHandled" -> { // Match code from server if it's specific
                                        // Or rely on the field checks if asana_tools sends the full JSON directly
                                        if (jsonObject.has("error") && jsonObject.getString("error").contains("Asana authentication failed")) {
                                            Log.w(TAG, "Handling Asana authentication error JSON from server.")
                                            _showAsanaSetupDialog.value = true // Keep specific dialog for Asana for now
                                            isAsanaAuthError = true // Mark as Asana error
                                            messageContentForChat = errorMessage // Use message from JSON
                                            speakThisMessage = false
                                            isErrorHandled = true
                                        }
                                    }
                                    "CLAUDE_AUTHENTICATION_ERROR" -> {
                                        Log.w(TAG, "Handling Claude authentication error JSON from server.")
                                        _showAuthErrorDialog.value = errorMessage // Use the generic auth error dialog
                                        messageContentForChat = errorMessage // Use message from JSON for chat history
                                        speakThisMessage = false
                                        isErrorHandled = true
                                    }
                                    "CLAUDE_API_KEY_MISSING" -> { // This might also come via JSON now
                                        Log.w(TAG, "Handling Claude API key missing error JSON from server.")
                                        _showAuthErrorDialog.value = errorMessage
                                        messageContentForChat = errorMessage
                                        speakThisMessage = false
                                        isErrorHandled = true
                                    }
                                    // Potentially other specific error codes from the server
                                    else -> {
                                        // Handle other structured errors if necessary
                                        if (jsonObject.has("error")) { // Generic tool error or other server error
                                            val errorDetail = jsonObject.optString("detail", "")
                                            messageContentForChat = "Server Error: $errorMessage ${if (errorDetail.isNotEmpty()) "- $errorDetail" else ""}"
                                            Log.e(TAG, "Structured server error: $messageContentForChat")
                                            speakThisMessage = false
                                            isErrorHandled = true // Assume structured errors are handled
                                        }
                                    }
                                }
                            }
                        } catch (e: JSONException) {
                            // Message is not a simple JSON error object, could be regular text or old Asana plain text error
                            Log.d(TAG, "Received message is not a JSON object or not a recognized error structure: ${event.text}")
                            // Fallback to legacy string checks if needed, though ideally all errors are JSON
                            val asanaAuthErrorSignature = "\"error\":\"Asana authentication failed. Please check your Asana Access Token in settings.\""
                            val asanaStatusCodeSignature = "\"status_code\":401"
                            if (event.text.contains(asanaAuthErrorSignature) && event.text.contains(asanaStatusCodeSignature)) {
                                Log.w(TAG, "Detected legacy Asana authentication error signature in message.")
                                _showAsanaSetupDialog.value = true
                                isAsanaAuthError = true
                                messageContentForChat = "Asana authentication failed. Please check your token in Settings."
                                speakThisMessage = false
                                isErrorHandled = true
                            }
                        }

                        // Add message to chat history, unless it was an error that shouldn't be shown or was handled by a dialog
                        if (_chatId.value > 0) {
                            // Decide if the raw server message (even if an error) should be added or a custom one.
                            // For now, messageContentForChat holds the potentially modified message.
                            repository.addAssistantMessage(_chatId.value, messageContentForChat)
                        }
                        
                        // If error was handled by a dialog, TTS might have been disabled already.
                        // If not an error, or error that still allows TTS:
                        if (speakThisMessage && _isTTSInitialized.value && _chatId.value > 0) { 
                            val utteranceId = UUID.randomUUID().toString()
                            tts?.speak(messageContentForChat, TextToSpeech.QUEUE_ADD, null, utteranceId)
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
                    Log.d(TAG, "TTS onDone for $utteranceId")
                        _isSpeaking.value = false
                    if (wasListeningBeforeTTS && _isVoiceResponseEnabled.value) {
                        Log.d(TAG, "TTS done, wasListeningBeforeTTS=true and voice response enabled, restarting ASR.")
                        speechRecognitionService.startListening { transcription ->
                            this@ChatViewModel.processAndSendTranscription(transcription)
                        }
                    } else {
                         Log.d(TAG, "TTS done, wasListeningBeforeTTS=$wasListeningBeforeTTS or voice response disabled, not restarting ASR.")
                    }
                    wasListeningBeforeTTS = false
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS onError for $utteranceId")
                        _isSpeaking.value = false
                    if (wasListeningBeforeTTS && _isVoiceResponseEnabled.value) {
                        Log.d(TAG, "TTS error, wasListeningBeforeTTS=true and voice response enabled, restarting ASR.")
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
            _inputText.value = ""
            _isResponding.value = false
            speechRecognitionService.stopListening()
            tts?.stop()
            _isSpeaking.value = false


            // Connect to server if needed *after* chat ID is set
            if (configUseRemoteAgent && _chatId.value != 0L) { // Connect for new (-1) or existing chats
                delay(100) // Small delay to ensure state propagation
                whizServerRepository.connect()
            }
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun toggleSpeechRecognition() {
        if (_isSpeaking.value) return // Don't allow mic toggle while speaking

        // Check for microphone permission
        if (!_micPermissionGranted.value) {
            // Permission not granted yet, emit error
            Log.w(TAG, "Microphone permission not granted")
            _errorState.value = "Microphone permission required" 
            return
        }

        if (isListening.value) {
            speechRecognitionService.stopListening()
        } else {
            // Clear manual input when starting voice input
            _inputText.value = ""
            speechRecognitionService.startListening { finalText ->
                sendUserInput(finalText)
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
            _inputText.value = ""

            // --- Server Interaction ---
            if (configUseRemoteAgent) {
                if (_isConnectedToServer.value) {
                    _isResponding.value = true // Show thinking indicator
                    val success = whizServerRepository.sendMessage(trimmedText)
                    if (!success) {
                        _isResponding.value = false // Stop indicator if send failed
                        _connectionError.value = "Failed to send message. Please try again."
                        Log.e(TAG, "Failed to send message via WebSocket")
                    }
                    // Response handling is done in observeServerMessages
                } else {
                    Log.w(TAG, "Cannot send message: Not connected to server.")
                    _connectionError.value = "Not connected to server. Please try again."
                    // Handle error (e.g., show snackbar "Not connected")
                    // Optionally fallback to local response or queue message
                    generateAssistantResponse(currentChatId) // Fallback to local?
                }
            } else {
                // --- Local Fallback ---
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