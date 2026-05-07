package com.example.whiz.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L

class InactivityTimer(
    private val scope: CoroutineScope,
    private val durationMs: Long,
    private val onTimeout: () -> Unit,
) {
    private val pauseSources = mutableSetOf<String>()
    private var job: Job? = null
    private var startTimeMs: Long = 0L
    private var remainingMs: Long = durationMs
    private var cancelled = false

    @Synchronized
    fun reset() {
        if (cancelled) return
        job?.cancel()
        pauseSources.clear()
        remainingMs = durationMs
        startTimeMs = SystemClock.elapsedRealtime()
        job = scope.launch {
            delay(durationMs)
            onTimeout()
        }
        Log.d(TAG, "reset: $durationMs ms")
    }

    @Synchronized
    fun pause(source: String) {
        if (cancelled) return
        val wasEmpty = pauseSources.isEmpty()
        val added = pauseSources.add(source)
        if (added && wasEmpty) {
            val elapsed = SystemClock.elapsedRealtime() - startTimeMs
            remainingMs = (remainingMs - elapsed).coerceAtLeast(0L)
            job?.cancel()
            job = null
            Log.d(TAG, "pause(\"$source\"): remaining=$remainingMs ms")
        } else if (added) {
            Log.d(TAG, "pause(\"$source\"): added (already paused by ${pauseSources - source})")
        }
    }

    @Synchronized
    fun resume(source: String) {
        if (cancelled) return
        val removed = pauseSources.remove(source)
        if (removed && pauseSources.isEmpty()) {
            if (remainingMs <= 0L) {
                Log.d(TAG, "resume(\"$source\"): remaining=0, firing immediately")
                onTimeout()
                return
            }
            startTimeMs = SystemClock.elapsedRealtime()
            val toWait = remainingMs
            job = scope.launch {
                delay(toWait)
                onTimeout()
            }
            Log.d(TAG, "resume(\"$source\"): waiting $toWait ms")
        } else if (removed) {
            Log.d(TAG, "resume(\"$source\"): still paused by $pauseSources")
        }
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
        pauseSources.clear()
        Log.d(TAG, "cancel")
    }

    companion object {
        private const val TAG = "InactivityTimer"
    }
}
