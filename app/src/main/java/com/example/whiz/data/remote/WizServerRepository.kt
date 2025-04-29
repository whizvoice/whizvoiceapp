package com.example.whiz.data.remote

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
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
class WizServerRepository @Inject constructor(
    private val okHttpClient: OkHttpClient // Inject OkHttpClient
) {
    private val TAG = "WizServerRepo"
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Use SharedFlow to broadcast events to collectors
    private val _webSocketEvents = MutableSharedFlow<WebSocketEvent>(replay = 0) // No replay needed
    val webSocketEvents: SharedFlow<WebSocketEvent> = _webSocketEvents.asSharedFlow()

    // Replace with your server's actual address
    private val WIZ_SERVER_URL = "ws://REDACTED_SERVER_IP:8000/chat" // 10.0.2.2 for Android emulator localhost

    fun connect() {
        if (webSocket != null) {
            Log.w(TAG, "WebSocket already connected or connecting.")
            return
        }
        Log.d(TAG, "Attempting to connect to $WIZ_SERVER_URL")
        val request = Request.Builder().url(WIZ_SERVER_URL).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connection opened.")
                scope.launch { _webSocketEvents.emit(WebSocketEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.i(TAG, "WebSocket message received: $text")
                scope.launch { _webSocketEvents.emit(WebSocketEvent.Message(text)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: Code=$code, Reason=$reason")
                webSocket.close(1000, null) // Initiate clean close
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket connection closed: Code=$code, Reason=$reason")
                this@WizServerRepository.webSocket = null // Clear reference
                scope.launch { _webSocketEvents.emit(WebSocketEvent.Closed) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failure", t)
                this@WizServerRepository.webSocket = null // Clear reference on failure
                scope.launch { _webSocketEvents.emit(WebSocketEvent.Error(t)) }
                // Optionally try to reconnect here
            }
        })
    }

    fun sendMessage(message: String): Boolean {
        return if (webSocket != null) {
            Log.d(TAG, "Sending message: $message")
            webSocket!!.send(message)
        } else {
            Log.w(TAG, "Cannot send message, WebSocket is not connected.")
            false
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket.")
        webSocket?.close(1000, "Client initiated disconnect")
        webSocket = null
    }
}