#!/bin/bash

echo "🧪 Testing Improved Test Classes"
echo "================================="

# Check if device is connected
echo "📱 Checking for connected devices..."
adb devices

DEVICE_COUNT=$(adb devices | grep -v "List of devices attached" | grep -v "^$" | wc -l)

if [ $DEVICE_COUNT -eq 0 ]; then
    echo "❌ No devices connected. Please connect an Android device or start an emulator."
    echo "💡 To connect a device:"
    echo "   1. Enable USB Debugging on your Android device"
    echo "   2. Connect via USB cable"
    echo "   3. Accept the USB debugging dialog"
    echo "💡 To start an emulator:"
    echo "   1. Android Studio > AVD Manager > Start emulator"
    echo "   2. Or use: emulator -avd YOUR_AVD_NAME"
    exit 1
fi

echo "✅ Found $DEVICE_COUNT connected device(s)"
echo ""

# Test our improved test classes one by one
echo "🔬 Testing ChatsListScreenTest (improved)..."
./gradlew app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.ui.screens.ChatsListScreenTest

echo ""
echo "🔬 Testing SettingsScreenTest (improved)..."  
./gradlew app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.ui.screens.SettingsScreenTest

echo ""
echo "🔬 Testing ChatScreenTest (improved)..."
./gradlew app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.ui.screens.ChatScreenTest

echo ""
echo "🔬 Testing TestModulesTest (dependency injection)..."
./gradlew app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.di.TestModulesTest

echo ""
echo "🎉 Test run completed!"
echo "📋 Check the output above for any failures or issues." 