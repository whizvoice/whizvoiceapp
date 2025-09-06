#!/bin/bash

# Smart Install Script for WhizVoice Debug
# This script:
# 1. Uses APK checksum to detect actual changes (works without aapt)
# 2. Preserves accessibility settings when possible
# 3. Only reinstalls when there are actual changes
#
# Usage:
#   ./install_debug_for_testing.sh           # Build and install (smart mode)
#   ./install_debug_for_testing.sh --force   # Force reinstall even if APK unchanged
#   ./install_debug_for_testing.sh --skip-build  # Use existing APK

set -e

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1"
}

# Function to check if accessibility service is enabled
check_accessibility_enabled() {
    local package="$1"
    local service_name="com.example.whiz.accessibility.WhizAccessibilityService"
    
    # Get the list of enabled accessibility services
    local enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    
    if echo "$enabled_services" | grep -q "$package/$service_name"; then
        return 0  # Accessibility is enabled
    else
        return 1  # Accessibility is not enabled
    fi
}

# Function to enable accessibility service
enable_accessibility_service() {
    local package="$1"
    local service_name="com.example.whiz.accessibility.WhizAccessibilityService"
    local full_service="$package/$service_name"
    
    # Get current enabled services
    local current_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    
    # Add our service if not already present
    if ! echo "$current_services" | grep -q "$full_service"; then
        if [ -z "$current_services" ] || [ "$current_services" = "null" ]; then
            new_services="$full_service"
        else
            new_services="$current_services:$full_service"
        fi
        
        log_with_time "🔧 Attempting to enable accessibility service programmatically..."
        
        # Try multiple approaches for Android 13+
        adb shell settings put secure accessibility_enabled 1
        adb shell settings put secure enabled_accessibility_services "$new_services"
        
        # Also try to mark as trusted (may not work without root)
        adb shell pm set-app-restricted-settings --user 0 "$package" false 2>/dev/null || true
        
        # Verify it worked
        if check_accessibility_enabled "$package"; then
            log_with_time "✅ Accessibility service enabled successfully!"
            return 0
        else
            log_with_time "⚠️  Android 13+ security prevents auto-enable for sideloaded apps"
            return 1
        fi
    fi
}

# Function to check for connected devices
check_for_devices() {
    log_with_time "🔍 Checking for connected ADB devices..."
    if ! adb devices | grep -q "device$"; then
        log_with_time "❌ No ADB devices or emulators found. Please connect a device or start an emulator."
        log_with_time "💡 Ensure USB debugging is enabled on your device and it's properly connected."
        exit 1
    fi
    log_with_time "✅ ADB device found."
}

# Function to get APK checksum
get_apk_checksum() {
    local apk_path="$1"
    if [[ -f "$apk_path" ]]; then
        # Use MD5 for speed, we just need change detection
        md5 -q "$apk_path" 2>/dev/null || md5sum "$apk_path" 2>/dev/null | cut -d' ' -f1
    else
        echo "none"
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
            echo "  --force: Force reinstall even if APK is unchanged"
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
CHECKSUM_FILE=".debug_apk_checksum"

log_with_time "📱 Installing WhizVoice Debug App..."
check_for_devices

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

# Get current APK checksum
CURRENT_CHECKSUM=$(get_apk_checksum "$APK_PATH")
log_with_time "📊 APK checksum: ${CURRENT_CHECKSUM:0:8}..."

# Get previous checksum if exists
PREVIOUS_CHECKSUM=""
if [[ -f "$CHECKSUM_FILE" ]]; then
    PREVIOUS_CHECKSUM=$(cat "$CHECKSUM_FILE")
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
    
    # Check if APK has changed
    if [[ "$CURRENT_CHECKSUM" == "$PREVIOUS_CHECKSUM" ]] && [[ "$FORCE_INSTALL" == "false" ]]; then
        log_with_time "✅ APK unchanged - skipping reinstall to preserve settings"
        
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
            # Try to enable it automatically
            if ! enable_accessibility_service "$PACKAGE_NAME"; then
                log_with_time ""
                log_with_time "⚠️  ACCESSIBILITY SERVICE IS DISABLED"
                log_with_time "📱 Please enable it manually:"
                log_with_time "   1. Go to Settings → Accessibility"
                log_with_time "   2. Find 'WhizVoice DEBUG' under Downloaded apps"
                log_with_time "   3. Enable the service"
            fi
        fi
        
        log_with_time "🎉 WhizVoice Debug app is ready for manual testing!"
        log_with_time "📱 No reinstall needed - your settings are preserved"
        exit 0
    else
        if [[ "$FORCE_INSTALL" == "true" ]]; then
            log_with_time "🔄 Forcing reinstall as requested"
        else
            log_with_time "🔄 APK has changed - reinstalling..."
        fi
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
    
    # Save current checksum for next run
    echo "$CURRENT_CHECKSUM" > "$CHECKSUM_FILE"
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
        
        # Try to restore it automatically
        if enable_accessibility_service "$PACKAGE_NAME"; then
            log_with_time "✅ Accessibility service restored automatically!"
        else
            log_with_time ""
            log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE RE-ENABLED"
            log_with_time "📱 Please enable it in Settings → Accessibility → WhizVoice DEBUG"
        fi
    else
        # Try to enable it for first time
        if ! enable_accessibility_service "$PACKAGE_NAME"; then
            log_with_time ""
            log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
            log_with_time "📱 Please enable it in Settings → Accessibility → WhizVoice DEBUG"
        fi
    fi
fi

log_with_time "🎉 WhizVoice Debug app is ready for manual testing!"
log_with_time "📱 You can now open the app on your device"