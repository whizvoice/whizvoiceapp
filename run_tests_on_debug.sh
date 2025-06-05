#!/bin/bash

# Run tests on WhizVoice Debug version with latest changes
# This builds, installs latest debug version, then runs all tests
# All output is logged to test_output.log for monitoring

set -e

# Clear previous log file
> test_output.log

echo "🧪 Running tests on WhizVoice Debug with latest changes..." | tee -a test_output.log
echo "" | tee -a test_output.log

# Function to run command with logging
run_with_log() {
    echo "⏳ $1" | tee -a test_output.log
    eval "$2" 2>&1 | tee -a test_output.log
    echo "" | tee -a test_output.log
}

# Build the latest debug APK
run_with_log "Building latest debug version..." "./gradlew assembleDebug --console=plain"

# Install the latest debug APK
run_with_log "Installing latest debug APK..." "adb install -r app/build/outputs/apk/debug/app-debug.apk"

echo "✅ Latest debug version installed" | tee -a test_output.log
echo "" | tee -a test_output.log

# Run unit tests first (fast, always use latest code)
run_with_log "Running unit tests with latest code..." "./gradlew testDebugUnitTest --console=plain"

# Run instrumented tests on debug build
run_with_log "Running instrumented tests on latest debug build..." "./gradlew connectedDebugAndroidTest --console=plain"

echo "" | tee -a test_output.log
echo "✅ All tests completed with latest changes!" | tee -a test_output.log
echo "" | tee -a test_output.log
echo "📊 Test reports available at:" | tee -a test_output.log
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html" | tee -a test_output.log
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html" | tee -a test_output.log
echo "" | tee -a test_output.log
echo "📱 Latest debug app (🧪 WhizVoice DEBUG) is ready for manual testing too!" | tee -a test_output.log
echo "" | tee -a test_output.log
echo "📋 Full test log saved to: test_output.log" | tee -a test_output.log 