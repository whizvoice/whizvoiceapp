package com.example.whiz.wakeword.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateFunnelTest {

    @Test
    fun `fresh funnel reports all zeros`() {
        val s = GateFunnel().snapshot()
        assertEquals(0L, s["skipped"])
        assertEquals(0L, s["inferences"])
        assertEquals(0L, s["vetoed"])
        assertEquals(0L, s["fired"])
        assertEquals(0L, s["accepted"])
        assertEquals(0L, s["rejected"])
        assertEquals(0L, s["ticks"])
    }

    @Test
    fun `each record method increments its own counter`() {
        val funnel = GateFunnel()
        funnel.recordSkip()
        funnel.recordSkip()
        funnel.recordInference()
        funnel.recordVeto()
        funnel.recordFire()
        funnel.recordAccept()
        funnel.recordReject()

        val s = funnel.snapshot()
        assertEquals(2L, s["skipped"])
        assertEquals(1L, s["inferences"])
        assertEquals(1L, s["vetoed"])
        assertEquals(1L, s["fired"])
        assertEquals(1L, s["accepted"])
        assertEquals(1L, s["rejected"])
    }

    @Test
    fun `ticks is skipped plus inferences`() {
        val funnel = GateFunnel()
        repeat(7) { funnel.recordSkip() }
        repeat(3) { funnel.recordInference() }
        assertEquals(10L, funnel.snapshot()["ticks"])
    }

    @Test
    fun `format reports each gate count and gate1-vs-gate2 split as percentages`() {
        val funnel = GateFunnel()
        repeat(90) { funnel.recordSkip() }
        repeat(10) { funnel.recordInference() }
        repeat(2) { funnel.recordVeto() }
        repeat(5) { funnel.recordFire() }
        repeat(4) { funnel.recordAccept() }
        repeat(1) { funnel.recordReject() }

        val text = funnel.format("2026-06-08 12:00:00")

        assertTrue(text.contains("2026-06-08 12:00:00"))
        assertTrue(text.contains("ticks:"))
        assertTrue(text.contains("100"))          // 90 + 10 ticks
        assertTrue(text.contains("gate1 VAD skip"))
        assertTrue(text.contains("gate2 inference"))
        assertTrue(text.contains("gate3 VAD veto"))
        assertTrue(text.contains("gate4 smoother fire"))
        assertTrue(text.contains("gate5 accepted"))
        assertTrue(text.contains("gate5 rejected"))
        assertTrue(text.contains("90.0%"))
        assertTrue(text.contains("10.0%"))
    }

    @Test
    fun `format does not divide by zero on an empty funnel`() {
        assertTrue(GateFunnel().format("t").contains("0.0%"))
    }
}
