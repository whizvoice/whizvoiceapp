#!/bin/bash

# WhizVoice Debug Installation Script
# This installs a debug version with package name com.example.whiz.debug
# so it won't interfere with your regular app installation

set -e

echo "🔨 Building WhizVoice Debug version..."
./gradlew assembleDebug

echo "📱 Installing debug APK..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "✅ Debug version installed successfully!"
echo ""
echo "📋 You now have two versions:"
echo "   • WhizVoice (com.example.whiz) - Your regular app"
echo "   • WhizVoice Debug (com.example.whiz.debug) - For testing"
echo ""
echo "🧪 To run tests on the debug version:"
echo "   ./run_tests_on_debug.sh"
echo ""
echo "🗑️  To uninstall debug version later:"
echo "   adb uninstall com.example.whiz.debug" 