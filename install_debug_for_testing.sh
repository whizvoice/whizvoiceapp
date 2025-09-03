#!/bin/bash

# Smart Install Script for WhizVoice Debug
# This script:
# 1. Checks if the app is already installed with the same version
# 2. Preserves accessibility settings when possible
# 3. Only reinstalls when there are actual changes
#
# Usage:
#   ./install_debug_for_testing.sh           # Build and install (smart mode)
#   ./install_debug_for_testing.sh --force   # Force reinstall even if versions match
#   ./install_debug_for_testing.sh --skip-build  # Use existing APK

set -e

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1"
}

# Function to check if accessibility service is enabled
check_accessibility_enabled() {
    local package="$1"
    local service_name="com.example.whiz.services.WhizAccessibilityService"
    
    # Get the list of enabled accessibility services
    local enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    
    if echo "$enabled_services" | grep -q "$package/$service_name"; then
        return 0  # Accessibility is enabled
    else
        return 1  # Accessibility is not enabled
    fi
}

# Function to get app version code
get_installed_version() {
    local package="$1"
    adb shell dumpsys package "$package" 2>/dev/null | grep versionCode | head -1 | sed 's/.*versionCode=//' | cut -d' ' -f1
}

# Function to get APK version code
get_apk_version() {
    local apk_path="$1"
    # Use aapt if available, otherwise try aapt2
    if command -v aapt &> /dev/null; then
        aapt dump badging "$apk_path" 2>/dev/null | grep versionCode | sed "s/.*versionCode='\([0-9]*\)'.*/\1/"
    elif command -v aapt2 &> /dev/null; then
        aapt2 dump badging "$apk_path" 2>/dev/null | grep versionCode | sed "s/.*versionCode='\([0-9]*\)'.*/\1/"
    else
        # Fallback: just return a value that won't match
        echo "unknown"
    fi
}

# Parse arguments
FORCE_INSTALL=false
SKIP_BUILD=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --force)
            FORCE_INSTALL=true
            shift
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --help)
            echo "Usage: $0 [--force] [--skip-build]"
            echo "  --force: Force reinstall even if versions match"
            echo "  --skip-build: Skip building, use existing APK"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--force] [--skip-build]"
            exit 1
            ;;
    esac
done

PACKAGE_NAME="com.example.whiz.debug"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

log_with_time "📱 Installing WhizVoice Debug App..."

# Build if needed
if [[ "$SKIP_BUILD" == "false" ]]; then
    log_with_time "⏳ Building latest debug version..."
    if ./gradlew assembleDebug --console=plain --quiet; then
        log_with_time "✅ Debug build completed successfully"
    else
        log_with_time "❌ Debug build failed"
        exit 1
    fi
else
    log_with_time "⏭️  Skipping build (using existing APK)"
fi

# Check if APK exists
if [[ ! -f "$APK_PATH" ]]; then
    log_with_time "❌ APK not found at $APK_PATH"
    log_with_time "💡 Run without --skip-build to build the APK first"
    exit 1
fi

# Check if app is installed
if adb shell pm list packages 2>/dev/null | grep -q "$PACKAGE_NAME"; then
    APP_INSTALLED=true
    log_with_time "✅ App is already installed"
    
    # Check accessibility status before potential reinstall
    if check_accessibility_enabled "$PACKAGE_NAME"; then
        ACCESSIBILITY_WAS_ENABLED=true
        log_with_time "✅ Accessibility service is currently ENABLED"
    else
        ACCESSIBILITY_WAS_ENABLED=false
        log_with_time "⚠️  Accessibility service is currently DISABLED"
    fi
    
    # Try to compare versions (may fail if aapt not available)
    INSTALLED_VERSION=$(get_installed_version "$PACKAGE_NAME")
    APK_VERSION=$(get_apk_version "$APK_PATH")
    
    if [[ "$APK_VERSION" != "unknown" ]]; then
        log_with_time "📊 Version comparison:"
        log_with_time "   Installed: $INSTALLED_VERSION"
        log_with_time "   APK:       $APK_VERSION"
        
        if [[ "$INSTALLED_VERSION" == "$APK_VERSION" ]] && [[ "$FORCE_INSTALL" == "false" ]]; then
            log_with_time "✅ Versions match - skipping reinstall to preserve settings"
            
            # Just grant permissions to be safe
            log_with_time "⏳ Ensuring permissions are granted..."
            adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
            adb shell pm grant "$PACKAGE_NAME" android.permission.QUERY_ALL_PACKAGES 2>/dev/null || true
            log_with_time "✅ Permissions granted"
            
            # Launch the app to verify it works
            log_with_time "⏳ Starting app to verify installation..."
            adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" >/dev/null 2>&1 || true
            sleep 2
            adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
            
            if [[ "$ACCESSIBILITY_WAS_ENABLED" == "false" ]]; then
                log_with_time ""
                log_with_time "⚠️  ACCESSIBILITY SERVICE IS DISABLED"
                log_with_time "📱 Please enable it manually:"
                log_with_time "   1. Go to Settings → Accessibility"
                log_with_time "   2. Find 'WhizVoice DEBUG' under Downloaded apps"
                log_with_time "   3. Enable the service"
            fi
            
            log_with_time "🎉 WhizVoice Debug app is ready for manual testing!"
            log_with_time "📱 No reinstall needed - your settings are preserved"
            exit 0
        fi
    else
        log_with_time "⚠️  Cannot compare versions (aapt not found) - will reinstall"
    fi
else
    APP_INSTALLED=false
    ACCESSIBILITY_WAS_ENABLED=false
    log_with_time "📦 App not installed - will perform fresh install"
fi

# Install or update the app
log_with_time "⏳ Installing debug APK..."
if adb install -r "$APK_PATH"; then
    log_with_time "✅ Debug app installed successfully"
else
    log_with_time "❌ Debug app installation failed"
    exit 1
fi

# Grant permissions
log_with_time "⏳ Granting permissions..."
adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PACKAGE_NAME" android.permission.QUERY_ALL_PACKAGES 2>/dev/null || true
log_with_time "✅ Permissions granted"

# Launch the app to verify installation
log_with_time "⏳ Starting app to verify installation..."
adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" >/dev/null 2>&1 || true
sleep 2
adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true

# Check accessibility after install
if check_accessibility_enabled "$PACKAGE_NAME"; then
    log_with_time "✅ Accessibility service is still ENABLED (preserved!)"
else
    if [[ "$ACCESSIBILITY_WAS_ENABLED" == "true" ]]; then
        log_with_time "⚠️  Accessibility service was RESET during reinstall"
    fi
    
    log_with_time ""
    log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
    log_with_time "📱 Please enable it in Settings → Accessibility → WhizVoice DEBUG"
fi

log_with_time "🎉 WhizVoice Debug app is ready for manual testing!"
log_with_time "📱 You can now open the app on your device"