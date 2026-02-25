package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.data.preferences.VoiceSettings
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.whiz.data.auth.AuthenticationRequiredException
import com.example.whiz.services.TTSManager
import com.example.whiz.services.WakeWordService
import com.example.whiz.data.local.SubscriptionStatus
import com.example.whiz.data.preferences.WakeWordPreferences

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WhizRepository,
    private val authRepository: AuthRepository,
    private val userPreferences: UserPreferences,
    private val ttsManager: TTSManager,
    private val wakeWordPreferences: WakeWordPreferences,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {
    private val TAG = "SettingsViewModel"

    private val _isClearingHistory = MutableStateFlow(false)
    val isClearingHistory: StateFlow<Boolean> = _isClearingHistory

    private val _showClearConfirmation = MutableStateFlow(false)
    val showClearConfirmation: StateFlow<Boolean> = _showClearConfirmation

    val hasClaudeToken: StateFlow<Boolean?> = userPreferences.hasClaudeToken
    val hasAsanaToken: StateFlow<Boolean?> = userPreferences.hasAsanaToken
    val voiceSettings: StateFlow<VoiceSettings> = userPreferences.voiceSettings

    private val _isSavingClaude = MutableStateFlow(false)
    val isSavingClaude: StateFlow<Boolean> = _isSavingClaude.asStateFlow()

    private val _isSavingAsana = MutableStateFlow(false)
    val isSavingAsana: StateFlow<Boolean> = _isSavingAsana.asStateFlow()
    
    private val _isSavingVoiceSettings = MutableStateFlow(false)
    val isSavingVoiceSettings: StateFlow<Boolean> = _isSavingVoiceSettings.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Hard sync state
    private val _isHardSyncing = MutableStateFlow(false)
    val isHardSyncing: StateFlow<Boolean> = _isHardSyncing.asStateFlow()

    // State to trigger navigation to Login screen
    private val _navigateToLogin = MutableStateFlow(false)
    val navigateToLogin: StateFlow<Boolean> = _navigateToLogin.asStateFlow()
    
    // Subscription states
    private val _subscriptionStatus = MutableStateFlow<SubscriptionStatus?>(null)
    val subscriptionStatus: StateFlow<SubscriptionStatus?> = _subscriptionStatus.asStateFlow()
    
    private val _isLoadingSubscription = MutableStateFlow(true)
    val isLoadingSubscription: StateFlow<Boolean> = _isLoadingSubscription.asStateFlow()
    
    private val _isProcessingSubscription = MutableStateFlow(false)
    val isProcessingSubscription: StateFlow<Boolean> = _isProcessingSubscription.asStateFlow()

    val isWakeWordEnabled: StateFlow<Boolean> = wakeWordPreferences.isEnabled

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                wakeWordPreferences.setEnabled(enabled)
                if (enabled) {
                    WakeWordService.start(context)
                } else {
                    WakeWordService.stop(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting wake word enabled=$enabled", e)
                _errorMessage.value = "Failed to update wake word setting: ${e.message}"
            }
        }
    }

    init {
        Log.d(TAG, "Initializing SettingsViewModel")
        viewModelScope.launch {
            try {
                userPreferences.initializeTokenStatus() // This might throw AuthenticationRequiredException
                // Explicitly load voice settings to ensure they're up to date
                userPreferences.loadVoiceSettings()
                Log.d(TAG, "Voice settings after init: ${userPreferences.voiceSettings.value}")
                
                // Load subscription status
                loadSubscriptionStatus()
            } catch (e: AuthenticationRequiredException) {
                Log.w(TAG, "Authentication required, navigating to login screen.", e)
                _navigateToLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing token status in ViewModel", e)
                _errorMessage.value = "Error loading initial settings."
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
            } catch (e: AuthenticationRequiredException) {
                Log.w(TAG, "Authentication required during save/clear Claude token, navigating to login screen.", e)
                _navigateToLogin.value = true
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
            } catch (e: AuthenticationRequiredException) {
                Log.w(TAG, "Authentication required during save/clear Asana token, navigating to login screen.", e)
                _navigateToLogin.value = true
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

    fun saveVoiceSettings(settings: VoiceSettings) {
        viewModelScope.launch {
            _isSavingVoiceSettings.value = true
            _errorMessage.value = null
            try {
                userPreferences.saveVoiceSettings(settings)
                _errorMessage.value = "Voice settings saved successfully!"
            } catch (e: AuthenticationRequiredException) {
                Log.w(TAG, "Authentication required during save voice settings, navigating to login screen.", e)
                _navigateToLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error saving voice settings", e)
                _errorMessage.value = "Failed to save voice settings: ${e.message}"
            } finally {
                _isSavingVoiceSettings.value = false
            }
        }
    }

    fun testVoiceSettings(settings: VoiceSettings) {
        try {
            ttsManager.testVoiceSettings(settings)
            Log.d(TAG, "Testing voice settings: $settings")
        } catch (e: Exception) {
            Log.e(TAG, "Error testing voice settings", e)
            _errorMessage.value = "Error testing voice settings: ${e.message}"
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun performHardSync() {
        viewModelScope.launch {
            _isHardSyncing.value = true
            _errorMessage.value = null
            try {
                Log.d(TAG, "Starting hard sync...")
                repository.forceFullRefresh()
                _errorMessage.value = "Hard sync completed successfully!"
                Log.d(TAG, "Hard sync completed successfully")
            } catch (e: AuthenticationRequiredException) {
                Log.w(TAG, "Authentication required during hard sync, navigating to login screen.", e)
                _navigateToLogin.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Error during hard sync", e)
                _errorMessage.value = "Hard sync failed: ${e.message}"
            } finally {
                _isHardSyncing.value = false
            }
        }
    }

    // Method to reset navigation trigger after navigation has occurred
    fun onLoginNavigationComplete() {
        _navigateToLogin.value = false
    }

    fun refreshVoiceSettings() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Refreshing voice settings...")
                userPreferences.loadVoiceSettings()
                Log.d(TAG, "Voice settings refreshed: ${userPreferences.voiceSettings.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing voice settings", e)
            }
        }
    }
    
    // Subscription functions
    private fun loadSubscriptionStatus() {
        viewModelScope.launch {
            _isLoadingSubscription.value = true
            try {
                val status = repository.getSubscriptionStatus()
                _subscriptionStatus.value = status
                Log.d(TAG, "Subscription status loaded: $status")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading subscription status", e)
                _errorMessage.value = "Failed to load subscription status"
            } finally {
                _isLoadingSubscription.value = false
            }
        }
    }
    
    fun startSubscription() {
        viewModelScope.launch {
            _isProcessingSubscription.value = true
            try {
                val checkoutUrl = repository.createCheckoutSession(
                    successUrl = "whizvoice://subscription/success",
                    cancelUrl = "whizvoice://subscription/cancel"
                )
                
                // Open the checkout URL in browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl))
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                
                // Refresh subscription status after a delay
                kotlinx.coroutines.delay(2000)
                loadSubscriptionStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting subscription", e)
                _errorMessage.value = "Failed to start subscription: ${e.message}"
            } finally {
                _isProcessingSubscription.value = false
            }
        }
    }
    
    fun cancelSubscription() {
        viewModelScope.launch {
            _isProcessingSubscription.value = true
            try {
                repository.cancelSubscription()
                _errorMessage.value = "Subscription will be canceled at the end of the billing period"
                
                // Reload subscription status
                loadSubscriptionStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling subscription", e)
                _errorMessage.value = "Failed to cancel subscription: ${e.message}"
            } finally {
                _isProcessingSubscription.value = false
            }
        }
    }
}