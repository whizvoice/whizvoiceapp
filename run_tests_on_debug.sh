#!/bin/bash

# Run tests on WhizVoice Debug version with SEQUENTIAL EXECUTION
# This builds, installs latest debug version, then runs tests sequentially for reliability
# Tests run one after another to avoid app state interference
# The debug app stays installed after testing for manual testing
#
# Usage:
#   ./run_tests_on_debug.sh              # Run tests sequentially (default)
#   ./run_tests_on_debug.sh --clean      # Run tests sequentially and uninstall app after
#   ./run_tests_on_debug.sh --skip-unit  # Skip unit tests, run only integration tests

set -e

# Trap handler to ensure debug app is always installed even on script failure
cleanup_and_ensure_debug_installed() {
    local exit_code=$?
    
    # Define log function in case it wasn't defined yet
    if ! declare -f log_with_time > /dev/null; then
        log_with_time() {
            echo "[$(date '+%H:%M:%S.%3N')] $1"
        }
    fi
    
    log_with_time "🔧 Script interrupted or failed (exit code: $exit_code)"
    
    # Pull any test screenshots that might have been captured before failure
    if declare -f pull_test_screenshots > /dev/null; then
        pull_test_screenshots 2>/dev/null || true
    else
        # Inline screenshot pulling if function not available yet
        log_with_time "📸 Pulling test screenshots and UI dumps from device (cleanup)..."
        mkdir -p test_screenshots 2>/dev/null || true
        local files=$(adb shell "ls /sdcard/Download/test_screenshots/ 2>/dev/null | grep -E '\.(png|xml)$'" | head -1)
        if [[ -n "$files" ]]; then
            if adb pull /sdcard/Download/test_screenshots/ temp_screenshots/ >/dev/null 2>&1; then
                find temp_screenshots \( -name "*.png" -o -name "*.xml" \) -exec mv {} test_screenshots/ \; 2>/dev/null || true
                rm -rf temp_screenshots 2>/dev/null || true
                local local_count=$(ls -1 test_screenshots/*.png test_screenshots/*.xml 2>/dev/null | wc -l | tr -d ' ')
                log_with_time "✅ Pulled $local_count screenshots and UI dumps to test_screenshots/ (cleanup)"
            fi
        fi
    fi
    
    # Always try to install debug app if we're not in clean mode (regardless of success/failure)
    if [[ "$CLEAN_AFTER_TESTS" != "true" ]]; then
        # Check if debug app is installed
        if ! adb shell pm list packages 2>/dev/null | grep -q "com.example.whiz.debug"; then
            log_with_time "📱 Debug app not found - running installation script..."
            
            # Call the dedicated install script
            if ./install.sh 2>/dev/null; then
                log_with_time "✅ Debug app installed successfully via install script"
            else
                log_with_time "❌ Install script failed - debug app may not be available"
            fi
        else
            log_with_time "✅ Debug app is already installed and ready for manual testing"
        fi
    fi
    
    # Preserve the original exit code
    exit $exit_code
}

# Set up trap to catch script exits
trap cleanup_and_ensure_debug_installed EXIT ERR

# Parse command line arguments
CLEAN_AFTER_TESTS=false
SKIP_UNIT_TESTS=false
SKIP_APP_INSTALL=false
VERBOSE_LOGGING=false
SINGLE_TEST=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --clean)
            CLEAN_AFTER_TESTS=true
            shift
            ;;
        --skip-unit)
            SKIP_UNIT_TESTS=true
            shift
            ;;
        --skip-app-install)
            SKIP_APP_INSTALL=true
            shift
            ;;
        --test)
            SINGLE_TEST="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE_LOGGING=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 [--clean] [--skip-unit] [--skip-app-install] [-v|--verbose] [--test <test_class_or_method>]"
            echo "Examples:"
            echo "  $0 --test com.example.whiz.integration.ChatViewModelIntegrationTest#botInterruption_allowsImmediateMessageSending"
            echo "  $0 --test com.example.whiz.integration.ChatViewModelIntegrationTest"
            echo "  $0 --skip-app-install --test com.example.whiz.integration.ChatViewModelIntegrationTest#botInterruption_allowsImmediateMessageSending"
            echo "  $0 -v --test <test>  # Run with verbose WebSocket logging (adds WhizServerRepo:V to logcat)"
            exit 1
            ;;
    esac
done

# Clear previous log files
> test_gradle_output.log
> test_logcat_output.log

# Clean screenshots and UI dumps from previous test runs (but preserve all artifacts within this run)
echo "[$(date '+%H:%M:%S.%3N')] 🧹 Cleaning screenshots and UI dumps from previous test runs..."
rm -rf test_screenshots/* 2>/dev/null || true
mkdir -p test_screenshots

# Function to log with timestamp
log_with_time() {
    echo "[$(date '+%H:%M:%S.%3N')] $1" | tee -a test_gradle_output.log
}

# Function to log with timestamp to console and test_summary.log only (not test_gradle_output.log)
log_summary_only() {
    local message="$1"
    local timestamp="[$(date '+%H:%M:%S.%3N')] $message"
    echo "$timestamp"
    echo "$timestamp" >> test_summary.log
}

# Function to run command with logging and timing
run_with_log() {
    local description="$1"
    local command="$2"
    local start_time=$(date +%s.%3N)
    
    log_with_time "⏳ $description"
    
    if eval "$command" >> test_gradle_output.log 2>&1; then
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ $description completed in ${duration}s"
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ $description failed in ${duration}s (exit code: $exit_code)"
        echo "❌ Check test_gradle_output.log for details" | tee -a test_gradle_output.log
        return $exit_code
    fi
    echo "" | tee -a test_gradle_output.log
}

# Function to run voice tests using gradle (more reliable than direct adb)
run_voice_tests_with_gradle() {
    local start_time=$(date +%s.%3N)
    log_with_time "🚀 Starting Voice Tests..."
    
            # Add section header to test_gradle_output.log
        echo "" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "📋 DETAILED LOGS FOR Voice Tests" >> test_gradle_output.log  
        echo "Test Class: com.example.whiz.voice.MicButtonDuringResponseTest" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
    
    if ./gradlew connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.voice.MicButtonDuringResponseTest \
        --console=plain \
        --no-daemon \
        >> test_gradle_output.log 2>&1; then
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Voice Tests completed successfully in ${duration}s"
        
        # Add end section to test_gradle_output.log
        echo "" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "📋 END OF DETAILED LOGS FOR Voice Tests" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse gradle test results for voice tests
        parse_gradle_test_results "Voice Tests" "test_gradle_output.log"
        return 0
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Voice Tests failed in ${duration}s (exit code: $exit_code)"
        
        # Add end section to test_gradle_output.log
        echo "" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "📋 END OF DETAILED LOGS FOR Voice Tests" >> test_gradle_output.log
        echo "=================================================================================" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse results even from failed runs
        parse_gradle_test_results "Voice Tests" "test_gradle_output.log"
        return $exit_code
    fi
}

# Function to parse gradle test results
parse_gradle_test_results() {
    local group_name="$1"
    local log_file="$2"
    
    local test_passed="0"
    local test_failed="0" 
    local test_skipped="0"
    
    if [[ "$group_name" == "Unit Tests" ]]; then
        # For unit tests, look for Gradle's test task summary
        # Gradle outputs test summaries like: "BUILD SUCCESSFUL" or "BUILD FAILED"
        # and may include lines like "1 test completed" or "3 tests completed, 1 failed"
        
        local build_result=$(grep -E "BUILD SUCCESSFUL|BUILD FAILED" "$log_file" | tail -1)
        
        # Look for gradle test summary patterns
        local test_summary=$(grep -E "[0-9]+ tests? completed" "$log_file" | tail -1)
        
        if [[ -n "$test_summary" ]]; then
            # Parse from gradle test summary: "X tests completed, Y failed"
            local total_tests=$(echo "$test_summary" | grep -o '[0-9]\+ tests\? completed' | grep -o '[0-9]\+')
            local failed_tests=$(echo "$test_summary" | grep -o '[0-9]\+ failed' | grep -o '[0-9]\+' || echo "0")
            local skipped_tests=$(echo "$test_summary" | grep -o '[0-9]\+ skipped' | grep -o '[0-9]\+' || echo "0")
            
            test_passed=$((${total_tests:-0} - ${failed_tests:-0} - ${skipped_tests:-0}))
            test_failed="${failed_tests:-0}"
            test_skipped="${skipped_tests:-0}"
        elif [[ "$build_result" == *"BUILD SUCCESSFUL"* ]]; then
            # No explicit test summary but successful build - count test files
            local total_unit_tests=$(find app/src/test -name "*.kt" -exec grep -c "@Test" {} \; 2>/dev/null | awk '{sum += $1} END {print sum+0}' | tr -d '\n')
            if [[ "$total_unit_tests" -gt 0 ]]; then
                test_passed="$total_unit_tests"
                test_failed="0"
                test_skipped="0"
            else
                test_passed="0"
                test_failed="0"
                test_skipped="0"
            fi
        else
            # Build failed
            test_passed="0"
            test_failed="unknown"
            test_skipped="0"
        fi
    else
        # For integration tests, parse from android test runner output
        # Look for "Starting X tests on device" and individual test results
        
        local total_tests=$(grep -o "Starting [0-9]\+ tests" "$log_file" | grep -o "[0-9]\+" | tail -1 | tr -d '\n\r ')
        
        # Count test results from android test runner - be specific to avoid gradle task results
        local failed_count=$(grep -c "FAILED.*\[" "$log_file" 2>/dev/null | tr -d '\n' || echo "0")
        local skipped_count=$(grep -c "SKIPPED.*\[" "$log_file" 2>/dev/null | tr -d '\n' || echo "0")
        
        # Also check for gradle's final test summary for android tests
        local gradle_summary=$(grep -E "Tests run: [0-9]+.*Failures: [0-9]+.*Errors: [0-9]+" "$log_file" | tail -1)
        if [[ -n "$gradle_summary" ]]; then
            # Parse gradle test summary format: "Tests run: X, Failures: Y, Errors: Z, Skipped: W"
            local gradle_total=$(echo "$gradle_summary" | grep -o 'Tests run: [0-9]\+' | grep -o '[0-9]\+')
            local gradle_failures=$(echo "$gradle_summary" | grep -o 'Failures: [0-9]\+' | grep -o '[0-9]\+')
            local gradle_errors=$(echo "$gradle_summary" | grep -o 'Errors: [0-9]\+' | grep -o '[0-9]\+')
            local gradle_skipped=$(echo "$gradle_summary" | grep -o 'Skipped: [0-9]\+' | grep -o '[0-9]\+')
            
            total_tests="${gradle_total:-$total_tests}"
            failed_count="$((${gradle_failures:-0} + ${gradle_errors:-0}))"
            skipped_count="${gradle_skipped:-$skipped_count}"
        fi
        
        if [[ -n "$total_tests" && "$total_tests" -gt 0 ]]; then
            local total_num="${total_tests:-0}"
            local failed_num="${failed_count:-0}"
            local skipped_num="${skipped_count:-0}"

            test_passed=$((total_num - failed_num - skipped_num))
            test_failed="$failed_num"
            test_skipped="$skipped_num"
            
            # Ensure non-negative passed count
            if [[ "$test_passed" -lt 0 ]]; then
                test_passed="0"
            fi
        else
            # Fallback to build result
            local build_result=$(grep -E "BUILD SUCCESSFUL|BUILD FAILED" "$log_file" | tail -1)
            if [[ "$build_result" == *"BUILD SUCCESSFUL"* ]]; then
                test_passed="unknown"
                test_failed="0"
                test_skipped="0"
            else
                test_passed="0"
                test_failed="unknown"
                test_skipped="0"
            fi
        fi
    fi
    
    # Log to console and test_summary.log only (not test_gradle_output.log)
    local result_line="📊 $group_name Results: $test_passed passed, $test_failed failed, $test_skipped skipped"
    log_summary_only "$result_line"
    
    # Store results in appropriate global variables
    if [[ "$group_name" == "Unit Tests" ]]; then
        UNIT_TESTS_PASSED=$test_passed
        UNIT_TESTS_FAILED=$test_failed
        UNIT_TESTS_SKIPPED=$test_skipped
    else
        INTEGRATION_TESTS_PASSED=$test_passed
        INTEGRATION_TESTS_FAILED=$test_failed
        INTEGRATION_TESTS_SKIPPED=$test_skipped
    fi
}

# Function to run unit tests with clean log separation
run_unit_tests() {
    local start_time=$(date +%s.%3N)
    log_with_time "🧪 Running unit tests..."
    
    # Add human-readable info to test_summary.log
    echo "" >> test_summary.log
    echo "=================================================================================" >> test_summary.log
    echo "📋 UNIT TEST EXECUTION" >> test_summary.log  
    echo "Command: ./gradlew testDebugUnitTest" >> test_summary.log
    echo "Started: $(date +'%H:%M:%S.%3N')" >> test_summary.log
    echo "=================================================================================" >> test_summary.log
    
    # Add gradle section marker to test_gradle_output.log
    echo "" >> test_gradle_output.log
    echo "# ========== UNIT TESTS: ./gradlew testDebugUnitTest ==========" >> test_gradle_output.log
    
    # Run gradle command and capture ONLY its output to test_gradle_output.log
    if ./gradlew testDebugUnitTest --console=plain --no-daemon >> test_gradle_output.log 2>&1; then
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Unit tests completed in ${duration}s"
        
        # Add success info to test_summary.log
        echo "✅ Unit tests completed in ${duration}s" >> test_summary.log
        echo "Finished: $(date +'%H:%M:%S.%3N')" >> test_summary.log
        
        # Add end marker to test_gradle_output.log
        echo "# ========== END UNIT TESTS ==========" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse results and add to test_summary.log
        parse_gradle_test_results "Unit Tests" "test_gradle_output.log"
        
        echo "" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "📋 END UNIT TEST EXECUTION" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "" >> test_summary.log
        
        return 0
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Unit tests failed in ${duration}s"
        
        # Add failure info to test_summary.log
        echo "❌ Unit tests failed in ${duration}s" >> test_summary.log
        echo "Failed: $(date +'%H:%M:%S.%3N')" >> test_summary.log
        
        # Add end marker to test_gradle_output.log
        echo "# ========== END UNIT TESTS (FAILED) ==========" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse results and add to test_summary.log
        parse_gradle_test_results "Unit Tests" "test_gradle_output.log"
        
        echo "" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "📋 END UNIT TEST EXECUTION" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "" >> test_summary.log
        
        return $exit_code
    fi
}

# Function to read test credentials
read_test_credentials() {
    local credentials_file="test_credentials.json"
    if [[ ! -f "$credentials_file" ]]; then
        log_with_time "❌ ERROR: $credentials_file not found."
        log_with_time ""
        log_with_time "   Please create $credentials_file with the following format:"
        log_with_time ""
        log_with_time '   {'
        log_with_time '     "google_test_account": {'
        log_with_time '       "email": "your-test-email@gmail.com",'
        log_with_time '       "password": "your-test-password",'
        log_with_time '       "display_name": "Test User",'
        log_with_time '       "user_id": "your-google-user-id"'
        log_with_time '     },'
        log_with_time '     "test_environment": {'
        log_with_time '       "use_real_auth": true,'
        log_with_time '       "api_base_url": "https://whizvoice.com/api"'
        log_with_time '     }'
        log_with_time '   }'
        log_with_time ""
        exit 1
    fi

    # Try nested format first (google_test_account.email), then flat format
    TEST_USERNAME=$(grep -o '"email": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)
    TEST_PASSWORD=$(grep -o '"password": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)

    if [[ -z "$TEST_USERNAME" ]]; then
        log_with_time "❌ ERROR: Could not find 'email' field in $credentials_file"
        log_with_time "   File contents:"
        cat "$credentials_file" | head -20 | while read line; do log_with_time "   $line"; done
        exit 1
    fi

    if [[ -z "$TEST_PASSWORD" || "$TEST_PASSWORD" == "REPLACE_WITH_ACTUAL_PASSWORD" ]]; then
        log_with_time "❌ ERROR: 'password' field is missing or has placeholder value in $credentials_file"
        exit 1
    fi

    log_with_time "🔑 Successfully read test credentials for user: $TEST_USERNAME"
}

# Function to check device network connectivity before tests
check_device_network_connectivity() {
    log_with_time "🌐 Checking device network connectivity..."

    # Try to resolve whizvoice.com from the device
    local dns_check=$(adb shell "ping -c 1 -W 5 whizvoice.com 2>&1" | head -5)

    if echo "$dns_check" | grep -q "unknown host\|No address associated\|bad address\|Network is unreachable"; then
        log_with_time "❌ NETWORK ERROR: Device cannot reach whizvoice.com"
        log_with_time "   DNS resolution failed - check WiFi/mobile data connection"
        log_with_time "   Error: $dns_check"
        log_with_time ""
        log_with_time "   Integration tests require network access to authenticate."
        log_with_time "   Please ensure the device has internet connectivity and retry."
        return 1
    elif echo "$dns_check" | grep -q "1 packets transmitted"; then
        log_with_time "✅ Network connectivity verified (whizvoice.com reachable)"
        return 0
    else
        log_with_time "⚠️  Network check inconclusive, proceeding anyway..."
        log_with_time "   Result: $dns_check"
        return 0
    fi
}

# Function to pull test screenshots and UI dumps from device to local folder
pull_test_screenshots() {
    log_with_time "📸 Pulling test screenshots and UI dumps from device..."
    
    # Check if device is still connected first
    if ! adb devices | grep -q "device$"; then
        log_with_time "⚠️  Device not connected - trying to reconnect..."
        adb reconnect >/dev/null 2>&1 || true
        sleep 2
        if ! adb devices | grep -q "device$"; then
            log_with_time "❌ Device offline - cannot pull screenshots"
            return 1
        fi
    fi
    
    # Ensure test_screenshots directory exists (already cleaned before tests)
    mkdir -p test_screenshots
    
    local total_pulled=0
    
    # Check primary location: /sdcard/Download/test_screenshots/ (dedicated test screenshot directory)
    local primary_files=$(adb shell "ls /sdcard/Download/test_screenshots/ 2>/dev/null | grep -E '\.(png|xml)$'" | head -1)
    
    if [[ -n "$primary_files" ]]; then
        log_with_time "📱 Found test screenshots in primary location: /sdcard/Download/test_screenshots/"
        if adb pull /sdcard/Download/test_screenshots/ temp_screenshots/ >/dev/null 2>&1; then
            # Move screenshots and UI dumps from temp folder to final location
            find temp_screenshots \( -name "*.png" -o -name "*.xml" \) -exec mv {} test_screenshots/ \; 2>/dev/null || true
            rm -rf temp_screenshots
            local primary_count=$(ls -1 test_screenshots/*.png test_screenshots/*.xml 2>/dev/null | wc -l | tr -d ' ')
            total_pulled=$((total_pulled + primary_count))
            log_with_time "✅ Pulled $primary_count screenshots from primary location"
        else
            log_with_time "⚠️  Failed to pull screenshots from primary location"
        fi
    fi
    
    # Note: All screenshots and UI dumps are now saved to /sdcard/Download/test_screenshots/
    # The CI location /data/local/tmp/screenshots/ is no longer used
    
    # Final count and listing
    local final_count=$(ls -1 test_screenshots/*.png test_screenshots/*.xml 2>/dev/null | wc -l | tr -d ' ')
    
    if [[ "$final_count" -gt 0 ]]; then
        log_with_time "✅ Successfully pulled $final_count total screenshots and UI dumps to test_screenshots/"
        
        # List the screenshots and UI dumps that were pulled
        log_with_time "📋 Screenshots and UI dumps captured:"
        for file in test_screenshots/*.png test_screenshots/*.xml; do
            if [[ -f "$file" ]]; then
                local filename=$(basename "$file")
                log_with_time "   • $filename"
            fi
        done
    else
        log_with_time "📸 No screenshots found on device (no test failures with screenshots)"
    fi
}

# Function to run integration tests with clean log separation
run_integration_tests_with_logcat() {
    local start_time=$(date +%s.%3N)
    log_with_time "🚀 Starting Integration Tests with Logcat..."
    
    # Add human-readable info to test_summary.log
    echo "" >> test_summary.log
    echo "=================================================================================" >> test_summary.log
    echo "📋 INTEGRATION TEST EXECUTION" >> test_summary.log  
    if [[ -n "$SINGLE_TEST" ]]; then
        echo "Command: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=$SINGLE_TEST" >> test_summary.log
    else
        echo "Command: ./gradlew connectedDebugAndroidTest" >> test_summary.log
    fi
    echo "Started: $(date +'%H:%M:%S.%3N')" >> test_summary.log
    echo "=================================================================================" >> test_summary.log
    
    # Add gradle section marker to test_gradle_output.log
    echo "" >> test_gradle_output.log
    echo "# ========== INTEGRATION TESTS: ./gradlew connectedDebugAndroidTest ==========" >> test_gradle_output.log
    
    # Clear logcat and start capture for test logs in background
    adb logcat -c
    
    # Start comprehensive logcat capture to dedicated file
    echo "📱 COMPLETE LOGCAT OUTPUT FROM INTEGRATION TESTS" > test_logcat_output.log
    echo "Started: $(date +'%H:%M:%S.%3N')" >> test_logcat_output.log
    echo "=================================================================================" >> test_logcat_output.log
    local discovered_tags=$(discover_test_log_tags)
    echo "🔍 Discovered test log tags: $(echo $discovered_tags | tr '\n' ' ')" >> test_summary.log
    echo "📱 Starting logcat with tags: $discovered_tags" >> test_summary.log
    
    # Start logcat capture with filter for app-specific logs
    # Filter: Show all logs from com.example.whiz, TestRunner, and errors/warnings from all sources
    # When VERBOSE_LOGGING is enabled, also capture WhizServerRepo Info logs for WebSocket debugging
    {
        if [[ "$VERBOSE_LOGGING" == "true" ]]; then
            echo "📱 Verbose logging enabled - adding WhizServerRepo:V to logcat filter" >> test_summary.log
            adb logcat -v time '*:E' '*:W' 'TestRunner:V' 'com.example.whiz*:V' 'WhizServerRepo:V' 'ToolExecutor:V' 'AndroidRuntime:V' >> test_logcat_output.log 2>&1 &
        else
            adb logcat -v time '*:E' '*:W' 'TestRunner:V' 'com.example.whiz*:V' 'ToolExecutor:V' 'AndroidRuntime:V' >> test_logcat_output.log 2>&1 &
        fi
        local logcat_pid=$!
    }
    echo "📱 Logcat started with PID: $logcat_pid (filtered for app logs + errors/warnings)" >> test_summary.log
    echo "🔍 Capturing app-specific logs, test runner output, and system errors/warnings" >> test_summary.log
    
    # Run gradle command and capture ONLY its output to test_gradle_output.log
    local gradle_command="./gradlew connectedDebugAndroidTest --console=plain --no-daemon"
    if [[ -n "$SINGLE_TEST" ]]; then
        # Fix the test class path if it's missing the integration package
        # This handles the case where WebSocketReconnectionTest is specified without the full package path
        if [[ "$SINGLE_TEST" == "com.example.whiz.WebSocketReconnectionTest" ]]; then
            SINGLE_TEST="com.example.whiz.integration.WebSocketReconnectionTest"
            echo "🔧 Fixed test path: using $SINGLE_TEST" >> test_summary.log
        fi
        gradle_command="$gradle_command -Pandroid.testInstrumentationRunnerArguments.class=$SINGLE_TEST"
        echo "🎯 Running single test: $SINGLE_TEST" >> test_summary.log
    fi
    
    if $gradle_command >> test_gradle_output.log 2>&1; then
        # Stop logcat capture
        kill $logcat_pid 2>/dev/null || true
        sleep 1
        
        # Add completion marker to logcat file
        echo "" >> test_logcat_output.log
        echo "=================================================================================" >> test_logcat_output.log
        echo "Completed: $(date +'%H:%M:%S.%3N') - SUCCESS" >> test_logcat_output.log
        echo "=================================================================================" >> test_logcat_output.log
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_summary_only "✅ Integration Tests completed successfully in ${duration}s"
        
        # Add success info and logcat to test_summary.log
        echo "✅ Integration Tests completed successfully in ${duration}s" >> test_summary.log
        echo "Finished: $(date +'%H:%M:%S.%3N')" >> test_summary.log
        echo "" >> test_summary.log
        echo "📱 ANDROID LOGCAT OUTPUT (TEST TAGS):" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        if [[ -f "$temp_logcat_file" && -s "$temp_logcat_file" ]]; then
            echo "📊 Logcat file size: $(wc -l < "$temp_logcat_file") lines" >> test_summary.log
            echo "" >> test_summary.log
            cat "$temp_logcat_file" >> test_summary.log
        else
            echo "No logcat output captured from test tags" >> test_summary.log
            echo "📊 Logcat file status: $(ls -la "$temp_logcat_file" 2>/dev/null || echo "file not found")" >> test_summary.log
        fi
        echo "=================================================================================" >> test_summary.log
        
        # Add end marker to test_gradle_output.log
        echo "# ========== END INTEGRATION TESTS ==========" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse results and add to test_summary.log
        parse_gradle_test_results "Integration Tests" "test_gradle_output.log"
        
        echo "" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "📋 END INTEGRATION TEST EXECUTION" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "" >> test_summary.log
        
        # Logcat file cleanup not needed - using persistent test_logcat_output.log
        return 0
    else
        local exit_code=$?
        
        # Stop logcat capture
        kill $logcat_pid 2>/dev/null || true
        sleep 1
        
        # Add completion marker to logcat file
        echo "" >> test_logcat_output.log
        echo "=================================================================================" >> test_logcat_output.log
        echo "Completed: $(date +'%H:%M:%S.%3N') - FAILED (exit code: $exit_code)" >> test_logcat_output.log
        echo "=================================================================================" >> test_logcat_output.log
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_summary_only "❌ Integration Tests failed in ${duration}s (exit code: $exit_code)"
        
        # Pull failure screenshots immediately while device is still connected
        log_summary_only "📸 Pulling failure screenshots immediately (device may go offline in CI)..."
        pull_test_screenshots
        
        # Add failure info and enhanced failure details to test_summary.log
        echo "❌ Integration Tests failed in ${duration}s (exit code: $exit_code)" >> test_summary.log
        echo "Failed: $(date +'%H:%M:%S.%3N')" >> test_summary.log
        echo "" >> test_summary.log
        
        # ENHANCED FAILURE INFORMATION - Extract specific test failures from gradle output
        echo "🔥 SPECIFIC TEST FAILURE DETAILS:" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        
        # Extract failed test methods and their assertions from gradle output
        local failed_tests=$(grep -A 3 -B 1 "FAILED.*(" test_gradle_output.log | grep -E "FAILED|AssertionError|RuntimeException|Error:|failed:" || echo "")
        if [[ -n "$failed_tests" ]]; then
            echo "🚨 Failed Test Details (from Gradle output):" >> test_summary.log
            echo "$failed_tests" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        # Extract specific assertion error messages (like from failWithScreenshot calls)
        local assertion_errors=$(grep -A 2 -B 1 "java.lang.AssertionError:" test_gradle_output.log || echo "")
        if [[ -n "$assertion_errors" ]]; then
            echo "⚠️ Assertion Error Messages:" >> test_summary.log
            echo "$assertion_errors" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        # Extract the specific error message text from AssertionError lines
        local error_messages=$(grep "java.lang.AssertionError:" test_gradle_output.log | sed 's/.*java.lang.AssertionError: //' || echo "")
        if [[ -n "$error_messages" ]]; then
            echo "💥 Specific Error Messages:" >> test_summary.log
            while IFS= read -r line; do
                echo "   • $line" >> test_summary.log
            done <<< "$error_messages"
            echo "" >> test_summary.log
        fi
        
        # Extract specific failing test names with better pattern matching
        echo "🎯 FAILING TESTS IDENTIFIED:" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        
        # Look for Android test failure patterns
        local android_failures=$(grep -E "\..*\s+>\s+.*\[.*\]\s+FAILED" test_gradle_output.log || echo "")
        if [[ -n "$android_failures" ]]; then
            echo "📱 Android Test Failures:" >> test_summary.log
            echo "$android_failures" >> test_summary.log
            echo "" >> test_summary.log
            
            # Extract just the test class and method names for easier correlation
            echo "🔍 Specific Failed Test Methods:" >> test_summary.log
            echo "$android_failures" | sed 's/\[.*\] FAILED//' | sed 's/^[[:space:]]*//' >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        # Look for process crash information
        local crash_info=$(grep -A 2 -B 2 "Process crashed\|Instrumentation run failed" test_gradle_output.log || echo "")
        if [[ -n "$crash_info" ]]; then
            echo "💥 Process Crash Information:" >> test_summary.log
            echo "$crash_info" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        # Extract test completion status
        local test_status=$(grep -E "Tests.*completed.*failed|Tests.*failed:" test_gradle_output.log || echo "")
        if [[ -n "$test_status" ]]; then
            echo "📊 Test Execution Status:" >> test_summary.log
            echo "$test_status" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        echo "=================================================================================" >> test_summary.log
        
        # Extract test class loading errors
        local class_errors=$(grep -A 2 -B 1 "ClassNotFoundException\|Failed loading.*test class" test_gradle_output.log || echo "")
        if [[ -n "$class_errors" ]]; then
            echo "📚 Test Class Loading Errors:" >> test_summary.log
            echo "$class_errors" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        # Extract task execution failures
        local task_errors=$(grep -A 5 "Execution failed for task" test_gradle_output.log || echo "")
        if [[ -n "$task_errors" ]]; then
            echo "⚙️ Task Execution Failures:" >> test_summary.log
            echo "$task_errors" >> test_summary.log
            echo "" >> test_summary.log
        fi
        
        echo "=================================================================================" >> test_summary.log
        echo "" >> test_summary.log
        
        # First, list all failing tests by extracting from gradle output
        echo "❌ FAILING TESTS:" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        
        # Extract failing test method names from gradle output
        local failing_test_methods=$(grep -E "FAILED" test_gradle_output.log | \
            sed -n 's/.*> \([^[]*\)\[.*FAILED.*/\1/p' | \
            sort -u)
        
        if [[ -n "$failing_test_methods" ]]; then
            echo "$failing_test_methods" | while read -r test_method; do
                if [[ -n "$test_method" ]]; then
                    echo "• $test_method" >> test_summary.log
                fi
            done
        else
            # Fallback: try to extract from TestRunner failed logs
            local fallback_tests=$(grep "TestRunner.*failed:" test_logcat_output.log 2>/dev/null | \
                sed -n 's/.*failed: \([^(]*\).*/\1/p' | \
                sort -u | head -10)
            if [[ -n "$fallback_tests" ]]; then
                echo "$fallback_tests" | while read -r test_name; do
                    echo "• $test_name" >> test_summary.log
                done
            else
                echo "• No specific failing tests identified" >> test_summary.log
            fi
        fi
        echo "" >> test_summary.log
        
        # Add relevant logcat excerpts for ONLY failed tests using original TAG approach
        echo "📱 FAILED TEST LOGS:" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "💡 Complete logcat available in: test_logcat_output.log" >> test_summary.log
        echo "" >> test_summary.log
        
        if [[ -f "test_logcat_output.log" && -s "test_logcat_output.log" ]]; then
            # Get all discovered test tags like before (including companion object constants)
            local discovered_tags=$(find app/src/androidTest -name "*.kt" -exec grep -ho '\(val\|const val\) TAG = "[^"]*"' {} \; 2>/dev/null | \
                sed 's/.*TAG = "\([^"]*\)".*/\1/' | \
                sort -u)
            
            # Get failing test classes from gradle output to filter tags
            local failing_test_classes=$(grep -E "FAILED" test_gradle_output.log | \
                sed -n 's/.*\.\([^.]*Test[^[]*\).*/\1/p' | \
                sort -u)
            
            if [[ -n "$discovered_tags" && -n "$failing_test_classes" ]]; then
                for tag in $discovered_tags; do
                    if [[ -n "$tag" && "$tag" != "TAG" ]]; then
                                                 # Check if this tag corresponds to a failing test class
                         local tag_matches_failure=false
                         for failing_class in $failing_test_classes; do
                             # Direct exact match first (e.g., SettingsIntegrationTest == SettingsIntegrationTest)
                             if [[ "$tag" == "$failing_class" ]]; then
                                 tag_matches_failure=true
                                 break
                             fi
                             
                             # More flexible matching: check if tag is contained in class name or vice versa
                             # Remove common suffixes for better matching
                             local tag_base=$(echo "$tag" | sed 's/Test$//')
                             local class_base=$(echo "$failing_class" | sed 's/IntegrationTest$//' | sed 's/AndLifecycleTest$//' | sed 's/Test$//')
                             
                             if [[ "$failing_class" == *"$tag"* ]] || [[ "$tag" == *"$class_base"* ]] || [[ "$tag_base" == *"$class_base"* ]] || [[ "$class_base" == *"$tag_base"* ]]; then
                                 tag_matches_failure=true
                                 break
                             fi
                         done
                        
                        # Only show logs for tags that match failing tests
                        if [[ "$tag_matches_failure" == true ]]; then
                            echo "📱 $tag execution logs:" >> test_summary.log
                            
                            # Look for specific assertion errors for this test
                            local assertion_errors=$(grep -A 5 -B 2 "AssertionError.*$tag\|$tag.*AssertionError" test_logcat_output.log | head -20 || echo "")
                            if [[ -n "$assertion_errors" ]]; then
                                echo "📱 $tag AssertionError details:" >> test_summary.log
                                echo "$assertion_errors" >> test_summary.log
                                echo "" >> test_summary.log
                            fi
                            
                            # Look for both direct tag logs and TestRunner logs mentioning the test class
                            local test_logs=$(grep "$tag" test_logcat_output.log | head -30 || echo "")
                            local test_runner_logs=$(grep "TestRunner.*$tag" test_logcat_output.log | head -20 || echo "")
                            # Also look for the full class name in case TAG is shortened
                            local tag_base=$(echo "$tag" | sed 's/Test$//')  # Remove "Test" suffix if present
                            local class_name_logs=$(grep "${tag_base}.*Test" test_logcat_output.log | head -20 || echo "")
                            
                            if [[ -n "$test_logs" ]]; then
                                echo "📱 $tag general logs:" >> test_summary.log
                                echo "$test_logs" >> test_summary.log
                            elif [[ -n "$test_runner_logs" ]]; then
                                echo "📱 $tag TestRunner logs:" >> test_summary.log
                                echo "$test_runner_logs" >> test_summary.log
                            elif [[ -n "$class_name_logs" ]]; then
                                echo "📱 $tag class name logs:" >> test_summary.log
                                echo "$class_name_logs" >> test_summary.log
                            else
                                echo "No $tag logs found" >> test_summary.log
                            fi
                            echo "" >> test_summary.log
                        fi
                    fi
                done
            else
                echo "No failing test tags discovered - showing comprehensive test failure logs" >> test_summary.log
                
                # Extract specific assertion errors with more context
                echo "📱 FAILED TEST LOGS:" >> test_summary.log
                echo "=================================================================================" >> test_summary.log
                
                # Look for specific assertion errors with full context
                local assertion_errors=$(grep -A 10 -B 2 "AssertionError" test_logcat_output.log | head -30 || echo "")
                if [[ -n "$assertion_errors" ]]; then
                    echo "💡 Complete logcat available in: test_logcat_output.log" >> test_summary.log
                    echo "" >> test_summary.log
                    echo "📱 Specific AssertionError details:" >> test_summary.log
                    echo "$assertion_errors" >> test_summary.log
                    echo "" >> test_summary.log
                fi
                
                # Also show general test failure logs
                local test_failure_logs=$(grep -E "TestRunner.*failed|RuntimeException" test_logcat_output.log | head -20 || echo "")
                if [[ -n "$test_failure_logs" ]]; then
                    echo "📱 General test failure logs:" >> test_summary.log
                    echo "$test_failure_logs" >> test_summary.log
                else
                    echo "No additional test failure logs found" >> test_summary.log
                fi
            fi
        else
            echo "❌ No complete logcat file found: test_logcat_output.log" >> test_summary.log
        fi
        echo "=================================================================================" >> test_summary.log
        
        # Add end marker to test_gradle_output.log
        echo "# ========== END INTEGRATION TESTS (FAILED) ==========" >> test_gradle_output.log
        echo "" >> test_gradle_output.log
        
        # Parse results and add to test_summary.log
        parse_gradle_test_results "Integration Tests" "test_gradle_output.log"
        
        echo "" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "📋 END INTEGRATION TEST EXECUTION" >> test_summary.log
        echo "=================================================================================" >> test_summary.log
        echo "" >> test_summary.log
        
        # Logcat file cleanup not needed - using persistent test_logcat_output.log
        return $exit_code
    fi
}

# Function to automatically discover log tags from test files
discover_test_log_tags() {
    local tags=""
    
    # Find all androidTest .kt files and extract log tags
    if find app/src/androidTest -name "*.kt" >/dev/null 2>&1; then
        # Extract TAG variable definitions (e.g., private val TAG = "MessageDisplayTest" or companion object { private const val TAG = "..." })
        local discovered_tags=$(find app/src/androidTest -name "*.kt" -exec grep -ho '\(val\|const val\) TAG = "[^"]*"' {} \; 2>/dev/null | \
            sed 's/.*TAG = "\([^"]*\)".*/\1/' | \
            sort -u)
        
        # Build logcat tag filter string with appropriate log levels
        if [[ -n "$discovered_tags" ]]; then
            for tag in $discovered_tags; do
                if [[ -n "$tag" && "$tag" != "TAG" ]]; then  # Skip empty and generic TAG
                    # Use different log levels based on tag patterns for better failure capture
                    if [[ "$tag" =~ Test|Error|Exception|Fail ]]; then
                        tags="$tags ${tag}:V"  # Verbose for test-related tags
                    else
                        tags="$tags ${tag}:D"  # Debug for other tags
                    fi
                fi
            done
        fi
        
        # Add comprehensive Android test and error tags for failure detection
        tags="$tags AndroidRuntime:E System.err:E TestRunner:* AssertionError:E RuntimeException:E ClassNotFoundException:E"
        
        # Add app-specific tags that might contain useful debugging info
        tags="$tags ChatViewModel:D AuthViewModel:D VoiceManager:D BaseIntegrationTest:V MainActivity:D"
        
        # Add instrumentation test tags to track test execution flow
        tags="$tags Instrumentation:I InstrumentationTestRunner:I MonitoringInstrumentation:I"
        
        # Remove leading space
        tags=$(echo "$tags" | sed 's/^ *//')
        
        if [[ -n "$discovered_tags" ]]; then
            echo "🔍 Discovered test log tags: $(echo $discovered_tags | tr '\n' ' ')" >> test_summary.log
        else
            echo "⚠️ No test log tags discovered - using basic tags only" >> test_summary.log
        fi
    else
        # Fallback to comprehensive tags if discovery fails
        tags="AndroidRuntime:E System.err:E TestRunner:* AssertionError:E RuntimeException:E ClassNotFoundException:E ChatViewModel:D AuthViewModel:D VoiceManager:D BaseIntegrationTest:V MainActivity:D"
        echo "⚠️ Could not discover test tags - using comprehensive fallback tags" >> test_summary.log
    fi
    
    echo "$tags"
}

# Initialize result variables
UNIT_TESTS_PASSED="0"
UNIT_TESTS_FAILED="0"
INTEGRATION_TESTS_PASSED="0"
INTEGRATION_TESTS_FAILED="0"
INTEGRATION_TESTS_SKIPPED="0"

log_with_time "🧪 Running tests SEQUENTIALLY for maximum reliability..."

# Read test credentials
read_test_credentials

# Wake device before any adb operations to prevent hanging
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
sleep 0.5

# Push test credentials to device for tests to load
log_with_time "📲 Pushing test credentials to device..."
adb shell mkdir -p /data/local/tmp >/dev/null 2>&1 || true
adb push test_credentials.json /data/local/tmp/test_credentials.json >/dev/null 2>&1
log_with_time "✅ Test credentials pushed to device"

if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    log_with_time "🗑️ Will uninstall debug app after tests complete"
else
    log_with_time "📱 Debug app will stay installed for manual testing after tests"
fi

# Initialize log files with clear purposes
echo "# GRADLE BUILD AND TEST EXECUTION OUTPUT" > test_gradle_output.log
echo "# HUMAN-READABLE SUMMARIES WITH RELEVANT EXCERPTS" > test_summary.log
# test_logcat_output.log will be initialized when tests start

# Build and install (no gradle output redirection here - these are not tests)
if [[ "$SKIP_APP_INSTALL" == "true" ]]; then
    log_with_time "⏭️ Skipping app rebuild and installation (using existing app)"
else
    # Use the smart install script which builds and installs the latest code
    log_with_time "🔧 Building and installing latest debug version..."
    if ./install.sh >> test_gradle_output.log 2>&1; then
        log_with_time "✅ App built and installed successfully"
    else
        log_with_time "❌ App installation failed"
        exit 1
    fi
fi

# Build and install test APK
run_with_log "Building test APK" "./gradlew assembleDebugAndroidTest --console=plain --quiet"
run_with_log "Installing test APK" "adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# Grant permissions and prepare device
log_with_time "🔐 Granting necessary permissions for testing..."
adb shell am start -n com.example.whiz.debug/com.example.whiz.MainActivity >/dev/null 2>&1 || true
# Grant overlay permission (Display over other apps)
adb shell appops set com.example.whiz.debug SYSTEM_ALERT_WINDOW allow 2>/dev/null || true

# Verify accessibility is enabled
enabled_check=$(adb shell settings get secure accessibility_enabled 2>/dev/null | tr -d '\r\n')
services_check=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
if [[ "$enabled_check" == "1" ]] && [[ "$services_check" == *"WhizAccessibilityService"* ]]; then
    log_with_time "✅ Granted: microphone, overlay, and accessibility permissions (verified)"
else
    log_with_time "⚠️  Granted permissions but accessibility may not be fully enabled (enabled=$enabled_check, services=$services_check)"
fi

# Wake device and ensure screen is on before tests
log_with_time "📱 Waking device and ensuring screen is on..."
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true  # Wake the device
adb shell input keyevent KEYCODE_MENU >/dev/null 2>&1 || true    # Dismiss lock screen if no security
sleep 1

# Prevent screen from sleeping during tests
log_with_time "📱 Disabling screen timeout and keeping screen on during tests..."
adb shell settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || true  # Max timeout (~24 days)
adb shell svc power stayon true >/dev/null 2>&1 || true  # Keep screen on while charging/USB connected

# Run tests sequentially for maximum reliability
if [[ "$SKIP_UNIT_TESTS" == "true" ]]; then
    log_with_time "📱 Running integration tests only (skipping unit tests)..."
    unit_exit_code=0
    UNIT_TESTS_PASSED="skipped"
    UNIT_TESTS_FAILED="0"
else
    adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true

    log_with_time "📱 Running tests sequentially for maximum reliability..."
    
    # Run unit tests first (don't exit on failure)
    set +e  # Temporarily disable exit on error
    run_unit_tests
    unit_exit_code=$?
    set -e  # Re-enable exit on error
fi

# Clean app state and device screenshots/UI dumps before integration tests
log_with_time "🧹 Cleaning app state, device screenshots, and UI dumps before integration tests..."
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true

# Clean old screenshots and UI dumps from device (local artifacts already cleaned at script start)
# BaseIntegrationTest saves screenshots and UI dumps to /sdcard/Download/test_screenshots/
adb shell 'rm -rf /sdcard/Download/test_screenshots/*' 2>/dev/null || true
adb shell 'mkdir -p /sdcard/Download/test_screenshots' 2>/dev/null || true

sleep 2
log_with_time "✅ App state and device screenshots cleaned"

# Check network connectivity before integration tests
if ! check_device_network_connectivity; then
    log_with_time "❌ Aborting tests due to network connectivity issues"
    exit 1
fi

# Run integration tests with logcat capture (don't exit on failure)
set +e  # Temporarily disable exit on error
run_integration_tests_with_logcat
integration_exit_code=$?
set -e  # Re-enable exit on error

# Pull screenshots from device to local folder after all tests complete (fallback for success cases)
if ! pull_test_screenshots; then
    log_summary_only "⚠️  Final screenshot pull failed (device may be offline in CI) - failure screenshots should have been pulled immediately"
fi

overall_exit_code=$((unit_exit_code + integration_exit_code))

# Final summary - use log_summary_only to keep this out of test_gradle_output.log
log_summary_only "🏁 TEST EXECUTION COMPLETED"
log_summary_only "=================================="

log_summary_only "📊 SEQUENTIAL EXECUTION SUMMARY:"

log_summary_only "   Unit Tests: $UNIT_TESTS_PASSED passed, $UNIT_TESTS_FAILED failed"
log_summary_only "   Integration Tests: $INTEGRATION_TESTS_PASSED passed, $INTEGRATION_TESTS_FAILED failed, $INTEGRATION_TESTS_SKIPPED skipped"

# Handle non-numeric values in totals (like "unknown" or "skipped")
unit_passed_num=0
unit_failed_num=0
integration_passed_num=0
integration_failed_num=0
integration_skipped_num=0

# Convert to numbers, handling special cases
if [[ "$UNIT_TESTS_PASSED" =~ ^[0-9]+$ ]]; then
    unit_passed_num=$UNIT_TESTS_PASSED
fi
if [[ "$UNIT_TESTS_FAILED" =~ ^[0-9]+$ ]]; then
    unit_failed_num=$UNIT_TESTS_FAILED
fi
if [[ "$INTEGRATION_TESTS_PASSED" =~ ^[0-9]+$ ]]; then
    integration_passed_num=$INTEGRATION_TESTS_PASSED
fi
if [[ "$INTEGRATION_TESTS_FAILED" =~ ^[0-9]+$ ]]; then
    integration_failed_num=$INTEGRATION_TESTS_FAILED
fi
if [[ "$INTEGRATION_TESTS_SKIPPED" =~ ^[0-9]+$ ]]; then
    integration_skipped_num=$INTEGRATION_TESTS_SKIPPED
fi

total_passed=$((unit_passed_num + integration_passed_num))
total_failed=$((unit_failed_num + integration_failed_num))
total_skipped=$integration_skipped_num

log_summary_only "📈 TOTAL: $total_passed passed, $total_failed failed, $total_skipped skipped"

if [[ "$overall_exit_code" -eq 0 ]]; then
    log_summary_only "🎉 ALL TESTS PASSED!"
else
    log_summary_only "❌ SOME TESTS FAILED (exit code: $overall_exit_code)"

    # Extract and display test failure summaries from logcat
    if [[ -f "test_logcat_output.log" ]]; then
        log_summary_only ""
        log_summary_only "❌ TEST FAILURE DETAILS:"
        log_summary_only "=================================="
        # Extract failure summaries logged with TEST_SUMMARY tag
        grep "TEST_SUMMARY.*❌" test_logcat_output.log 2>/dev/null | while read -r line; do
            # Extract the message part after TEST_SUMMARY tag
            failure_msg=$(echo "$line" | sed -E 's/.*TEST_SUMMARY[^:]*: //')
            log_summary_only "   $failure_msg"
        done
        log_summary_only "=================================="
    fi

    log_summary_only "📋 Check log files for details:"
    log_summary_only "   • test_gradle_output.log: Full Gradle build and test execution output"
    log_summary_only "   • test_logcat_output.log: Complete Android logcat from test execution"
    log_summary_only "   • test_summary.log: Human-readable summaries with relevant excerpts"
fi

# Cleanup
if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    log_summary_only "🗑️ Uninstalling debug app..."
    adb uninstall com.example.whiz.debug >/dev/null 2>&1 || true
    adb uninstall com.example.whiz.debug.test >/dev/null 2>&1 || true
    log_summary_only "✅ Debug app and test APK uninstalled"
else
    # Ensure debug app is installed for manual testing
    if ! adb shell pm list packages 2>/dev/null | grep -q "com.example.whiz.debug"; then
        log_summary_only "📱 Debug app not found - installing for manual testing..."
        if ./install.sh 2>/dev/null; then
            log_summary_only "✅ Debug app installed successfully for manual testing"
        else
            log_summary_only "❌ Failed to install debug app for manual testing"
        fi
    else
        log_summary_only "📱 Debug app remains installed for manual testing"
    fi
fi

log_summary_only "✅ Test execution completed. Check test_gradle_output.log (gradle), test_logcat_output.log (logcat), and test_summary.log (summaries)."

# Restore original screen timeout settings
log_with_time "📱 Restoring screen timeout to normal settings..."
adb shell settings put system screen_off_timeout 30000 >/dev/null 2>&1 || true  # 30 seconds default
adb shell svc power stayon false >/dev/null 2>&1 || true  # Restore normal power behavior

# Disable trap before normal exit (trap will only run on abnormal exits now)
trap - EXIT ERR

exit $overall_exit_code 
