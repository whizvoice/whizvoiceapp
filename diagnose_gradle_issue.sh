#!/bin/bash

echo "🔍 Gradle Device Detection Diagnosis"
echo "===================================="

echo ""
echo "📱 Device Information:"
echo "Device: $(adb shell getprop ro.product.model)"
echo "API Level: $(adb shell getprop ro.build.version.sdk)"
echo "Release: $(adb shell getprop ro.build.version.release)"
echo "ABI: $(adb shell getprop ro.product.cpu.abi)"

echo ""
echo "🔧 ADB Status:"
adb devices -l

echo ""
echo "🏗️ Gradle Device Detection (Dry Run):"
echo "Running: ./gradlew connectedDebugAndroidTest --dry-run"
./gradlew connectedDebugAndroidTest --dry-run 2>&1 | head -20

echo ""
echo "🎯 Gradle Device Detection (Info Level):"
echo "Running: ./gradlew connectedDebugAndroidTest --info --dry-run"
./gradlew connectedDebugAndroidTest --info --dry-run 2>&1 | grep -E "(device|emulator|API|compatible|android)" | head -10

echo ""
echo "🧪 Android Gradle Plugin Version:"
grep 'com.android.application' app/build.gradle.kts || grep 'com.android.application' build.gradle*

echo ""
echo "📋 Gradle Properties:"
cat gradle.properties | grep -E "(android|heap|parallel)"

echo ""
echo "🔍 Potential Issues:"
echo "1. Android Release 16 (API 36) is a preview/development version"
echo "2. Gradle might not recognize this unusual version combination"
echo "3. The Android Gradle Plugin might need updating for API 36 support"

echo ""
echo "💡 Workarounds:"
echo "1. Use run_tests_direct.sh (bypasses Gradle device detection)"
echo "2. Use a stable emulator (API 34 with Android 14)"
echo "3. Update Android Gradle Plugin if available" 