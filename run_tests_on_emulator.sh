#!/bin/bash

# Run tests on Android Emulator
# This runs tests on an emulator instead of your physical device.
# If no emulator is running, it auto-starts the first available AVD.
# Works even when a physical phone is also connected.
#
# Usage:
#   ./run_tests_on_emulator.sh                          # Run all tests
#   ./run_tests_on_emulator.sh --skip-unit              # Skip unit tests
#   ./run_tests_on_emulator.sh --skip-unit --test "com.example.whiz.integration.SomeTest#testMethod"

set -e

echo "🤖 Running WhizVoice tests on Android Emulator..."
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ ADB not found. Please install Android SDK tools."
    exit 1
fi

# Check if emulator command is available
if ! command -v emulator &> /dev/null; then
    echo "❌ Emulator command not found. Please ensure Android SDK is properly installed."
    echo "   Add to PATH: export PATH=\$PATH:/opt/homebrew/share/android-commandlinetools/emulator"
    exit 1
fi

# Parse command line arguments
SKIP_UNIT_TESTS=false
SINGLE_TEST=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-unit)
            SKIP_UNIT_TESTS=true
            shift
            ;;
        --test)
            SINGLE_TEST="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [--skip-unit] [--test <test_class_or_method>]"
            echo "Examples:"
            echo "  $0 --skip-unit --test com.example.whiz.integration.WebSocketReconnectionTest#testMultiChatOfflineMessaging"
            echo "  $0 --test com.example.whiz.integration.ChatViewModelIntegrationTest"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--skip-unit] [--test <test_class_or_method>]"
            exit 1
            ;;
    esac
done

# Helper: get the serial of a running emulator (e.g. emulator-5554)
get_emulator_serial() {
    adb devices | grep "^emulator-" | head -1 | cut -f1
}

# Function to check if any emulator is running
check_emulator_running() {
    adb devices | grep -q "^emulator-.*device"
}

# Function to wait for emulator to fully boot
wait_for_boot() {
    local timeout=300
    local elapsed=0

    echo "⏳ Waiting for emulator to appear in adb..."
    while ! check_emulator_running && [ $elapsed -lt $timeout ]; do
        sleep 5
        elapsed=$((elapsed + 5))
    done

    if ! check_emulator_running; then
        echo "❌ Emulator did not appear within ${timeout}s"
        return 1
    fi

    local serial
    serial=$(get_emulator_serial)
    echo "⏳ Waiting for $serial to finish booting..."
    adb -s "$serial" wait-for-device
    while [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ] && [ $elapsed -lt $timeout ]; do
        sleep 3
        elapsed=$((elapsed + 3))
    done

    if [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
        echo "❌ Emulator did not finish booting within ${timeout}s"
        return 1
    fi

    echo "✅ Emulator booted: $serial"
}

# Start or reuse emulator
if check_emulator_running; then
    EMULATOR_DEVICE=$(get_emulator_serial)
    echo "✅ Emulator already running: $EMULATOR_DEVICE"
else
    FIRST_EMULATOR=$(emulator -list-avds | head -1)
    if [ -z "$FIRST_EMULATOR" ]; then
        echo "❌ No AVDs found. Create one first (see README for setup instructions)."
        exit 1
    fi

    echo "🚀 Starting emulator: $FIRST_EMULATOR"
    emulator -avd "$FIRST_EMULATOR" -no-snapshot-save -no-boot-anim -memory 4096 &
    EMULATOR_PID=$!

    if ! wait_for_boot; then
        kill $EMULATOR_PID 2>/dev/null || true
        exit 1
    fi

    EMULATOR_DEVICE=$(get_emulator_serial)
fi

echo ""

# Build and install the latest debug APK
echo "🔨 Building latest debug version..."
./gradlew assembleDebug

echo "📱 Installing latest debug APK on emulator ($EMULATOR_DEVICE)..."
adb -s "$EMULATOR_DEVICE" install -r app/build/outputs/apk/debug/app-debug.apk
echo "✅ Latest debug version installed on emulator"
echo ""

# Unit tests
if [[ "$SKIP_UNIT_TESTS" == "true" ]]; then
    echo "⏭️  Skipping unit tests (--skip-unit)"
else
    echo "🔬 Running unit tests..."
    ./gradlew testDebugUnitTest
fi
echo ""

# Instrumented tests on emulator
echo "📱 Running instrumented tests on emulator..."
echo "   Target device: $EMULATOR_DEVICE"

GRADLE_CMD="./gradlew connectedDebugAndroidTest"
if [[ -n "$SINGLE_TEST" ]]; then
    # Fix common path typo
    if [[ "$SINGLE_TEST" == "com.example.whiz.WebSocketReconnectionTest"* ]]; then
        SINGLE_TEST="${SINGLE_TEST/com.example.whiz.WebSocketReconnectionTest/com.example.whiz.integration.WebSocketReconnectionTest}"
        echo "🔧 Fixed test path: $SINGLE_TEST"
    fi
    GRADLE_CMD="$GRADLE_CMD -Pandroid.testInstrumentationRunnerArguments.class=$SINGLE_TEST"
    echo "🎯 Running single test: $SINGLE_TEST"
fi

# Tell Gradle to only use the emulator, not the phone
export ANDROID_SERIAL="$EMULATOR_DEVICE"
$GRADLE_CMD

echo ""
echo "✅ All tests completed on emulator!"
echo ""
echo "📊 Test reports available at:"
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html"
echo ""
echo "🛑 To stop the emulator:"
echo "   adb -s $EMULATOR_DEVICE emu kill"
