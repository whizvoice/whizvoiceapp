#!/bin/bash

# WhizVoice Production Installation Script
# This installs the production version (com.example.whiz) for your daily use
# while keeping the debug version (com.example.whiz.debug) for testing

set -e

echo "🏭 Building WhizVoice Production version for daily use..."
echo ""

# Build the release APK (now signed with debug key for local installation)
./gradlew assembleRelease

echo "📱 Installing production APK..."
adb install -r app/build/outputs/apk/release/app-release.apk

echo "✅ Production version installed successfully!"
echo ""
echo "📋 You now have both versions:"

# Check what's installed
if adb shell pm list packages | grep -q "com.example.whiz.debug"; then
    echo "   ✅ Debug version (🧪 WhizVoice DEBUG) - For testing"
else
    echo "   ❌ Debug version - Not installed (install with ./install_debug_for_testing.sh)"
fi

if adb shell pm list packages | grep -q "com.example.whiz$"; then
    echo "   ✅ Production version (Whiz Voice) - For daily use"
else
    echo "   ❌ Production version - Installation may have failed"
fi

echo ""
echo "🎯 Perfect setup:"
echo "   • Daily use: 'Whiz Voice' (normal colors, production features)"
echo "   • Testing: '🧪 WhizVoice DEBUG' (orange colors, debug features)"
echo ""
echo "🚀 Commands:"
echo "   • Test changes: ./test_with_latest_changes.sh"
echo "   • Update production: ./install_production_app.sh"
echo "   • Check status: ./check_installed_apps.sh" 