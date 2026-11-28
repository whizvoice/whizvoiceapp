package com.example.whiz.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polling engine behind every screen agent wait. It has no Android types so the timing
 * rules can be pinned here with virtual time: a fake "root" is just a String.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenWaitTest {

    private fun TestScope.clock(): () -> Long = { testScheduler.currentTime }

    /** Returns the given roots in order, then the last one forever. */
    private fun roots(vararg sequence: String?): () -> String? {
        var i = 0
        return { sequence[minOf(i++, sequence.lastIndex)] }
    }

    @Test
    fun `returns Met as soon as success is true`() = runTest {
        val outcome = pollUntil(
            waitingFor = "x", deadline = null, ceilingMs = 5_000, now = clock(),
            read = roots("ready"), success = { it == "ready" },
        )
        assertTrue(outcome is WaitOutcome.Met)
        assertEquals(0L, (outcome as WaitOutcome.Met).elapsedMs)
    }

    @Test
    fun `keeps polling while loading then meets`() = runTest {
        val outcome = pollUntil(
            waitingFor = "x", deadline = null, ceilingMs = 5_000, now = clock(),
            read = roots("loading", "loading", "ready"),
            stillLoading = { it == "loading" }, success = { it == "ready" },
        )
        assertTrue(outcome is WaitOutcome.Met)
        assertTrue((outcome as WaitOutcome.Met).elapsedMs > 0)
    }

    @Test
    fun `still loading does not outlive the deadline`() = runTest {
        val deadline = ToolDeadline(testScheduler.currentTime + 1_000, clock())
        val outcome = pollUntil(
            waitingFor = "x", deadline = deadline, now = clock(),
            read = roots("loading"), stillLoading = { true }, success = { false },
        )
        assertTrue(outcome is WaitOutcome.TimedOut)
        val t = outcome as WaitOutcome.TimedOut
        assertTrue(t.wasLoading)
        assertTrue("elapsed ${t.elapsedMs}", t.elapsedMs in 1_000..1_500)
    }

    @Test
    fun `ceiling caps the wait below the deadline`() = runTest {
        val deadline = ToolDeadline(testScheduler.currentTime + 10_000, clock())
        val outcome = pollUntil(
            waitingFor = "x", deadline = deadline, ceilingMs = 500, now = clock(),
            read = roots("nope"), success = { false },
        )
        val t = outcome as WaitOutcome.TimedOut
        assertTrue("elapsed ${t.elapsedMs}", t.elapsedMs in 500..1_000)
    }

    @Test
    fun `deadline caps the wait below the ceiling`() = runTest {
        val deadline = ToolDeadline(testScheduler.currentTime + 300, clock())
        val outcome = pollUntil(
            waitingFor = "x", deadline = deadline, ceilingMs = 5_000, now = clock(),
            read = roots("nope"), success = { false },
        )
        val t = outcome as WaitOutcome.TimedOut
        assertTrue("elapsed ${t.elapsedMs}", t.elapsedMs in 300..800)
    }

    @Test
    fun `evaluates once when no time remains`() = runTest {
        val deadline = ToolDeadline(testScheduler.currentTime - 1, clock())
        var reads = 0
        val outcome = pollUntil(
            waitingFor = "x", deadline = deadline, now = clock(),
            read = { reads++; "ready" }, success = { it == "ready" },
        )
        assertTrue(outcome is WaitOutcome.Met)
        assertEquals(1, reads)
    }

    @Test
    fun `null read is treated as not ready`() = runTest {
        val outcome = pollUntil(
            waitingFor = "x", deadline = null, ceilingMs = 5_000, now = clock(),
            read = roots(null, null, "ready"), success = { it == "ready" },
        )
        assertTrue(outcome is WaitOutcome.Met)
    }

    @Test
    fun `uses default ceiling when neither bound is given`() = runTest {
        val outcome = pollUntil(
            waitingFor = "x", deadline = null, now = clock(),
            read = roots("nope"), success = { false },
        )
        val t = outcome as WaitOutcome.TimedOut
        assertTrue("elapsed ${t.elapsedMs}", t.elapsedMs in DEFAULT_CEILING_MS..DEFAULT_CEILING_MS + 500)
    }

    @Test
    fun `log lines name what was waited for`() = runTest {
        val lines = mutableListOf<String>()
        pollUntil(
            waitingFor = "Messages conversation to open", deadline = null, ceilingMs = 100, now = clock(),
            log = { lines.add(it) }, read = roots("nope"), success = { false },
        )
        assertTrue(lines.toString(), lines.any { "conversation to open" in it && "timed out" in it })
    }
}
