package com.example.whiz.wakeword.metrics

/**
 * Per-gate pass/block counters for the wake-word detection pipeline, so we can answer
 * "how many candidates passed each gate" — the funnel the per-component [MetricsSource]
 * snapshots were never wired up to expose.
 *
 * Gate order (see WakeWordEngine.feed):
 *   gate1 VAD skip      — silence; inference not run            -> [recordSkip]
 *   gate2 inference     — classifier ran, score produced        -> [recordInference]
 *   gate3 VAD veto-fire — score present but no recent speech     -> [recordVeto]
 *   gate4 smoother fire — score sustained >= enter threshold     -> [recordFire]
 *   gate5 verifier      — CAM++ voice match accept / reject      -> [recordAccept] / [recordReject]
 *
 * Counts are cumulative since construction (i.e. since service start). Not thread-safe;
 * the engine inference loop is single-threaded.
 */
class GateFunnel {
    private var skipped = 0L
    private var inferences = 0L
    private var vetoed = 0L
    private var fired = 0L
    private var accepted = 0L
    private var rejected = 0L

    fun recordSkip() { skipped++ }
    fun recordInference() { inferences++ }
    fun recordVeto() { vetoed++ }
    fun recordFire() { fired++ }
    fun recordAccept() { accepted++ }
    fun recordReject() { rejected++ }

    fun snapshot(): Map<String, Long> = mapOf(
        "skipped" to skipped,
        "inferences" to inferences,
        "vetoed" to vetoed,
        "fired" to fired,
        "accepted" to accepted,
        "rejected" to rejected,
        "ticks" to (skipped + inferences),
    )

    /** Human-readable funnel report; [nowLabel] is a caller-supplied timestamp string. */
    fun format(nowLabel: String): String {
        val ticks = skipped + inferences
        fun pct(n: Long): String =
            if (ticks == 0L) "0.0%" else "%.1f%%".format(n * 100.0 / ticks)
        return buildString {
            appendLine("Wake Word Gate Funnel (since service start, updated $nowLabel)")
            appendLine("=".repeat(50))
            appendLine("ticks:                $ticks")
            appendLine("  gate1 VAD skip:     $skipped  (${pct(skipped)})   <- silence, no inference")
            appendLine("  gate2 inference:    $inferences  (${pct(inferences)})   <- classifier ran")
            appendLine("gate3 VAD veto:       $vetoed   <- score present but no recent speech")
            appendLine("gate4 smoother fire:  $fired   <- score sustained >= threshold")
            appendLine("  gate5 accepted:     $accepted   <- voice match passed -> DETECTION")
            appendLine("  gate5 rejected:     $rejected   <- voice match failed")
        }
    }
}
