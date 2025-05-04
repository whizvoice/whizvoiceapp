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

// Add necessary imports at the top of ChatViewModel.kt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map // Ensure map is imported
import kotlinx.coroutines.flow.stateIn
import com.example.whiz.data.local.MessageEntity // Ensure MessageEntity is imported
import com.example.whiz.data.local.MessageType // Ensure MessageType is imported


@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // Inject Context
    private val repository: WhizRepository,
    private val speechRecognitionService: SpeechRecognitionService,
    private val whizServerRepository: WhizServerRepository
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
        serverMessageCollectorJob?.cancel() // Cancel previous collector if any
        serverMessageCollectorJob = viewModelScope.launch {
            whizServerRepository.webSocketEvents
                .catch { e -> Log.e(TAG, "Error in WebSocket event flow", e) }
                .collect { event ->
                    when (event) {
                        is WebSocketEvent.Message -> handleServerMessage(event.text)
                        is WebSocketEvent.Error -> {
                            Log.e(TAG, "WebSocket Error", event.error)
                            _isConnectedToServer.value = false
                            _isResponding.value = false // Stop showing thinking indicator on error
                            // Optionally show error to user via snackbar/state
                        }
                        is WebSocketEvent.Closed -> {
                            Log.i(TAG, "WebSocket Closed")
                            _isConnectedToServer.value = false
                            _isResponding.value = false // Stop showing thinking indicator
                        }
                        is WebSocketEvent.Connected -> {
                            Log.i(TAG, "WebSocket Connected")
                            _isConnectedToServer.value = true
                            // You might want to send initial context or chat history here if needed
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
                // Set listener to track utterance completion
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart: $utteranceId")
                        _isSpeaking.value = true
                        // Pause speech recognition when TTS starts
                        pauseSpeechRecognitionDuringTTS()
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS onDone: $utteranceId")
                        _isSpeaking.value = false
                        // Resume speech recognition when TTS finishes
                        resumeSpeechRecognitionAfterTTS()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS onError: $utteranceId")
                        _isSpeaking.value = false
                        // Resume speech recognition even if TTS errors out
                        resumeSpeechRecognitionAfterTTS()
                    }

                    // Deprecated but might be called on older APIs
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS onError (deprecated): $utteranceId, code: $errorCode")
                        _isSpeaking.value = false
                        resumeSpeechRecognitionAfterTTS()
                    }
                })
            }
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
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
                        // Handle error (e.g., show snackbar)
                        Log.e(TAG, "Failed to send message via WebSocket")
                    }
                    // Response handling is done in observeServerMessages
                } else {
                    Log.w(TAG, "Cannot send message: Not connected to server.")
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

    private fun pauseSpeechRecognitionDuringTTS() {
        if (isListening.value) {
            wasListeningBeforeTTS = true // Remember that we were listening
            speechRecognitionService.stopListening()
            Log.d(TAG, "Paused speech recognition for TTS.")
        } else {
            wasListeningBeforeTTS = false
        }
    }

    private fun resumeSpeechRecognitionAfterTTS() {
        if (wasListeningBeforeTTS) {
            // Restart listening only if it was active before TTS started
            // Give a small delay to avoid immediate re-triggering if there's noise
            viewModelScope.launch {
                delay(500) // Adjust delay as needed
                // Check if still in voice mode and not speaking again immediately
                if (wasListeningBeforeTTS && !_isSpeaking.value) {
                    Log.d(TAG, "Resuming speech recognition after TTS.")
                    // Re-initiate listening via the toggle function logic if needed
                    // For simplicity, directly call startListening here
                    speechRecognitionService.startListening { finalText ->
                        sendUserInput(finalText)
                    }
                }
                wasListeningBeforeTTS = false // Reset the flag
            }
        }
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
}