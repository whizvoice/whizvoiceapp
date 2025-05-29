# Real Authentication Testing Guide

This guide explains how to set up and run tests with real Google authentication using test credentials.

## Overview

The app now supports two types of authentication testing:

1. **Mock Authentication** (default) - Fast, reliable, no external dependencies
2. **Real Authentication** - Uses actual Google Sign-In with test credentials

## Setup

### 1. Create Test Credentials

Create a `test_credentials.json` file in the project root:

```json
{
  "google_test_account": {
    "email": "your-test-email@gmail.com",
    "password": "your-test-password",
    "display_name": "Test User",
    "user_id": "test_user_123"
  },
  "test_environment": {
    "use_real_auth": true,
    "api_base_url": "https://api-test.whizapp.com",
    "claude_api_key": "test_claude_key",
    "asana_token": "test_asana_token"
  }
}
```

**Important**: This file is automatically ignored by git and will not be committed.

### 2. Set up Test Account

1. Create a dedicated Google account for testing
2. Enable 2-factor authentication (recommended)
3. Add the account to your test device/emulator
4. Ensure the account has necessary permissions for your app

### 3. Run Setup Script

```bash
./setup_test_credentials.sh
```

This script will:

- Validate your credentials file
- Check if emulator is running
- Push credentials to the emulator
- Verify the setup

## Running Tests

### All Tests (Mock + Real Auth)

```bash
./gradlew connectedDebugAndroidTest
```

### Only Real Authentication Tests

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.ui.screens.LoginScreenRealAuthTest
```

### Only Mock Authentication Tests

```bash
./gradlew connectedDebugAndroidTest --tests "*Test" -x "*RealAuthTest"
```

## Test Behavior

### With Real Credentials

- Tests marked with `@Assume` will run real authentication flows
- Uses actual Google Sign-In dialogs
- Interacts with real Google services
- Slower but more realistic

### Without Real Credentials

- Real auth tests are automatically skipped
- Falls back to mock authentication
- Fast and reliable
- Good for CI/CD pipelines

## Test Structure

### TestCredentialsManager

- Loads credentials from JSON file
- Provides fallback to mock credentials
- Handles file not found gracefully

### LoginScreenRealAuthTest

- Contains real authentication test cases
- Uses `UiAutomator` for system-level interactions
- Automatically skips if no real credentials

### GoogleSignInAutomator

- Helper class for automating Google Sign-In flow
- Handles account selection
- Manages permission dialogs

## Security Considerations

1. **Never commit test credentials** - They're in `.gitignore`
2. **Use dedicated test accounts** - Don't use personal accounts
3. **Rotate credentials regularly** - Change passwords periodically
4. **Limit permissions** - Test accounts should have minimal necessary permissions
5. **Monitor usage** - Watch for unexpected activity on test accounts

## Troubleshooting

### Credentials Not Found

```
❌ test_credentials.json not found!
```

**Solution**: Create the credentials file as shown in setup section.

### Invalid JSON Format

```
❌ test_credentials.json is not valid JSON!
```

**Solution**: Validate JSON syntax using an online validator or IDE.

### Emulator Not Running

```
❌ No emulator detected!
```

**Solution**: Start an emulator:

```bash
export ANDROID_HOME=~/Library/Android/sdk
$ANDROID_HOME/emulator/emulator -avd Pixel_8 &
```

### Google Sign-In Fails

- Ensure test account is added to emulator
- Check if account requires 2FA setup
- Verify app's OAuth configuration
- Check Google Play Services on emulator

### Tests Skipped

```
Real auth tests require valid test credentials
```

**Solution**: This is expected behavior when no real credentials are available. Tests fall back to mock auth.

## CI/CD Integration

For GitHub Actions or other CI systems:

1. **Don't use real auth in CI** - Too slow and unreliable
2. **Use mock auth for CI** - Fast and deterministic
3. **Run real auth tests manually** - For integration testing
4. **Consider separate test environments** - Different configs for different test types

## Best Practices

1. **Layer your testing**:

   - Unit tests (90%) - Mock everything
   - Integration tests (9%) - Real auth, test environment
   - E2E tests (1%) - Real auth, real environment

2. **Use real auth for**:

   - Critical authentication flows
   - Integration testing
   - Manual QA testing
   - Pre-release validation

3. **Use mock auth for**:
   - Unit tests
   - CI/CD pipelines
   - Development testing
   - Edge case testing

## Example Test Output

```
✅ Found test_credentials.json
✅ JSON format is valid
✅ Emulator detected
📱 Pushing credentials to emulator...
✅ Credentials pushed successfully
✅ Credentials verified on emulator
🎉 Test credentials setup complete!
```

## File Structure

```
whizvoiceapp/
├── test_credentials.json          # Your test credentials (ignored by git)
├── setup_test_credentials.sh      # Setup script
├── REAL_AUTH_TESTING.md           # This documentation
└── app/src/androidTest/java/com/example/whiz/
    ├── TestCredentials.kt         # Credentials management
    └── ui/screens/
        └── LoginScreenRealAuthTest.kt  # Real auth tests
```
