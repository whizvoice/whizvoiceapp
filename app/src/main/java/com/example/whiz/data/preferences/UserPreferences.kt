package com.example.whiz.data.preferences

import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.auth.AuthenticationRequiredException
import kotlinx.coroutines.flow.first
import retrofit2.HttpException

@Singleton
class UserPreferences @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository
) {
    private val TAG = "UserPreferences"

    // Internal MutableStateFlow
    private val _hasClaudeToken = MutableStateFlow<Boolean?>(null) // Use null for initial unknown state
    private val _hasAsanaToken = MutableStateFlow<Boolean?>(null)

    // Publicly exposed StateFlow
    val hasClaudeToken: StateFlow<Boolean?> = _hasClaudeToken
    val hasAsanaToken: StateFlow<Boolean?> = _hasAsanaToken

    suspend fun initializeTokenStatus() {
        // Call this when the app starts or user logs in
        // Ensure refresh is called even if only one is null, or on explicit demand.
        // This will be triggered from SettingsViewModel init.
        Log.d(TAG, "initializeTokenStatus called. Claude: ${_hasClaudeToken.value}, Asana: ${_hasAsanaToken.value}")
        // Always attempt to refresh if there's a chance data is stale or uninitialized.
        // ViewModel will call this, and it can be called again if needed (e.g. after login)
        refreshApiTokenStatus()
    }

    suspend fun refreshApiTokenStatus() {
        try {
            // Wait for a valid server token. This will suspend until a token is available.
            val serverToken = authRepository.serverToken.first { it != null }
            Log.d(TAG, "Refreshing API token status from server with token: $serverToken")
            
            val response = apiService.getApiTokens() // Assumes getApiTokens uses the token via an interceptor
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
            Log.d(TAG, "Refreshed token status: Claude=${response.has_claude_token}, Asana=${response.has_asana_token}")
        } catch (e: Exception) {
            // Handle error (e.g., network error, server error)
            Log.e(TAG, "Error refreshing API token status", e)
            // Set state to false to indicate an error occurred and we don't know the status,
            // or that they are effectively not set from the app's perspective.
            _hasClaudeToken.value = false
            _hasAsanaToken.value = false
            // Check if the exception is an HttpException with a 401 status code
            if (e is HttpException && e.code() == 401) {
                Log.w(TAG, "Authentication required (401), throwing AuthenticationRequiredException.")
                throw AuthenticationRequiredException(cause = e)
            }
        }
    }


    suspend fun setClaudeToken(token: String) {
        // val serverToken = authRepository.serverToken.value // May not be needed if auth handled by interceptor
        Log.d(TAG, "Attempting to update Claude token on server via /user/api_key. New token (empty if clearing): '$token'")
        try {
            apiService.setUserApiKey( // Use the new function
                ApiService.UserApiKeySetRequest(
                    key_name = "claude_api_key", // Key name as expected by backend
                    key_value = token.ifBlank { null } // Send null if token is blank to clear
                )
            )
            Log.i(TAG, "Successfully sent Claude token update to server. Refreshing local status...")
            refreshApiTokenStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Claude token on server via /user/api_key", e)
            throw e 
        }
    }

    suspend fun setAsanaToken(token: String) {
        // val serverToken = authRepository.serverToken.value // May not be needed if auth handled by interceptor
        Log.d(TAG, "Attempting to update Asana token on server via /user/api_key. New token (empty if clearing): '$token'")
        try {
            apiService.setUserApiKey( // Use the new function
                ApiService.UserApiKeySetRequest(
                    key_name = "asana_access_token", // Key name as expected by backend
                    key_value = token.ifBlank { null } // Send null if token is blank to clear
                )
            )
            Log.i(TAG, "Successfully sent Asana token update to server. Refreshing local status...")
            refreshApiTokenStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Asana token on server via /user/api_key", e)
            throw e
        }
    }
} 