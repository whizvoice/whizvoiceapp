# Screen agent waits: audit and migration to a single wait primitive

Scope: `app/src/main/java/com/example/whiz/tools/ScreenAgentTools.kt` (10,735 lines).
Motivation: the CI emulator spike (2026-11-26) showed three tools failing on a slow device
because of fixed waits, with the server's retry succeeding every time. The waits are
production code; the emulator is just the first slow device we can reproduce on.

## Inventory

| Kind | Count | Notes |
|---|---|---|
| Generic wait helpers | 3 | `waitForCondition` (backoff), `waitForElement` (root-node predicate, fixed 200ms poll), `waitForElement` (by viewId/text, returns node) |
| App-specific wait helpers | 9 | `waitForAppReady`, `waitForForegroundPackage`, `waitForWhatsAppReady`, `waitForSearchResults`, `waitForSearchResultsToLoad`, `waitForAndClickPlayButton`, `waitForAndOpenContextMenu`, `waitForAndClickAddToQueue`, `waitForSmsConversationOpened` |
| Hand-rolled `while (currentTimeMillis - start < max)` loops | 6 | 2 are the generic helpers themselves; 4 are YT Music helpers that re-implement polling |
| Helper-based waits at call sites | 52 | 33 distinct hand-picked ceilings from 1,000 to 15,000 ms |
| Fixed `delay(N)` sleeps | 72 | 500ms x20, 1000ms x17, 1500ms x8, 300ms x8, 200ms x5, rest smaller |
| Retry loops with a sleep per attempt | 3 | `enableSpeakerphoneViaAccessibility`, `clickPlayButtonOnDetailPage`, `tapFoodTileAndLog` |

## The primitive

One function replaces the three generic helpers and the four YT Music polling loops:

```kotlin
private suspend fun waitForScreen(
    waitingFor: String,                      // "Messages conversation to open after tapping result"
    deadline: ToolDeadline,                  // from the per-tool budget; caps the wait
    ceilingMs: Long? = null,                 // optional step ceiling, only for "loaded but wrong" cases
    stillLoading: (AccessibilityNodeInfo) -> Boolean = { false },
    success: (AccessibilityNodeInfo) -> Boolean,
): WaitOutcome                               // Met(elapsed) | TimedOut(elapsed, lastRoot dumped) | Stuck(reason)
```

- `waitingFor` is a human-readable description. It goes into the "condition met after N ms"
  log line, the timeout warning, the UI dump reason, and the `stuck.failed_step` field sent
  to the server. Today the log line is just "Condition met after 4584ms" with no subject.
- `success` is checked first; `stillLoading` only matters when success is false. Loading
  keeps polling up to the deadline. Neither true means "loaded and wrong": fail now, with a dump.
- Polling interval, backoff, root-node acquisition and recycling live here and nowhere else.
- Predicate factories keep call sites short: `elementPresent(viewId)`, `elementGone(viewId)`,
  `textPresent(text)`, `foregroundIs(pkg)`, `treeSettled()` (node count and first texts unchanged
  across two polls, for post-scroll and post-animation waits).

Named wrappers survive only where the predicate is reused or non-trivial:
`waitForAppReady` (9 callers), `waitForWhatsAppReady` (screen-state detection), a YT Music
"results loaded" predicate (chips, rows, progress bar). `waitForSmsConversationOpened`
becomes `waitForScreen("Messages conversation to open", success = elementGone(SMS_SEARCH_BOX_ID))`
at its two call sites and is deleted.

## What to migrate, by category

### A. Sleep-then-proceed after app launch (7 sites, all `delay(1000)`)

`selectWhatsAppChatInternal` 1788, `draftWhatsAppMessage` 2202, `sendWhatsAppMessage` 2341,
`selectSMSChatInternal` 2448, `draftSMSMessage` 2906, `getGoogleMapsDirections` 4492,
`recenterGoogleMaps` 5220.

Each follows "X launched successfully" and precedes the first screen read. Nothing is checked.
On a fast phone it wastes a second; on a slow one it is not enough. Replace with
`waitForAppReady` (already exists, is an intelligent wait) where the tool doesn't already call
it a few lines later, and delete the sleep where it does (Maps directions already waits 15s for
foreground right after the sleep, so the sleep is pure waste).

### B. Sleep-then-check-once after a click (11 sites) — highest value

| Line | Function | Sleep | Waiting for |
|---|---|---|---|
| 2247 | draftWhatsAppMessage | 1500 | chat to open after tapping Message on profile |
| 2581 | selectSMSChatInternal | 1500 | conversations list after tapping contact |
| 2619 | selectSMSChatInternal | 1200 | conversation to open after tapping first conversation |
| 2638 | selectSMSChatInternal | 800 | conversation to load before search-box validation |
| 2689 | selectSMSChatInternal | 1500 | search results to appear after submitting search |
| 3921 | playYouTubeMusicSong | 2000 | mini player title after clicking Play |
| 4477 | getGoogleMapsDirections | 1500 | location details screen |
| 4654 | getGoogleMapsDirections | 1500 | directions screen after Back |
| 4699 | getGoogleMapsDirections | 1500 | location details after selecting result |
| 4895 | getGoogleMapsDirections | 1500 | location details after selecting result |
| 4965 | getGoogleMapsDirections | 1500 | directions screen with Start button |

