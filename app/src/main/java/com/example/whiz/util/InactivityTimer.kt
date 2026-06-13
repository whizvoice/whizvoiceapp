package com.example.whiz.util

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val INACTIVITY_TIMEOUT_MS = 3 * 60 * 1000L

class InactivityTimer(
    private val scope: CoroutineScope,
    private val durationMs: Long,
    private val onTimeout: () -> Unit,
) {
    private val pauseSources = mutableSetOf<String>()
    private var job: Job? = null
    private var startTimeMs: Long = 0L
    private var remainingMs: Long = durationMs
    // Absolute elapsedRealtime() the timer is due to fire while running. Monotonic and
    // keeps advancing through screen-off / deep sleep, unlike the coroutine delay() below
    // (which may be suspended). Used by checkExpired() for a lazy close on wake.
    private var deadlineMs: Long = 0L
    private var cancelled = false
    private var fired = false

    @Synchronized
    fun reset() {
        if (cancelled) return
        job?.cancel()
        pauseSources.clear()
        fired = false
        remainingMs = durationMs
        startTimeMs = SystemClock.elapsedRealtime()
        deadlineMs = startTimeMs + durationMs
        job = scope.launch {
            delay(durationMs)
            fire()
        }
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
                fire()
                return
            }
            startTimeMs = SystemClock.elapsedRealtime()
            deadlineMs = startTimeMs + remainingMs
            val toWait = remainingMs
            job = scope.launch {
                delay(toWait)
                fire()
            }
            Log.d(TAG, "resume(\"$source\"): waiting $toWait ms")
        } else if (removed) {
            Log.d(TAG, "resume(\"$source\"): still paused by $pauseSources")
        }
    }

    /**
     * Lazy close on wake. Fires immediately if the timer is running (no active pause
     * source) and the idle deadline has already elapsed — e.g. the screen was off long
     * enough that the coroutine delay() couldn't fire. Returns true if it fired.
     *
     * No-op while paused (a tool is running, etc.) or before the deadline; in the latter
     * case the existing countdown coroutine keeps running untouched. Callers should invoke
     * this when the surface becomes visible again (e.g. Activity.onResume). See bug #1347.
     */
    @Synchronized
    fun checkExpired(): Boolean {
        if (cancelled || fired) return false
        if (pauseSources.isNotEmpty()) return false
        if (SystemClock.elapsedRealtime() >= deadlineMs) {
            Log.d(TAG, "checkExpired(): deadline passed, firing")
            fire()
            return true
        }
        return false
    }

    @Synchronized
    fun cancel() {
        cancelled = true
        job?.cancel()
        job = null
        pauseSources.clear()
    }

    /**
     * Invokes onTimeout exactly once. Guards against the suspended countdown coroutine
     * resuming after a lazy checkExpired() has already closed (or vice versa).
     */
    @Synchronized
    private fun fire() {
        if (cancelled || fired) return
        fired = true
        job?.cancel()
        job = null
        onTimeout()
    }

    companion object {
        private const val TAG = "InactivityTimer"
    }
}
