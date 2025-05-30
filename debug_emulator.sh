#!/bin/bash

echo "🔍 Emulator Debug Information"
echo "============================"

echo ""
echo "📱 Connected Devices:"
adb devices -l

echo ""
echo "🔧 Device Properties:"
echo "SDK API Level: $(adb shell getprop ro.build.version.sdk)"
echo "Release Version: $(adb shell getprop ro.build.version.release)"
echo "Product Model: $(adb shell getprop ro.product.model)"
echo "Hardware: $(adb shell getprop ro.hardware)"
echo "ABI: $(adb shell getprop ro.product.cpu.abi)"

echo ""
echo "🏗️ Build Information:"
echo "Build ID: $(adb shell getprop ro.build.id)"
echo "Build Type: $(adb shell getprop ro.build.type)"
echo "Build Tags: $(adb shell getprop ro.build.tags)"

echo ""
echo "📦 Package Manager Test:"
if adb shell pm list packages | grep -q com.example.whiz; then
    echo "✅ App is installed"
    echo "App version: $(adb shell dumpsys package com.example.whiz | grep versionName | head -1)"
else
    echo "❌ App is not installed"
fi

echo ""
echo "🧪 Test APK Check:"
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    echo "✅ Debug APK exists"
    echo "APK size: $(ls -lh app/build/outputs/apk/debug/app-debug.apk | awk '{print $5}')"
else
    echo "❌ Debug APK not found"
fi

if [ -f "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" ]; then
    echo "✅ Test APK exists"
    echo "Test APK size: $(ls -lh app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | awk '{print $5}')"
else
    echo "❌ Test APK not found"
fi

echo ""
echo "🔌 ADB Connection Test:"
if adb shell echo "Connection test" > /dev/null 2>&1; then
    echo "✅ ADB connection working"
else
    echo "❌ ADB connection failed"
fi

echo ""
echo "🎯 Manual Installation Test:"
echo "Attempting to install debug APK..."
if adb install -r app/build/outputs/apk/debug/app-debug.apk > /dev/null 2>&1; then
    echo "✅ Manual APK installation successful"
else
    echo "❌ Manual APK installation failed"
fi

echo ""
echo "🧪 Gradle Test Detection:"
echo "Running Gradle device detection..."
./gradlew connectedDebugAndroidTest --dry-run 2>&1 | grep -E "(device|emulator|API|compatible)" | head -5 