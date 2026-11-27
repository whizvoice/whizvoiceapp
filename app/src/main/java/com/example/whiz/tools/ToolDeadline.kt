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
 * An old server that sends no `budget_ms` gets [DEFAULT_BUDGET_MS], the same 30 s the server
 * grants when a tool reports status "running".
 */
class ToolDeadline(
    val deadlineAtMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : AbstractCoroutineContextElement(ToolDeadline) {

    /** Milliseconds left before the deadline; never negative. */
    fun remainingMs(): Long = (deadlineAtMs - now()).coerceAtLeast(0L)

    companion object Key : CoroutineContext.Key<ToolDeadline> {
        const val RESERVE_MS = 3_000L
        const val DEFAULT_BUDGET_MS = 30_000L

        fun fromRequest(toolRequest: JSONObject, now: () -> Long = System::currentTimeMillis): ToolDeadline {
            val budgetMs = toolRequest.optLong("budget_ms", DEFAULT_BUDGET_MS)
            return ToolDeadline(now() + budgetMs - RESERVE_MS, now)
        }
    }
}

/** The deadline of the tool request this coroutine is running, or null outside a tool. */
suspend fun currentToolDeadline(): ToolDeadline? = coroutineContext[ToolDeadline]
