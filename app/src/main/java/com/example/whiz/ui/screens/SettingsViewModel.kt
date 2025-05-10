package com.example.whiz.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.preferences.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory: StateFlow<Boolean> = _isClearingHistory

    private val _showClearConfirmation = MutableStateFlow(false)
    val showClearConfirmation: StateFlow<Boolean> = _showClearConfirmation

    init {
        loadTokens()
    }

    private fun loadTokens() {
        viewModelScope.launch {
            userPreferences.loadTokens()
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
                // TODO: Implement chat history clearing
            } finally {
                _isClearingHistory.value = false
                _showClearConfirmation.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // TODO: Implement logout
        }
    }

    fun setClaudeToken(token: String) {
        viewModelScope.launch {
            userPreferences.setClaudeToken(token)
        }
    }

    fun setAsanaToken(token: String) {
        viewModelScope.launch {
            userPreferences.setAsanaToken(token)
        }
    }
} 