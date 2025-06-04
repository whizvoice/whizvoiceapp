package com.example.whiz.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.whiz.data.preferences.VoiceSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSManager @Inject constructor() {
    private val TAG = "TTSManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    // Event callbacks for audio coordination
    private var onSpeechStarted: (() -> Unit)? = null
    private var onSpeechCompleted: (() -> Unit)? = null
    private var onSpeechError: (() -> Unit)? = null
    
    fun initialize(context: Context, onInitialized: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                setupUtteranceListener()
                Log.d(TAG, "TTS initialized successfully")
                onInitialized(true)
            } else {
                Log.e(TAG, "TTS initialization failed")
                onInitialized(false)
            }
        }
    }
    
    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "TTS started for utterance: $utteranceId")
                onSpeechStarted?.invoke()
            }
            
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS completed for utterance: $utteranceId")
                onSpeechCompleted?.invoke()
            }
            
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                onSpeechError?.invoke()
            }
            
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                Log.d(TAG, "TTS stopped for utterance: $utteranceId, interrupted: $interrupted")
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
            Log.d(TAG, "Speaking: $text")
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
            Log.d(TAG, "Set speech rate to: $speechRate")
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
            Log.d(TAG, "Set pitch to: $pitch")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting pitch", e)
        }
    }
    
    fun testVoiceSettings(voiceSettings: VoiceSettings, testText: String = "This is an example of how text-to-speech sounds with your current settings.") {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot test voice settings")
            return
        }
        
        try {
            // Apply the voice settings temporarily
            if (!voiceSettings.useSystemDefaults) {
                // Use custom app settings
                tts?.setSpeechRate(voiceSettings.speechRate)
                tts?.setPitch(voiceSettings.pitch)
                Log.d(TAG, "Applied custom TTS settings: speechRate=${voiceSettings.speechRate}, pitch=${voiceSettings.pitch}")
                
                // Speak the test text immediately (for custom settings)
                tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "voice_test")
                Log.d(TAG, "Playing voice test with custom settings: $voiceSettings")
            } else {
                // Use system defaults - reset the TTS engine to clear any previous custom settings
                resetToSystemDefaults()
                // Use event-driven approach instead of arbitrary delay
                setAudioEventCallbacks(onCompleted = {
                    if (isInitialized) {
                        tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "voice_test")
                        Log.d(TAG, "Playing voice test with system TTS settings")
                    }
                    // Reset callbacks after use
                    setAudioEventCallbacks()
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error testing voice settings", e)
        }
    }
    
    private fun resetToSystemDefaults() {
        tts?.setSpeechRate(1.0f) // Default rate
        tts?.setPitch(1.0f) // Default pitch
        Log.d(TAG, "Reset TTS to system defaults")
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

data class VoiceSettings(
    val useSystemDefaults: Boolean = true,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
) 