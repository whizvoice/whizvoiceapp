package com.example.whiz.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.example.whiz.AssistantActivity

class WhizVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val TAG = "WhizSession"

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        Log.d(TAG, "onCreate: Session created, args: $args")
        val intent = Intent(context, AssistantActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (args != null) {
            intent.putExtras(args) // Pass along any args from the system
        }
        intent.putExtra("IS_ASSISTANT_LAUNCH", true)
        Log.d(TAG, "Starting AssistantActivity from session's onCreate")
        startAssistantActivity(intent)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow: Session shown, args: $args, showFlags: $showFlags")
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "onHide: Session hidden")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Session destroyed")
    }

    /**
     * Handles the actual voice interaction, typically by launching an Activity.
     * This method is called when an interaction is initiated via an assist gesture
     * (long-pressing home) or voice command.
     */
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.d(TAG, "onHandleAssist received")

        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("IS_ASSISTANT_LAUNCH", true)
            data?.let { bundle -> putExtra("assist_bundle", bundle) }
        }
        Log.d(TAG, "onHandleAssist: Starting AssistantActivity")
        startAssistantActivity(intent)
        finish() // Finish the session as the activity is now handling it.
    }
} 