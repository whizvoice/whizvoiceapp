package com.example.wiz.ui.viewmodels

import android.content.Context // Import Context
import android.speech.tts.TextToSpeech // Import TextToSpeech
import android.speech.tts.UtteranceProgressListener // Import UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wiz.data.local.MessageEntity
import com.example.wiz.data.repository.WizRepository
import com.example.wiz.services.SpeechRecognitionService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext // Import ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update // Import update
import kotlinx.coroutines.launch
import java.util.Locale // Import Locale
import java.util.UUID // Import UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context, // Inject Context
    private val repository: WizRepository,
    private val speechRecognitionService: SpeechRecognitionService
) : ViewModel(), TextToSpeech.OnInitListener { // Implement OnInitListener

    private val TAG = "ChatViewModel"

    // Chat state
    private val _chatId = MutableStateFlow<Long>(-1)
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

    init {
        speechRecognitionService.initialize()
        // Initialize TTS
        tts = TextToSpeech(context, this)
        Log.d(TAG, "TTS Initialization requested.")
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
            if (chatId <= 0) {
                _chatId.value = -1
                _chatTitle.value = "New Chat"
            } else {
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
            // Reset states for the new/loaded chat
            _inputText.value = ""
            _isResponding.value = false
            speechRecognitionService.stopListening() // Stop listening when changing chats
            tts?.stop() // Stop any ongoing TTS
            _isSpeaking.value = false
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun toggleSpeechRecognition() {
        if (_isSpeaking.value) return // Don't allow mic toggle while speaking

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
            // Stop listening before sending
            /*if (isListening.value) {
                speechRecognitionService.stopListening()
            }*/

            var currentChatId = _chatId.value
            // Create a new chat if needed
            if (currentChatId <= 0) {
                val chatTitle = repository.deriveChatTitle(trimmedText)
                val newChatId = repository.createChat(chatTitle)
                _chatId.value = newChatId
                _chatTitle.value = chatTitle
                currentChatId = newChatId // Use the new ID for subsequent operations
            }

            // Add user message
            repository.addUserMessage(currentChatId, trimmedText)

            // Clear input
            _inputText.value = ""

            // Generate assistant response
            generateAssistantResponse(currentChatId)

            // Schedule persistence check
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
        if (chatId <= 0) return // Safety check

        _isResponding.value = true
        delay(1000) // Simulate network delay

        val responses = listOf(
            "I understand. Can you tell me more about that?",
            "That's interesting. How can I help you with this?",
            "Let me think about that for a moment.",
            "I see what you mean. What would you like to do next?",
            "Thanks for sharing that with me.",
            "I'm processing that information. Is there anything specific you're looking for?",
            "I appreciate your patience while I think about this.",
            "That's a good point. Let me consider how best to assist you."
        )
        val responseText = responses.random()

        // Add assistant message to DB first
        repository.addAssistantMessage(chatId, responseText)

        _isResponding.value = false // Mark responding false after adding to DB

        // Speak the response if enabled and TTS is ready
        if (_isVoiceResponseEnabled.value && _isTTSInitialized.value) {
            speakAgentResponse(responseText)
        } else if (_isVoiceResponseEnabled.value && !_isTTSInitialized.value) {
            Log.w(TAG, "Voice response enabled but TTS not initialized. Skipping speech.")
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
                delay(200) // Adjust delay as needed
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
        // Shutdown TTS
        tts?.stop()
        tts?.shutdown()
        tts = null
        persistenceJob?.cancel()
    }
}