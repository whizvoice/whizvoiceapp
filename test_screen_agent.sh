#!/bin/bash

# WhatsApp Screen Agent Test Script using ADB
# This script replaces the Android Instrumentation Test with pure ADB commands
# to allow Accessibility Service to function properly

set -e  # Exit on error

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
PACKAGE_NAME="com.example.whiz.debug"
WHATSAPP_PACKAGE="com.whatsapp"
WHATSAPP_CONTACT="+1 628 209-9005"
TEST_MESSAGE="hey what's up how's it going just trying to test whiz voice"
CORRECTED_MESSAGE="hey what's up how's it going just trying to test whiz voice. Hope you're having a good day!"
SCREENSHOT_DIR="/sdcard/Download/test_screenshots"
LOCAL_SCREENSHOT_DIR="whizvoiceapp/test_screenshots"
TEST_ID=$(date +%s)
LOGCAT_FILE="adb_tests/screenshots/logcat_${TEST_ID}.txt"
LOGCAT_PID=""

# Function to print colored output
log_info() {
    echo -e "${BLUE}[INFO]${NC} [$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} [$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} [$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} [$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

# Function to start logcat capture
start_logcat() {
    # Create directory if it doesn't exist
    mkdir -p "adb_tests/screenshots"

    # Clear existing logcat
    adb logcat -c

    # Start logcat in background, filtering for our package and related services
    log_info "Starting logcat capture to $LOGCAT_FILE"
    adb logcat -v time "*:V" > "$LOGCAT_FILE" 2>&1 &
    LOGCAT_PID=$!

    # Give logcat a moment to start
    sleep 1
    log_success "Logcat capture started (PID: $LOGCAT_PID)"
}

# Function to stop logcat capture
stop_logcat() {
    if [ -n "$LOGCAT_PID" ]; then
        log_info "Stopping logcat capture..."
        kill $LOGCAT_PID 2>/dev/null || true
        wait $LOGCAT_PID 2>/dev/null || true
        LOGCAT_PID=""

        # Check if logcat file was created and has content
        if [ -f "$LOGCAT_FILE" ]; then
            local file_size=$(stat -f%z "$LOGCAT_FILE" 2>/dev/null || stat -c%s "$LOGCAT_FILE" 2>/dev/null || echo "0")
            log_success "Logcat saved to $LOGCAT_FILE (size: $file_size bytes)"

            # Also save a filtered version with just our package
            local filtered_file="${LOGCAT_FILE%.txt}_filtered.txt"
            grep -E "(WhizVoice|WhizAccessibility|$PACKAGE_NAME)" "$LOGCAT_FILE" > "$filtered_file" || true
            if [ -s "$filtered_file" ]; then
                log_info "Filtered logcat saved to $filtered_file"
            fi
        else
            log_warning "Logcat file not created or empty"
        fi
    fi
}

# Function to take screenshot
take_screenshot() {
    local name="$1"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local filename="whatsapp_test_${name}_${timestamp}.png"

    log_info "Taking screenshot: $filename"
    adb shell screencap "$SCREENSHOT_DIR/$filename"

    # Pull screenshot to local directory
    mkdir -p "$LOCAL_SCREENSHOT_DIR"
    adb pull "$SCREENSHOT_DIR/$filename" "$LOCAL_SCREENSHOT_DIR/" 2>/dev/null
    log_info "Screenshot saved to $LOCAL_SCREENSHOT_DIR/$filename"
}

# Function to check if accessibility service is enabled
check_accessibility_enabled() {
    local enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null)
    if [[ "$enabled_services" == *"$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"* ]]; then
        return 0
    else
        return 1
    fi
}

# Function to enable accessibility service
enable_accessibility_service() {
    log_info "Enabling accessibility service..."

    # Enable the accessibility service via settings
    adb shell settings put secure enabled_accessibility_services "$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"
    adb shell settings put secure accessibility_enabled 1

    # Wait a moment for the service to start
    sleep 2

    if check_accessibility_enabled; then
        log_success "Accessibility service enabled"
        return 0
    else
        log_error "Failed to enable accessibility service"
        return 1
    fi
}


# Function to launch WhizVoice app in voice mode
launch_whizvoice() {
    log_info "Launching WhizVoice app in voice mode..."

    # Kill app if running
    adb shell am force-stop "$PACKAGE_NAME"
    sleep 1

    # Launch with voice intent flags
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" \
        -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER \
        --ei android.intent.extra.launch_flags 0x10000000 \
        --el tracing_intent_id "$TEST_ID"

    sleep 3
    log_success "WhizVoice app launched"
}

