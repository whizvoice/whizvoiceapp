package com.example.whiz.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.whiz.wakeword.audio.SelfEchoGate
import com.example.whiz.wakeword.detection.AdaptiveThresholdController
import com.example.whiz.wakeword.detection.ScoreSmoother
import com.example.whiz.wakeword.detection.SpeakerVerifier
import com.example.whiz.wakeword.detection.VadGate
import com.example.whiz.wakeword.metrics.MetricsSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.FloatBuffer

data class DetectionEvent(val timestampMs: Long, val confidence: Float)

data class TimedDetection(val event: DetectionEvent, val captureTimeMs: Long)

interface WakeWordEngineApi : AutoCloseable {
    fun feed(samples: FloatArray)
    val detections: SharedFlow<DetectionEvent>
    var threshold: Float
    var consecutiveFramesRequired: Int
}

/**
 * Wake-word detection engine — buffered, openWakeWord-compatible.
 *
 * Architecture (matches livekit.wakeword.WakeWordModel.predict):
 *   1. Buffer ~2 s of incoming PCM audio (32 000 samples @ 16 kHz).
 *   2. Once full, every [inferenceIntervalFrames] new 80 ms frames, run the full chain:
 *      - mel(audio_2s)              → (T, 32) features, with `x/10 + 2` post-norm
 *      - 76-frame sliding window, stride 8, fed through embedding → (n_windows, 96)
 *      - last 16 embeddings → classifier → scalar score in [0, 1]
 *   3. Score feeds into [smoother] (or [Debouncer] fallback); detection emitted via
 *      [detections] Flow.
 *
 * Cost: each inference does ~24 embedding ONNX calls + 1 mel + 1 classifier.
 * Running every 4 frames (320 ms) keeps CPU manageable on modern devices while
 * staying responsive enough for a wake word.
 */
