#!/bin/bash

# UI Automator Login Script for WhizVoice
# Uses UI Automator to click through the actual Google sign-in flow in the main app

set -e

PACKAGE_NAME="com.example.whiz.debug"
TEST_EMAIL="REDACTED_TEST_EMAIL"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Function to dump UI and find element bounds
find_element_bounds() {
    local search_text="$1"
    local search_type="${2:-text}"  # text, content-desc, or resource-id

    # Dump current UI hierarchy
    adb shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
    sleep 1  # Give it a moment to write the file

    # Pull the dump file to local for easier processing
    adb pull /sdcard/window_dump.xml /tmp/window_dump.xml >/dev/null 2>&1

    # Search for the element based on type
    if [ "$search_type" == "text" ]; then
        BOUNDS=$(cat /tmp/window_dump.xml 2>/dev/null | grep -o "text=\"$search_text\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | grep -o "bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | head -1)
    elif [ "$search_type" == "content-desc" ]; then
        BOUNDS=$(cat /tmp/window_dump.xml 2>/dev/null | grep -o "content-desc=\"$search_text\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | grep -o "bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | head -1)
    elif [ "$search_type" == "partial-text" ]; then
        BOUNDS=$(cat /tmp/window_dump.xml 2>/dev/null | grep -o "text=\"[^\"]*$search_text[^\"]*\"[^>]*bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | grep -o "bounds=\"\[[0-9,]*\]\[[0-9,]*\]\"" | head -1)
    fi

    if [ -n "$BOUNDS" ]; then
        # Extract coordinates: bounds="[x1,y1][x2,y2]"
        echo "$BOUNDS" | sed 's/bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2 \3 \4/'
    else
        echo ""
    fi
}

# Function to click at element
click_element() {
    local search_text="$1"
    local search_type="${2:-text}"
    local wait_time="${3:-1}"

    log_info "Looking for element: $search_text ($search_type)"

    COORDS=$(find_element_bounds "$search_text" "$search_type")

    if [ -n "$COORDS" ]; then
        read x1 y1 x2 y2 <<< "$COORDS"
        CENTER_X=$(( (x1 + x2) / 2 ))
        CENTER_Y=$(( (y1 + y2) / 2 ))

        log_info "Found at [$x1,$y1][$x2,$y2], clicking at ($CENTER_X, $CENTER_Y)"
        adb shell input tap $CENTER_X $CENTER_Y
        sleep $wait_time
        return 0
    else
        log_warning "Element not found: $search_text"
        return 1
    fi
}

# Function to wait for element to appear
wait_for_element() {
    local search_text="$1"
    local search_type="${2:-text}"
    local max_wait="${3:-10}"
    local count=0

    log_info "Waiting for element: $search_text"

    while [ $count -lt $max_wait ]; do
        COORDS=$(find_element_bounds "$search_text" "$search_type")
        if [ -n "$COORDS" ]; then
            log_success "Element found: $search_text"
            return 0
        fi
        sleep 1
        ((count++))
    done

    log_warning "Element not found after ${max_wait} seconds: $search_text"
    return 1
}

# Function to take screenshot for debugging
take_screenshot() {
    local name="$1"
    local filename="login_${name}_$(date +%Y%m%d_%H%M%S).png"
    local screenshot_dir="$(dirname "$0")/screenshots"

    # Ensure screenshot directory exists
    mkdir -p "$screenshot_dir"

    adb shell screencap /sdcard/$filename
    adb pull /sdcard/$filename "$screenshot_dir/" 2>/dev/null
    adb shell rm /sdcard/$filename 2>/dev/null  # Clean up from device
    log_info "Screenshot saved: $screenshot_dir/$filename"
}

