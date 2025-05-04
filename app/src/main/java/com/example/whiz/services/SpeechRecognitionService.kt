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
    private var isInitialized = false

    fun initialize() {
        // Check availability first without doing anything that could crash
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition is not available on this device.")
            _errorState.value = "Speech recognition not available."
            isInitialized = false
            return
        }
        
        setupRecognizerIntent() // Setup intent once
        
        // Safely release any existing recognizer
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing existing recognizer", e)
        }
        
        // Create the new recognizer with error handling
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            Log.d(TAG, "Speech recognizer initialized successfully.")
            _errorState.value = null
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing SpeechRecognizer", e)
            _errorState.value = "Failed to initialize speech service."
            speechRecognizer = null
            isInitialized = false
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


    fun startListening(onFinalTranscription: (String) -> Unit) {
        // Check if already listening
        if (_isListening.value) {
            Log.w(TAG, "Already listening, ignoring startListening request.")
            return
        }

        // Ensure initialized (important!)
        if (!isInitialized) {
            Log.w(TAG, "Recognizer not initialized. Attempting initialization.")
            initialize()
            if (!isInitialized) {
                _errorState.value = "Speech service not ready. Please try again."
                Log.e(TAG, "Initialization failed, cannot start listening.")
                return // Abort if init failed
            }
        }

        // Check recognizer/intent again after potential init
        if (speechRecognizer == null || recognizerIntent == null) {
            Log.e(TAG, "Cannot start listening, recognizer or intent is null AFTER init check.")
            _errorState.value = "Speech service encountered an internal error."
            _isListening.value = false // Ensure state consistency
            return
        }

        Log.d(TAG, "Preparing to start listening.")
        _transcriptionState.value = ""
        _errorState.value = null
        recognitionCallback = onFinalTranscription
        manualStopInProgress = false
        utteranceFinalized = false

        // Make sure triggerListeningStart is called correctly
        triggerListeningStart("startListening_entry") // Pass context for logging
    }

    // And ensure triggerListeningStart logs clearly and sets state:
    private fun triggerListeningStart(reason: String) {
        // ... (null checks as before) ...
        Log.d(TAG, "Attempting triggerListeningStart from [$reason]")
        try {
            utteranceFinalized = false
            // *** IMPORTANT: Set state BEFORE calling startListening ***
            _isListening.value = true
            speechRecognizer?.startListening(recognizerIntent)
            Log.i(TAG, "SpeechRecognizer successfully called startListening (Reason: $reason). isListening state: ${_isListening.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Error in triggerListeningStart [$reason]", e)
            _errorState.value = "Could not start listening."
            _isListening.value = false // Reset state on error
        }
    }


    fun stopListening() {
        if (!_isListening.value && !manualStopInProgress) {
            Log.w(TAG, "Not listening or already stopping, ignoring stopListening request.")
        }

        Log.i(TAG, "Manual stop requested.")
        manualStopInProgress = true

        // Cancel any pending restarts before stopping
        serviceScope.launch { /* cancel pending restarts if using delays */ } // Placeholder if delays were used

        speechRecognizer?.cancel()
        speechRecognizer?.stopListening() // Request stop

        if (_isListening.value) {
            _isListening.value = false
            Log.i(TAG, "Set isListening to false due to manual stop.")
        }

        val textToFinalize = _transcriptionState.value
        if (textToFinalize.isNotEmpty()) {
            Log.d(TAG, "Finalizing text on manual stop: '$textToFinalize'")
            finalizeTranscription("manualStop")
        } else {
            _transcriptionState.value = ""
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
                Log.d(TAG, "onReadyForSpeech")
                _errorState.value = null
                manualStopInProgress = false
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                // Resetting here is good, but resetting before startListening is more reliable
                // utteranceFinalized = false // Keep this for safety, but primary reset is before startListening call
            }

            override fun onRmsChanged(rmsdB: Float) { /* ... */ }
            override fun onBufferReceived(buffer: ByteArray?) { /* ... */ }

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                // User stopped talking or recognizer hit a silence timeout.
                if (manualStopInProgress) {
                    _isListening.value = false
                    manualStopInProgress = false
                    Log.d(TAG,"EndOfSpeech handled after manual stop.")
                } else if (_isListening.value) {
                    // If still in voice mode, explicitly restart listening for the next phrase
                    Log.d(TAG,"Natural end of speech detected, explicitly restarting listener.")
                    // Add a small delay to prevent immediate restart if results are coming
                    serviceScope.launch {
                        delay(100) // Small delay (e.g., 100ms)
                        if(_isListening.value) { // Re-check state after delay
                            triggerListeningStart("onEndOfSpeech")
                        }
                    }
                }
            }

            override fun onError(error: Int) {
                val wasManuallyStopping = manualStopInProgress
                manualStopInProgress = false

                val errorMessage = getErrorMessage(error)
                Log.e(TAG, "onError: $errorMessage (code: $error), wasManuallyStopping: $wasManuallyStopping, isListening: ${_isListening.value}")

                val showError = shouldShowError(error, wasManuallyStopping)
                if (showError) { /* ... (error reporting same as before) ... */
                    if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        _errorState.value = "Speech service error. Please try again."
                    } else {
                        _errorState.value = errorMessage
                    }
                } else {
                    _errorState.value = null
                }


                // Don't finalize text on errors like NO_MATCH or CLIENT if stopping manually
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && _transcriptionState.value.isNotEmpty()) {
                    Log.d(TAG, "Finalizing transcription due to speech timeout with text.")
                    finalizeTranscription("timeoutWithError")
                }

                // Decide whether to attempt restart based on error and state
                val shouldStopListening = when(error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_SERVER -> true // Fatal errors
                    else -> false // For most others, try to restart if still in listening mode
                }

                if (shouldStopListening && _isListening.value) {
                    Log.w(TAG, "Stopping listening permanently due to error: $error")
                    _isListening.value = false // Stop listening state
                } else if (_isListening.value) {
                    // Attempt to restart on non-fatal errors if user hasn't manually stopped
                    Log.w(TAG, "Attempting to restart listening after recoverable error: $error")
                    // Add a delay before restarting after an error
                    serviceScope.launch {
                        delay(500) // Longer delay after error (e.g., 500ms)
                        if(_isListening.value) { // Re-check state after delay
                            triggerListeningStart("onErrorRestart")
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val validResult = !matches.isNullOrEmpty() && matches[0].isNotEmpty()

                if (validResult) {
                    val text = matches!![0].trim()
                    Log.i(TAG, "onResults: '$text'")
                    _transcriptionState.value = text
                    finalizeTranscription("onResults") // Finalize this utterance
                } else {
                    Log.w(TAG, "onResults received null, empty, or blank text.")
                    if (_transcriptionState.value.isNotEmpty()) _transcriptionState.value = "" // Clear any old partial
                    utteranceFinalized = false // Ensure flag allows next utterance
                }

                // ** CRITICAL: Restart listening if still in voice mode **
                if (_isListening.value) {
                    Log.d(TAG,"Restarting listener after processing results.")
                    // No delay needed usually after successful result
                    triggerListeningStart("onResultsRestart")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && matches[0].isNotEmpty()) {
                    val partialText = matches[0]
                    _transcriptionState.value = partialText
                    // If we get a partial result, it means an utterance is in progress or starting
                    // Ensure the finalized flag is false.
                    if (utteranceFinalized) {
                        Log.w(TAG, "Partial results received but utterance was marked final. Resetting flag.")
                        utteranceFinalized = false
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) { /* ... */ }
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