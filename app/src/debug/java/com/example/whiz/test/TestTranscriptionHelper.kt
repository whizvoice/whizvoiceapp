package com.example.whiz.test

import android.util.Log
import com.example.whiz.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only helper to process test transcription through ChatViewModel.
 * This is a fallback when VoiceManager callback doesn't work.
 */
fun processTestTranscriptionFallback(
    text: String,
    fromVoice: Boolean,
    autoSend: Boolean,
    processTestTranscription: (ChatViewModel, String, Boolean, Boolean) -> Unit
) {
    val chatViewModel = TestTranscriptionReceiver.activeChatViewModel
    if (chatViewModel != null) {
        Log.d("MainActivity", "Using ChatViewModel from TestTranscriptionReceiver (fallback)")
        processTestTranscription(chatViewModel, text, fromVoice, autoSend)
    } else {
        Log.e("MainActivity", "No ChatViewModel and VoiceManager fallback failed")
    }
}