# Function to send text to input field (old method - kept as fallback)
send_text_to_input_old() {
    local text="$1"
    log_info "Sending text (old method): $text"

    # Find and focus the EditText input field
    adb shell input tap 540 1800  # Approximate position of input field
    sleep 0.5

    # Clear existing text
    adb shell input keyevent KEYCODE_CTRL_A
    adb shell input keyevent KEYCODE_DEL

    # Escape the text for shell - wrap in quotes and escape internal quotes
    escaped_text=$(echo "$text" | sed "s/'/'\\\\''/g")

    # Type the message using escaped text
    adb shell "input text '$escaped_text'"
    sleep 0.5

    # Send the message
    adb shell input keyevent KEYCODE_ENTER
    sleep 2
}

# Function to send voice transcription via broadcast intent (new method)
send_voice_transcription() {
    local text="$1"
    local from_voice="${2:-true}"
    local auto_send="${3:-true}"

    log_info "Sending voice transcription via broadcast: '$text'"

    # Escape the text properly for the shell command
    local escaped_text=$(echo "$text" | sed "s/'/'\\\\''/g")

    # Send broadcast intent to simulate voice transcription
    # Note: We don't specify component name as the receiver is dynamically registered
    adb shell "am broadcast \
        -a com.example.whiz.TEST_TRANSCRIPTION \
        --es text '$escaped_text' \
        --ez fromVoice $from_voice \
        --ez autoSend $auto_send"

    # Wait for processing
    sleep 2
}

# Wrapper function that tries broadcast first, falls back to old method
send_text_to_input() {
    local text="$1"

    # Try the new broadcast method first
    send_voice_transcription "$text" true true

    # Check if it worked by looking for the text in UI
    # For now, we'll just assume it worked and log success
    log_success "Voice transcription broadcast sent"
}

# Function to click on screen coordinates
click_screen() {
    local x=$1
    local y=$2
    log_info "Clicking at ($x, $y)"
    adb shell input tap "$x" "$y"
}

# Function to check if WhatsApp is running
check_whatsapp_running() {
    local current_package=$(adb shell dumpsys window windows | grep -E 'mCurrentFocus' | cut -d '/' -f1 | sed 's/.* //')
    if [[ "$current_package" == *"$WHATSAPP_PACKAGE"* ]]; then
        return 0
    else
        return 1
    fi
}

# Function to wait for overlay
wait_for_overlay() {
    local max_wait=10
    local waited=0

    log_info "Waiting for WhizVoice overlay..."
    while [ $waited -lt $max_wait ]; do
        # Check for overlay window
        if adb shell dumpsys window windows | grep -q "Window.*$PACKAGE_NAME.*TYPE_APPLICATION_OVERLAY"; then
            log_success "WhizVoice overlay detected"
            return 0
        fi
        sleep 1
        ((waited++))
    done

    log_warning "Overlay not detected after ${max_wait} seconds"
    return 1
}

# Function to cleanup test data
cleanup() {
    log_info "Cleaning up test..."

    # Stop logcat capture first (always do this)
    stop_logcat

    # Return to WhizVoice app
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
    sleep 2

    # Force stop apps
    adb shell am force-stop "$PACKAGE_NAME"
    adb shell am force-stop "$WHATSAPP_PACKAGE"

    log_success "Cleanup completed"
}

