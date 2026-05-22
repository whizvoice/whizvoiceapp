package com.example.whiz.services

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
import com.example.whiz.accessibility.AccessibilityManager
import com.example.whiz.data.api.ApiService
import com.example.whiz.data.preferences.WakeWordPreferences
import com.example.whiz.wakeword.WakeWordEngine
import com.example.whiz.wakeword.audio.SelfEchoGate
import com.example.whiz.wakeword.detection.CamPlusOrtEmbedder
import com.example.whiz.wakeword.detection.ScoreSmoother
import com.example.whiz.wakeword.detection.SileroOrtScorer
import com.example.whiz.wakeword.detection.SpeakerVerifier
import com.example.whiz.wakeword.detection.VadGate
import com.example.whiz.wakeword.enrollment.EnrolledEmbeddingStore
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        const val EXTRA_AUDIO_ONLY = "audio_only"
        private const val SAMPLE_RATE = 16000
        private const val DETECTION_COOLDOWN_MS = 5000L

        // Ring buffer: 25 seconds of 16kHz mono 16-bit PCM = 800000 bytes
        private const val RING_BUFFER_SECONDS = 25
        private const val RING_BUFFER_SIZE = RING_BUFFER_SECONDS * SAMPLE_RATE * 2
        // Wake word detection clips are trimmed to 3 seconds for upload
        private const val WAKE_WORD_CLIP_SECONDS = 3
        private const val WAKE_WORD_CLIP_SIZE = WAKE_WORD_CLIP_SECONDS * SAMPLE_RATE * 2
        private const val MAX_AUDIO_FILES = 500
        private const val MAX_AUDIO_DIR_BYTES = 50L * 1024 * 1024 // 50MB

        private const val RESUME_DEBOUNCE_MS = 500L
        private const val EXTERNAL_RECORDER_RECHECK_MS = 1000L

        // openWakeWord engine frame size (80 ms @ 16 kHz, PCM16 → 2560 bytes per frame).
        private const val FRAME_BYTES = WakeWordEngine.FRAME_SAMPLES * 2

        // ScoreSmoother defaults — tuned on the prototype's Pixel 10 Pro Fold + Samsung
        // S25 Ultra against the openWakeWord TTS-synth eval. May need re-tuning against
        // whiz's deployed mic chain; surface as prefs in commit 6.
        private const val SMOOTHER_WINDOW = 3
        private const val SMOOTHER_ENTER_THRESHOLD = 0.92f
        private const val SMOOTHER_HYSTERESIS = 0.20f
        private const val SMOOTHER_REFRACTORY_MS = 1000

        // VAD gate defaults.
        private const val VAD_SPEECH_THRESHOLD = 0.5f
        private const val VAD_SILENCE_WINDOWS_BEFORE_SKIP = 50  // ~1.6 s @ 32 ms/window
        private const val VAD_PRE_WINDOW_LOOKBACK_MS = 2000L

        // Self-echo gate tail (matches prototype Phase A default).
        private const val SELF_ECHO_TAIL_MS = 300

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var instance: WakeWordService? = null

        fun start(context: Context) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission not granted — skipping WakeWordService start")
                return
            }
            val intent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(intent)
        }

        fun startAudioOnly(context: Context) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO permission not granted — skipping WakeWordService startAudioOnly")
                return
            }
            val intent = Intent(context, WakeWordService::class.java)
            intent.putExtra(EXTRA_AUDIO_ONLY, true)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            context.stopService(intent)
        }

        /**
         * Get a snapshot of the audio ring buffer (up to 25 seconds of PCM data).
         * Returns null if service isn't running or no audio captured.
         */
        fun getAudioSnapshot(): ByteArray? {
            val snapshot = instance?.audioRingBuffer?.snapshot()
            return if (snapshot != null && snapshot.isNotEmpty()) snapshot else null
        }

        /**
         * Convert raw PCM data to WAV format bytes.
         */
        fun pcmToWavBytes(pcmData: ByteArray): ByteArray {
            val totalDataLen = pcmData.size + 36
            val channels = 1
            val bitsPerSample = 16
            val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8

            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            intToByteArrayLE(totalDataLen).copyInto(header, 4)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            intToByteArrayLE(16).copyInto(header, 16)
            shortToByteArrayLE(1).copyInto(header, 20) // PCM format
            shortToByteArrayLE(channels.toShort()).copyInto(header, 22)
            intToByteArrayLE(SAMPLE_RATE).copyInto(header, 24)
            intToByteArrayLE(byteRate).copyInto(header, 28)
            shortToByteArrayLE((channels * bitsPerSample / 8).toShort()).copyInto(header, 32)
            shortToByteArrayLE(bitsPerSample.toShort()).copyInto(header, 34)
            "data".toByteArray().copyInto(header, 36)
            intToByteArrayLE(pcmData.size).copyInto(header, 40)

            return header + pcmData
        }

        private fun intToByteArrayLE(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )

        private fun shortToByteArrayLE(value: Short): ByteArray = byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            (value.toInt() shr 8 and 0xFF).toByte()
        )
    }

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject
    lateinit var apiService: ApiService

    @Inject
    lateinit var accessibilityManager: AccessibilityManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var audioRingBuffer: AudioRingBuffer? = null
    private var detectionJob: Job? = null
    private var detectionEventsJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var engine: WakeWordEngine? = null
    private var sileroScorer: SileroOrtScorer? = null
    private var selfEchoGate: SelfEchoGate? = null
    private var camPlusEmbedder: CamPlusOrtEmbedder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isAudioOnly = false
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
            updateExternalRecorderState(isAnyExternalRecorder(configs), source = "callback")
        }
    }

    // Session-id inequality alone identifies "not us"; AudioFlinger does not reuse
    // session ids across clients. Additional filters on clientAudioSource or
    // clientAudioSessionId == 0 only add false negatives.
    private fun isAnyExternalRecorder(configs: List<AudioRecordingConfiguration>?): Boolean {
        return configs?.any { it.clientAudioSessionId != ownAudioSessionId } ?: false
    }

    private fun updateExternalRecorderState(externalActive: Boolean, source: String) {
        if (externalActive != isExternalRecorderActive) {
            Log.d(TAG, "External recorder active changed ($source): $isExternalRecorderActive -> $externalActive")
            isExternalRecorderActive = externalActive
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
        isRunning = true
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            isPaused = !isPaused
            Log.d(TAG, "Toggle action received, isPaused=$isPaused")
            updateNotification()
            if (isPaused) {
                stopDetection()
            } else {
                if (isAudioOnly) startAudioOnlyCapture() else startDetection()
            }
            return START_STICKY
        }

        isAudioOnly = intent?.getBooleanExtra(EXTRA_AUDIO_ONLY, false) ?: false
        Log.d(TAG, "onStartCommand: isAudioOnly=$isAudioOnly")

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed (likely missing RECORD_AUDIO permission) — stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (isAudioOnly) {
            startAudioOnlyCapture()
        } else {
            startDetection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        instance = null
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
                // Build wake-word engine. Verifier is wired in commit 4; null here means
                // stage-1 only (no voice match). VAD + smoother + self-echo gate enabled.
                val vadEnabled = wakeWordPreferences.isVadEnabledOnce()
                val scorer = if (vadEnabled) {
                    try {
                        SileroOrtScorer(this@WakeWordService).also { sileroScorer = it }
                    } catch (e: Exception) {
                        Log.e(TAG, "Silero VAD init failed; running without VAD gate", e)
                        null
                    }
                } else null
                val vad = scorer?.let {
                    VadGate(
                        scorer = it,
                        speechThreshold = VAD_SPEECH_THRESHOLD,
                        silenceWindowsBeforeSkip = VAD_SILENCE_WINDOWS_BEFORE_SKIP,
                        preWindowLookbackMs = VAD_PRE_WINDOW_LOOKBACK_MS,
                    )
                }
                val smoother = ScoreSmoother(
                    windowSize = SMOOTHER_WINDOW,
                    enterThreshold = SMOOTHER_ENTER_THRESHOLD,
                    hysteresis = SMOOTHER_HYSTERESIS,
                    refractoryMs = SMOOTHER_REFRACTORY_MS,
                )
                selfEchoGate = SelfEchoGate(tailMs = SELF_ECHO_TAIL_MS)

                // Voice match: construct CAM++ verifier iff the user enrolled AND turned the toggle on.
                val verifier = if (wakeWordPreferences.isVoiceMatchEnabledOnce()) {
                    buildSpeakerVerifier()
                } else null
                if (verifier != null) Log.d(TAG, "Voice match enabled — CAM++ verifier active")

                engine = try {
                    WakeWordEngine(
                        context = this@WakeWordService,
                        smoother = smoother,
                        vad = vad,
                        selfEchoGate = selfEchoGate,
                        verifier = verifier,
                        baseStage1Threshold = SMOOTHER_ENTER_THRESHOLD,
                        adaptiveThreshold = null,  // Phase C, out of scope
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "WakeWordEngine init failed — stopping detection", e)
                    return@launch
                }
                Log.d(TAG, "WakeWordEngine initialized (mel + embedding + classifier + Silero VAD${if (verifier != null) " + CAM++" else ""})")

                // Detection events flow → activity launch
                detectionEventsJob = launch {
                    engine?.detections?.collect { event ->
                        val now = System.currentTimeMillis()
                        if (now - lastDetectionTime < DETECTION_COOLDOWN_MS) {
                            Log.d(TAG, "Cooldown active, ignoring detection (score=${event.confidence})")
                            return@collect
                        }
                        lastDetectionTime = now
                        Log.d(TAG, "Wake word detected: score=${event.confidence}")
                        val score = event.confidence.toDouble()
                        wakeWordPreferences.recordDetection("hey_whiz", score, true, "{}", score)
                        val stats = wakeWordPreferences.getStats("hey_whiz")
                        Log.d(TAG, "Stats[hey_whiz]: count=${stats.count}, accepted=${stats.acceptedCount}, mean=${"%.3f".format(stats.mean)}")
                        captureDetectionAudio("hey_whiz", score, true, "{}", score)
                        // Pin buffer "warm" so a follow-up utterance doesn't get swallowed by
                        // a fresh 2 s buffer-fill deadzone. Inference resumes immediately.
                        engine?.softReset()
                        onWakeWordDetected()
                    }
                }

                val bufferSize = maxOf(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ),
                    4096
                )

                audioRecord = createAudioRecord(bufferSize) ?: run {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    return@launch
                }
                audioRecord?.startRecording()
                Log.d(TAG, "Detection loop started")

                audioRingBuffer = AudioRingBuffer(RING_BUFFER_SIZE)

                val buffer = ByteArray(bufferSize)
                // Frame accumulator: PCM16 bytes are read in arbitrary chunks; the engine
                // requires fixed FRAME_BYTES (80 ms = 2560 bytes) per feed().
                val frameBytes = ByteArray(FRAME_BYTES)
                var frameFill = 0
                val frameFloats = FloatArray(WakeWordEngine.FRAME_SAMPLES)

                var frameCount = 0L
                var lastHeartbeatTime = System.currentTimeMillis()
                var lastExternalRecheckTime = System.currentTimeMillis()

                while (true) {
                    val nowRecheck = System.currentTimeMillis()
                    if (nowRecheck - lastExternalRecheckTime >= EXTERNAL_RECORDER_RECHECK_MS) {
                        val freshConfigs = audioManager?.activeRecordingConfigurations
                        updateExternalRecorderState(isAnyExternalRecorder(freshConfigs), source = "recheck")
                        lastExternalRecheckTime = nowRecheck
                    }

                    val shouldPause = speechRecognitionService.isListening.value || isExternalRecorderActive
                    if (shouldPause) {
                        audioRecord?.let { rec ->
                            val reason = when {
                                speechRecognitionService.isListening.value -> "main speech recognizer active"
                                isExternalRecorderActive -> "external recorder active"
                                else -> "unknown"
                            }
                            try {
                                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error stopping AudioRecord on pause", e)
                            }
                            rec.release()
                            audioRecord = null
                            // Audio gap will desync the engine ring buffer; reset on resume.
                            frameFill = 0
                            Log.d(TAG, "Paused (released): $reason")
                        }
                        delay(200)
                        continue
                    }

                    if (audioRecord == null) {
                        delay(RESUME_DEBOUNCE_MS)
                        if (speechRecognitionService.isListening.value || isExternalRecorderActive) continue
                        val fresh = createAudioRecord(bufferSize)
                        if (fresh == null) {
                            delay(500)
                            continue
                        }
                        audioRecord = fresh
                        fresh.startRecording()
                        engine?.reset()  // hard reset: buffer was discontinuous across pause
                        Log.d(TAG, "Resumed: created fresh AudioRecord")
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead <= 0) {
                        delay(10)
                        continue
                    }

                    // Existing: feed audio to ring buffer for detection-clip upload.
                    audioRingBuffer?.write(buffer, 0, bytesRead)

                    // New: chunk raw PCM16 bytes into FRAME_BYTES-sized frames, convert to
                    // float32 in [-1, 1], and feed engine. Partial frames carry over to
                    // the next read.
                    var srcOff = 0
                    while (srcOff < bytesRead) {
                        val take = minOf(FRAME_BYTES - frameFill, bytesRead - srcOff)
                        System.arraycopy(buffer, srcOff, frameBytes, frameFill, take)
                        srcOff += take
                        frameFill += take
                        if (frameFill == FRAME_BYTES) {
                            for (i in 0 until WakeWordEngine.FRAME_SAMPLES) {
                                val lo = frameBytes[i * 2].toInt() and 0xFF
                                val hi = frameBytes[i * 2 + 1].toInt()  // signed
                                val s16 = (hi shl 8) or lo
                                frameFloats[i] = s16 / 32768f
                            }
                            engine?.feed(frameFloats)
                            frameFill = 0
                        }
                    }

                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatTime >= 10_000) {
                        Log.d(TAG, "Heartbeat: processed $frameCount audio chunks, recording=${audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING}")
                        lastHeartbeatTime = now
                        frameCount = 0
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Detection loop error", e)
            }
        }
    }

    /**
     * Build the CAM++ speaker verifier if the user has enrolled (sidecar + embeddings file exist).
     * Returns null on any failure so the engine falls back to stage-1-only detection.
     */
    private fun buildSpeakerVerifier(): SpeakerVerifier? {
        return try {
            val wakeWordDir = File(filesDir, "wake_word").apply { mkdirs() }
            val sidecar = File(wakeWordDir, "enrollment/enrollment.json")
            val embeddingsFile = File(wakeWordDir, "embeddings.bin")
            if (!sidecar.exists() || !embeddingsFile.exists() || embeddingsFile.length() <= 8) {
                // Fail OPEN: persist a broken flag + auto-disable the toggle so Settings
                // surfaces the error to the user. Wake word continues working in
                // anyone-can-trigger mode — a silently-broken wake word is worse than
                // a permissive one.
                Log.w(TAG, "Voice match toggle ON but enrollment missing — auto-disabling, surfacing error in Settings")
                wakeWordPreferences.setVoiceMatchBroken(true)
                wakeWordPreferences.setVoiceMatchEnabled(false)
                return null
            }
            val embedder = CamPlusOrtEmbedder(this).also { camPlusEmbedder = it }
            val store = EnrolledEmbeddingStore(
                dir = wakeWordDir,
                dim = CamPlusOrtEmbedder.EMBEDDING_DIM,
                cap = 50,
                protectedCap = 5,
            )
            SpeakerVerifier(
                embedder = embedder,
                store = store,
                threshold = wakeWordPreferences.verifierThresholdOnce(),
                enforceGate = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build SpeakerVerifier — falling back to stage-1 only", e)
            null
        }
    }

    /**
     * Construct a fresh AudioRecord. Refreshes ownAudioSessionId before the caller
     * starts recording — otherwise the AudioRecordingCallback would see the new
     * session as "external" and we'd flap pause→release→recreate.
     */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord construction threw", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        ownAudioSessionId = record.audioSessionId
        Log.d(TAG, "AudioRecord created — sessionId=$ownAudioSessionId, source=VOICE_COMMUNICATION, aecAttached=false (no canceler on this session)")
        return record
    }

    /**
     * Audio-only mode: record audio into ring buffer without wake-word inference.
     * Used for bug report audio capture when wake word detection is off.
     */
    private fun startAudioOnlyCapture() {
        if (detectionJob?.isActive == true) return

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whiz:wake_word_detection")
        }
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
                Log.d(TAG, "Wake lock acquired (audio-only)")
            }
        }

        detectionJob = serviceScope.launch {
            try {
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
                    Log.e(TAG, "AudioRecord failed to initialize (audio-only)")
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d(TAG, "Audio-only capture started")

                audioRingBuffer = AudioRingBuffer(RING_BUFFER_SIZE)

                val buffer = ByteArray(bufferSize)
                while (true) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead <= 0) {
                        delay(10)
                        continue
                    }
                    audioRingBuffer?.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Audio-only capture error", e)
            }
        }
    }

    private fun onWakeWordDetected() {
        try {
            // Set BEFORE launching activity — prevents race with ACTION_SCREEN_ON.
            // setTurnScreenOn(true) in AssistantActivity triggers ACTION_SCREEN_ON before
            // Hilt injects voiceManager, so isWakeWordActiveSession must already be true.
            com.example.whiz.ui.viewmodels.VoiceManager.instance?.let {
                it.isWakeWordActiveSession = true
                Log.d(TAG, "Set isWakeWordActiveSession=true before launching activity")
            }

            Log.d(TAG, "Launching AssistantActivity")
            accessibilityManager.dismissNotificationShade()
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

    private fun stopDetection() {
        audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        val job = detectionJob
        detectionJob = null
        detectionEventsJob?.cancel()
        detectionEventsJob = null
        audioRingBuffer = null
        job?.cancel()
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        if (job != null) {
            try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(2000L) { job.join() }
                        ?: Log.w(TAG, "Detection coroutine did not exit within 2s of cancel")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error joining detection coroutine", e)
            }
        }
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released in stopDetection")
            }
        }
    }

    private fun releaseResources() {
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
            engine?.close()
            engine = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WakeWordEngine", e)
        }
        try {
            sileroScorer?.close()
            sileroScorer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Silero scorer", e)
        }
        try {
            camPlusEmbedder?.close()
            camPlusEmbedder = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing CAM++ embedder", e)
        }
        selfEchoGate = null
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

    // ========== AUDIO CLIP CAPTURE ==========

    /**
     * Fixed-size circular byte buffer for retaining recent audio samples.
     * Only used from the single audio read coroutine — not thread-safe.
     */
    private class AudioRingBuffer(private val capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var writePos = 0
        private var totalWritten = 0L

        @Synchronized
        fun write(data: ByteArray, offset: Int, length: Int) {
            var remaining = length
            var srcOffset = offset
            while (remaining > 0) {
                val chunk = minOf(remaining, capacity - writePos)
                System.arraycopy(data, srcOffset, buffer, writePos, chunk)
                writePos = (writePos + chunk) % capacity
                srcOffset += chunk
                remaining -= chunk
            }
            totalWritten += length
        }

        /** Returns buffer contents in chronological order. */
        @Synchronized
        fun snapshot(): ByteArray {
            val available = minOf(totalWritten, capacity.toLong()).toInt()
            if (available == 0) return ByteArray(0)

            val result = ByteArray(available)
            if (totalWritten < capacity) {
                System.arraycopy(buffer, 0, result, 0, available)
            } else {
                val tailLen = capacity - writePos
                System.arraycopy(buffer, writePos, result, 0, tailLen)
                System.arraycopy(buffer, 0, result, tailLen, writePos)
            }
            return result
        }

        /** Returns the last N bytes from the buffer (trimmed snapshot). */
        @Synchronized
        fun snapshot(maxBytes: Int): ByteArray {
            val full = snapshot()
            return if (full.size <= maxBytes) full
            else full.copyOfRange(full.size - maxBytes, full.size)
        }
    }

    private fun captureDetectionAudio(
        phrase: String,
        confidence: Double,
        accepted: Boolean,
        rawVoskJson: String,
        classifierScore: Double = -1.0
    ) {
        // Trim to last 3 seconds for wake word clips
        val pcmSnapshot = audioRingBuffer?.snapshot(WAKE_WORD_CLIP_SIZE)
        if (pcmSnapshot == null || pcmSnapshot.isEmpty()) return

        try {
            val timestamp = System.currentTimeMillis()
            val confStr = "%.0f".format(confidence * 100)  // engine score is [0,1]; scale for filename
            val audioDir = File(getExternalFilesDir(null), "wake_word_audio")
            audioDir.mkdirs()
            val wavFile = File(audioDir, "detection_${timestamp}_${confStr}.wav")
            saveWavFile(pcmSnapshot, wavFile)
            Log.d(TAG, "Saved detection audio: ${wavFile.name} (${pcmSnapshot.size} bytes PCM)")
            uploadWakeWordAudio(wavFile, phrase, confidence, accepted, rawVoskJson, classifierScore)
            enforceAudioStorageCap(audioDir)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save detection audio", e)
        }
    }

    private fun saveWavFile(pcmData: ByteArray, file: File) {
        FileOutputStream(file).use { fos ->
            fos.write(pcmToWavBytes(pcmData))
        }
    }

    private fun uploadWakeWordAudio(
        wavFile: File,
        phrase: String,
        confidence: Double,
        accepted: Boolean,
        rawVoskJson: String,
        classifierScore: Double = -1.0
    ) {
        serviceScope.launch {
            try {
                val filePart = MultipartBody.Part.createFormData(
                    "file",
                    wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaType())
                )
                val textType = "text/plain".toMediaType()

                // raw_vosk_json: legacy field name (Vosk pipeline removed). Server contract
                // unchanged; sending "{}" until the endpoint can drop or rename the field.
                apiService.uploadWakeWordAudio(
                    file = filePart,
                    phrase = phrase.toRequestBody(textType),
                    confidence = confidence.toString().toRequestBody(textType),
                    accepted = accepted.toString().toRequestBody(textType),
                    timestamp = System.currentTimeMillis().toString().toRequestBody(textType),
                    rawVoskJson = rawVoskJson.toRequestBody(textType),
                    classifierScore = classifierScore.toString().toRequestBody(textType)
                )
                wavFile.delete()
                Log.d(TAG, "Uploaded and deleted wake word audio: ${wavFile.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload wake word audio (kept locally): ${e.message}")
            }
        }
    }

    private fun enforceAudioStorageCap(audioDir: File) {
        try {
            val files = audioDir.listFiles { f -> f.extension == "wav" }
                ?.sortedBy { it.lastModified() } ?: return

            var filesToDelete = files.size - MAX_AUDIO_FILES
            var totalSize = files.sumOf { it.length() }

            for (file in files) {
                if (filesToDelete <= 0 && totalSize <= MAX_AUDIO_DIR_BYTES) break
                val size = file.length()
                if (file.delete()) {
                    filesToDelete--
                    totalSize -= size
                    Log.d(TAG, "Deleted old audio file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enforcing audio storage cap", e)
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
        val contentText = when {
            isPaused -> "Wake word detection paused"
            isAudioOnly -> "Audio capture active (developer mode)"
            else -> "Listening for \"Hey Whiz\"..."
        }

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
