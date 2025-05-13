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

sealed class WebSocketEvent {
    data class Message(val text: String) : WebSocketEvent()
    data class Error(val error: Throwable) : WebSocketEvent()
    data class AuthError(val message: String) : WebSocketEvent()
    object Closed : WebSocketEvent()
    object Connected : WebSocketEvent()
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

    fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "WebSocket already connected or connecting.")
            return
        }
        
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
            
            Log.d(TAG, "Attempting to connect to $WHIZ_SERVER_URL with server token")
            
            val requestBuilder = Request.Builder().url(WHIZ_SERVER_URL)
            requestBuilder.header("Authorization", "Bearer $serverToken")
            
            val request = requestBuilder.build()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        Log.i(TAG, "WebSocket connection opened.")
                        scope.launch { _webSocketEvents.emit(WebSocketEvent.Connected) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onOpen", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        Log.i(TAG, "WebSocket message received: $text")
                        // Check for specific auth-related error messages from the backend
                        if (text.contains("invalid x-api-key", ignoreCase = true) ||
                            text.contains("Claude API key not found", ignoreCase = true)) {
                            val userMessage = "Authentication error: Invalid or missing API key. Please check Settings."
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.AuthError(userMessage)) }
                        } else {
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text)) }
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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in onClosed", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    try {
                        Log.e(TAG, "WebSocket connection failure", t)
                        this@WhizServerRepository.webSocket = null // Clear reference on failure
                        // Check if the error is due to general authentication failure (e.g., bad server token)
                        if (response?.code == 401 || t.message?.contains("Authentication failed", ignoreCase = true) == true) {
                             val userMessage = "Authentication failed. Please log in again."
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.AuthError(userMessage)) }
                        } else {
                            scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(t)) }
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

    fun sendMessage(message: String): Boolean {
        return try {
            if (webSocket != null) {
                Log.d(TAG, "Sending message: $message")
                webSocket!!.send(message)
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
            Log.d(TAG, "Disconnecting WebSocket.")
            webSocket?.close(1000, "Client initiated disconnect")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting WebSocket", e)
        }
    }
} 