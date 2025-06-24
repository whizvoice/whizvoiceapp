package com.example.whiz.assistant

import android.content.Intent
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * The main entry point for the Assistant integration with the Android system.
 * This service is started when the user designates Whiz as the default assistant
 * and triggers it (e.g., via long-press home/power or hotword).
 * 
 * This service delegates all actual work to activities that can use Hilt for DI.
 */
class WhizVoiceInteractionService : VoiceInteractionService() {

    private val TAG = "WhizVoiceInteractionSvc"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Voice Interaction Service created")
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "onReady - Service is ready")
        // No direct service initialization here - delegate to activities with proper DI
    }

    // This method is called when the system wants to start a voice interaction session.
    // It determines if your app can handle the intent. For now, we handle standard assist.
    override fun onGetSupportedVoiceActions(voiceActions: Set<String>): Set<String> {
        // Support the standard assist action
        return setOf(Intent.ACTION_ASSIST)
    }
    
    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.d(TAG, "onLaunchVoiceAssistFromKeyguard - launching AssistantActivity")
        super.onLaunchVoiceAssistFromKeyguard()
        launchAssistantActivity()
    }
    

    
    private fun launchAssistantActivity() {
        try {
            Log.d(TAG, "Launching AssistantActivity for voice interaction")
            val assistantIntent = Intent(this, com.example.whiz.AssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("IS_ASSISTANT_LAUNCH", true)
                putExtra("FROM_VOICE_SERVICE", true)
            }
            startActivity(assistantIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AssistantActivity", e)
        }
    }
}