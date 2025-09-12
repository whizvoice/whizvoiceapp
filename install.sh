#!/bin/bash

# Unified Install Script for WhizVoice
# This script installs either debug or production version
# 
# Features:
# - Uses APK checksum to detect actual changes
# - Preserves accessibility settings when possible  
# - Only reinstalls when there are actual changes
#
# Usage:
#   ./install.sh                  # Install debug version (default)
#   ./install.sh --production     # Install production version
#   ./install.sh --force          # Force reinstall even if APK unchanged
#   ./install.sh --skip-build     # Use existing APK

set -e

# Function to display timestamp
log_with_time() {
    echo "[$(date +'%H:%M:%S.%N' | cut -c1-11)] $1"
}

# Function to check if accessibility service is enabled
check_accessibility_enabled() {
    local package="$1"
    local service_name="com.example.whiz.accessibility.WhizAccessibilityService"
    local full_service="$package/$service_name"
    
    local enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    
    if echo "$enabled_services" | grep -q "$full_service"; then
        return 0
    else
        return 1
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

# Parse command line arguments
FORCE_INSTALL="false"
SKIP_BUILD="false"
BUILD_TYPE="debug"
DISPLAY_NAME="WhizVoice DEBUG"

while [[ $# -gt 0 ]]; do
    case $1 in
        --production)
            BUILD_TYPE="production"
            DISPLAY_NAME="Whiz Voice"
            shift
            ;;
        --force)
            FORCE_INSTALL="true"
            shift
            ;;
        --skip-build)
            SKIP_BUILD="true"
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo "Options:"
            echo "  --production: Install production version (default: debug)"
            echo "  --force: Force reinstall even if APK is unchanged"
            echo "  --skip-build: Skip the build step and use existing APK"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Set variables based on build type
if [[ "$BUILD_TYPE" == "production" ]]; then
    PACKAGE_NAME="com.example.whiz"
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
    CHECKSUM_FILE=".production_apk_checksum"
    GRADLE_TASK="assembleRelease"
    BUILD_TYPE_DISPLAY="release"
    EMOJI="🏭"
else
    PACKAGE_NAME="com.example.whiz.debug"
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    CHECKSUM_FILE=".debug_apk_checksum"
    GRADLE_TASK="assembleDebug"
    BUILD_TYPE_DISPLAY="debug"
    EMOJI="🧪"
fi

log_with_time "$EMOJI Installing $DISPLAY_NAME..."
check_for_devices

# Build the APK unless skipped
if [[ "$SKIP_BUILD" == "false" ]]; then
    log_with_time "⏳ Building $BUILD_TYPE_DISPLAY version..."
    if ./gradlew $GRADLE_TASK --console=plain --quiet; then
        log_with_time "✅ Build completed successfully"
    else
        log_with_time "❌ Build failed"
        exit 1
    fi
else
    log_with_time "⏭️  Skipping build step (using existing APK)"
fi

# Check if APK exists
if [[ ! -f "$APK_PATH" ]]; then
    log_with_time "❌ APK not found at $APK_PATH"
    log_with_time "💡 Please build the app first or remove --skip-build flag"
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

# Check if app is already installed
if adb shell pm list packages 2>/dev/null | grep -q "^package:${PACKAGE_NAME}$"; then
    IS_INSTALLED="true"
    log_with_time "✅ App is already installed"
    
    # Check accessibility service status before potentially reinstalling
    ACCESSIBILITY_WAS_ENABLED="false"
    if check_accessibility_enabled "$PACKAGE_NAME"; then
        ACCESSIBILITY_WAS_ENABLED="true"
        log_with_time "✅ Accessibility service is currently ENABLED"
    else
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
        
        # Launch and stop the app to verify it works
        log_with_time "⏳ Verifying app installation..."
        adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" >/dev/null 2>&1 || true
        sleep 2
        adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
        
        if [[ "$ACCESSIBILITY_WAS_ENABLED" == "false" ]]; then
            log_with_time ""
            log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
            log_with_time "📱 Steps to enable:"
            log_with_time "   1. Go to Settings → Accessibility"
            log_with_time "   2. Find '$DISPLAY_NAME' under Downloaded apps"
            log_with_time "   3. Toggle it ON and confirm"
        fi
        
        log_with_time ""
        log_with_time "🎉 $DISPLAY_NAME is ready!"
        log_with_time "📱 No reinstall needed - your settings are preserved"
        
        # Save checksum for next run
        echo "$CURRENT_CHECKSUM" > "$CHECKSUM_FILE"
        exit 0
    else
        if [[ "$FORCE_INSTALL" == "true" ]]; then
            log_with_time "🔄 Forcing reinstall as requested"
        else
            log_with_time "🔄 APK has changed - reinstalling..."
        fi
    fi
else
    IS_INSTALLED="false"
    log_with_time "📦 App not installed - will perform fresh install"
    ACCESSIBILITY_WAS_ENABLED="false"
fi

# Force stop the app first to ensure fresh start
log_with_time "🛑 Force stopping app to ensure fresh installation..."
adb shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true

# Install the APK
log_with_time "📱 Installing APK..."
if adb install -r "$APK_PATH"; then
    log_with_time "✅ App installed successfully!"
    
    # Save current checksum for next run
    echo "$CURRENT_CHECKSUM" > "$CHECKSUM_FILE"
else
    log_with_time "❌ App installation failed"
    exit 1
fi

# Grant permissions
log_with_time "⏳ Granting permissions..."
adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PACKAGE_NAME" android.permission.QUERY_ALL_PACKAGES 2>/dev/null || true
log_with_time "✅ Permissions granted"

# Launch and stop the app to verify installation
log_with_time "⏳ Verifying installation..."
adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" >/dev/null 2>&1 || true
sleep 2
adb shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true

# Check accessibility service status after installation
if [[ "$IS_INSTALLED" == "true" ]]; then
    if [[ "$ACCESSIBILITY_WAS_ENABLED" == "true" ]]; then
        log_with_time ""
        log_with_time "⚠️  ACCESSIBILITY SERVICE WAS RESET BY REINSTALL"
        log_with_time "📱 Please re-enable it in Settings → Accessibility → $DISPLAY_NAME"
    else
        log_with_time ""
        log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
        log_with_time "📱 Please enable it in Settings → Accessibility → $DISPLAY_NAME"
    fi
else
    log_with_time ""
    log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
    log_with_time "📱 Please enable it in Settings → Accessibility → $DISPLAY_NAME"
fi

echo ""
log_with_time "📋 Installed versions:"

# Check what's installed
if [[ "$BUILD_TYPE" == "production" ]]; then
    # Installing production, check if debug exists
    if adb shell pm list packages | grep -q "com.example.whiz.debug"; then
        echo "   ✅ Debug version (🧪 WhizVoice DEBUG) - For testing"
    else
        echo "   ❌ Debug version - Not installed (install with: ./install.sh)"
    fi
    echo "   ✅ Production version (🏭 Whiz Voice) - For daily use"
else
    # Installing debug, check if production exists
    echo "   ✅ Debug version (🧪 WhizVoice DEBUG) - For testing"
    if adb shell pm list packages | grep -q "^package:com.example.whiz$"; then
        echo "   ✅ Production version (🏭 Whiz Voice) - For daily use"
    else
        echo "   ❌ Production version - Not installed (install with: ./install.sh --production)"
    fi
fi

echo ""
log_with_time "🎉 $DISPLAY_NAME is ready!"