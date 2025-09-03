#!/bin/bash

# Smart Install Script for WhizVoice Production
# This installs the production version (com.example.whiz) for your daily use
# while keeping the debug version (com.example.whiz.debug) for testing
#
# Features:
# - Checks if app is already installed with same version
# - Preserves accessibility settings when possible
# - Only reinstalls when necessary
#
# Usage:
#   ./install_production_app.sh           # Build and install (smart mode)
#   ./install_production_app.sh --force   # Force reinstall even if versions match
#   ./install_production_app.sh --skip-build  # Use existing APK

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

PACKAGE_NAME="com.example.whiz"
APK_PATH="app/build/outputs/apk/release/app-release.apk"

log_with_time "🏭 Installing WhizVoice Production version for daily use..."
echo ""

# Build if needed
if [[ "$SKIP_BUILD" == "false" ]]; then
    log_with_time "⏳ Building release version (signed with debug key)..."
    if ./gradlew assembleRelease --console=plain --quiet; then
        log_with_time "✅ Release build completed successfully"
    else
        log_with_time "❌ Release build failed"
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
if adb shell pm list packages 2>/dev/null | grep -q "^package:${PACKAGE_NAME}$"; then
    APP_INSTALLED=true
    log_with_time "✅ Production app is already installed"
    
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
            
            if [[ "$ACCESSIBILITY_WAS_ENABLED" == "false" ]]; then
                log_with_time ""
                log_with_time "⚠️  ACCESSIBILITY SERVICE IS DISABLED"
                log_with_time "📱 Please enable it manually:"
                log_with_time "   1. Go to Settings → Accessibility"
                log_with_time "   2. Find 'Whiz Voice' under Downloaded apps"
                log_with_time "   3. Enable the service"
            fi
            
            echo ""
            log_with_time "✅ Production version is up to date (no reinstall needed)"
            log_with_time "📱 Your settings and accessibility permissions are preserved!"
            
            # Check what's installed
            echo ""
            log_with_time "📋 Installed versions:"
            if adb shell pm list packages | grep -q "com.example.whiz.debug"; then
                echo "   ✅ Debug version (🧪 WhizVoice DEBUG) - For testing"
            else
                echo "   ❌ Debug version - Not installed (install with ./install_debug_for_testing.sh)"
            fi
            echo "   ✅ Production version (Whiz Voice) - For daily use"
            
            exit 0
        fi
    else
        log_with_time "⚠️  Cannot compare versions (aapt not found) - will reinstall"
    fi
else
    APP_INSTALLED=false
    ACCESSIBILITY_WAS_ENABLED=false
    log_with_time "📦 Production app not installed - will perform fresh install"
fi

# Install or update the app
log_with_time "📱 Installing production APK..."
if adb install -r "$APK_PATH"; then
    log_with_time "✅ Production app installed successfully!"
else
    log_with_time "❌ Production app installation failed"
    exit 1
fi

# Grant permissions
log_with_time "⏳ Granting permissions..."
adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PACKAGE_NAME" android.permission.QUERY_ALL_PACKAGES 2>/dev/null || true
log_with_time "✅ Permissions granted"

# Check accessibility after install
if check_accessibility_enabled "$PACKAGE_NAME"; then
    log_with_time "✅ Accessibility service is still ENABLED (preserved!)"
else
    if [[ "$ACCESSIBILITY_WAS_ENABLED" == "true" ]]; then
        log_with_time "⚠️  Accessibility service was RESET during reinstall"
    fi
    
    log_with_time ""
    log_with_time "⚠️  ACCESSIBILITY SERVICE NEEDS TO BE ENABLED"
    log_with_time "📱 Please enable it in Settings → Accessibility → Whiz Voice"
fi

echo ""
log_with_time "📋 You now have:"

# Check what's installed
if adb shell pm list packages | grep -q "com.example.whiz.debug"; then
    echo "   ✅ Debug version (🧪 WhizVoice DEBUG) - For testing"
else
    echo "   ❌ Debug version - Not installed (install with ./install_debug_for_testing.sh)"
fi

if adb shell pm list packages | grep -q "^package:com.example.whiz$"; then
    echo "   ✅ Production version (Whiz Voice) - For daily use"
else
    echo "   ❌ Production version - Installation may have failed"
fi