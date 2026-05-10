package com.example.whiz.services

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.whiz.BuildConfig
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.data.api.ApiService
import com.example.whiz.util.LogcatCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class BugReportSnapshot(
    val uiHierarchy: String?,
    val currentPackage: String?,
    val screenshotBase64: String?,
    val logcat: String?,
    val audioBase64: String?
)

@Singleton
class BugReportSubmitter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    companion object {
        private const val TAG = "BugReportSubmitter"
    }

    suspend fun captureSnapshot(): BugReportSnapshot = withContext(Dispatchers.IO) {
        Log.i(TAG, "Capturing bug report snapshot")

        val uiHierarchy = WhizAccessibilityService.getCurrentUiHierarchy()
        val currentPackage = WhizAccessibilityService.getCurrentPackageName()
        val logcat = LogcatCapture.capture()

        val audioBase64 = try {
            WakeWordService.getAudioSnapshot()?.let { pcmData ->
                val wavBytes = WakeWordService.pcmToWavBytes(pcmData)
                Base64.encodeToString(wavBytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture audio snapshot", e)
            null
        }

        val screenshotBase64 = suspendCancellableCoroutine<String?> { cont ->
            WhizAccessibilityService.takeScreenshotAsync { bitmap ->
                val encoded = bitmap?.let {
                    try {
                        val softwareBitmap = it.copy(Bitmap.Config.ARGB_8888, false)
                        val stream = ByteArrayOutputStream()
                        softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                        softwareBitmap.recycle()
                        it.recycle()
                        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to encode screenshot", e)
                        null
                    }
                }
                if (cont.isActive) cont.resume(encoded)
            }
        }

        Log.i(TAG, "Bug report snapshot captured")
        BugReportSnapshot(
            uiHierarchy = uiHierarchy,
            currentPackage = currentPackage,
            screenshotBase64 = screenshotBase64,
            logcat = logcat,
            audioBase64 = audioBase64
        )
    }

    suspend fun submit(
        snapshot: BugReportSnapshot,
        description: String?,
        source: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Submitting bug report (source=$source)")
        try {
            val displayMetrics = context.resources.displayMetrics
            val request = ApiService.UiDumpCreate(
                dumpReason = source,
                errorMessage = description?.ifBlank { null },
                uiHierarchy = snapshot.uiHierarchy,
                packageName = snapshot.currentPackage,
                deviceModel = Build.MODEL,
                deviceManufacturer = Build.MANUFACTURER,
                androidVersion = Build.VERSION.RELEASE,
                screenWidth = displayMetrics.widthPixels,
                screenHeight = displayMetrics.heightPixels,
                appVersion = BuildConfig.VERSION_NAME,
                conversationId = null,
                recentActions = null,
                screenAgentContext = buildMap {
                    put("report_type", source)
                    if (snapshot.screenshotBase64 != null) {
                        put("screenshot_base64", snapshot.screenshotBase64)
                    }
                    if (snapshot.logcat != null) {
                        put("logcat", snapshot.logcat)
                    }
                    if (snapshot.audioBase64 != null) {
                        put("audio_base64", snapshot.audioBase64)
                    }
                }
            )

            apiService.uploadUiDump(request)
            Log.i(TAG, "Bug report uploaded successfully (source=$source)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload bug report", e)
            Result.failure(e)
        }
    }

    suspend fun captureAndSubmit(message: String, source: String): Result<Unit> {
        val snapshot = captureSnapshot()
        return submit(snapshot, description = message, source = source)
    }
}
