package com.example.whiz.wakeword.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyGateTest {

    private fun gate() = EnergyGate(threshold = 0.02f, silenceFramesBeforeSkip = 3)

    @Test
    fun `fresh gate does not skip`() {
        assertFalse(gate().shouldSkip())
    }

    @Test
    fun `skips after enough consecutive sub-threshold frames`() {
        val g = gate()
        g.onFrame(0.001f)
        g.onFrame(0.001f)
        assertFalse(g.shouldSkip())   // only 2 < 3
        g.onFrame(0.001f)
        assertTrue(g.shouldSkip())    // 3rd silent frame -> skip
    }

    @Test
    fun `a single loud frame resets the silence run`() {
        val g = gate()
        repeat(5) { g.onFrame(0.001f) }
        assertTrue(g.shouldSkip())
        g.onFrame(0.2f)               // speech
        assertFalse(g.shouldSkip())   // counter reset -> run inference
    }

    @Test
    fun `frame exactly at threshold counts as speech (not silence)`() {
        val g = gate()
        repeat(5) { g.onFrame(0.02f) }
        assertFalse(g.shouldSkip())
    }

    @Test
    fun `reset clears the silence run`() {
        val g = gate()
        repeat(5) { g.onFrame(0.001f) }
        assertTrue(g.shouldSkip())
        g.reset()
        assertFalse(g.shouldSkip())
    }
}
