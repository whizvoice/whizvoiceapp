#!/bin/bash

# Run tests on WhizVoice Debug version with latest changes
# This builds, installs latest debug version, then runs all tests
# The debug app stays installed after testing for manual testing
# All output is logged to test_output.log for monitoring
#
# Usage:
#   ./run_tests_on_debug.sh          # Run tests and keep app installed (default)
#   ./run_tests_on_debug.sh --clean  # Run tests and uninstall app after

set -e

# Parse command line arguments
CLEAN_AFTER_TESTS=false
if [[ "$1" == "--clean" ]]; then
    CLEAN_AFTER_TESTS=true
fi

# Clear previous log file
> test_output.log

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1" | tee -a test_output.log
}

# Function to run command with logging and timing
run_with_log() {
    local description="$1"
    local command="$2"
    local start_time=$(date +%s.%3N)
    
    log_with_time "⏳ $description"
    
    # Run command and capture output - only log to file, show minimal console output
    if eval "$command" >> test_output.log 2>&1; then
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ $description completed in ${duration}s"
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ $description failed in ${duration}s (exit code: $exit_code)"
        echo "❌ Check test_output.log for details" | tee -a test_output.log
        return $exit_code
    fi
    echo "" | tee -a test_output.log
}

# Function to monitor test progress in background with detailed status
monitor_tests() {
    local test_type="$1"
    sleep 3 # Wait for tests to start
    
    local last_test_count=0
    local last_status_time=$(date +%s)
    
    while true; do
        # Check if gradlew process is still running
        if ! pgrep -f "gradlew.*Test" > /dev/null; then
            break
        fi
        
        local current_time=$(date +%s)
        local time_since_status=$((current_time - last_status_time))
        
        # Try to get current test progress from gradle output or logcat
        local test_status=""
        
        if [[ "$test_type" == "Instrumented tests" ]]; then
            # For instrumented tests, read from the progress file
            if [[ -f ".test_status" ]]; then
                local current_test=$(grep "CURRENT_TEST=" .test_status 2>/dev/null | cut -d'=' -f2 | tail -1)
                local running_test=$(grep "RUNNING_TEST=" .test_status 2>/dev/null | cut -d'=' -f2 | tail -1)
                local current_count=$(grep "CURRENT_COUNT=" .test_status 2>/dev/null | cut -d'=' -f2 | tail -1)
                local completed_count=$(grep "COMPLETED_COUNT=" .test_status 2>/dev/null | cut -d'=' -f2 | tail -1)
                
                # Show which test is currently running
                local active_test=""
                if [[ -n "$running_test" && "$running_test" != "" ]]; then
                    active_test="$running_test"
                elif [[ -n "$current_test" && "$current_test" != "" ]]; then
                    active_test="$current_test"
                fi
                
                # Show progress numbers
                if [[ -n "$completed_count" && "$completed_count" -gt 0 ]]; then
                    if [[ -n "$active_test" ]]; then
                        test_status="Running: $active_test ($completed_count completed)"
                    else
                        test_status="Progress: $completed_count tests completed"
                    fi
                elif [[ -n "$current_count" && "$current_count" -gt 0 ]]; then
                    if [[ -n "$active_test" ]]; then
                        test_status="Running: $active_test ($current_count started)"
                    else
                        test_status="Progress: $current_count tests started"
                    fi
                else
                    # Check if gradle output shows any activity and count test-related lines
                    local gradle_test_lines=$(grep -c -E "(Test|started|finished|executed)" test_output.log 2>/dev/null || echo "0")
                    local gradle_activity=$(tail -5 test_output.log 2>/dev/null | grep -E "(Test|BUILD|SUCCESSFUL|started|finished)" | tail -1)
                    if [[ "$gradle_test_lines" -gt 10 ]]; then
                        test_status="Working... ($gradle_test_lines test-related log lines)"
                    elif [[ -n "$gradle_activity" ]]; then
                        test_status="Gradle working on instrumented tests..."
                    else
                        test_status="Starting instrumented tests..."
                    fi
                fi
            else
                test_status="Initializing instrumented tests..."
            fi
                 else
            # For unit tests, try to parse from gradle console output  
            local unit_progress=$(grep -E "executed|completed|tests run" test_output.log | tail -1 | grep -o '[0-9]\+ tests' | head -1 2>/dev/null || echo "")
            local gradle_test_count=$(grep -c "Test " test_output.log 2>/dev/null || echo "0")
            
            if [[ -n "$unit_progress" ]]; then
                test_status="Progress: $unit_progress"
            elif [[ "$gradle_test_count" -gt 0 ]]; then
                test_status="Working... ($gradle_test_count test activities logged)"
            else
                test_status="Running unit tests..."
            fi
        fi
        
        # Show status every 15 seconds, or immediately if we have new progress
        if [[ $time_since_status -ge 15 ]] || [[ "$test_status" != *"$last_test_count"* ]]; then
            if [[ -n "$test_status" ]]; then
                log_with_time "📊 $test_type: $test_status"
            else
                log_with_time "📊 $test_type still running..."
            fi
            last_status_time=$current_time
        fi
        
        sleep 10 # Check every 10 seconds for more responsive updates
    done
}

