# Claude Context for WhizVoice Project

This project has two folders: whizvoice which has the webapp code, and whizvoiceapp which has the Android app code.

On my phone, which is what I use for testing as well as running the production app, there are two apps installed: the production app and the debug app. You can update them with these scripts:
- Production app: `./install.sh --production` (add `--force` to force reinstall even if APK unchanged)
- Debug app: `./install.sh` (add `--force` to force reinstall even if APK unchanged)

## Guidelines

- Only update this CLAUDE.md file when explicitly asked by the user
- Don't do git operations; user prefers to do it themselves
- Don't try to run the webserver. It's on a different machine.
- Don't do anything extra outside of what the user asked. For example if user asked you to add logging to debug a test, don't add code to try to make the test pass.
- Unfortunately the screen agent tools are quite brittle and can break when the apps that they navigate get updated. Whereever possible, please try to maintain backwards compatibility when updating UI navigation in screen agent tools so that it will work on previous versions as well as current versions of the app being navigated (e.g. YouTube Music, Google Messages, WhatsApp, Google Maps)

## Testing

### Server Logs

Server logs can be retrieved from the server machine with journalctl. Ask the user to run the command with the appropriate timestamps (use UTC). Example:
```
sudo journalctl -u whizvoice --since "2026-02-26 21:49:00 UTC" --until "2026-02-26 21:51:00 UTC" --no-pager > whizvoice_server_logs.txt
```
To figure out the right UTC timestamps, look at WebSocket log timestamps in logcat (e.g. `"timestamp":"2026-02-26T21:50:27.963Z"`) and add a buffer around the event of interest.

### Logcat

When i ask you to run or stream logcat, i mean stream logcat from the connected android device onto a file on the computer. We have a lot of logs and logcat rotates quickly, so just pulling it isn't enough.

- Start the stream BEFORE reproducing the issue; a `-d` dump after the fact loses lines that already rotated out of the ring buffer.
- A plain `adb logcat > file` redirect block-buffers — the file stays empty until several KB accumulate, so you can't read it incrementally mid-stream. Force line buffering by piping through grep:
  ```
  adb logcat -v time -s WakeWordEngine:D WakeWordService:D \
    | grep --line-buffered -E "." > logfile.log
  ```
- Filter by tag (`-s Tag:D ...`) up front to keep the file readable and small.

### Standard Integration Tests

You can run tests with run_tests_on_debug.sh script from whizvoiceapp . Note that often you will want to run a specific test with the --test option (check --help for more details) and use the option to skip unit tests. e.g.

```
cd whizvoiceapp && ./run_tests_on_debug.sh --skip-unit --test "com.example.whiz.integration.ChatLoadErrorTest#test500Error_ShowsErrorUI_and_check_retry_button"
```

the tests take a long time so please run it with your max timeout (10mins)

**IMPORTANT:** Always run tests without timeout as they can take several minutes to complete. Also, there is no need to build with Gradle or install the app before testing; the test will build and install already, so just run the test directly. Prefer running tests on the physical device rather than the emulator — the physical device is more performant and reliable. Do not use `ANDROID_SERIAL` or `--emulator` to target the emulator.

When investigating test failures, check these log files for detailed information:

- **test_gradle_output.log** - Gradle build output and test execution results
- **test_logcat_output.log** - Android system logs during test execution (key for debugging runtime issues)
- **test_summary.log** - High-level test results and summaries

Crucially, screenshots from failed tests will show up in whizvoiceapp/test_screenshots directory

### Screen Agent Tests

To run screen agent integration tests (which use ~/android_screenshot_testing/android_accessibility_tester.py):

```
cd whizvoiceapp && ./venv/bin/pytest run_screen_agent_tests.py
```

The test script automatically sources `export_anthropic_key.sh` to load the API key.

To run a specific test:
```
cd whizvoiceapp && ./venv/bin/pytest run_screen_agent_tests.py::test_google_maps_ui_dump -v
```

Test output for screen agent tests is stored in whizvoiceapp/screen_agent_test_output directory:
- **screen_agent_logcat.log** - Android logcat output during screen agent test execution
- Screenshots from tests are also saved in this directory

## Wake Word Detection Metrics

