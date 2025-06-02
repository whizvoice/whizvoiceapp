#!/bin/bash

# Check installed WhizVoice versions on connected device

echo "📱 Checking installed WhizVoice versions..."
echo ""

# Check if adb is available
if ! command -v adb &> /dev/null; then
    echo "❌ ADB not found. Please install Android SDK tools."
    exit 1
fi

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected or device offline."
    echo "   Please connect your device and enable USB debugging."
    exit 1
fi

echo "✅ Device connected"
echo ""

# Check for WhizVoice apps
PRODUCTION_APP=$(adb shell pm list packages | grep "com.example.whiz$" | head -1)
DEBUG_APP=$(adb shell pm list packages | grep "com.example.whiz.debug$" | head -1)

echo "🔍 Installed WhizVoice versions:"
echo ""

if [ -n "$PRODUCTION_APP" ]; then
    # Get version info for production app
    PROD_VERSION=$(adb shell dumpsys package com.example.whiz | grep versionName | head -1 | cut -d'=' -f2)
    echo "✅ Production App: com.example.whiz"
    echo "   📱 App Name: Whiz Voice"
    echo "   📦 Version: $PROD_VERSION"
    echo "   🎯 Usage: Your regular daily app"
else
    echo "❌ Production App: Not installed"
fi

echo ""

if [ -n "$DEBUG_APP" ]; then
    # Get version info for debug app
    DEBUG_VERSION=$(adb shell dumpsys package com.example.whiz.debug | grep versionName | head -1 | cut -d'=' -f2)
    echo "✅ Debug App: com.example.whiz.debug"
    echo "   📱 App Name: 🧪 WhizVoice DEBUG"
    echo "   📦 Version: $DEBUG_VERSION"
    echo "   🧪 Usage: Testing and development"
else
    echo "❌ Debug App: Not installed"
    echo "   💡 Install with: ./install_debug_for_testing.sh"
fi

echo ""

if [ -n "$PRODUCTION_APP" ] && [ -n "$DEBUG_APP" ]; then
    echo "🎉 Perfect! You have both versions installed for safe testing."
    echo ""
    echo "🚀 Quick actions:"
    echo "   • Test with latest changes: ./test_with_latest_changes.sh"
    echo "   • Update debug version: ./test_with_latest_changes.sh --manual"
    echo "   • Remove debug version: adb uninstall com.example.whiz.debug"
elif [ -n "$PRODUCTION_APP" ] && [ -z "$DEBUG_APP" ]; then
    echo "💡 You have your production app but no debug version yet."
    echo "   Install debug version: ./install_debug_for_testing.sh"
elif [ -z "$PRODUCTION_APP" ] && [ -n "$DEBUG_APP" ]; then
    echo "⚠️  You only have the debug version installed."
    echo "   This is fine for testing, but install the production version from the Play Store for daily use."
else
    echo "🆕 No WhizVoice apps installed yet."
    echo "   Install debug version: ./install_debug_for_testing.sh"
fi 