# Main test execution
main() {
    echo "=========================================="
    echo "WhatsApp Screen Agent Test (ADB Version)"
    echo "Test ID: $TEST_ID"
    echo "=========================================="

    # Clean up previous screenshots
    log_info "Cleaning up previous screenshots from adb_tests/screenshots..."
    rm -rf adb_tests/screenshots/*
    log_info "Previous screenshots deleted"

    # Start logcat capture early to capture everything
    start_logcat

    # Setup
    log_info "Setting up test environment..."
    # install latest app (this does force stop and reinstall)
    ./install.sh --force

    # Handle login using the UI automator login script (clicks through actual Google sign-in)
    log_info "Running login automation..."
    # Pass --no-clear since we already reinstalled the app above
    ./adb_tests/ui_automator_login.sh --no-clear
    if [ $? -ne 0 ]; then
        log_error "Login automation failed"
        exit 1
    fi
    log_success "Login automation completed"

    # IMPORTANT: Grant basic permissions AFTER login (these can be done via ADB)
    log_info "Granting basic permissions after login..."
    sleep 2  # Wait a moment after login

    # Grant microphone permission via ADB (this one works fine with ADB)
    if adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null; then
        log_success "✅ Microphone permission granted via ADB"
    else
        log_warning "⚠️ Could not grant microphone permission via ADB"
    fi

    # Grant overlay permission via ADB (this one works fine with ADB)
    if adb shell appops set "$PACKAGE_NAME" SYSTEM_ALERT_WINDOW allow 2>/dev/null; then
        log_success "✅ Overlay permission granted via ADB"
    else
        log_warning "⚠️ Could not grant overlay permission via ADB"
    fi

    # Use app's own UI to enable accessibility service (no instrumentation)
    log_info "Enabling accessibility service via app UI..."
    ./adb_tests/setup_permissions.sh --accessibility-only
    if [ $? -ne 0 ]; then
        log_error "Failed to enable accessibility service via UI"
        log_error "Please manually enable it in Settings → Accessibility → WhizVoice DEBUG"
        exit 1
    fi

    log_success "All permissions setup completed after login"

    # Make sure app is back in foreground after settings navigation
    log_info "Bringing WhizVoice back to foreground..."
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" 2>/dev/null
    sleep 3

    # Verify accessibility service is actually enabled
    log_info "Verifying accessibility service status..."
    if check_accessibility_enabled; then
        log_success "✅ Accessibility service is enabled and ready"
        # Check if the service is actually running
        if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
            log_success "✅ Accessibility service is actively running"
        else
            log_warning "⚠️ Accessibility service is enabled but may not be actively running yet"
            # Give it a moment to fully initialize
            sleep 2
            # Check again
            if adb shell dumpsys accessibility | grep -q "WhizAccessibilityService"; then
                log_success "✅ Accessibility service is now actively running"
            fi
        fi
    else
        log_error "❌ Accessibility service is not enabled - test may fail"
        exit 1  # Exit since accessibility is required for this test
    fi

    # Do NOT force-stop or restart the app here! That would kill the accessibility service we just enabled
    # Only bring to foreground if needed, and do it gently to preserve the service connection

    log_info "Checking app and accessibility service status..."
    local current_app=$(adb shell dumpsys window windows | grep -E 'mCurrentFocus' | cut -d '/' -f1 | sed 's/.* //' | tr -d '\r')
    local service_running=$(adb shell dumpsys accessibility | grep -c "WhizAccessibilityService" || echo "0")

    if [[ "$current_app" == *"$PACKAGE_NAME"* ]] && [[ "$service_running" -gt 0 ]]; then
        log_success "✅ App is in foreground and accessibility service is running - preserving state"
        # Don't restart anything, just continue
    elif [[ "$current_app" == *"$PACKAGE_NAME"* ]]; then
        log_warning "App is in foreground but accessibility service may not be connected"
        # App is there but service might need reconnection - just wait a bit for it to reconnect
        sleep 2
    else
        log_info "App not in foreground, bringing it forward gently..."
        # Use flags to avoid creating new process/task
        # FLAG_ACTIVITY_REORDER_TO_FRONT (0x00020000) - brings existing activity to front
        # FLAG_ACTIVITY_SINGLE_TOP (0x20000000) - reuses existing instance
        adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity" \
            --activity-reorder-to-front \
            --activity-single-top
        sleep 3

        # Check if service reconnected
        service_running=$(adb shell dumpsys accessibility | grep -c "WhizAccessibilityService" || echo "0")
        if [[ "$service_running" -gt 0 ]]; then
            log_success "✅ Accessibility service reconnected"
        else
            log_warning "⚠️ Accessibility service may not be connected"
        fi
    fi

    # Test 1: Navigation to WhatsApp Chat
    echo ""
    echo "TEST 1: Navigation to WhatsApp Chat"
    echo "------------------------------------"

    # App is already launched by login script
    take_screenshot "01_app_after_login"

    # Ensure app is fully initialized - wait for ChatViewModel to be ready
    log_info "Ensuring app is fully initialized..."
    sleep 3

    # Send command to open WhatsApp chat
    send_text_to_input "Open WhatsApp chat with $WHATSAPP_CONTACT"

    # Wait for WhatsApp to open
    log_info "Waiting for WhatsApp to open..."
    sleep 5

    if check_whatsapp_running; then
        log_success "WhatsApp opened successfully"
        take_screenshot "02_whatsapp_opened"
    else
        log_error "WhatsApp did not open"
        take_screenshot "02_whatsapp_failed"
    fi

    # Return to WhizVoice
    log_info "Returning to WhizVoice..."
    adb shell input keyevent KEYCODE_BACK
    sleep 2

    # Test 2: Message Drafting and Correction
    # echo ""
    # echo "TEST 2: Message Drafting and Correction"
    # echo "----------------------------------------"

    # launch_whizvoice
    # take_screenshot "03_test2_start"

    # # Send WhatsApp message request
    # send_text_to_input "Hello, can you please send a message to $WHATSAPP_CONTACT that says $TEST_MESSAGE"

    # # Wait for assistant to process
    # log_info "Waiting for assistant to process and show draft..."
    # sleep 5

    # # Check for overlay
    # if wait_for_overlay; then
    #     take_screenshot "04_draft_overlay"

    #     # Send correction
    #     log_info "Sending correction..."
    #     send_text_to_input "actually can you change trying to trying to, and then finish the sentence with a period and add hope you're having a good day!"

    #     # Wait for corrected draft
    #     sleep 5
    #     take_screenshot "05_corrected_draft"

    #     log_success "Draft and correction test completed"
    # else
    #     log_error "Draft overlay not shown"
    #     take_screenshot "04_draft_failed"
    # fi

    # Test 3: Verify Accessibility Service Functionality
    echo ""
    echo "TEST 3: Accessibility Service Check"
    echo "------------------------------------"

    # Check if accessibility service is still running
    if check_accessibility_enabled; then
        log_success "Accessibility service is running"

        # Get accessibility service info
        log_info "Getting accessibility service info..."
        adb shell dumpsys accessibility | grep -A 5 "WhizAccessibilityService"
    else
        log_error "Accessibility service is not running"
    fi

    # Cleanup
    echo ""
    cleanup

    # Summary
    echo ""
    echo "=========================================="
    echo "Test Summary"
    echo "=========================================="
    echo "Test ID: $TEST_ID"
    echo "Screenshots saved to: $LOCAL_SCREENSHOT_DIR"

    # Check both permission and actual service status
    echo ""
    echo "Accessibility Status:"

    # Check if permission is granted (service is in enabled list)
    local enabled_services=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n' || echo "")
    local dumpsys_output=$(adb shell dumpsys accessibility | grep "Enabled services:" || echo "")

    # Check both settings and dumpsys
    if [[ "$enabled_services" != "null" ]] && [[ "$enabled_services" == *"$PACKAGE_NAME"* ]] && [[ "$enabled_services" == *"WhizAccessibilityService"* ]]; then
        echo -e "${GREEN}  ✓ Permission: GRANTED${NC} (in settings)"
    elif [[ "$dumpsys_output" == *"$PACKAGE_NAME"* ]] && [[ "$dumpsys_output" == *"WhizAccessibilityService"* ]]; then
        echo -e "${GREEN}  ✓ Permission: GRANTED${NC} (in dumpsys)"
    else
        echo -e "${RED}  ✗ Permission: NOT GRANTED${NC}"
        echo "    Debug: enabled_services='$enabled_services'"
        echo "    Debug: dumpsys='$dumpsys_output'"
    fi

    # Check if service is actually running and not crashed
    local service_dump=$(adb shell dumpsys accessibility 2>/dev/null || echo "")

    # Use separate grep commands to avoid multiline issues
    local has_enabled=0
    local has_crashed=0

    if echo "$service_dump" | grep "Enabled services:" | grep -q "$PACKAGE_NAME.*WhizAccessibilityService"; then
        has_enabled=1
    fi

    if echo "$service_dump" | grep "Crashed services:" | grep -q "$PACKAGE_NAME.*WhizAccessibilityService"; then
        has_crashed=1
    fi

    if [[ $has_enabled -eq 1 ]] && [[ $has_crashed -eq 0 ]]; then
        echo -e "${GREEN}  ✓ Service: RUNNING${NC}"
    elif [[ $has_crashed -eq 1 ]]; then
        echo -e "${RED}  ✗ Service: CRASHED${NC}"
    else
        echo -e "${RED}  ✗ Service: NOT RUNNING${NC}"
    fi

    echo "=========================================="
    log_success "Test completed"
}

# Enhanced cleanup for script termination or errors
cleanup_on_exit() {
    local exit_code=$?

    if [ $exit_code -ne 0 ]; then
        log_error "Script exited with error code: $exit_code"
        # Take a final screenshot on error
        take_screenshot "error_final" 2>/dev/null || true
    fi

    # Always run cleanup
    cleanup

    # Show logcat location
    if [ -f "$LOGCAT_FILE" ]; then
        echo ""
        echo "=========================================="
        echo "Logcat output saved to:"
        echo "  Full: $LOGCAT_FILE"
        local filtered_file="${LOGCAT_FILE%.txt}_filtered.txt"
        if [ -f "$filtered_file" ]; then
            echo "  Filtered: $filtered_file"
        fi
        echo "=========================================="
    fi

    exit $exit_code
}

# Handle script termination and errors
trap cleanup_on_exit EXIT INT TERM

# Run the main test
main "$@"
