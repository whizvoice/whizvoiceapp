package com.example.whiz.services

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ONNX-based wake word classifier.
 *
 * Takes raw PCM audio (16kHz mono 16-bit) and returns a probability score
 * indicating whether the audio contains "hey whiz".
 *
 * Preprocessing pipeline (matches Python librosa):
 *   PCM bytes -> float samples -> STFT -> power spectrum -> mel filterbank -> log scale -> normalize
 *   -> ONNX CNN -> probability
 */
class WakeWordClassifier(context: Context) {

    companion object {
        private const val TAG = "WakeWordClassifier"
        private const val MODEL_FILENAME = "wake_word_classifier.onnx"
        private const val MEL_FB_FILENAME = "mel_filterbank.bin"
        private const val PARAMS_FILENAME = "preprocessing_params.json"
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Preprocessing parameters
    private val sampleRate: Int
    private val nFft: Int
    private val hopLength: Int
    private val nMels: Int
    private val nSamples: Int
    private val nTimeFrames: Int

    // Pre-computed mel filterbank: (nMels, nFft/2+1)
    private val melFilterbank: FloatArray
    // Pre-computed Hann window
    private val hannWindow: FloatArray

    init {
        // Load preprocessing params
        val paramsJson = context.assets.open(PARAMS_FILENAME).use {
            JSONObject(it.readBytes().decodeToString())
        }
        sampleRate = paramsJson.getInt("sample_rate")
        nFft = paramsJson.getInt("n_fft")
        hopLength = paramsJson.getInt("hop_length")
        nMels = paramsJson.getInt("n_mels")
        nSamples = paramsJson.getInt("n_samples")
        nTimeFrames = paramsJson.getInt("n_time_frames")

        // Load mel filterbank
        val fbBytes = context.assets.open(MEL_FB_FILENAME).use { it.readBytes() }
        val fbBuffer = ByteBuffer.wrap(fbBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        melFilterbank = FloatArray(fbBuffer.remaining())
        fbBuffer.get(melFilterbank)

        // Pre-compute Hann window
        hannWindow = FloatArray(nFft) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / nFft))).toFloat()
        }

        // Load ONNX model
        val modelBytes = context.assets.open(MODEL_FILENAME).use { it.readBytes() }
        session = ortEnv.createSession(modelBytes)
        Log.d(TAG, "ONNX classifier loaded: nMels=$nMels, nFft=$nFft, hopLength=$hopLength, nTimeFrames=$nTimeFrames")
    }

    /**
     * Classify raw PCM audio data.
     *
     * @param pcmData Raw 16kHz mono 16-bit PCM bytes (little-endian)
     * @return Probability [0.0, 1.0] that the audio contains "hey whiz"
     */
    fun classify(pcmData: ByteArray): Float {
        return try {
            val audioFloats = pcmBytesToFloats(pcmData)
            val melSpec = computeMelSpectrogram(audioFloats)
            runInference(melSpec)
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            0.0f
        }
    }

    /**
     * Convert 16-bit PCM byte array to normalized float array.
     * Pads or trims to expected length.
     */
    private fun pcmBytesToFloats(pcmData: ByteArray): FloatArray {
        val numSamples = pcmData.size / 2
        val floats = FloatArray(nSamples)

        val samplesToConvert = minOf(numSamples, nSamples)
        for (i in 0 until samplesToConvert) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            floats[i] = sample / 32768.0f
        }

        return floats
    }

    /**
     * Compute log-mel spectrogram matching librosa's output.
     * Returns flattened array in row-major order: (1, nMels, nTimeFrames)
     */
    private fun computeMelSpectrogram(audio: FloatArray): FloatArray {
        val nFftBins = nFft / 2 + 1

        // Pad audio for STFT (center padding like librosa)
        val padLen = nFft / 2
        val padded = FloatArray(audio.size + 2 * padLen)
        // Reflect padding
        for (i in 0 until padLen) {
            padded[padLen - 1 - i] = audio[minOf(i + 1, audio.size - 1)]
        }
        System.arraycopy(audio, 0, padded, padLen, audio.size)
        for (i in 0 until padLen) {
            padded[padLen + audio.size + i] = audio[maxOf(audio.size - 2 - i, 0)]
        }

        // Compute number of frames
        val nFrames = 1 + (padded.size - nFft) / hopLength

        // STFT -> power spectrogram
        val powerSpec = FloatArray(nFftBins * nFrames)
        val fftReal = FloatArray(nFft)
        val fftImag = FloatArray(nFft)

        for (frame in 0 until nFrames) {
            val start = frame * hopLength

            // Apply window and prepare for FFT
            for (i in 0 until nFft) {
                fftReal[i] = if (start + i < padded.size) padded[start + i] * hannWindow[i] else 0f
                fftImag[i] = 0f
            }

            // Simple DFT for the bins we need (nFft/2+1)
            for (k in 0 until nFftBins) {
                var realSum = 0.0
                var imagSum = 0.0
                val freq = -2.0 * PI * k / nFft
                for (n in 0 until nFft) {
                    val angle = freq * n
                    realSum += fftReal[n] * cos(angle)
                    imagSum += fftReal[n] * kotlin.math.sin(angle)
                }
                // Power = real^2 + imag^2
                powerSpec[k * nFrames + frame] = (realSum * realSum + imagSum * imagSum).toFloat()
            }
        }

        // Apply mel filterbank: mel = melFB @ power
        // melFilterbank is (nMels, nFftBins), powerSpec is (nFftBins, nFrames)
        // result is (nMels, nFrames)
        val actualTimeFrames = minOf(nFrames, nTimeFrames)
        val melSpec = FloatArray(nMels * nTimeFrames)

        for (m in 0 until nMels) {
            for (t in 0 until actualTimeFrames) {
                var sum = 0.0f
                for (k in 0 until nFftBins) {
                    sum += melFilterbank[m * nFftBins + k] * powerSpec[k * nFrames + t]
                }
                melSpec[m * nTimeFrames + t] = sum
            }
        }

        // Log scale: power_to_db with ref=max
        var maxVal = Float.MIN_VALUE
        for (v in melSpec) {
            if (v > maxVal) maxVal = v
        }
        val refVal = max(maxVal, 1e-10f)
        for (i in melSpec.indices) {
            val v = max(melSpec[i], 1e-10f)
            melSpec[i] = 10.0f * log10(v / refVal)
        }

        // Normalize to [0, 1]
        var minDb = Float.MAX_VALUE
        var maxDb = Float.MIN_VALUE
        for (v in melSpec) {
            if (v < minDb) minDb = v
            if (v > maxDb) maxDb = v
        }
        val range = maxDb - minDb + 1e-8f
        for (i in melSpec.indices) {
            melSpec[i] = (melSpec[i] - minDb) / range
        }

        return melSpec
    }

    /**
     * Run ONNX inference on mel spectrogram.
     */
    private fun runInference(melSpec: FloatArray): Float {
        // Input shape: (1, 1, nMels, nTimeFrames)
        val inputShape = longArrayOf(1, 1, nMels.toLong(), nTimeFrames.toLong())
        val inputBuffer = FloatBuffer.wrap(melSpec)
        val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)

        val results = session.run(mapOf("mel_spectrogram" to inputTensor))
        val outputTensor = results[0] as OnnxTensor
        val output = outputTensor.floatBuffer.get(0)

        inputTensor.close()
        results.close()

        Log.d(TAG, "Classifier score: $output")
        return output
    }

    fun close() {
        try {
            session.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
    }
}
