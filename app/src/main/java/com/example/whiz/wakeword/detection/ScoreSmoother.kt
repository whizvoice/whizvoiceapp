package com.example.whiz.wakeword.detection

import com.example.whiz.wakeword.metrics.MetricsSource

/**
 * Replaces `consecutiveFramesRequired = N` with a sliding-mean smoother + hysteresis + refractory.
 *
 * Maintains the last [windowSize] raw classifier scores; fires when the mean first crosses
 * [enterThreshold] rising. Re-arms only when the mean drops below `enterThreshold - hysteresis`.
 * After every fire, blocks new fires for [refractoryMs] regardless of score.
 *
 * Not thread-safe — caller (the engine inference loop) is single-threaded.
 */
class ScoreSmoother(
    private val windowSize: Int,
    private var enterThreshold: Float,
    private val hysteresis: Float,
    private val refractoryMs: Int,
) : MetricsSource {

    override val name: String = "wakeword.smoother"

    private val window = ArrayDeque<Float>(windowSize)
    private var armed = true
    private var refractoryUntilMs: Long = Long.MIN_VALUE
    private var lastMean: Float = 0f
    private var firesTotal: Int = 0
    private var refractoryBlocks: Int = 0

    /** Feed a new raw score. Returns true if this score causes a fire. */
    fun onScore(score: Float, atMs: Long): Boolean {
        if (window.size == windowSize) window.removeFirst()
        window.addLast(score)

        if (window.size < windowSize) {
            lastMean = window.average().toFloat()
            return false
        }
        val mean = window.average().toFloat()
        lastMean = mean

        if (atMs < refractoryUntilMs) {
            if (mean >= enterThreshold) refractoryBlocks += 1
            return false
        }

        val exitThreshold = enterThreshold - hysteresis
        if (!armed && mean < exitThreshold) armed = true

        if (armed && mean >= enterThreshold) {
            armed = false
            refractoryUntilMs = atMs + refractoryMs
            firesTotal += 1
            return true
        }
        return false
    }

    fun reset() {
        window.clear()
        armed = true
        refractoryUntilMs = Long.MIN_VALUE
        lastMean = 0f
    }

    @Synchronized
    fun setEnterThreshold(t: Float) {
        this.enterThreshold = t
    }

    override fun snapshot(): Map<String, Any> = mapOf(
        "last_mean" to lastMean,
        "fires_total" to firesTotal,
        "refractory_blocks_count" to refractoryBlocks,
        "armed" to armed,
    )
}
