#!/bin/bash

echo "🧪 Testing GitHub Actions CI Logic Locally"
echo "=========================================="

# Simulate the exact same logic as GitHub Actions
echo "🚀 Starting instrumented tests with direct ADB approach..."

# Wait for emulator to be fully ready
adb wait-for-device shell 'while [[ -z $(getprop sys.boot_completed | tr -d '\r') ]]; do sleep 1; done; input keyevent 82'

# Show device info for debugging
echo "📱 Device Info:"
echo "Model: $(adb shell getprop ro.product.model)"
echo "API Level: $(adb shell getprop ro.build.version.sdk)"
echo "Release: $(adb shell getprop ro.build.version.release)"

# Build APKs for direct execution
echo "🔨 Building APKs for direct execution..."
./gradlew assembleDebug assembleDebugAndroidTest --parallel --build-cache --configuration-cache

if [ $? -ne 0 ]; then
  echo "❌ APK build failed"
  exit 1
fi

# Install APKs manually
echo "📦 Installing APKs..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Run tests directly via ADB (excluding real auth tests for CI)
echo "🎯 Running instrumented tests via direct ADB..."
adb shell am instrument -w -r -e notClass com.example.whiz.ui.screens.LoginScreenRealAuthTest com.example.whiz.test/com.example.whiz.HiltTestRunner > test_results.txt 2>&1

# Check results
if grep -q "OK (" test_results.txt; then
  PASSED_TESTS=$(grep "OK (" test_results.txt | sed 's/.*OK (\([0-9]*\) tests).*/\1/')
  echo "✅ Direct ADB tests completed: $PASSED_TESTS tests passed"
  
  # Create JUnit XML for dorny/test-reporter
  mkdir -p app/build/outputs/androidTest-results/connected/
  echo '<?xml version="1.0" encoding="UTF-8"?>' > app/build/outputs/androidTest-results/connected/TEST-direct-adb.xml
  echo '<testsuite name="DirectADBTests" tests="'$PASSED_TESTS'" failures="0" errors="0" time="0" package="com.example.whiz">' >> app/build/outputs/androidTest-results/connected/TEST-direct-adb.xml
  echo '  <testcase name="instrumented_tests_via_adb" classname="DirectADBExecution" time="0"/>' >> app/build/outputs/androidTest-results/connected/TEST-direct-adb.xml
  echo '</testsuite>' >> app/build/outputs/androidTest-results/connected/TEST-direct-adb.xml
  
  cat test_results.txt
  exit 0
else
  echo "❌ Direct ADB tests failed"
  cat test_results.txt
  exit 1
fi 