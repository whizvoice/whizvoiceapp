package com.example.whiz.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "SpeechRecognition"
    private var speechRecognizer: SpeechRecognizer? = null

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
    
    // 🔧 Rate limiting for restarts
    private var lastStartTime = 0L
    private val RESTART_DELAY_MS = 500L // Minimum delay between restarts
    
    val isInitialized: Boolean
        get() = _isInitialized

    // --- Continuous Listening State ---
    var continuousListeningEnabled: Boolean = false

    fun initialize() {
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
        releaseInternal(isReinitializing = true)
        
        // Setup intent
        setupRecognizerIntent()
        
        // Create the new recognizer with error handling
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            Log.d(TAG, "Speech recognizer initialized successfully.")
            _errorState.value = null
            _isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechRecognizer", e)
            _errorState.value = "Failed to initialize speech service."
            speechRecognizer = null
            _isInitialized = false
            // Try to clean up
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (cleanupError: Exception) {
                Log.e(TAG, "Error during cleanup", cleanupError)
            }
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
        Log.d(TAG, "[DEBUG] startListening called. isInitialized=$isInitialized, isListening=${_isListening.value}")
        
        // 🔧 ALWAYS set the callback first, even if rate limited, in case speech recognition is already active
        recognitionCallback = callback
        
        // 🔧 Rate limiting check - but don't return early if already listening (callback is set above)
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStartTime < RESTART_DELAY_MS && !_isListening.value) {
            Log.d(TAG, "[DEBUG] startListening rate limited and not currently listening. Last start was ${currentTime - lastStartTime}ms ago")
            return
        }
        lastStartTime = currentTime
        
        if (!isInitialized) {
            Log.w(TAG, "[DEBUG] SpeechRecognitionService not initialized")
            initialize() // Try to initialize if not initialized
            if (!isInitialized) {
                Log.e(TAG, "[DEBUG] Failed to initialize speech recognition")
                _errorState.value = "Failed to initialize speech recognition"
                return
            }
        }

        if (_isListening.value) {
            Log.w(TAG, "[DEBUG] startListening called but already listening. Callback updated, ignoring new start request.")
            return
        }

        try {
            Log.d(TAG, "[DEBUG] Actually starting speech recognition")
            _isListening.value = true // Set listening state to true
            _errorState.value = null
            utteranceFinalized = false
            manualStopInProgress = false

            if (speechRecognizer == null) {
                Log.d(TAG, "[DEBUG] Creating new SpeechRecognizer instance")
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(createRecognitionListener())
            }

            setupRecognizerIntent()
            Log.d(TAG, "[DEBUG] Calling startListening on SpeechRecognizer")
            speechRecognizer?.startListening(recognizerIntent)
            Log.d(TAG, "[DEBUG] Speech recognition started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Error starting speech recognition", e)
            _errorState.value = "Error starting speech recognition: ${e.message}"
            _isListening.value = false // Reset listening state on error
            recognitionCallback = null
            // Try to clean up
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (cleanupError: Exception) {
                Log.e(TAG, "[DEBUG] Error during cleanup", cleanupError)
            }
        }
    }

    fun stopListening() {
        Log.d(TAG, "[DEBUG] stopListening called. isListening=${_isListening.value}")
        if (!_isListening.value) {
            Log.d(TAG, "[DEBUG] Not listening, ignoring stop request")
            return
        }

        try {
            Log.d(TAG, "[DEBUG] Stopping speech recognition")
            manualStopInProgress = true
            speechRecognizer?.stopListening()
            _isListening.value = false // Set listening state to false
            recognitionCallback = null
            Log.d(TAG, "[DEBUG] Speech recognition stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[DEBUG] Error stopping speech recognition", e)
            _errorState.value = "Error stopping speech recognition: ${e.message}"
            _isListening.value = false // Ensure listening state is false on error
            recognitionCallback = null
            // Try to clean up
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (cleanupError: Exception) {
                Log.e(TAG, "[DEBUG] Error during cleanup", cleanupError)
            }
        }
    }

    fun release() {
        Log.i(TAG, "Releasing SpeechRecognitionService resources.")
        serviceScope.launch { /* cancel pending jobs */ } // Placeholder
        releaseInternal(isReinitializing = false)
        recognitionCallback = null
    }

    private fun releaseInternal(isReinitializing: Boolean) {
        if (speechRecognizer != null) {
            try {
                speechRecognizer?.cancel() // Cancel any ongoing recognition immediately
                speechRecognizer?.destroy()
                Log.d(TAG,"SpeechRecognizer destroyed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during SpeechRecognizer release", e)
            } finally {
                speechRecognizer = null
                if (!isReinitializing) {
                    _isListening.value = false
                    _transcriptionState.value = ""
                    manualStopInProgress = false
                    utteranceFinalized = false
                    recognizerIntent = null // Clear intent too
                }
            }
        } else {
            Log.d(TAG,"Recognizer was already null during release.")
        }
    }


    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "[DEBUG] onReadyForSpeech")
                _errorState.value = null
                manualStopInProgress = false
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "[DEBUG] onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) { /* ... */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* ... */ }

            override fun onEndOfSpeech() {
                Log.d(TAG, "[DEBUG] onEndOfSpeech")
                // User stopped talking or recognizer hit a silence timeout.
                if (manualStopInProgress) {
                    Log.d(TAG, "[DEBUG] EndOfSpeech handled after manual stop.")
                    _isListening.value = false
                    manualStopInProgress = false
                }
                // If still in voice mode, explicitly restart listening for the next phrase
                Log.d(TAG, "[DEBUG] Natural end of speech detected, explicitly restarting listener.")
                // Add a small delay to prevent immediate restart if results are coming
                serviceScope.launch {
                    delay(100) // Small delay (e.g., 100ms)
                    if(_isListening.value) { // Re-check state after delay
                        try {
                            // 🔧 Rate limiting check before restart
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastStartTime >= RESTART_DELAY_MS) {
                                speechRecognizer?.startListening(recognizerIntent)
                                lastStartTime = currentTime // Update last start time
                                Log.d(TAG, "[DEBUG] Restarted listening after end of speech")
                            } else {
                                Log.d(TAG, "[DEBUG] Restart after end of speech skipped due to rate limiting")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[DEBUG] Error restarting listening after end of speech", e)
                            _isListening.value = false
                            _errorState.value = "Error restarting listening: ${e.message}"
                        }
                    }
                }
            }

            override fun onError(error: Int) {
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
                Log.e(TAG, "[DEBUG] Speech recognition error: $errorMessage (code $error)")
                
                // Only show user-visible errors for critical issues, not for expected errors like NO_MATCH
                if (shouldShowError(error, manualStopInProgress)) {
                    _errorState.value = errorMessage
                } else {
                    Log.d(TAG, "[DEBUG] Error $errorMessage (code $error) not shown to user due to shouldShowError policy")
                }
                
                _isListening.value = false

                // --- Continuous listening auto-restart logic ---
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && continuousListeningEnabled) {
                    Log.d(TAG, "[LOG] Auto-restarting listening after error '$errorMessage' (code $error) because continuousListeningEnabled=true")
                    serviceScope.launch {
                        delay(500) // Increased delay to prevent rapid restarts
                        // 🔧 Additional rate limiting check before auto-restart
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastStartTime >= RESTART_DELAY_MS && continuousListeningEnabled) {
                            startListening(recognitionCallback ?: { })
                        } else {
                            Log.d(TAG, "[LOG] Auto-restart skipped due to rate limiting or disabled continuous listening")
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d(TAG, "[DEBUG] onResults")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalText = matches?.firstOrNull() ?: ""
                Log.d(TAG, "[DEBUG] Final transcription: '$finalText'")
                _transcriptionState.value = finalText
                recognitionCallback?.invoke(finalText)
                _isListening.value = false
                
                // 🔧 Clear transcription state after callback to prevent UI from showing stale text
                _transcriptionState.value = ""
                Log.d(TAG, "[DEBUG] Cleared transcription state after processing results")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                Log.d(TAG, "[DEBUG] onPartialResults")
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull() ?: ""
                Log.d(TAG, "[DEBUG] Partial transcription: '$partialText'")
                _transcriptionState.value = partialText
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
}