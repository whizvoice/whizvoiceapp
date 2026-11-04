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

        // Static flag to simulate network errors when manual disconnect is active
        @Volatile
        var simulateNetworkErrorForManualDisconnect = true

        // Callback to check if WebSocket has persistent disconnect for testing
        @Volatile
        var persistentDisconnectForTestCheck: (() -> Boolean)? = null

        // Subscription test modes
        enum class SubscriptionTestMode {
            NONE,  // Default - normal API behavior
            NO_SUBSCRIPTION,
            ACTIVE_WITH_RENEWAL,
            ACTIVE_WITH_END_DATE
        }

        @Volatile
        var subscriptionTestMode: SubscriptionTestMode = SubscriptionTestMode.NONE
    }

    // While set, GET /api/conversations/{this id}/messages answers 500. Lets a test open a chat
    // whose row exists locally while its history sync fails on the server.
    @Volatile
    var failMessagesForChatId: String? = null

    // How many message fetches were failed this way (lets a test confirm the sync was attempted)
    @Volatile
    var failedMessageFetches = 0

    fun resetErrorState() {
        failMessagesForChatId = null
        failedMessageFetches = 0
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Handle subscription API requests
        if (url.contains("/api/subscription/status") && subscriptionTestMode != SubscriptionTestMode.NONE) {
            Log.d(TAG, "Intercepting subscription status request with mode: $subscriptionTestMode")

            val responseBody = when (subscriptionTestMode) {
                SubscriptionTestMode.NO_SUBSCRIPTION -> {
                    """
                    {
                        "has_subscription": false,
                        "subscription_id": null,
                        "status": null,
                        "current_period_end": null,
                        "cancel_at_period_end": null
                    }
                    """.trimIndent()
                }
                SubscriptionTestMode.ACTIVE_WITH_RENEWAL -> {
                    val futureTimestamp = System.currentTimeMillis() / 1000 + 2592000 // 30 days from now
                    """
                    {
                        "has_subscription": true,
                        "subscription_id": "sub_test_renewal_123",
                        "status": "active",
                        "current_period_end": $futureTimestamp,
                        "cancel_at_period_end": false
                    }
                    """.trimIndent()
                }
                SubscriptionTestMode.ACTIVE_WITH_END_DATE -> {
                    val futureTimestamp = System.currentTimeMillis() / 1000 + 1296000 // 15 days from now
                    """
                    {
                        "has_subscription": true,
                        "subscription_id": "sub_test_canceled_456",
                        "status": "active",
                        "current_period_end": $futureTimestamp,
                        "cancel_at_period_end": true
                    }
                    """.trimIndent()
                }
                else -> "" // Should not happen
            }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
        }

        // Fail the message history fetch for one chat
        val failChatId = failMessagesForChatId
        if (failChatId != null && request.method == "GET" &&
            url.contains("/api/conversations/$failChatId/messages")) {
            failedMessageFetches++
            Log.d(TAG, "Simulating 500 for messages of chat $failChatId (#$failedMessageFetches)")
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("Server error occurred".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        // Simulate network failure for every API call while the WebSocket is persistently disconnected
        if (simulateNetworkErrorForManualDisconnect && persistentDisconnectForTestCheck?.invoke() == true) {
            Log.d(TAG, "WebSocket has persistent disconnect - simulating IOException for: $url")
            throw IOException("Network unavailable - WebSocket persistently disconnected for testing")
        }

        return chain.proceed(request)
    }
}
