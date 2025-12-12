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

## Testing

### Standard Integration Tests

You can run tests with run_tests_on_debug.sh script from whizvoiceapp . Note that often you will want to run a specific test with the --test option (check --help for more details) and use the option to skip unit tests. e.g.

```
cd whizvoiceapp && ./run_tests_on_debug.sh --skip-unit --test "com.example.whiz.integration.ChatLoadErrorTest#test500Error_ShowsErrorUI_and_check_retry_button"
```

the tests take a long time so please run it with your max timeout (10mins)

**IMPORTANT:** Always run tests without timeout as they can take several minutes to complete. Also, there is no need to build with Gradle or install the app before testing; the test will build and install already, so just run the test directly.

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

## Database

Please refer to whizvoiceapp/.supabaseinfo for info about what's in the database and what functions are in the database.

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
   - If there are additional tool_use and tool_result pairs before the next USER text message, timestamps continue incrementing:
   - Second tool_use: T+5ms
   - Second placeholder tool_result: T+6ms
   - Final ASSISTANT text: T+7ms

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
