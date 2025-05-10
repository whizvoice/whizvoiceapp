package com.example.whiz.data.preferences

import com.example.whiz.data.api.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferences @Inject constructor(
    private val apiService: ApiService
) {
    private val _hasClaudeToken = MutableStateFlow(false)
    val hasClaudeToken: StateFlow<Boolean> = _hasClaudeToken

    private val _hasAsanaToken = MutableStateFlow(false)
    val hasAsanaToken: StateFlow<Boolean> = _hasAsanaToken

    suspend fun loadTokens() {
        try {
            val response = apiService.getApiTokens()
            _hasClaudeToken.value = response.has_claude_token
            _hasAsanaToken.value = response.has_asana_token
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun setClaudeToken(token: String) {
        try {
            apiService.updateApiTokens(
                ApiService.TokenUpdateRequest(claude_api_key = token)
            )
            _hasClaudeToken.value = true
        } catch (e: Exception) {
            // Handle error
        }
    }

    suspend fun setAsanaToken(token: String) {
        try {
            apiService.updateApiTokens(
                ApiService.TokenUpdateRequest(asana_access_token = token)
            )
            _hasAsanaToken.value = true
        } catch (e: Exception) {
            // Handle error
        }
    }
} 