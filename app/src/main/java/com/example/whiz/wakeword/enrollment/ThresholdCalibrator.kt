package com.example.whiz.wakeword.enrollment

object ThresholdCalibrator {

    // Tuned from the prototype's 2026-05-11 Pixel smoke. Enrollment-time scores
    // (clean-room, close to mic) run ~0.97; real-world triggers fire ~0.50 — a
    // 0.42 gap. The percentile math doesn't generalize across that gap, so we
    // treat enrollment as a *small* per-user adjustment, not the source of
    // truth: a wide buffer + hard ceiling keeps the threshold in a reachable
    // band even when enrollment scores are pathologically inflated.
    const val GLOBAL_DEFAULT = 0.50f
    const val GLOBAL_FLOOR = 0.40f
    const val GLOBAL_CEILING = 0.80f
    const val SAFETY_BUFFER = 0.20f

    /**
     * Per-user wake-word threshold from enrollment scores.
     *
     * - Empty list → [GLOBAL_DEFAULT].
     * - Otherwise: 20th-percentile of scores, minus [SAFETY_BUFFER], clamped to
     *   [GLOBAL_FLOOR]..[GLOBAL_CEILING].
     */
    fun compute(scores: List<Float>): Float {
        if (scores.isEmpty()) return GLOBAL_DEFAULT
        val sorted = scores.sorted()
        // Nearest-rank 20th percentile (no interpolation): idx = ceil(0.20 * n) - 1.
        val idx = (Math.ceil(0.20 * sorted.size).toInt() - 1).coerceAtLeast(0)
        val p20 = sorted[idx]
        val raw = p20 - SAFETY_BUFFER
        return raw.coerceIn(GLOBAL_FLOOR, GLOBAL_CEILING)
    }
}
