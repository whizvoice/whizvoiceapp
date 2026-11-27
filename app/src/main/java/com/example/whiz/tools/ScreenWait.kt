package com.example.whiz.tools

import kotlinx.coroutines.delay

/** Ceiling used when a wait has neither a tool deadline nor an explicit ceiling. */
const val DEFAULT_CEILING_MS = 2_000L

sealed class WaitOutcome {
    data class Met(val elapsedMs: Long) : WaitOutcome()
    /** [wasLoading]: the last read still looked like it was loading when time ran out. */
    data class TimedOut(val elapsedMs: Long, val wasLoading: Boolean) : WaitOutcome()
}

/**
 * The one polling loop behind every screen agent wait.
 *
 * Reads a root with [read], checks [success], and keeps polling while the bound allows. The
 * bound is the smaller of [ceilingMs] and what is left on [deadline]; with neither, it is
 * [DEFAULT_CEILING_MS]. Pass a ceiling only when the predicate can tell "loaded and wrong"
 * from "still loading" and should give up early; pass none to let the tool's budget decide.
 *
 * [stillLoading] does not change the bound. It is recorded in the outcome so the caller can
 * report "the app was still loading" rather than "the element was missing".
 *
 * [waitingFor] is a human-readable description ("Messages conversation to open") and is the
 * subject of every log line, so a logcat full of waits says which one each line was.
 *
 * Polling backs off from [initialDelayMs] doubling to [maxIntervalMs]. The predicate is always
 * evaluated at least once, even when no time remains. A null [read] counts as not ready.
 */
suspend fun <T> pollUntil(
    waitingFor: String,
    deadline: ToolDeadline?,
    ceilingMs: Long? = null,
    initialDelayMs: Long = 50,
    maxIntervalMs: Long = 500,
    now: () -> Long = System::currentTimeMillis,
    log: (String) -> Unit = {},
    read: () -> T?,
    stillLoading: (T) -> Boolean = { false },
    success: (T) -> Boolean,
): WaitOutcome {
    val start = now()
    val bound = listOfNotNull(ceilingMs, deadline?.remainingMs()).minOrNull() ?: DEFAULT_CEILING_MS
    var interval = initialDelayMs
    var lastLooksLoading = false

    while (true) {
        val root = read()
        if (root != null) {
            if (success(root)) {
                val elapsed = now() - start
                log("$waitingFor: met after ${elapsed}ms")
                return WaitOutcome.Met(elapsed)
            }
            lastLooksLoading = stillLoading(root)
        }
        val elapsed = now() - start
        val remaining = bound - elapsed
        if (remaining <= 0) {
            log("$waitingFor: timed out after ${elapsed}ms (bound ${bound}ms, loading=$lastLooksLoading)")
            return WaitOutcome.TimedOut(elapsed, lastLooksLoading)
        }
        delay(minOf(interval, remaining))
        interval = minOf(interval * 2, maxIntervalMs)
    }
}
