#!/bin/bash

# Programmatic Login Script for WhizVoice
# Uses am instrument to trigger test authentication

set -e

PACKAGE_NAME="com.example.whiz.debug"
TEST_PACKAGE_NAME="com.example.whiz.debug.test"
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

# Function to check if test APK is installed
check_test_apk() {
    if adb shell pm list packages | grep -q "$TEST_PACKAGE_NAME"; then
        return 0
    else
        return 1
    fi
}

# Function to build and install test APK if needed
ensure_test_apk() {
    if ! check_test_apk; then
        log_warning "Test APK not found. Building and installing..."

        # Build the test APK
        log_info "Building test APK..."
        (cd .. && ./gradlew assembleDebugAndroidTest)

        # Install the test APK
        log_info "Installing test APK..."
        adb install -r ../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

        if check_test_apk; then
            log_success "Test APK installed successfully"
        else
            log_error "Failed to install test APK"
            exit 1
        fi
    else
        log_success "Test APK already installed"
    fi
}

# Main login flow
main() {
    echo "=========================================="
    echo "WhizVoice Programmatic Login"
    echo "=========================================="

    # 1. Ensure test APK is installed
    ensure_test_apk

    # 2. Clear app data to ensure clean state (optional - comment out to keep existing data)
    log_info "Clearing app data for clean login..."
    adb shell pm clear "$PACKAGE_NAME" || log_warning "Could not clear app data (app might not be installed)"

    # 3. Perform programmatic login using am instrument
    log_info "Performing programmatic login with test account: $TEST_EMAIL"
    log_info "This will authenticate and save tokens to the app..."

    # Run the test that performs login
    if adb shell am instrument -w \
        -e class com.example.whiz.StandaloneLoginTest#performLogin \
        "$TEST_PACKAGE_NAME/com.example.whiz.HiltTestRunner" | tee login_output.log; then

        # Check if login was successful by looking at the output
        if grep -q "OK (1 test)" login_output.log; then
            log_success "Programmatic login completed successfully!"
        else
            log_error "Login test failed. Check login_output.log for details"
            exit 1
        fi
    else
        log_error "Failed to run login instrumentation test"
        exit 1
    fi

    # 4. Launch the app to verify login worked
    log_info "Launching WhizVoice app to verify login..."
    adb shell am start -n "$PACKAGE_NAME/com.example.whiz.MainActivity"
    sleep 2  # Wait for app to load

    # 5. Take a screenshot to verify we're logged in
    log_info "Taking screenshot to verify login state..."
    adb shell screencap /sdcard/logged_in_screen.png
    adb pull /sdcard/logged_in_screen.png . 2>/dev/null

    echo "=========================================="
    log_success "Login process completed!"
    echo ""
    echo "The app should now be logged in as: $TEST_EMAIL"
    echo "Screenshot saved as: logged_in_screen.png"
    echo ""
    echo "To logout, run: $0 --logout"
    echo "=========================================="
}

# Function to perform logout
logout() {
    echo "=========================================="
    echo "WhizVoice Programmatic Logout"
    echo "=========================================="

    ensure_test_apk

    log_info "Performing programmatic logout..."

    if adb shell am instrument -w \
        -e class com.example.whiz.StandaloneLoginTest#performLogout \
        "$TEST_PACKAGE_NAME/com.example.whiz.HiltTestRunner" | tee logout_output.log; then

        if grep -q "OK (1 test)" logout_output.log; then
            log_success "Logout completed successfully!"
        else
            log_error "Logout test failed. Check logout_output.log for details"
            exit 1
        fi
    else
        log_error "Failed to run logout instrumentation test"
        exit 1
    fi

    log_success "App has been logged out"
}

# Parse command line arguments
if [ "$1" == "--logout" ]; then
    logout
else
    main
fi