Running aggregate stats (count, accepted count, mean, std dev) are stored in SharedPreferences (`wake_word_settings`) using Welford's online algorithm. A human-readable summary is also written to the app's external files dir after each detection.

To pull the stats file:
```
# Debug:
adb pull /sdcard/Android/data/com.example.whiz.debug/files/wake_word_stats.txt

# Production:
adb pull /sdcard/Android/data/com.example.whiz/files/wake_word_stats.txt
```

Key files:
- `WakeWordPreferences.kt` - `recordDetection()`, `getStats()`, `getRecentDetections()`, `clearMetrics()`. Also stores a rolling window of the last 10 detections (confidence, accepted/rejected, timestamp) as JSON in SharedPreferences. These are included in the stats file under "recent (last N):"
- `WakeWordService.kt` - calls `recordDetection()` in `checkForWakeWord()` and logs stats summary

## Database

Please refer to whizvoiceapp/.supabaseinfo for info about what's in the database and what functions are in the database.

## Health Connect Integration (calorie / weight logging)

Whiz logs calories and weight via the `agent_log_health_data` tool, which writes a `NutritionRecord` / `WeightRecord` into **Health Connect** (`HealthConnectManager.kt`). Whiz only ever *writes* to Health Connect — it is up to a separate consuming app (Google Health, Samsung Health, etc.) to *read* that data out and display it. This split is the source of a confusing class of "Wizvoice says it logged but I don't see it" reports.

### Why "logged successfully" can still show nothing in the user's health app

- A successful Health Connect write (`source: "health_connect"`, "Wrote N kcal to Health Connect" in logcat) only means the data is in the Health Connect hub. It does **not** mean any app will display it.
- **Whiz cannot detect whether another app is connected.** The Health Connect SDK only exposes the *calling app's own* grants (`permissionController.getGrantedPermissions()`); there is no API (and no permission you can request) to query another app's connection or read permissions. This is a platform privacy wall, not a Whiz misconfiguration.
- Because of that wall, `HealthConnectManager.unconnectedHealthApps()` uses a **proxy heuristic**: it scans Health Connect records from the last 180 days and treats an app as "connected" if its package appears as a record's `dataOrigin`. This is unreliable in two ways:
  1. *Writing ≠ reading.* An app that writes weight (e.g. Google Health historically did) is counted "connected" even though it does not read nutrition.
  2. *Stale data.* Records written before the app was disconnected still carry its `dataOrigin`, so a 180-day-old write makes a now-disconnected app look connected.
- When the proxy false-positives, the `requires_connection` warning in `ToolExecutor.kt` is **suppressed** (logcat: `Skipping unconnected-apps prompt — non-Whiz HC writers present: [...]`) and the user gets a clean "Logged ✅" for data that is invisible in their app.
- **App updates silently revoke Health Connect grants.** The Fitbit → "Google Health" in-place rebrand (`com.fitbit.FitbitMobile`) reset its Health Connect permissions on update; the user had connected it before, but the grant was wiped. Re-granting is manual (below).
- Even when connected, Google Health's "Calories intake" may be its own food-log silo that does not ingest Health Connect nutrition. Re-granting Nutrition read is the right first step, but may still not surface Whiz-logged calories — that would be a Google Health limitation, not a Whiz bug.

### Connecting a health app: `agent_open_health_app_settings`

Use this tool to help the user connect a consuming app to Health Connect. Two scenarios: (1) follow-up when `agent_log_health_data` returns `reason: "requires_connection"` + an `unconnected_health_apps` array; (2) the user directly asks to connect a health app (e.g. "connect Google Health"). Do **not** use `agent_launch_app` for this — this tool's dialog flow is the correct path.

What it does: shows an in-app confirmation dialog, then on confirm fires the `android.health.connect.action.HEALTH_HOME_SETTINGS` intent (`MainActivity.kt`), landing on Health Connect's **"Your health apps"** list. It opens Health Connect itself, **not** the third-party app — the user picks which app to connect from there.

### The manual grant flow the user must complete (and the gotcha)

After the tool opens Health Connect, the user has to drill in — and it is **not obvious from the screens that permissions are off by default**:

