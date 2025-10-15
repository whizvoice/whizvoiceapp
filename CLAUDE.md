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

**IMPORTANT:** Always run tests without timeout as they can take several minutes to complete.

When investigating test failures, check these log files for detailed information:

- **test_gradle_output.log** - Gradle build output and test execution results
- **test_logcat_output.log** - Android system logs during test execution (key for debugging runtime issues)
- **test_summary.log** - High-level test results and summaries

Crucially, screenshots from failed tests will show up in whizvoiceapp/test_screenshots directory

### Screen Agent Tests

To run screen agent integration tests (which use ~/android_screenshot_testing/android_accessibility_tester.py):

**IMPORTANT:** You must source the API key file before running screen agent tests:

```
cd whizvoiceapp && source export_anthropic_key.sh && ./venv/bin/pytest run_screen_agent_tests.py
```

Test output for screen agent tests is stored in whizvoiceapp/screen_agent_test_output directory:
- **screen_agent_logcat.log** - Android logcat output during screen agent test execution
- Screenshots from tests are also saved in this directory

## Database

Please refer to whizvoiceapp/.supabaseinfo for info about what's in the database and what functions are in the database.
