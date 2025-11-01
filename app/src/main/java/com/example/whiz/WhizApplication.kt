package com.example.whiz

import android.app.Application
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess
import dagger.hilt.android.HiltAndroidApp
import com.example.whiz.services.SpeechRecognitionService
import javax.inject.Inject

@HiltAndroidApp
class WhizApplication : Application() {

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var appLifecycleService: com.example.whiz.services.AppLifecycleService

    override fun onCreate() {
        super.onCreate()

        Log.d("WhizApplication", "Application created - AppLifecycleService will automatically track foreground/background state")

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
} 