package com.example.whiz.data.remote

import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


sealed class WebSocketEvent {
    data class Message(
        val text: String, 
        val requestId: String? = null, 
        val conversationId: Long? = null,
        val clientConversationId: Long? = null
    ) : WebSocketEvent()
    data class Error(val error: Throwable) : WebSocketEvent()
    data class AuthError(val message: String) : WebSocketEvent()
    data class Cancelled(val cancelledRequestId: String, val requestId: String? = null) : WebSocketEvent()
    data class Interrupted(val message: String, val requestId: String? = null) : WebSocketEvent()
    object Closed : WebSocketEvent()
    object Connected : WebSocketEvent()
    object Reconnecting : WebSocketEvent()
}

// Data class for message retry queue
data class PendingMessage(
    val message: String,
    val requestId: String,
    val conversationId: Long?,
    val clientConversationId: Long? = null,
    val clientMessageId: String? = null,
    val retryCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

// Debug event tracking
data class TimestampedEvent(
    val event: WebSocketEvent,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class WhizServerRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: AuthRepository
) {
    private val TAG = "WhizServerRepo"
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Connection state management
    private enum class ConnectionState {
        IDLE,        // No connection, not attempting to connect
        CONNECTING,  // Connection in progress
        CONNECTED,   // Successfully connected
        DISCONNECTING // Disconnection in progress
    }
    
    private var connectionState = ConnectionState.IDLE
    private val connectionLock = Mutex()
    
    // Debug: Track event history for troubleshooting
    private val eventHistory = mutableListOf<TimestampedEvent>()
    private val maxEventHistorySize = 100 // Keep last 100 events

    // Separate connection state from message events to prevent inappropriate replay
    private val _connectionStateEvents = MutableSharedFlow<WebSocketEvent>(replay = 1) // Only for connection state tracking
    private val _messageEvents = MutableSharedFlow<WebSocketEvent>(replay = 0) // No replay for messages
    
    // Combined flow for backward compatibility
    val webSocketEvents: SharedFlow<WebSocketEvent> = merge(_connectionStateEvents, _messageEvents).shareIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        replay = 0
    )
    
    // Expose connection state for synchronous checking
    fun isConnected(): Boolean {
        return connectionState == ConnectionState.CONNECTED && webSocket != null
    }
    
    // Expose persistent disconnect state for testing
    fun persistentDisconnectForTest(): Boolean {
        return persistentDisconnectForTest
    }
    
    // Helper function to route events to appropriate flows
    private suspend fun emitEvent(event: WebSocketEvent) {
        // Track event in history for debugging
        synchronized(eventHistory) {
            eventHistory.add(TimestampedEvent(event))
            // Keep only the last N events
            while (eventHistory.size > maxEventHistorySize) {
                eventHistory.removeAt(0)
            }
        }
        
        when (event) {
            is WebSocketEvent.Connected, 
            is WebSocketEvent.Closed, 
            is WebSocketEvent.Reconnecting -> {
                _connectionStateEvents.emit(event)
            }
            is WebSocketEvent.Message,
            is WebSocketEvent.Error,
            is WebSocketEvent.AuthError,
            is WebSocketEvent.Cancelled,
            is WebSocketEvent.Interrupted -> {
                _messageEvents.emit(event)
            }
        }
    }
    
    // Debug method to get recent events
    fun getRecentEvents(sinceMinutesAgo: Int = 2): List<TimestampedEvent> {
        val cutoffTime = System.currentTimeMillis() - (sinceMinutesAgo * 60 * 1000)
        synchronized(eventHistory) {
            return eventHistory.filter { it.timestamp >= cutoffTime }.toList()
        }
    }
    
    // Debug method to dump event history to log
    fun dumpEventHistory(tag: String = TAG, sinceMinutesAgo: Int = 2) {
        val recentEvents = getRecentEvents(sinceMinutesAgo)
        Log.d(tag, "=== WebSocket Event History (last $sinceMinutesAgo minutes) ===")
        Log.d(tag, "Current state: connectionState=$connectionState, conversationId=$currentConversationId, isConnected=${isConnected()}, webSocket=${if(webSocket != null) "exists" else "null"}, persistentDisconnect=$persistentDisconnectForTest")
        Log.d(tag, "Replay cache: ${_connectionStateEvents.replayCache.lastOrNull()}")
        Log.d(tag, "Total events in period: ${recentEvents.size}")
        
        val dateFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        recentEvents.forEach { event ->
            val time = dateFormat.format(java.util.Date(event.timestamp))
            val eventStr = when (val e = event.event) {
                is WebSocketEvent.Connected -> "CONNECTED"
                is WebSocketEvent.Closed -> "CLOSED"
                is WebSocketEvent.Reconnecting -> "RECONNECTING"
                is WebSocketEvent.Error -> "ERROR: ${e.error.message}"
                is WebSocketEvent.AuthError -> "AUTH_ERROR: ${e.message}"
                is WebSocketEvent.Message -> "MESSAGE(requestId=${e.requestId}): ${e.text.take(50)}..."
                is WebSocketEvent.Cancelled -> "CANCELLED(requestId=${e.cancelledRequestId})"
                is WebSocketEvent.Interrupted -> "INTERRUPTED: ${e.message}"
            }
            Log.d(tag, "[$time] $eventStr")
        }
        Log.d(tag, "=== End Event History ===")
    }

    // Replace with your server's actual address
    private val WHIZ_SERVER_URL = "wss://whizvoice.com/ws/chat" // Using secure WebSocket with domain

    // Reconnection logic parameters
    private var currentReconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val initialReconnectDelayMs = 1000L
    private val reconnectDelayBackoffFactor = 2.0
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null // Timeout for WebSocket connection attempts
    private var persistentDisconnectForTest = false // Flag to prevent reconnect during testing

    // Message retry queue and parameters
    private val messageRetryQueue = mutableListOf<PendingMessage>()
    private val maxMessageRetries = 3
    private val messageRetryDelayMs = 2000L
    private var retryJob: Job? = null
    private var currentConversationId: Long? = null

    /**
     * Check if a request is currently in the retry queue (for testing purposes)
     */
    fun hasMessageInRetryQueue(requestId: String): Boolean {
        return messageRetryQueue.any { it.requestId == requestId }
    }

    /**
     * Get all request IDs currently in the retry queue (for testing purposes)
     */
    fun getRetryQueueRequestIds(): Set<String> {
        return messageRetryQueue.map { it.requestId }.toSet()
    }

    suspend fun connect(conversationId: Long? = null, turnOffPersistentDisconnect: Boolean = false) {
        Log.d(TAG, "connect() called with conversationId=$conversationId, turnOffPersistentDisconnect=$turnOffPersistentDisconnect, currentPersistentDisconnect=$persistentDisconnectForTest")
        
        // Use lock to ensure thread-safe connection state management
        connectionLock.withLock {
            // Check if we're already connected/connecting to the SAME conversation
            if ((connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) 
                && currentConversationId == conversationId) {
                Log.d(TAG, "Already ${connectionState.name.lowercase()} to same conversation: $conversationId")
                
                // Only reset persistent disconnect flag if explicitly requested
                if (turnOffPersistentDisconnect) {
                    Log.d(TAG, "Resetting persistentDisconnectForTest from $persistentDisconnectForTest to false")
                    persistentDisconnectForTest = false
                }
                
                // If already connected, process any queued messages
                if (connectionState == ConnectionState.CONNECTED) {
                    processRetryQueue()
                }
                return
            }
            
            // IMPORTANT: If we're connected to a specific conversation and someone requests null,
            // stay connected to the specific conversation (it's more specific/better)
            if ((connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED)
                && conversationId == null && currentConversationId != null) {
                Log.w(TAG, "⚠️ SUSPICIOUS: connect(null) called while already ${connectionState.name.lowercase()} to conversation $currentConversationId")
                Log.w(TAG, "⚠️ Stack trace to find caller:", Exception("Stack trace for null conversation connect"))
                
                // Only reset persistent disconnect flag if explicitly requested
                if (turnOffPersistentDisconnect) {
                    Log.d(TAG, "Resetting persistentDisconnectForTest from $persistentDisconnectForTest to false")
                    persistentDisconnectForTest = false
                }
                
                // If already connected, process any queued messages
                if (connectionState == ConnectionState.CONNECTED) {
                    processRetryQueue()
                }
                return
            }
            
            // If connecting/connected to a DIFFERENT conversation (and not the null case above), need to disconnect first
            if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
                Log.d(TAG, "Disconnecting from conversation $currentConversationId to connect to $conversationId")
                
                // Cancel any ongoing connection attempt
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                
                // Cancel the existing WebSocket
                webSocket?.cancel()
                webSocket = null
                
                // Reset state
                connectionState = ConnectionState.IDLE
            }
            
            // If currently disconnecting, wait a bit
            if (connectionState == ConnectionState.DISCONNECTING) {
                Log.d(TAG, "Currently disconnecting, waiting before new connection...")
                delay(100)
            }
            
            // Update state to CONNECTING
            connectionState = ConnectionState.CONNECTING
            currentConversationId = conversationId
            
            // Only reset persistent disconnect flag if explicitly requested
            if (turnOffPersistentDisconnect) {
                Log.d(TAG, "Resetting persistentDisconnectForTest from $persistentDisconnectForTest to false")
                persistentDisconnectForTest = false
            }
            
            // Cancel any pending reconnect job
            reconnectJob?.cancel()
        }  // End of connectionLock.withLock block

        try {
            // Only use server token, no fallback to Google token  
            val serverToken = withContext(Dispatchers.IO) {
                authRepository.serverToken.firstOrNull()
            }
            
            if (serverToken == null) {
                Log.w(TAG, "No server token available for WebSocket connection")
                // Reset connection state on auth failure
                connectionState = ConnectionState.IDLE
                // Keep currentConversationId for potential future retry
                scope.launch { emitEvent(WebSocketEvent.Error(Exception("Authentication required. Please log in again."))) }
                return
            }
            
            // Build WebSocket URL with conversation_id parameter if provided
            val websocketUrl = if (conversationId != null && conversationId > 0) {
                "$WHIZ_SERVER_URL?conversation_id=$conversationId"
            } else {
                WHIZ_SERVER_URL
            }
            
            val requestBuilder = Request.Builder().url(websocketUrl)
            requestBuilder.header("Authorization", "Bearer $serverToken")
            
            val request = requestBuilder.build()
            Log.d(TAG, "Creating new WebSocket with URL: $websocketUrl, persistentDisconnect=$persistentDisconnectForTest")
            
            // Create the WebSocket first
            val newWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        Log.i(TAG, "WebSocket connection opened for conversationId=$conversationId. persistentDisconnect=$persistentDisconnectForTest")
                        
                        // Update connection state
                        connectionState = ConnectionState.CONNECTED
                        
                        connectionTimeoutJob?.cancel() // Cancel timeout on successful connection
                        currentReconnectAttempts = 0 // Reset on successful open
                        reconnectJob?.cancel() // Cancel any pending reconnect job
                        // Don't automatically reset persistentDisconnectForTest on connection open
                        scope.launch { emitEvent(WebSocketEvent.Connected) }

                        // Process any queued messages when reconnected
                        processRetryQueue()

                        // Send timezone via API endpoint after connection is established
                        val timezone = java.util.TimeZone.getDefault().id
                        scope.launch {
                            try {
                                val success = authRepository.setUserTimezone(timezone)
                                if (!success) {
                                    Log.w(TAG, "Failed to set timezone via API: $timezone")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting timezone via API", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onOpen", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        
                        var messageHandled = false
                        var requestId: String? = null
                        
                        // Attempt to parse as JSON first to extract request_id and handle structured responses
                        try {
                            val jsonObject = org.json.JSONObject(text)
                            
                            // Extract request_id if present (could be in regular response or error)
                            requestId = if (jsonObject.has("request_id")) {
                                jsonObject.getString("request_id")
                            } else null
                            
                            // Check if this is a cancellation confirmation
                            if (jsonObject.has("type") && jsonObject.getString("type") == "cancelled") {
                                val cancelledRequestId = if (jsonObject.has("cancelled_request_id")) {
                                    jsonObject.getString("cancelled_request_id")
                                } else null
                                
                                if (cancelledRequestId != null) {
                                    scope.launch { emitEvent(WebSocketEvent.Cancelled(cancelledRequestId, requestId)) }
                                } else {
                                    Log.w(TAG, "Received cancellation confirmation without cancelled_request_id")
                                    scope.launch { emitEvent(WebSocketEvent.Cancelled("unknown", requestId)) }
                                }
                                messageHandled = true
                            }
                            // Check if this is an interruption confirmation
                            else if (jsonObject.has("type") && jsonObject.getString("type") == "interrupted") {
                                val interruptedMessage = if (jsonObject.has("message")) {
                                    jsonObject.getString("message")
                                } else "Request was interrupted"
                                
                                scope.launch { emitEvent(WebSocketEvent.Interrupted(interruptedMessage, requestId)) }
                                messageHandled = true
                            }
                            // Handle structured errors with request_id
                            else if (jsonObject.has("error")) {
                                val errorMessage = jsonObject.getString("error")
                                
                                // Emit the error message as a regular message for ChatViewModel to handle
                                scope.launch { emitEvent(WebSocketEvent.Message(errorMessage, requestId)) }
                                messageHandled = true
                            }
                            // Handle normal structured response with request_id and conversation_id
                            else if (jsonObject.has("response")) {
                                val responseText = jsonObject.getString("response")
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                val clientConversationId = if (jsonObject.has("client_conversation_id")) {
                                    jsonObject.getLong("client_conversation_id")
                                } else null
                                val emitStartTime = System.currentTimeMillis()
                                scope.launch { 
                                    emitEvent(WebSocketEvent.Message(responseText, requestId, conversationId, clientConversationId))
                                    val emitEndTime = System.currentTimeMillis()
                                    val emitDuration = emitEndTime - emitStartTime
                                    if (emitDuration > 50) {
                                        Log.w(TAG, "WebSocket emit delay: ${emitDuration}ms")
                                    }
                                }
                                messageHandled = true
                            }
                        } catch (e: org.json.JSONException) {
                            // Not a JSON object, or JSON parsing failed.
                            Log.d(TAG, "Message is not a structured JSON error. Will proceed to general message handling or legacy checks.")
                        }

                        // If the message was not handled as a structured JSON error above, treat it as a regular message
                        // or apply legacy string checks if absolutely necessary (and if they don't conflict).
                        if (!messageHandled) {
                            // The problematic string checks for "invalid x-api-key" and Asana messages
                            // are removed here because these errors should now be exclusively handled
                            // by the JSON parsing logic above, which emits WebSocketEvent.Message
                            // for ChatViewModel to process. Keeping these string checks would cause
                            // double handling or incorrect emission of WebSocketEvent.AuthError.

                            // If it's a plain text "Authentication failed. Please login again." and NOT JSON
                            // (this is a very specific legacy case)
                            if (text.contains("Authentication failed. Please login again.", ignoreCase = true) && !text.trimStart().startsWith("{")) {
                                Log.w(TAG, "Received plain text legacy auth error")
                                scope.launch { emitEvent(WebSocketEvent.AuthError("Authentication failed. Please login again.")) }
                            } else {
                                // Default for any unhandled or plain text message

                                val emitStartTime = System.currentTimeMillis()
                                scope.launch { 
                                    emitEvent(WebSocketEvent.Message(text, requestId))
                                    val emitEndTime = System.currentTimeMillis()
                                    val emitDuration = emitEndTime - emitStartTime
                                    if (emitDuration > 50) {
                                        Log.w(TAG, "WebSocket emit delay: ${emitDuration}ms")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onMessage", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try {
                        Log.i(TAG, "WebSocket closing: Code=$code, Reason=$reason")
                        webSocket.close(1000, null) // Initiate clean close
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onClosing", e)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    try {
                        Log.i(TAG, "WebSocket connection closed: Code=$code, Reason=$reason, persistentDisconnect=$persistentDisconnectForTest")
                        
                        // Update connection state
                        connectionState = ConnectionState.IDLE
                        // DO NOT reset currentConversationId here - we need it for reconnection!
                        
                        connectionTimeoutJob?.cancel() // Cancel timeout on close
                        this@WhizServerRepository.webSocket = null // Clear reference
                        scope.launch { emitEvent(WebSocketEvent.Closed) }
                        
                        // Attempt to reconnect if not manually disconnected and not a specific "do not retry" code
                        // Example: code 1000 is normal closure, 1008 is policy violation (likely auth)
                        if (!persistentDisconnectForTest && code != 1008 && code != 1011) { 
                            scheduleReconnect()
                        } else {
                             Log.i(TAG, "Not attempting reconnect. persistentDisconnectForTest=$persistentDisconnectForTest, code=$code")
                             currentReconnectAttempts = 0 // Reset if not retrying
                             reconnectJob?.cancel()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onClosed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try {
                        Log.e(TAG, "WebSocket connection failure. persistentDisconnect=$persistentDisconnectForTest, response code=${response?.code}, message=${t.message}", t)
                        
                        // Update connection state
                        connectionState = ConnectionState.IDLE
                        // DO NOT reset currentConversationId here - we need it for reconnection!
                        
                        connectionTimeoutJob?.cancel() // Cancel timeout on failure
                        this@WhizServerRepository.webSocket = null // Clear reference on failure
                        
                        val isAuthFailure = response?.code == 401 || t.message?.contains("Authentication failed", ignoreCase = true) == true
                        
                        if (isAuthFailure) {
                             val userMessage = "Authentication failed. Please log in again."
                            scope.launch { emitEvent(WebSocketEvent.AuthError(userMessage)) }
                            currentReconnectAttempts = 0 // Don't retry on explicit auth failure
                            reconnectJob?.cancel()
                            // Don't automatically set persistentDisconnectForTest on auth failure
                        } else {
                            scope.launch { emitEvent(WebSocketEvent.Error(t)) }
                            if (!persistentDisconnectForTest) {
                                scheduleReconnect()
                            } else {
                                Log.i(TAG, "Not attempting reconnect due to manual disconnect flag on failure.")
                                currentReconnectAttempts = 0
                                reconnectJob?.cancel()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onFailure", e)
                    }
                }
            })
            
            // Store the WebSocket reference immediately
            webSocket = newWebSocket
            
            // Set up a timeout for the connection attempt AFTER creating the WebSocket
            connectionTimeoutJob?.cancel()
            connectionTimeoutJob = scope.launch {
                delay(5000) // 5 second timeout for WebSocket connection
                
                // Check if still in CONNECTING state after timeout
                if (connectionState == ConnectionState.CONNECTING) {
                    Log.e(TAG, "WebSocket connection timeout after 5 seconds for URL: $websocketUrl")
                    Log.e(TAG, "Connection state still CONNECTING, conversationId: $conversationId")
                    
                    // Update state to IDLE
                    connectionState = ConnectionState.IDLE
                    // DO NOT reset currentConversationId here - we need it for reconnection!
                    
                    webSocket?.cancel() // Cancel the hanging connection
                    webSocket = null
                    emitEvent(WebSocketEvent.Error(Exception("WebSocket connection timeout - server may not recognize conversation_id=$conversationId")))
                    
                    // Only attempt reconnect if not manually disconnected
                    if (!persistentDisconnectForTest) {
                        Log.d(TAG, "Scheduling reconnect after timeout")
                        scheduleReconnect()
                    }
                }
            }
            
            Log.d(TAG, "WebSocket creation initiated. Waiting for onOpen/onFailure callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WebSocket connection", e)
            // Reset connection state on error
            connectionState = ConnectionState.IDLE
            // Keep currentConversationId for potential future retry
            scope.launch { emitEvent(WebSocketEvent.Error(e)) }
        }
    }

    fun sendMessage(message: String, requestId: String, clientConversationId: Long? = null, clientMessageId: String? = null): Boolean {
        return try {
            val currentSocket = webSocket
            if (currentSocket != null && !persistentDisconnectForTest) {
                // 🔧 ENHANCED: Check connection state before sending
                if (connectionState != ConnectionState.CONNECTED) {
                    Log.w(TAG, "WebSocket exists but connection state is $connectionState - queueing message for retry")
                    queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
                    return false
                }
                
                // Send structured JSON with request ID and optional client context
                val messageJson = org.json.JSONObject().apply {
                    put("message", message)
                    put("request_id", requestId)
                    put("type", "message")
                    // Add client context for optimistic ID mapping
                    clientConversationId?.let { put("client_conversation_id", it) }
                    clientMessageId?.let { put("client_message_id", it) }
                }
                val jsonMessage = messageJson.toString()
                
                // Try to send the message
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    true
                } else {
                    Log.w(TAG, "WebSocket send failed - queueing message for retry")
                    queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
                    false
                }
            } else {
                Log.w(TAG, "WebSocket not connected - queueing message for retry")
                queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
                // Attempt to reconnect
                if (!persistentDisconnectForTest) {
                    val conversationId = currentConversationId
                    scope.launch {
                        delay(100L) // Small delay before reconnect
                        connect(conversationId)
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message - queueing for retry", e)
            queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
            false
        }
    }

    private fun queueMessageForRetry(message: String, requestId: String, conversationId: Long?, clientConversationId: Long? = null, clientMessageId: String? = null) {
        Log.d(TAG, "Queueing message for retry: $message with requestId: $requestId")
        val pendingMessage = PendingMessage(message, requestId, conversationId, clientConversationId, clientMessageId)
        
        // Remove any existing message with the same requestId to avoid duplicates
        messageRetryQueue.removeAll { it.requestId == requestId }
        messageRetryQueue.add(pendingMessage)
        
        // Start retry process if not already running
        if (retryJob?.isActive != true) {
            retryJob = scope.launch {
                delay(messageRetryDelayMs)
                processRetryQueue()
            }
        }
    }

    private fun processRetryQueue() {
        if (messageRetryQueue.isEmpty()) {
            Log.d(TAG, "Retry queue is empty")
            return
        }
        
        Log.d(TAG, "Processing retry queue with ${messageRetryQueue.size} messages")
        val currentSocket = webSocket
        
        if (currentSocket == null || persistentDisconnectForTest) {
            Log.d(TAG, "Cannot process retry queue - not connected")
            return
        }
        
        val messagesToRetry = messageRetryQueue.toList()
        messageRetryQueue.clear()
        
        messagesToRetry.forEach { pendingMessage ->
            try {
                val messageJson = org.json.JSONObject().apply {
                    put("message", pendingMessage.message)
                    put("request_id", pendingMessage.requestId)
                    put("type", "message")
                    // Include client context for optimistic ID mapping
                    pendingMessage.clientConversationId?.let { put("client_conversation_id", it) }
                    pendingMessage.clientMessageId?.let { put("client_message_id", it) }
                }
                val jsonMessage = messageJson.toString()
                
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    Log.d(TAG, "Successfully retried message: ${pendingMessage.message}")
                } else {
                    val newRetryCount = pendingMessage.retryCount + 1
                    if (newRetryCount < maxMessageRetries) {
                        Log.w(TAG, "Retry failed, queueing again (attempt ${newRetryCount + 1}/$maxMessageRetries)")
                        messageRetryQueue.add(pendingMessage.copy(retryCount = newRetryCount))
                    } else {
                        Log.e(TAG, "Message retry failed after $maxMessageRetries attempts: ${pendingMessage.message}")
                        // Emit error for this specific message
                        scope.launch {
                            emitEvent(WebSocketEvent.Error(Exception("Failed to send message after $maxMessageRetries attempts: ${pendingMessage.message}")))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error retrying message: ${pendingMessage.message}", e)
                val newRetryCount = pendingMessage.retryCount + 1
                if (newRetryCount < maxMessageRetries) {
                    messageRetryQueue.add(pendingMessage.copy(retryCount = newRetryCount))
                } else {
                    scope.launch {
                        emitEvent(WebSocketEvent.Error(Exception("Failed to send message after $maxMessageRetries attempts: ${pendingMessage.message}")))
                    }
                }
            }
        }
        
        // If there are still messages to retry, schedule another attempt
        if (messageRetryQueue.isNotEmpty()) {
            retryJob = scope.launch {
                delay(messageRetryDelayMs * 2) // Longer delay for subsequent retries
                processRetryQueue()
            }
        }
    }

    fun cancelRequest(requestId: String): Boolean {
        return try {
            if (webSocket != null) {
                val cancelRequestId = java.util.UUID.randomUUID().toString()
                val cancelJson = org.json.JSONObject().apply {
                    put("type", "cancel")
                    put("cancel_request_id", requestId)
                    put("request_id", cancelRequestId)
                }
                val jsonMessage = cancelJson.toString()
                Log.d(TAG, "Sending cancel request: $jsonMessage")
                webSocket!!.send(jsonMessage)
                true
            } else {
                Log.w(TAG, "Cannot send cancel request, WebSocket is not connected.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending cancel request", e)
            false
        }
    }

    fun sendInterruptMessage(message: String, requestId: String, clientConversationId: Long? = null, clientMessageId: String? = null): Boolean {
        // This is the same as sendMessage since the backend automatically handles interrupts
        // when a new message arrives while there are active requests
        return sendMessage(message, requestId, clientConversationId, clientMessageId)
    }

    fun disconnect(setPersistentDisconnect: Boolean = false) {
        try {
            Log.d(TAG, "Disconnecting WebSocket manually. setPersistentDisconnect=$setPersistentDisconnect, currentState=$connectionState")
            
            // Check if already disconnected or disconnecting
            if (connectionState == ConnectionState.IDLE || connectionState == ConnectionState.DISCONNECTING) {
                Log.d(TAG, "Already in state $connectionState, skipping disconnect")
                return
            }
            
            // Update state to DISCONNECTING
            connectionState = ConnectionState.DISCONNECTING
            
            // Only set flag to prevent auto-reconnect if explicitly requested
            if (setPersistentDisconnect) {
                persistentDisconnectForTest = true
            }
            
            // Cancel any pending jobs
            connectionTimeoutJob?.cancel()
            reconnectJob?.cancel() // Cancel any pending reconnect attempts
            retryJob?.cancel() // Cancel any pending retry attempts
            currentReconnectAttempts = 0 // Reset attempts
            
            // Don't clear retry queue on manual disconnect - messages should be retried when reconnecting
            // This allows queued messages to be sent when the connection is re-established
            Log.d(TAG, "Preserving ${messageRetryQueue.size} messages in retry queue for reconnection")
            
            // Close the WebSocket
            val currentSocket = webSocket
            if (currentSocket != null) {
                currentSocket.close(1000, "Client initiated disconnect")
            }
            
            // Clear references
            webSocket = null
            connectionState = ConnectionState.IDLE
            // Only clear conversation ID if explicitly disconnecting with persistent flag
            // This allows reconnects to remember the conversation
            if (setPersistentDisconnect) {
                currentConversationId = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
    }

    private fun scheduleReconnect() {
        if (persistentDisconnectForTest) {
            Log.i(TAG, "Reconnect scheduling aborted: manually disconnected.")
            return
        }

        if (currentReconnectAttempts >= maxReconnectAttempts) {
            Log.w(TAG, "Max reconnect attempts reached ($maxReconnectAttempts). Giving up.")
            currentReconnectAttempts = 0 // Reset for future manual connect
            // Emit a final error if we have pending messages that couldn't be sent
            if (messageRetryQueue.isNotEmpty()) {
                scope.launch {
                    emitEvent(WebSocketEvent.Error(Exception("Connection lost. Please check your internet connection and try again.")))
                }
                // Don't clear retry queue on connection errors - messages should be retried when reconnecting
                Log.d(TAG, "Connection lost. Preserving ${messageRetryQueue.size} messages in retry queue for reconnection")
            }
            return
        }

        reconnectJob?.cancel() // Cancel any existing job

        val delayMs = (initialReconnectDelayMs * Math.pow(reconnectDelayBackoffFactor, currentReconnectAttempts.toDouble())).toLong()
        currentReconnectAttempts++

        Log.i(TAG, "Scheduling reconnect attempt $currentReconnectAttempts/$maxReconnectAttempts in ${delayMs}ms.")
        
        reconnectJob = scope.launch {
            try {
                emitEvent(WebSocketEvent.Reconnecting) // Notify UI
                delay(delayMs)
                Log.i(TAG, "Attempting reconnect now (attempt $currentReconnectAttempts)...")
                connect(currentConversationId) // Call the main connect method with current conversation
            } catch (e: Exception) {
                Log.e(TAG, "Exception in scheduled reconnect job", e)
                // This catch is for the delay or emit itself. connect() has its own try-catch.
            }
        }
    }
} 