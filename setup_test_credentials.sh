#!/bin/bash

echo "🔐 Setting up Test Credentials for Real Authentication"
echo "=================================================="

# Check if test_credentials.json exists
if [ ! -f "test_credentials.json" ]; then
    echo "❌ test_credentials.json not found!"
    echo ""
    echo "Please create test_credentials.json with your real Google test account:"
    echo ""
    cat << 'EOF'
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
EOF
    echo ""
    echo "Then run this script again."
    exit 1
fi

echo "✅ Found test_credentials.json"

# Validate JSON format
if ! python3 -m json.tool test_credentials.json > /dev/null 2>&1; then
    echo "❌ test_credentials.json is not valid JSON!"
    echo "Please check the format and try again."
    exit 1
fi

echo "✅ JSON format is valid"

# Check if emulator is running
if ! adb devices | grep -q "emulator"; then
    echo "❌ No emulator detected!"
    echo "Please start an emulator first:"
    echo "  export ANDROID_HOME=~/Library/Android/sdk"
    echo "  \$ANDROID_HOME/emulator/emulator -avd Pixel_8 &"
    exit 1
fi

echo "✅ Emulator detected"

# Push credentials to emulator
echo "📱 Pushing credentials to emulator..."
adb push test_credentials.json /data/local/tmp/test_credentials.json

if [ $? -eq 0 ]; then
    echo "✅ Credentials pushed successfully"
else
    echo "❌ Failed to push credentials"
    exit 1
fi

# Verify credentials are accessible
echo "🔍 Verifying credentials on emulator..."
adb shell "cat /data/local/tmp/test_credentials.json" > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo "✅ Credentials verified on emulator"
else
    echo "❌ Could not verify credentials on emulator"
    exit 1
fi

echo ""
echo "🎉 Test credentials setup complete!"
echo ""
echo "You can now run real authentication tests:"
echo "  ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.ui.screens.LoginScreenRealAuthTest"
echo ""
echo "Or run all tests (including real auth tests):"
echo "  ./gradlew connectedDebugAndroidTest" 