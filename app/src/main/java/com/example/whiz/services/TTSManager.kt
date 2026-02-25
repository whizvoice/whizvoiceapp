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
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    @Volatile private var isInitializing = false

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

    // Queue of callbacks waiting for initialization
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    fun initialize(onInitialized: (Boolean) -> Unit) {
        // If already initialized, immediately call back with success
        if (isInitialized && tts != null) {
            Log.d(TAG, "TTS already initialized, returning cached state")
            onInitialized(true)
            return
        }

        // If initialization is in progress, queue this callback
        if (isInitializing) {
            Log.d(TAG, "TTS initialization in progress, queuing callback")
            synchronized(pendingCallbacks) {
                pendingCallbacks.add(onInitialized)
            }
            return
        }

        // Start new initialization
        isInitializing = true
        Log.d(TAG, "Starting TTS initialization")

        tts = TextToSpeech(context) { status ->
            isInitializing = false
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                _initializationError.value = false
                setupUtteranceListener()
                Log.d(TAG, "TTS initialized successfully")
                onInitialized(true)
                // Notify all pending callbacks
                synchronized(pendingCallbacks) {
                    pendingCallbacks.forEach { it(true) }
                    pendingCallbacks.clear()
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
                _initializationError.value = true
                onInitialized(false)
                // Notify all pending callbacks of failure
                synchronized(pendingCallbacks) {
                    pendingCallbacks.forEach { it(false) }
                    pendingCallbacks.clear()
                }
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
    
    private fun stripMarkdown(text: String): String {
        var result = text
        // Code fences: ```...``` → content only
        result = result.replace(Regex("```[a-zA-Z]*\\n?([\\s\\S]*?)```"), "$1")
        // Inline code: `code` → code
        result = result.replace(Regex("`([^`]+)`"), "$1")
        // Images: ![alt](url) → alt
        result = result.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // Links: [text](url) → text
        result = result.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        // Bold/italic: **text**, *text*, __text__, _text_ → text
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        result = result.replace(Regex("__(.+?)__"), "$1")
        result = result.replace(Regex("\\*(.+?)\\*"), "$1")
        result = result.replace(Regex("(?<=\\s|^)_(.+?)_(?=\\s|$)", RegexOption.MULTILINE), "$1")
        // Strikethrough: ~~text~~ → text
        result = result.replace(Regex("~~(.+?)~~"), "$1")
        // Headers: # , ## , etc. → removed
        result = result.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        // Horizontal rules: lines that are just ---, ***, ___ → removed
        result = result.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
        // Blockquotes: > at line start → removed
        result = result.replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        // Unordered list markers: - , * , + at line start → removed
        result = result.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
        // Ordered list markers: 1. , 2. at line start → removed
        result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        // Clean up extra blank lines
        result = result.replace(Regex("\n{3,}"), "\n\n")
        return result.trim()
    }

    fun speak(text: String, utteranceId: String = "default") {
        if (!isInitialized || tts == null) {
            Log.w(TAG, "TTS not initialized or engine null (isInitialized=$isInitialized, tts=${tts != null}), cannot speak")
            onSpeechError?.invoke()
            return
        }

        val strippedText = stripMarkdown(text)
        Log.d(TAG, "Speaking text (${strippedText.take(50)}...) with utteranceId=$utteranceId")

        // Send text to bubble overlay if it's running
        try {
            BubbleOverlayService.updateBotResponse(text)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update bubble overlay: ${e.message}")
        }

        try {
            val result = tts?.speak(strippedText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            Log.d(TAG, "TTS speak() returned: $result (SUCCESS=0, ERROR=-1)")
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS speak() failed with result: $result")
                onSpeechError?.invoke()
            }
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
        // Manually set speaking state to false to ensure sync
        // This is needed because onStop callback isn't guaranteed to fire
        // when stop() is called directly (e.g., from MainActivity.onPause)
        _isSpeaking.value = false
        Log.d(TAG, "TTS stopped manually, isSpeaking set to false")
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        isInitializing = false
        synchronized(pendingCallbacks) {
            pendingCallbacks.clear()
        }
        onSpeechStarted = null
        onSpeechCompleted = null
        onSpeechError = null
    }
} 