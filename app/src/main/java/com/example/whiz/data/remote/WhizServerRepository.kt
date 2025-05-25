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
import kotlinx.coroutines.runBlocking
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
    data class Message(val text: String, val requestId: String? = null) : WebSocketEvent()
    data class Error(val error: Throwable) : WebSocketEvent()
    data class AuthError(val message: String) : WebSocketEvent()
    object Closed : WebSocketEvent()
    object Connected : WebSocketEvent()
    object Reconnecting : WebSocketEvent()
}

@Singleton
class WhizServerRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val authRepository: AuthRepository
) {
    private val TAG = "WhizServerRepo"
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Use SharedFlow to broadcast events to collectors
    private val _webSocketEvents = MutableSharedFlow<WebSocketEvent>(replay = 0) // No replay needed
    val webSocketEvents: SharedFlow<WebSocketEvent> = _webSocketEvents.asSharedFlow()

    // Replace with your server's actual address
    private val WHIZ_SERVER_URL = "wss://whizvoice.com/ws/chat" // Using secure WebSocket with domain

    // Reconnection logic parameters
    private var currentReconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val initialReconnectDelayMs = 1000L // 1 second
    private val reconnectDelayBackoffFactor = 2.0
    private var reconnectJob: Job? = null
    private var isManuallyDisconnected = false // Flag to prevent reconnect on manual disconnect

    fun connect(conversationId: Long? = null) {
        if (webSocket != null && webSocket?.send("") == true) { // Crude check if socket is still valid
            Log.w(TAG, "WebSocket already connected or connecting and seems active.")
            // If successfully connected, reset manual disconnect flag
            isManuallyDisconnected = false
            currentReconnectAttempts = 0 // Reset attempts on explicit connect call if it implies success
            reconnectJob?.cancel() // Cancel any pending reconnect job
            return
        }
        // If webSocket is not null but check failed, or webSocket is null:
        Log.d(TAG, "Proceeding with connect(). Current webSocket state: ${if (webSocket == null) "null" else "exists but check failed"}")
        isManuallyDisconnected = false // Reset this flag on any attempt to connect
        reconnectJob?.cancel() // Cancel any pending reconnect job before attempting a new connection

        try {
            // Only use server token, no fallback to Google token
            val serverToken = runBlocking { 
                withContext(Dispatchers.IO) {
                    authRepository.serverToken.firstOrNull()
                }
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
                            
                            // Check if this is a regular response with message content
                            if (jsonObject.has("response") && jsonObject.has("request_id")) {
                                val responseText = jsonObject.getString("response")
                                Log.d(TAG, "Received structured response with request_id: $requestId")
                                scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(responseText, requestId)) }
                                messageHandled = true
                            }
                            // Handle structured errors (existing logic)
                            if (jsonObject.optString("type") == "error") {
                                val errorCode = jsonObject.optString("code")
                                val errorMessage = jsonObject.optString("message", "An unknown error occurred.")
                                
                                // Handle WebSocket-level authentication errors by emitting WebSocketEvent.AuthError
                                if (errorCode == "AUTH_JWT_INVALID" || errorCode == "AUTH_GENERAL_ERROR" || 
                                    errorCode == "AUTH_TOKEN_MISSING" || errorCode == "AUTH_USER_ID_MISSING") {
                                    Log.w(TAG, "Received structured WebSocket auth error: $errorCode - $errorMessage")
                                    scope.launch { _webSocketEvents.emit(WebSocketEvent.AuthError(errorMessage)) }
                                    messageHandled = true
                                } 
                                // Handle specific service-level errors (like Claude or Asana auth failures)
                                // by passing the original JSON message through as WebSocketEvent.Message
                                // so ChatViewModel can parse it and show the appropriate dialog.
                                else if (errorCode == "CLAUDE_AUTHENTICATION_ERROR" || 
                                         errorCode == "CLAUDE_API_KEY_MISSING" || 
                                         errorCode == "ASANA_AUTH_ERROR" || // Assuming Asana errors also use "ASANA_AUTH_ERROR" code
                                         errorCode == "AsanaAuthErrorHandled") { // Or the one from app.py StopIteration
                                    Log.i(TAG, "Received structured service error JSON: $errorCode. Passing as WebSocketEvent.Message.")
                                    scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text, requestId)) }
                                    messageHandled = true
                                }
                                // For other structured errors that aren't WebSocket auth or known service errors,
                                // also pass them as a Message event for ChatViewModel to potentially display.
                                else if (jsonObject.has("error")) { // A generic structured error from the server
                                    Log.w(TAG, "Received other structured error JSON: $errorCode. Passing as WebSocketEvent.Message.")
                                    scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text, requestId)) }
                                    messageHandled = true
                                }
                                // If it's a JSON error but not one of the above, it's unexpected or a new type.
                                // Log it and decide if it should be an Error event or Message event.
                                // For now, let it fall through if not explicitly handled as Message or AuthError.
                                else {
                                     Log.w(TAG, "Received unhandled structured JSON error type: $errorCode. Current default is to pass as Message.")
                                     scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text, requestId)) } // Default for unknown JSON errors
                                     messageHandled = true
                                }
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
                                scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text, requestId)) }
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

    fun sendMessage(message: String, requestId: String): Boolean {
        return try {
            if (webSocket != null) {
                // Send structured JSON with request ID
                val messageJson = org.json.JSONObject().apply {
                    put("message", message)
                    put("request_id", requestId)
                }
                val jsonMessage = messageJson.toString()
                Log.d(TAG, "Sending structured message: $jsonMessage")
                webSocket!!.send(jsonMessage)
                true
            } else {
                Log.w(TAG, "Cannot send message, WebSocket is not connected.")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            false
        }
    }

    fun disconnect() {
        try {
            Log.d(TAG, "Disconnecting WebSocket manually.")
            isManuallyDisconnected = true // Set flag to prevent auto-reconnect
            reconnectJob?.cancel() // Cancel any pending reconnect attempts
            currentReconnectAttempts = 0 // Reset attempts
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
                connect() // Call the main connect method
            } catch (e: Exception) {
                Log.e(TAG, "Exception in scheduled reconnect job", e)
                // This catch is for the delay or emit itself. connect() has its own try-catch.
            }
        }
    }
} 