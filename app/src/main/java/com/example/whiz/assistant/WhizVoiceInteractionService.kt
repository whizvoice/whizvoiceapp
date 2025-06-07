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
}