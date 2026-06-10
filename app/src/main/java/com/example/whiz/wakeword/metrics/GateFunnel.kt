package com.example.whiz.wakeword.metrics

/**
 * Per-gate pass counters for the wake-word detection pipeline, so we can answer
 * "how many candidates passed each gate."
 *
 * Gate order (see WakeWordEngine.feed):
 *   inference  — classifier ran, score produced            -> [recordInference]
 *   smoother   — score sustained >= enter threshold (fire)  -> [recordFire]
 *   verifier   — CAM++ voice match accept / reject          -> [recordAccept] / [recordReject]
 *
 * Counts are cumulative since construction (i.e. since service start). Not thread-safe;
 * the engine inference loop is single-threaded.
 */
class GateFunnel {
    private var inferences = 0L
    private var fired = 0L
    private var accepted = 0L
    private var rejected = 0L

    fun recordInference() { inferences++ }
    fun recordFire() { fired++ }
    fun recordAccept() { accepted++ }
    fun recordReject() { rejected++ }

    fun snapshot(): Map<String, Long> = mapOf(
        "inferences" to inferences,
        "fired" to fired,
        "accepted" to accepted,
        "rejected" to rejected,
    )

    /** Human-readable funnel report; [nowLabel] is a caller-supplied timestamp string. */
    fun format(nowLabel: String): String = buildString {
        appendLine("Wake Word Gate Funnel (since service start, updated $nowLabel)")
        appendLine("=".repeat(50))
        appendLine("inference (classifier ran): $inferences")
        appendLine("smoother fire (>= threshold): $fired")
        appendLine("  verifier accepted -> DETECTION: $accepted")
        appendLine("  verifier rejected: $rejected")
    }
}
