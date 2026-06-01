package com.example.whiz.wakeword.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.whiz.wakeword.metrics.MetricsSource
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Voice-Activity gate sitting in parallel with the wake-word chain.
 *
 * Two roles:
 *  - shouldSkipInference(): skip the expensive embedding/classifier work when
 *    the last [silenceWindowsBeforeSkip] frames were sub-threshold (battery).
 *  - allowsFireAt(): require VAD-positive somewhere in the preceding
 *    [preWindowLookbackMs] before allowing the smoother's fire to propagate (FAR).
 *
 * Window cadence: every call to [onWindow] receives 32 ms of PCM (512 samples @ 16 kHz)
 * — matching Silero v6's fixed frame size.
 *
 * The actual ONNX scorer is injected as [scorer] so the state machine is testable
 * in isolation. Production wires it to a [SileroOrtScorer] (created on the engine thread).
 */
class VadGate(
    private val scorer: Scorer,
    private val speechThreshold: Float,
    private val silenceWindowsBeforeSkip: Int,
    private val preWindowLookbackMs: Long,
) : MetricsSource {

    override val name: String = "wakeword.vad"

    interface Scorer {
        fun score(window: FloatArray): Float
    }

    private var consecutiveSilence: Int = 0
    private var lastSpeechAtMs: Long = Long.MIN_VALUE
    private var lastScore: Float = 0f
    private var skippedInferences: Int = 0
    private val histogram = IntArray(8)

    fun onWindow(window: FloatArray, atMs: Long) {
        val s = scorer.score(window)
        lastScore = s
        histogram[(s * 8).toInt().coerceIn(0, 7)] += 1
        if (s >= speechThreshold) {
            consecutiveSilence = 0
            lastSpeechAtMs = atMs
        } else {
            consecutiveSilence += 1
        }
    }

    fun shouldSkipInference(atMs: Long): Boolean =
        consecutiveSilence >= silenceWindowsBeforeSkip

    /** Called by the engine each tick after consuming the gate's verdict. */
    fun maybeRecordSkip(atMs: Long) {
        if (shouldSkipInference(atMs)) skippedInferences += 1
    }

    fun allowsFireAt(atMs: Long): Boolean {
        // Sentinel guard — (atMs - Long.MIN_VALUE) would overflow positive and
        // accidentally pass the gate in the no-speech-yet state.
        if (lastSpeechAtMs == Long.MIN_VALUE) return false
        return (atMs - lastSpeechAtMs) <= preWindowLookbackMs
    }

    override fun snapshot(): Map<String, Any> = mapOf(
        "last_score" to lastScore,
        "skipped_inferences_count" to skippedInferences,
        "vad_score_histogram" to histogram.copyOf(),
    )
}

/**
 * Production [VadGate.Scorer] backed by Silero v6 ONNX.
 *
 * IO names (verified via onnx.load on silero_vad.onnx):
 *   - input "input"  : float32 [1, 576] at 16 kHz (= 64 carry-over + 512 new = 36 ms)
 *   - input "state"  : float32 [2, 1, 128] hidden state (carried across calls)
 *   - input "sr"     : int64 SCALAR (rank 0)
 *   - output "output": float32 [1, 1] speech probability
 *   - output "stateN": float32 [2, 1, 128] new hidden state
 *
 * Context carry-over: the model expects the LAST 64 samples of the previous call
 * to be prepended to the current 512-sample window. Without this prefix the Conv
 * layers see corrupted boundaries every call and emit ~0 for everything.
 * Initial context is zeros.
 */
class SileroOrtScorer(
    context: Context,
    assetName: String = "silero_vad_v6.onnx",
) : VadGate.Scorer, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var state: FloatArray = FloatArray(2 * 1 * 128)
    internal val contextBuffer = SileroContextBuffer()
    private val sr: LongArray = longArrayOf(16_000L)

    init {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions())
    }

    override fun score(window: FloatArray): Float {
        val combined = contextBuffer.prepareInput(window)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(combined), longArrayOf(1, combined.size.toLong())).use { audio ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { st ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(sr), longArrayOf()).use { srT ->
                    val inputs = mapOf(
                        INPUT_AUDIO to audio as OnnxTensor,
                        INPUT_STATE to st as OnnxTensor,
                        INPUT_SR to srT as OnnxTensor,
                    )
                    session.run(inputs).use { results ->
                        val probOut = results[OUTPUT_PROB].get() as OnnxTensor
                        val newState = results[OUTPUT_STATE].get() as OnnxTensor
                        val prob = (probOut.floatBuffer.array())[0]
                        val sBuf = newState.floatBuffer
                        sBuf.rewind(); sBuf.get(state)
                        contextBuffer.updateContext(window)
                        return prob
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val WINDOW_SAMPLES = 512
        const val CONTEXT_SAMPLES = 64
        private const val INPUT_AUDIO = "input"
        private const val INPUT_STATE = "state"
        private const val INPUT_SR = "sr"
        private const val OUTPUT_PROB = "output"
        private const val OUTPUT_STATE = "stateN"
    }
}

/**
 * Maintains the 64-sample carry-over Silero v6 requires per call. Without this,
 * the ONNX model emits ~0 for any input including real speech. Extracted from
 * [SileroOrtScorer] so the array-rolling can be unit-tested without loading
 * the 2 MB ONNX session.
 */
internal class SileroContextBuffer(
    private val contextSize: Int = SileroOrtScorer.CONTEXT_SAMPLES,
    private val windowSize: Int = SileroOrtScorer.WINDOW_SAMPLES,
) {
    private val combined = FloatArray(contextSize + windowSize)
    private val ctx = FloatArray(contextSize)

    fun prepareInput(window: FloatArray): FloatArray {
        require(window.size == windowSize) { "Expected $windowSize samples per call, got ${window.size}" }
        System.arraycopy(ctx, 0, combined, 0, contextSize)
        System.arraycopy(window, 0, combined, contextSize, windowSize)
        return combined
    }

    fun updateContext(window: FloatArray) {
        System.arraycopy(window, windowSize - contextSize, ctx, 0, contextSize)
    }

    @androidx.annotation.VisibleForTesting
    fun contextSnapshot(): FloatArray = ctx.copyOf()
}
