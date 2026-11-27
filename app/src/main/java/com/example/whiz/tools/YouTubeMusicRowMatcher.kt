package com.example.whiz.tools

/**
 * Decides whether a YouTube Music search result row (title + artist byline) is the one the user
 * asked for. Word-level: every query word found in the row's title or artist counts, and at
 * least one must be in the title so "a different song by the same artist" is never taken.
 *
 * Pure (no Android dependencies), so it is unit-tested on the JVM. Used by the play/queue tools
 * so they act on a row for the query instead of the first row on screen, which on a stale
 * results page or in the player's up-next list is the song already playing.
 */
object YouTubeMusicRowMatcher {

    /** More than this fraction of the query's words must appear in the row (one of two is not enough). */
    const val MIN_SCORE: Double = 0.5

    /**
     * Fraction (0.0 to 1.0) of [query]'s words present in [title] or [artist]. 0.0 for a blank
     * query or a missing title.
     */
    fun score(query: String, title: String?, artist: String?): Double {
        val queryWords = words(query)
        if (queryWords.isEmpty() || title.isNullOrBlank()) return 0.0
        val rowWords = words(title) + words(artist ?: "")
        return queryWords.count { it in rowWords }.toDouble() / queryWords.size
    }

    /** True when [score] exceeds [MIN_SCORE] and at least one query word is in the title. */
    fun matches(query: String, title: String?, artist: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val titleWords = words(title)
        val titleHit = words(query).any { it in titleWords }
        return titleHit && score(query, title, artist) > MIN_SCORE
    }

    /** Lower-cased words; apostrophes removed (so "it's" == "its"), other punctuation splits. */
    private fun words(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("['’]"), "")
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .toSet()
}
