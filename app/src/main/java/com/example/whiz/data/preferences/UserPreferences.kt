package com.example.whiz.data.preferences

import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
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
        if (_hasClaudeToken.value == null || _hasAsanaToken.value == null) {
            refreshApiTokenStatus()
        }
    }

    suspend fun refreshApiTokenStatus() {
        try {
            val serverToken = authRepository.serverToken.value ?: return // Need auth token
            Log.d(TAG, "Refreshing API token status from server...")
            val response = apiService.getApiTokens()
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
            Log.d(TAG, "Refreshed token status: Claude=${response.has_claude_token}, Asana=${response.has_asana_token}")
        } catch (e: Exception) {
            // Handle error (e.g., network error, server error)
            Log.e(TAG, "Error refreshing API token status", e)
            // Optionally set state to false or keep null to indicate error
            // _hasClaudeToken.value = false
            // _hasAsanaToken.value = false
        }
    }


    suspend fun setClaudeToken(token: String) {
        val serverToken = authRepository.serverToken.value
        Log.d(TAG, "Attempting to update Claude token on server. New token (empty if clearing): '$token'")
        try {
            apiService.updateApiTokens(
                ApiService.TokenUpdateRequest(claude_api_key = token)
            )
            // SUCCESS: Update state only AFTER successful API call by REFRESHING
            Log.i(TAG, "Successfully sent Claude token update to server. Refreshing local status...")
            refreshApiTokenStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Claude token on server", e)
            // Consider refreshing status to get the actual state from server
            // refreshApiTokenStatus() 
            // Rethrow the exception so the ViewModel can handle it
            throw e 
        }
    }

    suspend fun setAsanaToken(token: String) {
        val serverToken = authRepository.serverToken.value
        Log.d(TAG, "Attempting to update Asana token on server. New token (empty if clearing): '$token'")
        try {
            apiService.updateApiTokens(
                ApiService.TokenUpdateRequest(asana_access_token = token)
            )
            // SUCCESS: Update state only AFTER successful API call by REFRESHING
            Log.i(TAG, "Successfully sent Asana token update to server. Refreshing local status...")
            refreshApiTokenStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating Asana token on server", e)
            // Consider refreshing status to get the actual state from server
            // refreshApiTokenStatus()
             // Rethrow the exception so the ViewModel can handle it
            throw e
        }
    }
} 