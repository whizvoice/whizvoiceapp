package com.example.whiz.services

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use class that opens an AudioRecord mic and pipes raw PCM audio
 * to a ParcelFileDescriptor. The read end of the pipe is passed to
 * RecognizerIntent.EXTRA_AUDIO_SOURCE so the speech recognizer reads from
 * our mic instead of opening its own.
 *
 * Lifecycle: create -> start() -> stop() or cleanup()
 * The pipe is single-use — after stop/cleanup, create a new instance.
 */
class AudioPipeRecorder {

    companion object {
        private const val TAG = "AudioPipeRecorder"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    }

    /** Read end — pass this to RecognizerIntent.EXTRA_AUDIO_SOURCE */
    val readParcel: ParcelFileDescriptor

    private val writeParcel: ParcelFileDescriptor
    private val audioRecord: AudioRecord
    private var echoCanceler: AcousticEchoCanceler? = null

    /** True when AEC is attached and enabled on this recorder. */
    val isAECEnabled: Boolean
        get() = echoCanceler?.enabled == true
    private val isRecording = AtomicBoolean(false)
    private val isStopped = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    init {
        // Create the pipe
        val pipe = ParcelFileDescriptor.createPipe()
        readParcel = pipe[0]
        writeParcel = pipe[1]

        // Calculate buffer size
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            readParcel.close()
            writeParcel.close()
            throw IllegalStateException("AudioRecord.getMinBufferSize failed: $minBufferSize")
        }

        // Use 2x minimum buffer for safety
        val bufferSize = maxOf(minBufferSize * 2, 4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            readParcel.close()
            writeParcel.close()
            throw IllegalStateException("AudioRecord failed to initialize (state=${audioRecord.state})")
        }

        // Attach Acoustic Echo Canceler if available (non-fatal if it fails)
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.also {
                    it.enabled = true
                    Log.d(TAG, "AcousticEchoCanceler attached and enabled (sessionId=${audioRecord.audioSessionId})")
                }
            } else {
                Log.d(TAG, "AcousticEchoCanceler not available on this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach AcousticEchoCanceler (non-fatal): ${e.message}")
            echoCanceler = null
        }

        Log.d(TAG, "AudioPipeRecorder created (bufferSize=$bufferSize)")
    }

    /**
     * Start recording audio and writing to the pipe.
     * Must be called BEFORE the recognizer starts reading from readParcel.
     */
    fun start() {
        if (isStopped.get()) {
            Log.w(TAG, "start() called on already-stopped recorder, ignoring")
            return
        }
        if (!isRecording.compareAndSet(false, true)) {
            Log.w(TAG, "start() called but already recording, ignoring")
            return
        }

        audioRecord.startRecording()
        Log.d(TAG, "AudioRecord started")

        recordingThread = Thread({
            val buffer = ByteArray(4096)
            val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeParcel)

            try {
                while (isRecording.get()) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    when {
                        bytesRead > 0 -> {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                            Log.w(TAG, "AudioRecord ERROR_DEAD_OBJECT, stopping")
                            break
                        }
                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Log.w(TAG, "AudioRecord ERROR_INVALID_OPERATION, stopping")
                            break
                        }
                        bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                            Log.w(TAG, "AudioRecord ERROR_BAD_VALUE, stopping")
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                // Recognizer closed the pipe — normal shutdown
                Log.d(TAG, "Write pipe closed (recognizer done): ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error in recording thread", e)
            } finally {
                try {
                    outputStream.close()
                } catch (_: Exception) {}
                Log.d(TAG, "Recording thread exiting")
            }
        }, "AudioPipeRecorder-thread").also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Stop recording and close the write end of the pipe (signals EOF to recognizer).
     * Safe to call multiple times.
     */
    fun stop() {
        if (!isStopped.compareAndSet(false, true)) {
            return // Already stopped
        }
        isRecording.set(false)

        try {
            audioRecord.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        try {
            echoCanceler?.release()
            echoCanceler = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AcousticEchoCanceler: ${e.message}")
        }
        try {
            audioRecord.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
        }

        try {
            writeParcel.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing write parcel: ${e.message}")
        }

        // Wait for recording thread to finish
        recordingThread?.let { thread ->
            try {
                thread.join(1000)
                if (thread.isAlive) {
                    Log.w(TAG, "Recording thread did not finish within 1s")
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while joining recording thread")
            }
        }
        recordingThread = null

        Log.d(TAG, "AudioPipeRecorder stopped")
    }

    /**
     * Stop recording and close both ends of the pipe.
     * Use when discarding the recorder entirely.
     */
    fun cleanup() {
        stop()
        try {
            readParcel.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing read parcel: ${e.message}")
        }
        Log.d(TAG, "AudioPipeRecorder cleaned up")
    }
}
