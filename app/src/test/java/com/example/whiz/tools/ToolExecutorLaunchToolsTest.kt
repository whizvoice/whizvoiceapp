package com.example.whiz.tools

import com.example.whiz.tools.ToolExecutor.Companion.toolsForLaunchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `available_tools` array returned by agent_launch_app must always include the
 * generic screen tools. Otherwise the model reads the app-specific list as the full
 * set of things it can do and refuses to operate apps without dedicated tools
 * (e.g. "I can't interact with Google Keep" when get_ui + click work fine).
 */
class ToolExecutorLaunchToolsTest {

    private val draftAndSend = listOf(
        "agent_draft_message" to "Draft a message in the current app's text input",
        "agent_send_message" to "Send the drafted message"
    )

    @Test
    fun `generic screen tools are appended for an app with only draft and send`() {
        val names = toolsForLaunchResult(draftAndSend).map { it.first }
        assertTrue("missing agent_get_ui in $names", "agent_get_ui" in names)
        assertTrue("missing agent_click in $names", "agent_click" in names)
        assertTrue("missing agent_insert_text in $names", "agent_insert_text" in names)
        assertTrue("missing agent_press_back in $names", "agent_press_back" in names)
    }

    @Test
    fun `app-specific tools come first and are preserved`() {
        val result = toolsForLaunchResult(draftAndSend)
        assertEquals(draftAndSend, result.take(draftAndSend.size))
    }

    @Test
    fun `generic tool descriptions say they work in any app`() {
        val generic = toolsForLaunchResult(emptyList())
        assertTrue(generic.isNotEmpty())
        val getUi = generic.first { it.first == "agent_get_ui" }
        assertTrue("get_ui description should say it works in any app: ${getUi.second}",
            getUi.second.contains("any app", ignoreCase = true))
    }

    @Test
    fun `no tool name is duplicated`() {
        val names = toolsForLaunchResult(draftAndSend).map { it.first }
        assertEquals(names.distinct(), names)
    }
}
