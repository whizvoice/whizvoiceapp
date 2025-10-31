package com.example.whiz.ui.viewmodels

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val ttsManager: TTSManager,  // Make public for bubble access
    private val userPreferences: UserPreferences,
    private val appLifecycleService: AppLifecycleService
) {

    private val TAG = "VoiceManager"

    companion object {
        @Volatile
        var instance: VoiceManager? = null
            private set
    }

    init {
        instance = this
    }

    // Create a coroutine scope for this singleton service
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Screen lock detection
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val _isScreenLocked = MutableStateFlow(keyguardManager.isKeyguardLocked)
    private val isScreenLocked: StateFlow<Boolean> = _isScreenLocked.asStateFlow()

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned off - stopping microphone and destroying recognizer")
                    _isScreenLocked.value = true
                    // Stop and completely destroy the recognizer when screen turns off
                    if (isListening.value) {
                        stopListening()
                        // Force destroy the recognizer to ensure it's completely stopped
                        speechRecognitionService.release()
                        speechRecognitionService.initialize()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen turned on - updating lock state")
                    // When screen turns on, it might still be locked (showing lock screen)
                    // Update the lock state and STOP listening if still locked
                    val isLocked = keyguardManager.isKeyguardLocked
                    _isScreenLocked.value = isLocked

                    if (isLocked) {
                        Log.d(TAG, "Screen is on but still locked - destroying recognizer to prevent audio pickup")
                        if (isListening.value) {
                            stopListening()
                        }
                        // Force destroy any lingering recognizer instances
                        speechRecognitionService.release()
                        speechRecognitionService.initialize()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen unlocked (user present)")
                    _isScreenLocked.value = false
                    // Restart listening if continuous mode was enabled
                    if (continuousListeningEnabled && appLifecycleService.isInForeground()) {
                        Log.d(TAG, "Screen unlocked - restarting continuous listening")
                        coroutineScope.launch {
                            delay(100L) // Small delay to ensure state is settled
                            if (shouldBeListening()) {
                                startContinuousListening()
                            }
                        }
                    }
                }
            }
        }
    }

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

    // Track continuous listening state before TTS started (to restore after TTS finishes)
    private var continuousListeningBeforeTTS: Boolean? = null

    // Track current voice settings to know when we need to reset
    private var currentVoiceSettings: com.example.whiz.data.preferences.VoiceSettings? = null

    // Track TTS state before backgrounding so bubble service can restore it if needed
    // Package-private so BubbleOverlayService can clear it when bubble session ends
    @Volatile
    var ttsStateBeforeBackground: Boolean? = null

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
        observeScreenLockState()

        // Register screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenStateReceiver, filter)

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
        val screenNotLocked = !isScreenLocked.value

        // Check bubble mode - if bubble is active and mic is off, don't listen
        val bubbleMode = BubbleOverlayService.bubbleListeningMode
        val bubbleAllowsListening = !isBubbleActive ||
            (bubbleMode == ListeningMode.CONTINUOUS_LISTENING || bubbleMode == ListeningMode.TTS_WITH_LISTENING)

        // Keep listening if either in foreground OR bubble is active (with mic enabled)
        // BUT only if screen is NOT locked (unless bubble is active)
        val should = continuousListeningEnabled && (isInForeground || isBubbleActive) &&
                    hasPermission && notSpeaking && bubbleAllowsListening &&
                    (screenNotLocked || isBubbleActive) // Allow listening when screen locked ONLY if bubble is active

        Log.d(TAG, "shouldBeListening check: continuousEnabled=$continuousListeningEnabled, " +
                "foreground=$isInForeground, bubble=$isBubbleActive, bubbleMode=$bubbleMode, " +
                "permission=$hasPermission, notSpeaking=$notSpeaking, screenNotLocked=$screenNotLocked, result=$should")

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
                Log.d(TAG, "TTS started - continuous listening state before TTS: $continuousListeningBeforeTTS")
                // No need to set _isSpeaking - TTSManager handles it
            },
            onCompleted = {
                Log.d(TAG, "TTS completed - restoring continuous listening state to: $continuousListeningBeforeTTS")
                // No need to set _isSpeaking - TTSManager handles it

                // Restore continuous listening to whatever it was before TTS started
                // This preserves the user's preference (on or off)
                val shouldRestoreContinuousListening = continuousListeningBeforeTTS ?: false
                continuousListeningBeforeTTS = null // Clear the saved state

                if (shouldRestoreContinuousListening) {
                    Log.d(TAG, "TTS completed - restarting continuous listening as it was enabled before TTS")
                    startContinuousListening()
                } else {
                    Log.d(TAG, "TTS completed - not restarting continuous listening as it was disabled before TTS")
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

    private fun observeScreenLockState() {
        // Listen for screen lock state changes
        coroutineScope.launch {
            isScreenLocked.collect { locked ->
                Log.d(TAG, "Screen lock state changed: locked=$locked")
                if (locked && isListening.value) {
                    Log.d(TAG, "Screen locked - stopping microphone")
                    stopListening()
                }
            }
        }
    }
    
    private fun onAppBackgrounded() {
        Log.d(TAG, "onAppBackgrounded called. continuousListeningEnabled=$continuousListeningEnabled")

        // Note: TTS state is saved by ChatViewModel before disabling
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
            // Remember continuous listening state before TTS starts so we can restore it later
            continuousListeningBeforeTTS = continuousListeningEnabled
            Log.d(TAG, "speak: Saved continuous listening state before TTS: $continuousListeningBeforeTTS")

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
        val bubbleActive = BubbleOverlayService.isActive
        val bubbleMode = BubbleOverlayService.bubbleListeningMode
        val voiceEnabled = _isVoiceResponseEnabled.value

        Log.d(TAG, "shouldEnableTTS: bubbleActive=$bubbleActive, bubbleMode=$bubbleMode, voiceEnabled=$voiceEnabled")

        // If bubble is active, use bubble mode to determine TTS state
        if (bubbleActive) {
            if (bubbleMode == ListeningMode.TTS_WITH_LISTENING) {
                Log.d(TAG, "shouldEnableTTS: Returning true due to bubble TTS_WITH_LISTENING mode")
                return true
            } else {
                // CONTINUOUS_LISTENING or MIC_OFF modes should have TTS off
                Log.d(TAG, "shouldEnableTTS: Returning false due to bubble mode $bubbleMode (not TTS mode)")
                return false
            }
        }

        // Bubble not active, use the normal voice response setting
        Log.d(TAG, "shouldEnableTTS: Returning $voiceEnabled from voice response setting (bubble not active)")
        return voiceEnabled
    }

    fun startListening(callback: (String) -> Unit) {
        speechRecognitionService.startListening(callback)
    }

    fun stopListening() {
        speechRecognitionService.stopListening()
    }

    // Transcription flow for continuous listening - consumers can collect from this
    private val _transcriptionFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val transcriptionFlow: SharedFlow<String> = _transcriptionFlow.asSharedFlow()

    // Transcription callback for continuous listening (set by consumers like ChatViewModel)
    // TODO: This can be deprecated once all consumers move to using transcriptionFlow
    private var transcriptionCallback: ((String) -> Unit)? = null
    
    fun startContinuousListening() {
        Log.d(TAG, "[DEBUG] startContinuousListening() called, continuousListeningEnabled=$continuousListeningEnabled")
        if (!continuousListeningEnabled) {
            Log.d(TAG, "startContinuousListening called but continuous listening is disabled")
            return
        }

        // Check if we should actually be listening (includes screen lock check)
        if (!shouldBeListening()) {
            Log.d(TAG, "startContinuousListening called but shouldBeListening() returned false - not starting")
            return
        }

        Log.d(TAG, "[DEBUG] About to call startListening()")
        startListening { finalText ->
            Log.d(TAG, "startContinuousListening: got transcription. continuousListeningEnabled=$continuousListeningEnabled, text='$finalText'")

            // Call the transcription callback if set (for chat integration)
            if (finalText.isNotBlank()) {
                // Emit to flow for all consumers (ChatScreen, BubbleOverlayService, etc.)
                coroutineScope.launch {
                    _transcriptionFlow.emit(finalText)
                }

                // Also call legacy callback if set (for backward compatibility)
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

    // Handle mic button click during TTS - interrupt TTS and start listening
    fun handleMicClickDuringTTS() {
        if (ttsManager.isSpeaking.value) {
            Log.d(TAG, "handleMicClickDuringTTS: User interrupted TTS - starting listening")

            // Stop TTS immediately (user wants to speak)
            stopSpeaking()

            // Start listening immediately for this one interaction
            // The continuous listening state will be restored when TTS completes (via callback)
            coroutineScope.launch {
                // Wait for TTS to actually stop
                ttsManager.isSpeaking.first { !it }
                Log.d(TAG, "handleMicClickDuringTTS: TTS stopped, starting listening for user input")
                // Start a one-time listening session (continuous mode will be restored by TTS callback)
                startContinuousListening()
            }
        }
    }

    // Cleanup method - typically called when the app is destroyed
    // As a Singleton, this will rarely be called in practice
    fun cleanup() {
        Log.d(TAG, "Cleaning up VoiceManager resources")
        try {
            context.unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering screen state receiver", e)
        }
        coroutineScope.cancel()
        ttsManager.shutdown()
        speechRecognitionService.release()
    }
} 