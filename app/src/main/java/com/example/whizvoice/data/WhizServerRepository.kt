import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.example.whiz.data.remote.WebSocketEvent

class WhizServerRepository {
    private val _webSocketEvents = MutableSharedFlow<WebSocketEvent>()

    fun connect() {
        // Implementation of connect function
    }

    fun onMessage(text: String) {
        Log.d("WhizServerRepository", "onMessage: $text")
        if (text.contains("invalid x-api-key", ignoreCase = true)) {
            _webSocketEvents.tryEmit(WebSocketEvent.AuthError("Invalid API Key"))
        } else if (text.contains("Claude API key not found", ignoreCase = true)) {
            _webSocketEvents.tryEmit(WebSocketEvent.AuthError("Claude API key not found. Please set it up in settings."))
        } else if (text.contains("Asana access token isn't set up", ignoreCase = true)) {
            _webSocketEvents.tryEmit(WebSocketEvent.AuthError("Asana access token isn't set up. Please set it up in settings."))
        } else if (text.contains("Authentication failed. Please login again.", ignoreCase = true)) {
            _webSocketEvents.tryEmit(WebSocketEvent.AuthError("Authentication failed. Please login again."))
        } else {
            _webSocketEvents.tryEmit(WebSocketEvent.Message(text))
        }
    }

    fun observeEvents(): SharedFlow<WebSocketEvent> = _webSocketEvents
} 