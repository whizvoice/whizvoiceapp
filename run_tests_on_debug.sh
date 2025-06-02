#!/bin/bash

# Run tests on WhizVoice Debug version
# This targets the debug build variant to avoid interfering with your regular app

set -e

echo "🧪 Running tests on WhizVoice Debug (com.example.whiz.debug)..."
echo ""

# Check if debug version is installed
if ! adb shell pm list packages | grep -q "com.example.whiz.debug"; then
    echo "❌ Debug version not found. Please install it first:"
    echo "   ./install_debug_for_testing.sh"
    exit 1
fi

echo "✅ Debug version found"
echo ""

# Run unit tests first (fast)
echo "🔬 Running unit tests..."
./gradlew testDebugUnitTest
echo ""

# Run instrumented tests on debug build
echo "📱 Running instrumented tests on debug build..."
./gradlew connectedDebugAndroidTest

echo ""
echo "✅ All tests completed!"
echo ""
echo "📊 Test reports available at:"
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html" 