package com.example.whiz.data.auth

import android.util.Log
import com.example.whiz.data.remote.AuthApi // Assuming your refresh API endpoint is in AuthApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        Log.d(TAG, "authenticate called - previous request failed with code: ${response.code}")

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
            Log.w(TAG, "Authentication retried too many times. Not retrying.")
            // Optionally: Trigger sign-out here if multiple refresh attempts fail
            // runBlocking { authRepository.signOut() } // Consider the implications
            return null
        }

        val currentAccessToken = runBlocking { authRepository.serverToken.first() }
        // If the token that failed is not the one we currently have, it means it was refreshed elsewhere.
        // In this case, try the request again with the current token.
        val authHeader = response.request.header("Authorization")
        if (authHeader != null && authHeader != "Bearer $currentAccessToken") {
            Log.d(TAG, "Retrying with current potentially refreshed token.")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $currentAccessToken")
                .build()
        }

        Log.d(TAG, "Attempting to refresh access token...")
        val refreshSuccessful = runBlocking {
            // Pass the actual API service to refreshAccessToken
            authRepository.refreshAccessToken()
        }

        if (refreshSuccessful) {
            val newAccessToken = runBlocking { authRepository.serverToken.first() }
            Log.i(TAG, "Token refresh successful. Retrying original request with new token.")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
        } else {
            Log.w(TAG, "Token refresh failed. Not retrying original request.")
            // Token refresh failed (e.g., refresh token invalid), AuthRepository.refreshAccessToken should have handled sign-out.
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