package com.example.whiz.test

import android.util.Log
import com.example.whiz.ui.viewmodels.ChatViewModel

/**
 * Release build no-op version of test transcription fallback.
 */
fun processTestTranscriptionFallback(
    text: String,
    fromVoice: Boolean,
    autoSend: Boolean,
    processTestTranscription: (ChatViewModel, String, Boolean, Boolean) -> Unit
) {
    Log.e("MainActivity", "VoiceManager fallback failed (release build)")
}
