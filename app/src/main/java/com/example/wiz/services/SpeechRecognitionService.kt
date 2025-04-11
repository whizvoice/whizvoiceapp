package com.example.wiz.services

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "SpeechRecognition"
    private var speechRecognizer: SpeechRecognizer? = null

    private val _transcriptionState = MutableStateFlow("")
    val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState.asStateFlow()

    private var recognitionCallback: ((String) -> Unit)? = null

    private var pauseDetectionMillis = 2500L
    private var lastPartialResult = ""
    private var lastPartialTimestamp = 0L
    private var lastResultReceivedTime = 0L

    private var manualStopInProgress = false

    fun initialize() {
        // Check if device supports speech recognition
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _errorState.value = "Speech recognition is not available on this device."
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(createRecognitionListener())
        Log.d(TAG, "Speech recognizer initialized")
    }

    fun startListening(onFinalTranscription: (String) -> Unit) {
        // Clear previous states
        _transcriptionState.value = ""
        _errorState.value = null
        recognitionCallback = onFinalTranscription

        speechRecognizer?.let {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

                // Increase silence duration for more complete sentences
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, pauseDetectionMillis)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, pauseDetectionMillis)
            }
            manualStopInProgress = false
            it.startListening(intent)
            _isListening.value = true
            Log.d(TAG, "Started listening for speech input")
        } ?: run {
            _errorState.value = "Speech recognizer not initialized."
            Log.e(TAG, "Failed to start listening - recognizer not initialized")
        }
    }

    fun stopListening() {
        Log.d(TAG, "Stopping speech recognition")
        manualStopInProgress = true // Set flag before stopping
        speechRecognizer?.stopListening()
        _isListening.value = false

        // Only finalize if there might be something to finalize
        if (_transcriptionState.value.isNotEmpty()) {
            finalizeTranscription()
        } else {
            // Ensure state is reset even if finalizing doesn't happen
            _transcriptionState.value = ""
            lastPartialResult = ""
            lastPartialTimestamp = 0L
        }
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognizer resources")
        speechRecognizer?.stopListening() // Stop listening first
        speechRecognizer?.destroy()     // Then destroy
        speechRecognizer = null         // Nullify the reference
        _isListening.value = false      // Reset state
        _transcriptionState.value = ""
        recognitionCallback = null      // Clear callback
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                _errorState.value = null
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech began")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Too verbose for normal logging
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                Log.d(TAG, "Buffer received: ${buffer?.size ?: 0} bytes")
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
                finalizeTranscription()
                // Restart listening if still in listening mode
                if (_isListening.value) {
                    startListening(recognitionCallback ?: { _ -> })
                }

                _isListening.value = false // Ensure state is updated
                manualStopInProgress = false // Reset flag
            }

            override fun onError(error: Int) {
                val isManualStopError = manualStopInProgress && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT)
                manualStopInProgress = false

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No recognition match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Unknown error"
                }

                Log.e(TAG, "Speech recognition error: $errorMessage (code: $error)")

                // Don't show timeout errors as they're expected when pausing speech
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    _errorState.value = errorMessage
                }

                // Don't show common errors after manual stop or simple timeouts/no-matches as user-facing errors
                if (error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_NO_MATCH &&
                    !isManualStopError) { // Check the flag here
                    _errorState.value = errorMessage
                }


                // If we have text and hit a timeout, it's likely because the user paused
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT && _transcriptionState.value.isNotEmpty()) {
                    Log.d(TAG, "Speech timeout with text - finalizing transcription")
                    finalizeTranscription()
                }

                // Restart listening if still in listening mode (except for permission errors)
                if (_isListening.value && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    startListening(recognitionCallback ?: { _ -> })
                } else {
                    _isListening.value = false
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    Log.d(TAG, "Final result: $text")
                    _transcriptionState.value = text

                    // Check if this is likely a complete thought
                    val seemsComplete = text.matches(".*[.!?]\\s*$".toRegex()) || text.length > 20

                    if (seemsComplete) {
                        finalizeTranscription()
                        // Restart listening if still in listening mode
                        if (_isListening.value) {
                            startListening(recognitionCallback ?: { _ -> })
                        }
                    } else {
                        // For very short phrases, wait to collect more
                        // and just update the transcription for now
                        lastPartialResult = text
                        lastPartialTimestamp = System.currentTimeMillis()
                    }
                } else {
                    Log.d(TAG, "Received empty final results")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partialText = matches[0]
                    Log.d(TAG, "Partial result: $partialText")
                    _transcriptionState.value = partialText

                    lastResultReceivedTime = System.currentTimeMillis()

                    // Check if speech has paused
                    val currentTime = System.currentTimeMillis()
                    val isRepeatedText = partialText == lastPartialResult
                    val hasNaturalPause = partialText.matches(".*[.!?]\\s*$".toRegex())

                    if (isRepeatedText) {
                        if (currentTime - lastPartialTimestamp > pauseDetectionMillis) {
                            Log.d(TAG, "Detected pause in speech (repeated text) - finalizing transcription")
                            finalizeTranscription()

                            // Restart listening if still in listening mode
                            if (_isListening.value) {
                                speechRecognizer?.stopListening()
                                startListening(recognitionCallback ?: { _ -> })
                            }
                        }
                    } else if (hasNaturalPause && partialText.length > 10) {
                        // If we detect a natural end of sentence and text is substantial
                        Log.d(TAG, "Detected natural sentence ending - finalizing transcription")
                        finalizeTranscription()

                        // Restart listening if still in listening mode
                        if (_isListening.value) {
                            speechRecognizer?.stopListening()
                            startListening(recognitionCallback ?: { _ -> })
                        }
                    } else {
                        lastPartialResult = partialText
                        lastPartialTimestamp = currentTime
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Speech recognition event: $eventType")
            }
        }
    }

    private fun finalizeTranscription() {
        val finalText = _transcriptionState.value.trim()
        manualStopInProgress = false // Reset flag here too
        if (finalText.isNotEmpty()) {
            Log.d(TAG, "Finalizing transcription: $finalText")
            recognitionCallback?.invoke(finalText)
            _transcriptionState.value = "" // Clear the state AFTER invoking callback
            lastPartialResult = "" // Reset tracking
        } else {
            Log.d(TAG, "No text to finalize")
            // Still clear state just in case
            _transcriptionState.value = ""
            lastPartialResult = ""
        }
        lastPartialTimestamp = 0L
    }
}