package com.example.whiz.wakeword.metrics

/**
 * Contract for any component that wants its snapshot included in OperationalMetrics.
 *
 * Implementations MUST return a fresh map each call (no shared mutation).
 * Keys MUST follow `wakeword.<component>.<metric>` for stable Health-UI labels.
 */
interface MetricsSource {
    val name: String
    fun snapshot(): Map<String, Any>
}
