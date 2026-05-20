package com.example.whiz.wakeword.enrollment

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.math.sqrt

/**
 * One-shot recorder for enrollment clips. Records exactly [CLIP_SAMPLES]
 * (2 s @ 16 kHz mono 16-bit PCM).
 *
 * Uses `VOICE_COMMUNICATION` to match the runtime wake-word path (HAL AEC). The
 * verifier centroid is computed against the distribution that the runtime
 * detector will see — using `VOICE_RECOGNITION` here would create a mismatch
 * that hurts cosine-similarity quality at fire time.
 *
 * Caller MUST ensure mic is not held by [com.example.whiz.services.WakeWordService] —
 * the wake-word service already pauses when the main speech recognizer is
 * active; enrollment can use the same lock by setting the speech recognizer's
 * isListening flag, or by stopping the service first.
 */
class EnrollmentRecorder {

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun recordOneClip(): ShortArray = recordOneClipWithLevels { /* discard */ }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun recordOneClipWithLevels(onLevel: (Float) -> Unit): ShortArray {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, FORMAT)
        val bufSize = maxOf(minBuf, CLIP_SAMPLES * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_HZ,
            CHANNEL,
            FORMAT,
            bufSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (state=${record.state})"
        }
        return try {
            record.startRecording()
            val out = ShortArray(CLIP_SAMPLES)
            var read = 0
            var nextLevelAt = LEVEL_CHUNK_SAMPLES
            while (read < CLIP_SAMPLES) {
                val n = record.read(out, read, CLIP_SAMPLES - read)
                if (n <= 0) error("AudioRecord.read failed: $n")
                read += n
                while (read >= nextLevelAt) {
                    val from = nextLevelAt - LEVEL_CHUNK_SAMPLES
                    onLevel(rms(out, from, LEVEL_CHUNK_SAMPLES))
                    nextLevelAt += LEVEL_CHUNK_SAMPLES
                }
            }
            out
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    private fun rms(buf: ShortArray, from: Int, len: Int): Float {
        var sumSq = 0.0
        for (i in from until (from + len)) {
            val v = buf[i].toDouble()
            sumSq += v * v
        }
        val mean = sumSq / len
        val r = sqrt(mean).toFloat() / Short.MAX_VALUE.toFloat()
        return r.coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CLIP_SAMPLES = 32_000
        const val LEVEL_CHUNK_SAMPLES = 1_600  // 100 ms @ 16 kHz → ~20 callbacks across 2 s
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
