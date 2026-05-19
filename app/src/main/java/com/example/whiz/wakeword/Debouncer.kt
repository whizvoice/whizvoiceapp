package com.example.whiz.wakeword

/**
 * Legacy frame-counter detector used as a fallback when no [ScoreSmoother] is supplied
 * to the engine (e.g. enrollment scoring). Fires after [framesRequired] consecutive
 * raw scores above [threshold]. Mainline detection goes through the smoother.
 */
class Debouncer(
    var threshold: Float,
    var framesRequired: Int,
) {
    private var consecutive: Int = 0

    fun update(score: Float, atMs: Long): DetectionEvent? {
        if (score > threshold) {
            consecutive++
            if (consecutive >= framesRequired) {
                consecutive = 0
                return DetectionEvent(timestampMs = atMs, confidence = score)
            }
        } else {
            consecutive = 0
        }
        return null
    }

    fun reset() {
        consecutive = 0
    }
}
