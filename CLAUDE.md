# Claude Context for WhizVoice Project

This project has two folders: whizvoice which has the webapp code, and whizvoiceapp which has the Android app code.

On my phone, which is what I use for testing as well as running the production app, there are two apps installed: the production app and the debug app. You can update them with these scripts: whizvoiceapp/install_production_app.sh and whizvoiceapp/install_debug_for_testing.sh

## Guidelines

- Only update this CLAUDE.md file when explicitly asked by the user
- Don't do git operations; user prefers to do it themselves
- Don't try to run the webserver. It's on a different machine.

## Testing

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

## Database

Please refer to whizvoiceapp/.supabaseinfo for info about what's in the database and what functions are in the database.
