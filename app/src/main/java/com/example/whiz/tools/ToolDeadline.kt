package com.example.whiz.tools

import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The time budget for one screen agent tool request.
 *
 * The server sends `budget_ms` on every `tool_execution` message: the timeout it is about to
 * wait with for this request. [ToolExecutor] installs a [ToolDeadline] in the coroutine context
 * before dispatching the tool, so every wait inside the tool can bound itself by
 * [remainingMs] instead of a per-step guess about how fast the device is. The deadline is the
 * budget minus [RESERVE_MS], left over for building and sending the result so the device
 * fails on its own terms (with a UI dump) rather than the server timing out silently.
 *
 * An old server that sends no `budget_ms` gets the per-tool value that server waits with
 * ([DEFAULT_BUDGETS_MS], mirrored from the whizvoice tool modules), or [DEFAULT_BUDGET_MS]
 * for a tool not listed there.
 *
 * When a parked tool resumes (phone unlocked, accessibility service connected) ToolExecutor
 * reports status "running" and the server grants a fresh 30 s; [restart] keeps this deadline
 * in step with that, otherwise every wait in the tool would start with nothing left.
 */
class ToolDeadline(
    deadlineAtMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : AbstractCoroutineContextElement(ToolDeadline) {

    @Volatile
    var deadlineAtMs: Long = deadlineAtMs
        private set

    /** Milliseconds left before the deadline; never negative. */
    fun remainingMs(): Long = (deadlineAtMs - now()).coerceAtLeast(0L)

    /** Start a fresh budget of [budgetMs] from now (minus the reserve). */
    fun restart(budgetMs: Long = RUNNING_EXTENSION_MS) {
        deadlineAtMs = now() + budgetMs - RESERVE_MS
    }

    companion object Key : CoroutineContext.Key<ToolDeadline> {
        const val RESERVE_MS = 3_000L
        /** For a tool with no server-sent budget and no entry in [DEFAULT_BUDGETS_MS]. */
        const val DEFAULT_BUDGET_MS = 30_000L
        /** What the server adds when a tool reports status "running" (app.py tool_status). */
        const val RUNNING_EXTENSION_MS = 30_000L

        /** Server-side wait per tool, for servers that predate `budget_ms`. Keep in step with the whizvoice tool modules. */
        val DEFAULT_BUDGETS_MS: Map<String, Long> = mapOf(
            // screen_agent_tools.py
            "agent_launch_app" to 5_000L,
            "agent_disable_continuous_listening" to 3_000L,
            "agent_set_tts_enabled" to 3_000L,
            "agent_close_app" to 5_000L,
            "agent_open_app" to 5_000L,
            "agent_close_other_app" to 15_000L,
            "agent_log_health_data" to 30_000L,
            "agent_open_health_app_settings" to 70_000L,
            "agent_press_back" to 8_000L,
            "agent_get_ui" to 10_000L,
            "agent_peek_app" to 10_000L,
            "agent_click" to 5_000L,
            "agent_insert_text" to 5_000L,
            // maps_tools.py
            "agent_search_google_maps_location" to 25_000L,
            "agent_search_google_maps_phrase" to 25_000L,
            "agent_recenter_google_maps" to 10_000L,
            "agent_select_location_from_list" to 25_000L,
            "agent_fullscreen_google_maps" to 10_000L,
            "agent_get_google_maps_directions" to 40_000L,
            // music_tools.py
            "agent_play_youtube_music" to 15_000L,
            "agent_queue_youtube_music" to 15_000L,
            "agent_play_next_youtube_music" to 15_000L,
            "agent_pause_youtube_music" to 5_000L,
            // messaging_tools.py (draft tools: 25s when a chat/contact name is given, else 5s)
            "agent_whatsapp_select_chat" to 10_000L,
            "agent_whatsapp_draft_message" to 25_000L,
            "agent_whatsapp_send_message" to 8_000L,
            "agent_sms_select_chat" to 10_000L,
            "agent_sms_draft_message" to 25_000L,
            "agent_sms_send_message" to 8_000L,
            "agent_dismiss_draft" to 5_000L,
        )

        fun fromRequest(toolRequest: JSONObject, now: () -> Long = System::currentTimeMillis): ToolDeadline {
            val tool = toolRequest.optString("tool", "")
            val fallback = DEFAULT_BUDGETS_MS[tool] ?: DEFAULT_BUDGET_MS
            val budgetMs = toolRequest.optLong("budget_ms", fallback)
            return ToolDeadline(now() + budgetMs - RESERVE_MS, now)
        }
    }
}

/** The deadline of the tool request this coroutine is running, or null outside a tool. */
suspend fun currentToolDeadline(): ToolDeadline? = coroutineContext[ToolDeadline]
