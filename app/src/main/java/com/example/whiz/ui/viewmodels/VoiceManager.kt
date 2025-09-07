package com.example.whiz.ui.viewmodels

import android.content.Context
import android.util.Log
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.services.AppLifecycleService
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.ListeningMode
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionManager: PermissionManager,
    private val speechRecognitionService: SpeechRecognitionService,
    private val ttsManager: TTSManager,
    private val userPreferences: UserPreferences,
    private val appLifecycleService: AppLifecycleService
) {

    private val TAG = "VoiceManager"
    
    // Create a coroutine scope for this singleton service
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Speech Recognition State
    val transcriptionState = speechRecognitionService.transcriptionState
    val isListening = speechRecognitionService.isListening
    val speechError = speechRecognitionService.errorState

    // TTS State
    private val _isTTSInitialized = MutableStateFlow(false)
    val isTTSInitialized: StateFlow<Boolean> = _isTTSInitialized.asStateFlow()
    
    // Derive speaking state directly from TTSManager (single source of truth)
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking

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
        }

    init {
        initializeTTS()
        observeVoiceSettings()
        observePermissionChanges()
        observeAppLifecycle()
        observeBubbleModeChanges()
        
        // Set up callback for SpeechRecognitionService to check if it should actually be listening
        speechRecognitionService.continuousListeningCallback = { continuousListeningEnabled }
        speechRecognitionService.shouldRestartCallback = { shouldBeListening() }
    }
    
    /**
     * Determines if the speech service should actually be listening right now.
     * This is the authoritative check that considers all conditions.
     */
    private fun shouldBeListening(): Boolean {
        val isInForeground = appLifecycleService.isInForeground()
        val isBubbleActive = BubbleOverlayService.isActive
        val hasPermission = permissionManager.microphonePermissionGranted.value
        val notSpeaking = !isSpeaking.value
        
        // Check bubble mode - if bubble is active and mic is off, don't listen
        val bubbleMode = BubbleOverlayService.bubbleListeningMode
        val bubbleAllowsListening = !isBubbleActive || 
            (bubbleMode == ListeningMode.CONTINUOUS_LISTENING || bubbleMode == ListeningMode.TTS_WITH_LISTENING)
        
        // Keep listening if either in foreground OR bubble is active (with mic enabled)
        val should = continuousListeningEnabled && (isInForeground || isBubbleActive) && 
                    hasPermission && notSpeaking && bubbleAllowsListening
        
        Log.d(TAG, "shouldBeListening check: continuousEnabled=$continuousListeningEnabled, " +
                "foreground=$isInForeground, bubble=$isBubbleActive, bubbleMode=$bubbleMode, " +
                "permission=$hasPermission, notSpeaking=$notSpeaking, result=$should")
        
        return should
    }

    private fun initializeTTS() {
        ttsManager.initialize { isInitialized ->
            _isTTSInitialized.value = isInitialized
            if (isInitialized) {
                Log.d(TAG, "TTS initialized successfully")
                setupTTSCallbacks()
                // Apply current voice settings if any
                coroutineScope.launch {
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
                // No need to set _isSpeaking - TTSManager handles it
            },
            onCompleted = {
                Log.d(TAG, "TTS completed - continuous listening enabled: $continuousListeningEnabled")
                // No need to set _isSpeaking - TTSManager handles it
                
                // Auto-restart continuous listening after TTS completes if it was enabled
                // Use state observation instead of delay - the TTS callback guarantees TTS is done
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            },
            onError = {
                Log.e(TAG, "TTS error occurred")
                // No need to set _isSpeaking - TTSManager handles it
                
                // Also handle continuous listening restart on error if needed
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            }
        )
    }

    private fun observeVoiceSettings() {
        coroutineScope.launch {
            userPreferences.voiceSettings.collect { voiceSettings ->
                if (_isTTSInitialized.value) {
                    applyVoiceSettings(voiceSettings)
                }
            }
        }
    }

    private fun observePermissionChanges() {
        coroutineScope.launch {
            permissionManager.microphonePermissionGranted.collect { hasPermission ->
                if (hasPermission) {
                    onPermissionGranted()
                } else {
                    onPermissionDenied()
                }
            }
        }
    }

    private fun onPermissionGranted() {
        Log.d(TAG, "Microphone permission granted - enabling voice features")
        try {
            speechRecognitionService.initialize()
            
            // If continuous listening was enabled before permission was granted, start it now
            if (continuousListeningEnabled) {
                coroutineScope.launch {
                    delay(100L) // Small delay to ensure service is initialized
                    startContinuousListening()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing speech service after permission granted", e)
        }
    }

    private fun onPermissionDenied() {
        Log.w(TAG, "Microphone permission denied - disabling voice features")
        
        // Stop any ongoing listening
        if (isListening.value) {
            try {
                speechRecognitionService.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition after permission denied", e)
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

    private fun observeAppLifecycle() {
        // Handle app background events
        coroutineScope.launch {
            appLifecycleService.appBackgroundEvent.collect {
                Log.d(TAG, "App backgrounded - stopping continuous listening")
                onAppBackgrounded()
            }
        }
        
        // Handle app foreground events
        coroutineScope.launch {
            appLifecycleService.appForegroundEvent.collect {
                Log.d(TAG, "App foregrounded")
                onAppForegrounded()
            }
        }
    }
    
    private fun observeBubbleModeChanges() {
        // Listen for bubble mode changes
        coroutineScope.launch {
            BubbleOverlayService.modeChangeFlow.collect { mode ->
                Log.d(TAG, "Bubble mode changed to: $mode")
                
                when (mode) {
                    ListeningMode.MIC_OFF -> {
                        // Stop both listening and TTS when mic is turned off
                        Log.d(TAG, "MIC_OFF mode - stopping speech recognition and TTS")
                        stopListening()
                        ttsManager.stop()
                    }
                    ListeningMode.CONTINUOUS_LISTENING -> {
                        // Stop TTS when switching to listening-only mode
                        Log.d(TAG, "CONTINUOUS_LISTENING mode - stopping TTS if active")
                        if (ttsManager.isSpeaking.value) {
                            Log.d(TAG, "Stopping TTS as bubble switched to listening-only mode")
                            ttsManager.stop()
                        }
                        
                        // Re-evaluate if we should be listening
                        Log.d(TAG, "Mode allows listening, checking if should restart")
                        if (continuousListeningEnabled && !isSpeaking.value && shouldBeListening()) {
                            Log.d(TAG, "Restarting speech recognition for mode: CONTINUOUS_LISTENING")
                            // Force restart the continuous listening
                            coroutineScope.launch {
                                delay(100) // Small delay to ensure state is settled
                                if (shouldBeListening() && !speechRecognitionService.isListening.value) {
                                    Log.d(TAG, "Force restarting continuous listening after mode change")
                                    startContinuousListening()
                                }
                            }
                        }
                    }
                    ListeningMode.TTS_WITH_LISTENING -> {
                        // Re-evaluate if we should be listening
                        Log.d(TAG, "TTS_WITH_LISTENING mode - keeping TTS enabled")
                        if (continuousListeningEnabled && !isSpeaking.value && shouldBeListening()) {
                            Log.d(TAG, "Restarting speech recognition for mode: TTS_WITH_LISTENING")
                            // Force restart the continuous listening
                            coroutineScope.launch {
                                delay(100) // Small delay to ensure state is settled
                                if (shouldBeListening() && !speechRecognitionService.isListening.value) {
                                    Log.d(TAG, "Force restarting continuous listening after mode change")
                                    startContinuousListening()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun onAppBackgrounded() {
        Log.d(TAG, "onAppBackgrounded called. continuousListeningEnabled=$continuousListeningEnabled")
        
        // Check if bubble overlay is running - if so, don't stop listening
        if (com.example.whiz.services.BubbleOverlayService.isActive) {
            Log.d(TAG, "Bubble overlay is active - keeping voice recognition running with continuous listening")
            // Important: Don't stop listening and don't change continuousListeningEnabled
            return
        }
        
        // Stop listening but preserve the setting
        if (isListening.value) {
            Log.d(TAG, "Stopping speech recognition due to app backgrounded (preserving continuous listening setting)")
            try {
                // Stop listening but DON'T change continuousListeningEnabled
                // This way when the app returns to foreground, continuous listening will resume
                speechRecognitionService.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping speech recognition on background", e)
            }
        }
    }
    
    private fun onAppForegrounded() {
        Log.d(TAG, "onAppForegrounded called. continuousListeningEnabled=$continuousListeningEnabled")
        
        // Note: We don't automatically restart continuous listening here
        // It should be restarted by ChatScreen when appropriate
        // This prevents unwanted mic activation when returning to non-chat screens
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
        // No need to set _isSpeaking - TTSManager handles it
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
    
    /**
     * Check if TTS should be enabled based on bubble mode
     */
    fun shouldEnableTTS(): Boolean {
        // If bubble is active and in TTS mode, enable TTS
        val bubbleActive = BubbleOverlayService.isActive
        val bubbleMode = BubbleOverlayService.bubbleListeningMode
        val voiceEnabled = _isVoiceResponseEnabled.value
        
        Log.d(TAG, "shouldEnableTTS: bubbleActive=$bubbleActive, bubbleMode=$bubbleMode, voiceEnabled=$voiceEnabled")
        
        if (bubbleActive && bubbleMode == ListeningMode.TTS_WITH_LISTENING) {
            Log.d(TAG, "shouldEnableTTS: Returning true due to bubble TTS mode")
            return true
        }
        // Otherwise use the normal voice response setting
        Log.d(TAG, "shouldEnableTTS: Returning $voiceEnabled from voice response setting")
        return voiceEnabled
    }

    fun startListening(callback: (String) -> Unit) {
        speechRecognitionService.startListening(callback)
    }

    fun stopListening() {
        speechRecognitionService.stopListening()
    }

    // Transcription callback for continuous listening (set by consumers like ChatViewModel)
    private var transcriptionCallback: ((String) -> Unit)? = null
    
    fun startContinuousListening() {
        Log.d(TAG, "[DEBUG] startContinuousListening() called, continuousListeningEnabled=$continuousListeningEnabled")
        if (!continuousListeningEnabled) {
            Log.d(TAG, "startContinuousListening called but continuous listening is disabled")
            return
        }

        Log.d(TAG, "[DEBUG] About to call startListening()")
        startListening { finalText ->
            Log.d(TAG, "startContinuousListening: got transcription. continuousListeningEnabled=$continuousListeningEnabled, text='$finalText'")
            
            // Call the transcription callback if set (for chat integration)
            if (finalText.isNotBlank()) {
                transcriptionCallback?.invoke(finalText)
                
                // Auto-restart continuous listening if still enabled
                if (continuousListeningEnabled) {
                    Log.d(TAG, "Continuous listening: restarting after result")
                    coroutineScope.launch {
                        // Small delay to ensure the previous listening session is fully stopped
                        delay(100L)
                        if (continuousListeningEnabled && !ttsManager.isSpeaking.value) {
                            startContinuousListening() // This will check isSpeaking again
                        }
                    }
                }
            }
        }
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
        Log.d(TAG, "[DEBUG] updateContinuousListeningEnabled called with: $enabled (was: $continuousListeningEnabled)")
        continuousListeningEnabled = enabled
        Log.d(TAG, "updateContinuousListeningEnabled: $enabled")
        
        if (enabled) {
            Log.d(TAG, "[DEBUG] Calling startContinuousListening()")
            // Start continuous listening immediately
            startContinuousListening()
            Log.d(TAG, "[DEBUG] startContinuousListening() returned")
        } else {
            // Stop listening if disabled
            stopListening()
        }
    }
    
    fun setTranscriptionCallback(callback: (String) -> Unit) {
        transcriptionCallback = callback
    }

    // Helper method to determine if mic button should be shown during TTS
    fun shouldShowMicButtonDuringTTS(): Boolean {
        return ttsManager.shouldShowMicButtonDuringTTS()
    }

    // Handle mic button click during TTS - pause TTS and start listening
    fun handleMicClickDuringTTS() {
        if (ttsManager.isSpeaking.value && !ttsManager.areHeadphonesConnected()) {
            Log.d(TAG, "handleMicClickDuringTTS: Pausing TTS and starting listening")
            
            // Pause/stop TTS (but keep voice response enabled for future messages)
            stopSpeaking()
            
            // Enable continuous listening and start immediately
            continuousListeningEnabled = true
            
            // Use reactive state observation instead of delay
            // Observe when TTS actually stops speaking, but only check once
            coroutineScope.launch {
                ttsManager.isSpeaking.first { !it } // Wait for first emission where isSpeaking is false
                if (continuousListeningEnabled) {
                    startContinuousListening()
                }
            }
        }
    }

    // Cleanup method - typically called when the app is destroyed
    // As a Singleton, this will rarely be called in practice
    fun cleanup() {
        Log.d(TAG, "Cleaning up VoiceManager resources")
        coroutineScope.cancel()
        ttsManager.shutdown()
        speechRecognitionService.release()
    }
} 