package com.example.whiz.tools

import org.json.JSONObject

/**
 * Builds the `stuck` block attached to a screen agent tool result that reports `success: false`.
 *
 * A scripted flow (Maps directions, YouTube Music queue, ...) knows its goal and the step that
 * failed, and the phone can read the screen it is stuck on. The conversational model cannot see
 * any of that mid-flow, so when a flow gives up we hand it everything it needs to recover with the
 * generic UI tools: the goal, the failed step, which app is in front, and the interactable element
 * list in the same `[n]` form `agent_get_ui` produces. The list is taken with
 * [ScreenAgentTools.getUi], which also refreshes the element registry, so the ids are valid for
 * `agent_click` right away without another `agent_get_ui` round trip.
 *
 * Pure: no Android dependencies, so the shape is unit-tested on the JVM.
 */
object StuckReport {

    /** Upper bound on the screen text carried in a tool result. */
    const val MAX_SCREEN_CHARS: Int = 6000

    private const val TRUNCATION_MARK = "\n… (truncated)"

    /**
     * Never get a block: the generic UI tools are the recovery, not the thing being recovered, and
     * agent_launch_app is intent-based (a failure there is "not installed", not an on-screen obstacle).
     */
    val GENERIC_UI_TOOLS: Set<String> = setOf(
        "agent_get_ui",
        "agent_click",
        "agent_insert_text",
        "agent_press_back",
        "agent_peek_app",
        "agent_launch_app",
    )

    /** One-line goal per scripted tool, so the model knows what "done" would have looked like. */
    val GOALS: Map<String, String> = mapOf(
        "agent_play_youtube_music" to "Play the requested music on YouTube Music",
        "agent_queue_youtube_music" to "Add the requested song to the YouTube Music queue",
        "agent_pause_youtube_music" to "Pause YouTube Music",
        "agent_search_google_maps_phrase" to "Search Google Maps and show the results list",
        "agent_search_google_maps_location" to "Show the requested place in Google Maps",
        "agent_select_location_from_list" to "Open one result from the Google Maps results list",
        "agent_get_google_maps_directions" to "Start Google Maps directions to the selected place",
        "agent_recenter_google_maps" to "Recenter Google Maps on the current location",
        "agent_fullscreen_google_maps" to "Bring Google Maps navigation back to full screen",
        "agent_whatsapp_select_chat" to "Open the requested chat",
        "agent_sms_select_chat" to "Open the requested chat",
        "agent_whatsapp_draft_message" to "Type the drafted message into the open chat",
        "agent_sms_draft_message" to "Type the drafted message into the open chat",
        "agent_draft_message" to "Type the drafted message into the open chat",
        "agent_whatsapp_send_message" to "Send the drafted message",
        "agent_sms_send_message" to "Send the drafted message",
        "agent_send_message" to "Send the drafted message",
        "agent_press_call_button" to "Start the call from the open contact or chat",
        "agent_close_other_app" to "Close the requested app",
    )

    /**
     * True when [result] (a tool's own JSON) reports a failed outcome, the tool needs the accessibility
     * service (so it was driving another app's UI) and it is not one of the generic UI tools.
     *
     * A failed outcome is `success: false`, or `success: true` with `sent: false`: the generic send tool
     * uses `success` for "ran without crashing" and `sent` for whether the message actually went, and a
     * hidden Send button is exactly the kind of obstacle the block exists for.
     */
    fun shouldAttach(toolName: String, result: JSONObject, requiresAccessibility: Boolean): Boolean {
        if (!requiresAccessibility || toolName in GENERIC_UI_TOOLS) return false
        val failed = result.has("success") && !result.optBoolean("success", true)
        val unsent = result.has("sent") && !result.optBoolean("sent", true)
        return failed || unsent
    }

    /**
     * Adds the `stuck` block to [result] and returns the same instance. [screen] is the
     * `getUi("interactable")` dump (null when it could not be read); [foregroundPackage] is the
     * package owning the active window (null when unknown). When the foreground is Whiz itself
     * ([selfPackage]) the screen is withheld: offering Whiz's own controls as click targets is
     * never the right recovery, launching the target app is. Absent inputs leave their keys out.
     */
    fun attach(
        toolName: String,
        result: JSONObject,
        screen: String?,
        foregroundPackage: String?,
        selfPackage: String? = null,
    ): JSONObject {
        val failedStep = result.optString("error").takeIf { it.isNotBlank() } ?: "unknown"
        val whizInFront = selfPackage != null && foregroundPackage == selfPackage
        val usableScreen = screen?.takeUnless { whizInFront }
        val stuck = JSONObject().apply {
            put("goal", GOALS[toolName] ?: "Complete $toolName")
            put("failed_step", failedStep)
            foregroundPackage?.let { put("foreground_app", it) }
            usableScreen?.let { put("screen", truncate(it)) }
            put("recovery", recoveryText(toolName, usableScreen != null, whizInFront))
        }
        result.put("stuck", stuck)
        return result
    }

    private fun recoveryText(toolName: String, hasScreen: Boolean, whizInFront: Boolean): String {
        val look = when {
            whizInFront -> "Whiz itself is in the foreground, so the target app is not open: use agent_launch_app to open it."
            hasScreen -> "The element ids in 'screen' are valid right now: use agent_click on a dismissive element " +
                "(or agent_press_back) to clear whatever is in the way, or agent_launch_app if the target app is not foreground_app."
            else -> "The screen could not be read; call agent_get_ui to see what is in the way, then clear it with " +
                "agent_click or agent_press_back, or agent_launch_app if the target app is not foreground_app."
        }
        return "The tool could not finish on the current screen. $look If you have not already retried " +
            "$toolName for this request, call it once more with the same arguments; otherwise tell the user what is " +
            "on screen and stop."
    }

    /** Keeps the END of the dump: in tree order, in-window popups and bottom sheets come last. */
    private fun truncate(screen: String): String =
        if (screen.length <= MAX_SCREEN_CHARS) screen
        else TRUNCATION_MARK.trimStart() + "\n" + screen.takeLast(MAX_SCREEN_CHARS - TRUNCATION_MARK.length)
}
