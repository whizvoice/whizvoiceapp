package com.example.whiz.services

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.whiz.data.preferences.VoiceSettings
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSManager @Inject constructor(
    private val context: Context
) : TextToSpeech.OnInitListener {
    
    private val TAG = "TTSManager"
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    init {
        initializeTTS()
    }
    
    private fun initializeTTS() {
        tts = TextToSpeech(context, this)
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language is not supported or missing data.")
                isInitialized = false
            } else {
                Log.d(TAG, "TTS initialized successfully.")
                isInitialized = true
            }
        } else {
            Log.e(TAG, "TTS initialization failed! Status: $status")
            isInitialized = false
        }
    }
    
    private fun resetToSystemDefaults() {
        // To properly reset to system defaults, we need to reinitialize the TTS engine
        // because there's no direct way to "unset" previously applied settings
        try {
            tts?.stop()
            tts?.shutdown()
            initializeTTS()
            Log.d(TAG, "Reset TTS engine to use system defaults")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting TTS to system defaults", e)
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
            } else {
                // Use system defaults - reset the TTS engine to clear any previous custom settings
                resetToSystemDefaults()
                // Wait a moment for the reset to complete before speaking
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isInitialized) {
                        tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "voice_test")
                        Log.d(TAG, "Playing voice test with system TTS settings")
                    }
                }, 500)
                return // Exit early since we're handling the speech in the delayed callback
            }
            
            // Speak the test text (for custom settings)
            tts?.speak(testText, TextToSpeech.QUEUE_FLUSH, null, "voice_test")
            Log.d(TAG, "Playing voice test with custom settings: $voiceSettings")
        } catch (e: Exception) {
            Log.e(TAG, "Error testing voice settings", e)
        }
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
} 