package com.example.whiz.wakeword.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.whiz.wakeword.metrics.MetricsSource
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Stage-2 speaker verifier. Given a 2 s PCM candidate and a set of enrolled
 * speaker embeddings, returns the max-cosine similarity and a fire/suppress
 * verdict.
 *
 * Two gating modes:
 *   - enforceGate = true:  sync-gate. Candidate fires only if score >= threshold.
 *   - enforceGate = false: journal-only. Always returns gatePassed = true.
 */
class SpeakerVerifier(
    private val embedder: Embedder,
    private val store: EmbeddingStore,
    private val threshold: Float,
    private val enforceGate: Boolean,
) : MetricsSource {

    override val name: String = "wakeword.verifier"

    interface Embedder {
        fun embed(pcm: ShortArray): FloatArray
    }

    interface EmbeddingStore {
        fun all(): List<FloatArray>
        fun appendIfBelowCap(embedding: FloatArray): Boolean
        fun count(): Int
    }

    data class Verdict(
        val score: Float?,
        val decision: String,
        val gatePassed: Boolean,
    )

    private var lastScore: Float = 0f
    private var acceptedCount: Int = 0
    private var rejectedCount: Int = 0
    private var noEnrollmentCount: Int = 0
    private var loggedCount: Int = 0

    fun verify(pcm: ShortArray): Verdict {
        val enrolled = store.all()
        if (enrolled.isEmpty()) {
            noEnrollmentCount += 1
            return Verdict(score = null, decision = "no_enrollment", gatePassed = !enforceGate)
        }

        val raw = embedder.embed(pcm)
        val test = l2Normalize(raw)

        var maxCos = Float.NEGATIVE_INFINITY
        for (e in enrolled) {
            val cos = dot(test, e)
            if (cos > maxCos) maxCos = cos
        }
        lastScore = maxCos

        val gatePassed: Boolean
        val decision: String
        if (!enforceGate) {
            gatePassed = true
            decision = "logged"
            loggedCount += 1
        } else if (maxCos >= threshold) {
            gatePassed = true
            decision = "accepted"
            acceptedCount += 1
        } else {
            gatePassed = false
            decision = "rejected"
            rejectedCount += 1
        }
        return Verdict(score = maxCos, decision = decision, gatePassed = gatePassed)
    }

    override fun snapshot(): Map<String, Any> = mapOf(
        "last_score" to lastScore,
        "accepted_count" to acceptedCount,
        "rejected_count" to rejectedCount,
        "logged_count" to loggedCount,
        "no_enrollment_count" to noEnrollmentCount,
        "enrolled_count" to store.count(),
        "enforce_gate" to enforceGate,
        "threshold" to threshold,
    )

    companion object {
        fun l2Normalize(v: FloatArray): FloatArray {
            var sumSq = 0.0
            for (x in v) sumSq += x.toDouble() * x.toDouble()
            val n = sqrt(sumSq).toFloat()
            if (n < 1e-8f) return v.copyOf()
            val out = FloatArray(v.size)
            for (i in v.indices) out[i] = v[i] / n
            return out
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            require(a.size == b.size) { "dot: dimension mismatch ${a.size} vs ${b.size}" }
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}

/**
 * Kaldi-compatible 80-bin log-Mel fbank computed in pure Kotlin to match the
 * 3D-Speaker CAM++ pre-processing recipe.
 *
 * Parameters mirror torchaudio.compliance.kaldi.fbank defaults used by
 * 3D-Speaker CAM++: 25/10 ms frames, Povey window, preemphasis 0.97,
 * remove_dc_offset, 80 mel bins, log of power spectrum, then per-utterance
 * cepstral mean normalization (CMN).
 */
object KaldiFbank {
    private const val SAMPLE_RATE = 16_000
    private const val FRAME_LENGTH = 400        // 25 ms at 16 kHz
    private const val FRAME_SHIFT = 160         // 10 ms at 16 kHz
    private const val N_FFT = 512               // next pow2 >= 400
    private const val NUM_MEL_BINS = 80
    private const val LOW_FREQ = 20.0f
    private const val HIGH_FREQ = (SAMPLE_RATE / 2).toFloat()
    private const val PREEMPH = 0.97f
    private const val LOG_EPS = 1e-10f

    /** Povey window: ((0.5 - 0.5*cos(2*pi*n/(N-1)))^0.85). */
    private val window: FloatArray = FloatArray(FRAME_LENGTH).also { w ->
        for (n in 0 until FRAME_LENGTH) {
            val v = 0.5 - 0.5 * cos(2.0 * PI * n / (FRAME_LENGTH - 1))
            w[n] = v.pow(0.85).toFloat()
        }
    }

    /** Mel filterbank: 80 triangular filters over the FFT power spectrum (n_fft/2+1 bins). */
    private val melFilters: Array<FloatArray> = buildMelFilters()

    private fun buildMelFilters(): Array<FloatArray> {
        val nBins = N_FFT / 2 + 1
        fun hzToMel(hz: Float): Float = 1127.0f * ln(1.0f + hz / 700.0f)
        fun melToHz(mel: Float): Float = 700.0f * (exp(mel / 1127.0f) - 1.0f)
        val melLow = hzToMel(LOW_FREQ)
        val melHigh = hzToMel(HIGH_FREQ)
        val melPoints = FloatArray(NUM_MEL_BINS + 2) { i ->
            melLow + (melHigh - melLow) * i / (NUM_MEL_BINS + 1)
        }
        val hzPoints = FloatArray(NUM_MEL_BINS + 2) { melToHz(melPoints[it]) }
        val binFreqs = FloatArray(nBins) { it * SAMPLE_RATE.toFloat() / N_FFT }
        val filters = Array(NUM_MEL_BINS) { FloatArray(nBins) }
        for (m in 0 until NUM_MEL_BINS) {
            val left = hzPoints[m]
            val center = hzPoints[m + 1]
            val right = hzPoints[m + 2]
            for (k in 0 until nBins) {
                val f = binFreqs[k]
                val w = when {
                    f < left || f > right -> 0.0f
                    f <= center -> (f - left) / (center - left)
                    else -> (right - f) / (right - center)
                }
                filters[m][k] = w
            }
        }
        return filters
    }

    /** Compute fbank features. Returns flat row-major [T*80]; T is also returned. */
    fun compute(pcm: ShortArray): Pair<FloatArray, Int> {
        if (pcm.size < FRAME_LENGTH) return FloatArray(0) to 0
        val nFrames = 1 + (pcm.size - FRAME_LENGTH) / FRAME_SHIFT
        val out = FloatArray(nFrames * NUM_MEL_BINS)
        val frame = FloatArray(N_FFT)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (t in 0 until nFrames) {
            val start = t * FRAME_SHIFT
            // 1) PCM → float with Kaldi pre-emphasis x[n] = s[n] - 0.97 * s[n-1].
            //    Kaldi treats the sample before the frame as s[-1].
            val prev = if (start == 0) pcm[0].toFloat() else pcm[start - 1].toFloat()
            for (n in 0 until FRAME_LENGTH) {
                val s = pcm[start + n].toFloat()
                val prevS = if (n == 0) prev else pcm[start + n - 1].toFloat()
                frame[n] = s - PREEMPH * prevS
            }
            // 2) DC removal on the frame.
            var mean = 0.0
            for (n in 0 until FRAME_LENGTH) mean += frame[n]
            val m = (mean / FRAME_LENGTH).toFloat()
            for (n in 0 until FRAME_LENGTH) frame[n] -= m
            // 3) Povey window.
            for (n in 0 until FRAME_LENGTH) frame[n] *= window[n]
            // 4) Zero-pad to N_FFT.
            for (n in FRAME_LENGTH until N_FFT) frame[n] = 0f
            // 5) FFT (radix-2 in-place Cooley-Tukey).
            for (n in 0 until N_FFT) { re[n] = frame[n]; im[n] = 0f }
            fftRadix2InPlace(re, im)
            // 6) Power spectrum → mel filter → log.
            val nBins = N_FFT / 2 + 1
            val power = FloatArray(nBins)
            for (k in 0 until nBins) power[k] = re[k] * re[k] + im[k] * im[k]
            val rowOff = t * NUM_MEL_BINS
            for (b in 0 until NUM_MEL_BINS) {
                var s = 0f
                val filt = melFilters[b]
                for (k in 0 until nBins) s += filt[k] * power[k]
                out[rowOff + b] = ln(max(s, LOG_EPS))
            }
        }
        // 7) Cepstral Mean Normalization: subtract per-bin mean over time.
        val binMean = FloatArray(NUM_MEL_BINS)
        for (t in 0 until nFrames) {
            val off = t * NUM_MEL_BINS
            for (b in 0 until NUM_MEL_BINS) binMean[b] += out[off + b]
        }
        for (b in 0 until NUM_MEL_BINS) binMean[b] /= nFrames
        for (t in 0 until nFrames) {
            val off = t * NUM_MEL_BINS
            for (b in 0 until NUM_MEL_BINS) out[off + b] -= binMean[b]
        }
        return out to nFrames
    }

    /** Iterative radix-2 Cooley-Tukey FFT. n must be a power of two. */
    private fun fftRadix2InPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two, got $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val theta = -2.0 * PI / size
            val wpr = cos(theta).toFloat()
            val wpi = kotlin.math.sin(theta).toFloat()
            var k = 0
            while (k < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (l in 0 until half) {
                    val a = k + l
                    val b = a + half
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val wrNext = wr * wpr - wi * wpi
                    wi = wr * wpi + wi * wpr
                    wr = wrNext
                }
                k += size
            }
            size = size shl 1
        }
    }
}

