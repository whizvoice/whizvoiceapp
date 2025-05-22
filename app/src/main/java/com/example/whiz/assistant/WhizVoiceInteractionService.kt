package com.example.whiz.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.example.whiz.services.SpeechRecognitionService

/**
 * The main entry point for the Assistant integration with the Android system.
 * This service is started when the user designates Whiz as the default assistant
 * and triggers it (e.g., via long-press home/power or hotword).
 */
class WhizVoiceInteractionService : VoiceInteractionService() {

    private val TAG = "WhizVoiceInteractionSvc"

    private lateinit var speechRecognitionService: SpeechRecognitionService

    override fun onCreate() {
        super.onCreate()
        speechRecognitionService = SpeechRecognitionService(applicationContext)
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "onReady - Service is ready.")
        // Initialize speech recognition service
        speechRecognitionService.initialize()
    }

    // This method is called when the system wants to start a voice interaction session.
    // It determines if your app can handle the intent. For now, we handle standard assist.
    override fun onGetSupportedVoiceActions(voiceActions: Set<String>): Set<String> {
        // Support the standard assist action
        return setOf(Intent.ACTION_ASSIST)
    }
}