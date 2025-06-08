#!/bin/bash

# This script performs a full reset of the test environment to resolve
# persistent test failures related to authentication and system state.
#
# WARNING: This is a destructive action and will clear app data and
#          reboot the test device.
#
# Usage: ./reset_test_environment.sh

set -e

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1"
}

log_with_time "🧪 Starting test environment reset..."

# Uninstall the app
log_with_time "➡️ Uninstalling the app..."
adb uninstall com.example.whiz.debug || true

# Clear app data
log_with_time "➡️ Clearing app data..."
adb shell pm clear com.example.whiz.debug || true

# Clear Google Play Services cache
log_with_time "➡️ Clearing Google Play Services cache..."
adb shell pm clear com.google.android.gms || true

# Reboot the device
log_with_time "➡️ Rebooting the device..."
adb reboot

log_with_time "✅ Test environment reset complete. Please wait for the device to reboot." 