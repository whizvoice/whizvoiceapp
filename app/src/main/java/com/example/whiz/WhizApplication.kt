package com.example.whiz

import android.app.Application
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess
import dagger.hilt.android.HiltAndroidApp
import com.example.whiz.data.preferences.WakeWordPreferences
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.WakeWordService
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class WhizApplication : Application() {

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var appLifecycleService: com.example.whiz.services.AppLifecycleService

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject
    lateinit var audioFocusManager: com.example.whiz.services.AudioFocusManager

    override fun onCreate() {
        super.onCreate()

        Log.d("WhizApplication", "Application created - AppLifecycleService will automatically track foreground/background state")

        // Auto-start wake word service if enabled (recovers from process death)
        try {
            if (wakeWordPreferences.isEnabledOnce() && !WakeWordService.isRunning) {
                Log.d("WhizApplication", "Wake word enabled, starting WakeWordService")
                WakeWordService.start(this)
            }
        } catch (e: Exception) {
            Log.e("WhizApplication", "Error checking wake word preference", e)
        }

        // Set up global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("WhizApplication", "Uncaught exception in thread ${thread.name}", throwable)

                // Save crash data to file for upload on next launch
                try {
                    val crashData = JSONObject().apply {
                        put("thread_name", thread.name)
                        put("stack_trace", Log.getStackTraceString(throwable))
                        put("timestamp", System.currentTimeMillis())
                        put("device_model", Build.MODEL)
                        put("device_manufacturer", Build.MANUFACTURER)
                        put("android_version", Build.VERSION.RELEASE)
                        put("app_version", BuildConfig.VERSION_NAME)
                    }

                    // Write to internal storage (survives crash, doesn't need permissions)
                    val crashFile = File(filesDir, "pending_crash.json")
                    crashFile.writeText(crashData.toString())
                    Log.e("WhizApplication", "Crash saved to file: ${crashFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e("WhizApplication", "Failed to save crash to file", e)
                }

                // Release audio ducking so other apps' volume returns to normal
                try {
                    audioFocusManager.abandonDuckingFocus()
                } catch (e: Exception) {
                    Log.e("WhizApplication", "Failed to release audio ducking on crash", e)
                }

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
} 