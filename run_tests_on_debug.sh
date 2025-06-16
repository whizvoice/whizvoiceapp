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

# Parse command line arguments
CLEAN_AFTER_TESTS=false
SKIP_UNIT_TESTS=false

for arg in "$@"; do
    case $arg in
        --clean)
            CLEAN_AFTER_TESTS=true
            ;;
        --skip-unit)
            SKIP_UNIT_TESTS=true
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Usage: $0 [--clean] [--skip-unit]"
            exit 1
            ;;
    esac
done

# Clear previous log files
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


# Function to run voice tests using gradle (more reliable than direct adb)
run_voice_tests_with_gradle() {
    local start_time=$(date +%s.%3N)
    log_with_time "🚀 Starting Voice Tests..."
    
    # Add section header to test_output.log
    echo "" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "📋 DETAILED LOGS FOR Voice Tests" >> test_output.log  
    echo "Test Class: com.example.whiz.voice.MicButtonDuringResponseTest" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "" >> test_output.log
    
    if ./gradlew connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.example.whiz.voice.MicButtonDuringResponseTest \
        --console=plain \
        --no-daemon \
        >> test_output.log 2>&1; then
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Voice Tests completed successfully in ${duration}s"
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Voice Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Parse gradle test results for voice tests
        parse_gradle_test_results "Voice Tests" "test_output.log"
        return 0
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Voice Tests failed in ${duration}s (exit code: $exit_code)"
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Voice Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Parse results even from failed runs
        parse_gradle_test_results "Voice Tests" "test_output.log"
        return $exit_code
    fi
}

# Function to parse gradle test results
parse_gradle_test_results() {
    local group_name="$1"
    local log_file="$2"
    
    # Look for gradle test summary patterns
    local test_passed="0"
    local test_failed="0"
    local test_skipped="0"
    
    # Parse from gradle output patterns like "Starting 5 tests" and "FAILED/SUCCESS"
    local total_tests=$(grep -o "Starting [0-9]\+ tests" "$log_file" | grep -o "[0-9]\+" | head -1 | tr -d '\n\r ')
    local failed_count=$(grep -c "FAILED" "$log_file" 2>/dev/null | tr -d '\n\r ' || echo "0")
    # Count unique skipped tests (avoid double counting from multiple lines per test)
    local skipped_count=$(grep -c "MicButtonDuringResponseTest.*SKIP" "$log_file" 2>/dev/null | tr -d '\n\r ' || echo "0")
    
    if [[ -n "$total_tests" && "$total_tests" -gt 0 ]]; then
        test_failed="$failed_count"
        test_skipped="$skipped_count"
        test_passed=$(( ${total_tests:-0} - ${failed_count:-0} - ${skipped_count:-0} ))
    else
        # Fallback: look for BUILD SUCCESSFUL/FAILED
        local build_result=$(grep -E "BUILD SUCCESSFUL|BUILD FAILED" "$log_file" | tail -1)
        if [[ "$build_result" == *"BUILD SUCCESSFUL"* ]]; then
            # Estimate based on test files if no explicit count
            test_passed=$(find app/src/androidTest -name "*MicButtonDuringResponseTest.kt" -exec grep -c "@Test" {} \; 2>/dev/null | awk '{sum += $1} END {print sum+0}' | tr -d '\n')
            test_failed="0"
            test_skipped="0"
        else
            test_passed="0"
            test_failed="unknown"
            test_skipped="0"
        fi
    fi
    
    log_with_time "📊 $group_name Results: $test_passed passed, $test_failed failed, $test_skipped skipped"
    
    # Store results in global variables for final summary
    INTEGRATION_TESTS_PASSED=$test_passed
    INTEGRATION_TESTS_FAILED=$test_failed
    INTEGRATION_TESTS_SKIPPED=$test_skipped
}

