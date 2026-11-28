# Screen Agent Wait Primitive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace guess-based sleeps and hand-picked ceilings in the screen agent tools with one described, deadline-aware wait primitive, starting with the three tools that fail on slow devices.

**Architecture:** The server sends the per-tool timeout it is about to wait with as `budget_ms`; the client turns it into a `ToolDeadline` coroutine-context element so no tool signature changes. A generic `pollUntil` engine (unit-tested, no Android types) backs a `waitForScreen` helper in `ScreenAgentTools` that takes a description, a success predicate and an optional still-loading predicate. The old generic helpers become shims over it; tools migrate one per commit.

**Tech Stack:** Kotlin coroutines, JUnit 4 + kotlinx-coroutines-test (unit), Python 3 + pytest (server).

**Spec:** `docs/superpowers/specs/2026-11-26-screen-agent-wait-primitive-audit.md`

## Global Constraints

- Every view id, text match and screen predicate stays exactly as it is today; only how long we wait changes (CLAUDE.md: backwards compatibility across app versions).
- Client must work against an old server (no `budget_ms`) and server against an old client (ignores the field).
- No instrumented-test or emulator verification steps in this plan; the user validates on the phone. Unit tests only where there is logic without Android types.
- Code edits via the Edit tool, never sed/heredoc.
- Commit per task on `uiautomator`; do not push.

Platform constraint vs design choice: a coroutine-context element for the deadline is a design choice (avoids touching ~15 public tool signatures); the 3 s client reserve is a design choice (flat, revisit if result payloads grow); `budget_ms` carried in the WebSocket message rather than a client table is a design choice (single source of truth); the server's existing 30 s "running" extension is a platform fact we reuse rather than duplicate.

## Review Focus

1. A tool request with no `budget_ms` (old server) must behave exactly as today: ceilings apply, nothing waits longer or shorter. Pinned in Task 2 (`falls back to the per-tool default when the message has no budget`).
2. A wait whose deadline is already in the past must evaluate the predicate once and return, never spin. Pinned in Task 3 (`evaluates once when no time remains`).
3. Still-loading must not extend past the deadline; a spinner that never clears must still time out. Pinned in Task 3 (`still loading does not outlive the deadline`).
4. A ceiling shorter than the remaining budget must win, so "loaded but wrong" screens fail fast. Pinned in Task 3 (`ceiling caps the wait below the deadline`).
5. Root-node recycling: every root obtained inside `waitForScreen` must be recycled unless returned to the caller. No unit test can pin this (Android type); reviewer checks Task 3 by reading.

---

### Task 1: Server sends `budget_ms` on every tool_execution message

**Files:**
- Modify: `whizvoice/screen_agent_tools.py` (9 message sites + `_send_tool_and_wait`), `whizvoice/maps_tools.py` (6), `whizvoice/music_tools.py` (4), `whizvoice/messaging_tools.py` (7)
- Test: `whizvoice/tests/test_tool_budget.py`

**Interfaces:**
- Produces: every dict with `"type": "tool_execution"` carries `"budget_ms": int(timeout * 1000)` where `timeout` is the float later passed to `wait_for_tool_result`.

- [ ] **Step 1: Write the failing tests**

```python
# tests/test_tool_budget.py
import pathlib, re, json
from unittest.mock import AsyncMock, MagicMock
import pytest

MODULES = ["screen_agent_tools.py", "maps_tools.py", "music_tools.py", "messaging_tools.py"]

@pytest.mark.parametrize("module", MODULES)
def test_every_tool_execution_message_carries_budget_ms(module):
    src = (pathlib.Path(__file__).parent.parent / module).read_text()
    # each message literal: from '"type": "tool_execution"' to the closing brace of that dict
    starts = [m.start() for m in re.finditer(r'"type": "tool_execution"', src)]
    assert starts, module
    for s in starts:
        block = src[s : src.index("}", s)]
        assert '"budget_ms"' in block, f"{module} message at offset {s} lacks budget_ms"

@pytest.mark.asyncio
async def test_send_tool_and_wait_budget_matches_timeout():
    from screen_agent_tools import _send_tool_and_wait
    ws = MagicMock(); ws.send_text = AsyncMock()
    handler = MagicMock(); handler.wait_for_tool_result = AsyncMock(return_value={"success": True})
    await _send_tool_and_wait("agent_press_back", {}, "u", ws, handler, None, timeout=8.0)
    sent = json.loads(ws.send_text.call_args.args[0])
    assert sent["budget_ms"] == 8000
    assert handler.wait_for_tool_result.call_args.kwargs["timeout"] == 8.0
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd whizvoice && venv/bin/python -m pytest tests/test_tool_budget.py -q`
Expected: 4 parametrized FAIL on `lacks budget_ms`, 1 FAIL on `KeyError: 'budget_ms'`.

