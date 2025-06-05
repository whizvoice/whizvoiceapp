package com.example.whiz.ui.viewmodels

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speechRecognitionService: SpeechRecognitionService,
    private val ttsManager: TTSManager,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val TAG = "VoiceManager"

    // Speech Recognition State
    val transcriptionState = speechRecognitionService.transcriptionState
    val isListening = speechRecognitionService.isListening
    val speechError = speechRecognitionService.errorState

    // TTS State
    private val _isTTSInitialized = MutableStateFlow(false)
    val isTTSInitialized: StateFlow<Boolean> = _isTTSInitialized.asStateFlow()
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Voice Response Setting
    private val _isVoiceResponseEnabled = MutableStateFlow(false)
    val isVoiceResponseEnabled: StateFlow<Boolean> = _isVoiceResponseEnabled.asStateFlow()

    // Continuous Listening State
    private val _isContinuousListeningEnabled = MutableStateFlow(false)
    val isContinuousListeningEnabled: StateFlow<Boolean> = _isContinuousListeningEnabled.asStateFlow()

    // Track if user was listening before TTS started
    private var wasListeningBeforeTTS = false
    
    // Track current voice settings to know when we need to reset
    private var currentVoiceSettings: com.example.whiz.data.preferences.VoiceSettings? = null

    private var continuousListeningEnabled: Boolean
        get() = _isContinuousListeningEnabled.value
        set(value) {
            _isContinuousListeningEnabled.value = value
            speechRecognitionService.continuousListeningEnabled = value
        }

    init {
        initializeTTS()
        observeVoiceSettings()
    }

    private fun initializeTTS() {
        ttsManager.initialize { isInitialized ->
            _isTTSInitialized.value = isInitialized
            if (isInitialized) {
                Log.d(TAG, "TTS initialized successfully")
                setupTTSCallbacks()
                // Apply current voice settings if any
                viewModelScope.launch {
                    userPreferences.voiceSettings.collect { voiceSettings ->
                        applyVoiceSettings(voiceSettings)
                    }
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    private fun setupTTSCallbacks() {
        ttsManager.setAudioEventCallbacks(
            onStarted = {
                Log.d(TAG, "TTS started - was listening before: $wasListeningBeforeTTS")
                _isSpeaking.value = true
            },
            onCompleted = {
                Log.d(TAG, "TTS completed - continuous listening enabled: $continuousListeningEnabled")
                _isSpeaking.value = false
                
                // Auto-restart continuous listening after TTS completes if it was enabled
                // Use state observation instead of delay - the TTS callback guarantees TTS is done
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            },
            onError = {
                Log.e(TAG, "TTS error occurred")
                _isSpeaking.value = false
                
                // Also handle continuous listening restart on error if needed
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            }
        )
    }

    private fun observeVoiceSettings() {
        viewModelScope.launch {
            userPreferences.voiceSettings.collect { voiceSettings ->
                if (_isTTSInitialized.value) {
                    applyVoiceSettings(voiceSettings)
                }
            }
        }
    }

    private fun applyVoiceSettings(voiceSettings: com.example.whiz.data.preferences.VoiceSettings) {
        if (!_isTTSInitialized.value) return

        try {
            // Only apply settings if they have changed
            if (currentVoiceSettings != voiceSettings) {
                Log.d(TAG, "Applying voice settings - useSystemDefaults: ${voiceSettings.useSystemDefaults}")
                
                if (!voiceSettings.useSystemDefaults) {
                    ttsManager.setSpeechRate(voiceSettings.speechRate)
                    ttsManager.setPitch(voiceSettings.pitch)
                }
                
                currentVoiceSettings = voiceSettings
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying voice settings", e)
        }
    }

    fun speak(text: String) {
        if (_isTTSInitialized.value && text.isNotBlank()) {
            // Remember if we were listening before TTS starts
            wasListeningBeforeTTS = isListening.value || continuousListeningEnabled
            
            // Stop any ongoing speech recognition before speaking
            if (isListening.value) {
                speechRecognitionService.stopListening()
            }
            
            ttsManager.speak(text)
        }
    }

    fun stopSpeaking() {
        ttsManager.stop()
        _isSpeaking.value = false
    }

    fun testVoiceSettings(settings: com.example.whiz.data.preferences.VoiceSettings) {
        if (_isTTSInitialized.value) {
            ttsManager.testVoiceSettings(settings)
        }
    }

    fun setVoiceResponseEnabled(enabled: Boolean) {
        _isVoiceResponseEnabled.value = enabled
        Log.d(TAG, "Voice response enabled: $enabled")
    }

    fun startListening(callback: (String) -> Unit) {
        speechRecognitionService.startListening(callback)
    }

    fun stopListening() {
        speechRecognitionService.stopListening()
    }

    fun startContinuousListening() {
        if (!continuousListeningEnabled) {
            Log.d(TAG, "startContinuousListening called but continuous listening is disabled")
            return
        }

        startListening { /* No callback needed for continuous listening */ }
    }

    fun toggleContinuousListening() {
        continuousListeningEnabled = !continuousListeningEnabled
        Log.d(TAG, "Continuous listening toggled to: $continuousListeningEnabled")
        
        if (continuousListeningEnabled) {
            startContinuousListening()
        } else {
            stopListening()
        }
    }

    fun updateContinuousListeningEnabled(enabled: Boolean) {
        continuousListeningEnabled = enabled
    }

    // Helper method to detect if headphones are connected
    fun areHeadphonesConnected(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headphone status", e)
            false
        }
    }

    // Helper method to determine if mic button should be shown during TTS
    fun shouldShowMicButtonDuringTTS(): Boolean {
        return _isSpeaking.value && !areHeadphonesConnected()
    }

    // Handle mic button click during TTS - pause TTS and start listening
    fun handleMicClickDuringTTS() {
        if (_isSpeaking.value && !areHeadphonesConnected()) {
            Log.d(TAG, "handleMicClickDuringTTS: Pausing TTS and starting listening")
            
            // Pause/stop TTS (but keep voice response enabled for future messages)
            stopSpeaking()
            
            // Enable continuous listening and start immediately
            continuousListeningEnabled = true
            
            // Use reactive state observation instead of delay
            // Observe when TTS actually stops speaking, but only check once
            viewModelScope.launch {
                isSpeaking.first { !it } // Wait for first emission where isSpeaking is false
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.shutdown()
        speechRecognitionService.release()
    }
} 