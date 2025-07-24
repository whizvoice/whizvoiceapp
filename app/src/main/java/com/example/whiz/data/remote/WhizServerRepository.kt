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


sealed class WebSocketEvent {
    data class Message(val text: String, val requestId: String? = null, val conversationId: Long? = null) : WebSocketEvent()
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

@Singleton
class WhizServerRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: AuthRepository
) {
    private val TAG = "WhizServerRepo"
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Use SharedFlow to broadcast events to collectors
    private val _webSocketEvents = MutableSharedFlow<WebSocketEvent>(replay = 1) // Keep last event for connection state tracking
    val webSocketEvents: SharedFlow<WebSocketEvent> = _webSocketEvents.asSharedFlow()

    // Replace with your server's actual address
    private val WHIZ_SERVER_URL = "wss://whizvoice.com/ws/chat" // Using secure WebSocket with domain

    // Reconnection logic parameters
    private var currentReconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val initialReconnectDelayMs = 1000L
    private val reconnectDelayBackoffFactor = 2.0
    private var reconnectJob: Job? = null
    private var isManuallyDisconnected = false // Flag to prevent reconnect on manual disconnect

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

    suspend fun connect(conversationId: Long? = null) {
        currentConversationId = conversationId
        
        // 🔧 FIXED: Improved WebSocket connection state checking
        // Don't use the crude send("") check which can cause message loss
        if (webSocket != null && _webSocketEvents.replayCache.lastOrNull() is WebSocketEvent.Connected) {
            Log.w(TAG, "WebSocket already connected based on connection state.")
            // If successfully connected, reset manual disconnect flag
            isManuallyDisconnected = false
            currentReconnectAttempts = 0 // Reset attempts on explicit connect call if it implies success
            reconnectJob?.cancel() // Cancel any pending reconnect job
            // Process any queued messages when reconnected
            processRetryQueue()
            return
        }
        // If webSocket is not null but connection state is not Connected, proceed with new connection
        Log.d(TAG, "Proceeding with connect(). Current webSocket state: ${if (webSocket == null) "null" else "exists but not confirmed connected"}")
        isManuallyDisconnected = false // Reset this flag on any attempt to connect
        reconnectJob?.cancel() // Cancel any pending reconnect job before attempting a new connection

        try {
            // Only use server token, no fallback to Google token  
            val serverToken = withContext(Dispatchers.IO) {
                authRepository.serverToken.firstOrNull()
            }
            
            if (serverToken == null) {
                Log.w(TAG, "No server token available for WebSocket connection")
                scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(Exception("Authentication required. Please log in again."))) }
                return
            }
            
            // Build WebSocket URL with conversation_id parameter if provided
            val websocketUrl = if (conversationId != null && conversationId > 0) {
                "$WHIZ_SERVER_URL?conversation_id=$conversationId"
            } else {
                WHIZ_SERVER_URL
            }
            
            Log.d(TAG, "Attempting to connect to $websocketUrl with server token (conversation_id: $conversationId)")
            
            val requestBuilder = Request.Builder().url(websocketUrl)
            requestBuilder.header("Authorization", "Bearer $serverToken")
            
            val request = requestBuilder.build()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        Log.i(TAG, "WebSocket connection opened.")
                        currentReconnectAttempts = 0 // Reset on successful open
                        reconnectJob?.cancel() // Cancel any pending reconnect job
                        isManuallyDisconnected = false // Reset flag
                        scope.launch { _webSocketEvents.emit(WebSocketEvent.Connected) }

                        // Process any queued messages when reconnected
                        processRetryQueue()

                        // Send timezone via API endpoint after connection is established
                        val timezone = java.util.TimeZone.getDefault().id
                        scope.launch {
                            try {
                                val success = authRepository.setUserTimezone(timezone)
                                if (success) {
                                    Log.i(TAG, "Successfully set timezone via API: $timezone")
                                } else {
                                    Log.w(TAG, "Failed to set timezone via API: $timezone")
                                    // Optionally, send an error event to the UI or retry
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error setting timezone via API", e)
                                // Optionally, send an error event to the UI or retry
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onOpen", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        Log.i(TAG, "WebSocket message received: $text")
                        
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
                                Log.d(TAG, "Received cancellation confirmation with request_id: $requestId")
                                val cancelledRequestId = if (jsonObject.has("cancelled_request_id")) {
                                    jsonObject.getString("cancelled_request_id")
                                } else null
                                
                                if (cancelledRequestId != null) {
                                    scope.launch { _webSocketEvents.emit(WebSocketEvent.Cancelled(cancelledRequestId, requestId)) }
                                } else {
                                    Log.w(TAG, "Received cancellation confirmation without cancelled_request_id")
                                    scope.launch { _webSocketEvents.emit(WebSocketEvent.Cancelled("unknown", requestId)) }
                                }
                                messageHandled = true
                            }
                            // Check if this is an interruption confirmation
                            else if (jsonObject.has("type") && jsonObject.getString("type") == "interrupted") {
                                Log.d(TAG, "Received interruption confirmation with request_id: $requestId")
                                val interruptedMessage = if (jsonObject.has("message")) {
                                    jsonObject.getString("message")
                                } else "Request was interrupted"
                                
                                scope.launch { _webSocketEvents.emit(WebSocketEvent.Interrupted(interruptedMessage, requestId)) }
                                messageHandled = true
                            }
                            // Handle structured errors with request_id
                            else if (jsonObject.has("error")) {
                                val errorMessage = jsonObject.getString("error")
                                Log.d(TAG, "Received structured error with request_id: $requestId, error: $errorMessage")
                                
                                // Emit the error message as a regular message for ChatViewModel to handle
                                scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(errorMessage, requestId)) }
                                messageHandled = true
                            }
                            // Handle normal structured response with request_id and conversation_id
                            else if (jsonObject.has("response")) {
                                val responseText = jsonObject.getString("response")
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                Log.d(TAG, "Received structured response with request_id: $requestId, conversation_id: $conversationId")
                                val emitStartTime = System.currentTimeMillis()
                                scope.launch { 
                                    _webSocketEvents.emit(WebSocketEvent.Message(responseText, requestId, conversationId))
                                    val emitEndTime = System.currentTimeMillis()
                                    val emitDuration = emitEndTime - emitStartTime
                                    if (emitDuration > 50) {
                                        Log.w(TAG, "⚠️ WebSocket emit delay: ${emitDuration}ms for structured response")
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
                                Log.w(TAG, "Received plain text legacy auth error: 'Authentication failed. Please login again.'")
                                scope.launch { _webSocketEvents.emit(WebSocketEvent.AuthError("Authentication failed. Please login again.")) }
                            } else {
                                // Default for any unhandled or plain text message
                                Log.d(TAG, "Emitting as generic WebSocketEvent.Message: $text")
                                val emitStartTime = System.currentTimeMillis()
                                scope.launch { 
                                    _webSocketEvents.emit(WebSocketEvent.Message(text, requestId))
                                    val emitEndTime = System.currentTimeMillis()
                                    val emitDuration = emitEndTime - emitStartTime
                                    if (emitDuration > 50) {
                                        Log.w(TAG, "⚠️ WebSocket emit delay: ${emitDuration}ms for generic message")
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
                        Log.i(TAG, "WebSocket connection closed: Code=$code, Reason=$reason")
                        this@WhizServerRepository.webSocket = null // Clear reference
                        scope.launch { _webSocketEvents.emit(WebSocketEvent.Closed) }
                        
                        // Attempt to reconnect if not manually disconnected and not a specific "do not retry" code
                        // Example: code 1000 is normal closure, 1008 is policy violation (likely auth)
                        if (!isManuallyDisconnected && code != 1008 && code != 1011) { 
                            scheduleReconnect()
                        } else {
                             Log.i(TAG, "Not attempting reconnect. isManuallyDisconnected=$isManuallyDisconnected, code=$code")
                             currentReconnectAttempts = 0 // Reset if not retrying
                             reconnectJob?.cancel()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onClosed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try {
                        Log.e(TAG, "WebSocket connection failure", t)
                        this@WhizServerRepository.webSocket = null // Clear reference on failure
                        
                        val isAuthFailure = response?.code == 401 || t.message?.contains("Authentication failed", ignoreCase = true) == true
                        
                        if (isAuthFailure) {
                             val userMessage = "Authentication failed. Please log in again."
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.AuthError(userMessage)) }
                            currentReconnectAttempts = 0 // Don't retry on explicit auth failure
                            reconnectJob?.cancel()
                            isManuallyDisconnected = true // Treat as a state requiring manual intervention (login)
                        } else {
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(t)) }
                            if (!isManuallyDisconnected) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WebSocket connection", e)
            scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(e)) }
        }
    }

    fun sendMessage(message: String, requestId: String, clientConversationId: Long? = null, clientMessageId: String? = null): Boolean {
        return try {
            val currentSocket = webSocket
            if (currentSocket != null && !isManuallyDisconnected) {
                // 🔧 ENHANCED: Check connection state before sending
                val lastEvent = _webSocketEvents.replayCache.lastOrNull()
                if (lastEvent !is WebSocketEvent.Connected) {
                    Log.w(TAG, "WebSocket exists but last state is not Connected: $lastEvent - queueing message for retry")
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
                Log.d(TAG, "Sending structured message: $jsonMessage")
                
                // Try to send the message
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    Log.d(TAG, "Message sent successfully to WebSocket")
                    true
                } else {
                    Log.w(TAG, "WebSocket.send() returned false - queueing message for retry")
                    queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
                    false
                }
            } else {
                Log.w(TAG, "Cannot send message, WebSocket is not connected - queueing message for retry")
                queueMessageForRetry(message, requestId, currentConversationId, clientConversationId, clientMessageId)
                // Attempt to reconnect
                if (!isManuallyDisconnected) {
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
        
        if (currentSocket == null || isManuallyDisconnected) {
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
                            _webSocketEvents.emit(WebSocketEvent.Error(Exception("Failed to send message after $maxMessageRetries attempts: ${pendingMessage.message}")))
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
                        _webSocketEvents.emit(WebSocketEvent.Error(Exception("Failed to send message after $maxMessageRetries attempts: ${pendingMessage.message}")))
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

    fun disconnect() {
        try {
            Log.d(TAG, "Disconnecting WebSocket manually.")
            isManuallyDisconnected = true // Set flag to prevent auto-reconnect
            reconnectJob?.cancel() // Cancel any pending reconnect attempts
            retryJob?.cancel() // Cancel any pending retry attempts
            currentReconnectAttempts = 0 // Reset attempts
            messageRetryQueue.clear() // Clear retry queue
            webSocket?.close(1000, "Client initiated disconnect")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
    }

    private fun scheduleReconnect() {
        if (isManuallyDisconnected) {
            Log.i(TAG, "Reconnect scheduling aborted: manually disconnected.")
            return
        }

        if (currentReconnectAttempts >= maxReconnectAttempts) {
            Log.w(TAG, "Max reconnect attempts reached ($maxReconnectAttempts). Giving up.")
            currentReconnectAttempts = 0 // Reset for future manual connect
            // Emit a final error if we have pending messages that couldn't be sent
            if (messageRetryQueue.isNotEmpty()) {
                scope.launch {
                    _webSocketEvents.emit(WebSocketEvent.Error(Exception("Connection lost. Please check your internet connection and try again.")))
                }
                messageRetryQueue.clear()
            }
            return
        }

        reconnectJob?.cancel() // Cancel any existing job

        val delayMs = (initialReconnectDelayMs * Math.pow(reconnectDelayBackoffFactor, currentReconnectAttempts.toDouble())).toLong()
        currentReconnectAttempts++

        Log.i(TAG, "Scheduling reconnect attempt $currentReconnectAttempts/$maxReconnectAttempts in ${delayMs}ms.")
        
        reconnectJob = scope.launch {
            try {
                _webSocketEvents.emit(WebSocketEvent.Reconnecting) // Notify UI
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