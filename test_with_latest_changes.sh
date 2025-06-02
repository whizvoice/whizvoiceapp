#!/bin/bash

# Test with latest changes - builds, installs if needed, and runs tests
set -e

echo "🚀 Testing WhizVoice with latest changes..."
echo ""

# Parse command line arguments
MANUAL_TEST=false
SKIP_INSTALL=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --manual)
            MANUAL_TEST=true
            shift
            ;;
        --skip-install)
            SKIP_INSTALL=true
            shift
            ;;
        *)
            echo "Usage: $0 [--manual] [--skip-install]"
            echo "  --manual      Also install debug APK for manual testing"
            echo "  --skip-install Skip debug APK installation completely"
            exit 1
            ;;
    esac
done

# Build the debug APK (always do this to ensure tests use latest code)
echo "🔨 Building latest debug version..."
./gradlew assembleDebug

# Install debug APK if requested or if doing manual testing
if [[ "$SKIP_INSTALL" == false ]]; then
    if [[ "$MANUAL_TEST" == true ]]; then
        echo "📱 Installing debug APK for manual testing..."
        adb install -r app/build/outputs/apk/debug/app-debug.apk
        echo "✅ Debug app installed - you can now test manually"
        echo ""
    else
        # Check if debug version exists, install if not
        if ! adb shell pm list packages | grep -q "com.example.whiz.debug"; then
            echo "📱 Installing debug APK (first time)..."
            adb install -r app/build/outputs/apk/debug/app-debug.apk
        else
            echo "ℹ️  Debug version already installed (skipping reinstall for testing)"
            echo "   Use --manual flag if you want to reinstall for manual testing"
        fi
    fi
fi

echo ""

# Run unit tests first (fast, always use latest code)
echo "🔬 Running unit tests with latest code..."
./gradlew testDebugUnitTest
echo ""

# Run instrumented tests (Gradle will automatically install latest test APK)
echo "📱 Running instrumented tests with latest code..."
echo "   (Gradle will automatically build and install latest test APK)"
./gradlew connectedDebugAndroidTest

echo ""
echo "✅ All tests completed with latest changes!"
echo ""
echo "📊 Test reports:"
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html"

if [[ "$MANUAL_TEST" == true ]]; then
    echo ""
    echo "📱 Debug app is installed and ready for manual testing"
    echo "   Look for 'WhizVoice Debug' in your app drawer"
fi 