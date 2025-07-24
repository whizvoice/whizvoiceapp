package com.example.whiz.data.auth

import android.util.Log
import com.example.whiz.data.remote.AuthApi // Assuming your refresh API endpoint is in AuthApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider // Needed for lazy/safe injection of AuthRepository

// TODO: You might need to adjust the AuthApi import if your refresh endpoint is defined elsewhere.
// Also, ensure AuthRepository and AuthApi are correctly provided by Hilt for injection.

class TokenAuthenticator @Inject constructor(
    // Use Provider<AuthRepository> to avoid circular dependency issues if AuthRepository itself uses OkHttpClient
    private val authRepositoryProvider: Provider<AuthRepository>,
    // Assuming AuthApi is where the refreshAccessToken suspend fun is defined
    // If not, you'll need to inject the correct API service that has the refresh call
    private val authServerApiProvider: Provider<AuthApi> // Or your specific API service interface
) : Authenticator {

    private val TAG = "TokenAuthenticator"

    override fun authenticate(route: Route?, response: Response): Request? {
        Log.d(TAG, "🔑 TokenAuthenticator.authenticate called - previous request failed with code: ${response.code} for URL: ${response.request.url}")
        Log.d(TAG, "🔑 Response message: ${response.message}")
        Log.d(TAG, "🔑 Request headers: ${response.request.headers}")
        
        // Log response body if available for debugging
        try {
            val responseBodyString = response.peekBody(1024).string()
            if (responseBodyString.isNotEmpty()) {
                Log.d(TAG, "🔑 Response body: $responseBodyString")
            }
        } catch (e: Exception) {
            Log.d(TAG, "🔑 Could not read response body: ${e.message}")
        }

        // Get AuthRepository instance safely
        val authRepository = authRepositoryProvider.get()
        val authServerApi = authServerApiProvider.get() // Get the API service instance

        // Avoid re-authentication for the refresh token request itself
        if (response.request.url.pathSegments.contains("refresh") && response.request.url.pathSegments.contains("auth")) {
            Log.w(TAG, "Authentication failed for refresh token request itself. Not retrying.")
            return null // Do not retry the request that tried to refresh the token
        }
        
        // Limit the number of retries to prevent infinite loops if refresh keeps failing
        if (responseCount(response) >= 2) {
            Log.w(TAG, "🔑 Authentication retried too many times. Signing out user.")
            // Trigger sign-out here if multiple refresh attempts fail
            runBlocking { 
                withContext(Dispatchers.IO) {
                    try {
                        authRepository.signOut()
                        Log.d(TAG, "🔑 User signed out due to repeated authentication failures")
                    } catch (e: Exception) {
                        Log.e(TAG, "🔑 Error during automatic sign-out", e)
                    }
                }
            }
            return null
        }

        val currentAccessToken = runBlocking { 
            withContext(Dispatchers.IO) {
                authRepository.serverToken.first()
            }
        }
        // If the token that failed is not the one we currently have, it means it was refreshed elsewhere.
        // In this case, try the request again with the current token.
        val authHeader = response.request.header("Authorization")
        if (authHeader != null && authHeader != "Bearer $currentAccessToken") {
            Log.d(TAG, "Retrying with current potentially refreshed token.")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $currentAccessToken")
                .build()
        }

        Log.d(TAG, "🔄 Attempting to refresh access token...")
        val refreshSuccessful = runBlocking {
            withContext(Dispatchers.IO) {
                try {
                    // Pass the actual API service to refreshAccessToken
                    val result = authRepository.refreshAccessToken()
                    Log.d(TAG, "🔄 Token refresh completed with result: $result")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "🔄 Exception during token refresh", e)
                    false
                }
            }
        }

        if (refreshSuccessful) {
            val newAccessToken = runBlocking { 
                withContext(Dispatchers.IO) {
                    authRepository.serverToken.first()
                }
            }
            Log.i(TAG, "Token refresh successful. Retrying original request with new token.")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
        } else {
            Log.w(TAG, "🔑 Token refresh failed. Signing out user due to invalid tokens.")
            // Token refresh failed (e.g., refresh token invalid), sign out the user
            runBlocking { 
                withContext(Dispatchers.IO) {
                    try {
                        authRepository.signOut()
                        Log.d(TAG, "🔑 User signed out due to token refresh failure")
                    } catch (e: Exception) {
                        Log.e(TAG, "🔑 Error during automatic sign-out after refresh failure", e)
                    }
                }
            }
            return null
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var priorResponse = response.priorResponse
        while (priorResponse != null) {
            result++
            priorResponse = priorResponse.priorResponse
        }
        return result
    }
} 