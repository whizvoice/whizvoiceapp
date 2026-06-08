package com.example.whiz.integration

import android.media.AudioManager
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.di.AppModule
import com.example.whiz.services.AppLifecycleService
import com.example.whiz.services.AudioFocusManager
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Audio Ducking Test
 *
 * Guards the invariant: while continuous listening is on, Whiz holds ducking focus so other apps
 * (Maps nav, YT Music) lower their volume — even if those apps start and stop playing audio mid-session.
 *
 * Why this exists: ducking has regressed repeatedly. The canonical case is commit 7a13953c
 * ("put audioducking back after speech recognizer refactor") — a recognizer refactor silently dropped
 * the ducking hookup because nothing tested it. The fragile link is the isListening observer at
 * VoiceManager.kt:250-258, which re-requests ducking whenever the mic turns on in continuous mode
 * (the cold-start / wake-word / restart-after-error paths the event-driven call sites miss).
 *
 * These assertions verify the focus-request bookkeeping (AudioFocusManager.isDuckingActive). They do
 * NOT verify the physical ~14dB volume drop of another app — that requires real audio on a physical
 * device (CI runs the emulator with -noaudio) and is covered by an optional screen-agent test, not here.
 * Historically every ducking regression has been "we stopped calling requestDuckingFocus at all,"
 * which the bookkeeping assertions catch. Test mode (enableTestMode) lets the recognizer report
 * isListening without a real SpeechRecognizer, so these run on the -noaudio CI emulator.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AudioDuckingTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "AudioDuckingTest"
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var audioFocusManager: AudioFocusManager

    @Inject
    lateinit var appLifecycleService: AppLifecycleService

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // device setup, authentication, app launch

        device.executeShellCommand("pm grant $packageName android.permission.RECORD_AUDIO")

        // Test mode: the recognizer flips isListening without touching the real SpeechRecognizer
        // (unavailable on the -noaudio CI emulator). Same mechanism TTSQueueingTest relies on.
        speechRecognitionService.enableTestMode()

        // Guarantee foreground so the shouldReRequestDucking policy (VoiceManager.kt:267-271) holds.
        instrumentation.runOnMainSync { appLifecycleService.notifyAppForegrounded() }
    }

    @After
    fun cleanup() {
        Log.d(TAG, "TearDown: AudioDuckingTest")
        instrumentation.runOnMainSync {
            voiceManager.updateContinuousListeningEnabled(false)
            audioFocusManager.abandonDuckingFocus()
        }
        speechRecognitionService.disableTestMode()
    }

    /** Polls AudioFocusManager.isDuckingActive until it reaches [expected] or the timeout elapses. */
    private fun waitForDucking(expected: Boolean, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (audioFocusManager.isDuckingActive.value == expected) return true
            Thread.sleep(50)
        }
        return audioFocusManager.isDuckingActive.value == expected
    }

    private fun waitForListening(expected: Boolean, timeoutMs: Long = 1500L) {
        val start = System.currentTimeMillis()
        while (speechRecognitionService.isListening.value != expected &&
            System.currentTimeMillis() - start < timeoutMs) {
            Thread.sleep(50)
        }
    }

    /**
     * Behavior 1a — request on listen (direct path): ducking is held while continuous listening is
     * on and released when it turns off.
     */
    @Test
    fun ducking_isHeldWhileContinuousListeningOn_andReleasedWhenOff() {
        instrumentation.runOnMainSync { voiceManager.updateContinuousListeningEnabled(true) }
        if (!waitForDucking(true)) {
            failWithScreenshot(
                "ducking_not_active_on_listen",
                "Ducking should be active while continuous listening is on"
            )
        }

        instrumentation.runOnMainSync { voiceManager.updateContinuousListeningEnabled(false) }
        if (!waitForDucking(false)) {
            failWithScreenshot(
                "ducking_not_released",
                "Ducking should be released when continuous listening turns off"
            )
        }
    }

    /**
     * Behavior 1b — request on listen (observer path, the anti-mask regression guard).
     *
     * Simulates ducking being lost mid-session while continuous listening stays on, then drives a
     * mic restart (false->true listening transition — the cold-start / wake-word / restart-after-error
     * shape). Only the isListening observer at VoiceManager.kt:250-258 re-requests ducking on this
     * path; the direct call sites do not run. This test fails if that observer is removed — exactly
     * the 7a13953c regression. A test that only toggled updateContinuousListeningEnabled would pass
     * even with the observer deleted, because that call ducks directly.
     */
    @Test
    fun ducking_isReacquired_whenMicRestartsMidSession() {
        instrumentation.runOnMainSync { voiceManager.updateContinuousListeningEnabled(true) }
        if (!waitForDucking(true)) {
            failWithScreenshot(
                "ducking_not_active_setup",
                "Ducking should be active after enabling continuous listening"
            )
        }

        // Ducking lost mid-session; continuous listening remains on.
        instrumentation.runOnMainSync { audioFocusManager.abandonDuckingFocus() }
        if (!waitForDucking(false)) {
            failWithScreenshot(
                "ducking_not_cleared",
                "Precondition: ducking should be cleared before the mic restarts"
            )
        }

        // Drive a listening false->true transition. The observer must re-acquire ducking.
        instrumentation.runOnMainSync { voiceManager.stopListening() }
        waitForListening(false)
        instrumentation.runOnMainSync { voiceManager.startListening { } }

        if (!waitForDucking(true)) {
            failWithScreenshot(
                "ducking_not_reacquired",
                "Ducking must be re-acquired by the isListening observer when the mic restarts " +
                    "mid-session (VoiceManager.kt:250-258)"
            )
        }
    }

    /**
     * Behavior 2 — re-request on interruption ("app starts and stops in the middle").
     *
     * Another app grabs focus (e.g. a Maps nav prompt) so we lose ducking; while still listening, we
     * must re-request it (AudioFocusManager.attemptDuckingReRequest, ~500ms within the retry window).
     * When the other app returns focus (AUDIOFOCUS_GAIN), ducking must stay active. Pins the retry
     * behavior so future changes to MAX_DUCKING_RETRIES / the giving-up logic surface in CI.
     */
    @Test
    fun ducking_isReRequested_afterTransientFocusLoss_andHeldOnGain() {
        instrumentation.runOnMainSync { voiceManager.updateContinuousListeningEnabled(true) }
        if (!waitForDucking(true)) {
            failWithScreenshot(
                "ducking_not_active_setup",
                "Ducking should be active after enabling continuous listening"
            )
        }

        // Another app takes focus -> ducking dropped synchronously.
        instrumentation.runOnMainSync {
            audioFocusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        }
        if (audioFocusManager.isDuckingActive.value) {
            failWithScreenshot(
                "ducking_not_dropped_on_loss",
                "Ducking should drop on transient focus loss"
            )
        }

        // Still listening -> ducking must be re-requested within the retry window.
        if (!waitForDucking(true, timeoutMs = 4000L)) {
            failWithScreenshot(
                "ducking_not_rerequested",
                "Ducking should be re-requested after a transient focus loss while continuous " +
                    "listening is still on"
            )
        }

        // Other app finishes and returns focus -> ducking stays active.
        instrumentation.runOnMainSync {
            audioFocusManager.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        if (!waitForDucking(true)) {
            failWithScreenshot(
                "ducking_lost_after_gain",
                "Ducking should remain active after regaining audio focus"
            )
        }
    }
}
