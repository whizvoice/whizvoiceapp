package com.example.whiz.data.remote

import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.ConnectionStateManager
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
import java.io.IOException
import kotlinx.coroutines.CancellationException


sealed class WebSocketEvent {
    data class Message(
        val text: String, 
        val requestId: String? = null, 
        val conversationId: Long? = null,
        val clientConversationId: Long? = null
    ) : WebSocketEvent()
    data class ToolExecution(
        val toolRequest: org.json.JSONObject,
        val requestId: String? = null,
        val conversationId: Long? = null
    ) : WebSocketEvent()
    data class Error(val error: Throwable) : WebSocketEvent()
    data class AuthError(val message: String) : WebSocketEvent()
    data class Cancelled(val cancelledRequestId: String, val requestId: String? = null) : WebSocketEvent()
    data class DeleteMessage(val messageId: Long, val conversationId: Long, val requestId: String?, val reason: String?) : WebSocketEvent()
    object Closed : WebSocketEvent()
    object Connected : WebSocketEvent()
    object Reconnecting : WebSocketEvent()
}

// Data class for message retry queue
data class PendingMessage(
    val message: String,
    val requestId: String,
    val chatId: Long,
    val clientMessageId: String? = null,
    val timestamp: Long? = null,
    val retryCount: Int = 0
)

