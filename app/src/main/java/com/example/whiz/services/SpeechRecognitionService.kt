package com.example.whiz.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "SpeechRecognition"
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionListener: RecognitionListener? = null

    // --- State Flows ---
    private val _transcriptionState = MutableStateFlow("")
    val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    // --- Internal State ---
    private var recognitionCallback: ((String) -> Unit)? = null
    private var manualStopInProgress = false
    @Volatile
    private var utteranceFinalized = false
    private var recognizerIntent: Intent? = null // Store the intent for restarting
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) // Scope for delays
    private var _isInitialized = false
    
    // 🔧 Smart rate limiting - only for error-based restarts
    private var lastErrorTime = 0L
    private var errorRestartCount = 0
    private val ERROR_RESTART_WINDOW_MS = 5000L // Window for counting rapid errors
    private val MAX_ERROR_RESTARTS = 3 // Max error restarts within window

    // 🔧 Partial concatenation for premature end-of-speech detection
    private var lastPartialTimestamp = 0L
    private var savedPartialForConcatenation = ""
    private val PREMATURE_END_THRESHOLD_MS = 1500L // If end-of-speech fires within this time of last partial, it's probably premature
    private val SAVED_PARTIAL_TIMEOUT_MS = 5000L // Clear saved partial if no new speech within this time
    private var savedPartialTimeoutJob: kotlinx.coroutines.Job? = null
    
    val isInitialized: Boolean
        get() = _isInitialized

    // --- Continuous Listening State ---
    // Note: Continuous listening state is now managed by VoiceManager
    // This callback provides the current state when needed
    var continuousListeningCallback: (() -> Boolean)? = null

    // Callback to check if we should actually restart listening (considers all conditions)
    var shouldRestartCallback: (() -> Boolean)? = null

    // --- Test Support ---
    // Allow tests to simulate partial transcriptions without real speech recognizer
    @Volatile
    private var testModeEnabled = false
    private var isTestInjectedCallback = false

    fun initialize() {
        // Ensure initialization always happens on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                initialize()
            }
            return
        }
        
        // Check availability first without doing anything that could crash
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device.")
            _errorState.value = "Speech recognition not available."
            _isInitialized = false
            return
        }
        
        // Reset all state
        _isListening.value = false
        _transcriptionState.value = ""
        manualStopInProgress = false
        utteranceFinalized = false
        recognitionCallback = null
        
        // Safely release any existing recognizer first
        cleanup()
        
        // Setup intent
        setupRecognizerIntent()
        
        // Create the new recognizer with error handling
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognitionListener = createRecognitionListener()
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Log.d(TAG, "Speech recognizer initialized successfully.")
            _errorState.value = null
            _isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechRecognizer", e)
            _errorState.value = "Failed to initialize speech service."
            cleanup()
            _isInitialized = false
        }
    }

    // Setup the intent properties once
    private fun setupRecognizerIntent() {
        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Setting these might influence when onEndOfSpeech/onResults are called
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L) // Increased pause detection
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            // Consider removing minimum length if causing issues:
            // putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        }
    }


    fun startListening(callback: (String) -> Unit) {
        // 🔧 ALWAYS set the callback first, even if rate limited, in case speech recognition is already active
        recognitionCallback = callback

        // 🧪 TEST MODE: If test mode is enabled, skip initialization checks and just set listening state
        // This allows tests to inject simulated transcriptions on devices without speech recognition (e.g., CI emulators)
        if (testModeEnabled) {
            Log.d(TAG, "[TEST] Test mode enabled - setting isListening=true without real speech recognizer")
            _isListening.value = true
            _errorState.value = null
            utteranceFinalized = false
            manualStopInProgress = false
            return
        }

        if (!isInitialized) {
            initialize() // Try to initialize if not initialized
            if (!isInitialized) {
                Log.e(TAG, "Failed to initialize speech recognition")
                _errorState.value = "Failed to initialize speech recognition"
                return
            }
        }

        if (_isListening.value) {
            return
        }

        try {
            // Ensure we have a clean state before starting
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                if (speechRecognizer == null) {
                    Log.e(TAG, "Failed to create SpeechRecognizer instance")
                    _errorState.value = "Failed to create speech recognizer"
                    return
                }
                recognitionListener = createRecognitionListener()
                speechRecognizer?.setRecognitionListener(recognitionListener)
            }

            // Validate the recognizer is in a good state
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available")
                _errorState.value = "Speech recognition not available"
                return
            }

            _isListening.value = true // Set listening state to true
            _errorState.value = null
            utteranceFinalized = false
            manualStopInProgress = false

            setupRecognizerIntent()
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            _errorState.value = "Error starting speech recognition: ${e.message}"
            _isListening.value = false // Reset listening state on error
            recognitionCallback = null

            // Force cleanup and reinitialize on error
            cleanup()

            // Try to reinitialize for next attempt
            initialize()
        }
    }

    fun stopListening() {
        Log.d(TAG, "[DEBUG] stopListening called. isListening=${_isListening.value}")

        try {
            Log.d(TAG, "[DEBUG] Stopping speech recognition forcefully")
            manualStopInProgress = true

            // Note: Continuous listening is now managed by VoiceManager

            // First cancel any ongoing recognition
            speechRecognizer?.cancel()

            // Then stop listening
            speechRecognizer?.stopListening()

            // Force state to false immediately
            _isListening.value = false

            // 🔧 BUG FIX: DO NOT clear callback here! Let onResults() deliver final transcription first
            // recognitionCallback = null  // ← REMOVED: This was preventing final results delivery

            // Clear transcription state and saved partial (user explicitly stopped)
            _transcriptionState.value = ""
            savedPartialForConcatenation = ""
            lastPartialTimestamp = 0L
            savedPartialTimeoutJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
            _errorState.value = "Error stopping speech recognition: ${e.message}"
            _isListening.value = false
            // 🔧 BUG FIX: Don't clear callback on error either - let onResults() handle cleanup
            // recognitionCallback = null  // ← REMOVED: This was preventing final results delivery
            // Note: Continuous listening is now managed by VoiceManager
            // Try to clean up
            cleanup()
            savedPartialForConcatenation = ""
            lastPartialTimestamp = 0L
            savedPartialTimeoutJob?.cancel()
        }
    }

    fun release() {
        Log.i(TAG, "Releasing SpeechRecognitionService resources.")
        serviceScope.cancel() // Cancel all pending coroutines
        cleanup()
        recognitionCallback = null
        _isInitialized = false
    }

    /**
     * Safely cleanup SpeechRecognizer and related resources
     * Ensures all SpeechRecognizer operations run on the main thread
     */
    private fun cleanup() {
        try {
            speechRecognizer?.let { recognizer ->
                // Ensure SpeechRecognizer operations run on main thread
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    // Already on main thread, proceed directly
                    performCleanupOperations(recognizer)
                } else {
                    // Post to main thread
                    Handler(Looper.getMainLooper()).post {
                        performCleanupOperations(recognizer)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during cleanup", e)
        }
        
        // Clear the reference regardless of thread
        speechRecognizer = null
        recognitionListener = null
    }
    
    /**
     * Perform the actual cleanup operations on the main thread
     */
    private fun performCleanupOperations(recognizer: SpeechRecognizer) {
        try {
            recognizer.cancel() // Cancel any ongoing recognition
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling speech recognizer", e)
        }
        
        try {
            recognizer.setRecognitionListener(null) // Clear listener before destroy
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing recognition listener", e)
        }
        
        try {
            recognizer.destroy() // Destroy the recognizer
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying speech recognizer", e)
        }
        
        // Reset state flags
        _isListening.value = false
        _transcriptionState.value = ""
        manualStopInProgress = false
        utteranceFinalized = false
        recognizerIntent = null
    }

    private fun releaseInternal(isReinitializing: Boolean) {
        cleanup()
        if (!isReinitializing) {
            _isInitialized = false
        }
    }


    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[DEBUG] onReadyForSpeech")
                _errorState.value = null
                manualStopInProgress = false

                // CRITICAL: Re-check if we should still be listening
                // This prevents race conditions where bubble is dismissed after restart is initiated
                val shouldStillBeListening = shouldRestartCallback?.invoke() ?: (continuousListeningCallback?.invoke() ?: false)
                if (!shouldStillBeListening) {
                    Log.w(TAG, "🔄 RESTART_DEBUG: onReadyForSpeech - shouldRestartCallback now returns false, stopping immediately")
                    stopListening()
                }
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[DEBUG] onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) { /* ... */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* ... */ }

            override fun onEndOfSpeech() {
                // If in test mode, ignore real speech recognizer callbacks to prevent conflicts
                if (testModeEnabled) {
                    Log.d(TAG, "[TEST MODE] Ignoring real speech recognizer end-of-speech callback")
                    return
                }

                val continuousListeningEnabled = continuousListeningCallback?.invoke() ?: false
                val shouldRestart = shouldRestartCallback?.invoke() ?: continuousListeningEnabled
                val timeSinceLastPartial = System.currentTimeMillis() - lastPartialTimestamp
                val currentPartial = _transcriptionState.value

                Log.d(TAG, "🔄 RESTART_DEBUG: onEndOfSpeech - manualStopInProgress: $manualStopInProgress, continuousListeningEnabled: $continuousListeningEnabled, shouldRestart: $shouldRestart, _isListening: ${_isListening.value}, timeSinceLastPartial: ${timeSinceLastPartial}ms, currentPartial: '$currentPartial'")

                // User stopped talking or recognizer hit a silence timeout.
                if (manualStopInProgress) {
                    Log.d(TAG, "🔄 RESTART_DEBUG: EndOfSpeech - not restarting (manual stop in progress)")
                    _isListening.value = false
                    manualStopInProgress = false
                    return
                }

                // For continuous listening, check if this is a premature end-of-speech
                if (shouldRestart) {
                    val isPrematureEnd = timeSinceLastPartial < PREMATURE_END_THRESHOLD_MS && currentPartial.isNotBlank()

                    if (isPrematureEnd) {
                        // 🔧 Premature end-of-speech detected - save partial for concatenation
                        Log.d(TAG, "🔄 RESTART_DEBUG: Premature end-of-speech detected (${timeSinceLastPartial}ms < ${PREMATURE_END_THRESHOLD_MS}ms), saving partial for concatenation: '$currentPartial'")
                        savedPartialForConcatenation = currentPartial
                        // Don't clear transcription state - keep showing it in UI

                        // Start a timeout to send the saved partial if user doesn't continue
                        savedPartialTimeoutJob?.cancel()
                        savedPartialTimeoutJob = serviceScope.launch {
                            delay(SAVED_PARTIAL_TIMEOUT_MS)
                            if (savedPartialForConcatenation.isNotBlank()) {
                                Log.d(TAG, "🔄 RESTART_DEBUG: Saved partial timed out after ${SAVED_PARTIAL_TIMEOUT_MS}ms, sending as final: '$savedPartialForConcatenation'")
                                val partialToSend = savedPartialForConcatenation
                                savedPartialForConcatenation = ""
                                // Send the partial as the final transcription
                                _transcriptionState.value = partialToSend
                                recognitionCallback?.invoke(partialToSend)
                                // Clear transcription state after sending
                                _transcriptionState.value = ""
                            }
                        }
                    } else {
                        Log.d(TAG, "🔄 RESTART_DEBUG: Normal end-of-speech (${timeSinceLastPartial}ms >= ${PREMATURE_END_THRESHOLD_MS}ms or no partial), letting onResults deliver")
                        // Clear any saved partial since this is a real pause
                        savedPartialForConcatenation = ""
                        savedPartialTimeoutJob?.cancel()
                    }

                    try {
                        Log.d(TAG, "🔄 RESTART_DEBUG: Attempting to restart listening after end of speech (continuous mode)")
                        // 🔧 Don't cancel! Let onResults be delivered naturally
                        // The new startListening will implicitly end the previous session
                        speechRecognizer?.startListening(recognizerIntent)
                        Log.d(TAG, "🔄 RESTART_DEBUG: Successfully restarted listening after end of speech")
                        // Don't set _isListening to false when we're restarting!
                    } catch (e: Exception) {
                        Log.e(TAG, "🔄 RESTART_DEBUG: Error restarting listening after end of speech", e)
                        _isListening.value = false
                        _errorState.value = "Error restarting listening: ${e.message}"
                    }
                } else {
                    Log.d(TAG, "🔄 RESTART_DEBUG: Not restarting - shouldRestart=$shouldRestart")
                    _isListening.value = false
                }
            }

            override fun onError(error: Int) {
                // If in test mode, ignore real speech recognizer callbacks to prevent conflicts
                if (testModeEnabled) {
                    Log.d(TAG, "[TEST MODE] Ignoring real speech recognizer error callback (error=$error)")
                    return
                }

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown speech recognition error (code: $error)"
                }
                
                Log.d(TAG, "🔄 RESTART_DEBUG: onError called - error=$error ($errorMessage), manualStopInProgress=$manualStopInProgress")
                
                // Only show user-visible errors for critical issues, not for expected errors like NO_MATCH
                if (shouldShowError(error, manualStopInProgress)) {
                    Log.e(TAG, "Speech recognition error: $errorMessage (code $error)")
                    _errorState.value = errorMessage
                }

                // --- Continuous listening auto-restart logic with smart rate limiting ---
                val continuousListeningEnabled = continuousListeningCallback?.invoke() ?: false
                // Use shouldRestartCallback if available, otherwise fall back to continuousListeningEnabled
                val shouldRestart = shouldRestartCallback?.invoke() ?: continuousListeningEnabled

                Log.d(TAG, "🔄 RESTART_DEBUG: Checking auto-restart conditions - continuousListeningEnabled=$continuousListeningEnabled, shouldRestart=$shouldRestart, error matches=${(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT)}")

                // Auto-restart for NO_MATCH, SPEECH_TIMEOUT, and ERROR_CLIENT
                // ERROR_CLIENT typically occurs when restarting too quickly (race condition with previous session cleanup)
                // and should be retried automatically in continuous listening mode
                val willRestart = (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT) && shouldRestart && !manualStopInProgress

                if (willRestart) {
                    // Smart rate limiting: only prevent restart if too many rapid errors
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastErrorTime > ERROR_RESTART_WINDOW_MS) {
                        // Reset error count if outside the window
                        errorRestartCount = 0
                    }
                    lastErrorTime = currentTime
                    errorRestartCount++
                    
                    Log.d(TAG, "🔄 RESTART_DEBUG: Error qualifies for restart - errorRestartCount=$errorRestartCount, MAX_ERROR_RESTARTS=$MAX_ERROR_RESTARTS")
                    
                    if (errorRestartCount <= MAX_ERROR_RESTARTS && shouldRestart && !manualStopInProgress) {
                        Log.d(TAG, "🔄 RESTART_DEBUG: Auto-restarting listening after error (attempt $errorRestartCount)")

                        // FIX: Reset listening state before restart - the recognizer is dead after an error
                        // Without this, startListening() early-returns because _isListening is still true
                        _isListening.value = false

                        // Add delay for ERROR_CLIENT to allow Android to clean up previous session
                        if (error == SpeechRecognizer.ERROR_CLIENT) {
                            Log.d(TAG, "🔄 RESTART_DEBUG: Adding 500ms delay before restart (ERROR_CLIENT)")
                            Handler(Looper.getMainLooper()).postDelayed({
                                startListening(recognitionCallback ?: { })
                            }, 500)
                        } else {
                            startListening(recognitionCallback ?: { })
                        }
                    } else {
                        Log.w(TAG, "🔄 RESTART_DEBUG: Auto-restart blocked: $errorRestartCount rapid errors within ${ERROR_RESTART_WINDOW_MS}ms or shouldRestart=$shouldRestart")
                        // Set listening to false only when we're NOT restarting (rate limited)
                        _isListening.value = false
                    }
                } else {
                    Log.d(TAG, "🔄 RESTART_DEBUG: Auto-restart conditions not met - skipping restart")
                    // Set listening to false only when we're NOT restarting (conditions not met)
                    _isListening.value = false
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "[DEBUG] onResults")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var finalText = matches?.firstOrNull() ?: ""

                // 🔧 If we have a saved partial from a previous session, merge it with the final result
                if (savedPartialForConcatenation.isNotBlank() && finalText.isNotBlank()) {
                    val originalFinal = finalText
                    finalText = mergeOverlapping(savedPartialForConcatenation, finalText)
                    Log.d(TAG, "[DEBUG] 🔗 MERGED final result: saved='$savedPartialForConcatenation' + final='$originalFinal' -> '$finalText'")
                    savedPartialForConcatenation = "" // Clear after use
                    savedPartialTimeoutJob?.cancel()
                }

                Log.d(TAG, "[DEBUG] Final transcription: '$finalText'")
                _transcriptionState.value = finalText

                // Send to bubble overlay if active
                try {
                    BubbleOverlayService.updateUserTranscription(finalText)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not update bubble overlay: ${e.message}")
                }

                if (recognitionCallback != null) {
                    if (finalText.isNotBlank()) {
                        Log.d(TAG, "[DEBUG] Delivering final transcription: '$finalText'")
                        // Deliver final transcription even if manually stopped
                        // This ensures the user gets their speech text in the input field
                        recognitionCallback?.invoke(finalText)
                    }
                }

                // Check if we're in continuous listening mode
                val shouldRestart = shouldRestartCallback?.invoke() ?: (continuousListeningCallback?.invoke() ?: false)

                if (!shouldRestart) {
                    // Only clear listening state if NOT in continuous mode
                    _isListening.value = false
                    // Clear callback after results are delivered (safe to clear now)
                    recognitionCallback = null
                } else {
                    Log.d(TAG, "[DEBUG] Continuous listening mode - keeping callback and listening state")
                    // Don't clear the callback or listening state in continuous mode
                    // onEndOfSpeech will handle the restart
                }

                // 🔧 Clear transcription state after callback to prevent UI from showing stale text
                Log.d(TAG, "[DEBUG] 🧹 CLEARING transcription state (was: '$finalText', partial may have been: '${_transcriptionState.value}')")
                _transcriptionState.value = ""
                Log.d(TAG, "[DEBUG] Cleared transcription state after processing results")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "[DEBUG] onPartialResults")

                // If in test mode, ignore real speech recognizer callbacks to prevent conflicts
                if (testModeEnabled && !isTestInjectedCallback) {
                    Log.d(TAG, "[TEST MODE] Ignoring real speech recognizer partial callback")
                    return
                }

                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                var partialText = matches?.firstOrNull() ?: ""

                // 🔧 If we have a saved partial from a previous session (premature end-of-speech),
                // merge it with the new partial using overlap detection
                if (savedPartialForConcatenation.isNotBlank() && partialText.isNotBlank()) {
                    val originalPartial = partialText
                    partialText = mergeOverlapping(savedPartialForConcatenation, partialText)
                    Log.d(TAG, "[DEBUG] 🔗 MERGED partial: saved='$savedPartialForConcatenation' + partial='$originalPartial' -> '$partialText'")
                }

                Log.d(TAG, "[DEBUG] 🎙️ PARTIAL transcription: '$partialText' (previous: '${_transcriptionState.value}')")

                // Ignore empty partial results to prevent clearing user's spoken text
                // Empty partials can occur due to TTS interference, pauses, or recognition resets
                if (partialText.isNotBlank()) {
                    _transcriptionState.value = partialText
                    lastPartialTimestamp = System.currentTimeMillis()
                    // Clear saved partial once we've successfully concatenated and got new speech
                    if (savedPartialForConcatenation.isNotBlank()) {
                        Log.d(TAG, "[DEBUG] 🧹 Clearing savedPartialForConcatenation after successful concatenation")
                        savedPartialForConcatenation = ""
                        savedPartialTimeoutJob?.cancel() // Cancel the timeout since we used the partial
                    }
                } else {
                    Log.d(TAG, "[DEBUG] Ignoring empty partial result to preserve previous transcription")
                }

                // Note: We only update the transcription state for UI display.
                // We do NOT send partial results to bubble overlay to avoid creating
                // duplicate messages. Only onResults() should trigger message sends.
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "[DEBUG] onEvent: $eventType")
            }
        }
    }


    private fun finalizeTranscription(context: String) {
        val finalText = _transcriptionState.value.trim()
        Log.d(TAG,"finalizeTranscription called from [$context]. Text: '$finalText', Finalized Flag: $utteranceFinalized")

        if (utteranceFinalized) {
            Log.w(TAG,"Attempted to finalize transcription [$context] but already finalized.")
            // Don't clear state here if it was already cleared or holds next partial
            return
        }

        if (finalText.isNotEmpty()) {
            Log.i(TAG, "Finalizing and invoking callback from [$context] for: '$finalText'")
            try {
                recognitionCallback?.invoke(finalText)
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking recognition callback", e)
            }
            utteranceFinalized = true
            // Clear the state *immediately* after finalization and setting flag
            _transcriptionState.value = ""
        } else {
            Log.d(TAG, "finalizeTranscription called from [$context] with empty text.")
            // Don't set utteranceFinalized true if text was empty.
            // Clear state if it wasn't already empty.
            if (_transcriptionState.value.isNotEmpty()) _transcriptionState.value = ""
        }
    }

    // --- Helper methods getErrorMessage, shouldShowError remain the same ---
    /**
     * Merges two strings by finding the longest overlap between the suffix of the first
     * and the prefix of the second. This handles cases where speech recognition splits
     * a phrase and the partial doesn't exactly match the start of the final result.
     *
     * Examples:
     * - mergeOverlapping("the quick brown", "quick brown fox") -> "the quick brown fox"
     * - mergeOverlapping("hello world", "world is great") -> "hello world is great"
     * - mergeOverlapping("no overlap", "different text") -> "no overlap different text"
     */
    private fun mergeOverlapping(first: String, second: String): String {
        if (first.isBlank()) return second
        if (second.isBlank()) return first

        // If second already starts with first, no merging needed
        if (second.startsWith(first, ignoreCase = true)) {
            return second
        }

        // If first already ends with second (second is substring of first's end), just return first
        if (first.endsWith(second, ignoreCase = true)) {
            return first
        }

        val firstLower = first.lowercase()
        val secondLower = second.lowercase()

        // Find the longest suffix of first that matches a prefix of second
        // Start from the longest possible overlap and work down
        val maxOverlap = minOf(first.length, second.length)

        for (overlapLen in maxOverlap downTo 1) {
            val suffix = firstLower.takeLast(overlapLen)
            val prefix = secondLower.take(overlapLen)

            if (suffix == prefix) {
                // Found overlap - merge by taking first + remainder of second
                val result = first + second.drop(overlapLen)
                Log.d(TAG, "[DEBUG] 🔗 MERGE_OVERLAP: '$first' + '$second' -> '$result' (overlap: '$suffix')")
                return result
            }
        }

        // No overlap found - simple concatenation with space
        Log.d(TAG, "[DEBUG] 🔗 MERGE_NO_OVERLAP: '$first' + '$second' (no overlap found)")
        return "$first $second"
    }

    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient microphone permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No recognition match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
            else -> "Unknown speech recognition error ($error)"
        }
    }

    private fun shouldShowError(error: Int, wasManuallyStopping: Boolean): Boolean {
        return when (error) {
            // NEVER show CLIENT error toast as it seems benign in restarts
            SpeechRecognizer.ERROR_CLIENT -> false

            // Don't show errors expected during normal operation or manual stops
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> false // Expected if user is silent or pauses

            // Show critical errors (unless manually stopping for some?)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> true // Always show these

            // Show unknown errors
            else -> true
        }
    }

    // ==================== TEST SUPPORT METHODS ====================

    /**
     * Enable test mode to allow simulating speech recognition through real callbacks.
     * In test mode, we inject results through the actual onPartialResults/onResults callbacks,
     * allowing the real SpeechRecognizer to run (and fail gracefully) while tests control the input.
     */
    fun enableTestMode() {
        testModeEnabled = true
        Log.d(TAG, "[TEST] Test mode enabled - will inject results through real callbacks")
    }

    /**
     * Disable test mode and return to normal operation.
     */
    fun disableTestMode() {
        testModeEnabled = false
        Log.d(TAG, "[TEST] Test mode disabled")
    }

    /**
     * Simulate a partial transcription result by injecting through the real onPartialResults callback.
     * This goes through the exact same code path as real speech recognition.
     *
     * This is a suspend function that waits for the main thread to process the callback,
     * matching production behavior where callbacks run synchronously on the main thread.
     *
     * @param partialText The partial transcription text to simulate
     */
    suspend fun testSetPartialTranscription(partialText: String) {
        if (!testModeEnabled) {
            Log.w(TAG, "[TEST] testSetPartialTranscription called but test mode not enabled!")
            return
        }

        Log.d(TAG, "[TEST] Injecting partial result through real callback: '$partialText'")

        // Create a Bundle like Android's SpeechRecognizer would
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partialText))
        }

        // Inject through the REAL onPartialResults callback on main thread and WAIT for it
        // This matches production behavior where callbacks run synchronously on main thread
        withContext(Dispatchers.Main) {
            isTestInjectedCallback = true
            try {
                recognitionListener?.onPartialResults(bundle)
            } finally {
                isTestInjectedCallback = false
            }
        }
    }

    /**
     * Simulate a final transcription result by injecting through the real onResults callback.
     * This goes through the exact same code path as real speech recognition.
     *
     * @param finalText The final transcription text to simulate
     */
    suspend fun testSendFinalTranscription(finalText: String) {
        if (!testModeEnabled) {
            Log.w(TAG, "[TEST] testSendFinalTranscription called but test mode not enabled!")
            return
        }

        Log.d(TAG, "[TEST] Injecting final result through real callback: '$finalText'")

        // Create a Bundle like Android's SpeechRecognizer would
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(finalText))
        }

        // Inject through the REAL onResults callback on main thread
        withContext(Dispatchers.Main) {
            recognitionListener?.onResults(bundle)
        }
    }
}