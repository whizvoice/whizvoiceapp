#!/bin/bash

# Run tests on WhizVoice Debug version with latest changes
# This builds, installs latest debug version, then runs all tests

set -e

echo "🧪 Running tests on WhizVoice Debug with latest changes..."
echo ""

# Build the latest debug APK
echo "🔨 Building latest debug version..."
./gradlew assembleDebug

# Install the latest debug APK
echo "📱 Installing latest debug APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "✅ Latest debug version installed"
echo ""

# Run unit tests first (fast, always use latest code)
echo "🔬 Running unit tests with latest code..."
./gradlew testDebugUnitTest
echo ""

# Run instrumented tests on debug build
echo "📱 Running instrumented tests on latest debug build..."
./gradlew connectedDebugAndroidTest

echo ""
echo "✅ All tests completed with latest changes!"
echo ""
echo "📊 Test reports available at:"
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html"
echo ""
echo "📱 Latest debug app (🧪 WhizVoice DEBUG) is ready for manual testing too!" 