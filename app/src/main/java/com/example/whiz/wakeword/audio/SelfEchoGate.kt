package com.example.whiz.wakeword.audio

import com.example.whiz.wakeword.metrics.MetricsSource

/**
 * Suspends wake-word scoring (NOT capture) while our own app is playing audio.
 * Tracks playback as a depth counter so overlapping start/stop calls behave correctly.
 * After playback ends, gate remains blocking for [tailMs] to allow the speaker tail
 * to die out before re-enabling scoring.
 */
class SelfEchoGate(
    private val tailMs: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : MetricsSource {

    override val name: String = "wakeword.self_echo"

    private var depth: Int = 0
    private var lastStopMs: Long? = null
    private var blocksCount: Int = 0

    fun onPlaybackStart(atMs: Long = clock()) {
        depth += 1
    }

    fun onPlaybackStop(atMs: Long = clock()) {
        if (depth == 0) return
        depth -= 1
        if (depth == 0) lastStopMs = atMs
    }

    fun isBlocking(atMs: Long = clock()): Boolean {
        val tail = lastStopMs?.let { (atMs - it) < tailMs } ?: false
        val blocking = depth > 0 || tail
        if (blocking) blocksCount += 1
        return blocking
    }

    override fun snapshot(): Map<String, Any> = mapOf(
        "blocks_count" to blocksCount,
        "active" to (depth > 0),
        "tail_ms" to tailMs,
    )
}
