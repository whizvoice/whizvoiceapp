package com.example.whiz.tools

import com.example.whiz.tools.ScreenAgentTools.Companion.APP_NAME_TO_PACKAGE
import com.example.whiz.tools.ScreenAgentTools.Companion.calculateMatchScore
import com.example.whiz.tools.ScreenAgentTools.Companion.rankCandidates
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

    // ---- word-overlap tier: "google keep" must resolve to the "Keep Notes" label ----

    private val KEEP = "com.google.android.keep"

    @Test
    fun `google keep matches the Keep Notes label via word overlap`() {
        // Pixel launcher label for com.google.android.keep is "Keep Notes".
        val score = calculateMatchScore("google keep", "Keep Notes", KEEP)
        assertTrue("expected a usable match (>= 0.5) but got $score", score >= 0.5f)
    }

    @Test
    fun `overlap tier scores below an exact match on another app`() {
        val overlap = calculateMatchScore("google keep", "Keep Notes", KEEP)
        val exact = calculateMatchScore("google keep", "Google Keep", KEEP)
        assertTrue("overlap ($overlap) must rank below exact ($exact)", overlap < exact)
    }

    @Test
    fun `the google qualifier in google keep does not match a standalone Google app`() {
        val score = calculateMatchScore("google keep", "Google", "com.google.android.googlequicksearchbox")
        assertTrue("a bare qualifier should not match (got $score)", score < 0.5f)
    }

    @Test
    fun `overlap on a trailing label word alone does not launch`() {
        // "google notes" shares only the non-head word "notes" with "Keep Notes";
        // that is too weak to launch (could equally be Samsung Notes).
        val score = calculateMatchScore("google notes", "Keep Notes", KEEP)
        assertTrue("trailing-word overlap should stay below 0.5 (got $score)", score < 0.5f)
    }

    @Test
    fun `partial overlap is non-zero so it can surface as a candidate`() {
        val score = calculateMatchScore("google notes", "Keep Notes", KEEP)
        assertTrue("partial overlap should be > 0 for candidate ranking (got $score)", score > 0f)
    }

    @Test
    fun `a query made only of brand words is not stripped to nothing`() {
        // "google" must still exact-match the Google app, not become an empty query.
        assertEquals(1.0f, calculateMatchScore("google", "Google", "com.google.android.googlequicksearchbox"))
        assertEquals(0.0f, calculateMatchScore("google", "Spotify", "com.spotify.music"))
    }

    // ---- candidates on failure: rank installed labels so the model can disambiguate ----

    @Test
    fun `rankCandidates returns the closest installed labels best first`() {
        val installed = listOf(
            "Spotify" to "com.spotify.music",
            "Keep Notes" to KEEP,
            "Google" to "com.google.android.googlequicksearchbox",
            "Samsung Notes" to "com.samsung.android.app.notes"
        )
        val ranked = rankCandidates("google notes", installed, limit = 3)
        assertEquals(listOf("Keep Notes", "Samsung Notes"), ranked)
    }

    @Test
    fun `rankCandidates omits apps with zero score and respects the limit`() {
        val installed = listOf(
            "Keep Notes" to KEEP,
            "Samsung Notes" to "com.samsung.android.app.notes",
            "Notes" to "com.example.notes",
            "Spotify" to "com.spotify.music"
        )
        val ranked = rankCandidates("notes", installed, limit = 2)
        assertEquals(2, ranked.size)
        assertTrue(ranked.none { it == "Spotify" })
    }
}
