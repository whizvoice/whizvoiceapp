package com.example.whiz.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * The main entry point for the Assistant integration with the Android system.
 * This service is started when the user designates Whiz as the default assistant
 * and triggers it (e.g., via long-press home/power or hotword).
 */
class WhizVoiceInteractionService : VoiceInteractionService() {

    private val TAG = "WhizVoiceInteractionSvc"

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "onReady - Service is ready.")
        // You can perform any one-time setup here if needed.
        // Hotword detection might be initialized here or in the session.
    }

    // This method is called when the system wants to start a voice interaction session.
    // It determines if your app can handle the intent. For now, we handle standard assist.
    override fun onGetSupportedVoiceActions(voiceActions: Set<String>): Set<String> {
        // For now, let's say we support the basic assist action
        // You might add custom voice actions later.
        Log.d(TAG, "onGetSupportedVoiceActions - Checking supported actions.")
        // Return an empty set for now, or declare specific actions if you implement them.
        return HashSet() // Or return setOf("your.custom.ACTION") if defined
    }
}