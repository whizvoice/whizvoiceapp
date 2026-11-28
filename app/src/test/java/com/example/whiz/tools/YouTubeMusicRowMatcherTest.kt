package com.example.whiz.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue/play tools must act on a search result row that matches the user's query, not on
 * whatever row happens to be first (a stale results page from the previous search, or the
 * player's own up-next list, both seen live on 2026-11-26 queueing "Golden" twice).
 */
class YouTubeMusicRowMatcherTest {

    @Test
    fun `row for the requested song matches`() {
        assertTrue(YouTubeMusicRowMatcher.matches("How its Done HUNTRIX", "How It's Done", "HUNTRIX, EJAE, AUDREY NUNA & REI AMI"))
    }

    @Test
    fun `a different song by the same artist does not match`() {
        // The stale page for the previous search: same artist, wrong title.
        assertFalse(YouTubeMusicRowMatcher.matches("How its Done HUNTRIX", "Golden", "HUNTRIX, EJAE, AUDREY NUNA & REI AMI"))
    }

    @Test
    fun `apostrophes and case do not matter`() {
        assertTrue(YouTubeMusicRowMatcher.matches("how it's done", "HOW ITS DONE", null))
    }

    @Test
    fun `missing title never matches`() {
        assertFalse(YouTubeMusicRowMatcher.matches("How its Done HUNTRIX", null, "HUNTRIX"))
    }

    @Test
    fun `playlist title with all query words beats a partial one`() {
        val full = YouTubeMusicRowMatcher.score("90s pop", "The Hits: 90s K-Pop", "YouTube Music")
        val partial = YouTubeMusicRowMatcher.score("90s pop", "Pop Indonesia '10-an", "YouTube Music")
        assertEquals(1.0, full, 1e-9)
        assertTrue("partial=$partial", partial < full)
        assertFalse(YouTubeMusicRowMatcher.matches("90s pop", "Pop Indonesia '10-an", "YouTube Music"))
    }

    @Test
    fun `artist words count toward the score but a title hit is required`() {
        // Half the query words are in the row, all of them in the artist: not a match.
        assertFalse(YouTubeMusicRowMatcher.matches("Golden HUNTRIX", "Soda Pop", "HUNTRIX & Golden Ears"))
        // Title carries "Golden", artist carries "HUNTRIX": a match.
        assertTrue(YouTubeMusicRowMatcher.matches("Golden HUNTRIX", "Golden", "HUNTRIX"))
    }

    @Test
    fun `blank query matches nothing`() {
        assertFalse(YouTubeMusicRowMatcher.matches("  ", "Golden", "HUNTRIX"))
    }
}
