package com.example.whiz.wakeword.enrollment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class EnrollmentViewModel(
    private val scorer: EnrollmentScorer,
    private val saveClip: (Int, ShortArray) -> Unit,
    private val saveSidecar: (EnrollmentSidecar) -> Unit,
    private val persistThreshold: (Float) -> Unit,
    private val modelHash: () -> String,
    private val phraseDisplayName: () -> String = { "Hey Whiz" },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EnrollmentState>(EnrollmentState.NotEnrolled)
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    private val collectedClips = mutableListOf<Pair<ShortArray, Float>>()
    private var retriesForCurrentClip = 0
    // Highest-scoring failed attempt for the current clip slot. After MAX_RETRIES
    // failures, we silently accept this so the flow always completes all
    // TARGET_CLIPS slots.
    private var bestFailedAttempt: Pair<ShortArray, Float>? = null

    /** Read-only view of accepted clip scores so far — exposed for UI breakdown. */
    val clipScores: List<Float> get() = collectedClips.map { it.second }

    fun start() {
        collectedClips.clear()
        resetCurrentClipState()
        _state.value = EnrollmentState.Recording(clipIndex = 1)
    }

    /** Return to the entry screen, discarding any in-memory progress. */
    fun reset() {
        collectedClips.clear()
        resetCurrentClipState()
        _state.value = EnrollmentState.NotEnrolled
    }

    fun onClipRecorded(pcm: ShortArray) {
        val current = _state.value as? EnrollmentState.Recording ?: return
        scope.launch {
            try {
                val score = scorer.score(pcm)
                if (score >= ACCEPT_THRESHOLD) {
                    saveAndAdvance(current.clipIndex, pcm, score)
                    return@launch
                }

                val prevBest = bestFailedAttempt
                if (prevBest == null || score > prevBest.second) {
                    bestFailedAttempt = pcm to score
                }
                retriesForCurrentClip++

                if (retriesForCurrentClip >= MAX_RETRIES) {
                    // Exhausted: auto-accept the best failed attempt so the user is
                    // never stuck. Threshold calibration reflects the score honestly,
                    // and the "Re-record this clip" button on the next Recording
                    // screen lets them try again if they want.
                    val (bestPcm, bestScore) = bestFailedAttempt!!
                    saveAndAdvance(current.clipIndex, bestPcm, bestScore)
                } else {
                    _state.value = EnrollmentState.RetryClip(
                        clipIndex = current.clipIndex,
                        lastScore = score,
                        retriesLeft = MAX_RETRIES - retriesForCurrentClip,
                    )
                }
            } catch (t: Throwable) {
                _state.value = EnrollmentState.Error("Could not score clip: ${t.message}")
            }
        }
    }

    /** Pop the most recent accepted clip and return to its Recording slot. */
    fun redoPreviousClip() {
        val current = _state.value as? EnrollmentState.Recording ?: return
        if (current.clipIndex <= 1 || collectedClips.isEmpty()) return
        collectedClips.removeAt(collectedClips.lastIndex)
        resetCurrentClipState()
        _state.value = EnrollmentState.Recording(
            clipIndex = current.clipIndex - 1,
            lastAcceptedScore = collectedClips.lastOrNull()?.second,
        )
    }

    fun retryCurrentClip() {
        val retry = _state.value as? EnrollmentState.RetryClip ?: return
        _state.value = EnrollmentState.Recording(clipIndex = retry.clipIndex)
    }

    private fun saveAndAdvance(clipIndex: Int, pcm: ShortArray, score: Float) {
        collectedClips.add(pcm to score)
        try {
            saveClip(clipIndex, pcm)
            if (clipIndex >= TARGET_CLIPS) {
                finalize()
            } else {
                resetCurrentClipState()
                _state.value = EnrollmentState.Recording(
                    clipIndex = clipIndex + 1,
                    lastAcceptedScore = score,
                )
            }
        } catch (t: Throwable) {
            _state.value = EnrollmentState.Error("Could not save clip: ${t.message}")
        }
    }

    private fun resetCurrentClipState() {
        retriesForCurrentClip = 0
        bestFailedAttempt = null
    }

    private fun finalize() {
        val scores = collectedClips.map { it.second }
        val threshold = ThresholdCalibrator.compute(scores)
        val enrolledAt = Instant.ofEpochMilli(clock()).toString()
        val sidecar = EnrollmentSidecar(
            version = 1,
            enrolledAt = enrolledAt,
            phrase = phraseDisplayName(),
            modelHash = modelHash(),
            clips = collectedClips.mapIndexed { idx, (_, score) ->
                ClipMeta(path = "clip-${idx + 1}.pcm", wakeWordScore = score)
            },
            userThreshold = threshold,
        )
        saveSidecar(sidecar)
        persistThreshold(threshold)
        _state.value = EnrollmentState.Complete(threshold = threshold, enrolledAt = enrolledAt)
    }

    companion object {
        const val TARGET_CLIPS = 5
        const val ACCEPT_THRESHOLD = 0.90f
        const val MAX_RETRIES = 3
    }
}
