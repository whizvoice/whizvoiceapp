package com.example.whiz.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.whiz.AssistantActivity
import com.example.whiz.R
import com.example.whiz.data.preferences.WakeWordPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_TOGGLE = "com.example.whiz.ACTION_TOGGLE_WAKE_WORD"
        private const val SAMPLE_RATE = 16000
        private const val DETECTION_COOLDOWN_MS = 5000L
        private const val WAKE_WORD_CONFIDENCE_THRESHOLD_HEY = 95.0

        private const val RESUME_DEBOUNCE_MS = 500L
        private const val MODEL_VERSION_KEY = "vosk_model_version"
        private const val MODEL_VERSION = "en-us-0.22-lgraph"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.stopService(intent)
        }
    }

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var detectionJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isPaused = false
    @Volatile
    private var lastDetectionTime = 0L
    @Volatile
    private var isExternalRecorderActive = false
    private var ownAudioSessionId: Int = 0
    private var audioManager: AudioManager? = null
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
            val myUid = android.os.Process.myUid()
            val externalActive = configs?.any { config ->
                config.clientAudioSource != MediaRecorder.AudioSource.DEFAULT &&
                    config.clientAudioSessionId != 0 &&
                    config.clientAudioSessionId != ownAudioSessionId &&
                    getUidFromConfig(config, myUid) != myUid
            } ?: false
            if (externalActive != isExternalRecorderActive) {
                Log.d(TAG, "External recorder active changed: $isExternalRecorderActive -> $externalActive")
                isExternalRecorderActive = externalActive
            }
        }
    }

    private fun getUidFromConfig(config: AudioRecordingConfiguration, fallback: Int): Int {
        return try {
            val method = AudioRecordingConfiguration::class.java.getMethod("getClientUid")
            method.invoke(config) as Int
        } catch (e: Exception) {
            fallback // If reflection fails, treat as own UID to avoid self-detection
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            isPaused = !isPaused
            Log.d(TAG, "Toggle action received, isPaused=$isPaused")
            updateNotification()
            if (isPaused) {
                stopDetection()
            } else {
                startDetection()
            }
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startDetection()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        stopDetection()
        releaseResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startDetection() {
        if (detectionJob?.isActive == true) return

        // Register for audio recording changes to yield mic to external apps
        if (audioManager == null) {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        audioManager?.registerAudioRecordingCallback(recordingCallback, null)

        // Acquire partial wake lock to keep CPU alive during screen-off detection
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whiz:wake_word_detection")
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.d(TAG, "Wake lock acquired")
            }
        }

        detectionJob = serviceScope.launch {
            try {
                val model = ensureModelReady() ?: run {
                    Log.e(TAG, "Failed to load Vosk model")
                    return@launch
                }
                voskModel = model

                val grammar = "[\"hey whiz\", \"[unk]\"]"
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammar).apply {
                    setMaxAlternatives(1)
                }

                val bufferSize = maxOf(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    4096
                )

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }

                ownAudioSessionId = audioRecord?.audioSessionId ?: 0
                Log.d(TAG, "Own audio session ID: $ownAudioSessionId")

                audioRecord?.startRecording()
                Log.d(TAG, "Detection loop started")

                val buffer = ByteArray(bufferSize)
                var frameCount = 0L
                var lastHeartbeatTime = System.currentTimeMillis()

                while (true) {
                    // Pause while main speech recognizer or external recorder is active
                    val shouldPause = speechRecognitionService.isListening.value || isExternalRecorderActive
                    if (shouldPause) {
                        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val reason = when {
                                speechRecognitionService.isListening.value -> "main speech recognizer active"
                                isExternalRecorderActive -> "external recorder active"
                                else -> "unknown"
                            }
                            audioRecord?.stop()
                            Log.d(TAG, "Paused: $reason")
                        }
                        delay(200)
                        continue
                    }

                    // Resume after pause source stops (with debounce)
                    if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        delay(RESUME_DEBOUNCE_MS)
                        // Re-check that no pause source has started again
                        if (speechRecognitionService.isListening.value || isExternalRecorderActive) continue
                        audioRecord?.startRecording()
                        Log.d(TAG, "Resumed: no active recorders")
                    }

                    // Cooldown after detection
                    if (System.currentTimeMillis() - lastDetectionTime < DETECTION_COOLDOWN_MS) {
                        delay(200)
                        continue
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead <= 0) {
                        delay(10)
                        continue
                    }

                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatTime >= 10_000) {
                        Log.d(TAG, "Heartbeat: processed $frameCount audio frames, recording=${audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING}")
                        lastHeartbeatTime = now
                        frameCount = 0
                    }

                    val rec = recognizer ?: continue
                    if (rec.acceptWaveForm(buffer, bytesRead)) {
                        val result = rec.result
                        checkForWakeWord(result)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Detection loop error", e)
            }
        }
    }

    private fun checkForWakeWord(jsonResult: String) {
        try {
            val json = JSONObject(jsonResult)
            val alternatives = json.optJSONArray("alternatives") ?: return
            if (alternatives.length() == 0) return

            val best = alternatives.getJSONObject(0)
            val text = best.optString("text", "").lowercase()
            val confidence = best.optDouble("confidence", 0.0)

            val (threshold, phraseKey) = when {
                text.contains("hey whiz") -> WAKE_WORD_CONFIDENCE_THRESHOLD_HEY to "hey_whiz"
                else -> return
            }

            val accepted = confidence >= threshold
            wakeWordPreferences.recordDetection(phraseKey, confidence, accepted)
            val stats = wakeWordPreferences.getStats(phraseKey)
            Log.d(TAG, "Stats[$phraseKey]: count=${stats.count}, accepted=${stats.acceptedCount}, mean=${"%.1f".format(stats.mean)}, stdDev=${"%.1f".format(stats.stdDev)}, last=${"%.1f".format(stats.lastConfidence)}")

            if (accepted) {
                Log.d(TAG, "Wake word detected: '$text' (confidence=$confidence, threshold=$threshold)")
                lastDetectionTime = System.currentTimeMillis()
                recognizer?.reset()
                onWakeWordDetected()
            } else {
                Log.d(TAG, "Wake word rejected (low confidence): '$text' (confidence=$confidence, threshold=$threshold)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing recognizer result", e)
        }
    }

    private fun onWakeWordDetected() {
        try {
            // Set BEFORE launching activity — prevents race with ACTION_SCREEN_ON
            // setTurnScreenOn(true) in AssistantActivity triggers ACTION_SCREEN_ON before
            // Hilt injects voiceManager, so isWakeWordActiveSession must already be true
            com.example.whiz.ui.viewmodels.VoiceManager.instance?.let {
                it.isWakeWordActiveSession = true
                Log.d(TAG, "Set isWakeWordActiveSession=true before launching activity")
            }

            Log.d(TAG, "Launching AssistantActivity")
            collapseStatusBar()
            val assistantIntent = Intent(this, AssistantActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("IS_ASSISTANT_LAUNCH", true)
                putExtra("FROM_WAKE_WORD", true)
            }
            startActivity(assistantIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch AssistantActivity", e)
        }
    }

    @SuppressLint("WrongConstant")
    private fun collapseStatusBar() {
        try {
            val service = getSystemService("statusbar")
            val clazz = Class.forName("android.app.StatusBarManager")
            val method = clazz.getMethod("collapsePanels")
            method.invoke(service)
        } catch (e: Exception) {
            Log.w(TAG, "Could not collapse status bar", e)
        }
    }

    private fun stopDetection() {
        audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        detectionJob?.cancel()
        detectionJob = null
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released in stopDetection")
            }
        }
    }

    private fun releaseResources() {
        // Defensive unregister in case stopDetection wasn't called
        try {
            audioManager?.unregisterAudioRecordingCallback(recordingCallback)
            audioManager = null
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering recording callback", e)
        }
        try {
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        try {
            recognizer?.close()
            recognizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing recognizer", e)
        }
        try {
            voskModel?.close()
            voskModel = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing model", e)
        }
        // Release wake lock
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "Wake lock released in releaseResources")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }
    }

    private fun ensureModelReady(): Model? {
        val modelDir = File(filesDir, "vosk-model")
        val prefs = getSharedPreferences("vosk_prefs", Context.MODE_PRIVATE)
        val storedVersion = prefs.getString(MODEL_VERSION_KEY, null)

        if (modelDir.exists() && storedVersion == MODEL_VERSION) {
            Log.d(TAG, "Model already extracted, loading from ${modelDir.absolutePath}")
            return try {
                Model(modelDir.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing model, re-extracting", e)
                modelDir.deleteRecursively()
                extractAndLoadModel(modelDir, prefs)
            }
        }

        return extractAndLoadModel(modelDir, prefs)
    }

    private fun extractAndLoadModel(
        modelDir: File,
        prefs: android.content.SharedPreferences
    ): Model? {
        return try {
            Log.d(TAG, "Extracting Vosk model from assets to ${modelDir.absolutePath}")
            modelDir.deleteRecursively()
            modelDir.mkdirs()
            copyAssetDir("model-en-us", modelDir)
            prefs.edit().putString(MODEL_VERSION_KEY, MODEL_VERSION).apply()
            Log.d(TAG, "Model extraction complete")
            Model(modelDir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract/load Vosk model", e)
            null
        }
    }

    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val assetManager = assets
        val files = assetManager.list(assetPath) ?: return

        targetDir.mkdirs()

        for (file in files) {
            val srcPath = "$assetPath/$file"
            val targetFile = File(targetDir, file)
            val subFiles = assetManager.list(srcPath)

            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetDir(srcPath, targetFile)
            } else {
                assetManager.open(srcPath).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wake Word Detection",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Listening for \"Hey Whiz\""
            setShowBadge(false)
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val toggleIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionText = if (isPaused) "Resume" else "Pause"
        val contentText = if (isPaused) "Wake word detection paused" else "Listening for \"Hey Whiz\"..."

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whiz Voice")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(0, actionText, togglePendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }
}
