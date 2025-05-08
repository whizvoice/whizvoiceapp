package com.example.whiz.data.remote

import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            // Use runBlocking here to get the token synchronously before creating the WebSocket
            // Prefer server token over Google token
            val serverToken = runBlocking { authRepository.serverToken.firstOrNull() }
            val authToken = runBlocking { authRepository.authToken.firstOrNull() }
            
            val tokenToUse = serverToken ?: authToken
            
            Log.d(TAG, "Attempting to connect to $WHIZ_SERVER_URL${if (tokenToUse != null) " with auth" else ""}")
            
            val requestBuilder = Request.Builder().url(WHIZ_SERVER_URL)
            
            // Add authorization header if token exists
            if (tokenToUse != null) {
                requestBuilder.header("Authorization", "Bearer $tokenToUse")
            }
            
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
                        scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text)) }
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
                        scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(t)) }
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