# Main login flow
main() {
    echo "=========================================="
    echo "WhizVoice UI Automator Login"
    echo "=========================================="

    # 1. Clear app data for clean state (unless --no-clear flag is passed)
    if [ "${1:-}" != "--no-clear" ]; then
        log_info "Clearing app data for clean login..."
        adb shell pm clear "$PACKAGE_NAME" || log_warning "Could not clear app data"
    else
        log_info "Skipping app data clear (already handled by caller)"
    fi

    # 2. Launch the app
    log_info "Launching WhizVoice app..."
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
    sleep 3  # Wait for app to fully load

    # 3. Take initial screenshot
    take_screenshot "01_login_screen"

    # 4. Click "Sign in with Google" button
    log_info "Clicking 'Sign in with Google' button..."

    # Try multiple ways to find the button
    if ! click_element "Sign in with Google" "text" 2; then
        # Try with content description if text doesn't work
        if ! click_element "Sign in with Google button" "content-desc" 2; then
            # Fall back to known coordinates as last resort
            log_warning "Using fallback coordinates for Google sign-in button"
            adb shell input tap 540 1500
            sleep 2
        fi
    fi

    # 5. Wait for Google account chooser or sign-in screen
    log_info "Waiting for Google account chooser..."
    take_screenshot "02_google_chooser"

    # 6. Look for and click the test account
    # Google account chooser might show the email or "Continue as [name]"
    log_info "Looking for test account: $TEST_EMAIL"

    # Try to find the test email in the account list
    if click_element "$TEST_EMAIL" "text" 2; then
        log_success "Selected test account"
    else
        # Try to find "Continue as" button (for single account)
        if click_element "Continue as" "partial-text" 2; then
            log_success "Clicked Continue button"
        else
            # Check if we need to add an account
            if click_element "Add another account" "text" 2; then
                log_warning "Need to add account - this requires manual setup"
                log_error "Please manually sign in with $TEST_EMAIL first, then run this script again"
                exit 1
            else
                log_warning "Could not find account selector - checking if already logged in"
            fi
        fi
    fi

    # 7. Wait a moment for any additional prompts
    sleep 3

    # 8. Check for any permission or confirmation dialogs
    # Sometimes Google asks for additional permissions
    if wait_for_element "Allow" "text" 3; then
        click_element "Allow" "text" 1
        log_info "Granted additional permissions"
    fi

    if wait_for_element "Continue" "text" 3; then
        click_element "Continue" "text" 1
        log_info "Clicked Continue"
    fi

    # 9. Wait for app to return and complete login
    log_info "Waiting for login to complete..."
    sleep 5

    # 10. Take final screenshot to verify login state
    take_screenshot "03_after_login"

    # 11. Check if we're logged in by looking for chat UI elements
    if wait_for_element "Type a message" "partial-text" 5 || \
       wait_for_element "Start chatting" "partial-text" 5 || \
       wait_for_element "New Chat" "text" 5; then
        log_success "Login successful! App is now logged in."
    else
        # Check if we're still on login screen
        if wait_for_element "Sign in with Google" "text" 2; then
            log_error "Login failed - still on login screen"
            log_info "This might mean:"
            log_info "  1. The Google account needs to be set up on the device first"
            log_info "  2. The app needs permission to access the account"
            log_info "  3. There was an error during the OAuth flow"
            exit 1
        else
            log_warning "Unknown state - please check the screenshots"
        fi
    fi

    echo "=========================================="
    log_success "Login process completed!"
    echo ""
    echo "Screenshots saved in adb_tests/screenshots/:"
    ls -la "$(dirname "$0")/screenshots"/login_*.png 2>/dev/null | tail -5 || echo "No screenshots found"
    echo "=========================================="
}

# Function to just launch the app (useful for testing if already logged in)
launch_only() {
    echo "=========================================="
    echo "Launching WhizVoice App"
    echo "=========================================="

    log_info "Launching app..."
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
    sleep 2

    take_screenshot "current_state"

    # Check login state
    if wait_for_element "Sign in with Google" "text" 2; then
        log_info "App is on login screen (not logged in)"
    else
        log_success "App appears to be logged in"
    fi
}

# Parse command line arguments
case "${1:-}" in
    --launch)
        launch_only
        ;;
    --no-clear)
        main "--no-clear"
        ;;
    --help)
        echo "Usage: $0 [OPTIONS]"
        echo ""
        echo "Options:"
        echo "  (no options)  Run full login flow"
        echo "  --no-clear    Skip clearing app data"
        echo "  --launch      Just launch the app and check state"
        echo "  --help        Show this help message"
        ;;
    *)
        main
        ;;
esac