- [ ] **Step 3: Add `budget_ms` to each message**

In every tool that builds its own message, hoist the literal passed to `wait_for_tool_result(timeout=...)` into a local `timeout = <value>` above the message dict if it is not already a local, add `"budget_ms": int(timeout * 1000),` to the dict, and pass `timeout=timeout`. In `_send_tool_and_wait` add the same key from its `timeout` parameter. Values stay what they are today.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd whizvoice && venv/bin/python -m pytest tests/test_tool_budget.py test_stuck_recovery.py -q`
Expected: all PASS.

- [ ] **Step 5: Commit** (`whizvoice` repo): `Server: send budget_ms with every screen agent tool request`

---

### Task 2: `ToolDeadline` context element installed by ToolExecutor

**Files:**
- Create: `whizvoiceapp/app/src/main/java/com/example/whiz/tools/ToolDeadline.kt`
- Modify: `whizvoiceapp/app/src/main/java/com/example/whiz/tools/ToolExecutor.kt:534` (wrap the `when (toolName)` dispatch)
- Test: `whizvoiceapp/app/src/test/java/com/example/whiz/tools/ToolDeadlineTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class ToolDeadline(val deadlineAtMs: Long, private val now: () -> Long = System::currentTimeMillis)
      : AbstractCoroutineContextElement(ToolDeadline) {
      companion object Key : CoroutineContext.Key<ToolDeadline> {
          const val RESERVE_MS = 3_000L
          const val DEFAULT_BUDGET_MS = 30_000L          // matches the server's "running" extension
          fun fromRequest(toolRequest: JSONObject, now: () -> Long = System::currentTimeMillis): ToolDeadline
          // budget = toolRequest.optLong("budget_ms", DEFAULT_BUDGET_MS); deadline = now() + budget - RESERVE_MS
      }
      fun remainingMs(): Long                            // never negative
  }
  suspend fun currentToolDeadline(): ToolDeadline?       // coroutineContext[ToolDeadline]
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
class ToolDeadlineTest {
    private var clock = 1_000L
    private val now = { clock }

