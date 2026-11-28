package com.example.whiz.services

import com.example.whiz.services.BubbleOverlayService.Companion.shouldCancelInFlightTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #1558: in-flight screen-agent tools were only cancelled when the bubble was
 * drag-dismissed. When the bubble went away because the main app came to the front,
 * the tool kept driving the screen (and backed out of Whiz). Any teardown that the
 * user caused must cancel in-flight tools; teardowns a tool itself requested (close
 * app, open app, permission prompt) must not cancel the tool that asked for them.
 */
class BubbleOverlayServiceCancelTest {

    @Test
    fun `drag dismiss cancels in-flight tools`() {
        assertTrue(shouldCancelInFlightTools(dismissedByUser = true, cancelToolsOnStop = true))
    }

    @Test
    fun `drag dismiss cancels even if a tool had asked for a quiet stop`() {
        assertTrue(shouldCancelInFlightTools(dismissedByUser = true, cancelToolsOnStop = false))
    }

    @Test
    fun `main app coming to the front cancels in-flight tools`() {
        assertTrue(shouldCancelInFlightTools(dismissedByUser = false, cancelToolsOnStop = true))
    }

    @Test
    fun `a stop requested by a running tool does not cancel that tool`() {
        assertFalse(shouldCancelInFlightTools(dismissedByUser = false, cancelToolsOnStop = false))
    }
}
