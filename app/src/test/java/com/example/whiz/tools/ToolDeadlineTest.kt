package com.example.whiz.tools

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The server sends `budget_ms` (the timeout it will wait with) on every tool request. The
 * device turns it into a deadline, minus a reserve for building and sending the result, and
 * carries it in the coroutine context so every wait inside the tool can draw from it.
 */
class ToolDeadlineTest {
    private var clock = 1_000L
    private val now = { clock }

    @Test
    fun `deadline is budget minus reserve from now`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"budget_ms": 40000}"""), now)
        assertEquals(1_000L + 40_000L - ToolDeadline.RESERVE_MS, d.deadlineAtMs)
        assertEquals(37_000L, d.remainingMs())
    }

    @Test
    fun `falls back to the per-tool default when the message has no budget`() {
        // An old server sends no budget_ms; the default must match what that server waits
        // with for this tool (agent_press_back: 8s), not a generic value that outlives it.
        val d = ToolDeadline.fromRequest(JSONObject("""{"tool": "agent_press_back"}"""), now)
        assertEquals(8_000L - ToolDeadline.RESERVE_MS, d.remainingMs())
    }

    @Test
    fun `unknown tool with no budget falls back to the generic default`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"tool": "agent_not_a_real_tool"}"""), now)
        assertEquals(ToolDeadline.DEFAULT_BUDGET_MS - ToolDeadline.RESERVE_MS, d.remainingMs())
    }

    @Test
    fun `remaining never goes negative`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"budget_ms": 1000}"""), now)
        clock += 10_000
        assertEquals(0L, d.remainingMs())
    }

    @Test
    fun `restart gives a fresh budget from now`() {
        // After a parked tool resumes (phone unlocked), the server grants a fresh 30s; the
        // device deadline must follow, or every wait in the tool starts with nothing left.
        val d = ToolDeadline.fromRequest(JSONObject("""{"budget_ms": 5000}"""), now)
        clock += 60_000
        assertEquals(0L, d.remainingMs())
        d.restart(ToolDeadline.RUNNING_EXTENSION_MS)
        assertEquals(ToolDeadline.RUNNING_EXTENSION_MS - ToolDeadline.RESERVE_MS, d.remainingMs())
    }

    @Test
    fun `is readable from the coroutine context`() = runTest {
        val d = ToolDeadline(5_000L, now)
        withContext(d) { assertSame(d, currentToolDeadline()) }
        assertNull(currentToolDeadline())
    }
}