// Debug event tracking
data class TimestampedEvent(
    val event: WebSocketEvent,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class WhizServerRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: AuthRepository,
    private val connectionStateManager: ConnectionStateManager
) {
    private val TAG = "com.example.whiz.WebSocket"
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
    
    // Generation tracking for non-blocking connection management
    // Each connection attempt gets a unique generation number
    // Callbacks from old connections are ignored based on generation mismatch
    private val connectionGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var currentGeneration = 0

    // Track the conversation ID we're currently connecting to (needed during CONNECTING state
    // when webSocket reference is null but we need to know what we're connecting to for migration checks)
    private var connectingToConversationId: Long? = null
    
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

    /**
     * Convert epoch milliseconds to ISO timestamp string with exactly 3 decimal places.
     * This ensures consistent format that Python's datetime.fromisoformat() can parse.
     * Format: "2025-12-17T02:10:24.400Z" (always 3 decimal places)
     */
    private fun formatTimestamp(epochMillis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMillis)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
        return formatter.format(instant)
    }

    // Expose persistent disconnect state for testing
    fun persistentDisconnectForTest(): Boolean {
        return persistentDisconnectForTest
    }
    
    // Allow tests to reset the persistent disconnect flag to simulate network restoration
    fun resetPersistentDisconnectForTest() {
        Log.d(TAG, "Test: Resetting persistentDisconnectForTest flag to simulate network restoration")
        persistentDisconnectForTest = false
    }

    /**
     * Reset connection state for testing. Called between tests to ensure clean state.
     * This resets all internal state and the persistentDisconnectForTest flag.
     */
    suspend fun resetConnectionStateForTesting() {
        connectionLock.withLock {
            Log.d(TAG, "resetConnectionStateForTesting: Resetting from state=$connectionState, persistentDisconnect=$persistentDisconnectForTest")

            // Cancel any pending jobs
            connectionTimeoutJob?.cancel()
            reconnectJob?.cancel()
            retryJob?.cancel()

            // Close existing WebSocket if any
            webSocket?.let { ws ->
                try {
                    ws.close(1000, "Test cleanup")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing WebSocket during test cleanup", e)
                }
            }
            webSocket = null

            // Reset all connection state
            connectionState = ConnectionState.IDLE
            connectingToConversationId = null
            persistentDisconnectForTest = false
            currentReconnectAttempts = 0

            Log.d(TAG, "resetConnectionStateForTesting: Complete")
        }
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
            is WebSocketEvent.ToolExecution,
            is WebSocketEvent.Error,
            is WebSocketEvent.AuthError,
            is WebSocketEvent.Cancelled,
            is WebSocketEvent.DeleteMessage -> {
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
        Log.d(tag, "Current state: connectionState=$connectionState, isConnected=${isConnected()}, webSocket=${if(webSocket != null) "exists" else "null"}, persistentDisconnect=$persistentDisconnectForTest")
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
                is WebSocketEvent.ToolExecution -> "TOOL_EXECUTION(requestId=${e.requestId}): ${e.toolRequest.optString("tool")}"
                is WebSocketEvent.Cancelled -> "CANCELLED(requestId=${e.cancelledRequestId})"
                is WebSocketEvent.DeleteMessage -> "DELETE_MESSAGE(requestId=${e.requestId}, messageId=${e.messageId})"
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
    // Removed currentConversationId - using chatId parameter directly for single source of truth

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
    
    /**
     * Returns true if the retry queue is empty, false otherwise.
     */
    fun isRetryQueueEmpty(): Boolean {
        return messageRetryQueue.isEmpty()
    }
    
    /**
     * Filter out function call XML blocks from message content.
     * These XML blocks are internal tool call structures that should not be displayed to users.
     * They typically look like: <function_calls>...</function_calls> or <function_result>...</function_result>
     */
    private fun filterToolCallXML(content: String): String {
        var filtered = content
        
        // Remove function_calls blocks
        filtered = filtered.replace(Regex("<function_calls>.*?</function_calls>", RegexOption.DOT_MATCHES_ALL), "")
        
        // Remove function_result blocks  
        filtered = filtered.replace(Regex("<function_result>.*?</function_result>", RegexOption.DOT_MATCHES_ALL), "")
        
        // Clean up any extra whitespace left behind
        filtered = filtered.replace(Regex("\n{3,}"), "\n\n").trim()
        
        return filtered
    }
    
    /**
     * Extract the conversation ID from the current WebSocket connection's URL
     * Checks for both conversation_id (positive) and client_conversation_id (negative) parameters
     */
    private fun getConnectedConversationId(): Long? {
        val url = webSocket?.request()?.url?.toString() ?: return null
        
        // First check for conversation_id (server-backed chat)
        val conversationIdMatch = Regex("conversation_id=(\\d+)").find(url)
        if (conversationIdMatch != null) {
            return conversationIdMatch.groupValues[1].toLongOrNull()
        }
        
        // Then check for client_conversation_id (optimistic chat)
        val clientConversationIdMatch = Regex("client_conversation_id=(-\\d+)").find(url)
        return clientConversationIdMatch?.groupValues?.get(1)?.toLongOrNull()
    }
    
    suspend fun connect(conversationId: Long? = null, turnOffPersistentDisconnect: Boolean = false) {
        Log.d(TAG, "connect() called with conversationId=$conversationId, turnOffPersistentDisconnect=$turnOffPersistentDisconnect, currentPersistentDisconnect=$persistentDisconnectForTest")
        Log.d(TAG, "🔌 WEBSOCKET CONNECT CALLED - conversationId: $conversationId", Exception("connect() stack trace"))

        // If we're just resetting the flag without providing a conversation ID, only reset the flag and return
        if (turnOffPersistentDisconnect && conversationId == null) {
            if (persistentDisconnectForTest) {
                Log.d(TAG, "Resetting persistentDisconnectForTest flag only - no reconnection attempt")
                persistentDisconnectForTest = false
                return
            }
        }

        // Check if persistent disconnect is active and we're not explicitly turning it off
        if (!turnOffPersistentDisconnect) {
            if (persistentDisconnectForTest) {
                Log.d(TAG, "Connection blocked by persistentDisconnectForTest flag - simulating network unavailable")
            }
            if (conversationId == null) {
                Log.d(TAG, "NULL conversation ID, returning without attempting connection.")
                return
            }
        }

        // Increment generation for this new connection attempt
        val thisGeneration = connectionGeneration.incrementAndGet()

        // Use lock to ensure thread-safe connection state management
        connectionLock.withLock {
            // Reset persistent disconnect flag if requested
            if (turnOffPersistentDisconnect) {
                if (persistentDisconnectForTest) {
                    Log.d(TAG, "Resetting persistentDisconnectForTest flag from $persistentDisconnectForTest to false")
                    persistentDisconnectForTest = false
                } else {
                    Log.d(TAG, "Tried to reset persistentDisconnectForTest flag from $persistentDisconnectForTest to false but it was already false")
                }
            }
            
            // Validate that conversationId is not null (except when just resetting the flag)
            // In production, we should always have a valid conversation ID (either server-backed or optimistic)
            if (conversationId == null && !turnOffPersistentDisconnect) {
                val errorMsg = "connect() called with null conversationId - this should not happen in production. " +
                               "All chats should have either a server-backed ID (>0) or optimistic ID (<-1). " +
                               "Current state: connectionState=$connectionState"
                Log.e(TAG, errorMsg)
                throw IllegalArgumentException(errorMsg)
            }
            
            // Check if we're already connected/connecting
            if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
                // Get the conversation ID we're currently connected/connecting to
                // Use getConnectedConversationId() for CONNECTED state (from websocket URL)
                // Use connectingToConversationId for CONNECTING state (websocket not yet assigned)
                val currentConversationId = getConnectedConversationId() ?: connectingToConversationId

                if (connectionState == ConnectionState.CONNECTED && currentConversationId == conversationId) {
                    Log.d(TAG, "Already connected to conversation $conversationId - no need to reconnect")
                    return
                }

                // Check if this is just a migration from optimistic to real ID
                // Case 1: Migration already registered
                // Case 2: Currently connected/connecting to optimistic ID and being asked to connect to a positive ID
                //         (migration may not be registered yet, but server handles routing via Redis)
                // Note: Must check BOTH CONNECTED and CONNECTING states to handle race condition where
                //       server responds with real ID before the optimistic connection fully opens
                if (currentConversationId != null && conversationId != null &&
                    (connectionStateManager.areChatsMigrated(currentConversationId, conversationId) ||
                     (currentConversationId < 0 && conversationId > 0))) {
                    Log.d(TAG, "Migration from $currentConversationId to $conversationId - keeping connection alive (server handles routing)")
                    return
                }

                // Different conversation or still connecting - close and reconnect
                Log.d(TAG, "Currently ${connectionState.name.lowercase()} to conversation $currentConversationId, need to connect to $conversationId - closing old connection")
                // Continue below to close old connection and open new one
            }
            
            // Store the generation for this connection
            currentGeneration = thisGeneration
            Log.d(TAG, "Starting new connection with generation $thisGeneration")
            
            // If there's an existing connection, disconnect it asynchronously
            val oldWebSocket = webSocket
            val oldGeneration = thisGeneration - 1
            if (oldWebSocket != null) {
                Log.d(TAG, "Disconnecting old connection (generation $oldGeneration) asynchronously")
                // Disconnect old connection in background - non-blocking
                scope.launch {
                    try {
                        oldWebSocket.close(1000, "New connection initiated")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing old WebSocket", e)
                    }
                }
                // Clear the reference immediately
                webSocket = null
            }
            
            // NO WAITING - proceed immediately with new connection
            // The generation tracking will ensure old callbacks are ignored

            // Update state to CONNECTING and track the conversation ID we're connecting to
            connectionState = ConnectionState.CONNECTING
            connectingToConversationId = conversationId
            
            // Only reset persistent disconnect flag if explicitly requested
            if (turnOffPersistentDisconnect) {
                Log.d(TAG, "Resetting persistentDisconnectForTest from $persistentDisconnectForTest to false")
                persistentDisconnectForTest = false
            }
            
            // Don't cancel reconnect job here - it might be the one calling connect()
            // The job will complete naturally after this connect() call
            // reconnectJob?.cancel() // REMOVED to avoid self-cancellation
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
                // Connection failed due to auth
                scope.launch { emitEvent(WebSocketEvent.Error(Exception("Authentication required. Please log in again."))) }
                return
            }
            
            // Build WebSocket URL with conversation_id parameter if provided
            // Include any conversation ID except -1 (which represents "no chat")
            val websocketUrl = if (conversationId != null && conversationId != -1L) {
                "$WHIZ_SERVER_URL?conversation_id=$conversationId"
            } else {
                WHIZ_SERVER_URL
            }
            
            val requestBuilder = Request.Builder().url(websocketUrl)
            requestBuilder.header("Authorization", "Bearer $serverToken")
            
            val request = requestBuilder.build()
            Log.d(TAG, "Creating new WebSocket with URL: $websocketUrl, persistentDisconnect=$persistentDisconnectForTest")

            // Check if we should simulate connection failure for testing
            if (persistentDisconnectForTest) {
                Log.d(TAG, "Simulating WebSocket connection failure due to persistentDisconnectForTest flag")
                // Reset connection state
                connectionState = ConnectionState.IDLE
                // Throw IOException to simulate network failure - this will trigger scheduleReconnect()
                throw IOException("Network unavailable - WebSocket persistently disconnected for testing")
            }

            // Create the WebSocket first
            Log.d(TAG, "🔥 Creating WebSocket with URL: $websocketUrl, thisGeneration=$thisGeneration")
            val newWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "🔥 onOpen ENTRY - thisGeneration=$thisGeneration, currentGeneration=$currentGeneration")
                    try {
                        // Check if this callback is from the current generation
                        if (thisGeneration != currentGeneration) {
                            Log.w(TAG, "🔥 onOpen: IGNORING callback - generation mismatch! thisGen=$thisGeneration, currentGen=$currentGeneration")
                            webSocket.close(1000, "Superseded by newer connection")
                            return
                        }
                        
                        Log.i(TAG, "WebSocket connection opened for conversationId=$conversationId, generation=$thisGeneration. persistentDisconnect=$persistentDisconnectForTest")
                        
                        // CRITICAL FIX: Store the WebSocket reference FIRST before any operations that might use it
                        // This prevents race condition where processRetryQueue() runs before webSocket is assigned
                        this@WhizServerRepository.webSocket = webSocket
                        
                        // Update connection state
                        connectionState = ConnectionState.CONNECTED
                        
                        connectionTimeoutJob?.cancel() // Cancel timeout on successful connection
                        currentReconnectAttempts = 0 // Reset on successful open
                        reconnectJob?.cancel() // Cancel any pending reconnect job
                        // Don't automatically reset persistentDisconnectForTest on connection open
                        scope.launch { emitEvent(WebSocketEvent.Connected) }

                        // Process any queued messages when reconnected
                        processRetryQueue(conversationId)

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
                        Log.d(TAG, "📥 WebSocket message received: ${text.take(200)}...") // Log first 200 chars
                        
                        var messageHandled = false
                        var requestId: String? = null
                        
                        // Attempt to parse as JSON first to extract request_id and handle structured responses
                        try {
                            val jsonObject = org.json.JSONObject(text)
                            Log.d(TAG, "📥 Parsed JSON message type: ${jsonObject.optString("type", "unknown")}")
                            
                            // Extract request_id if present (could be in regular response or error)
                            requestId = if (jsonObject.has("request_id")) {
                                jsonObject.getString("request_id")
                            } else null
                            
                            // Check if this is a tool execution request
                            if (jsonObject.has("type") && jsonObject.getString("type") == "tool_execution") {
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                
                                Log.i(TAG, "🔧 TOOL EXECUTION REQUEST RECEIVED!")
                                Log.i(TAG, "🔧 Tool: ${jsonObject.optString("tool", "unknown")}")
                                Log.i(TAG, "🔧 Request ID: ${jsonObject.optString("request_id", "none")}")
                                Log.i(TAG, "🔧 Full request: ${jsonObject.toString(2)}")
                                
                                scope.launch { 
                                    Log.d(TAG, "🔧 Emitting ToolExecution event")
                                    emitEvent(WebSocketEvent.ToolExecution(jsonObject, requestId, conversationId))
                                }
                                messageHandled = true
                            }
                            // Check if this is a cancellation confirmation
                            else if (jsonObject.has("type") && jsonObject.getString("type") == "cancelled") {
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
                            // Handle delete_message notifications
                            else if (jsonObject.has("type") && jsonObject.getString("type") == "delete_message") {
                                val messageId = if (jsonObject.has("message_id")) {
                                    jsonObject.getLong("message_id")
                                } else null
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                val deleteRequestId = if (jsonObject.has("request_id")) {
                                    jsonObject.getString("request_id")
                                } else null
                                val reason = if (jsonObject.has("reason")) {
                                    jsonObject.getString("reason")
                                } else null

                                if (messageId != null && conversationId != null) {
                                    Log.d(TAG, "📥 Delete message notification: messageId=$messageId, conversationId=$conversationId, requestId=$deleteRequestId, reason=$reason")
                                    scope.launch { emitEvent(WebSocketEvent.DeleteMessage(messageId, conversationId, deleteRequestId, reason)) }
                                } else {
                                    Log.w(TAG, "Received delete_message without message_id or conversation_id")
                                }
                                messageHandled = true
                            }
                            // Handle streaming chunk messages
                            else if (jsonObject.has("type") && jsonObject.getString("type") == "stream_chunk") {
                                // Extract the content from the streaming chunk
                                var content = if (jsonObject.has("content")) {
                                    jsonObject.getString("content")
                                } else ""
                                
                                // Filter out function call XML blocks that shouldn't be displayed
                                // These are internal tool call structures that should be hidden from users
                                content = filterToolCallXML(content)
                                
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                
                                val clientConversationId = if (jsonObject.has("client_conversation_id") && !jsonObject.isNull("client_conversation_id")) {
                                    jsonObject.getLong("client_conversation_id")
                                } else null
                                
                                Log.d(TAG, "📥 Processing stream_chunk with content: ${content.take(50)}...")
                                
                                // Emit the content as a regular message, not the raw JSON
                                scope.launch { 
                                    emitEvent(WebSocketEvent.Message(content, requestId, conversationId, clientConversationId))
                                }
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
                            // This handles both "response" type and "broadcast" type messages
                            else if (jsonObject.has("response")) {
                                var responseText = jsonObject.getString("response")
                                
                                // Filter out function call XML blocks that shouldn't be displayed
                                responseText = filterToolCallXML(responseText)
                                val conversationId = if (jsonObject.has("conversation_id")) {
                                    jsonObject.getLong("conversation_id")
                                } else null
                                val clientConversationId = if (jsonObject.has("client_conversation_id") && !jsonObject.isNull("client_conversation_id")) {
                                    jsonObject.getLong("client_conversation_id")
                                } else null
                                
                                // Update ConnectionStateManager if this is a migration from optimistic to real ID
                                if (conversationId != null && conversationId > 0 && clientConversationId != null && clientConversationId < 0) {
                                    val lastActiveId = connectionStateManager.getLastActiveConversationId()
                                    if (lastActiveId == clientConversationId) {
                                        Log.d(TAG, "Updating ConnectionStateManager: migration from $clientConversationId to $conversationId")
                                        // Register migration FIRST so areChatsMigrated() check in connect() will work
                                        connectionStateManager.registerChatMigration(clientConversationId, conversationId)
                                        connectionStateManager.setActiveConversation(conversationId)
                                    }
                                }
                                
                                // Log the message type for debugging
                                val messageType = if (jsonObject.has("type")) {
                                    jsonObject.getString("type")
                                } else "unknown"
                                Log.d(TAG, "Handling message type: $messageType with response field")
                                
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
                            Log.d(TAG, "JSON parsing failed for message: ${e.message}. Raw text: ${text.take(200)}")
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
                    Log.d(TAG, "🔥 onClosed ENTRY - thisGeneration=$thisGeneration, currentGeneration=$currentGeneration, code=$code, reason=$reason")
                    try {
                        // Check if this callback is from the current generation
                        if (thisGeneration != currentGeneration) {
                            Log.d(TAG, "onClosed: Callback from old generation $thisGeneration (current: $currentGeneration)")
                            // For test disconnects with persistentDisconnectForTest, still schedule reconnect
                            // This ensures retry mechanism continues even during test-induced disconnections
                            if (persistentDisconnectForTest && code == 1000) {
                                Log.d(TAG, "Test disconnect detected - scheduling reconnect attempts")
                                scheduleReconnect()
                            }
                            return
                        }
                        
                        Log.i(TAG, "WebSocket connection closed: Code=$code, Reason=$reason, generation=$thisGeneration, persistentDisconnect=$persistentDisconnectForTest")
                        
                        // Update connection state only if this is the current generation
                        connectionState = ConnectionState.IDLE
                        // Connection closed
                        
                        connectionTimeoutJob?.cancel() // Cancel timeout on close
                        this@WhizServerRepository.webSocket = null // Clear reference
                        scope.launch { emitEvent(WebSocketEvent.Closed) }
                        
                        // Attempt to reconnect if not a specific "do not retry" code
                        // Example: code 1000 is normal closure, 1008 is policy violation (likely auth)
                        // Note: We don't check persistentDisconnectForTest here - let retries continue
                        // The flag will block the actual connection attempt, simulating network failure
                        if (code != 1008 && code != 1011) { 
                            scheduleReconnect()
                        } else {
                             Log.i(TAG, "Not attempting reconnect. code=$code")
                             currentReconnectAttempts = 0 // Reset if not retrying
                             reconnectJob?.cancel()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onClosed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "🔥 onFailure ENTRY - thisGeneration=$thisGeneration, currentGeneration=$currentGeneration, error=${t.message}", t)
                    try {
                        // Check if this callback is from the current generation
                        if (thisGeneration != currentGeneration) {
                            Log.w(TAG, "🔥 onFailure: IGNORING callback - generation mismatch! thisGen=$thisGeneration, currentGen=$currentGeneration")
                            return
                        }
                        
                        Log.e(TAG, "WebSocket connection failure. generation=$thisGeneration, persistentDisconnect=$persistentDisconnectForTest, response code=${response?.code}, message=${t.message}", t)
                        
                        // Update connection state only if this is the current generation
                        connectionState = ConnectionState.IDLE
                        // Connection closed
                        
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
                            // Always attempt reconnect on failure - let the flag block at connection level
                            scheduleReconnect()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onFailure", e)
                    }
                }
            })
            
            // Store the WebSocket reference immediately (if onOpen hasn't already done it)
            // This handles the case where onOpen hasn't fired yet
            if (webSocket == null) {
                webSocket = newWebSocket
            }
            
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
                    // Connection closed
                    
                    webSocket?.cancel() // Cancel the hanging connection
                    webSocket = null
                    emitEvent(WebSocketEvent.Error(Exception("WebSocket connection timeout - server may not recognize conversation_id=$conversationId")))

                    // Always schedule reconnect after timeout - the retry mechanism should continue
                    // even during test-induced disconnections. The flag will block the actual connection
                    // attempt in connect(), but the retry loop should keep running.
                    Log.d(TAG, "Scheduling reconnect after timeout (persistentDisconnect=$persistentDisconnectForTest)")
                    scheduleReconnect()
                }
            }
            
            Log.d(TAG, "WebSocket creation initiated. Waiting for onOpen/onFailure callback...")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating WebSocket connection", e)
            // Reset connection state on error
            connectionState = ConnectionState.IDLE
            scope.launch { emitEvent(WebSocketEvent.Error(e)) }
            
            // Schedule reconnect to continue exponential backoff retry chain
            // This is crucial for network errors (including test-simulated ones)
            // so that retries continue when the network/flag is restored
            if (e is IOException) {
                Log.d(TAG, "Scheduling reconnect after network error: ${e.message}")
                scheduleReconnect()
            } else if (e is CancellationException) {
                // JobCancellationException can happen during connection failures and should retry
                // But other CancellationExceptions might be intentional cancellations
                if (e.message?.contains("was cancelled") == true) {
                    // This looks like a connection failure side effect, not intentional cancellation
                    Log.d(TAG, "Scheduling reconnect after connection cancellation: ${e.message}")
                    scheduleReconnect()
                } else {
                    Log.d(TAG, "Coroutine intentionally cancelled, not scheduling reconnect: ${e.message}")
                    // Don't retry on intentional cancellation
                }
            } else if (e.message?.contains("auth", ignoreCase = true) == true) {
                Log.w(TAG, "Not scheduling reconnect for auth error: ${e.message}")
                // Don't retry on auth errors
            } else {
                // For other exceptions, also attempt reconnect
                Log.d(TAG, "Scheduling reconnect after unexpected error: ${e.message}")
                scheduleReconnect()
            }
        }
    }

    fun sendToolResult(toolName: String, requestId: String, result: org.json.JSONObject, chatId: Long, timestamp: Long? = null): Boolean {
        Log.i(TAG, "📤📤📤 SENDING TOOL RESULT TO SERVER")
        Log.i(TAG, "📤 Tool: $toolName")
        Log.i(TAG, "📤 Request ID: $requestId")
        Log.i(TAG, "📤 Chat ID: $chatId")
        Log.i(TAG, "📤 Timestamp: $timestamp")
        Log.i(TAG, "📤 Result: ${result.toString(2)}")

        // Warn if timestamp is missing - helps debug message ordering issues
        if (timestamp == null) {
            Log.w(TAG, "⚠️ TIMESTAMP_MISSING: sendToolResult called without timestamp! requestId=$requestId, tool=$toolName")
        }
        
        return try {
            val currentSocket = webSocket
            Log.i(TAG, "📤 WebSocket status: ${if (currentSocket != null) "EXISTS" else "NULL"}")
            Log.i(TAG, "📤 Connection state: $connectionState")
            
            if (currentSocket != null && !persistentDisconnectForTest) {
                if (connectionState != ConnectionState.CONNECTED) {
                    Log.w(TAG, "❌ WebSocket exists but connection state is $connectionState - queueing tool result for retry")
                    // Queue the tool result JSON for retry using existing mechanism
                    val resultJson = org.json.JSONObject().apply {
                        put("type", "tool_result")
                        put("tool", toolName)
                        put("request_id", requestId)
                        put("result", result)
                        if (chatId > 0) {
                            put("conversation_id", chatId)
                        } else if (chatId < 0) {
                            put("client_conversation_id", chatId)
                        }
                        // Include timestamp if provided
                        timestamp?.let {
                            val isoTimestamp = formatTimestamp(it)
                            put("timestamp", isoTimestamp)
                        }
                    }
                    queueMessageForRetry(resultJson.toString(), requestId, chatId)
                    return false
                }
                
                // Send tool result as JSON
                val resultJson = org.json.JSONObject().apply {
                    put("type", "tool_result")
                    put("tool", toolName)
                    put("request_id", requestId)
                    put("result", result)

                    // Include conversation ID
                    if (chatId > 0) {
                        put("conversation_id", chatId)
                    } else if (chatId < 0) {
                        put("client_conversation_id", chatId)
                    }

                    // Include timestamp if provided
                    timestamp?.let {
                        val isoTimestamp = formatTimestamp(it)
                        put("timestamp", isoTimestamp)
                    }
                }
                val jsonMessage = resultJson.toString()
                
                Log.i(TAG, "📤 WEBSOCKET SEND: Sending tool result with requestId=$requestId")
                Log.i(TAG, "📤 JSON being sent: $jsonMessage")
                
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    Log.i(TAG, "✅✅✅ TOOL RESULT SENT SUCCESSFULLY: requestId=$requestId, tool=$toolName")
                    true
                } else {
                    Log.e(TAG, "❌❌❌ TOOL RESULT SEND FAILED: requestId=$requestId - queueing for retry")
                    queueMessageForRetry(jsonMessage, requestId, chatId)
                    false
                }
            } else {
                Log.w(TAG, "WebSocket not connected - queueing tool result for retry")
                // Queue the tool result JSON for retry
                val resultJson = org.json.JSONObject().apply {
                    put("type", "tool_result")
                    put("tool", toolName)
                    put("request_id", requestId)
                    put("result", result)
                    if (chatId > 0) {
                        put("conversation_id", chatId)
                    } else if (chatId < 0) {
                        put("client_conversation_id", chatId)
                    }
                    // Include timestamp if provided
                    timestamp?.let {
                        val isoTimestamp = formatTimestamp(it)
                        put("timestamp", isoTimestamp)
                    }
                }
                queueMessageForRetry(resultJson.toString(), requestId, chatId)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool result - queueing for retry", e)
            // Queue on exception too
            val resultJson = org.json.JSONObject().apply {
                put("type", "tool_result")
                put("tool", toolName)
                put("request_id", requestId)
                put("result", result)
                if (chatId > 0) {
                    put("conversation_id", chatId)
                } else if (chatId < 0) {
                    put("client_conversation_id", chatId)
                }
                // Include timestamp if provided
                timestamp?.let {
                    val isoTimestamp = formatTimestamp(it)
                    put("timestamp", isoTimestamp)
                }
            }
            queueMessageForRetry(resultJson.toString(), requestId, chatId)
            false
        }
    }
    
    fun sendMessage(message: String, requestId: String, chatId: Long, clientMessageId: String? = null, timestamp: Long? = null): Boolean {
        // 🔧 CRITICAL LOGGING: Log what we're about to send
        Log.d(TAG, "📤 SENDING MESSAGE: requestId=$requestId, chatId=$chatId, content='${message.take(50)}...', timestamp=$timestamp")

        // Warn if timestamp is missing - helps debug message ordering issues
        if (timestamp == null) {
            Log.w(TAG, "⚠️ TIMESTAMP_MISSING: sendMessage called without timestamp! requestId=$requestId, content='${message.take(50)}...'")
        }

        return try {
            val currentSocket = webSocket
            if (currentSocket != null && !persistentDisconnectForTest) {
                // 🔧 ENHANCED: Check connection state before sending
                if (connectionState != ConnectionState.CONNECTED) {
                    Log.w(TAG, "WebSocket exists but connection state is $connectionState - queueing message for retry")
                    queueMessageForRetry(message, requestId, chatId, clientMessageId, timestamp)
                    return false
                }

                // Check if message's chatId matches the connected WebSocket's conversation
                val connectedConvId = getConnectedConversationId()
                if (connectedConvId != null) {
                    // Resolve both IDs to handle optimistic ID migrations
                    val effectiveMessageChatId = connectionStateManager.getEffectiveChatId(chatId) ?: chatId
                    val effectiveConnectedId = connectionStateManager.getEffectiveChatId(connectedConvId) ?: connectedConvId
                    if (effectiveMessageChatId != effectiveConnectedId) {
                        Log.w(TAG, "Message chatId $chatId (effective: $effectiveMessageChatId) doesn't match connected conversation $connectedConvId (effective: $effectiveConnectedId) - queueing for retry")
                        queueMessageForRetry(message, requestId, chatId, clientMessageId, timestamp)
                        return false
                    }
                }

                // Send structured JSON with request ID and optional client context
                val messageJson = org.json.JSONObject().apply {
                    put("message", message)
                    put("request_id", requestId)
                    put("type", "message")
                    
                    // Use chatId directly - send as conversation_id if positive, client_conversation_id if negative
                    if (chatId > 0) {
                        put("conversation_id", chatId)
                    } else if (chatId < 0) {
                        put("client_conversation_id", chatId)
                    }
                    
                    clientMessageId?.let { put("client_message_id", it) }
                    
                    // Include timestamp if provided
                    timestamp?.let { 
                        // Convert milliseconds to ISO format string for server
                        val isoTimestamp = formatTimestamp(it)
                        put("timestamp", isoTimestamp)
                    }
                }
                val jsonMessage = messageJson.toString()
                
                // 🔧 CRITICAL LOGGING: Log the actual JSON being sent
                Log.d(TAG, "📤 WEBSOCKET SEND: Attempting to send message with requestId=$requestId")
                
                // Try to send the message
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    Log.d(TAG, "✅ WEBSOCKET SEND SUCCESS: requestId=$requestId, content='${message.take(50)}...'")
                    true
                } else {
                    Log.w(TAG, "❌ WEBSOCKET SEND FAILED: requestId=$requestId - queueing message for retry")
                    queueMessageForRetry(message, requestId, chatId, clientMessageId, timestamp)
                    false
                }
            } else {
                Log.w(TAG, "WebSocket not connected - queueing message for retry")
                queueMessageForRetry(message, requestId, chatId, clientMessageId, timestamp)
                
                // Attempt to reconnect if we're not in test disconnect mode
                // and if we don't already have a retry job running
                if (!persistentDisconnectForTest && connectionState != ConnectionState.CONNECTING) {
                    Log.d(TAG, "Attempting to reconnect for chat $chatId (retry job active: ${retryJob?.isActive})")
                    scope.launch {
                        delay(100L) // Small delay before reconnect
                        connect(chatId)
                    }
                } else if (persistentDisconnectForTest) {
                    Log.d(TAG, "Not reconnecting - persistent disconnect is enabled")
                } else {
                    Log.d(TAG, "Not reconnecting - already connecting")
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message - queueing for retry", e)
            queueMessageForRetry(message, requestId, chatId, clientMessageId)
            false
        }
    }

    private fun queueMessageForRetry(message: String, requestId: String, chatId: Long, clientMessageId: String? = null, timestamp: Long? = null) {
        // 🔧 CRITICAL LOGGING: Log complete message content being queued
        Log.d(TAG, "📥 QUEUEING MESSAGE FOR RETRY: requestId=$requestId, chatId=$chatId, content='$message', timestamp=$timestamp")
        val pendingMessage = PendingMessage(message, requestId, chatId, clientMessageId, timestamp)
        
        // Remove any existing message with the same requestId to avoid duplicates
        messageRetryQueue.removeAll { it.requestId == requestId }
        messageRetryQueue.add(pendingMessage)
        
        // Start retry process if not already running
        if (retryJob?.isActive != true) {
            retryJob = scope.launch {
                delay(messageRetryDelayMs)
                // Process all messages in retry queue
                processRetryQueue()
            }
        }
    }

    private fun processRetryQueue(conversationId: Long? = null) {
        // Use provided conversationId, or fall back to the WebSocket's connected conversation
        val effectiveConversationId = conversationId ?: getConnectedConversationId()

        if (messageRetryQueue.isEmpty()) {
            Log.d(TAG, "Retry queue is empty")
            return
        }

        Log.d(TAG, "Processing retry queue with ${messageRetryQueue.size} messages (effectiveConversationId=$effectiveConversationId)")
        val currentSocket = webSocket
        
        if (currentSocket == null || persistentDisconnectForTest) {
            Log.d(TAG, "Cannot process retry queue - not connected")
            return
        }
        
        // Only process messages that match the conversation we're connected to
        // Discard messages for other conversations (they'll be synced from local DB when those chats are opened)
        val allMessages = messageRetryQueue.toList()
        val messagesToRetry = if (effectiveConversationId != null) {
            allMessages.filter { msg ->
                // Resolve both the queued message's chatId and the current conversationId
                // to handle optimistic ID migrations (e.g., -1759344504803 → 5127)
                val effectiveQueuedChatId = connectionStateManager.getEffectiveChatId(msg.chatId) ?: msg.chatId
                val effectiveCurrentChatId = connectionStateManager.getEffectiveChatId(effectiveConversationId) ?: effectiveConversationId

                // Log migration resolution for debugging
                if (effectiveQueuedChatId != msg.chatId) {
                    Log.d(TAG, "Resolved queued message chatId ${msg.chatId} → $effectiveQueuedChatId")
                }
                if (effectiveCurrentChatId != effectiveConversationId) {
                    Log.d(TAG, "Resolved current conversationId $effectiveConversationId → $effectiveCurrentChatId")
                }

                effectiveQueuedChatId == effectiveCurrentChatId
            }
        } else {
            // If no conversationId specified and no WebSocket connected, process all messages
            allMessages
        }
        
        val discardedCount = allMessages.size - messagesToRetry.size
        if (discardedCount > 0) {
            Log.d(TAG, "Discarding $discardedCount messages for other conversations (will be synced from local DB when those chats are opened)")
        }
        
        if (messagesToRetry.isEmpty()) {
            Log.d(TAG, "No messages in retry queue for conversation $effectiveConversationId")
            // Clear the queue since we're discarding messages for other conversations
            messageRetryQueue.clear()
            return
        }

        Log.d(TAG, "Processing ${messagesToRetry.size} messages for conversation $effectiveConversationId")
        
        // Clear the entire queue (we're not keeping messages for other conversations)
        messageRetryQueue.clear()
        
        messagesToRetry.forEach { pendingMessage ->
            try {
                // Check if this is already a complete JSON message (e.g., tool result)
                val jsonMessage = if (pendingMessage.message.startsWith("{") && pendingMessage.message.contains("\"type\"")) {
                    // This is already a complete JSON message (like a tool result)
                    // Just use it as-is
                    pendingMessage.message
                } else {
                    // This is a regular text message, wrap it in JSON structure
                    val messageJson = org.json.JSONObject().apply {
                        put("message", pendingMessage.message)
                        put("request_id", pendingMessage.requestId)
                        put("type", "message")
                        
                        // Send as conversation_id if positive, client_conversation_id if negative
                        if (pendingMessage.chatId > 0) {
                            put("conversation_id", pendingMessage.chatId)
                        } else if (pendingMessage.chatId < 0) {
                            put("client_conversation_id", pendingMessage.chatId)
                        }
                        
                        pendingMessage.clientMessageId?.let { put("client_message_id", it) }
                        
                        // Include timestamp if provided
                        pendingMessage.timestamp?.let { 
                            // Convert milliseconds to ISO format string for server
                            val isoTimestamp = formatTimestamp(it)
                            put("timestamp", isoTimestamp)
                        }
                    }
                    messageJson.toString()
                }
                
                val success = currentSocket.send(jsonMessage)
                if (success) {
                    // 🔧 CRITICAL LOGGING: Log successful retry with full content
                    Log.d(TAG, "✅ RETRY SUCCESS: requestId=${pendingMessage.requestId}, content='${pendingMessage.message}'")
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
                // Process all messages in retry queue
                processRetryQueue()
            }
        }
    }

    fun disconnect(setPersistentDisconnect: Boolean = false) {
        try {
            Log.d(TAG, "Disconnecting WebSocket manually. setPersistentDisconnect=$setPersistentDisconnect, currentState=$connectionState, generation=$currentGeneration")
            
            // Check if already disconnected
            if (connectionState == ConnectionState.IDLE && webSocket == null) {
                Log.d(TAG, "Already disconnected, skipping disconnect")
                // When already disconnected, only update persistentDisconnectForTest if explicitly setting it to true
                // This preserves the flag when ChatViewModel calls disconnect() during navigation
                if (setPersistentDisconnect) {
                    Log.d(TAG, "Setting persistentDisconnectForTest to true even though already disconnected")
                    persistentDisconnectForTest = true
                }
                return
            }
            
            // Increment generation to invalidate any pending callbacks
            val disconnectGeneration = connectionGeneration.incrementAndGet()
            currentGeneration = disconnectGeneration
            Log.d(TAG, "Disconnect initiated with generation $disconnectGeneration")
            
            // Update state to DISCONNECTING
            connectionState = ConnectionState.DISCONNECTING
            
            // Only update the persistent disconnect flag if explicitly requested
            // When setPersistentDisconnect is false, preserve the existing value
            if (setPersistentDisconnect) {
                persistentDisconnectForTest = true
            } else {
                // Don't reset to false - preserve existing value
                Log.d(TAG, "Preserving existing persistentDisconnectForTest value: $persistentDisconnectForTest")
            }

            // Cancel any pending jobs
            connectionTimeoutJob?.cancel()

            // Only cancel reconnect attempts and reset counter when NOT simulating network loss
            // When setPersistentDisconnect=true (test simulating network down), keep reconnect attempts running
            // This allows the retry mechanism to continue in the background (blocked at connection level by the flag)
            if (!setPersistentDisconnect) {
                Log.d(TAG, "Cancelling reconnect job and resetting attempts (production disconnect)")
                reconnectJob?.cancel() // Cancel any pending reconnect attempts
                retryJob?.cancel() // Cancel any pending retry attempts
                currentReconnectAttempts = 0 // Reset attempts
            } else {
                Log.d(TAG, "Preserving reconnect job and attempts (simulating network loss)")
            }
            
            // Don't clear retry queue on manual disconnect - messages should be retried when reconnecting
            // This allows queued messages to be sent when the connection is re-established
            Log.d(TAG, "Preserving ${messageRetryQueue.size} messages in retry queue for reconnection")
            
            // Close the WebSocket
            val currentSocket = webSocket
            if (currentSocket != null) {
                currentSocket.close(1000, "Client initiated disconnect")
                // The onClosed callback will handle:
                // - Setting connectionState to IDLE
                // - Clearing the webSocket reference
                // - Emitting the Closed event
            } else {
                // No WebSocket to close, transition directly to IDLE
                connectionState = ConnectionState.IDLE
            }
            
            // Persistent disconnect just means "don't auto-reconnect"
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
    }

    private fun scheduleReconnect() {
        // Don't check persistentDisconnectForTest here - let the retry mechanism keep running
        // The actual connection attempt will be blocked in connect() if the flag is true
        // This better simulates production behavior where retries keep happening
        
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
                // Wait for the delay FIRST before notifying about reconnection
                delay(delayMs)
                Log.i(TAG, "Attempting reconnect now (attempt $currentReconnectAttempts)...")
                
                // Get the active or last active conversation ID from ConnectionStateManager
                val conversationId = connectionStateManager.activeConversationId.value 
                    ?: connectionStateManager.getLastActiveConversationId()
                
                if (conversationId != null && conversationId != -1L) {
                    Log.d(TAG, "Attempting to reconnect with conversation ID: $conversationId")
                    // Directly attempt to reconnect with the known conversation ID
                    connect(conversationId)
                } else {
                    Log.w(TAG, "No valid conversation ID available for reconnection")
                    // Still emit event so ChatViewModel can handle if it's active
                    emitEvent(WebSocketEvent.Reconnecting)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception in scheduled reconnect job", e)
                // This catch is for the delay or emit itself.
            }
        }
    }
} 