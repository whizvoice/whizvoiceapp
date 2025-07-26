# Claude Context for WhizVoice Project

This project has two folders: whizvoice which has the webapp code, and whizvoiceapp which has the Android app code.

## Guidelines

- Only update this CLAUDE.md file when explicitly asked by the user
- Don't do git operations; user prefers to do it themselves
- Don't try to run the webserver. It's on a different machine.

## Testing

You can run tests with run_tests_on_debug.sh script. Note that often you will want to run a specific test with the --test option (check --help for more details) and use the option to skip unit tests.

When investigating test failures, check these log files for detailed information:

- **test_gradle_output.log** - Gradle build output and test execution results
- **test_logcat_output.log** - Android system logs during test execution (key for debugging runtime issues)
- **test_summary_output.log** - High-level test results and summaries

## Database

Please refer to whizvoiceapp/.supabaseinfo for info about what's in the database and what functions are in the database.
