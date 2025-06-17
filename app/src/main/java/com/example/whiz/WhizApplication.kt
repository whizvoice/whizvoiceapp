package com.example.whiz

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.system.exitProcess
import dagger.hilt.android.HiltAndroidApp
import com.example.whiz.services.SpeechRecognitionService
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Singleton

@HiltAndroidApp
class WhizApplication : Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    @Inject
    lateinit var appLifecycleService: com.example.whiz.services.AppLifecycleService
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d("WhizApplication", "Screen turned off - stopping continuous listening")
                    try {
                        speechRecognitionService.continuousListeningEnabled = false
                        speechRecognitionService.stopListening()
                        appLifecycleService.notifyAppBackgrounded()
                    } catch (e: Exception) {
                        Log.e("WhizApplication", "Error stopping speech recognition on screen off", e)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d("WhizApplication", "Screen turned on - notifying app foreground")
                    try {
                        appLifecycleService.notifyAppForegrounded()
                    } catch (e: Exception) {
                        Log.e("WhizApplication", "Error notifying foreground on screen on", e)
                    }
                }
            }
        }
    }
    
    override fun onCreate() {
        super<Application>.onCreate()
        
        // Register for app lifecycle events
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Register for screen on/off events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        
        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("WhizApplication", "Uncaught exception in thread ${thread.name}", throwable)
                
                // Show toast on main thread
                android.os.Handler(mainLooper).post {
                    Toast.makeText(
                        this,
                        "An error occurred. Please try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                
                // Give the toast time to show before killing the process
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.e("WhizApplication", "Error in uncaught exception handler", e)
            } finally {
                // Kill the process
                exitProcess(1)
            }
        }
    }
    
    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        Log.d("WhizApplication", "App moved to background - stopping continuous listening")
        try {
            // Stop continuous listening to prevent microphone staying active when app is in background
            speechRecognitionService.continuousListeningEnabled = false
            speechRecognitionService.stopListening()
            // Notify through the service that app went to background
            appLifecycleService.notifyAppBackgrounded()
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error stopping speech recognition on background", e)
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onDestroy(owner)
        Log.d("WhizApplication", "App destroyed - releasing speech recognition resources")
        try {
            // Release speech recognition resources to prevent memory leaks
            speechRecognitionService.release()
            // Unregister screen receiver
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error releasing resources on destroy", e)
        }
    }
    
    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        Log.d("WhizApplication", "App moved to foreground - notifying ChatViewModels")
        try {
            // Notify through the injectable service
            appLifecycleService.notifyAppForegrounded()
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error notifying ChatViewModels of app foreground", e)
        }
    }
} 