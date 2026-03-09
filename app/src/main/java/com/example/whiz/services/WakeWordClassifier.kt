package com.example.whiz.services

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * ONNX-based wake word classifier.
 *
 * Takes raw PCM audio (16kHz mono 16-bit) and returns a probability score
 * indicating whether the audio contains "hey whiz". The ONNX model includes
 * mel spectrogram preprocessing, so only raw audio floats are needed.
 */
class WakeWordClassifier(context: Context) {

    companion object {
        private const val TAG = "WakeWordClassifier"
        private const val MODEL_FILENAME = "wake_word_classifier.onnx"
        private const val SAMPLE_RATE = 16000
        private const val DURATION_SECONDS = 3
        private const val EXPECTED_SAMPLES = SAMPLE_RATE * DURATION_SECONDS
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(MODEL_FILENAME).use { it.readBytes() }
        session = ortEnv.createSession(modelBytes)
        Log.d(TAG, "ONNX model loaded: ${modelBytes.size} bytes")
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
            runInference(audioFloats)
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed", e)
            0.0f
        }
    }

    /**
     * Convert 16-bit PCM byte array to normalized float array.
     * Pads or trims to expected length (3 seconds at 16kHz).
     */
    private fun pcmBytesToFloats(pcmData: ByteArray): FloatArray {
        val numSamples = pcmData.size / 2
        val floats = FloatArray(EXPECTED_SAMPLES)

        val samplesToConvert = minOf(numSamples, EXPECTED_SAMPLES)
        for (i in 0 until samplesToConvert) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low  // little-endian 16-bit signed
            floats[i] = sample / 32768.0f
        }
        // Remaining samples stay 0.0 (silence padding)

        return floats
    }

    /**
     * Run ONNX inference on audio float array.
     */
    private fun runInference(audioFloats: FloatArray): Float {
        // Input shape: (1, n_samples)
        val inputShape = longArrayOf(1, audioFloats.size.toLong())
        val inputBuffer = FloatBuffer.wrap(audioFloats)
        val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)

        val results = session.run(mapOf("audio" to inputTensor))
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
