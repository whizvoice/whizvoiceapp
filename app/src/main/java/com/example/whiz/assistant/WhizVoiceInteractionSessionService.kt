package com.example.whiz.assistant

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * This service is responsible for creating new VoiceInteractionSession instances
 * when the system requests an interaction.
 */
class WhizVoiceInteractionSessionService : VoiceInteractionSessionService() {
    private val TAG = "WhizSessionSvc"

    override fun onNewSession(args: android.os.Bundle?): VoiceInteractionSession {
        Log.d(TAG, "onNewSession - Creating a new WhizVoiceInteractionSession")
        return WhizVoiceInteractionSession(this) // Pass context
    }
}