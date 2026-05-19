package com.example.whiz.wakeword.detection

/**
 * Stage-2 speaker verifier. STUB in commit 2 — full CAM++ implementation lands in commit 4.
 *
 * The wake-word engine takes this as a nullable constructor param and skips verification
 * entirely when null. Until commit 4 wires the real implementation, the engine is always
 * constructed with `verifier = null`.
 *
 * The Verdict shape is fixed in commit 2 so engine code can reference it.
 */
class SpeakerVerifier {
    data class Verdict(
        val score: Float,
        val gatePassed: Boolean,
        val decision: String,
    )

    fun verify(pcm: ShortArray): Verdict = Verdict(score = 0f, gatePassed = true, decision = "stub")
}