    @Test fun `deadline is budget minus reserve from now`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"budget_ms": 40000}"""), now)
        assertEquals(1_000L + 40_000L - 3_000L, d.deadlineAtMs)
        assertEquals(37_000L, d.remainingMs())
    }
    @Test fun `falls back to the per-tool default when the message has no budget`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"tool": "agent_press_back"}"""), now)
        assertEquals(30_000L - 3_000L, d.remainingMs())
    }
    @Test fun `remaining never goes negative`() {
        val d = ToolDeadline.fromRequest(JSONObject("""{"budget_ms": 1000}"""), now)
        clock += 10_000
        assertEquals(0L, d.remainingMs())
    }
    @Test fun `is readable from the coroutine context`() = runTest {
        val d = ToolDeadline(5_000L, now)
        withContext(d) { assertSame(d, currentToolDeadline()) }
        assertNull(currentToolDeadline())
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd whizvoiceapp && ./gradlew testDebugUnitTest --tests 'com.example.whiz.tools.ToolDeadlineTest' --console=plain -q`
Expected: compilation FAIL, unresolved `ToolDeadline`.

- [ ] **Step 3: Implement `ToolDeadline.kt` per the interface above**

- [ ] **Step 4: Install it in `ToolExecutor.executeToolFromJson`**

Wrap the existing `when (toolName) { ... }` block in `withContext(ToolDeadline.fromRequest(toolRequest)) { ... }`. Log one line: `"⏱ $toolName budget ${budget}ms (deadline reserve ${RESERVE_MS}ms)"`.

- [ ] **Step 5: Run tests to verify they pass**

Run: same command as Step 2, plus `./gradlew compileDebugKotlin --console=plain -q`
Expected: 4 PASS, compile clean.

- [ ] **Step 6: Commit**: `Tools: carry the server's budget as a ToolDeadline in the coroutine context`

---

### Task 3: `pollUntil` engine, `waitForScreen`, predicate factories, shims

**Files:**
- Create: `whizvoiceapp/app/src/main/java/com/example/whiz/tools/ScreenWait.kt`
- Modify: `whizvoiceapp/app/src/main/java/com/example/whiz/tools/ScreenAgentTools.kt` — `waitForCondition` (8546), both `waitForElement`s (6862, 8681), add `waitForScreen` + factories next to `waitForAppReady` (8575)
- Test: `whizvoiceapp/app/src/test/java/com/example/whiz/tools/ScreenWaitTest.kt`

**Interfaces:**
- Produces (in `ScreenWait.kt`, no Android imports):
  ```kotlin
  sealed class WaitOutcome { data class Met(val elapsedMs: Long) : WaitOutcome(); data class TimedOut(val elapsedMs: Long, val wasLoading: Boolean) : WaitOutcome() }

  suspend fun <T> pollUntil(
      waitingFor: String,
      deadline: ToolDeadline?,            // null → ceilingMs alone bounds the wait
      ceilingMs: Long? = null,            // null with null deadline → DEFAULT_CEILING_MS (2_000)
      initialDelayMs: Long = 50, maxIntervalMs: Long = 500,
      now: () -> Long = System::currentTimeMillis,
      log: (String) -> Unit = {},
      read: () -> T?,                     // null → treat as not ready, keep polling
      stillLoading: (T) -> Boolean = { false },
      success: (T) -> Boolean,
  ): WaitOutcome
  ```
  Bound = `min(ceilingMs ?: ∞, deadline?.remainingMs() ?: ∞)`, falling back to `DEFAULT_CEILING_MS` if both absent. Loop: read → success? Met; else if stillLoading, keep polling; else keep polling (a wrong screen only fails fast when the caller passes a ceiling). Backoff doubles from `initialDelayMs` to `maxIntervalMs`. Logs `"$waitingFor: met after N ms"` or `"$waitingFor: timed out after N ms (loading=$wasLoading)"`.
- Produces (in `ScreenAgentTools`):
  ```kotlin
  private suspend fun waitForScreen(
      waitingFor: String, ceilingMs: Long? = null,
      stillLoading: (AccessibilityNodeInfo) -> Boolean = { false },
      success: (AccessibilityNodeInfo) -> Boolean,
  ): Boolean   // reads currentToolDeadline(); acquires root per poll via accessibilityService.getCurrentRootNode(), recycles it after each predicate run
  private fun elementPresent(viewId: String): (AccessibilityNodeInfo) -> Boolean
  private fun elementGone(viewId: String): (AccessibilityNodeInfo) -> Boolean
  private fun textPresent(text: String): (AccessibilityNodeInfo) -> Boolean
  private fun treeSettled(): (AccessibilityNodeInfo) -> Boolean   // true when (childCount, first 5 texts) equals the previous poll's
  ```
  `waitForScreen` needs the service: take `accessibilityService: WhizAccessibilityService` as its first parameter, matching the existing helpers.

- [ ] **Step 1: Write the failing tests** (fake root = `String`, virtual time via `runTest`, `now = { testScheduler.currentTime }`)

```kotlin
class ScreenWaitTest {
    @Test fun `returns Met as soon as success is true`() = runTest { ... read = { "ready" }, success = { it == "ready" } → Met }
    @Test fun `keeps polling while loading then meets`() = runTest { reads "loading","loading","ready" → Met, elapsed > 0 }
    @Test fun `still loading does not outlive the deadline`() = runTest { deadline 1_000 ms away, read always "loading", stillLoading true → TimedOut(wasLoading = true) at ≈1_000 }
    @Test fun `ceiling caps the wait below the deadline`() = runTest { deadline 10_000, ceiling 500, never ready → TimedOut at ≈500 }
    @Test fun `deadline caps the wait below the ceiling`() = runTest { deadline 300, ceiling 5_000 → TimedOut at ≈300 }
    @Test fun `evaluates once when no time remains`() = runTest { deadline already past, read = { "ready" } → Met; read count == 1 }
    @Test fun `null read is treated as not ready`() = runTest { reads null, null, "ready" → Met }
    @Test fun `uses default ceiling when neither bound is given`() = runTest { never ready → TimedOut at ≈2_000 }
    @Test fun `log lines name what was waited for`() = runTest { collect log; assert any contains "conversation to open" }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd whizvoiceapp && ./gradlew testDebugUnitTest --tests 'com.example.whiz.tools.ScreenWaitTest' --console=plain -q`
Expected: compilation FAIL, unresolved `pollUntil`.

- [ ] **Step 3: Implement `ScreenWait.kt`**

- [ ] **Step 4: Run tests to verify they pass**

Expected: 9 PASS.

- [ ] **Step 5: Add `waitForScreen` and the four factories to `ScreenAgentTools`**

`treeSettled()` holds its previous snapshot in a closure, so each call site makes a fresh one.

- [ ] **Step 6: Re-implement the three generic helpers as shims**

`waitForCondition(maxWaitMs, initialDelayMs, maxIntervalMs, condition)` → `pollUntil(waitingFor = "condition", deadline = null, ceilingMs = maxWaitMs, read = { Unit }, success = { condition() }) is Met`. Both `waitForElement`s likewise with `ceilingMs = timeoutMs/maxWaitMs` and `deadline = null`. Same signatures, same bounds, so the 52 call sites are unchanged.

- [ ] **Step 7: Compile and run the whole unit suite**

Run: `cd whizvoiceapp && ./gradlew testDebugUnitTest --console=plain -q`
Expected: PASS.

- [ ] **Step 8: Commit**: `Tools: add pollUntil/waitForScreen wait primitive; old helpers become shims`

---

### Task 4: SMS select and draft on the primitive

**Files:**
- Modify: `ScreenAgentTools.kt` — `selectSMSChatInternal` sleeps at 2581, 2619, 2638, 2689; the two `waitForSmsConversationOpened` call sites; delete `waitForSmsConversationOpened` (8601); `enterSMSSearchText` ceilings 3677/3705/3776 (3000 → `null`).

**Interfaces:**
- Consumes: Task 3 `waitForScreen`, `elementPresent`, `elementGone`; `SMS_SEARCH_BOX_ID`.

- [ ] **Step 1: Replace each sleep with a described wait** (view ids are the ones the next line already reads)

| Site | Replace with |
|---|---|
| 2689 `delay(1500)` after search | `waitForScreen(svc, "Messages search results for '$contactName'", success = { elementPresent(zero_state_search_results)(it) \|\| elementPresent(zero_state_search_chat_results)(it) })` |
| 2581 `delay(1500)` after tapping contact | `waitForScreen(svc, "Messages conversations list or conversation after tapping '$contactName'", success = { elementPresent(zero_state_search_chat_header)(it) \|\| elementGone(SMS_SEARCH_BOX_ID)(it) })` |
| 2619 `delay(1200)` after tapping first conversation | `waitForScreen(svc, "Messages conversation to open", success = elementGone(SMS_SEARCH_BOX_ID))` |
| 2638 `delay(800)` before validation | delete (2619 already waited) |
| both `waitForSmsConversationOpened(svc)` | `waitForScreen(svc, "Messages conversation to open after tapping result", success = elementGone(SMS_SEARCH_BOX_ID))` |

- [ ] **Step 2: Drop the three `enterSMSSearchText` ceilings** so the deadline bounds them (`maxWaitMs = 3000` → use `waitForScreen` with no ceiling, same predicates).

- [ ] **Step 3: Compile**: `./gradlew compileDebugKotlin --console=plain -q`

- [ ] **Step 4: Commit**: `SMS: wait for the screen the next step reads instead of fixed sleeps`

---

### Task 5: YouTube Music on the primitive

**Files:**
- Modify: `ScreenAgentTools.kt` — `waitForAndClickPlayButton` (7023), `waitForAndOpenContextMenu` (7061), `waitForAndClickAddToQueue` (7091), `waitForSearchResultsToLoad` (6893), `playYouTubeMusicSong` 3921 `delay(2000)`, app-ready ceiling 3865 (5000) and 4009 (8000).

**Interfaces:**
- Consumes: Task 3. `clickFirstYouTubeMusicResult(root, svc, contentType): ClickResultInfo` unchanged.

- [ ] **Step 1: Rewrite `waitForAndClickPlayButton` on `pollUntil`**

`read = { svc.getCurrentRootNode() }`, `stillLoading = { elementPresent("com.google.android.apps.youtube.music:id/load_progress")(it) && !hasAnyResultRow(it) }` where `hasAnyResultRow` is the type-indicator scan already in `waitForSearchResultsToLoad` (extract it as `private fun hasYtmResultRow(root): Boolean`), `success = { root -> val r = clickFirstYouTubeMusicResult(root, svc, contentType); if (r.clicked) lastClick = r; r.clicked }`. Keep the ANR-dialog dismissal inside `read`. `waitingFor = "YT Music result row for '$contentType' to click"`. Ceiling: none (deadline bounds it; server budget is 15 s).

- [ ] **Step 2: Rewrite the context-menu and add-to-queue helpers the same way**, keeping their two cosmetic `delay(500)`s after the click.

- [ ] **Step 3: `waitForSearchResultsToLoad`** → `waitForScreen(svc, "YT Music search results", stillLoading = elementPresent(load_progress), success = { elementPresent(chip_cloud_chip_text)(it) || hasYtmResultRow(it) })`.

- [ ] **Step 4: 3921 `delay(2000)`** → `waitForScreen(svc, "YT Music mini player to show a title", success = { getMiniPlayerTitle(it) != null })`, then the existing read.

- [ ] **Step 5: App-ready ceilings 3865 and 4009** → leave `waitForAppReady` but pass `maxWaitMs = currentToolDeadline()?.remainingMs() ?: 5000`.

- [ ] **Step 6: Compile, commit**: `YT Music: tell "results loading" from "no match" and wait on the deadline`

---

### Task 6: Google Maps on the primitive

**Files:**
- Modify: `ScreenAgentTools.kt` — `getGoogleMapsDirections` sleeps 4477, 4492, 4654, 4699, 4895, 4965; app-ready ceilings `searchGoogleMapsPhrase` 5458 and `searchGoogleMapsLocation` 4200 (5000), `recenterGoogleMaps` 5233 and `selectLocationFromList` 5350 (3000).

- [ ] **Step 1: Replace the sleeps**

| Site | Replace with |
|---|---|
| 4492 `delay(1000)` after launch | delete; `waitForAppReady(..., 15000)` follows at 4517 |
| 4477, 4699, 4895 "location details to load" | `waitForScreen(svc, "Maps location details", success = { detectGoogleMapsScreenState(it) == GoogleMapsScreenState.LOCATION_DETAILS })` (use the enum value the state detector already returns for that screen) |
| 4654 after Back | `waitForScreen(svc, "Maps directions screen after back", success = { detectGoogleMapsScreenState(it) == GoogleMapsScreenState.DIRECTIONS })` |
| 4965 "directions screen with Start" | delete; the 10 s `waitForCondition` directly below already polls for it |

- [ ] **Step 2: App-ready ceilings** 4200, 5458 (5000) and 5233, 5350 (3000) → `maxWaitMs = currentToolDeadline()?.remainingMs() ?: <old value>`.

- [ ] **Step 3: Compile, commit**: `Maps: wait for the details/directions screen instead of 1.5s sleeps; app-ready on the deadline`

---

### Task 7: Remaining launch sleeps, settle sleeps and retry loops

**Files:**
- Modify: `ScreenAgentTools.kt` — launch sleeps 1788, 2202, 2341, 2448, 2906, 5220; settle sleeps 1274, 4264, 4448, 4564, 9055, 4044; retry loops 511, 7327, 10261; accessibility-service retry at 2448.

- [ ] **Step 1: Launch sleeps** → `waitForAppReady(svc, <package>)` with `maxWaitMs = currentToolDeadline()?.remainingMs() ?: 3000`, except where the service instance is fetched after the sleep (1788, 2341, 2448, 2906, 5220): move the `getInstance()` null-check above the wait first.

- [ ] **Step 2: Settle sleeps** → `waitForScreen(svc, "<what> animation to settle", ceilingMs = <old sleep × 2>, success = treeSettled())`.

- [ ] **Step 3: Retry loops** → one `waitForScreen` whose `success` finds the element, followed by the click; `ceilingMs` = old attempts × interval. 2448's service retry → `pollUntil("accessibility service instance", deadline, ceilingMs = 2000, read = { WhizAccessibilityService.getInstance() }, success = { true })`.

- [ ] **Step 4: Compile, run unit suite, commit**: `Tools: replace launch/settle sleeps and retry loops with described waits`

---

### Task 8: Prune

- [ ] **Step 1:** If `waitForElement` (predicate form, 6862) has no callers after Tasks 5 and 7, delete it. Keep `waitForCondition` and the viewId `waitForElement` as shims; 40+ callers remain and migrating them is not in scope.
- [ ] **Step 2:** Rewrite the spec's "Order of work" to list only what remains (category D ceilings outside the three tools, category G stays).
- [ ] **Step 3:** Commit: `Tools: drop unused predicate waitForElement; update wait audit`
