package com.example.wiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wiz.data.repository.WizRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WizRepository
) : ViewModel() {

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory = _isClearingHistory.asStateFlow()

    private val _showClearConfirmation = MutableStateFlow(false)
    val showClearConfirmation = _showClearConfirmation.asStateFlow()

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
            repository.deleteAllChats()
            _isClearingHistory.value = false
            _showClearConfirmation.value = false
        }
    }
}