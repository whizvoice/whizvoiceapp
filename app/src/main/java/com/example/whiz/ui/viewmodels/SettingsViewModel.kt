package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WhizRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory: StateFlow<Boolean> = _isClearingHistory

    private val _showClearConfirmation = MutableStateFlow(false)
    val showClearConfirmation: StateFlow<Boolean> = _showClearConfirmation

    // Show confirmation dialog
    fun showClearHistoryConfirmation() {
        _showClearConfirmation.value = true
    }

    // Dismiss confirmation dialog
    fun dismissClearHistoryConfirmation() {
        _showClearConfirmation.value = false
    }

    // Clear all chat history
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
}