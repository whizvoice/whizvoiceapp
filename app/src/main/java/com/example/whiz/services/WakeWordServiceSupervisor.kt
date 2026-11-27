package com.example.whiz.services

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whiz.BuildConfig
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.preferences.WakeWordPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the wake-word service alive across the situations where Android refuses to start it.
 *
 * A microphone foreground service can only be started while the app is visible. After a
 * reinstall or a process kill the process comes back in the background (the system rebinds the
 * accessibility service), so the start in Application.onCreate is refused. Every later moment
 * that might succeed calls [ensureRunning]; see [WakeWordStartTrigger] for the list.
 */
@Singleton
class WakeWordServiceSupervisor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordPreferences: WakeWordPreferences,
    private val apiService: ApiService,
) {
    private val guard = WakeWordStartGuard(
        isEnabled = { wakeWordPreferences.isEnabledOnce() },
        isRunning = { WakeWordService.isRunning },
        hasMicPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        requestStart = {
            context.startForegroundService(Intent(context, WakeWordService::class.java))
        },
        report = ::uploadFailure,
        log = { Log.i(TAG, it) },
    )

    /** Non-null while wake word is enabled but could not be started with the app on screen. */
    val failure: StateFlow<WakeWordStartFailure?> = guard.failure

    fun ensureRunning(trigger: WakeWordStartTrigger): WakeWordStartOutcome = guard.ensureRunning(trigger)

    fun onStartForegroundFailed(error: Throwable) = guard.onStartForegroundFailed(error)

    fun onStarted() = guard.onStarted()

    private fun uploadFailure(failure: WakeWordStartFailure) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                apiService.uploadUiDump(
                    ApiService.UiDumpCreate(
                        dumpReason = DUMP_REASON,
                        errorMessage = "${failure.trigger}: ${failure.error}".take(300),
                        packageName = context.packageName,
                        deviceModel = Build.MODEL,
                        deviceManufacturer = Build.MANUFACTURER,
                        androidVersion = Build.VERSION.RELEASE,
                        appVersion = BuildConfig.VERSION_NAME,
                        screenAgentContext = mapOf(
                            "trigger" to failure.trigger.name,
                            "error" to failure.error,
                        ),
                        // Diagnostic only: nothing for the screen-agent autofix pipeline to act on.
                        expectedFailure = true,
                    )
                )
                Log.i(TAG, "Reported $DUMP_REASON")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload $DUMP_REASON", e)
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SupervisorEntryPoint {
        fun wakeWordServiceSupervisor(): WakeWordServiceSupervisor
    }

    companion object {
        private const val TAG = "WakeWordSupervisor"
        const val DUMP_REASON = "wake_word_start_failed"

        /** For Android components that are not Hilt-injected (services, receivers). */
        fun from(context: Context): WakeWordServiceSupervisor =
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SupervisorEntryPoint::class.java,
            ).wakeWordServiceSupervisor()
    }
}
