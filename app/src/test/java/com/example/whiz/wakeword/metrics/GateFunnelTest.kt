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
        assertEquals(0L, s["fired"])
        assertEquals(0L, s["accepted"])
        assertEquals(0L, s["rejected"])
    }

    @Test
    fun `each record method increments its own counter`() {
        val funnel = GateFunnel()
        funnel.recordSkip()
        funnel.recordInference()
        funnel.recordInference()
        funnel.recordFire()
        funnel.recordAccept()
        funnel.recordReject()

        val s = funnel.snapshot()
        assertEquals(1L, s["skipped"])
        assertEquals(2L, s["inferences"])
        assertEquals(1L, s["fired"])
        assertEquals(1L, s["accepted"])
        assertEquals(1L, s["rejected"])
    }

    @Test
    fun `format reports each gate count and the supplied timestamp`() {
        val funnel = GateFunnel()
        repeat(90) { funnel.recordSkip() }
        repeat(10) { funnel.recordInference() }
        repeat(5) { funnel.recordFire() }
        repeat(4) { funnel.recordAccept() }
        repeat(1) { funnel.recordReject() }

        val text = funnel.format("2026-06-09 12:00:00")

        assertTrue(text.contains("2026-06-09 12:00:00"))
        assertTrue(text.contains("energy skip (silence): 90"))
        assertTrue(text.contains("inference (classifier ran): 10"))
        assertTrue(text.contains("smoother fire (>= threshold): 5"))
        assertTrue(text.contains("verifier accepted -> DETECTION: 4"))
        assertTrue(text.contains("verifier rejected: 1"))
    }
}
