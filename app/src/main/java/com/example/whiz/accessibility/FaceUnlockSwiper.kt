package com.example.whiz.accessibility

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Finishes a face unlock that is stuck at "swipe up to open".
 *
 * On Pixel, face unlock without the "skip lock screen" setting can authenticate the user but
 * leave the keyguard up with an open lock icon; the system still wants a swipe. In that state
 * [isKeyguardLocked] is true but [isDeviceLocked] is false — that pair is how we know face passed.
 *
 * This runs as a WATCHER alongside the system's own keyguard-dismiss request (the request is
 * what triggers a face attempt in the first place — face does not re-run on its own while the
 * lock screen just sits there). Usually face success makes the system finish the unlock itself,
 * and the watcher simply observes the keyguard going away ([Result.ALREADY_UNLOCKED]). If instead
 * the keyguard stays up after the device reports unlocked, the watcher injects the swipe through
 * the accessibility service and confirms the keyguard went away.
 *
 * Pure coroutine logic with injected probes so it can be unit-tested with virtual time; the
 * Android wiring lives in [MainActivity].
 *
 * Nothing here bypasses authentication: the swipe is only sent once the system itself reports
 * the device unlocked. If that never happens within the window we return [Result.FACE_NOT_PASSED]
 * and the system prompt (fingerprint/PIN) remains the way in.
 */
class FaceUnlockSwiper(
    private val isKeyguardLocked: () -> Boolean,
    private val isDeviceLocked: () -> Boolean,
    private val swipeUp: suspend () -> Boolean,
    private val watchWindowMs: Long = DEFAULT_WATCH_WINDOW_MS,
    private val dismissWaitMs: Long = DEFAULT_DISMISS_WAIT_MS,
    private val pollMs: Long = DEFAULT_POLL_MS,
) {
    enum class Result {
        /** Keyguard went away without our swipe (system finished the unlock, or the user did). */
        ALREADY_UNLOCKED,
        /** Face unlock passed, we swiped, and the keyguard is gone. */
        DISMISSED,
        /** Device never reported unlocked within the window; nothing was swiped. */
        FACE_NOT_PASSED,
        /** Face passed but the swipe could not be dispatched or the keyguard stayed up. */
        SWIPE_FAILED,
    }

    suspend fun attempt(): Result {
        // Phase 1: watch for face to pass (device unlocked while keyguard still showing).
        var waited = 0L
        while (true) {
            if (!isKeyguardLocked()) {
                Log.i(TAG, "Keyguard gone after ~${waited}ms without a swipe — nothing to do")
                return Result.ALREADY_UNLOCKED
            }
            if (!isDeviceLocked()) break
            if (waited >= watchWindowMs) {
                Log.i(TAG, "Device still locked after ${watchWindowMs}ms — watcher done, prompt stays up")
                return Result.FACE_NOT_PASSED
            }
            delay(pollMs)
            waited += pollMs
        }
        Log.i(TAG, "Face unlock passed after ~${waited}ms (keyguard up, device unlocked) — swiping to dismiss")

        // Phase 2: a single swipe, then confirm.
        if (!swipeUp()) {
            Log.w(TAG, "Swipe-up gesture could not be dispatched")
            return Result.SWIPE_FAILED
        }
        waited = 0L
        while (waited < dismissWaitMs) {
            if (!isKeyguardLocked()) {
                Log.i(TAG, "Keyguard dismissed by swipe after ~${waited}ms")
                return Result.DISMISSED
            }
            delay(pollMs)
            waited += pollMs
        }
        if (!isKeyguardLocked()) return Result.DISMISSED
        Log.w(TAG, "Keyguard still up ${dismissWaitMs}ms after swipe")
        return Result.SWIPE_FAILED
    }

    companion object {
        private const val TAG = "FaceUnlockSwiper"
        // Matches the unlock-prompt wake lock (65s) so the watcher never outlives the prompt.
        const val DEFAULT_WATCH_WINDOW_MS = 60_000L
        const val DEFAULT_DISMISS_WAIT_MS = 1_500L
        const val DEFAULT_POLL_MS = 100L
    }
}
