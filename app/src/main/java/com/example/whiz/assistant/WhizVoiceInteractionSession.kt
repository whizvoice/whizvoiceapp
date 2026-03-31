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
    private val TAG = "WhizVoiceInteractionSession"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Session created")
        
        // Launch AssistantActivity which handles all the business logic
        // AssistantActivity uses Hilt for dependency injection
        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("IS_ASSISTANT_LAUNCH", true)
            putExtra("FROM_VOICE_SERVICE", true)
        }
        Log.d(TAG, "Starting AssistantActivity from session's onCreate")
        startAssistantActivity(intent)
        // Finish session immediately so its window doesn't steal touch input
        // from the activity (especially critical on the lock screen)
        finish()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.d(TAG, "onShow - Assistant UI shown")
    }

    override fun onHide() {
        super.onHide()
        Log.d(TAG, "onHide - Assistant UI hidden")
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
    @Deprecated("This method overrides a deprecated method in VoiceInteractionSession. " +
            "Consider using the new voice interaction APIs when available.")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.d(TAG, "onHandleAssist received")

        val intent = Intent(context, AssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("IS_ASSISTANT_LAUNCH", true)
            putExtra("FROM_VOICE_SERVICE", true)
            data?.let { bundle -> putExtra("assist_bundle", bundle) }
        }
        Log.d(TAG, "onHandleAssist: Starting AssistantActivity")
        startAssistantActivity(intent)
        finish() // Finish the session as the activity is now handling it.
    }

//    override fun onCreateContentView(): View {
//        Log.d(TAG, "onCreateContentView - Creating assistant UI")
//        return ComposeView(context).apply {
//            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
//            setContent {
//                AssistantOverlayUi(chatViewModel, onDismiss = { /* No-op for now */ })
//            }
//        }
//    }
} 