#!/bin/bash

# Run tests on Android Emulator
# This runs all tests on an emulator instead of your physical device

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
    echo "   Add to PATH: export PATH=\$PATH:\$ANDROID_HOME/emulator"
    exit 1
fi

# Function to check if any emulator is running
check_emulator_running() {
    adb devices | grep -q "emulator.*device"
}

# Function to list available emulators
list_emulators() {
    echo "📱 Available emulators:"
    emulator -list-avds | while read -r avd; do
        if [ -n "$avd" ]; then
            echo "   • $avd"
        fi
    done
}

# Check if an emulator is already running
if check_emulator_running; then
    echo "✅ Emulator is running"
    EMULATOR_DEVICE=$(adb devices | grep "emulator" | head -1 | cut -f1)
    echo "   Using: $EMULATOR_DEVICE"
else
    echo "❌ No emulator is currently running"
    echo ""
    
    # List available emulators
    AVAILABLE_EMULATORS=$(emulator -list-avds)
    if [ -z "$AVAILABLE_EMULATORS" ]; then
        echo "❌ No emulators found. Please create one first:"
        echo "   1. Open Android Studio"
        echo "   2. Go to Tools > AVD Manager"
        echo "   3. Create a new Virtual Device"
        exit 1
    fi
    
    list_emulators
    echo ""
    echo "🚀 Options:"
    echo "   1. Start an emulator manually: emulator -avd <emulator_name>"
    echo "   2. Auto-start the first available emulator"
    echo ""
    
    read -p "Would you like to auto-start the first emulator? (y/N): " AUTO_START
    if [[ $AUTO_START =~ ^[Yy]$ ]]; then
        FIRST_EMULATOR=$(emulator -list-avds | head -1)
        if [ -n "$FIRST_EMULATOR" ]; then
            echo "🚀 Starting emulator: $FIRST_EMULATOR"
            echo "   This may take a few minutes..."
            emulator -avd "$FIRST_EMULATOR" -no-snapshot-save -no-boot-anim &
            EMULATOR_PID=$!
            
            echo "⏳ Waiting for emulator to boot..."
            # Wait for emulator to be ready
            timeout=300  # 5 minutes timeout
            elapsed=0
            while ! check_emulator_running && [ $elapsed -lt $timeout ]; do
                sleep 5
                elapsed=$((elapsed + 5))
                echo "   Still waiting... (${elapsed}s)"
            done
            
            if check_emulator_running; then
                echo "✅ Emulator is ready!"
                EMULATOR_DEVICE=$(adb devices | grep "emulator" | head -1 | cut -f1)
            else
                echo "❌ Emulator failed to start within $timeout seconds"
                kill $EMULATOR_PID 2>/dev/null || true
                exit 1
            fi
        fi
    else
        echo "❌ Please start an emulator first and then run this script again"
        exit 1
    fi
fi

echo ""

# Wait a bit more for emulator to be fully ready
echo "⏳ Ensuring emulator is fully ready..."
sleep 10

# Build and install the latest debug APK
echo "🔨 Building latest debug version..."
./gradlew assembleDebug

echo "📱 Installing latest debug APK on emulator..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
echo "✅ Latest debug version installed on emulator"
echo ""

# Run unit tests first (fast, don't need device for this part)
echo "🔬 Running unit tests with latest code..."
./gradlew testDebugUnitTest
echo ""

# Run instrumented tests on emulator
echo "📱 Running instrumented tests on emulator with latest code..."
echo "   Target device: $EMULATOR_DEVICE"
./gradlew connectedDebugAndroidTest

echo ""
echo "✅ All tests completed on emulator with latest changes!"
echo ""
echo "📊 Test reports available at:"
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html"
echo ""
echo "🛑 To stop the emulator:"
echo "   adb -s $EMULATOR_DEVICE emu kill" 