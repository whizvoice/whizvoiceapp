package com.example.wiz.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wiz.data.local.ChatEntity
import com.example.wiz.data.local.MessageEntity
import com.example.wiz.data.local.MessageType
import com.example.wiz.data.repository.WizRepository
import com.example.wiz.services.SpeechRecognitionService
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: WizRepository,
    private val speechRecognitionService: SpeechRecognitionService
) : ViewModel() {

    // Chat state
    private val _chatId = MutableStateFlow<Long>(-1)
    private val _chatTitle = MutableStateFlow<String>("New Chat")
    val chatTitle = _chatTitle.asStateFlow()

    // UI state for the text input
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // Transition state to coordinate loading with animations
    private val _isTransitionComplete = MutableStateFlow(false)
    val isTransitionComplete = _isTransitionComplete.asStateFlow()

    // Defer message loading until transition completes
    private val _shouldLoadMessages = MutableStateFlow(false)

    // Messages in the current chat - gated by transition completion
    val messages = _chatId.combine(_shouldLoadMessages) { id, shouldLoad ->
        Pair(id, shouldLoad)
    }.flatMapLatest { (id, shouldLoad) ->
        if (shouldLoad && id > 0) {
            repository.getMessagesForChat(id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Transcription state from speech service
    val transcriptionState = speechRecognitionService.transcriptionState
    val isListening = speechRecognitionService.isListening
    val speechError = speechRecognitionService.errorState

    // Responses are in progress
    private val _isResponding = MutableStateFlow(false)
    val isResponding = _isResponding.asStateFlow()

    // Chat persistence jobs
    private var persistenceJob: Job? = null

    // Initialize the speech recognition service
    init {
        speechRecognitionService.initialize()
    }

    // Called when UI transition animation completes
    fun onTransitionComplete() {
        _isTransitionComplete.value = true
        _shouldLoadMessages.value = true
    }

    // Reset transition state when navigating away
    fun onTransitionStart() {
        _isTransitionComplete.value = false
        _shouldLoadMessages.value = false
    }

    fun loadChat(chatId: Long) {
        viewModelScope.launch {
            if (chatId <= 0) {
                // This is a new chat
                _chatId.value = -1
                _chatTitle.value = "New Chat"
                // Even for a new chat, allow message loading (it will be empty initially)
                _shouldLoadMessages.value = true // <-- Add this for new chats too
                return@launch
            }

            Log.d("ChatViewModel", "Loading chat with ID: $chatId")

            val chat = repository.getChatById(chatId)
            if (chat != null) {
                _chatId.value = chatId
                _chatTitle.value = chat.title
                _shouldLoadMessages.value = true // <-- Set this after confirming chat ID
                Log.d("ChatViewModel", "Loaded chat: ${chat.title} (ID: $chatId)")
            } else {
                // Chat not found, treat as new
                _chatId.value = -1
                _chatTitle.value = "New Chat"
                _shouldLoadMessages.value = true // <-- Add this here as well
                Log.d("ChatViewModel", "Chat not found, creating new chat")
            }
        }
    }


    // Update input text (for manual text entry)
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    // Toggle speech recognition
    fun toggleSpeechRecognition() {
        if (isListening.value) {
            speechRecognitionService.stopListening()
        } else {
            speechRecognitionService.startListening { finalText ->
                sendUserInput(finalText)
            }
        }
    }

    // Send the current input or transcription text
    fun sendUserInput(text: String = _inputText.value) {
        if (text.isBlank()) return

        viewModelScope.launch {
            // Create a new chat if needed
            if (_chatId.value <= 0) {
                val chatTitle = repository.deriveChatTitle(text)
                val newChatId = repository.createChat(chatTitle)
                _chatId.value = newChatId
                _chatTitle.value = chatTitle

                // Ensure we're loading messages for this new chat
                _shouldLoadMessages.value = true
            }

            // Add user message
            repository.addUserMessage(_chatId.value, text)

            // Clear input
            _inputText.value = ""

            // Generate assistant response
            generateAssistantResponse()

            // Schedule persistence check
            schedulePersistenceCheck()
        }
    }

    // Generate a canned assistant response
    private suspend fun generateAssistantResponse() {
        _isResponding.value = true

        // Simulate network delay for realism
        delay(1000)

        // Sample responses - in a real app, this would call the LLM API
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

        val response = responses.random()
        repository.addAssistantMessage(_chatId.value, response)

        _isResponding.value = false
    }

    // Schedule a check to see if this chat should be persisted
    private fun schedulePersistenceCheck() {
        persistenceJob?.cancel()
        persistenceJob = viewModelScope.launch {
            if (repository.shouldPersistChat(_chatId.value)) {
                // Update last message time to ensure proper sorting
                repository.updateChatLastMessageTime(_chatId.value)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognitionService.release()
    }
}