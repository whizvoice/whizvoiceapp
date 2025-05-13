package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WhizRepository,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {
    private val TAG = "SettingsViewModel"

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory: StateFlow<Boolean> = _isClearingHistory

    private val _showClearConfirmation = MutableStateFlow(false)
    val showClearConfirmation: StateFlow<Boolean> = _showClearConfirmation

    val hasClaudeToken: StateFlow<Boolean?> = userPreferences.hasClaudeToken
    val hasAsanaToken: StateFlow<Boolean?> = userPreferences.hasAsanaToken

    private val _isSavingClaude = MutableStateFlow(false)
    val isSavingClaude: StateFlow<Boolean> = _isSavingClaude.asStateFlow()

    private val _isSavingAsana = MutableStateFlow(false)
    val isSavingAsana: StateFlow<Boolean> = _isSavingAsana.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        Log.d(TAG, "Initializing SettingsViewModel")
        viewModelScope.launch {
            try {
                userPreferences.initializeTokenStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing token status in ViewModel", e)
                _errorMessage.value = "Error loading initial token status."
            }
        }
    }

    fun showClearHistoryConfirmation() {
        _showClearConfirmation.value = true
    }

    fun dismissClearHistoryConfirmation() {
        _showClearConfirmation.value = false
    }

    fun clearAllChatHistory() {
        viewModelScope.launch {
            _isClearingHistory.value = true
            try {
                repository.deleteAllChats()
            } finally {
                _isClearingHistory.value = false
                _showClearConfirmation.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.signOut()
        }
    }

    fun saveClaudeToken(token: String) {
        // Allow blank token for clearing. UI should handle enabling save button.
        viewModelScope.launch {
            _isSavingClaude.value = true // Represents busy state for save/clear
            _errorMessage.value = null
            try {
                userPreferences.setClaudeToken(token)
                if (token.isBlank()) {
                    _errorMessage.value = "Claude API Key cleared successfully!"
                } else {
                    _errorMessage.value = "Claude API Key saved successfully!"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving/clearing Claude token", e)
                if (token.isBlank()) {
                    _errorMessage.value = "Failed to clear Claude API Key: ${e.message}"
                } else {
                    _errorMessage.value = "Failed to save Claude API Key: ${e.message}"
                }
            } finally {
                _isSavingClaude.value = false
            }
        }
    }

    fun saveAsanaToken(token: String) {
        // Allow blank token for clearing.
        viewModelScope.launch {
            _isSavingAsana.value = true // Represents busy state for save/clear
            _errorMessage.value = null
            try {
                userPreferences.setAsanaToken(token)
                if (token.isBlank()) {
                    _errorMessage.value = "Asana Access Token cleared successfully!"
                } else {
                    _errorMessage.value = "Asana Access Token saved successfully!"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving/clearing Asana token", e)
                if (token.isBlank()) {
                    _errorMessage.value = "Failed to clear Asana Access Token: ${e.message}"
                } else {
                    _errorMessage.value = "Failed to save Asana Access Token: ${e.message}"
                }
            } finally {
                _isSavingAsana.value = false
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}