package com.example.whiz

/**
 * A media session's "what is playing" state, parsed from `dumpsys media_session`.
 *
 * [state] uses PlaybackState constants: 3 = playing, 2 = paused, 1 = stopped; -1 when the state line
 * could not be parsed.
 */
data class MediaSessionState(val title: String, val artist: String, val state: Int, val description: String = "$title, $artist") {
    val isPlaying get() = state == 3
    val isPausedOrStopped get() = state == 2 || state == 1

    /**
     * Match against the whole description line (titles with commas shift [artist] into the wrong
     * slot), with curly apostrophes normalised to straight ones (YouTube Music writes "It’s").
     */
    fun mentions(vararg needles: String): Boolean {
        val haystack = description.replace('’', '\'').replace('‘', '\'')
        return needles.any { haystack.contains(it.replace('’', '\''), ignoreCase = true) }
    }
}

/**
 * Pure parser over `dumpsys media_session` output for the session owned by [pkg].
 *
 * Tolerant of the two line formats seen on Android 14-17:
 *   metadata: size=N, description=<title>, <artist>, <album>
 *   state=PlaybackState {state=3, position=...}            (older)
 *   state=PlaybackState {state=PLAYING(3), position=...}   (newer)
 * Returns null when no session for [pkg] has metadata (nothing loaded yet).
 */
fun parseMediaSession(dump: String, pkg: String): MediaSessionState? {
    val lines = dump.lines()
    // Session blocks start at a line naming the package with "(userId=" and run until the next such
    // header. The summary line "Media button session is <pkg>/... (userId=0)" matches too but has no
    // body, so scan every candidate block and use the first one that carries metadata.
    val headerIdxs = lines.indices.filter { lines[it].contains(pkg) && lines[it].contains("(userId=") }
    for (headerIdx in headerIdxs) {
        val block = lines.drop(headerIdx + 1).takeWhile { !it.contains("(userId=") }
        val metadataLine = block.firstOrNull { it.trim().startsWith("metadata:") } ?: continue
        val desc = Regex("description=(.*)$").find(metadataLine)?.groupValues?.get(1) ?: continue
        if (desc.trim() == "null") continue
        val parts = desc.split(", ").map { it.trim().takeUnless { s -> s == "null" }.orEmpty() }
        val title = parts.getOrNull(0).orEmpty()
        val artist = parts.getOrNull(1).orEmpty()

        val stateLine = block.firstOrNull { it.contains("PlaybackState {") }
        val state = stateLine
            ?.let { Regex("state=(?:[A-Z_]+\\()?(\\d+)").find(it.substringAfter("PlaybackState {"))?.groupValues?.get(1)?.toInt() }
            ?: -1
        return MediaSessionState(title, artist, state, desc.trim())
    }
    return null
}