/**
 * Production [SpeakerVerifier.Embedder] backed by CAM++ ONNX.
 *
 * IO names + shapes (verified via onnx.load on cam_plus.onnx):
 *   - input  'x'         : float32 [N, T, 80] (Kaldi log-mel fbank, CMN-normalized)
 *   - output 'embedding' : float32 [batch, 192]
 *
 * Pre-processing is computed by [KaldiFbank] in pure Kotlin.
 */
class CamPlusOrtEmbedder(
    context: Context,
    assetName: String = "cam_plus.onnx",
) : SpeakerVerifier.Embedder, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        session = env.createSession(bytes, OrtSession.SessionOptions())
    }

    override fun embed(pcm: ShortArray): FloatArray {
        require(pcm.size == 32_000) {
            "CAM++ embedder expects 2 s of 16 kHz PCM (32 000 samples), got ${pcm.size}"
        }
        val (flat, T) = KaldiFbank.compute(pcm)
        require(T > 0) { "CAM++ fbank produced 0 frames" }
        val shape = longArrayOf(1L, T.toLong(), 80L)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { t ->
            session.run(mapOf(INPUT_NAME to t as OnnxTensor)).use { out ->
                val raw = out[OUTPUT_NAME].get() as OnnxTensor
                val flatOut = raw.floatBuffer
                val emb = FloatArray(EMBEDDING_DIM)
                flatOut.rewind(); flatOut.get(emb)
                return emb
            }
        }
    }

    override fun close() {
        session.close()
        // env is process-wide; do not close.
    }

    companion object {
        const val EMBEDDING_DIM = 192
        private const val INPUT_NAME = "x"
        private const val OUTPUT_NAME = "embedding"
    }
}
