package com.example.whiz.wakeword.detection

/**
 * Phase C adaptive threshold controller — OUT OF SCOPE for v1.
 *
 * The wake-word engine references this as a nullable constructor param. Until/unless
 * Phase C is implemented, the engine is always constructed with `adaptiveThreshold = null`
 * and this class is never instantiated. Kept here so the engine's type signatures resolve.
 */
class AdaptiveThresholdController {
    fun onNonFireScore(score: Float) {}
    fun effectiveThreshold(base: Float): Float = base
}
