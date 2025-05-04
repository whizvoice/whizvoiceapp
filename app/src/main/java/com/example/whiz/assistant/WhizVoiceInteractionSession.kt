package com.example.whiz.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.whiz.AssistantActivity // Your dedicated Assistant UI Activity

/**
 * Manages a single voice interaction session. This is where the core logic resides
 * for starting the UI and processing voice commands (via the launched Activity).
 * Hotword detection is NOT handled here for 3rd party apps.
 */
class WhizVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val TAG = "WhizSession"
    private val handler = Handler(Looper.getMainLooper())

    /*override fun onCreate(args: Bundle?) {
        super.onCreate()
        Log.d(TAG, "onCreate - Session created")
        // No hotword initialization needed/possible here
    }*/


    // Called when the session is shown (e.g., user long-presses home/power)
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow - flags: $showFlags")
        // Determine if this was triggered by voice (e.g., "OK Google, ask Whiz to...")
        // The system might provide this info in args or flags, needs investigation/testing
        // For now, assume it's a manual trigger unless args indicate otherwise.
        val isVoiceTrigger = args?.getString("trigger_mode") == "voice" // Example check, might vary
        startAssistantActivity(isVoiceTrigger)
    }

    // Called when the session is hidden
    override fun onHide() {
        super.onHide()
        Log.d(TAG, "onHide")
        // No hotword logic needed here
    }

    // Called when the system explicitly requests an assist action
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.d(TAG, "onHandleAssist")
        // You could potentially use the structure/content for context-aware assistance
        startAssistantActivity(isVoiceTrigger = false) // Treat explicit assist like a manual trigger
    }


    private fun startAssistantActivity(isVoiceTrigger: Boolean) {
        try {
            val intent = Intent(context, AssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                // Pass information about the trigger if available/meaningful
                // The system might handle initial voice input before launching your activity
                putExtra("isVoiceTrigger", isVoiceTrigger) // Keep flag for potential system voice actions
            }
            Log.d(TAG, "Starting AssistantActivity (Voice Trigger: $isVoiceTrigger)...")
            context.startActivity(intent)
            // We finish the session itself relatively quickly, the AssistantActivity takes over the UI
            // finish() // Consider finishing the session after starting the activity
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AssistantActivity", e)
            finish() // Finish session if activity start fails
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Session destroyed")
        handler.removeCallbacksAndMessages(null) // Clean up handler
        super.onDestroy()
    }

    /*override fun onCancel() {
        Log.w(TAG, "onCancel() called on session!") // Is the system cancelling it?
        super.onCancel()
        // You might call finish() here if cancellation means user dismissed it
    }*/

    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed() called on session.") // Is back press routed here?
        super.onBackPressed()
        // You might call finish() here too
    }
}