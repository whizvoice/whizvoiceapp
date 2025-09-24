#!/bin/bash

# Script to set up permissions for WhizVoice app testing
# This runs an instrumented test to grant accessibility and other permissions,
# which will persist for subsequent test runs

set -e

SCRIPT_NAME="setup_permissions.sh"
PACKAGE_NAME="com.example.whiz.debug"
TEST_PACKAGE="${PACKAGE_NAME}.test"
TEST_CLASS="com.example.whiz.setup.PermissionSetupTest"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_color() {
    local color=$1
    shift
    echo -e "${color}$@${NC}"
}

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S')] $1"
}

# Function to check if device is connected
check_device() {
    if ! adb devices | grep -q "device$"; then
        print_color $RED "❌ No device connected. Please connect a device or start an emulator."
        exit 1
    fi
    print_color $GREEN "✅ Device connected"
}

# Function to check current permission status
check_permission_status() {
    log_with_time "📊 Checking current permission status..."

    # Check microphone permission
    mic_granted=$(adb shell pm list permissions -g | grep -A 20 "com.example.whiz.debug" | grep "android.permission.RECORD_AUDIO" || echo "")
    if [[ -n "$mic_granted" ]]; then
        print_color $GREEN "  ✅ Microphone permission: GRANTED"
    else
        print_color $YELLOW "  ⚠️ Microphone permission: NOT GRANTED"
    fi

    # Check overlay permission
    overlay_granted=$(adb shell appops get $PACKAGE_NAME SYSTEM_ALERT_WINDOW 2>/dev/null | grep -i "allow" || echo "")
    if [[ -n "$overlay_granted" ]]; then
        print_color $GREEN "  ✅ Overlay permission: GRANTED"
    else
        print_color $YELLOW "  ⚠️ Overlay permission: NOT GRANTED"
    fi

    # Check accessibility service
    enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    if [[ "$enabled_services" == *"$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"* ]]; then
        print_color $GREEN "  ✅ Accessibility service: ENABLED"
    else
        print_color $YELLOW "  ⚠️ Accessibility service: NOT ENABLED"
    fi
}

# Function to run permission setup test
run_permission_setup() {
    local test_method=${1:-"setupAllPermissions"}

    log_with_time "🚀 Running permission setup test: $test_method"

    # Build the test APK first (if needed)
    log_with_time "📦 Building test APK..."
    if ! ./gradlew :app:assembleDebugAndroidTest -q; then
        print_color $RED "❌ Failed to build test APK"
        exit 1
    fi

    # Install the test APK
    log_with_time "📲 Installing test APK..."
    local test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    if [[ -f "$test_apk" ]]; then
        adb install -r -g "$test_apk" 2>/dev/null || true
    fi

    # Run the specific test method
    log_with_time "🏃 Running instrumented test to set up permissions..."
    adb shell am instrument -w \
        -e class "${TEST_CLASS}#${test_method}" \
        "${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner" | tee permission_setup.log

    # Check if test completed
    if grep -q "OK (1 test)" permission_setup.log; then
        print_color $GREEN "✅ Permission setup test completed successfully"
        return 0
    else
        print_color $YELLOW "⚠️ Permission setup test may have encountered issues"
        return 1
    fi
}

# Function to try direct ADB permission grant (faster alternative)
try_direct_adb_grant() {
    log_with_time "⚡ Attempting direct ADB permission grant (faster method)..."

    local all_success=true

    # Grant microphone permission
    if adb shell pm grant $PACKAGE_NAME android.permission.RECORD_AUDIO 2>/dev/null; then
        print_color $GREEN "  ✅ Microphone permission granted"
    else
        print_color $YELLOW "  ⚠️ Could not grant microphone permission via ADB"
        all_success=false
    fi

    # Grant overlay permission
    if adb shell appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow 2>/dev/null; then
        print_color $GREEN "  ✅ Overlay permission granted"
    else
        print_color $YELLOW "  ⚠️ Could not grant overlay permission via ADB"
        all_success=false
    fi

    # Enable accessibility service
    local current_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null || echo "")
    local our_service="${PACKAGE_NAME}/com.example.whiz.accessibility.WhizAccessibilityService"

    if [[ "$current_services" != *"$our_service"* ]]; then
        if [[ "$current_services" == "null" ]] || [[ -z "$current_services" ]]; then
            new_services="$our_service"
        else
            new_services="${current_services}:${our_service}"
        fi

        if adb shell settings put secure enabled_accessibility_services "$new_services" 2>/dev/null && \
           adb shell settings put secure accessibility_enabled 1 2>/dev/null; then
            print_color $GREEN "  ✅ Accessibility service enabled"
        else
            print_color $YELLOW "  ⚠️ Could not enable accessibility service via ADB"
            all_success=false
        fi
    else
        print_color $GREEN "  ✅ Accessibility service already enabled"
    fi

    if $all_success; then
        return 0
    else
        return 1
    fi
}

# Main execution
main() {
    print_color $BLUE "==========================================
🔧 WhizVoice Permission Setup Script
==========================================
"

    # Parse command line arguments
    local method="all"
    local use_instrumentation=false

    while [[ $# -gt 0 ]]; do
        case $1 in
            --accessibility-only)
                method="accessibility"
                shift
                ;;
            --force-instrumentation)
                use_instrumentation=true
                shift
                ;;
            --help)
                echo "Usage: $SCRIPT_NAME [options]"
                echo ""
                echo "Options:"
                echo "  --accessibility-only     Only set up accessibility service"
                echo "  --force-instrumentation  Use instrumentation method (slower but more reliable)"
                echo "  --help                   Show this help message"
                echo ""
                echo "By default, tries direct ADB grant first, falls back to instrumentation if needed."
                exit 0
                ;;
            *)
                print_color $RED "Unknown option: $1"
                echo "Use --help for usage information"
                exit 1
                ;;
        esac
    done

    # Check device connection
    check_device

    # Check current status
    print_color $BLUE "\n📊 Current permission status:"
    check_permission_status

    # Decide which method to use
    if $use_instrumentation; then
        log_with_time "\n🎯 Using instrumentation method as requested..."
        if [[ "$method" == "accessibility" ]]; then
            run_permission_setup "setupAccessibilityOnly"
        else
            run_permission_setup "setupAllPermissions"
        fi
    else
        # Try direct ADB method first
        log_with_time "\n🎯 Trying fast ADB method first..."
        if try_direct_adb_grant; then
            print_color $GREEN "\n✅ All permissions granted via direct ADB commands!"
        else
            print_color $YELLOW "\n⚠️ Some permissions couldn't be granted via ADB, falling back to instrumentation..."
            if [[ "$method" == "accessibility" ]]; then
                run_permission_setup "setupAccessibilityOnly"
            else
                run_permission_setup "setupAllPermissions"
            fi
        fi
    fi

    # Final status check
    print_color $BLUE "\n📊 Final permission status:"
    check_permission_status

    print_color $GREEN "\n==========================================
✅ Permission setup complete!
==========================================

You can now run your tests with permissions already granted.
The permissions will persist until explicitly revoked.

To run your tests:
  ./run_tests_on_debug.sh --skip-unit --test YourTestClass
"
}

# Run main function
main "$@"