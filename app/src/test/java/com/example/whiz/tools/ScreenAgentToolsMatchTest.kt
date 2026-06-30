package com.example.whiz.tools

import com.example.whiz.tools.ScreenAgentTools.Companion.APP_NAME_TO_PACKAGE
import com.example.whiz.tools.ScreenAgentTools.Companion.calculateMatchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for app-name resolution (bug #1435: "could not find an app matching
 * 'Google Maps'" even though Maps is installed — its launcher label is just "Maps").
 */
class ScreenAgentToolsMatchTest {

    private val MAPS = "com.google.android.apps.maps"

    // ---- bug #1435: "google maps" must resolve to Maps ----

    @Test
    fun `google maps matches the Maps label via fuzzy score`() {
        // Device label for com.google.android.apps.maps is "Maps", model emits "Google Maps".
        val score = calculateMatchScore("google maps", "Maps", MAPS)
        assertTrue("expected a usable match (>= 0.5) but got $score", score >= 0.5f)
    }

    @Test
    fun `google maps resolves to the maps package via the dictionary`() {
        assertEquals(MAPS, APP_NAME_TO_PACKAGE["google maps"])
    }

    // ---- guard: a leading qualifier must not match an unrelated app ----

    @Test
    fun `the google qualifier alone does not match a standalone Google app`() {
        // "Google" (the search app) shares the leading word but is not what the user meant.
        val score = calculateMatchScore("google maps", "Google", "com.google.android.googlequicksearchbox")
        assertTrue("a bare qualifier should not match (got $score)", score < 0.5f)
    }

    // ---- characterization: existing behavior must be preserved ----

    @Test
    fun `bare maps still matches the Maps label exactly`() {
        assertEquals(1.0f, calculateMatchScore("maps", "Maps", MAPS))
    }

    @Test
    fun `unrelated app does not match`() {
        assertEquals(0.0f, calculateMatchScore("google maps", "Spotify", "com.spotify.music"))
    }

    @Test
    fun `the two former dictionaries are now one shared map containing maps`() {
        assertEquals(MAPS, APP_NAME_TO_PACKAGE["maps"])
    }
}
