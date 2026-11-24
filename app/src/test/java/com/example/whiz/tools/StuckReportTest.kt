package com.example.whiz.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A screen agent tool that fails on an unexpected screen reports a `stuck` block so the model
 * can clear the obstacle with the generic UI tools and retry. These tests pin the block's shape
 * and the rule for when it is attached.
 */
class StuckReportTest {

    private fun failed(error: String? = "Could not find Start button") = JSONObject().apply {
        put("success", false)
        error?.let { put("error", it) }
    }

    @Test
    fun `not attached when the tool succeeded`() {
        assertFalse(StuckReport.shouldAttach("agent_queue_youtube_music", JSONObject().put("success", true), true))
    }

    @Test
    fun `not attached for tools that do not need accessibility`() {
        assertFalse(StuckReport.shouldAttach("agent_dial_phone_number", failed(), false))
    }

    @Test
    fun `not attached for the generic ui tools`() {
        for (t in listOf("agent_get_ui", "agent_click", "agent_insert_text", "agent_press_back", "agent_peek_app")) {
            assertFalse(t, StuckReport.shouldAttach(t, failed(), true))
        }
    }

    @Test
    fun `attached for a failed screen agent tool`() {
        assertTrue(StuckReport.shouldAttach("agent_get_google_maps_directions", failed(), true))
    }

    @Test
    fun `block carries goal, failed step, foreground app, screen and recovery`() {
        val out = StuckReport.attach(
            "agent_queue_youtube_music",
            failed("drag handle not found"),
            "[1] Button \"Don't allow\"\n[2] Button \"Allow\"",
            "com.google.android.apps.youtube.music"
        )
        val stuck = out.getJSONObject("stuck")
        assertEquals("Add the requested song to the YouTube Music queue", stuck.getString("goal"))
        assertEquals("drag handle not found", stuck.getString("failed_step"))
        assertEquals("com.google.android.apps.youtube.music", stuck.getString("foreground_app"))
        assertTrue(stuck.getString("screen").contains("[1] Button \"Don't allow\""))
        assertTrue(stuck.getString("recovery").contains("agent_queue_youtube_music"))
        assertTrue(stuck.getString("recovery").contains("agent_click"))
        assertFalse("original fields must survive", out.optBoolean("success", true))
    }

    @Test
    fun `failed step falls back to unknown and goal to the tool name`() {
        // A tool with no entry in GOALS (e.g. one added later) still gets a usable goal line.
        val stuck = StuckReport.attach("agent_new_tool_without_goal", failed(error = null), null, null).getJSONObject("stuck")
        assertEquals("unknown", stuck.getString("failed_step"))
        assertEquals("Complete agent_new_tool_without_goal", stuck.getString("goal"))
    }

    @Test
    fun `screen and foreground are omitted when unknown`() {
        val stuck = StuckReport.attach("agent_play_youtube_music", failed(), null, null).getJSONObject("stuck")
        assertFalse(stuck.has("screen"))
        assertFalse(stuck.has("foreground_app"))
        assertTrue(stuck.has("recovery"))
    }

    @Test
    fun `screen is truncated at MAX_SCREEN_CHARS keeping the tail where popups live`() {
        // Tree order puts in-window popups and bottom sheets last; a cut must keep that end.
        val head = "[1] Button \"Search\"\n"
        val filler = (2..400).joinToString("\n") { "[$it] TextView \"Result row $it with a long title to pad the dump\"" }
        val tail = "\n[401] Button \"No thanks\""
        val long = head + filler + tail
        assertTrue(long.length > StuckReport.MAX_SCREEN_CHARS)
        val screen = StuckReport.attach("agent_play_youtube_music", failed(), long, null).getJSONObject("stuck").getString("screen")
        assertTrue(screen.length <= StuckReport.MAX_SCREEN_CHARS)
        assertTrue("tail must survive: ${screen.takeLast(80)}", screen.endsWith("[401] Button \"No thanks\""))
        assertTrue("cut must be marked: ${screen.take(80)}", screen.startsWith("… (truncated)"))
    }

    @Test
    fun `recovery says to retry once and not to loop`() {
        val recovery = StuckReport.attach("agent_play_youtube_music", failed(), "[1] Button \"Close\"", "com.google.android.apps.youtube.music")
            .getJSONObject("stuck").getString("recovery")
        assertTrue(recovery, recovery.contains("once", ignoreCase = true))
        assertTrue(recovery, recovery.contains("already retried", ignoreCase = true))
    }

    @Test
    fun `recovery does not point at a screen that is absent`() {
        val recovery = StuckReport.attach("agent_play_youtube_music", failed(), null, null).getJSONObject("stuck").getString("recovery")
        assertFalse(recovery, recovery.contains("'screen'"))
        assertTrue(recovery, recovery.contains("agent_get_ui"))
    }

    @Test
    fun `screen is withheld when Whiz itself owns the foreground`() {
        val stuck = StuckReport.attach(
            "agent_play_youtube_music", failed(), "[1] Button \"New Chat\"", "com.example.whiz.debug",
            selfPackage = "com.example.whiz.debug"
        ).getJSONObject("stuck")
        assertFalse("Whiz's own controls must not be offered as targets", stuck.has("screen"))
        assertEquals("com.example.whiz.debug", stuck.getString("foreground_app"))
    }

    @Test
    fun `not attached for the intent based app launcher`() {
        assertFalse(StuckReport.shouldAttach("agent_launch_app", failed(), true))
    }
}
