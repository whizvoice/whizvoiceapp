package com.example.whiz.wakeword.detection

/**
 * Cheap RMS-energy silence gate — the battery-saving replacement for the removed Silero
 * VAD skip. Skips the expensive mel→embedding→classifier inference once the audio has been
 * below [threshold] RMS for [silenceFramesBeforeSkip] consecutive frames.
 *
 * Unlike a learned VAD, energy can't mistake a loud wake word for silence: genuine "Hey Whiz"
 * peaks at ~0.10+ RMS on this device while a quiet room is ~0.005, so a threshold of ~0.02
 * skips an idle room but never a spoken wake word. Not thread-safe; the engine loop is
 * single-threaded.
 */
class EnergyGate(
    private val threshold: Float,
    private val silenceFramesBeforeSkip: Int,
) {
    private var consecutiveSilence = 0

    /** Feed one frame's RMS. Frames >= [threshold] count as speech and reset the silence run. */
    fun onFrame(rms: Float) {
        if (rms >= threshold) consecutiveSilence = 0 else consecutiveSilence++
    }

    fun shouldSkip(): Boolean = consecutiveSilence >= silenceFramesBeforeSkip

    fun reset() {
        consecutiveSilence = 0
    }
}
