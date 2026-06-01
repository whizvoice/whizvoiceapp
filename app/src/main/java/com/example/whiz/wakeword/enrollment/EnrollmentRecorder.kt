package com.example.whiz.wakeword.enrollment

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission

/**
 * Press-and-hold recorder for enrollment clips. Records while [shouldStop]
 * returns false, capped at [MAX_HOLD_SAMPLES] (5 s).
 *
 * The runtime engine requires exactly [CLIP_SAMPLES] (2 s @ 16 kHz mono PCM16),
 * so the captured audio is post-processed:
 *   - longer than 2 s → take the trailing 2 s (the user typically says
 *     "Hey Whiz" right before releasing)
 *   - shorter than 2 s → left-pad with silence to 2 s
 *
 * Uses `VOICE_RECOGNITION` to match the runtime wake-word path. The verifier
 * centroid is computed against the distribution the runtime detector sees;
 * using a different source here would create a mismatch that hurts cosine
 * similarity at fire time. (Runtime switched from VOICE_COMMUNICATION to
 * VOICE_RECOGNITION because VOICE_COMMUNICATION gets muted ~20 s after
 * screen-off when no VOIP session is active.)
 *
 * Caller MUST ensure the mic isn't held by WakeWordService — EnrollmentScreen
 * stops the service in a DisposableEffect.
 */
class EnrollmentRecorder {

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun recordHeldClip(shouldStop: () -> Boolean): ShortArray {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, FORMAT)
        val bufSize = maxOf(minBuf, MAX_HOLD_SAMPLES * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
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
            val growing = ShortArray(MAX_HOLD_SAMPLES)
            var read = 0
            while (read < MAX_HOLD_SAMPLES && !shouldStop()) {
                val toRead = minOf(READ_CHUNK_SAMPLES, MAX_HOLD_SAMPLES - read)
                val n = record.read(growing, read, toRead)
                if (n <= 0) break
                read += n
            }
            val out = ShortArray(CLIP_SAMPLES)
            if (read >= CLIP_SAMPLES) {
                System.arraycopy(growing, read - CLIP_SAMPLES, out, 0, CLIP_SAMPLES)
            } else {
                System.arraycopy(growing, 0, out, CLIP_SAMPLES - read, read)
            }
            out
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CLIP_SAMPLES = 32_000                  // 2 s — engine input size
        const val MAX_HOLD_SAMPLES = 5 * SAMPLE_RATE_HZ  // 5 s safety cap
        private const val READ_CHUNK_SAMPLES = 1_600     // 100 ms read chunks → responsive shouldStop
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