# Function to run unit tests
run_unit_tests() {
    local start_time=$(date +%s.%3N)
    log_with_time "🧪 Running unit tests..."
    
    # Add section header to test_output.log
    echo "" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "📋 DETAILED LOGS FOR Unit Tests" >> test_output.log  
    echo "Test Command: ./gradlew testDebugUnitTest" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "" >> test_output.log
    
    # Use temp file to filter out gradle task output
    local temp_gradle_output="temp_gradle_unit_output.log"
    if ./gradlew testDebugUnitTest \
        --console=plain \
        --no-daemon \
        > "$temp_gradle_output" 2>&1; then
        
        # Filter out gradle task lines and append to test_output.log
        grep -v "^> Task :" "$temp_gradle_output" | \
        grep -v "^Configuration on demand" | \
        grep -v "^BUILD SUCCESSFUL" | \
        grep -v "^BUILD FAILED" | \
        grep -v "actionable tasks:" >> test_output.log
        rm -f "$temp_gradle_output"
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Unit tests completed in ${duration}s"
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Unit Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Parse unit test results
        local unit_summary=$(grep -E "BUILD SUCCESSFUL|BUILD FAILED" test_output.log | tail -1)
        if [[ "$unit_summary" == *"BUILD SUCCESSFUL"* ]]; then
            local estimated_tests=$(find app/src/test -name "*.kt" -exec grep -c "@Test" {} \; 2>/dev/null | awk '{sum += $1} END {print sum+0}' | tr -d '\n')
            log_with_time "📊 Unit Test Results: ~$estimated_tests tests passed"
            UNIT_TESTS_PASSED="$estimated_tests"
            UNIT_TESTS_FAILED="0"
        else
            log_with_time "📊 Unit Test Results: Some tests failed"
            UNIT_TESTS_PASSED="0"
            UNIT_TESTS_FAILED="unknown"
        fi
        return 0
    else
        local exit_code=$?
        
        # Filter out gradle task lines and append to test_output.log even for failures
        grep -v "^> Task :" "$temp_gradle_output" | \
        grep -v "^Configuration on demand" | \
        grep -v "^BUILD SUCCESSFUL" | \
        grep -v "^BUILD FAILED" | \
        grep -v "actionable tasks:" >> test_output.log
        rm -f "$temp_gradle_output"
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Unit tests failed in ${duration}s"
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Unit Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        UNIT_TESTS_PASSED="0"
        UNIT_TESTS_FAILED="unknown"
        return $exit_code
    fi
}

