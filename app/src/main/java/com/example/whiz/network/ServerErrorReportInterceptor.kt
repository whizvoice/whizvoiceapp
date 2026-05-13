package com.example.whiz.network

import android.util.Log
import com.example.whiz.services.BugReportSubmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Tracks (method+path+status) keys we've already reported this process lifetime.
 * Reset on process death — matches the "per session" dedup the user wanted.
 */
@Singleton
class ServerErrorReportTracker @Inject constructor() {
    private val seen = mutableSetOf<String>()
    private val lock = Any()

    fun shouldReport(key: String): Boolean {
        synchronized(lock) {
            return seen.add(key)
        }
    }
}

/**
 * Auto-reports HTTP 5xx responses from the whizvoice server by routing them
 * through the existing BugReportSubmitter pipeline with a dump_reason of
 * "server_error_5xx".
 *
 * Uses Provider<BugReportSubmitter> to break the Hilt dependency cycle
 * (OkHttpClient -> Interceptor -> BugReportSubmitter -> ApiService -> OkHttpClient).
 */
@Singleton
class ServerErrorReportInterceptor @Inject constructor(
    private val bugReportSubmitterProvider: Provider<BugReportSubmitter>,
    private val tracker: ServerErrorReportTracker,
    private val appScope: CoroutineScope
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code !in 500..599) return response

        val path = request.url.encodedPath
        // Avoid reporting failures of the report-upload endpoint itself.
        if (path.startsWith("/api/ui-dumps")) return response

        val key = "${request.method} $path:${response.code}"
        if (!tracker.shouldReport(key)) return response

        val method = request.method
        val url = request.url.toString()
        val code = response.code
        val statusMessage = response.message
        val receivedAtMs = response.receivedResponseAtMillis
        val bodySnippet = try {
            response.peekBody(BODY_PEEK_BYTES).string()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to peek response body for $method $path", e)
            null
        }

        appScope.launch {
            try {
                val extraContext = buildMap<String, Any> {
                    put("http_method", method)
                    put("http_url", url)
                    put("http_status", code)
                    put("http_status_message", statusMessage)
                    put("http_response_received_at_ms", receivedAtMs)
                    if (bodySnippet != null) {
                        put("http_response_body_snippet", bodySnippet)
                    }
                }
                val message = "Server $code on $method $path"
                bugReportSubmitterProvider.get()
                    .captureAndSubmit(message = message, source = SOURCE, extraContext = extraContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-report server error for $method $path", e)
            }
        }

        return response
    }

    companion object {
        private const val TAG = "ServerErrorReportInterceptor"
        private const val SOURCE = "server_error_5xx"
        private const val BODY_PEEK_BYTES = 4096L
    }
}
