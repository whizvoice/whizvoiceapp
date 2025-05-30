#!/bin/bash

echo "🧪 Running Instrumented Tests Locally"
echo "======================================"

# Set up Android environment
export ANDROID_HOME=~/Library/Android/sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/tools:$PATH

echo "📱 Checking for connected devices..."
adb devices

# Check if any device is connected
DEVICE_COUNT=$(adb devices | grep -E "(device|emulator)" | grep -v "List of devices" | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ No devices connected."
    echo ""
    echo "To run instrumented tests, you need either:"
    echo "1. A connected Android device with USB debugging enabled"
    echo "2. A running Android emulator"
    echo ""
    echo "To start an emulator:"
    echo "  $ANDROID_HOME/emulator/emulator -avd Pixel_8 -no-audio -no-boot-anim"
    echo ""
    echo "Available AVDs:"
    $ANDROID_HOME/emulator/emulator -list-avds
    exit 1
fi

echo "✅ Found $DEVICE_COUNT device(s)"

# Wait for device to be ready
echo "⏳ Waiting for device to be ready..."
adb wait-for-device

# Check if device is fully booted (for emulators)
echo "🔍 Checking boot status..."
BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null || echo "")
if [ "$BOOT_COMPLETED" != "1" ]; then
    echo "⏳ Device is still booting, waiting..."
    while [ "$BOOT_COMPLETED" != "1" ]; do
        sleep 5
        BOOT_COMPLETED=$(adb shell getprop sys.boot_completed 2>/dev/null || echo "")
        echo "   Boot status: $BOOT_COMPLETED"
    done
fi

echo "✅ Device is ready!"

# Run the instrumented tests
echo "🧪 Running instrumented tests..."
./gradlew connectedDebugAndroidTest --stacktrace

# Check results
if [ $? -eq 0 ]; then
    echo "✅ Instrumented tests completed successfully!"
    echo "📊 Test report: app/build/reports/androidTests/connected/index.html"
else
    echo "❌ Instrumented tests failed!"
    exit 1
fi 