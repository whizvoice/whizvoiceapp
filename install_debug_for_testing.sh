#!/bin/bash

# Install WhizVoice Debug version for manual testing
# This script builds and installs the latest debug version 
# It's designed to be run independently or after test failures

set -e

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1"
}

log_with_time "📱 Installing WhizVoice Debug App..."

# Build the latest debug version
log_with_time "⏳ Building latest debug version..."
if ./gradlew assembleDebug --console=plain --quiet; then
    log_with_time "✅ Debug build completed successfully"
else
    log_with_time "❌ Debug build failed"
    exit 1
fi

# Install the debug APK
log_with_time "⏳ Installing debug APK..."
if adb install -r app/build/outputs/apk/debug/app-debug.apk; then
    log_with_time "✅ Debug app installed successfully"
else
    log_with_time "❌ Debug app installation failed"
    exit 1
fi

# Grant necessary permissions
log_with_time "⏳ Granting microphone permission..."
adb shell pm grant com.example.whiz.debug android.permission.RECORD_AUDIO 2>/dev/null || true
log_with_time "✅ Permissions granted"

# Launch the app to verify installation
log_with_time "⏳ Starting app to verify installation..."
adb shell am start -n com.example.whiz.debug/com.example.whiz.MainActivity >/dev/null 2>&1 || true
sleep 2
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true

log_with_time "🎉 WhizVoice Debug app is ready for manual testing!"
log_with_time "📱 You can now open the app on your device" 