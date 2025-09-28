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
        if (intent?.action != ACTION_TEST_TRANSCRIPTION || context == null) {
            return
        }

        val text = intent.getStringExtra("text") ?: ""
        val fromVoice = intent.getBooleanExtra("fromVoice", true)
        val autoSend = intent.getBooleanExtra("autoSend", true)

        Log.d(TAG, "Received test transcription: text='$text', fromVoice=$fromVoice, autoSend=$autoSend")

        // Send a local broadcast that BubbleOverlayService can listen to
        // This works across process boundaries
        val localIntent = Intent("com.example.whiz.TEST_TRANSCRIPTION_LOCAL")
        localIntent.putExtra("text", text)
        localIntent.putExtra("fromVoice", fromVoice)
        localIntent.putExtra("autoSend", autoSend)
        localIntent.setPackage(context.packageName) // Keep it local to our app
        context.sendBroadcast(localIntent)
        Log.d(TAG, "Sent local broadcast for transcription: '$text'")

        // Also try to use VoiceManager if available (for when app is in foreground)
        val voiceManager = VoiceManager.instance

        if (voiceManager != null && fromVoice) {
            Log.d(TAG, "VoiceManager instance found, trying to use it")

            // Try multiple approaches to trigger voice input
            try {
                // Approach 1: Try to use the transcriptionCallback if it exists
                val transcriptionCallbackField: Field = voiceManager.javaClass.getDeclaredField("transcriptionCallback")
                transcriptionCallbackField.isAccessible = true
                val callback = transcriptionCallbackField.get(voiceManager) as? ((String) -> Unit)

                if (callback != null) {
                    // Simulate transcription through VoiceManager's callback
                    CoroutineScope(Dispatchers.Main).launch {
                        Log.d(TAG, "Invoking transcription callback with: '$text'")
                        callback.invoke(text)
                        Log.d(TAG, "Test transcription processed through VoiceManager callback")
                    }
                    return
                } else {
                    Log.w(TAG, "VoiceManager transcription callback is not set")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing VoiceManager transcription callback", e)
            }

            // Approach 2: If callback doesn't work, try to find and call ChatViewModel directly from any available source
            try {
                // Try to get ChatViewModel from MessageDraftOverlayService if it exists
                val overlayServiceClass = Class.forName("com.example.whiz.services.MessageDraftOverlayService")
                val companionField = overlayServiceClass.getDeclaredField("Companion")
                companionField.isAccessible = true
                val companion = companionField.get(null)

                val getInstanceMethod = companion.javaClass.getDeclaredMethod("getInstance")
                val overlayService = getInstanceMethod.invoke(companion)

                if (overlayService != null) {
                    Log.d(TAG, "Found MessageDraftOverlayService instance, looking for ChatViewModel")

                    // Try to find viewModel field
                    val viewModelField = overlayService.javaClass.getDeclaredField("viewModel")
                    viewModelField.isAccessible = true
                    val overlayViewModel = viewModelField.get(overlayService)

                    if (overlayViewModel != null) {
                        Log.d(TAG, "Found ChatViewModel in overlay service, processing transcription")
                        CoroutineScope(Dispatchers.Main).launch {
                            val updateMethod = overlayViewModel.javaClass.getDeclaredMethod("updateInputText", String::class.java, Boolean::class.java)
                            updateMethod.invoke(overlayViewModel, text, fromVoice)

                            if (autoSend && text.isNotBlank()) {
                                val sendMethod = overlayViewModel.javaClass.getDeclaredMethod("sendUserInput", String::class.java)
                                sendMethod.invoke(overlayViewModel, text)
                                Log.d(TAG, "Test transcription sent via overlay ChatViewModel: '$text'")
                            }
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not access MessageDraftOverlayService: ${e.message}")
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