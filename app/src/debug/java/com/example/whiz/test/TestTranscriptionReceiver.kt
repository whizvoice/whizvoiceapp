package com.example.whiz.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.reflect.Field

/**
 * Debug-only broadcast receiver for simulating voice transcriptions via ADB.
 *
 * Usage:
 * adb shell am broadcast -a com.example.whiz.TEST_TRANSCRIPTION \
 *   --es text "Your transcribed text" \
 *   --ez fromVoice true \
 *   -n com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver
 */
class TestTranscriptionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TestTranscription"
        const val ACTION_TEST_TRANSCRIPTION = "com.example.whiz.TEST_TRANSCRIPTION"

        // Store reference to active ChatViewModel for testing (kept for backward compatibility)
        @Volatile
        var activeChatViewModel: ChatViewModel? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION_TEST_TRANSCRIPTION) {
            return
        }

        val text = intent.getStringExtra("text") ?: ""
        val fromVoice = intent.getBooleanExtra("fromVoice", true)
        val autoSend = intent.getBooleanExtra("autoSend", true)

        Log.d(TAG, "Received test transcription: text='$text', fromVoice=$fromVoice, autoSend=$autoSend")

        // First try to use VoiceManager (works in bubble mode)
        val voiceManager = VoiceManager.instance

        if (voiceManager != null && fromVoice) {
            Log.d(TAG, "Using VoiceManager to simulate voice transcription")

            try {
                // Use reflection to get the transcriptionCallback field
                val transcriptionCallbackField: Field = voiceManager.javaClass.getDeclaredField("transcriptionCallback")
                transcriptionCallbackField.isAccessible = true
                val callback = transcriptionCallbackField.get(voiceManager) as? ((String) -> Unit)

                if (callback != null) {
                    // Simulate transcription through VoiceManager's callback
                    // This will trigger the same flow as real voice input
                    CoroutineScope(Dispatchers.Main).launch {
                        Log.d(TAG, "Invoking transcription callback with: '$text'")
                        callback.invoke(text)
                        Log.d(TAG, "Test transcription processed through VoiceManager")
                    }
                    return
                } else {
                    Log.w(TAG, "VoiceManager transcription callback is not set")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing VoiceManager transcription callback", e)
            }
        }

        // Fallback to direct ChatViewModel approach (for non-voice or if VoiceManager fails)
        val viewModel = activeChatViewModel

        if (viewModel == null) {
            Log.e(TAG, "No active ChatViewModel and VoiceManager fallback failed")
            return
        }

        // Simulate transcription directly through ChatViewModel
        try {
            CoroutineScope(Dispatchers.Main).launch {
                // Update the input text with the transcription
                viewModel.updateInputText(text, fromVoice = fromVoice)

                // If autoSend is true, automatically send the message
                if (autoSend && text.isNotBlank()) {
                    viewModel.sendUserInput(text)
                    Log.d(TAG, "Test transcription sent via ChatViewModel: '$text'")
                } else {
                    Log.d(TAG, "Test transcription set to input field via ChatViewModel: '$text'")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing test transcription through ChatViewModel", e)
        }
    }
}