package com.example.whiz.integration

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.ToolFlowIntegrationTest
import com.example.whiz.di.AppModule
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Instrumented version of run_screen_agent_tests.py::test_media_stream_partial_duck.
 *
 * Whiz lowers STREAM_MUSIC directly while a listening session is active (other apps ignore
 * focus-based ducking) and restores it when the session ends. Opening a chat starts continuous
 * listening (-> duck); returning to My Chats stops it (-> restore). This test reads and sets the
 * stream through AudioManager from the instrumentation process and checks both the live index and
 * the AudioFocusManager log lines, so the assertions are exact. The duck fraction itself is tunable;
 * the test only requires a meaningful duck (<= 60% of the baseline) and a clean restore.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MediaStreamDuckTest : ToolFlowIntegrationTest() {

    companion object {
        private const val TAG = "MediaStreamDuckTest"
        private val DUCK_LOG = Regex("Media stream ducked: STREAM_MUSIC (\\d+) -> (\\d+)")
        private const val RESTORE_LOG = "Media stream restored: STREAM_MUSIC"
    }

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun musicVolume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    private fun audioFocusLog(): String = device.executeShellCommand("logcat -d -s AudioFocusManager:D")

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
    }

    @After
    fun cleanup() {
        stopToolResultCollector()
        runBlocking {
            // The chat opened by the New Chat tap is known by id through the captured view model, so
            // cleanup never has to guess from the account's chat list (which CI also writes to).
            cleanupTrackedChats()
        }
    }

    @Test
    fun testMediaStream_partialDuckWhileListening_andRestore(): Unit = runBlocking {
        // Start at My Chats: continuous listening is off there, so no duck is active yet.
        assertTrue("app did not launch", launchAppAndWaitForLoad())
        if (isCurrentlyInChatScreen()) {
            assertTrue("could not return to the chats list", navigateBackToChatsListFromChat())
        }
        captureChatViewModel() // the chat opened below reports its id here for cleanup

        val original = musicVolume()
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val baseline = max(4, (maxVolume * 0.5).roundToInt())
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, baseline, 0)
            assertTrue("could not set STREAM_MUSIC to $baseline (max $maxVolume)", waitUntil(3_000) { musicVolume() == baseline })
            Log.i(TAG, "STREAM_MUSIC baseline=$baseline (was $original, max $maxVolume)")

            // Open a new chat -> continuous listening on -> requestDuckingFocus() -> media duck.
            device.executeShellCommand("logcat -c")
            // Click the FAB directly rather than clickNewChatButtonAndWaitForChatScreen(): leaving the
            // chats list is enough here, the duck itself is the real signal.
            val newChat = device.wait(Until.findObject(By.pkg(packageName).desc("New Chat")), 5_000)
                ?: failWithScreenshot("'New Chat' button not found on the chats list", "duck_no_new_chat_button")
            newChat.click()
            if (!device.wait(Until.gone(By.pkg(packageName).desc("My Chats Title")), 10_000)) {
                failWithScreenshot("new chat did not open after clicking 'New Chat'", "duck_new_chat_not_opened")
            }
            if (!waitUntil(10_000) { musicVolume() < baseline }) {
                failWithScreenshot("STREAM_MUSIC was not ducked when the listening session started (still ${musicVolume()})", "duck_no_duck")
            }
            var match: MatchResult? = null
            assertTrue("no '$DUCK_LOG' line in AudioFocusManager log", waitUntil(5_000) {
                match = DUCK_LOG.find(audioFocusLog()); match != null
            })
            val (orig, ducked) = match!!.destructured.let { (a, b) -> a.toInt() to b.toInt() }
            assertEquals("duck recorded a different starting volume", baseline, orig)
            assertTrue("expected a meaningful duck (<= 60% of $orig), got $orig -> $ducked", ducked <= (orig * 0.6).roundToInt())
            assertEquals("live STREAM_MUSIC should equal the ducked value", ducked, musicVolume())
            Log.i(TAG, "duck ok: STREAM_MUSIC $orig -> $ducked")

            // Back to My Chats -> continuous listening off -> restore.
            device.executeShellCommand("logcat -c")
            assertTrue("could not return to the chats list", navigateBackToChatsListFromChat())
            assertTrue("STREAM_MUSIC not restored to $baseline (is ${musicVolume()})", waitUntil(10_000) { musicVolume() == baseline })
            assertTrue("no '$RESTORE_LOG' line in AudioFocusManager log", waitUntil(5_000) { audioFocusLog().contains(RESTORE_LOG) })
            Log.i(TAG, "restore ok: STREAM_MUSIC -> $baseline")
        } finally {
            // Put the user's volume back whatever happened. If a failure left the chat open the duck
            // is still active and the app would re-duck a direct write, so end the session first
            // (back to the chats list -> restore), then write the original and confirm it stuck past
            // the app's volume-settle debounce. Logged, not asserted, so the real failure surfaces.
            try {
                if (isCurrentlyInChatScreen()) {
                    navigateBackToChatsListFromChat()
                    waitUntil(10_000) { !isCurrentlyInChatScreen() && musicVolume() >= baseline }
                }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                val restored = waitUntil(5_000) { musicVolume() == original } && run { delay(2_500); musicVolume() == original }
                if (!restored) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
                    Log.w(TAG, "media volume did not settle at $original (is ${musicVolume()}); wrote it again")
                } else {
                    Log.i(TAG, "restored user's media volume to $original")
                }
            } catch (e: Exception) {
                Log.w(TAG, "restoring media volume failed", e)
            }
        }
    }
}