# Function to count total tests
count_total_tests() {
    echo "⏳ Counting total tests..." | tee -a test_output.log
    local unit_test_count=$(find app/src/test -name "*.kt" -exec grep -c "@Test" {} \; 2>/dev/null | awk '{sum += $1} END {print sum+0}')
    local integration_test_count=$(find app/src/androidTest -name "*.kt" -exec grep -c "@Test" {} \; 2>/dev/null | awk '{sum += $1} END {print sum+0}')
    local total_tests=$((unit_test_count + integration_test_count))
    
    log_with_time "📊 Found $unit_test_count unit tests + $integration_test_count instrumented tests = $total_tests total tests"
}

# Function to read test credentials from JSON file
read_test_credentials() {
    local credentials_file="test_credentials.json"
    if [[ ! -f "$credentials_file" ]]; then
        log_with_time "❌ ERROR: $credentials_file not found. Please create it with test credentials."
        exit 1
    fi
    
    # Read username and password using a simple parser (avoids jq dependency)
    TEST_USERNAME=$(grep -o '"email": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)
    TEST_PASSWORD=$(grep -o '"password": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)
    
    if [[ -z "$TEST_USERNAME" || -z "$TEST_PASSWORD" || "$TEST_PASSWORD" == "REPLACE_WITH_ACTUAL_PASSWORD" ]]; then
        log_with_time "❌ ERROR: Test credentials in $credentials_file are incomplete or placeholders."
        log_with_time "Please ensure 'email' and 'password' are set correctly for the 'google_test_account'."
        exit 1
    fi
    
    log_with_time "🔑 Successfully read test credentials for user: $TEST_USERNAME"
}

log_with_time "🧪 Running tests on WhizVoice Debug with latest changes..."

# Count and display total tests
count_total_tests

# Read test credentials
read_test_credentials

if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    log_with_time "🗑️  Will uninstall debug app after tests complete"
else
    log_with_time "📱 Debug app will stay installed for manual testing after tests"
fi
echo "" | tee -a test_output.log

# Build the latest debug APK
run_with_log "Building latest debug version" "./gradlew assembleDebug --console=plain --quiet"

# Install the latest debug APK (updates existing installation if present)
run_with_log "Installing/updating latest debug APK" "adb install -r app/build/outputs/apk/debug/app-debug.apk"

# Grant necessary permissions for testing (especially microphone for voice tests)
log_with_time "🔐 Granting necessary permissions for testing..."
# Launch app first, then grant permissions while it's running
adb shell am start -n com.example.whiz.debug/com.example.whiz.MainActivity >/dev/null 2>&1 || true
sleep 3
adb shell pm grant com.example.whiz.debug android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant com.example.whiz.debug android.permission.WRITE_SETTINGS 2>/dev/null || true
# Stop the app to avoid interference with tests
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true
sleep 1
# Verify permission was granted
if adb shell dumpsys package com.example.whiz.debug | grep -q "android.permission.RECORD_AUDIO: granted=true"; then
    log_with_time "✅ Microphone permission granted successfully"
else
    log_with_time "⚠️ Microphone permission may not be granted - tests might fail"
fi



# Function to sign out current user using proper Firebase/Google authentication
sign_out_current_user() {
    log_with_time "🔄 Signing out current user using Firebase authentication..."
    
    # Method 1: Use the proper Firebase sign-out intent
    log_with_time "🔥 Triggering Firebase sign-out via MainActivity intent..."
    adb shell am start -n com.example.whiz/com.example.whiz.MainActivity \
        --es "action" "sign_out" >/dev/null 2>&1
    sleep 3
    
    # Verify sign-out by checking if we're back to login screen
    adb shell uiautomator dump /sdcard/signout_verify.xml >/dev/null 2>&1
    adb pull /sdcard/signout_verify.xml /tmp/signout_verify.xml >/dev/null 2>&1
    
    if grep -q -E "(Sign in|Welcome to WhizVoice|Sign in with Google)" /tmp/signout_verify.xml 2>/dev/null; then
        log_with_time "✅ Firebase sign-out successful - app is back to login screen"
        adb shell rm /sdcard/signout_verify.xml >/dev/null 2>&1 || true
        rm -f /tmp/signout_verify.xml >/dev/null 2>&1 || true
        return 0
    else
        log_with_time "⚠️  Firebase sign-out may not have completed, trying fallback method..."
        
        # Method 2: Force-stop and clear app data (fallback)
        log_with_time "🧹 Using fallback - clearing app data..."
        adb shell am force-stop com.example.whiz
        sleep 1
        
        # Clear app data to reset authentication state (like fresh install)
        adb shell pm clear com.example.whiz >/dev/null 2>&1
        sleep 2
        
        # Restart the app - should now be in unauthenticated state
        log_with_time "🚀 Restarting app to verify fallback sign-out..."
        adb shell am start -n com.example.whiz/com.example.whiz.MainActivity >/dev/null 2>&1
        sleep 3
        
        # Verify we're back to login screen
        adb shell uiautomator dump /sdcard/signout_verify2.xml >/dev/null 2>&1
        adb pull /sdcard/signout_verify2.xml /tmp/signout_verify2.xml >/dev/null 2>&1
        
        if grep -q -E "(Sign in|Welcome to WhizVoice|Sign in with Google)" /tmp/signout_verify2.xml 2>/dev/null; then
            log_with_time "✅ Fallback sign-out successful - app is back to login screen"
            adb shell rm /sdcard/signout_verify.xml /sdcard/signout_verify2.xml >/dev/null 2>&1 || true
            rm -f /tmp/signout_verify.xml /tmp/signout_verify2.xml >/dev/null 2>&1 || true
            return 0
        else
            log_with_time "⚠️  Fallback may not have completed, trying ui method..."
        
        # Method 2: Try UI-based sign out as fallback
        adb shell uiautomator dump /sdcard/signout_check.xml >/dev/null 2>&1
        adb pull /sdcard/signout_check.xml /tmp/signout_check.xml >/dev/null 2>&1
        
        # Look for menu, settings, or profile options
        local menu_bounds=$(grep -o -E 'content-desc="(Menu|Settings|Profile|More options)"[^>]*bounds="\[[0-9,]*\]"' /tmp/signout_check.xml | grep -o 'bounds="\[[0-9,]*\]"' | head -1 | grep -o '\[[0-9,]*\]')
        
        if [[ -n "$menu_bounds" ]]; then
            local coords=$(echo "$menu_bounds" | tr -d '[]' | tr ',' ' ')
            local x1=$(echo $coords | cut -d' ' -f1)
            local y1=$(echo $coords | cut -d' ' -f2)
            local x2=$(echo $coords | cut -d' ' -f3)
            local y2=$(echo $coords | cut -d' ' -f4)
            local center_x=$(( (x1 + x2) / 2 ))
            local center_y=$(( (y1 + y2) / 2 ))
            
            log_with_time "📱 Tapping menu/settings at ($center_x, $center_y)"
            adb shell input tap $center_x $center_y
            sleep 2
            
            # Look for sign out option
            adb shell uiautomator dump /sdcard/menu_check.xml >/dev/null 2>&1
            adb pull /sdcard/menu_check.xml /tmp/menu_check.xml >/dev/null 2>&1
            
            local signout_bounds=$(grep -o -E 'text="(Sign out|Logout|Log out)"[^>]*bounds="\[[0-9,]*\]"' /tmp/menu_check.xml | grep -o 'bounds="\[[0-9,]*\]"' | head -1 | grep -o '\[[0-9,]*\]')
            
            if [[ -n "$signout_bounds" ]]; then
                local coords=$(echo "$signout_bounds" | tr -d '[]' | tr ',' ' ')
                local x1=$(echo $coords | cut -d' ' -f1)
                local y1=$(echo $coords | cut -d' ' -f2)
                local x2=$(echo $coords | cut -d' ' -f3)
                local y2=$(echo $coords | cut -d' ' -f4)
                local center_x=$(( (x1 + x2) / 2 ))
                local center_y=$(( (y1 + y2) / 2 ))
                
                log_with_time "📱 Tapping sign out at ($center_x, $center_y)"
                adb shell input tap $center_x $center_y
                sleep 3
                
                # Verify sign out worked
                adb shell am start -n com.example.whiz/com.example.whiz.MainActivity >/dev/null 2>&1
                sleep 3
                
                adb shell uiautomator dump /sdcard/final_verify.xml >/dev/null 2>&1
                adb pull /sdcard/final_verify.xml /tmp/final_verify.xml >/dev/null 2>&1
                
                if grep -q -E "(Sign in|Welcome to WhizVoice|Sign in with Google)" /tmp/final_verify.xml 2>/dev/null; then
                    log_with_time "✅ UI-based sign out successful"
                    return 0
                fi
            fi
        fi
        
        log_with_time "❌ Could not complete sign out"
        fi
        return 1
    fi
    
    # Clean up temp files
    adb shell rm /sdcard/signout_check.xml /sdcard/menu_check.xml /sdcard/signout_verify.xml /sdcard/final_verify.xml >/dev/null 2>&1 || true
    rm -f /tmp/signout_check.xml /tmp/menu_check.xml /tmp/signout_verify.xml /tmp/final_verify.xml >/dev/null 2>&1 || true
}



# Run unit tests first (fast, always use latest code)
log_with_time "🧪 Starting unit tests..."
monitor_tests "Unit tests" &
MONITOR_PID=$!
run_with_log "Running unit tests with latest code" "./gradlew testDebugUnitTest --console=plain"
kill $MONITOR_PID 2>/dev/null || true

# Run instrumented tests on debug build
log_with_time "🧪 Starting instrumented tests..."

# Clean up old screenshots and create fresh directory for failed tests
SCREENSHOTS_DIR="test_screenshots"
if [[ -d "$SCREENSHOTS_DIR" ]]; then
    log_with_time "🧹 Cleaning up old screenshots from previous test runs..."
    rm -rf "$SCREENSHOTS_DIR"/*
    log_with_time "✅ Cleaned up old screenshots"
fi
mkdir -p "$SCREENSHOTS_DIR"
log_with_time "📸 Created screenshots directory: $SCREENSHOTS_DIR"

# Start logcat monitoring in background to capture test logs
log_with_time "📱 Starting logcat monitoring for test details..."
adb logcat -c  # Clear logcat

# Monitor test execution with detailed logging
adb logcat "*:I" | grep -E "(TestRunner|InstrumentationResultPrinter|started:|finished:|INSTRUMENTATION_STATUS)" | while read line; do
    echo "[$(date '+%H:%M:%S.%3N')] LOGCAT: $line" >> test_output.log
done &
LOGCAT_PID=$!

# Create a real-time test progress monitor by parsing gradle output
rm -f .test_progress .test_status 2>/dev/null || true
touch .test_status

# Monitor gradle output for test progress in background
monitor_test_progress() {
    tail -f test_output.log 2>/dev/null | while IFS= read -r line; do
        # Look for gradle test execution patterns
        if [[ "$line" == *"started"* && "$line" == *"Test"* ]]; then
            test_name=$(echo "$line" | sed -E 's/.*Test ([^[:space:]]+) started.*/\1/' | head -c 50)
            if [[ -n "$test_name" && "$test_name" != "$line" ]]; then
                current_count=$(grep -c "CURRENT_TEST=" .test_status 2>/dev/null || echo "0")
                current_count=$((current_count + 1))
                echo "CURRENT_TEST=$test_name" >> .test_status  
                echo "CURRENT_COUNT=$current_count" >> .test_status
            fi
        elif [[ "$line" == *"tests completed"* ]]; then
            completed=$(echo "$line" | grep -o '[0-9]\+ tests completed' | grep -o '[0-9]\+')
            if [[ -n "$completed" ]]; then
                echo "COMPLETED_COUNT=$completed" >> .test_status
            fi
        elif [[ "$line" == *"Executing test"* ]] || [[ "$line" == *"Running test"* ]]; then
            test_name=$(echo "$line" | sed -E 's/.*test[[:space:]]+([^[:space:]]+).*/\1/' | head -c 50)
            if [[ -n "$test_name" && "$test_name" != "$line" ]]; then
                echo "RUNNING_TEST=$test_name" >> .test_status
            fi
        fi
    done
}

monitor_test_progress &
TEST_PROGRESS_PID=$!

monitor_tests "Instrumented tests" &
MONITOR_PID=$!

# Function to capture screenshots on test failure
capture_test_screenshots() {
    log_with_time "📸 Capturing screenshots for failed tests..."
    
    # Wait a moment for any UI animations to settle
    sleep 2
    
    # Capture current screen
    local timestamp=$(date '+%Y%m%d_%H%M%S')
    local screenshot_file="$SCREENSHOTS_DIR/test_failure_${timestamp}.png"
    
    if adb exec-out screencap -p > "$screenshot_file" 2>/dev/null; then
        log_with_time "📸 Screenshot saved: $screenshot_file"
    else
        log_with_time "⚠️  Failed to capture screenshot"
    fi
    
    # UI hierarchy dump removed for faster debugging - screenshots are usually sufficient
    
    # Get current app state from logcat
    local logcat_snippet="$SCREENSHOTS_DIR/recent_logs_${timestamp}.txt"
    adb logcat -d -t 100 | grep -E "(WhizVoice|Test|Error|Exception)" > "$logcat_snippet" || true
    if [[ -s "$logcat_snippet" ]]; then
        log_with_time "📝 Recent logs saved: $logcat_snippet"
    fi
}

# Run tests and capture screenshots on failure
# Pass credentials via instrumentation arguments with --no-configuration-cache to avoid warnings
log_with_time "🔑 Passing test credentials via instrumentation arguments..."
if ! run_with_log "Running instrumented tests with credentials" "./gradlew connectedDebugAndroidTest --console=plain --no-configuration-cache -Pandroid.testInstrumentationRunnerArguments.testUsername=\"$TEST_USERNAME\" -Pandroid.testInstrumentationRunnerArguments.testPassword=\"$TEST_PASSWORD\""; then
    # Tests failed, capture screenshots and debugging info
    capture_test_screenshots
    TEST_EXIT_CODE=$?
else
    TEST_EXIT_CODE=0
fi

# Clean up background processes gracefully
log_with_time "🧹 Starting cleanup of background monitoring processes..."

# List all background processes before cleanup
log_with_time "📊 Background processes before cleanup:"
if [[ -n "$TEST_PROGRESS_PID" ]]; then
    if kill -0 $TEST_PROGRESS_PID 2>/dev/null; then
        log_with_time "   • Test progress monitor (PID: $TEST_PROGRESS_PID) - RUNNING"
    else
        log_with_time "   • Test progress monitor (PID: $TEST_PROGRESS_PID) - ALREADY STOPPED"
    fi
fi
if [[ -n "$MONITOR_PID" ]]; then
    if kill -0 $MONITOR_PID 2>/dev/null; then
        log_with_time "   • Test status monitor (PID: $MONITOR_PID) - RUNNING"
    else
        log_with_time "   • Test status monitor (PID: $MONITOR_PID) - ALREADY STOPPED"
    fi
fi
if [[ -n "$LOGCAT_PID" ]]; then
    if kill -0 $LOGCAT_PID 2>/dev/null; then
        log_with_time "   • Logcat monitor (PID: $LOGCAT_PID) - RUNNING"
    else
        log_with_time "   • Logcat monitor (PID: $LOGCAT_PID) - ALREADY STOPPED"
    fi
fi

# Clean up test progress monitoring
if [[ -n "$TEST_PROGRESS_PID" ]]; then
    log_with_time "🔄 Stopping test progress monitoring (PID: $TEST_PROGRESS_PID)..."
    if kill -0 $TEST_PROGRESS_PID 2>/dev/null; then
        log_with_time "   • Sending TERM signal..."
        kill $TEST_PROGRESS_PID >/dev/null 2>&1 || true
        
        # Wait up to 5 seconds for graceful shutdown
        wait_count=0
        while kill -0 $TEST_PROGRESS_PID 2>/dev/null && [[ $wait_count -lt 5 ]]; do
            sleep 1
            wait_count=$((wait_count + 1))
            log_with_time "   • Waiting for graceful shutdown... ($wait_count/5)"
        done
        
        if kill -0 $TEST_PROGRESS_PID 2>/dev/null; then
            log_with_time "   • Process still running, sending KILL signal..."
            kill -9 $TEST_PROGRESS_PID >/dev/null 2>&1 || true
            sleep 1
        fi
        
        # Use timeout for wait to prevent hanging
        log_with_time "   • Finalizing process cleanup..."
        timeout 1 wait $TEST_PROGRESS_PID >/dev/null 2>&1 || true
        log_with_time "✅ Test progress monitoring stopped"
    else
        log_with_time "✅ Test progress monitoring was already stopped"
    fi
else
    log_with_time "ℹ️  No test progress monitoring PID to clean up"
fi

# Clean up test status monitoring
if [[ -n "$MONITOR_PID" ]]; then
    log_with_time "🔄 Stopping test status monitoring (PID: $MONITOR_PID)..."
    if kill -0 $MONITOR_PID 2>/dev/null; then
        log_with_time "   • Sending TERM signal..."
        kill $MONITOR_PID >/dev/null 2>&1 || true
        
        # Wait up to 5 seconds for graceful shutdown
        wait_count=0
        while kill -0 $MONITOR_PID 2>/dev/null && [[ $wait_count -lt 5 ]]; do
            sleep 1
            wait_count=$((wait_count + 1))
            log_with_time "   • Waiting for graceful shutdown... ($wait_count/5)"
        done
        
        if kill -0 $MONITOR_PID 2>/dev/null; then
            log_with_time "   • Process still running, sending KILL signal..."
            kill -9 $MONITOR_PID >/dev/null 2>&1 || true
            sleep 1
        fi
        
        # Use timeout for wait to prevent hanging
        log_with_time "   • Finalizing process cleanup..."
        timeout 3 wait $MONITOR_PID >/dev/null 2>&1 || true
        log_with_time "✅ Test status monitoring stopped"
    else
        log_with_time "✅ Test status monitoring was already stopped"
    fi
else
    log_with_time "ℹ️  No test status monitoring PID to clean up"
fi

# Clean up logcat monitoring
if [[ -n "$LOGCAT_PID" ]]; then
    log_with_time "🔄 Stopping logcat monitoring (PID: $LOGCAT_PID)..."
    if kill -0 $LOGCAT_PID 2>/dev/null; then
        log_with_time "   • Sending TERM signal..."
        kill $LOGCAT_PID >/dev/null 2>&1 || true
        
        # Wait up to 5 seconds for graceful shutdown
        wait_count=0
        while kill -0 $LOGCAT_PID 2>/dev/null && [[ $wait_count -lt 5 ]]; do
            sleep 1
            wait_count=$((wait_count + 1))
            log_with_time "   • Waiting for graceful shutdown... ($wait_count/5)"
        done
        
        if kill -0 $LOGCAT_PID 2>/dev/null; then
            log_with_time "   • Process still running, sending KILL signal..."
            kill -9 $LOGCAT_PID >/dev/null 2>&1 || true
            sleep 1
        fi
        
        # Use timeout for wait to prevent hanging
        log_with_time "   • Finalizing process cleanup..."
        timeout 3 wait $LOGCAT_PID >/dev/null 2>&1 || true
        log_with_time "✅ Logcat monitoring stopped"
    else
        log_with_time "✅ Logcat monitoring was already stopped"
    fi
else
    log_with_time "ℹ️  No logcat monitoring PID to clean up"
fi

# Clean up any remaining gradle processes
log_with_time "🔄 Checking for any remaining gradle processes..."
gradle_pids=$(pgrep -f "gradlew.*Test" || true)
if [[ -n "$gradle_pids" ]]; then
    log_with_time "⚠️  Found remaining gradle test processes: $gradle_pids"
    log_with_time "   • Attempting to stop them..."
    echo "$gradle_pids" | xargs -r kill >/dev/null 2>&1 || true
    sleep 2
    
    # Check if they're still running
    remaining_gradle=$(pgrep -f "gradlew.*Test" || true)
    if [[ -n "$remaining_gradle" ]]; then
        log_with_time "   • Some gradle processes still running, force killing: $remaining_gradle"
        echo "$remaining_gradle" | xargs -r kill -9 >/dev/null 2>&1 || true
    fi
    log_with_time "✅ Gradle process cleanup completed"
else
    log_with_time "✅ No remaining gradle processes found"
fi

# Clean up progress files
log_with_time "🔄 Cleaning up temporary files..."
if [[ -f ".test_progress" ]]; then
    rm -f .test_progress
    log_with_time "   • Removed .test_progress"
fi
if [[ -f ".test_status" ]]; then
    rm -f .test_status
    log_with_time "   • Removed .test_status"
fi
log_with_time "✅ Temporary files cleaned up"

log_with_time "✅ All background processes and files cleaned up successfully"

# Reinstall debug app after tests (gradle uninstalls it automatically)
if [[ "$CLEAN_AFTER_TESTS" == "false" ]]; then
    echo "" | tee -a test_output.log
    log_with_time "📱 Gradle auto-uninstalled debug app after tests, reinstalling for manual testing..."
    run_with_log "Reinstalling debug app for manual testing" "adb install -r app/build/outputs/apk/debug/app-debug.apk"
fi

# Optionally clean up the debug app
if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    echo "" | tee -a test_output.log
    run_with_log "Uninstalling debug app (--clean mode)" "adb uninstall com.example.whiz.debug"
fi

echo "" | tee -a test_output.log
log_with_time "✅ All tests completed with latest changes!"
echo "" | tee -a test_output.log
echo "📊 Test reports available at:" | tee -a test_output.log
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html" | tee -a test_output.log
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html" | tee -a test_output.log
echo "" | tee -a test_output.log

if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    echo "🗑️  Debug app was uninstalled after testing" | tee -a test_output.log
else
    echo "📱 Debug app (🧪 WhizVoice DEBUG) is still installed for manual testing!" | tee -a test_output.log
    echo "💡 To uninstall later: adb uninstall com.example.whiz.debug" | tee -a test_output.log
fi

echo "" | tee -a test_output.log

# Show screenshot info if any were captured
if [[ -d "$SCREENSHOTS_DIR" ]] && [[ -n "$(ls -A "$SCREENSHOTS_DIR" 2>/dev/null)" ]]; then
    echo "📸 Test debugging files saved to: $SCREENSHOTS_DIR/" | tee -a test_output.log
    echo "   • Screenshots: $(ls "$SCREENSHOTS_DIR"/*.png 2>/dev/null | wc -l) files" | tee -a test_output.log
    echo "   • UI dumps: $(ls "$SCREENSHOTS_DIR"/*.xml 2>/dev/null | wc -l) files" | tee -a test_output.log
    echo "   • Log snippets: $(ls "$SCREENSHOTS_DIR"/*.txt 2>/dev/null | wc -l) files" | tee -a test_output.log
    echo "" | tee -a test_output.log
fi

echo "📋 Full test log saved to: test_output.log" | tee -a test_output.log

# Exit with the same code as the tests
if [[ -n "$TEST_EXIT_CODE" ]] && [[ "$TEST_EXIT_CODE" -ne 0 ]]; then
    exit $TEST_EXIT_CODE
fi 
