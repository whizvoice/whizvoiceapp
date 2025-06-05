package com.example.whiz

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.system.exitProcess
import dagger.hilt.android.HiltAndroidApp
import com.example.whiz.services.SpeechRecognitionService
import javax.inject.Inject

@HiltAndroidApp
class WhizApplication : Application(), DefaultLifecycleObserver {

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    override fun onCreate() {
        super<Application>.onCreate()
        
        // Register for app lifecycle events
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
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
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error releasing speech recognition on destroy", e)
        }
    }
    
    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        Log.d("WhizApplication", "App moved to foreground")
        // Don't automatically restart continuous listening here - let the ChatViewModel handle it
        // based on the current state and user preferences
        
        // Set a flag that ChatViewModels can observe to know the app came to foreground
        try {
            // We could use a shared preference or other mechanism, but for now just log
            // The ChatViewModel will handle restarting continuous listening in its own lifecycle
            Log.d("WhizApplication", "App foregrounded - ChatViewModels should handle continuous listening restart")
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error handling app foreground", e)
        }
    }
} 