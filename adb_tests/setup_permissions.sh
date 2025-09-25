#!/bin/bash

# Enable accessibility service by clicking through WhizVoice app's own UI
# This uses pure ADB UI automation instead of instrumentation to avoid force-stop issues

set -e

PACKAGE_NAME="com.example.whiz.debug"
SERVICE_NAME="WhizAccessibilityService"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Function to get timestamp
timestamp() {
    date '+%H:%M:%S'
}

log_info() {
    echo -e "${BLUE}[$(timestamp)]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[$(timestamp)]${NC} ✅ $1"
}

log_error() {
    echo -e "${RED}[$(timestamp)]${NC} ❌ $1"
}

log_warning() {
    echo -e "${YELLOW}[$(timestamp)]${NC} ⚠️ $1"
}

# Function to dump UI and save for debugging
dump_ui() {
    adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
    # Optionally copy to local for debugging
    # adb pull /sdcard/window_dump.xml ./debug_ui_dump.xml 2>/dev/null
}

# Function to click on element by content-desc
click_by_content_desc() {
    local desc="$1"
    log_info "Looking for content-desc: '$desc'"

    dump_ui

    # Get coordinates of element with matching content-desc
    local coords=$(adb shell "cat /sdcard/window_dump.xml | grep -o \"content-desc=\\\"[^\\\"]*${desc}[^\\\"]*\\\"[^>]*bounds=\\\"\\[[0-9,]*\\]\\[[0-9,]*\\]\\\"\" | head -1 | sed 's/.*bounds=\"\\[\\([0-9]*\\),\\([0-9]*\\)\\]\\[\\([0-9]*\\),\\([0-9]*\\)\\]\".*/\\1 \\2 \\3 \\4/'" 2>/dev/null)

    if [ -n "$coords" ]; then
        read x1 y1 x2 y2 <<< "$coords"
        local x=$(( (x1 + x2) / 2 ))
        local y=$(( (y1 + y2) / 2 ))

        log_info "Found element, clicking at ($x, $y)"
        adb shell input tap $x $y
        return 0
    else
        return 1
    fi
}

# Function to click on element by text (exact match)
click_by_text() {
    local text="$1"
    log_info "Looking for exact text: '$text'"

    dump_ui

    # Debug: Show all elements with our text
    log_info "Searching for all instances of '$text'..."
    local all_matches=$(adb shell "cat /sdcard/window_dump.xml | grep -n \"text=\\\"${text}\\\"\"" 2>/dev/null || echo "")
    if [ -n "$all_matches" ]; then
        log_info "All matches found: $all_matches"
    fi

    # Get ONLY the TextView element with the exact text, not the whole document
    local element_line=$(adb shell "cat /sdcard/window_dump.xml" 2>/dev/null | grep -o "<node[^>]*text=\"${text}\"[^>]*>" | head -1)

    if [ -n "$element_line" ]; then
        log_info "Found element (first 400 chars): ${element_line:0:400}"

        # Extract bounds from this specific node element
        local bounds_match=$(echo "$element_line" | grep -o 'bounds="[^"]*"' | head -1)
        log_info "Bounds attribute: $bounds_match"

        # Extract the numbers from bounds="[x1,y1][x2,y2]"
        local coords=$(echo "$bounds_match" | sed 's/bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/')
        log_info "Extracted coordinates: $coords"

        if [ -n "$coords" ] && [ "$coords" != "$bounds_match" ]; then
            read x1 y1 x2 y2 <<< "$coords"
            local x=$(( (x1 + x2) / 2 ))
            local y=$(( (y1 + y2) / 2 ))

            log_info "Bounds: left=$x1, top=$y1, right=$x2, bottom=$y2"
            log_info "Calculated center point: x=$x, y=$y"
            log_info "CLICKING at ($x, $y) for text '$text'"

            adb shell input tap $x $y
            return 0
        else
            log_error "Failed to parse coordinates from bounds: $bounds_match"
        fi
    fi

    log_warning "Could not find or click text: '$text'"
    return 1
}

# Function to check if accessibility dialog is visible
check_for_accessibility_dialog() {
    dump_ui

    # Look for the accessibility dialog based on exact text from code
    if adb shell "cat /sdcard/window_dump.xml | grep -q -E '(Enable Accessibility Service|Open accessibility settings button|WhizVoice needs permission to automate actions across apps)'" 2>/dev/null; then
        return 0
    fi
    return 1
}

