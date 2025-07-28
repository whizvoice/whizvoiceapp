package com.example.whiz.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.whiz.data.preferences.VoiceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import android.media.AudioManager

@Singleton
class TTSManager @Inject constructor(
    private val context: Context
) {
    private val TAG = "TTSManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    // Expose isSpeaking state for testing and UI
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    // Expose initialization error state for testing (especially on emulators)
    private val _initializationError = MutableStateFlow(false)
    val initializationError: StateFlow<Boolean> = _initializationError.asStateFlow()
    
    // Event callbacks for audio coordination
    private var onSpeechStarted: (() -> Unit)? = null
    private var onSpeechCompleted: (() -> Unit)? = null
    private var onSpeechError: (() -> Unit)? = null
    
    fun initialize(onInitialized: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                _initializationError.value = false
                setupUtteranceListener()
                Log.d(TAG, "TTS initialized successfully")
                onInitialized(true)
            } else {
                Log.e(TAG, "TTS initialization failed")
                _initializationError.value = true
                onInitialized(false)
            }
        }
    }
    
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started for utterance: $utteranceId")
                _isSpeaking.value = true
                onSpeechStarted?.invoke()
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed for utterance: $utteranceId")
                _isSpeaking.value = false
                onSpeechCompleted?.invoke()
            }
            
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
                onSpeechError?.invoke()
            }
            
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d(TAG, "TTS stopped for utterance: $utteranceId, interrupted: $interrupted")
                _isSpeaking.value = false
                onSpeechCompleted?.invoke()
            }
        })
    }
    
    fun setAudioEventCallbacks(
        onStarted: (() -> Unit)? = null,
        onCompleted: (() -> Unit)? = null,
        onError: (() -> Unit)? = null
    ) {
        this.onSpeechStarted = onStarted
        this.onSpeechCompleted = onCompleted
        this.onSpeechError = onError
    }
    
    fun speak(text: String, utteranceId: String = "default") {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            onSpeechError?.invoke()
            return
        }
        
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking text", e)
            onSpeechError?.invoke()
        }
    }
    
    fun setSpeechRate(speechRate: Float) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot set speech rate")
            return
        }
        
        try {
            tts?.setSpeechRate(speechRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speech rate", e)
        }
    }
    
    fun setPitch(pitch: Float) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot set pitch")
            return
        }
        
        try {
            tts?.setPitch(pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pitch", e)
        }
    }
    
    fun testVoiceSettings(settings: VoiceSettings) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot test voice settings")
            return
        }
        
        // Apply the settings temporarily
        if (!settings.useSystemDefaults) {
            tts?.setSpeechRate(settings.speechRate)
            tts?.setPitch(settings.pitch)
        }
        
        // Test with a sample phrase
        val testText = "This is how your voice settings will sound."
        speak(testText, "voice_test")
    }
    
    // Helper method to detect if headphones are connected (for TTS audio routing decisions)
    fun areHeadphonesConnected(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Check for wired headphones or Bluetooth audio
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        } catch (e: Exception) {
            Log.e(TAG, "Error checking headphone status", e)
            false // Default to false (safer - assume speakers)
        }
    }
    
    // Helper method to determine if mic button should be shown during TTS
    fun shouldShowMicButtonDuringTTS(): Boolean {
        return _isSpeaking.value && !areHeadphonesConnected()
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        onSpeechStarted = null
        onSpeechCompleted = null
        onSpeechError = null
    }
} 