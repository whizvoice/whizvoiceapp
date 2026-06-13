package com.example.whiz

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlin.system.exitProcess
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.preferences.WakeWordPreferences
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.WakeWordService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Inject
    lateinit var apiService: ApiService

    override fun onCreate() {
        super.onCreate()

        // Install the global exception handler FIRST, before any other startup work, to
        // minimize the window where an early crash would hit the system default handler
        // instead of ours. (Hilt graph construction happens in super.onCreate() above, so
        // it can't be covered here — that gap is instead caught by the ApplicationExitInfo
        // pass in uploadPendingExitReasons().)
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Write the crash file FIRST, with the minimum allocation, so even an
                // OutOfMemoryError crash is likely to still leave a record. Catch Throwable
                // (not just Exception) because OOM is an Error, not an Exception.
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
                    File(filesDir, "pending_crash.json").writeText(crashData.toString())
                } catch (t: Throwable) {
                    // Last-ditch: write just the throwable class so we still get a row
                    try {
                        File(filesDir, "pending_crash.json").writeText(
                            "{\"stack_trace\":\"${throwable.javaClass.name}\"," +
                                "\"timestamp\":${System.currentTimeMillis()}}"
                        )
                    } catch (_: Throwable) {
                    }
                }

                Log.e("WhizApplication", "Uncaught exception in thread ${thread.name}", throwable)

                // Best-effort cleanup, AFTER the crash file is safely written.
                // Release audio ducking so other apps' volume returns to normal.
                try {
                    audioFocusManager.abandonDuckingFocus()
                } catch (e: Throwable) {
                    Log.e("WhizApplication", "Failed to release audio ducking on crash", e)
                }

                // Show toast on main thread
                try {
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(
                            this,
                            "An error occurred. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (_: Throwable) {
                }

                // Give the toast time to show before killing the process
                Thread.sleep(1000)
            } catch (e: Throwable) {
                Log.e("WhizApplication", "Error in uncaught exception handler", e)
            } finally {
                // Kill the process
                exitProcess(1)
            }
        }

        // Apply Material You dynamic colors to all activities
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Upload any pending crash report (JVM uncaught exception) from previous session
        uploadPendingCrashReport()

        // Upload any abnormal process deaths the JVM handler can't see (native crash, ANR,
        // low-memory kill, etc.) recorded by the OS for the previous process.
        uploadPendingExitReasons()

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
    }

    private fun uploadPendingCrashReport() {
        val crashFile = File(filesDir, "pending_crash.json")
        if (!crashFile.exists()) return

        Log.i("WhizApplication", "Found pending crash report, uploading...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val crashData = JSONObject(crashFile.readText())
                val stackTrace = crashData.optString("stack_trace", "Unknown")
                val firstLine = stackTrace.lineSequence().firstOrNull() ?: "Unknown crash"

                val request = ApiService.UiDumpCreate(
                    dumpReason = "app_crash",
                    errorMessage = firstLine,
                    uiHierarchy = null,
                    packageName = packageName,
                    deviceModel = crashData.optString("device_model"),
                    deviceManufacturer = crashData.optString("device_manufacturer"),
                    androidVersion = crashData.optString("android_version"),
                    screenWidth = null,
                    screenHeight = null,
                    appVersion = crashData.optString("app_version"),
                    conversationId = null,
                    recentActions = null,
                    screenAgentContext = mapOf(
                        "thread_name" to crashData.optString("thread_name"),
                        "stack_trace" to stackTrace,
                        "crash_timestamp" to crashData.optLong("timestamp")
                    )
                )

                apiService.uploadUiDump(request)
                crashFile.delete()
                Log.i("WhizApplication", "Crash report uploaded successfully")
            } catch (e: Exception) {
                Log.w("WhizApplication", "Failed to upload crash report (will retry on next launch)", e)
            }
        }
    }

    /**
     * Report abnormal process deaths that the JVM uncaught-exception handler structurally
     * cannot see — native crashes (SIGSEGV/SIGABRT), ANRs, low-memory kills, etc. — using the
     * OS-recorded [ApplicationExitInfo] for the previous process. Uploaded as `process_death`
     * rows through the same pipeline as `app_crash`, so they land in `screen_agent_ui_dumps`.
     */
    private fun uploadPendingExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return  // getHistoricalProcessExitReasons is API 30+

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val exitInfos = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                if (exitInfos.isEmpty()) return@launch

                val prefs = getSharedPreferences(CRASH_DIAG_PREFS, Context.MODE_PRIVATE)
                val lastReportedTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
                val intentionalExitTs = prefs.getLong(KEY_INTENTIONAL_EXIT_TS, 0L)

                // Oldest-first so the dedupe marker advances monotonically.
                val newInfos = exitInfos
                    .filter { it.timestamp > lastReportedTs }
                    .sortedBy { it.timestamp }

                var marker = lastReportedTs
                for (info in newInfos) {
                    if (shouldReportExit(info, intentionalExitTs)) {
                        // Stop and leave the marker if upload fails, so we retry next launch
                        // without re-uploading earlier successes.
                        if (!uploadExitInfo(info)) break
                    }
                    marker = info.timestamp
                }
                if (marker > lastReportedTs) {
                    prefs.edit().putLong(KEY_LAST_EXIT_TS, marker).apply()
                }
            } catch (e: Exception) {
                Log.w("WhizApplication", "Failed to process historical exit reasons", e)
            }
        }
    }

    private fun shouldReportExit(info: ApplicationExitInfo, intentionalExitTs: Long): Boolean {
        return when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            // REASON_CRASH is a JVM crash our uncaught-exception handler did NOT catch (e.g. one
            // that fired before the handler was installed, during Hilt init). Handled crashes
            // exit via exitProcess() and register as EXIT_SELF instead, so this does not
            // double-report with the app_crash path.
            ApplicationExitInfo.REASON_CRASH -> true
            // killProcess(myPid) (e.g. agent_close_app) surfaces as SIGNALED. Only report it
            // when it does NOT match an intentional self-exit we flagged just before killing.
            ApplicationExitInfo.REASON_SIGNALED ->
                kotlin.math.abs(info.timestamp - intentionalExitTs) > INTENTIONAL_EXIT_WINDOW_MS
            // EXIT_SELF, USER_REQUESTED, USER_STOPPED, PERMISSION_CHANGE, UNKNOWN are
            // normal/uninteresting.
            else -> false
        }
    }

    private suspend fun uploadExitInfo(info: ApplicationExitInfo): Boolean {
        return try {
            val reasonName = exitReasonName(info.reason)

            // ANR and native-crash records carry a trace; bound its size (ANR traces dump
            // every thread and can be hundreds of KB).
            var trace: String? = null
            if (info.reason == ApplicationExitInfo.REASON_ANR ||
                info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
            ) {
                trace = try {
                    info.traceInputStream?.bufferedReader()?.use { it.readText() }?.let {
                        if (it.length > MAX_TRACE_BYTES) it.takeLast(MAX_TRACE_BYTES) else it
                    }
                } catch (e: Exception) {
                    null
                }
            }

            val context = mutableMapOf<String, Any>(
                "reason" to info.reason,
                "reason_name" to reasonName,
                "description" to (info.description ?: ""),
                "importance" to info.importance,
                "pss_kb" to info.pss,
                "rss_kb" to info.rss,
                "status" to info.status,
                "exit_timestamp" to info.timestamp
            )
            trace?.let { context["trace"] = it }

            val request = ApiService.UiDumpCreate(
                dumpReason = "process_death",
                errorMessage = "$reasonName: ${info.description ?: ""}".take(300),
                uiHierarchy = null,
                packageName = packageName,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                // Note: this is the CURRENT app version, not necessarily the prior process's
                // version (ApplicationExitInfo doesn't record it) — usually the same.
                appVersion = BuildConfig.VERSION_NAME,
                conversationId = null,
                recentActions = null,
                screenAgentContext = context
            )
            apiService.uploadUiDump(request)
            Log.i("WhizApplication", "Reported process_death: $reasonName (${info.description})")
            true
        } catch (e: Exception) {
            Log.w("WhizApplication", "Failed to upload process_death (will retry next launch)", e)
            false
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        else -> "REASON_$reason"
    }

    companion object {
        private const val CRASH_DIAG_PREFS = "crash_diagnostics"
        private const val KEY_LAST_EXIT_TS = "last_reported_exit_ts"
        private const val KEY_INTENTIONAL_EXIT_TS = "intentional_exit_ts"
        private const val INTENTIONAL_EXIT_WINDOW_MS = 30_000L
        private const val MAX_TRACE_BYTES = 64_000

        /**
         * Record that we are about to deliberately kill our own process (e.g. agent_close_app),
         * so the next launch's [uploadPendingExitReasons] doesn't misreport the resulting
         * SIGNALED exit as a crash. Uses commit() because the process is about to die.
         */
        fun flagIntentionalExit(context: Context) {
            try {
                context.getSharedPreferences(CRASH_DIAG_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_INTENTIONAL_EXIT_TS, System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) {
            }
        }
    }
} 