# Main function
enable_accessibility_via_app() {
    log_info "Closing Settings app to ensure clean state..."

    # Force stop Settings app to ensure we start fresh
    adb shell am force-stop com.android.settings 2>/dev/null
    sleep 1

    log_info "Checking if app is running..."

    # Start or bring app to foreground
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" 2>/dev/null
    sleep 3

    # Check if accessibility dialog is showing
    if check_for_accessibility_dialog; then
        log_success "Found accessibility permission dialog"

        # Click on "Open Settings" button using exact content description from code
        if click_by_content_desc "Open accessibility settings button" || \
           click_by_text "Open Settings"; then

            log_success "Clicked on settings button"
            sleep 3

            # We might land on either:
            # 1. The accessibility list page - need to click "WhizVoice DEBUG"
            # 2. Directly on the WhizVoice DEBUG detail page - can toggle immediately

            # Check if we're on the list page by looking for "WhizVoice DEBUG" text
            dump_ui

            if adb shell "cat /sdcard/window_dump.xml | grep -q 'WhizVoice DEBUG'" 2>/dev/null && \
               adb shell "cat /sdcard/window_dump.xml | grep -q 'Downloaded apps'" 2>/dev/null; then
                # We're on the list page, need to click the item
                log_info "On accessibility list page, need to click WhizVoice DEBUG..."

                # Click on WhizVoice DEBUG using fixed coordinates
                log_info "Clicking at WhizVoice DEBUG item (X=500, Y=700)"
                adb shell input tap 500 700
                sleep 3

                # Verify we're on the right page now
                dump_ui
                if adb shell "cat /sdcard/window_dump.xml | grep -q 'Use.*DEBUG\\|Use.*service\\|WhizVoice DEBUG'" 2>/dev/null; then
                    log_success "Successfully navigated to WhizVoice DEBUG settings"
                else
                    log_warning "May not be on correct page, checking what we clicked..."
                    # Let's see what page we're on
                    local page_title=$(adb shell "cat /sdcard/window_dump.xml | grep -o '<node[^>]*text=\"[^\"]*\"[^>]*class=\"android.widget.TextView\"[^>]*>' | head -1" 2>/dev/null)
                    log_info "Current page title element: $page_title"

                    # Go back and try again with a different approach
                    log_info "Going back to try again..."
                    adb shell input keyevent KEYCODE_BACK
                    sleep 2

                    # Try clicking lower on the second item
                    log_info "Trying alternative coordinates (540, 480)"
                    adb shell input tap 540 480
                    sleep 2
                fi
            else
                log_info "Already on WhizVoice DEBUG detail page"
            fi

            # Stop here - user will manually handle the rest
            log_success "Successfully clicked on WhizVoice DEBUG in accessibility settings"
            log_info "Please manually toggle the switch to enable the service"

            return 0
        else
            log_error "Could not find button to open accessibility settings"
            return 1
        fi
    else
        log_warning "Accessibility dialog not visible in app"

        # Maybe the service is already enabled?
        if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
            log_success "Accessibility service is already running"
            return 0
        else
            log_error "Accessibility dialog not shown and service not running"
            log_info "The app may need to request accessibility permission first"
            return 1
        fi
    fi
}

# Parse command line arguments
MODE=""
for arg in "$@"; do
    case $arg in
        --accessibility-only)
            MODE="accessibility"
            shift
            ;;
        --force-instrumentation)
            log_warning "Ignoring --force-instrumentation flag (using pure ADB UI automation instead)"
            shift
            ;;
        *)
            ;;
    esac
done

echo "=========================================="
echo "🔧 WhizVoice Permission Setup Script"
echo "=========================================="
echo ""

# Check device connection
if ! adb devices | grep -q "device$"; then
    log_error "No device connected. Please connect a device or start an emulator."
    exit 1
fi
log_success "Device connected"
echo ""

# Check current status
echo ""
echo "📊 Current permission status:"
echo "[$(timestamp)] 📊 Checking current permission status..."

# Check microphone permission
mic_granted=$(adb shell pm list permissions -g | grep -A 20 "com.example.whiz.debug" | grep "android.permission.RECORD_AUDIO" || echo "")
if [[ -n "$mic_granted" ]]; then
    echo "  ✅ Microphone permission: GRANTED"
else
    echo "  ⚠️ Microphone permission: NOT GRANTED"
fi

# Check overlay permission
overlay_granted=$(adb shell appops get "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW 2>/dev/null | grep -c "allow" || echo "0")
if [[ "$overlay_granted" -gt 0 ]]; then
    echo "  ✅ Overlay permission: GRANTED"
else
    echo "  ⚠️ Overlay permission: NOT GRANTED"
fi

# Check accessibility service
enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
if [[ "$enabled_services" == *"$PACKAGE_NAME"* ]]; then
    echo "  ✅ Accessibility service: ENABLED"

    # Check if it's actually running
    if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
        log_success "Accessibility service is running"
        echo ""
        echo "=========================================="
        echo "[$(timestamp)] ✅ All permissions are already set up!"
        echo "=========================================="
        exit 0
    else
        log_warning "Service is enabled but not running, attempting to start..."
        # Try to restart the app
        adb shell am force-stop "$PACKAGE_NAME"
        sleep 1
        adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
        sleep 3

        if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
            log_success "Accessibility service is now running"
            exit 0
        fi
    fi
else
    echo "  ⚠️ Accessibility service: NOT ENABLED"
fi

# Set up permissions
echo ""
log_info "Setting up accessibility permission via app UI..."

if enable_accessibility_via_app; then
    # Verify it worked
    sleep 2
    enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')

    if [[ "$enabled_services" == *"$PACKAGE_NAME"* ]]; then
        log_success "Accessibility service is now enabled"

        if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
            log_success "Accessibility service is running"
        else
            log_warning "Service enabled but not yet running"
        fi

        echo ""
        echo "=========================================="
        echo "[$(timestamp)] ✅ Permission setup complete!"
        echo "=========================================="
        echo ""
        echo "You can now run your tests with permissions already granted."
        echo "The permissions will persist until explicitly revoked."
        echo ""
        echo "To run your tests:"
        echo "  ./run_tests_on_debug.sh --skip-unit --test YourTestClass"
        echo ""

        exit 0
    else
        log_error "Failed to enable accessibility service"
        exit 1
    fi
else
    log_error "Failed to enable accessibility through app UI"
    exit 1
fi