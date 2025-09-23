#!/bin/bash

# Deterministic Login Script for WhizVoice
# Assumes fresh install state - uses fixed timing and positions

set -e

PACKAGE_NAME="com.example.whiz.debug"
TEST_EMAIL="REDACTED_TEST_EMAIL"
TEST_PASSWORD="REDACTED_TEST_PASSWORD"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
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

# Main login flow
main() {
    echo "=========================================="
    echo "WhizVoice Deterministic Login"
    echo "=========================================="

    # Check device
    if ! adb devices | grep -q "device$"; then
        log_error "No ADB device connected"
        exit 1
    fi

    # 1. Clear app data (ensure fresh state)
    log_info "Clearing app data for fresh start..."
    adb shell pm clear "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1

    # 2. Launch app
    log_info "Launching WhizVoice app..."
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
    sleep 4  # Wait for app to fully load

    # 3. Take a screenshot to see what's on screen
    log_info "Taking screenshot of current screen..."
    adb shell screencap /sdcard/current_screen.png
    adb pull /sdcard/current_screen.png . 2>/dev/null

    echo "=========================================="
    log_success "App launched!"
    echo ""
    echo "Screenshot saved as: current_screen.png"
    echo "Please review the screenshot to see what's on the screen."
}

# Run main
main "$@"