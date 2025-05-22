package com.example.whiz.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import com.example.whiz.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class PowerButtonService : Service() {
    private val TAG = "PowerButtonService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isProcessingPowerButton = false
    
    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    private val powerButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen turned off")
                    if (isProcessingPowerButton) {
                        Log.d(TAG, "Already processing power button press, ignoring")
                        return
                    }
                    isProcessingPowerButton = true
                    serviceScope.launch {
                        try {
                            withContext(Dispatchers.Main) {
                                Log.d(TAG, "Cleaning up previous speech recognition session (if any)")
                                try {
                                    if (speechRecognitionService.isListening.value) {
                                        Log.d(TAG, "stopListening before new session")
                                        speechRecognitionService.stopListening()
                                    }
                                    speechRecognitionService.release()
                                    delay(100) // Small delay after cleanup
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error during initial cleanup", e)
                                }
                                // Instead of starting speech recognition here, just launch MainActivity with the right flags
                                Log.d(TAG, "Launching MainActivity with ENABLE_VOICE_MODE")
                                if (isAppInForeground(context)) {
                                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                        putExtra("FROM_POWER_BUTTON", true)
                                        putExtra("ENABLE_VOICE_MODE", true)
                                    }
                                    context?.startActivity(mainIntent)
                                } else {
                                    Log.w(TAG, "App not in foreground, not launching MainActivity.")
                                }
                                isProcessingPowerButton = false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling power button press", e)
                            try {
                                withContext(Dispatchers.Main) {
                                    speechRecognitionService.stopListening()
                                    speechRecognitionService.release()
                                }
                            } catch (cleanupError: Exception) {
                                Log.e(TAG, "Error during cleanup", cleanupError)
                            }
                            isProcessingPowerButton = false
                        }
                    }
                }
            }
        }
    }

    // Helper to check if app is in foreground
    private fun isAppInForeground(context: Context?): Boolean {
        try {
            val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val appProcesses = activityManager?.runningAppProcesses ?: return false
            val packageName = context.packageName
            for (appProcess in appProcesses) {
                if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if app is in foreground", e)
        }
        return false
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PowerButtonService created")
        registerReceiver(powerButtonReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "PowerButtonService started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PowerButtonService destroyed")
        try {
            unregisterReceiver(powerButtonReceiver)
            // Clean up speech recognition
            speechRecognitionService.stopListening()
            speechRecognitionService.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up PowerButtonService", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 