package com.example.whiz.test

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager

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

        // Store reference to active ChatViewModel for testing
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

        // Get the active ChatViewModel
        val viewModel = activeChatViewModel

        if (viewModel == null) {
            Log.e(TAG, "No active ChatViewModel available for test transcription")
            return
        }

        // Simulate voice transcription
        try {
            // Update the input text with the transcription
            viewModel.updateInputText(text, fromVoice = fromVoice)

            // If autoSend is true, automatically send the message
            if (autoSend && text.isNotBlank()) {
                viewModel.sendUserInput(text)
                Log.d(TAG, "Test transcription sent: '$text'")
            } else {
                Log.d(TAG, "Test transcription set to input field: '$text'")
            }

            // If this is a voice transcription, update VoiceManager state
            if (fromVoice) {
                // This simulates what would happen with real voice input
                VoiceManager.instance?.let { voiceManager ->
                    // The VoiceManager would normally update its transcription state
                    // but we're bypassing that and going directly to ChatViewModel
                    Log.d(TAG, "Simulated voice transcription complete")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing test transcription", e)
        }
    }
}