# Function to read test credentials
read_test_credentials() {
    local credentials_file="test_credentials.json"
    if [[ ! -f "$credentials_file" ]]; then
        log_with_time "❌ ERROR: $credentials_file not found. Please create it with test credentials."
        exit 1
    fi
    
    TEST_USERNAME=$(grep -o '"email": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)
    TEST_PASSWORD=$(grep -o '"password": "[^"]*"' "$credentials_file" | head -1 | cut -d'"' -f4)
    
    if [[ -z "$TEST_USERNAME" || -z "$TEST_PASSWORD" || "$TEST_PASSWORD" == "REPLACE_WITH_ACTUAL_PASSWORD" ]]; then
        log_with_time "❌ ERROR: Test credentials incomplete"
        exit 1
    fi
    
    log_with_time "🔑 Successfully read test credentials for user: $TEST_USERNAME"
}

# Function to pull test screenshots from device to local folder
pull_test_screenshots() {
    log_with_time "📸 Pulling test screenshots from device..."
    
    # Ensure test_screenshots directory exists (already cleaned before tests)
    mkdir -p test_screenshots
    
    # Check if device has any screenshots (more reliable check)
    local screenshot_files=$(adb shell ls /sdcard/Download/test_screenshots/*.png 2>/dev/null | grep -v "No such file" | head -1)
    
    if [[ -n "$screenshot_files" ]]; then
        # Pull all screenshots from device
        if adb pull /sdcard/Download/test_screenshots/ temp_screenshots/ >/dev/null 2>&1; then
            # Move screenshots from temp folder to final location (find all .png files)
            find temp_screenshots -name "*.png" -exec mv {} test_screenshots/ \; 2>/dev/null || true
            rm -rf temp_screenshots
            local local_count=$(ls -1 test_screenshots/*.png 2>/dev/null | wc -l | tr -d ' ')
            log_with_time "✅ Successfully pulled $local_count screenshots to test_screenshots/"
            
            # List the screenshots that were pulled
            if [[ "$local_count" -gt 0 ]]; then
                log_with_time "📋 Screenshots captured:"
                for screenshot in test_screenshots/*.png; do
                    if [[ -f "$screenshot" ]]; then
                        local filename=$(basename "$screenshot")
                        log_with_time "   • $filename"
                    fi
                done
            fi
        else
            log_with_time "⚠️  Failed to pull screenshots from device"
        fi
    else
        log_with_time "📸 No screenshots found on device (no test failures with screenshots)"
    fi
}

# Function to run integration tests with logcat capture
run_integration_tests_with_logcat() {
    local start_time=$(date +%s.%3N)
    log_with_time "🚀 Starting Integration Tests with Logcat..."
    
    # Add section header to test_output.log
    echo "" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "📋 DETAILED LOGS FOR Integration Tests" >> test_output.log  
    echo "Test Classes: All integration test classes" >> test_output.log
    echo "=================================================================================" >> test_output.log
    echo "" >> test_output.log
    
    # Clear logcat and start capture for test logs in background
    adb logcat -c
    local temp_logcat_file="temp_logcat_integration.log"
    adb logcat -v time "*:S" AppLifecycleTest:D SimpleTTSTest:D AndroidRuntime:E System.err:E TestRunner:I > "$temp_logcat_file" 2>&1 &
    local logcat_pid=$!
    
    # Run all integration tests (this will run all androidTest classes)
    # Use temp file to filter out gradle task output
    local temp_gradle_output="temp_gradle_output.log"
    if ./gradlew connectedDebugAndroidTest \
        --console=plain \
        --no-daemon \
        > "$temp_gradle_output" 2>&1; then
        
        # Filter out gradle task lines and append to test_output.log
        grep -v "^> Task :" "$temp_gradle_output" | \
        grep -v "^Configuration on demand" | \
        grep -v "^BUILD SUCCESSFUL" | \
        grep -v "^BUILD FAILED" | \
        grep -v "actionable tasks:" >> test_output.log
        rm -f "$temp_gradle_output"
        
        # Stop logcat capture
        kill $logcat_pid 2>/dev/null || true
        sleep 1
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Integration Tests completed successfully in ${duration}s"
        
        # Append filtered logcat output to test_output.log
        echo "" >> test_output.log
        echo "📱 ANDROID LOGCAT OUTPUT (AppLifecycleTest TAG):" >> test_output.log
        echo "=================================================================================" >> test_output.log
        if [[ -f "$temp_logcat_file" && -s "$temp_logcat_file" ]]; then
            cat "$temp_logcat_file" >> test_output.log
        else
            echo "No logcat output captured for AppLifecycleTest tag" >> test_output.log
        fi
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Integration Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Parse gradle test results for integration tests
        parse_gradle_test_results "Integration Tests" "test_output.log"
        
        # Clean up temp logcat file
        rm -f "$temp_logcat_file"
        return 0
    else
        local exit_code=$?
        
        # Stop logcat capture
        kill $logcat_pid 2>/dev/null || true
        sleep 1
        
        # Filter out gradle task lines and append to test_output.log even for failures
        grep -v "^> Task :" "$temp_gradle_output" | \
        grep -v "^Configuration on demand" | \
        grep -v "^BUILD SUCCESSFUL" | \
        grep -v "^BUILD FAILED" | \
        grep -v "actionable tasks:" >> test_output.log
        rm -f "$temp_gradle_output"
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Integration Tests failed in ${duration}s (exit code: $exit_code)"
        
        # Append filtered logcat output to test_output.log even for failed tests
        echo "" >> test_output.log
        echo "📱 ANDROID LOGCAT OUTPUT (AppLifecycleTest TAG) - FROM FAILED TEST:" >> test_output.log
        echo "=================================================================================" >> test_output.log
        if [[ -f "$temp_logcat_file" && -s "$temp_logcat_file" ]]; then
            cat "$temp_logcat_file" >> test_output.log
        else
            echo "No logcat output captured for AppLifecycleTest tag" >> test_output.log
        fi
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Add end section to test_output.log
        echo "" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "📋 END OF DETAILED LOGS FOR Integration Tests" >> test_output.log
        echo "=================================================================================" >> test_output.log
        echo "" >> test_output.log
        
        # Parse results even from failed runs
        parse_gradle_test_results "Integration Tests" "test_output.log"
        
        # Clean up temp logcat file
        rm -f "$temp_logcat_file"
        return $exit_code
    fi
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

if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    log_with_time "🗑️ Will uninstall debug app after tests complete"
else
    log_with_time "📱 Debug app will stay installed for manual testing after tests"
fi
echo "" | tee -a test_output.log

# Build and install
run_with_log "Building latest debug version" "./gradlew assembleDebug --console=plain --quiet"
run_with_log "Installing/updating latest debug APK" "adb install -r app/build/outputs/apk/debug/app-debug.apk"

# Build and install test APK
run_with_log "Building test APK" "./gradlew assembleDebugAndroidTest --console=plain --quiet"
run_with_log "Installing test APK" "adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

# Grant permissions and prepare device
log_with_time "🔐 Granting necessary permissions for testing..."
# Wake up the screen and unlock (important for UI tests)
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_MENU
sleep 1
adb shell am start -n com.example.whiz.debug/com.example.whiz.MainActivity >/dev/null 2>&1 || true
sleep 3
adb shell pm grant com.example.whiz.debug android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true
sleep 1
log_with_time "✅ Permissions granted and device prepared"

# Run tests sequentially for maximum reliability
if [[ "$SKIP_UNIT_TESTS" == "true" ]]; then
    log_with_time "📱 Running integration tests only (skipping unit tests)..."
    unit_exit_code=0
    UNIT_TESTS_PASSED="skipped"
    UNIT_TESTS_FAILED="0"
else
    log_with_time "📱 Running tests sequentially for maximum reliability..."
    
    # Run unit tests first
    run_unit_tests
    unit_exit_code=$?
fi

# Clean app state and screenshots before integration tests
log_with_time "🧹 Cleaning app state and old screenshots before integration tests..."
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true

# Clean old screenshots from local directory
rm -rf test_screenshots/* 2>/dev/null || true
mkdir -p test_screenshots

# Clean old screenshots from device
adb shell rm -rf /sdcard/Download/test_screenshots/* 2>/dev/null || true
adb shell mkdir -p /sdcard/Download/test_screenshots 2>/dev/null || true

sleep 2
log_with_time "✅ App state and screenshots cleaned"

# Run integration tests with logcat capture
run_integration_tests_with_logcat
integration_exit_code=$?

# Pull screenshots from device to local folder after all tests complete
pull_test_screenshots

overall_exit_code=$((unit_exit_code + integration_exit_code))

# Final summary
log_with_time "🏁 TEST EXECUTION COMPLETED"
log_with_time "=================================="

log_with_time "📊 SEQUENTIAL EXECUTION SUMMARY:"

log_with_time "   Unit Tests: $UNIT_TESTS_PASSED passed, $UNIT_TESTS_FAILED failed"
log_with_time "   Integration Tests: $INTEGRATION_TESTS_PASSED passed, $INTEGRATION_TESTS_FAILED failed, $INTEGRATION_TESTS_SKIPPED skipped"

total_passed=$(( ${UNIT_TESTS_PASSED:-0} + ${INTEGRATION_TESTS_PASSED:-0} ))
total_failed=$(( ${UNIT_TESTS_FAILED:-0} + ${INTEGRATION_TESTS_FAILED:-0} ))
total_skipped=$(( ${INTEGRATION_TESTS_SKIPPED:-0} ))

log_with_time "📈 TOTAL: $total_passed passed, $total_failed failed, $total_skipped skipped"

if [[ "$overall_exit_code" -eq 0 ]]; then
    log_with_time "🎉 ALL TESTS PASSED!"
else
    log_with_time "❌ SOME TESTS FAILED (exit code: $overall_exit_code)"
    log_with_time "📋 Check test_output.log for full details including:"
    log_with_time "   • Complete test execution logs with individual test names"
    log_with_time "   • Detailed error messages and stack traces"  
    log_with_time "   • Test timing and performance information"
fi

# Cleanup
if [[ "$CLEAN_AFTER_TESTS" == "true" ]]; then
    log_with_time "🗑️ Uninstalling debug app..."
    adb uninstall com.example.whiz.debug >/dev/null 2>&1 || true
    adb uninstall com.example.whiz.debug.test >/dev/null 2>&1 || true
    log_with_time "✅ Debug app and test APK uninstalled"
else
    log_with_time "📱 Debug app remains installed for manual testing"
fi

log_with_time "✅ Test execution completed. Check test_output.log for full details."

exit $overall_exit_code 