This is exactly the SMS bug pattern: a guess, then one read. Each has an obvious success
predicate (the thing the next line looks for) and most have a still-loading signal (progress
bar, "Finding best route...", empty list container). The two already-fixed SMS sites
(formerly 2727 and 2787) belong here too and should be re-expressed on the primitive.

### C. Hand-rolled polling loops that should be the primitive (4 helpers)

`waitForAndClickPlayButton` 7023, `waitForAndOpenContextMenu` 7061, `waitForAndClickAddToQueue`
7091, and the predicate-style `waitForElement` 6862. Each has its own fixed 200ms poll and its
own ceiling. The Play-button one is the YT Music CI failure: it cannot tell "results still
loading" (ProgressBar `load_progress`, no rows) from "results loaded, none match", so it gives up
at 8s on a loading screen. On the primitive it gets `stillLoading = elementPresent(load_progress)`
and the deadline as its cap. The two 500ms "so the user can see" sleeps inside the context-menu
and add-to-queue helpers are cosmetic and stay.

### D. Ceilings that should become the deadline (reviewed helper-based waits)

Keep a step ceiling only where the predicate means "wrong screen, stop early". Drop it where
it only encodes a guess about device speed. Candidates to drop, all of which succeed on a phone
in well under the ceiling and only fail on slow devices:

- `playYouTubeMusicSong` app-ready 5000 (CI met it at 4584ms)
- `searchGoogleMapsPhrase` / `searchGoogleMapsLocation` app-ready 5000 (directions next door is 15000 after a field failure, dump 1349)
- `recenterGoogleMaps` / `selectLocationFromList` app-ready 3000
- `selectWhatsAppChatInternal` 5000 x2, `enterSMSSearchText` 3000 x3
- Fitbit `addFitbitQuickCalories` / `tapFoodTileAndLog` / `enterCaloriesAndLog` 5000

Ceilings to keep as step ceilings (they are reserves within the budget, not guesses):
`getGoogleMapsDirections` 10_000 directions wait and 15_000 foreground waits (already reasoned
against the 40s server timeout), `selectTransportModeAndStart` 6000 / 5000, `verifyNavigationStarted` 2500.

### E. Retry loops with a sleep per attempt (3)

`enableSpeakerphoneViaAccessibility` 511 (10 x 500ms), `clickPlayButtonOnDetailPage` 7327
(attempts x 500ms + a 1000ms), `tapFoodTileAndLog` 10261 (10 x 400ms). Each is a wait for an
element written as a loop. Express as one `waitForScreen` with the click inside the success
predicate's follow-up, or as wait-then-click. Also the accessibility-service retry at 2448
(8 x 250ms) is a wait for `WhizAccessibilityService.getInstance() != null`.

### F. Settle sleeps after scroll or animation (6) — convert to `treeSettled()`

`swipeUpToDismiss` 1274 (600), `searchGoogleMapsLocation` 4264 (1000), `getGoogleMapsDirections`
4448 (1000) and 4564 (1000), `scrollToTopOfChatList` 9055 (400), `queueYouTubeMusicSong` 4044 (500).
These wait for motion to stop, not for an element. A "tree unchanged across two polls" predicate
returns in ~200ms on a phone and waits longer on a slow device.

### G. Leave alone (about 35 sleeps)

- IME and text-entry settles: `insertText` 1615/1624, `enterSMSSearchText` 3663, `openKeyboardAndWaitForIme`
  8491/8510, `updateSearchText` 9177/9212, `sendDraftedMessageCore` 8342. The keyboard path is
  load-bearing (see memory: Compose inputs need focus+click+delay+click); not worth the risk.
- Cosmetic pauses so the user can see an action: 7076, 7106.
- Sub-300ms spacing between consecutive accessibility actions: 1894, 2555, 1042, 1994, 2037,
  3139 etc. These are not waits for state; converting them buys nothing.
- `pauseYouTubeMusic` 4122 (300ms before reading state): small, follows a media command, no
  tree signal to poll.

## Status (2026-11-26, branch `uiautomator`)

Done: `budget_ms` on every server tool request; `ToolDeadline` in the tool coroutine context;
`pollUntil` (unit-tested) + `waitForScreen` + `elementPresent/elementGone/textPresent/treeSettled`;
categories A, B, C, F migrated; E except the two loops below; D for the SMS, YT Music and Maps
tools. `waitForCondition` and the viewId `waitForElement` remain as shims over `pollUntil`
(40+ callers, ceilings unchanged); the predicate `waitForElement` is deleted. 72 fixed sleeps → 48.

## Remaining

- Category D outside the three tools: WhatsApp select/draft ceilings (5000 x2), Fitbit
  `addFitbitQuickCalories` / `tapFoodTileAndLog` / `enterCaloriesAndLog` (5000), and the ~40
  `waitForCondition(maxWaitMs = ...)` shim callers. Convert to `waitForScreen` with a description
  as each tool is touched; drop the ceiling where it only encodes device speed.
- Category E leftovers: `clickPlayButtonOnDetailPage` (5 attempts with branching and its own
  sleeps) and `tapFoodTileAndLog`'s scroll-to-top loop (already a before/after settle check).
- Category G stays as is.

Every commit keeps the same view ids and screen predicates, so older versions of Messages,
WhatsApp, YT Music and Maps are navigated exactly as before; only how long we are willing to
wait changes.