1. **"Your health apps"** list — the consuming app (e.g. "Health" = Google Health, `com.fitbit.FitbitMobile`) may show **"Not connected"** even if it previously had access (e.g. reset by an app update). This list label is the authoritative connection status.
2. Tap the app → **"App access"** screen. This shows permission **categories**, not individual data types: "Fitness and wellness" (exercise, sleep, **nutrition**, …), "Medical records", "Additional access" (past data, background data).
3. **GOTCHA — you must tap INTO each category and explicitly allow the data types.** From the "App access" screen it is *not* clear that the categories are currently denied. Merely viewing it grants nothing. The user must open **each** category ("Fitness and wellness", "Medical records", "Additional access") and turn on the relevant toggles (for calories: **Nutrition** read). When guiding a user, tell them explicitly to click into each category and allow — don't let them assume the first screen connected it.
4. **"Remove access for this app"** appears on the "App access" screen even when nothing meaningful is granted, so its presence does **not** mean the app is connected. Trust the "Not connected" label on the list, not this button.

## Message Ordering and Timestamp Constraints

To ensure proper conversation history when messages are saved to the database and loaded back, the following constraints MUST be maintained:

### Timestamp Rules for Messages with Same request_id

All messages in a request/response cycle share the same `request_id`. Timestamps must be carefully managed to ensure proper ordering:

1. **Base Rule**: ASSISTANT messages with the same `request_id` need to have timestamps that are +1ms after the USER message timestamp
   - This ensures responses appear immediately after the user message they're responding to

2. **Tool Use Flow with Placeholder tool_result**:
   - When an ASSISTANT message contains tool_use blocks, we create a placeholder tool_result immediately to allow conversation to continue
   - **USER message** (original): timestamp T (e.g., .464)
   - **ASSISTANT text_before** (if any): T+1ms (e.g., .465)
   - **ASSISTANT tool_use**: T+2ms (e.g., .466)
   - **USER placeholder tool_result**: T+3ms (e.g., .467) - with content "Result pending..."
   - **ASSISTANT text_after**: T+4ms (e.g., .468)

3. **Real tool_result Replacement**:
   - When the actual tool execution completes, the real tool_result MUST replace the placeholder
   - **CRITICAL**: The timestamp of the placeholder (T+3ms) MUST be preserved when replacing with the real result
   - This ensures the final ASSISTANT text (T+4ms) remains after the tool_result

4. **Multiple Tool Uses**:

   **Parallel** (Claude returns multiple tool_uses in ONE response):
   - ASSISTANT: [text, tool_use_A, tool_use_B] at T+1ms
   - USER: [tool_result_A, tool_result_B] at T+2ms (grouped together)
   - Tool_results matched by `tool_use_id`, executed in parallel

   **Sequential** (Claude calls one tool, waits, then calls another):
   - ASSISTANT tool_use_A: T+1ms
   - USER tool_result_A: T+2ms
   - ASSISTANT tool_use_B: T+3ms (new Claude response)
   - USER tool_result_B: T+4ms

### Message Merging Rules

Claude API requires strict user/assistant alternation. Messages must be merged to maintain this:

1. **ASSISTANT Messages with Text and Tool Use**:
   - Text content MUST come before tool_use blocks in the same message
   - All content from the same ASSISTANT turn must be merged into a single message
   - Example: `{"role": "assistant", "content": [{"type": "text", "text": "..."}, {"type": "tool_use", ...}]}`

2. **Consecutive USER Messages**:
   - USER messages in a row (between ASSISTANT messages) MUST be merged together
   - This can happen when multiple user inputs or tool_results arrive before the next ASSISTANT response
   - If a tool_result and text arrive together, tool_result MUST come first, then text
   - Example: `{"role": "user", "content": [{"type": "tool_result", ...}, {"type": "text", "text": "..."}]}`

### Implementation Notes

- The `save_message_to_db()` function in `whizvoice/database.py` handles timestamp management
- The `load_conversation_history()` function in `whizvoice/database.py` handles message merging when loading from database
- Tool messages (tool_use, tool_result) are stored in the database but filtered out when syncing to Android client (which only shows text messages)
- Redis cache maintains the full conversation history including tool messages for server-side Claude API calls