class WakeWordEngine(
    context: Context,
    classifierAsset: String = DEFAULT_CLASSIFIER_ASSET,
    private val smoother: ScoreSmoother? = null,
    private val vad: VadGate? = null,
    private val selfEchoGate: SelfEchoGate? = null,
    private val verifier: SpeakerVerifier? = null,
    private val baseStage1Threshold: Float = 0f,
    private val adaptiveThreshold: AdaptiveThresholdController? = null,
) : WakeWordEngineApi, MetricsSource {

    override val name: String = "wakeword.engine"

    /** Verdict from the most recent verifier run, or null if verifier didn't run for this fire. */
    @Volatile var lastVerifierVerdict: SpeakerVerifier.Verdict? = null
        private set

    private var inferencesTotal: Long = 0L
    private val latenciesMs = ArrayDeque<Long>(LATENCY_WINDOW)

    private fun recordInferenceLatency(ms: Long) {
        if (latenciesMs.size >= LATENCY_WINDOW) latenciesMs.removeFirst()
        latenciesMs.addLast(ms)
        inferencesTotal += 1
    }

    override fun snapshot(): Map<String, Any> {
        val sorted = latenciesMs.sorted()
        val n = sorted.size
        val p50 = if (n == 0) 0L else sorted[n / 2]
        val p95 = if (n == 0) 0L else sorted[((n * 95) / 100).coerceAtMost(n - 1)]
        val p99 = if (n == 0) 0L else sorted[((n * 99) / 100).coerceAtMost(n - 1)]
        return mapOf(
            "inferences_total" to inferencesTotal,
            "latency_ms_p50" to p50,
            "latency_ms_p95" to p95,
            "latency_ms_p99" to p99,
        )
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val melSession: OrtSession
    private val embeddingSession: OrtSession
    private val classifierSession: OrtSession

    private val _detections =
        MutableSharedFlow<DetectionEvent>(replay = 0, extraBufferCapacity = 16)
    override val detections: SharedFlow<DetectionEvent> = _detections.asSharedFlow()

    private val _rawScores =
        MutableSharedFlow<Float>(replay = 0, extraBufferCapacity = 16)
    /** Raw classifier scores emitted on every inference run (before debouncing). */
    val rawScores: SharedFlow<Float> = _rawScores.asSharedFlow()

    private val debouncer = Debouncer(threshold = 0.5f, framesRequired = 3)

    override var threshold: Float
        get() = debouncer.threshold
        set(value) { debouncer.threshold = value }

    override var consecutiveFramesRequired: Int
        get() = debouncer.framesRequired
        set(value) { debouncer.framesRequired = value }

    var inferenceIntervalFrames: Int = 4  // ~320 ms at 80 ms/frame

    private val ringBuffer = FloatArray(AUDIO_BUFFER_SAMPLES)
    private var writePos = 0
    private var totalSamplesWritten = 0L
    private var framesSinceLastInference = 0

    private val vadAccum = FloatArray(VAD_WINDOW_SAMPLES)
    private var vadFill = 0

    init {
        melSession = loadModel(context, "melspectrogram.onnx")
        embeddingSession = loadModel(context, "embedding_model.onnx")
        classifierSession = loadModel(context, classifierAsset)
    }

    private fun loadModel(context: Context, assetName: String): OrtSession {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    /** Feed one 80 ms PCM frame (1280 samples, float32 in [-1, 1]). */
    override fun feed(samples: FloatArray) {
        require(samples.size == FRAME_SAMPLES) {
            "Expected $FRAME_SAMPLES samples per frame, got ${samples.size}"
        }
        val nowMs = System.currentTimeMillis()

        vad?.let { pumpVad(samples, nowMs, it) }

        for (s in samples) {
            ringBuffer[writePos] = s
            writePos = (writePos + 1) % AUDIO_BUFFER_SAMPLES
        }
        totalSamplesWritten += samples.size
        framesSinceLastInference++

        if (totalSamplesWritten < AUDIO_BUFFER_SAMPLES) return
        if (framesSinceLastInference < inferenceIntervalFrames) return
        framesSinceLastInference = 0

        if (selfEchoGate?.isBlocking(atMs = nowMs) == true) return
        if (vad?.shouldSkipInference(atMs = nowMs) == true) {
            vad.maybeRecordSkip(atMs = nowMs)
            return
        }

        val ordered = FloatArray(AUDIO_BUFFER_SAMPLES)
        for (i in 0 until AUDIO_BUFFER_SAMPLES) {
            ordered[i] = ringBuffer[(writePos + i) % AUDIO_BUFFER_SAMPLES]
        }

        val t0 = System.nanoTime()
        val score = runInference(ordered)
        recordInferenceLatency((System.nanoTime() - t0) / 1_000_000)
        _rawScores.tryEmit(score)
        adaptiveThreshold?.onNonFireScore(score)

        if (smoother != null) {
            adaptiveThreshold?.let { ctrl ->
                smoother.setEnterThreshold(ctrl.effectiveThreshold(baseStage1Threshold))
            }
            if (vad != null && !vad.allowsFireAt(atMs = nowMs)) return
            if (smoother.onScore(score, atMs = nowMs)) {
                val verdict = verifier?.verify(captureBuffer())
                lastVerifierVerdict = verdict
                if (verdict == null || verdict.gatePassed) {
                    _detections.tryEmit(DetectionEvent(timestampMs = nowMs, confidence = score))
                }
            }
        } else {
            val event = debouncer.update(score, nowMs)
            if (event != null) _detections.tryEmit(event)
        }
    }

    private fun pumpVad(samples: FloatArray, sampleStartMs: Long, gate: VadGate) {
        var srcIdx = 0
        while (srcIdx < samples.size) {
            val take = minOf(VAD_WINDOW_SAMPLES - vadFill, samples.size - srcIdx)
            System.arraycopy(samples, srcIdx, vadAccum, vadFill, take)
            vadFill += take; srcIdx += take
            if (vadFill == VAD_WINDOW_SAMPLES) {
                val windowMs = sampleStartMs + ((srcIdx - VAD_WINDOW_SAMPLES) * 1000L) / SAMPLE_RATE_HZ
                gate.onWindow(vadAccum.copyOf(), atMs = windowMs)
                vadFill = 0
            }
        }
    }

    /** Snapshot the current 2 s ring buffer as PCM16, chronologically ordered. */
    fun captureBuffer(): ShortArray {
        val out = ShortArray(AUDIO_BUFFER_SAMPLES)
        for (i in 0 until AUDIO_BUFFER_SAMPLES) {
            val sample = ringBuffer[(writePos + i) % AUDIO_BUFFER_SAMPLES]
            out[i] = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** Clear the ring buffer and inference cadence — next [feed] starts fresh. */
    fun reset() {
        for (i in ringBuffer.indices) ringBuffer[i] = 0f
        writePos = 0
        totalSamplesWritten = 0L
        framesSinceLastInference = 0
        vadFill = 0
        debouncer.reset()
        smoother?.reset()
    }

    /**
     * Clear the buffer but keep the engine "warm" — `totalSamplesWritten` is
     * pinned at [AUDIO_BUFFER_SAMPLES] so the next `feed()` call can run
     * inference on the next [inferenceIntervalFrames] tick rather than waiting
     * 2 s for the buffer to physically fill.
     */
    fun softReset() {
        for (i in ringBuffer.indices) ringBuffer[i] = 0f
        writePos = 0
        totalSamplesWritten = AUDIO_BUFFER_SAMPLES.toLong()
        framesSinceLastInference = 0
        vadFill = 0
        debouncer.reset()
        smoother?.reset()
    }

    private fun runInference(audio: FloatArray): Float {
        // ---- Stage 1: mel ----
        val melInput = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
        )
        val melResult = melSession.run(
            mapOf(melSession.inputNames.first() to melInput)
        )
        @Suppress("UNCHECKED_CAST")
        val melRaw = melResult[0].value as Array<Array<Array<FloatArray>>>
        val timeFrames = melRaw[0][0].size
        val melFeatures = Array(timeFrames) { t ->
            FloatArray(MEL_BINS) { m -> melRaw[0][0][t][m] / 10f + 2f }
        }
        melInput.close()
        melResult.close()

        if (timeFrames < EMBEDDING_WINDOW) return 0f
        val numWindows = (timeFrames - EMBEDDING_WINDOW) / EMBEDDING_STRIDE + 1
        if (numWindows < MIN_EMBEDDINGS) return 0f

        // ---- Stage 2: sliding-window embeddings ----
        val embeddings = ArrayList<FloatArray>(numWindows)
        val windowFlat = FloatArray(EMBEDDING_WINDOW * MEL_BINS)
        for (w in 0 until numWindows) {
            val start = w * EMBEDDING_STRIDE
            for (r in 0 until EMBEDDING_WINDOW) {
                System.arraycopy(melFeatures[start + r], 0, windowFlat, r * MEL_BINS, MEL_BINS)
            }
            val embInput = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(windowFlat.copyOf()),
                longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1)
            )
            val embResult = embeddingSession.run(
                mapOf(embeddingSession.inputNames.first() to embInput)
            )
            @Suppress("UNCHECKED_CAST")
            val embRaw = embResult[0].value as Array<Array<Array<FloatArray>>>
            embeddings.add(embRaw[0][0][0].copyOf())
            embInput.close()
            embResult.close()
        }

        // ---- Stage 3: classifier on last 16 embeddings ----
        val last16Start = embeddings.size - MIN_EMBEDDINGS
        val clsFlat = FloatArray(MIN_EMBEDDINGS * EMBEDDING_DIM)
        for (i in 0 until MIN_EMBEDDINGS) {
            System.arraycopy(embeddings[last16Start + i], 0, clsFlat, i * EMBEDDING_DIM, EMBEDDING_DIM)
        }
        val clsInput = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(clsFlat),
            longArrayOf(1, MIN_EMBEDDINGS.toLong(), EMBEDDING_DIM.toLong())
        )
        val clsResult = classifierSession.run(
            mapOf(classifierSession.inputNames.first() to clsInput)
        )
        @Suppress("UNCHECKED_CAST")
        val raw = clsResult[0].value as Array<FloatArray>
        val score = raw[0][0]
        clsInput.close()
        clsResult.close()
        return score
    }

    /**
     * One-shot direct inference on a 2 s clip. Used by enrollment scoring;
     * does NOT update the ring buffer or emit detection events.
     */
    fun scoreClipDirect(pcm: ShortArray): Float {
        require(pcm.size == AUDIO_BUFFER_SAMPLES) {
            "Expected $AUDIO_BUFFER_SAMPLES samples, got ${pcm.size}"
        }
        val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
        return runInference(floats)
    }

    override fun close() {
        melSession.close()
        embeddingSession.close()
        classifierSession.close()
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val FRAME_MS = 80
        const val FRAME_SAMPLES = SAMPLE_RATE_HZ / 1000 * FRAME_MS  // 1280

        const val AUDIO_BUFFER_SECONDS = 2
        const val AUDIO_BUFFER_SAMPLES = SAMPLE_RATE_HZ * AUDIO_BUFFER_SECONDS  // 32000

        const val MEL_BINS = 32
        const val EMBEDDING_WINDOW = 76     // mel frames per embedding window
        const val EMBEDDING_STRIDE = 8      // mel frames between windows
        const val EMBEDDING_DIM = 96
        const val MIN_EMBEDDINGS = 16       // classifier input length

        const val ASSET_HEY_WHIZ = "hey_whiz.onnx"
        const val DEFAULT_CLASSIFIER_ASSET = ASSET_HEY_WHIZ

        fun defaultThreshold(): Float = 0.50f
        fun defaultFrames(): Int = 1

        // Rolling window of inference-latency samples for p50/p95/p99.
        // 256 ≈ 80 s of history at default inferenceIntervalFrames cadence (~320 ms).
        private const val LATENCY_WINDOW = 256

        // Silero v6 fixed PCM-frame size (32 ms @ 16 kHz).
        private const val VAD_WINDOW_SAMPLES = 512
    }
}
