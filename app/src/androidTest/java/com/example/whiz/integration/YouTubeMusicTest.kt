package com.example.whiz.integration

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.example.whiz.MediaSessionState
import com.example.whiz.ToolFlowIntegrationTest
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.di.AppModule
import com.example.whiz.parseMediaSession
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Screen agent YouTube Music test.
 *
 * Play a song, queue a second one, switch to a 90s pop playlist, play a podcast, then stop. The
 * media session (`dumpsys media_session`) is the source of truth for the current track and
 * play/pause state; the queue order and playlist page have no media-session equivalent and are
 * checked on screen in YouTube Music by text.
 *
 * "Loaded" accepts playing OR paused for the play steps: the voice assistant fights YouTube Music
 * for the mic, so a song that is loaded but paused counts, as it does in the Python test. Only the
 * final "Stop the music" step requires paused/stopped.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class YouTubeMusicTest : ToolFlowIntegrationTest() {

    companion object {
        private const val TAG = "YouTubeMusicTest"
        private const val YTM_PKG = "com.google.android.apps.youtube.music"

        private val PLAY_TOOLS = setOf("agent_play_youtube_music")
        private val QUEUE_TOOLS = setOf("agent_queue_youtube_music")
        private val PLAY_OR_QUEUE = PLAY_TOOLS + QUEUE_TOOLS
        private val PAUSE_TOOLS = setOf("agent_pause_youtube_music")

        private const val TOOL_TIMEOUT_MS = 60_000L
        private const val MEDIA_TIMEOUT_MS = 30_000L

        // Queue UI selectors, captured from a live `uiautomator dump` on 2026-11-12:
        //  - collapsed mini-player:   mini_player (title in mini_player_title); tapping it opens the full player
        //  - full player queue sheet: bottom_sheet_drag_handle (desc "Drag to move"; reports clickable=false
        //                             but a tap on it expands the sheet) and a "Your Queue" tab by description
        //  - expanded queue:          queue_list (RecyclerView) with rows of title + byline
        //  - a "Try Music Premium" mealbar with a "No thanks" button can overlap the sheet; dismiss it first
        private const val MINI_PLAYER_RES = "$YTM_PKG:id/mini_player"
        private const val DRAG_HANDLE_RES = "$YTM_PKG:id/bottom_sheet_drag_handle"
        private const val QUEUE_LIST_RES = "$YTM_PKG:id/queue_list"
        private const val ROW_TITLE_RES = "$YTM_PKG:id/title"
        // Whole word "pop" so the queue's "Popular" chip cannot satisfy the playlist check.
        private val NINETIES_OR_POP: Pattern = Pattern.compile("(?i).*(90s|\\bpop\\b).*")
    }

    @get:Rule(order = 3)
    val skipOnCI = SkipOnCIOrEmulatorRule()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        startToolResultCollector()
        device.executeShellCommand("am force-stop $YTM_PKG")
    }

    @After
    fun cleanup() {
        stopToolResultCollector()
        try {
            device.executeShellCommand("input keyevent 127") // KEYCODE_MEDIA_PAUSE, in case it is still playing
        } catch (_: Exception) {
        }
        runBlocking {
            stopBubbleOverlayIfActive()
            cleanupTrackedChats()
        }
        try {
            device.executeShellCommand("am force-stop $YTM_PKG")
        } catch (_: Exception) {
        }
    }

    private fun mediaSession(): MediaSessionState? =
        parseMediaSession(device.executeShellCommand("dumpsys media_session"), YTM_PKG)

    /** Polls the media session until [predicate] holds; fails with a screenshot and the last state otherwise. */
    private suspend fun awaitMediaSession(what: String, tag: String, predicate: (MediaSessionState) -> Boolean): MediaSessionState {
        val deadline = System.currentTimeMillis() + MEDIA_TIMEOUT_MS
        var last: MediaSessionState? = null
        while (System.currentTimeMillis() < deadline) {
            last = mediaSession()
            if (last != null && predicate(last)) return last
            delay(500)
        }
        failWithScreenshot("media session never reached '$what' within ${MEDIA_TIMEOUT_MS}ms; last=$last", tag)
    }

    private fun MediaSessionState.loaded() = isPlaying || state == 2

    /** YouTube Music titles use the curly apostrophe ("How It’s Done"); compare with a straight one. */
    private fun straightQuotes(s: String) = s.replace('’', '\'').replace('‘', '\'')

    /** Opens the queue sheet: mini-player -> full player -> drag handle tap; waits for the queue list. */
    private fun openQueue() {
        device.findObject(By.pkg(YTM_PKG).text("No thanks"))?.click()
        // The player may be collapsed (mini-player) or already expanded (drag handle visible); it can
        // also still be animating after the queue action, so wait for either before deciding. A
        // system permission prompt from YouTube Music (nearby devices, 2026-11-17) hides the whole
        // player behind another window; deny it as soon as it shows up.
        val handleSel = By.pkg(YTM_PKG).res(DRAG_HANDLE_RES)
        val miniSel = By.pkg(YTM_PKG).res(MINI_PLAYER_RES)
        if (!waitUntilBlocking(10_000) { dismissPermissionPromptIfShown(); device.hasObject(handleSel) || device.hasObject(miniSel) }) {
            failWithScreenshot("YouTube Music player not found (neither mini-player nor expanded player)", "ytm_no_player")
        }
        if (!device.hasObject(handleSel)) {
            device.findObject(miniSel)?.click()
        }
        if (!device.wait(Until.hasObject(By.pkg(YTM_PKG).res(DRAG_HANDLE_RES)), 10_000)) {
            failWithScreenshot("YouTube Music queue drag handle not found after opening the player", "ytm_no_drag_handle")
        }
        // Dismissing the mealbar re-lays-out the sheet, so only look the handle up after it is gone.
        device.findObject(By.pkg(YTM_PKG).text("No thanks"))?.click()
        device.wait(Until.gone(By.pkg(YTM_PKG).text("No thanks")), 3_000)
        // An expanded "Sponsored" ad panel covers the lower half of the player, including the drag
        // handle, which stays in the tree underneath it: the tap then lands on the ad. Close it first.
        closeAdPanelIfShown()
        val queueSel = By.pkg(YTM_PKG).res(QUEUE_LIST_RES)
        repeat(2) { attempt ->
            val handle = device.wait(Until.findObject(By.pkg(YTM_PKG).res(DRAG_HANDLE_RES)), 5_000)
                ?: failWithScreenshot("YouTube Music queue drag handle disappeared", "ytm_no_drag_handle")
            handle.click()
            if (device.wait(Until.hasObject(queueSel), 10_000)) return
            Log.w(TAG, "queue did not open on attempt ${attempt + 1}; closing any ad panel and retrying")
            closeAdPanelIfShown()
        }
        failWithScreenshot("YouTube Music queue list did not open after tapping the drag handle", "ytm_queue_not_opened")
    }

    /** Collapses YouTube Music's in-player ad panel when it is expanded (desc "Close ad panel"). */
    private fun closeAdPanelIfShown() {
        val close = By.pkg(YTM_PKG).desc("Close ad panel")
        device.findObject(close)?.let {
            it.click()
            device.wait(Until.gone(close), 3_000)
        }
    }

    @Test
    fun testYouTubeMusic_play_queue_switch_podcast_pause(): Unit = runBlocking {
        assertTrue(
            "Real accessibility service must be connected for the screen agent to drive YouTube Music",
            WhizAccessibilityService.isServiceConnected()
        )
        voiceManager.setVoiceResponseEnabled(false)
        val chatViewModel = launchVoiceModeAndCaptureViewModel()

        // ---- Step 1: play Golden; media session shows it loaded, YouTube Music in front ----
        val mark1 = toolResults.size
        sendVoice(chatViewModel, "Play Golden from Kpop Demon Hunters on YouTube Music.")
        val play = awaitSuccess(PLAY_TOOLS, mark1, TOOL_TIMEOUT_MS, "ytm_play_golden")
        Log.i(TAG, "play result: ${play.result}")
        val golden = awaitMediaSession("Golden loaded", "ytm_golden_not_loaded") { it.title.contains("Golden", true) && it.loaded() }
        assertTrue("YouTube Music should be in the foreground", device.wait(Until.hasObject(By.pkg(YTM_PKG).depth(0)), 10_000))
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 1 ok: $golden")

        // ---- Step 2: queue How It's Done; on screen Golden is above How It's Done in the queue ----
        val mark2 = toolResults.size
        sendVoice(chatViewModel, "Queue How its Done by HUNTRIX on YouTube Music")
        // Known intermittent app bug (PR #69, autofix/ytmusic_no_action_menu): the queue action sometimes
        // cannot open the "Action menu" on YouTube Music's Nov 2026 search rows and reports
        // success=false, which fails here. That is the intended report, not a test problem.
        val queue = awaitSuccess(QUEUE_TOOLS, mark2, TOOL_TIMEOUT_MS, "ytm_queue")
        Log.i(TAG, "queue result: ${queue.result}")
        openQueue()
        // Re-read the rows on every poll (the sheet animates and rebinds). "Queue next" puts the song
        // directly under the current one; autoplay from the same soundtrack usually does not, so
        // require adjacency, not just order.
        // The list rebinds while it animates, so the UiObject2 can go stale between the lookup and the
        // read; treat that poll as "no rows yet" rather than aborting the test without a screenshot.
        fun queueTitles(): List<String> = try {
            (device.findObject(By.pkg(YTM_PKG).res(QUEUE_LIST_RES))
                ?.findObjects(By.res(ROW_TITLE_RES)) ?: emptyList())
                .sortedBy { it.visibleBounds.top }
                .mapNotNull { it.text }
        } catch (_: StaleObjectException) {
            emptyList()
        }
        fun queuedNext(titles: List<String>): Boolean {
            val i = titles.indexOfFirst { it.contains("Golden", true) }
            return i >= 0 && titles.getOrNull(i + 1)?.let { straightQuotes(it) }?.contains("How It's Done", true) == true
        }
        if (!waitUntil(10_000) { queuedNext(queueTitles()) }) {
            failWithScreenshot("Queue should show How It's Done right after Golden; rows=${queueTitles()}", "ytm_queue_order")
        }
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 2 ok")

        // ---- Step 3: 90s pop instead; media session leaves Golden, a 90s/pop node is on screen ----
        val mark3 = toolResults.size
        sendVoice(chatViewModel, "Actually can you play some 90s pop instead")
        val nineties = awaitSuccess(PLAY_OR_QUEUE, mark3, TOOL_TIMEOUT_MS, "ytm_90s")
        Log.i(TAG, "90s result: ${nineties.result}")
        // Not Golden and not the song we queued behind it: either would also satisfy "changed".
        val changed = awaitMediaSession("a 90s pop track, loaded", "ytm_still_golden") {
            !it.mentions("Golden", "How It's Done", "HUNTR") && it.loaded()
        }
        // The playlist page: prefer the exact title the tool reports it opened (e.g. "The Hits: '90s"),
        // falling back to the Python test's "90s or pop" wording. The full player may cover it.
        val playedTitle = nineties.result.optString("played_title").takeIf { it.isNotBlank() }
        val playlistNode = playedTitle?.let { By.pkg(YTM_PKG).textContains(it) } ?: By.pkg(YTM_PKG).text(NINETIES_OR_POP)
        if (!device.hasObject(playlistNode) && device.hasObject(By.pkg(YTM_PKG).res(DRAG_HANDLE_RES))) device.pressBack()
        if (!waitUntil(10_000) { device.hasObject(playlistNode) }) {
            failWithScreenshot("Playlist '${playedTitle ?: "90s/pop"}' not visible in YouTube Music after switching", "ytm_no_90s_playlist")
        }
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 3 ok: $changed")

        // ---- Step 4: the 99% Invisible podcast ----
        val mark4 = toolResults.size
        sendVoice(chatViewModel, "Play the 99% Invisible podcast")
        val podcast = awaitSuccess(PLAY_TOOLS, mark4, TOOL_TIMEOUT_MS, "ytm_podcast")
        Log.i(TAG, "podcast result: ${podcast.result}")
        val invisible = awaitMediaSession("99% Invisible loaded", "ytm_podcast_not_loaded") {
            it.mentions("99% Invisible", "99 Percent Invisible") && it.loaded()
        }
        awaitTurnComplete(chatViewModel)
        Log.i(TAG, "step 4 ok: $invisible")

        // ---- Step 5: stop; media session paused/stopped with the podcast still loaded ----
        val mark5 = toolResults.size
        sendVoice(chatViewModel, "Stop the music")
        val pause = awaitSuccess(PAUSE_TOOLS, mark5, TOOL_TIMEOUT_MS, "ytm_pause")
        Log.i(TAG, "pause result: ${pause.result}")
        val stopped = awaitMediaSession("paused", "ytm_not_paused") { it.isPausedOrStopped && it.mentions("Invisible") }
        Log.i(TAG, "step 5 ok: $stopped")
    }
}
