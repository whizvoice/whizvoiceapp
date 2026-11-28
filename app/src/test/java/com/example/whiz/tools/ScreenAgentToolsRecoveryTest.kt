package com.example.whiz.tools

import com.example.whiz.tools.ScreenAgentTools.Companion.GOOGLE_MAPS_PACKAGE
import com.example.whiz.tools.ScreenAgentTools.Companion.mapsRecoveryMayPressBack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #1558: the Maps directions tool's "unknown screen state" recovery pressed the
 * system Back button up to three times without checking which app was in front. The
 * first press left Maps for the launcher; the next one backed out of Whiz itself.
 * Back may only be pressed while Google Maps owns the foreground window.
 */
class ScreenAgentToolsRecoveryTest {

    @Test
    fun `back press is allowed while Maps is the foreground window`() {
        assertTrue(mapsRecoveryMayPressBack(GOOGLE_MAPS_PACKAGE))
    }

    @Test
    fun `back press is refused once the launcher is in front`() {
        assertFalse(mapsRecoveryMayPressBack("com.google.android.apps.nexuslauncher"))
    }

    @Test
    fun `back press is refused when Whiz itself is in front`() {
        assertFalse(mapsRecoveryMayPressBack("com.example.whiz"))
    }

    @Test
    fun `back press is refused when no window can be read`() {
        assertFalse(mapsRecoveryMayPressBack(null))
    }
}
