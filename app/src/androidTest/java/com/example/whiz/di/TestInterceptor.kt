package com.example.whiz.di

import android.util.Log
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import javax.inject.Inject
import javax.inject.Singleton
import com.example.whiz.data.remote.WhizServerRepository
import java.io.IOException

/**
 * Test interceptor that can simulate different HTTP responses for testing.
 * This allows us to test error handling without needing a mock server.
 */
@Singleton
class TestInterceptor @Inject constructor() : Interceptor {
    
    companion object {
        private const val TAG = "TestInterceptor"
        
        // Test chat IDs that trigger specific responses
        const val CHAT_ID_404 = 404404L
        const val CHAT_ID_401 = 401401L
        const val CHAT_ID_403 = 403403L
        const val CHAT_ID_500 = 500500L
        const val CHAT_ID_503 = 503503L
        const val CHAT_ID_TIMEOUT = 999999L
        const val CHAT_ID_SUCCESS_AFTER_ERROR = 200200L
        
        // Static flag to simulate network errors when manual disconnect is active
        @Volatile
        var simulateNetworkErrorForManualDisconnect = true
        
        // Callback to check if WebSocket is manually disconnected
        @Volatile
        var isManuallyDisconnectedCheck: (() -> Boolean)? = null
    }
    
    // Track if we should return an error for the success-after-error chat
    private var shouldReturnErrorForSuccessChat = true
    
    // Track retry attempts for specific chat IDs
    private val retryCountMap = mutableMapOf<Long, Int>()
    
    fun resetErrorState() {
        shouldReturnErrorForSuccessChat = true
        retryCountMap.clear()
    }
    
    fun resetRetryCount(chatId: Long) {
        retryCountMap.remove(chatId)
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        
        // Only intercept conversation GET requests
        if (request.method == "GET" && url.contains("/api/conversations/") && !url.contains("/messages")) {
            // Extract chat ID from URL
            val chatId = url.substringAfterLast("/conversations/").substringBefore("?").toLongOrNull()
            
            Log.d(TAG, "Intercepting GET request for chat ID: $chatId")
            
            // For specific test chat IDs, bypass the WebSocket disconnect check to allow retry logic
            if (chatId in listOf(CHAT_ID_500, CHAT_ID_SUCCESS_AFTER_ERROR)) {
                // Let the chat-specific logic below handle these cases
                Log.d(TAG, "Bypassing WebSocket disconnect check for test chat ID: $chatId")
            } else {
                // Check if WebSocket is manually disconnected - simulate network failure for all API calls
                if (simulateNetworkErrorForManualDisconnect && isManuallyDisconnectedCheck?.invoke() == true) {
                    Log.d(TAG, "WebSocket is manually disconnected - simulating IOException for: $url")
                    throw IOException("Network unavailable - WebSocket manually disconnected for testing")
                }
            }
            
            return when (chatId) {
                CHAT_ID_404 -> {
                    Log.d(TAG, "Simulating 404 Not Found for chat $chatId")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("Chat not found".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                
                CHAT_ID_401 -> {
                    Log.d(TAG, "Simulating 401 Unauthorized for chat $chatId")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("Unauthorized access".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                
                CHAT_ID_403 -> {
                    Log.d(TAG, "Simulating 403 Forbidden for chat $chatId")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("Access forbidden".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                
                CHAT_ID_500 -> {
                    // Track retry count for this chat ID
                    val retryCount = retryCountMap.getOrDefault(chatId, 0)
                    retryCountMap[chatId] = retryCount + 1
                    
                    if (retryCount == 0) {
                        // First attempt: return 500 error
                        Log.d(TAG, "First attempt: Simulating 500 Internal Server Error for chat $chatId")
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Internal Server Error")
                            .body("Server error occurred".toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        // Retry: return 404 to trigger new chat creation
                        Log.d(TAG, "Retry attempt #$retryCount: Simulating 404 Not Found for chat $chatId")
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("Chat not found after retry".toResponseBody("text/plain".toMediaType()))
                            .build()
                    }
                }
                
                CHAT_ID_503 -> {
                    Log.d(TAG, "Simulating 503 Service Unavailable for chat $chatId")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("Service temporarily unavailable".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                
                CHAT_ID_TIMEOUT -> {
                    Log.d(TAG, "Simulating timeout for chat $chatId")
                    Thread.sleep(10000) // Sleep longer than typical timeout
                    chain.proceed(request)
                }
                
                CHAT_ID_SUCCESS_AFTER_ERROR -> {
                    if (shouldReturnErrorForSuccessChat) {
                        Log.d(TAG, "First request: Simulating 500 error for chat $chatId")
                        shouldReturnErrorForSuccessChat = false
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Internal Server Error")
                            .body("Temporary error".toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        Log.d(TAG, "Retry request: Returning success for chat $chatId")
                        // Return a successful response
                        val successBody = """
                            {
                                "id": $chatId,
                                "title": "Test Chat",
                                "created_at": "${System.currentTimeMillis()}",
                                "last_message_time": ${System.currentTimeMillis()}
                            }
                        """.trimIndent()
                        
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(successBody.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
                
                else -> {
                    // For all other requests, proceed normally
                    chain.proceed(request)
                }
            }
        }
        
        // For non-chat requests, check WebSocket disconnect status
        if (simulateNetworkErrorForManualDisconnect && isManuallyDisconnectedCheck?.invoke() == true) {
            // But still bypass for specific test chat IDs that might be in the URL
            val chatIdInUrl = if (url.contains("/conversations/")) {
                url.substringAfter("/conversations/").substringBefore("/").substringBefore("?").toLongOrNull()
            } else {
                null
            }
            
            if (chatIdInUrl !in listOf(CHAT_ID_500, CHAT_ID_SUCCESS_AFTER_ERROR)) {
                Log.d(TAG, "WebSocket is manually disconnected - simulating IOException for: $url")
                throw IOException("Network unavailable - WebSocket manually disconnected for testing")
            }
        }
        
        // For non-chat requests, proceed normally
        return chain.proceed(request)
    }
}