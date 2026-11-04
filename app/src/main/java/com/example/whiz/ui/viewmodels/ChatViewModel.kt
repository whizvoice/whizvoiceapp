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



/**
 * Structured server error codes that open the API-key dialog (routes to Settings)
 * instead of rendering an inline "Server Error" chat bubble.
 */
val AUTH_DIALOG_ERROR_CODES: Set<String> = setOf(
    "CLAUDE_AUTHENTICATION_ERROR",
    "CLAUDE_API_KEY_MISSING",
)

/**
 * Structured server error codes whose message is shown as-is in the chat: no
 * dialog, and no "Server Error:" prefix. The server's text already tells the
 * user what to do.
 */
val PLAIN_MESSAGE_ERROR_CODES: Set<String> = setOf(
    "CLAUDE_OVERLOADED",
    "CLAUDE_USAGE_LIMIT",
)

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
         * Auto-expires after 5 seconds to prevent getting permanently stuck.
         */
        private const val TRANSITION_TIMEOUT_MS = 5000L
        @Volatile
        private var _isTransitioning = false
        @Volatile
        private var transitionStartTimeMs = 0L

        var isTransitioning: Boolean
            get() {
                if (!_isTransitioning) return false
                if (System.currentTimeMillis() - transitionStartTimeMs > TRANSITION_TIMEOUT_MS) {
                    _isTransitioning = false
                    return false
                }
                return true
            }
            set(value) {
                _isTransitioning = value
                if (value) transitionStartTimeMs = System.currentTimeMillis()
            }

        /**
         * Track the active ChatViewModel instance. When the Activity is recreated,
         * a new ViewModel is created but old ones' coroutine collectors keep running.
         * Only the active instance should process transcriptions and other side effects.
         */
        @Volatile
        var activeInstance: ChatViewModel? = null
    }

    private val TAG = "ChatViewModel"

    init {
        // Register this as the active instance — old instances' collectors will check this
        // and skip processing to avoid duplicate sends
        val previousInstance = activeInstance
        activeInstance = this
        if (previousInstance != null && previousInstance !== this) {
            Log.w(TAG, "⚠️ New ChatViewModel replacing previous instance (Activity recreated). Old collectors will be deactivated.")
        }
    }

    private fun isActiveInstance(): Boolean = activeInstance === this

    // Config state
    val configUseRemoteAgent = true;

    // Chat state - initialize from navigation argument. The id is a client-minted UUID that never
    // changes for a given chat. This ViewModel is Activity-scoped (see ChatScreen's hiltViewModel
    // owner), so the Activity's SavedStateHandle normally carries no "chatId"; in that case start
    // on a freshly minted id (an empty chat with no row) until loadChat() sets the screen's chat.
    private val _chatId = MutableStateFlow<String>(
        // Fall back to the last active conversation before minting: VoiceManager.transcriptionFlow
        // replays (replay = 1), so after an Activity recreation mid-utterance (bubble <-> main)
        // the replayed transcription is sent before loadChat() runs. A random id would create a
        // ghost chat and steal the sentence. Must be evaluated before the init block that pushes
        // _chatId into setActiveConversation, or it would read back its own fresh UUID.
        savedStateHandle.get<String>("chatId")
            ?: connectionStateManager.getLastActiveConversationId()
            ?: UUID.randomUUID().toString()
    )
    val chatId: StateFlow<String> = _chatId.asStateFlow()

    private val _chatTitle = MutableStateFlow<String>("New Chat")
    val chatTitle = _chatTitle.asStateFlow()
    
    // Track multiple pending WebSocket requests by request ID
    private val pendingRequests: MutableMap<String, String> = mutableMapOf() // requestId -> chatId

    // RequestIds successfully written to the WebSocket (OkHttp buffer-accepted). Distinct from
    // pendingRequests (cleared on every disconnect): durable across reconnects so orphan-retry
    // doesn't re-send a message the server already has. Cleared in onCleared().
    private val sentRequestIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Most recently sent user requestId — used to skip TTS for stale responses
    // (when user has sent a newer message before the prior response arrives)
    private var latestUserRequestId: String? = null

    // Track request IDs that were superseded by newer requests (to prevent orphaned retry)
    private val supersededRequestIds = mutableSetOf<String>()
    
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

    // Scroll-to-bottom trigger for UI - incremented when new messages are added or synced
    private val _scrollTrigger = MutableStateFlow(0)
    val scrollTrigger: StateFlow<Int> = _scrollTrigger.asStateFlow()

    // Messages in the current chat with deduplication to handle optimistic UI transitions
    val messages = _chatId
        .flatMapLatest { id ->
        Log.d(TAG, "🔥 messages flow: Chat ID changed to $id")
        repository.observeMessages(id).map { messagesList ->
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
                    key
                }

                // For each group, keep the LATEST message (highest timestamp, then LOWEST id as tiebreaker)
                // Using lowest id when timestamps are equal keeps the first-arriving message,
                // which is more stable as new duplicates arrive (prevents UI flickering)
                val deduplicatedMessages = grouped.values.mapNotNull { duplicates ->
                    duplicates.maxWithOrNull(compareBy({ it.timestamp }, { -it.id }))
                }.sortedBy { it.timestamp }  // Re-sort by timestamp for display order

                deduplicatedMessages
            }
    }.distinctUntilChanged() // Prevent duplicate emissions
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly, // Changed from WhileSubscribed to Eagerly
        initialValue = emptyList()
    )

    // Request IDs the server has resolved — reply received, cancelled/deleted, or reported
    // not-pending on chat load. Lets the thinking indicator come down even when the trailing
    // message is still a USER message (tool-only/textless completions, or clock-skew that
    // sorts the assistant reply before its user message). In-memory; cleared on chat switch.
    private val _resolvedRequestIds = MutableStateFlow<Set<String>>(emptySet())

    private fun markRequestResolved(requestId: String?) {
        if (requestId.isNullOrEmpty()) return
        _resolvedRequestIds.update { it + requestId }
    }

    // Thinking-indicator signal: show whenever the conversation is awaiting a reply — the
    // last text message is from the USER and that request hasn't been resolved by the server.
    // Derived locally from the messages flow, so it shows instantly and survives navigation
    // and process restart with no round-trip; the server's reply/verdict takes it down via
    // _resolvedRequestIds. (Distinct from isResponding, which still drives the mic buttons.)
    val showThinking: StateFlow<Boolean> = combine(messages, _resolvedRequestIds) { msgs, resolved ->
        val last = msgs.lastOrNull()
        last != null && last.type == MessageType.USER &&
            (last.requestId == null || last.requestId !in resolved)
    }.distinctUntilChanged()
     .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
    private var pendingTTSCheckJob: Job? = null  // Delayed job to play queued TTS after speech activity window expires
    private val RECENT_SPEECH_THRESHOLD_MS = 2000L  // Don't start TTS if user spoke within this window
    
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
    private var chatIdWhenDisconnected: String? = null

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

        // TTS barge-in: clear queued TTS when user interrupts
        voiceManager.onBargeIn = {
            Log.d(TAG, "Barge-in: clearing pendingTTSMessage")
            pendingTTSMessage = null
            pendingTTSCheckJob?.cancel()
        }

        // Start observing messages immediately
        if (configUseRemoteAgent) {
            Log.d(TAG, "Init: Using remote agent. WebSocket will connect when sending first message or loading existing chat.")
            // Don't connect here - let sendMessage or loadChatWithVoiceMode handle it
            // This prevents duplicate connections when navigating to existing chats
        }
        
        // Track active conversation in ConnectionStateManager
        viewModelScope.launch {
            _chatId.collect { id ->
                connectionStateManager.setActiveConversation(id)
                Log.d(TAG, "Updated active conversation in ConnectionStateManager: $id")
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

        // Single transcription consumer for BOTH chat and bubble modes.
        //
        // Previously there were two collectors — one on BubbleOverlayService.userTranscriptionFlow
        // and one on voiceManager.transcriptionFlow — gating mutually-exclusively on
        // BubbleOverlayService.isActive at consume time. That created a race where a bubble
        // open/close mid-utterance could leave both gates failing (message dropped) or both
        // passing (message duplicated). Both consumers always called the same sendUserInput
        // with the same chat-id semantics, so the split bought nothing functional.
        //
        // Now: one collector on voiceManager.transcriptionFlow processes every final regardless
        // of bubble state. The bubble UI keeps its own subscription on
        // BubbleOverlayService.userTranscriptionFlow for display purposes (BubbleOverlayService
        // still receives via SpeechRecognitionService.onResults / onSegmentResults).
        //
        // Dedup uses voiceManager.lastProcessedTranscriptionSeq so a freshly-created
        // ChatViewModel that receives the replayed last emission doesn't double-send what an
        // earlier (now superseded) instance already sent.
        viewModelScope.launch {
            voiceManager.transcriptionFlow
                .collect { emission ->
                    val active = isActiveInstance()
                    val bubbleActive = BubbleOverlayService.isActive  // for diagnostics only
                    val transcription = emission.text
                    if (!active) {
                        return@collect
                    }
                    if (emission.seq <= voiceManager.lastProcessedTranscriptionSeq) {
                        return@collect
                    }
                    if (transcription.isNotBlank()) {
                        Log.d(TAG, "Received voice transcription: '$transcription' (bubbleActive=$bubbleActive)")
                        voiceManager.lastProcessedTranscriptionSeq = emission.seq
                        updateInputText(transcription, fromVoice = true)
                        sendUserInput(transcription)
                    }
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
                        Log.d(TAG, "WebSocketEvent.Connected: Syncing messages for chat $currentChatId (reconnect=$isReconnectingAfterDisconnect)")
                        viewModelScope.launch {
                            // A chat with no local row has had nothing sent yet - nothing to sync.
                            if (!chatRowExists(currentChatId)) {
                                Log.d(TAG, "WebSocketEvent.Connected: Chat $currentChatId has no row yet (new chat), skipping sync")
                                return@launch
                            }
                            try {
                                // Clean up temporary error messages from offline state
                                val deletedCount = repository.deleteAssistantMessageByRequestId(currentChatId, "CONNECTION_ERROR")
                                if (deletedCount > 0) {
                                    Log.d(TAG, "WebSocketEvent.Connected: Cleaned up $deletedCount CONNECTION_ERROR message(s)")
                                    repository.refreshMessages()
                                }

                                // Fetch any messages we might have missed
                                val serverMessages = repository.fetchMessagesWithRetry(currentChatId)
                                Log.d(TAG, "WebSocketEvent.Connected: Retrieved ${serverMessages.size} messages from server for chat $currentChatId")

                                // fetchMessagesWithRetry already stores messages; just trigger UI refresh
                                repository.refreshMessages()
                                _scrollTrigger.value += 1 // Scroll to show synced messages after reconnection

                                // Check for orphaned messages that need to be sent
                                // This handles messages that were created while offline
                                checkAndRetryOrphanedMessages(currentChatId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error syncing messages for chat $currentChatId", e)
                                // Don't show error to user - this is a background sync
                            }
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
                        viewModelScope.launch {
                            // A chat with no local row has had nothing sent yet; it connects on first send.
                            if (!chatRowExists(currentChatId)) {
                                Log.d(TAG, "WebSocketEvent.Reconnecting: Chat $currentChatId has no row yet (new chat), not reconnecting")
                                return@launch
                            }
                            Log.d(TAG, "WebSocketEvent.Reconnecting: Attempting to reconnect with chatId=$currentChatId")
                            try {
                                // Don't reset persistentDisconnectForTest flag here - let the test control it
                                whizServerRepository.connect(currentChatId)
                                Log.d(TAG, "WebSocketEvent.Reconnecting: Reconnection initiated for chatId=$currentChatId")
                            } catch (e: Exception) {
                                Log.e(TAG, "WebSocketEvent.Reconnecting: Failed to reconnect", e)
                            }
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
                            val errorChatId = _chatId.value
                            if (chatRowExists(errorChatId)) { // Only add if the chat has a row (a message was sent)
                                // Use optimistic (local-only) insert since we're likely offline
                                repository.addAssistantMessageOptimistic(
                                    chatId = errorChatId,
                                    content = "Error: Unable to send message. Please try again.",
                                    requestId = "CONNECTION_ERROR"
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
                                // Reconnect to the same conversation we were in. A chat with no
                                // row yet has sent nothing and connects on first send.
                                val reconnectChatId = _chatId.value
                                if (chatRowExists(reconnectChatId)) {
                                    whizServerRepository.connect(reconnectChatId)
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
                                val authFailChatId = _chatId.value
                                if (chatRowExists(authFailChatId)) { // Only add if the chat has a row
                                    repository.addAssistantMessage(
                                        chatId = authFailChatId,
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
                        whizServerRepository.untrackRequest(event.cancelledRequestId)
                        // Server resolved this request (cancelled). If it's the trailing message the
                        // thinking indicator comes down; if a newer user message superseded it, that
                        // newer message keeps the indicator up.
                        markRequestResolved(event.cancelledRequestId)
                        Log.d(TAG, "🔥 CANCELLATION: Pending requests map after removing: $pendingRequests")
                        // 🔧 DON'T update responding state here - animation should continue until
                        // a real (non-cancelled) response arrives. This prevents the typing indicator
                        // from stopping when a request is cancelled but no response text was shown.
                    }
                    is WebSocketEvent.DeleteMessage -> {
                        Log.d(TAG, "🗑️ Delete message notification: messageId=${event.messageId}, conversationId=${event.conversationId}, requestId=${event.requestId}, reason=${event.reason}")

                        if (event.requestId != null && event.reason == "superseded_by_new_request") {
                            supersededRequestIds.add(event.requestId)
                        }

                        // Server resolved this request (its message is being deleted/superseded), so it
                        // will never produce a visible reply — take the indicator down for it.
                        markRequestResolved(event.requestId)

                        if (event.requestId != null && pendingRequests.containsKey(event.requestId)) {
                            Log.d(TAG, "🗑️ Removing superseded request ${event.requestId} from pendingRequests")
                            pendingRequests.remove(event.requestId)
                            whizServerRepository.untrackRequest(event.requestId)
                            updateRespondingStateForCurrentChat()
                        }

                        // Only delete if this message is for the current chat
                        val deleteChatId = _chatId.value
                        if (event.chatId == deleteChatId) {
                            viewModelScope.launch {
                                try {
                                    if (event.requestId != null) {
                                        // Delete the assistant message by request_id
                                        Log.d(TAG, "🗑️ Deleting assistant message with requestId=${event.requestId} from chat $deleteChatId")
                                        val deletedCount = withContext(Dispatchers.IO) {
                                            repository.deleteAssistantMessageByRequestId(deleteChatId, event.requestId)
                                        }
                                        Log.d(TAG, "🗑️ Deleted $deletedCount assistant message(s) for request ${event.requestId}")
                                    } else {
                                        // Fallback: if no request_id, trigger a refresh to sync with server
                                        Log.w(TAG, "🗑️ No request_id in delete notification, falling back to message refresh")
                                        repository.syncMessages(deleteChatId)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error handling delete message event", e)
                                }
                            }
                        } else {
                            Log.d(TAG, "🗑️ Ignoring delete message for different conversation: ${event.chatId} (current: $deleteChatId)")
                        }
                    }
                    is WebSocketEvent.Message -> {
                        val processingStartTime = System.currentTimeMillis()
                        val messageReceivedTime = System.currentTimeMillis()
                        val processingDelay = processingStartTime - messageReceivedTime
                        if (processingDelay > 100) {
                            Log.w(TAG, "Message processing delay: ${processingDelay}ms")
                        }
                        
                        Log.d(TAG, "🔧 WEBSOCKET_MESSAGE: Received message event with chatId=${event.chatId}, conversationId=${event.conversationId}, requestId=${event.requestId}")

                        try {
                            var isErrorHandled = false
                            var messageContentForChat = event.content
                            // Check both voice response setting AND bubble TTS mode
                            var speakThisMessage = isVoiceResponseEnabled.value ||
                                (BubbleOverlayService.isActive && BubbleOverlayService.bubbleListeningMode == ListeningMode.TTS_WITH_LISTENING)

                            // Record the server's integer id on the row (diagnostics only; never read back).
                            event.conversationId?.let { serverId ->
                                repository.recordServerId(event.chatId, serverId)
                            }

                            // Only attempt JSON parsing for error handling if the text looks like JSON
                            try {
                                if (event.content.trimStart().startsWith("{")) {
                                    val jsonObject = JSONObject(event.content)
                                    
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
                                        in AUTH_DIALOG_ERROR_CODES -> {
                                            Log.w(TAG, "Handling Claude API key error ($errorCode) from server: $errorMessageFromServer")
                                            _showAuthErrorDialog.value = errorMessageFromServer
                                        }
                                        in PLAIN_MESSAGE_ERROR_CODES -> {
                                            Log.w(TAG, "Claude API error shown as chat message ($errorCode): $errorMessageFromServer")
                                            messageContentForChat = errorMessageFromServer
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

                            // Route the reply to the chat its request was sent from. Replies with no
                            // pending request (broadcasts, or a completion after a disconnect cleared
                            // pendingRequests) go to the chat the server says they belong to.
                            val requestId = event.requestId
                            val originalChatId = requestId?.let { pendingRequests.remove(it) }
                            val targetChatId: String = if (requestId != null && originalChatId != null) {
                                whizServerRepository.untrackRequest(requestId)
                                Log.d(TAG, "🐛 VOICE_DEBUG: Found requestId $requestId in pendingRequests, chat = $originalChatId")
                                originalChatId
                            } else {
                                if (requestId != null) {
                                    Log.w(TAG, "Request ID $requestId not found in pending requests")
                                }
                                Log.d(TAG, "🐛 VOICE_DEBUG: Using chatId from server: ${event.chatId} (current: ${_chatId.value})")
                                event.chatId
                            }
                            Log.d(TAG, "🐛 VOICE_DEBUG: Final calculated targetChatId = $targetChatId")

                            val isResponseForCurrentChat = (targetChatId == _chatId.value)

                            if (!isResponseForCurrentChat) {
                                // Reply for a chat that isn't on screen: store it under its own chat
                                // (creating the row if this device has never seen it), but skip the
                                // bubble/TTS/UI handling below.
                                Log.w(TAG, "Response is for chat $targetChatId but current chat is ${_chatId.value}. Storing it without UI/TTS.")
                                viewModelScope.launch {
                                    try {
                                        storeAssistantReply(targetChatId, messageContentForChat, event.requestId, event.serverTimestamp)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error storing reply for chat $targetChatId", e)
                                    }
                                }
                                // 🔧 Update responding state for current chat since we're not processing this response
                                updateRespondingStateForCurrentChat()
                                return@collect
                            }

                            // Only save assistant message to local DB if NOT using remote agent
                            // Remote agent (WebSocket server) handles message persistence
                            if (!configUseRemoteAgent) {
                                try {
                                    viewModelScope.launch {
                                        val messageId = repository.addAssistantMessage(
                                            chatId = targetChatId,
                                            content = messageContentForChat
                                        )
                                        updateRespondingStateForCurrentChat()
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
                                        // Use request ID pairing to insert assistant message after corresponding user message
                                        // This ensures responses appear in the correct order relative to their user messages
                                        storeAssistantReply(targetChatId, messageContentForChat, event.requestId, event.serverTimestamp)
                                        // Server replied — mark resolved so the indicator comes down even if
                                        // clock skew sorts this reply before its user message in the list.
                                        markRequestResolved(event.requestId)
                                        // Update responding state AFTER message is inserted so typing indicator
                                        // doesn't disappear before the new message appears
                                        updateRespondingStateForCurrentChat()
                                        _scrollTrigger.value += 1 // Scroll to show bot response
                                    }
                                } catch (e: Exception) {
                                }
                            }

                            // TTS and UI updates (only for current chat)
                            
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
                                    messageReceivedTime > lastBackgroundedTime
                                } else {
                                    // Either never backgrounded OR backgrounded long ago (>3 seconds)
                                    // Only speak recent messages (arrived within last 3 seconds)
                                    val messageAge = currentTime - messageReceivedTime
                                    messageAge <= 3000L
                                }
                                
                                // 🔧 Additional validation: Only speak if this is truly for the current visible chat
                                // and the message is actually being displayed to the user
                                // Skip TTS if a newer user message exists (response is stale).
                                // Null cases fall through to speak: legacy non-remote-agent path or first response in session.
                                val isResponseLatest = event.requestId == null ||
                                                       latestUserRequestId == null ||
                                                       event.requestId == latestUserRequestId

                                // Use VoiceManager's shouldEnableTTS which checks bubble mode properly
                                val ttsEnabled = voiceManager.shouldEnableTTS()
                                val shouldSpeak = ttsEnabled &&
                                                _isTTSInitialized.value &&
                                                speakThisMessage &&
                                                messageContentForChat.isNotBlank() &&
                                                isResponseForCurrentChat &&
                                                targetChatId == _chatId.value && // Double-check current chat
                                                isMessageFresh && // Only speak fresh messages
                                                isResponseLatest // Skip stale responses (newer user msg exists)

                                Log.d(TAG, "TTS Decision: ttsEnabled=$ttsEnabled, ttsInit=${_isTTSInitialized.value}, " +
                                        "speakThis=$speakThisMessage, fresh=$isMessageFresh, latest=$isResponseLatest, " +
                                        "shouldSpeak=$shouldSpeak")
                                
                                if (shouldSpeak) {
                                    // Check if user has active partial transcription OR was recently speaking
                                    val hasPartialTranscription = voiceManager.transcriptionState.value.isNotBlank()
                                    val recentSpeechMs = System.currentTimeMillis() - voiceManager.lastSpeechActivityTimestamp
                                    val userRecentlySpeaking = voiceManager.lastSpeechActivityTimestamp > 0L && recentSpeechMs < RECENT_SPEECH_THRESHOLD_MS
                                    val shouldQueueTTS = hasPartialTranscription || userRecentlySpeaking

                                    Log.d(TAG, "TTS Queue Decision: hasPartial=$hasPartialTranscription, " +
                                            "recentSpeechMs=$recentSpeechMs, userRecentlySpeaking=$userRecentlySpeaking, " +
                                            "shouldQueue=$shouldQueueTTS")

                                    if (shouldQueueTTS) {
                                        Log.d(TAG, "User is speaking or was recently speaking - queueing TTS: '${messageContentForChat.take(50)}...'")
                                        // Always update to the latest message - replaces any existing queued message
                                        pendingTTSMessage = messageContentForChat
                                        // Schedule delayed playback after the speech activity window expires
                                        pendingTTSCheckJob?.cancel()
                                        pendingTTSCheckJob = viewModelScope.launch {
                                            val waitMs = (RECENT_SPEECH_THRESHOLD_MS - recentSpeechMs + 500L).coerceAtLeast(500L)
                                            delay(waitMs)
                                            startQueuedTTS()
                                        }
                                        // Don't stop listening, let user finish
                                    } else {
                                        // No active speech - start TTS immediately
                                        // Full-duplex: keep mic active when AEC/headphones available
                                        if (isListening.value && !voiceManager.isFullDuplexAvailable()) {
                                            wasListeningBeforeTTS = true
                                            voiceManager.stopListening() // Stop STT before TTS speaks
                                        }
                                        ttsManager.speak(messageContentForChat, "chat_message")
                                        // TTSManager will handle the isSpeaking state
                                    }
                                } else {
                                    // Restart listening after assistant reply if needed
                                    if (isListening.value) {
                                        // Already listening (full-duplex mode) - nothing to restart
                                    } else if (voiceManager.isContinuousListeningEnabled.value && !isSpeaking.value) {
                                        startContinuousListening()
                                    } else if (wasListeningBeforeTTS && !isSpeaking.value) {
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
                if (!chatRowExists(currentChatId)) {
                    Log.w(TAG, "[TOOL_COLLECTOR] Cannot send tool result - current chat $currentChatId has no row (new chat)")
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
                    is ToolExecutionResult.Status -> {
                        Log.i(TAG, "[TOOL_COLLECTOR] Tool status: ${result.status} for ${result.requestId}")
                        whizServerRepository.sendToolStatus(
                            toolName = result.toolName,
                            requestId = result.requestId,
                            status = result.status,
                            message = result.message,
                            chatId = currentChatId
                        )
                    }
                }
            }
        }
    }

    /** A chat has a local row once its first message is sent (or a reply/sync brought it in). */
    private suspend fun chatRowExists(chatId: String): Boolean = repository.getChatById(chatId) != null

    /**
     * Store an assistant reply under [chatId], creating the chat row first if this device has
     * never seen the chat. With a request id the reply is placed right after its user message.
     */
    private suspend fun storeAssistantReply(chatId: String, content: String, requestId: String?, serverTimestamp: String?) {
        if (chatId.isBlank()) { Log.w(TAG, "storeAssistantReply: blank chatId, dropping reply (requestId=$requestId)"); return }
        repository.ensureChatRowForIncoming(chatId, content)
        if (requestId != null) {
            repository.addAssistantMessageAfterRequest(chatId, content, requestId, serverTimestamp)
        } else {
            // Fallback: add at end if no request ID
            repository.addAssistantMessageOptimistic(chatId, content)
        }
    }

    private suspend fun handleServerMessage(responseText: String) {
        val currentChatId = _chatId.value

        // Create the chat row if nothing has been stored for this chat yet
        if (!chatRowExists(currentChatId)) {
            Log.d(TAG, "handleServerMessage: Chat $currentChatId has no row. Creating it for initial server message.")
            // Derive a title from the server message or use a default
            val chatTitle = repository.deriveChatTitle(responseText).ifBlank { "Assistant Chat" } // Use helper or default
            repository.createChatLocal(currentChatId, chatTitle)
            _chatTitle.value = chatTitle

            Log.d(TAG, "handleServerMessage: Created chat $currentChatId with title '$chatTitle'")
        }

        // The chat row now exists
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

    fun loadChat(chatId: String) {
        loadChatWithVoiceMode(chatId, false)
    }

    /** A chat is new until its first send creates the local row. */
    suspend fun isNewChat(chatId: String): Boolean = repository.getChatById(chatId) == null

    fun loadChatWithVoiceMode(chatId: String, isVoiceModeActivation: Boolean = false) {
        Log.d(TAG, "🔥 loadChatWithVoiceMode STARTED for chatId: $chatId, voiceMode: $isVoiceModeActivation")
        Log.d(TAG, "🔥 STACK TRACE for loadChatWithVoiceMode:", Exception("Stack trace"))
        viewModelScope.launch {
            try {
                // 🔧 Enhanced cleanup when switching chats
                Log.d(TAG, "Loading chat $chatId. Current chat: ${_chatId.value}, Pending requests: ${pendingRequests.size}")

                // Skip disconnect when re-entering the same chat (e.g. navigating from chat list back)
                // The ViewModel survives navigation (Activity-scoped), so pendingRequests and WebSocket are still valid
                val isSameChat = chatId == _chatId.value

                // Disconnect from server if switching chats or loading new (but not when re-entering the same chat)
                if (configUseRemoteAgent && !isSameChat) {
                    try {
                        // Clear reconnection flag since this is an intentional disconnect
                        isReconnectingAfterDisconnect = false
                        chatIdWhenDisconnected = null
                        whizServerRepository.disconnect()
                        _isConnectedToServer.value = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disconnecting WebSocket during loadChat", e)
                    }
                } else if (isSameChat) {
                    Log.d(TAG, "🔥 loadChat: Re-entering same chat $chatId - skipping disconnect to preserve pending requests")
                }
                
                // Clear superseded request tracking on chat load
                supersededRequestIds.clear()

                // Reset server-resolved tracking when switching to a different chat. Keep it on
                // same-chat re-entry, since the reconcile below is gated on !isSameChat and would
                // not re-resolve a request we already took the indicator down for.
                if (!isSameChat) {
                    _resolvedRequestIds.value = emptySet()
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

                // A chat with no local row is a new chat: nothing has been sent yet, so there is
                // nothing to sync and the WebSocket connects on first send.
                var hasChatRow = false
                Log.d(TAG, "Loading chat with ID: $chatId")
                try {
                    val chat = repository.getChatById(chatId)
                    _chatId.value = chatId
                    if (chat != null) {
                        hasChatRow = true
                        _chatTitle.value = chat.title
                        Log.d(TAG, "🔥 loadChat: LOADED chat: ${chat.title} (ID: $chatId)")
                        // 🔧 For existing chats, update based on actual pending requests
                        updateRespondingStateForCurrentChat()
                    } else {
                        _chatTitle.value = "New Chat"
                        _isResponding.value = false // 🔧 Immediately set to false for new chats
                        Log.d(TAG, "🔥 loadChat: New chat $chatId (no row yet), responding state reset to false")

                        // Prime WebSocket connection early for new chats - saves 150-250ms when user sends first message
                        if (configUseRemoteAgent) {
                            try {
                                Log.d(TAG, "🔥 loadChat: Priming WebSocket connection for new chat")
                                whizServerRepository.primeConnection()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to prime WebSocket connection", e)
                                // Non-fatal - connection will be established on first message
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Room failed on a primary-key lookup. Keep the requested chat and let the
                    // sync/connect below run; the screen shows whatever Room can still provide.
                    Log.e(TAG, "Error loading chat from repository", e)
                    _chatId.value = chatId // Keep the requested chat ID
                    hasChatRow = true // Unknown; still attempt the sync/connect below as before
                    _chatTitle.value = "Chat"
                    _isResponding.value = false
                    _errorState.value = null // Clear general error state
                }
                
                // Don't clear input text - if it's the same chat, preserve what user was typing
                // If it's a different chat, we have a new ViewModel instance anyway
                // 🔧 Update responding state based on current chat's pending requests
                updateRespondingStateForCurrentChat()
                _errorState.value = null // 🔧 Clear any error states when switching chats
                _connectionError.value = null // 🔧 Clear connection errors too
                
                // 🔧 Reset voice responses to default (off) when loading a chat, UNLESS voice mode is being activated
                // This prevents voice responses from staying on from previous sessions
                // Voice mode will explicitly re-enable it if needed
                if (!isVoiceModeActivation) {
                    if (isVoiceResponseEnabled.value) {
                        voiceManager.setVoiceResponseEnabled(false)
                    }
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

                // Refresh messages to ensure we have latest data (skip for a new chat with no row)
                if (hasChatRow) {
                    try {
                        Log.d(TAG, "loadChat: Performing sync for chat ${_chatId.value}")
                        val serverMessages = repository.fetchMessagesWithRetry(_chatId.value)
                        Log.d(TAG, "loadChat: Retrieved ${serverMessages.size} messages from server for chat ${_chatId.value}")
                        
                        // The fetchMessagesWithRetry method already handles storing messages and deduplication
                        // Just trigger messages refresh to update UI
                        repository.refreshMessages()
                        _scrollTrigger.value += 1 // Scroll to show synced messages after loadChat
                    } catch (e: Exception) {
                        Log.e(TAG, "Error syncing messages during loadChat", e)
                        _errorState.value = "Failed to sync messages: ${e.message}"
                    }
                }

                // Connect to server if needed *after* chat ID is set
                // BUT respect manual disconnect flag (for testing connection errors)
                Log.d(TAG, "🔌 Checking WebSocket reconnect: configUseRemoteAgent=$configUseRemoteAgent, _chatId.value=${_chatId.value}, isConnected=${whizServerRepository.isConnected()}, isSameChat=$isSameChat, persistentDisconnectForTest=${whizServerRepository.persistentDisconnectForTest()}")
                // Connect for chats that have a row; a new chat (no row) connects when sending its first message
                // Skip reconnect if re-entering the same chat and already connected
                if (isSameChat && whizServerRepository.isConnected()) {
                    Log.d(TAG, "🔌 Skipping WebSocket reconnect - re-entering same chat and already connected")
                } else if (configUseRemoteAgent && hasChatRow && !whizServerRepository.persistentDisconnectForTest()) {
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
                
                // Reconcile the thinking indicator against the server's view of in-flight requests.
                // The indicator shows locally whenever the last message is from the USER (see
                // showThinking); here we ask the server whether that trailing request is actually still
                // pending. If the server has no record of it as pending — it completed, failed, or was a
                // textless/tool-only turn that left no reply — mark it resolved so the indicator comes
                // down. If it is genuinely pending, ensure it stays shown.
                if (configUseRemoteAgent && !isSameChat && hasChatRow) {
                    try {
                        val trailingRequestId = messages.value.lastOrNull()
                            ?.takeIf { it.type == MessageType.USER }
                            ?.requestId
                        if (trailingRequestId != null) {
                            val serverPendingIds = repository.getPendingRequests(_chatId.value)
                            if (serverPendingIds.contains(trailingRequestId)) {
                                Log.d(TAG, "🔥 Server reports trailing request $trailingRequestId still pending for chat ${_chatId.value} - keeping thinking indicator")
                                _resolvedRequestIds.update { it - trailingRequestId }
                            } else {
                                Log.d(TAG, "🔥 Server reports trailing request $trailingRequestId is not pending for chat ${_chatId.value} - clearing thinking indicator")
                                markRequestResolved(trailingRequestId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reconciling pending request state from server", e)
                    }
                }

                // Check for orphaned messages that need retry
                if (configUseRemoteAgent && hasChatRow) {
                    checkAndRetryOrphanedMessages(_chatId.value)
                }

                // 🎙️ VOICE APP BEHAVIOR: Update permission state
                val actualPermissionState = PermissionHandler.hasMicrophonePermission(context)
                if (_micPermissionGranted.value != actualPermissionState) {
                    _micPermissionGranted.value = actualPermissionState
                }

                // Note: We no longer stop/restart continuous listening in loadChat
                // If continuous listening is enabled, it stays running throughout the chat load
                // This avoids race conditions with async stop/start IPC calls
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in loadChat", e)
                _errorState.value = "Failed to load chat: ${e.message}"
                // Try to recover to a safe state
                try {
                    _chatTitle.value = "New Chat"
                    updateRespondingStateForCurrentChat()
                } catch (recoveryError: Exception) {
                    Log.e(TAG, "Error during loadChat recovery", recoveryError)
                }
            }
            Log.d(TAG, "🔥 loadChatWithVoiceMode COMPLETED for chatId: $chatId, final _chatId.value: ${_chatId.value}")
        }
    }

    /**
     * Sync messages when returning to a chat screen
     * This is lighter weight than loadChat - it just fetches new messages without resetting state
     */
    fun syncMessagesIfNeeded(chatId: String) {
        viewModelScope.launch {
            try {
                // Only sync if this is the current chat and it has a row (a new chat has nothing to sync)
                if (_chatId.value == chatId && chatRowExists(chatId)) {
                    Log.d(TAG, "📥 Syncing messages for chat $chatId on screen resume")
                    
                    // Use the retry mechanism to fetch any new messages
                    val serverMessages = repository.fetchMessagesWithRetry(chatId)
                    Log.d(TAG, "📥 Sync complete: Retrieved ${serverMessages.size} messages for chat $chatId")
                    
                    // The fetchMessagesWithRetry method already handles storing messages and deduplication
                    // Just trigger messages refresh to update UI
                    repository.refreshMessages()
                    _scrollTrigger.value += 1 // Scroll to show synced messages on screen resume
                } else {
                    Log.d(TAG, "📥 Skipping sync - chat ID mismatch or invalid. Current: ${_chatId.value}, Requested: $chatId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing messages on resume", e)
                // Don't show error to user - this is a background sync
            }
        }
    }
    
    fun updateInputText(text: String, fromVoice: Boolean = false) {
        _inputText.value = text
        _isInputFromVoice.value = fromVoice
        
        // 🔧 NEW: Auto-disable continuous listening when user starts typing
        // This ensures the send button appears when user types text
        if (!fromVoice && text.isNotEmpty()) {
            if (voiceManager.isContinuousListeningEnabled.value) {
                voiceManager.updateContinuousListeningEnabled(false)
            }
            // Stop any active listening (regular or continuous) when user types
            if (voiceManager.isListening.value) {
                voiceManager.stopListening()
            }
        }
        
        // Clear voice flag when text is empty (reset state)
        if (text.isEmpty()) {
            _isInputFromVoice.value = false
        }
    }

    fun toggleSpeechRecognition() {
        if (!_micPermissionGranted.value) {
            // Request permission instead of just showing an error
            onRequestMicrophonePermission?.invoke()
            return
        }
        
        // Case 1: Microphone is actively listening - always allow stopping
        if (isListening.value) {
            voiceManager.updateContinuousListeningEnabled(false)
            voiceManager.stopListening()
            return
        }
        
        // Case 2: Continuous listening is enabled but mic not actively listening
        // Allow user to disable continuous listening mode
        if (voiceManager.isContinuousListeningEnabled.value) {
            voiceManager.updateContinuousListeningEnabled(false)
            return
        }
        
        // Case 3: Want to enable continuous listening - only block during TTS speaking
        // 🔧 FIXED: Allow microphone during server responses, only block during TTS
        if (ttsManager.isSpeaking.value) {
            _errorState.value = "Cannot start listening while assistant is speaking"
            return
        }
        
        // Case 4: Enable continuous listening and start immediately unless TTS is active
        _inputText.value = ""
        voiceManager.updateContinuousListeningEnabled(true)
        
        // 🔧 IMPROVED: Start listening immediately unless TTS is speaking
        // Allow listening during server responses for better UX
        if (!isSpeaking.value) {
            voiceManager.startListening { recognizedText ->
                if (recognizedText.isNotBlank()) {
                    sendUserInput(recognizedText)
                }
            }
        }
    }

    // Method specifically for ensuring continuous listening is enabled (for voice mode activation)
    fun ensureContinuousListeningEnabled() {
        if (!_micPermissionGranted.value) {
            // Request permission instead of just returning
            onRequestMicrophonePermission?.invoke()
            return
        }
        
        // If continuous listening is already enabled, don't disable it
        if (voiceManager.isContinuousListeningEnabled.value) {
            // If not currently listening and not busy, start listening
            // Note: We don't check isResponding here - user should be able to speak while waiting for response
            if (!isListening.value && !isSpeaking.value) {
                startContinuousListening()
            }
            return
        }
        
        // Enable continuous listening if not already enabled
        _inputText.value = ""
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
        // Chat-specific headphone-aware listening behavior
        // Prevents audio feedback when TTS is speaking without headphones
        if (ttsManager.isSpeaking.value) {
            val headphonesConnected = ttsManager.areHeadphonesConnected()
            if (!headphonesConnected) {
                return
            } else {
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
            val currentChatId = _chatId.value

            // 🔧 NEW: Generate request ID early for optimistic UI pairing
            val requestId = if (configUseRemoteAgent) java.util.UUID.randomUUID().toString() else null
            if (requestId != null) {
                latestUserRequestId = requestId
            }

            // New user message supersedes any in-flight TTS from a prior response
            pendingTTSCheckJob?.cancel()
            pendingTTSMessage = null
            if (ttsManager.isSpeaking.value) {
                Log.d(TAG, "New user message — stopping TTS for prior response")
                ttsManager.stop()
            }

            // Capture timestamp for message consistency between local and server
            val messageTimestamp = System.currentTimeMillis()

            // First message in this chat: create its row locally (the server creates its own
            // row from the first message's client_chat_id).
            val isFirstMessage = repository.getChatById(currentChatId) == null
            if (isFirstMessage) {
                val title = repository.deriveChatTitle(trimmedText)
                repository.createChatLocal(currentChatId, title)
                _chatTitle.value = title
            }

            // Add the message with the captured timestamp (optimistic UI)
            val localMessageId = repository.addUserMessageOptimistic(currentChatId, trimmedText, requestId, messageTimestamp)
            _scrollTrigger.value += 1 // Scroll to show new user message

            if (isFirstMessage) {
                // Connect to WebSocket with this chat's id
                if (!whizServerRepository.isConnected() && !whizServerRepository.persistentDisconnectForTest()) {
                    whizServerRepository.connect(currentChatId)
                } else if (whizServerRepository.isConnected()) {
                    // Already connected but possibly to a different chat - reconnect with this chat's id
                    whizServerRepository.connect(currentChatId)
                }
            } else {
                // Ensure WebSocket is connected to the correct conversation
                // This handles the case where we're switching between existing chats
                if (!whizServerRepository.isConnected() && !whizServerRepository.persistentDisconnectForTest()) {
                    whizServerRepository.connect(currentChatId)
                    // After connecting, check for unsent messages
                    viewModelScope.launch {
                        // Wait for the WebSocket to actually connect
                        whizServerRepository.webSocketEvents
                            .first { it is WebSocketEvent.Connected }
                        checkAndRetryOrphanedMessages(currentChatId)
                    }
                } else if (whizServerRepository.isConnected()) {
                    // Already connected but possibly to a different chat - reconnect with correct chat ID
                    whizServerRepository.connect(currentChatId)
                    // After reconnecting, check for unsent messages
                    viewModelScope.launch {
                        // Wait for the WebSocket to actually connect
                        whizServerRepository.webSocketEvents
                            .first { it is WebSocketEvent.Connected }
                        checkAndRetryOrphanedMessages(currentChatId)
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
                    Log.d(TAG, "🧵 THREAD DEBUG: Message appeared on thread: ${Thread.currentThread().name}")
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            }
            
            // Only clear the input if it still contains the message we just sent
            // This prevents clearing text that the user is currently typing
            if (_inputText.value.trim() == trimmedText) {
                _inputText.value = ""
                _isInputFromVoice.value = false
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
                
                val chatIdForWebSocket = currentChatId

                // 🔧 FIX: Ensure requestId is non-null for WebSocket operations
                val nonNullRequestId = requestId ?: java.util.UUID.randomUUID().toString()
                pendingRequests[nonNullRequestId] = chatIdForWebSocket
                whizServerRepository.trackRequest(nonNullRequestId)

                // 🔧 CRITICAL: Update responding state immediately after adding to pending requests
                updateRespondingStateForCurrentChat()
                
                // 🔧 CRITICAL LOGGING: Log what we're sending to the server
                Log.d(TAG, "📤 CALLING whizServerRepository.sendMessage: requestId=$nonNullRequestId, content='$trimmedText', chatId=$chatIdForWebSocket, timestamp=$messageTimestamp")
                val success = whizServerRepository.sendMessage(trimmedText, nonNullRequestId, chatIdForWebSocket, timestamp = messageTimestamp)

                if (success) {
                    sentRequestIds.add(nonNullRequestId)
                } else {
                    // Message was queued for retry, don't clear the request tracking yet
                    // The retry mechanism will handle this transparently
                    Log.d(TAG, "Message queued for retry")
                }
                // 🔧 REMOVED: Manual refresh is unnecessary - messages flow updates automatically
                // The database updates from optimistic UI and server responses will trigger the flow naturally
                
                // Response handling is done in observeServerMessages
            } else {
                // --- Local Fallback ---
                generateAssistantResponse(currentChatId)
            }

            // Schedule persistence check (only for local agent)
            if (!configUseRemoteAgent) {
                schedulePersistenceCheck(currentChatId)
            }

            // Start queued TTS if user was speaking when assistant message arrived
            startQueuedTTS(forceStart = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendUserInput", e)
            }
        }
    }

    /**
     * Send a hidden user message — processed by the server like a normal user input
     * (so the LLM responds), but never inserted into the local message list and excluded
     * from REST history sync via `content_type='hidden_text'` on the server. Used for
     * side-channel signals like "screen unlocked" after a tool was blocked on the keyguard.
     */
    fun sendHiddenSystemMessage(content: String) {
        if (content.isBlank()) return
        if (!configUseRemoteAgent) {
            Log.d(TAG, "sendHiddenSystemMessage skipped — remote agent disabled")
            return
        }

        viewModelScope.launch {
            try {
                val actualChatId = _chatId.value
                if (!chatRowExists(actualChatId)) {
                    Log.w(TAG, "sendHiddenSystemMessage skipped — chat $actualChatId has no row (new chat)")
                    return@launch
                }

                val requestId = java.util.UUID.randomUUID().toString()
                pendingRequests[requestId] = actualChatId
                whizServerRepository.trackRequest(requestId)

                Log.d(TAG, "📤 SENDING HIDDEN SYSTEM MESSAGE: requestId=$requestId, chatId=$actualChatId, content='$content'")
                val success = whizServerRepository.sendMessage(
                    message = content,
                    requestId = requestId,
                    chatId = actualChatId,
                    timestamp = System.currentTimeMillis(),
                    hidden = true
                )
                if (success) {
                    sentRequestIds.add(requestId)
                } else {
                    Log.d(TAG, "Hidden system message queued for retry")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending hidden system message", e)
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
        // 🔧 Don't set _inputText.value as this causes the transcription to appear in the input box
        // and can cause previously sent messages to reappear. Instead, send the transcription directly.
        sendUserInput(transcription) // Pass the transcription directly instead of setting input text
    }

    private suspend fun generateAssistantResponse(chatId: String) {
        if (configUseRemoteAgent) return // Don't run if using remote

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

    private fun schedulePersistenceCheck(chatId: String) {
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
        sentRequestIds.clear()
        
        // VoiceManager is a singleton that manages its own cleanup.
        // Don't shutdown() the singleton TTSManager here — VoiceManager (also singleton)
        // is still using it, and tearing down the underlying TextToSpeech causes the
        // next initialize() to build a fresh engine at platform defaults, silently
        // dropping the user's custom rate/pitch. The OS reclaims TTS resources at
        // process death; we don't need to release them on nav-scoped cleanup.
        ttsManager.stop()
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
            if (!voiceManager.isContinuousListeningEnabled.value) {
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
        // IMPORTANT: Save TTS state FIRST before we disable it
        // BubbleOverlayService will use this to decide whether to enable TTS mode
        voiceManager.ttsStateBeforeBackground = isVoiceResponseEnabled.value

        // Record when we're backgrounding to prevent TTS replay of old messages
        lastBackgroundedTime = System.currentTimeMillis()

        if (!BubbleOverlayService.isActive && !BubbleOverlayService.isPendingStart) {
            // No bubble — full teardown to prevent background audio
            Log.d(TAG, "[LOG] No bubble - full TTS teardown")

            pendingTTSCheckJob?.cancel()
            pendingTTSMessage = null

            // Stop unconditionally: an utterance handed to the engine reports
            // isSpeaking=false until the engine's onStart fires (~700ms), so gating
            // on isSpeaking lets it start speaking after the screen is off (bug #1516).
            Log.d(TAG, "[LOG] Stopping TTS audio as app is going to background")
            ttsManager.stop()

            voiceManager.setVoiceResponseEnabled(false)
        } else {
            Log.d(TAG, "[LOG] Bubble overlay active/pending - preserving TTS state, active audio, and queued TTS for bubble continuity")
        }

        // VoiceManager handles stopping continuous listening on background
    }

    // Called when app comes back to foreground - restart continuous listening if it was enabled
    fun onAppForegrounded() {
        // Only restart if we have permission, are in a chat, and continuous listening was enabled before backgrounding
        if (_micPermissionGranted.value && voiceManager.isContinuousListeningEnabled.value) {
            try {
                viewModelScope.launch {
                    delay(200L) // Brief delay to ensure app is fully resumed
                    
                    // Don't try to "fix" TTS state - MainActivity.onPause already stops TTS properly
                    // Just restart continuous listening if needed
                    if (!isListening.value) {
                        startContinuousListening()
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
    
    fun refreshMessages() {
        val currentChatId = _chatId.value
        viewModelScope.launch {
            if (!chatRowExists(currentChatId)) {
                // A new chat with no row has nothing on the server to refresh
                Log.d(TAG, "refreshMessages: Skipping refresh - chat $currentChatId has no row (new chat)")
                _isRefreshing.value = false
                return@launch
            }
            _isRefreshing.value = true
            try {
                Log.d(TAG, "refreshMessages: Starting refresh for chat $currentChatId")

                // Use fetchMessagesWithRetry which includes deduplication and retry logic
                val serverMessages = repository.fetchMessagesWithRetry(currentChatId)
                Log.d(TAG, "refreshMessages: Retrieved ${serverMessages.size} messages from server")

                // The fetchMessagesWithRetry already handles storing messages with deduplication
                // Just trigger UI refresh
                repository.refreshMessages()

                // If REST fetch succeeded, try reconnecting WebSocket if it's disconnected
                // This handles the case where internet was restored after max reconnect attempts
                if (!whizServerRepository.isConnected()) {
                    Log.d(TAG, "refreshMessages: REST fetch succeeded but WebSocket disconnected, attempting reconnect")
                    // Track retry queue messages in pendingRequests BEFORE connecting,
                    // so when processRetryQueue sends them and responses arrive,
                    // pendingRequests is properly cleared and isResponding resets to false
                    val retryRequestIds = whizServerRepository.getRetryQueueRequestIds()
                    if (retryRequestIds.isNotEmpty()) {
                        Log.d(TAG, "refreshMessages: Adding ${retryRequestIds.size} retry queue request(s) to pendingRequests")
                        for (requestId in retryRequestIds) {
                            pendingRequests[requestId] = currentChatId
                        }
                        _isResponding.value = true
                    }
                    whizServerRepository.connect(currentChatId)
                }

                _errorState.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing messages for chat $currentChatId", e)
                _errorState.value = "Failed to refresh messages: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private fun applyVoiceSettings(voiceSettings: com.example.whiz.data.preferences.VoiceSettings) {
        try {
            currentVoiceSettings = voiceSettings

            if (!voiceSettings.useSystemDefaults) {
                Log.d(TAG, "Applying custom voice settings: speechRate=${voiceSettings.speechRate}, pitch=${voiceSettings.pitch}")
                ttsManager.setSpeechRate(voiceSettings.speechRate)
                ttsManager.setPitch(voiceSettings.pitch)
            } else {
                Log.d(TAG, "Using system default TTS settings - resetting speech rate and pitch to 1.0")
                ttsManager.setSpeechRate(1.0f)
                ttsManager.setPitch(1.0f)
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
    private fun startQueuedTTS(forceStart: Boolean = false) {
        pendingTTSMessage?.let { message ->
            if (!forceStart) {
                val hasPartialTranscription = voiceManager.transcriptionState.value.isNotBlank()
                val recentSpeechMs = System.currentTimeMillis() - voiceManager.lastSpeechActivityTimestamp
                val userRecentlySpeaking = voiceManager.lastSpeechActivityTimestamp > 0L
                    && recentSpeechMs < RECENT_SPEECH_THRESHOLD_MS
                if (hasPartialTranscription || userRecentlySpeaking) {
                    Log.d(TAG, "startQueuedTTS: user still speaking (hasPartial=$hasPartialTranscription, recentSpeechMs=$recentSpeechMs), rescheduling")
                    pendingTTSCheckJob?.cancel()
                    pendingTTSCheckJob = viewModelScope.launch {
                        val waitMs = (RECENT_SPEECH_THRESHOLD_MS - recentSpeechMs + 500L).coerceAtLeast(500L)
                        delay(waitMs)
                        startQueuedTTS()
                    }
                    return
                }
            }
            // Guard against race condition: TTS can fire after app is backgrounded
            // Bypass guard when bubble is active — bubble keeps TTS alive after Activity backgrounds
            val bubbleTTSActive = BubbleOverlayService.isActive || BubbleOverlayService.isPendingStart
            if (!bubbleTTSActive && (!isVoiceResponseEnabled.value || !appLifecycleService.isInForeground())) {
                Log.d(TAG, "startQueuedTTS: skipping - voiceResponse=${isVoiceResponseEnabled.value}, foreground=${appLifecycleService.isInForeground()}, bubbleTTS=$bubbleTTSActive")
                pendingTTSMessage = null
                return
            }
            Log.d(TAG, "Starting queued TTS after user finished speaking: '$message'")
            // Full-duplex: keep mic active when AEC/headphones available
            if (isListening.value && !voiceManager.isFullDuplexAvailable()) {
                wasListeningBeforeTTS = true
                voiceManager.stopListening()
            }
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

                        // Full-duplex: keep mic active when AEC or headphones available
                        if (!voiceManager.isFullDuplexAvailable()) {
                            // No full-duplex: stop listening to prevent mic from picking up TTS audio
                            if (voiceManager.isContinuousListeningEnabled.value) {
                                Log.d(TAG, "Stopping listening to prevent TTS echo (no full-duplex)")
                                wasListeningBeforeTTS = true
                            }
                            viewModelScope.launch(Dispatchers.Main) {
                                voiceManager.stopListening()
                            }
                        } else {
                            Log.d(TAG, "Full-duplex available - keeping mic active during TTS")
                        }
                        // TTSManager handles its own isSpeaking state
                    },
                    onCompleted = {
                        Log.d(TAG, "TTS completed - audio focus released")
                        // TTSManager handles its own isSpeaking state
                        // Restart listening if we stopped it when TTS started
                        // Must run on main thread for speech recognizer
                        viewModelScope.launch(Dispatchers.Main) {
                            if (isListening.value) {
                                // Already listening (full-duplex mode) - no restart needed
                                Log.d(TAG, "TTS completed - mic already active (full-duplex), no restart needed")
                                wasListeningBeforeTTS = false
                            } else if (wasListeningBeforeTTS && voiceManager.isContinuousListeningEnabled.value) {
                                Log.d(TAG, "TTS completed - restarting listening that was paused for TTS")
                                wasListeningBeforeTTS = false
                                startContinuousListening()
                            } else if (voiceManager.isContinuousListeningEnabled.value) {
                                // Continuous listening enabled but mic not active - restart
                                Log.d(TAG, "TTS completed with continuous listening enabled - auto-resuming listening")
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
        
        ttsManager.speak(text, "chat_message")
    }
    
    /**
     * Check for orphaned user messages in a chat that need to be retried.
     * An orphaned message is a user message that was sent more than 2 minutes ago
     * but never received an assistant response.
     * For chats the server hasn't acknowledged yet (no server id recorded), retry all user
     * messages immediately.
     */
    private suspend fun checkAndRetryOrphanedMessages(chatId: String) {
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
            val isUnsyncedChat = repository.getChatById(chatId)?.serverId == null
            val retryThresholdMs = if (isUnsyncedChat) {
                // For chats the server hasn't acknowledged yet, retry messages immediately (1 second threshold)
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

                // Check if it was already superseded by a newer request
                if (supersededRequestIds.contains(requestId)) {
                    Log.d(TAG, "Message ${message.id} (requestId: $requestId) was superseded, skipping retry")
                    continue
                }

                // Check if it was already successfully sent to the server (durable across disconnects)
                if (sentRequestIds.contains(requestId)) {
                    Log.d(TAG, "Message ${message.id} (requestId: $requestId) already sent to server, skipping orphan retry")
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
                    chatId,
                    timestamp = message.timestamp
                )
                
                if (!success) {
                    Log.d(TAG, "Message ${message.id} queued for retry (requestId: $requestId)")
                } else {
                    sentRequestIds.add(requestId)
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