#!/bin/bash

# Run tests on WhizVoice Debug version with PARALLEL EXECUTION and PROCESS ISOLATION
# This builds, installs latest debug version, then runs tests in parallel for speed
# Different test types run in separate processes to avoid interference
# The debug app stays installed after testing for manual testing
#
# Usage:
#   ./run_tests_on_debug.sh          # Run tests in parallel (default)
#   ./run_tests_on_debug.sh --clean  # Run tests in parallel and uninstall app after
#   ./run_tests_on_debug.sh --sequential  # Run tests sequentially (old behavior)

set -e

# Parse command line arguments
CLEAN_AFTER_TESTS=false
SEQUENTIAL_MODE=false
if [[ "$1" == "--clean" ]]; then
    CLEAN_AFTER_TESTS=true
elif [[ "$1" == "--sequential" ]]; then
    SEQUENTIAL_MODE=true
elif [[ "$2" == "--clean" ]]; then
    CLEAN_AFTER_TESTS=true
fi

# Clear previous log files
> test_output.log
> unit_tests.log
> service_tests.log  
> voice_tests.log

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

# Function to run parallel test group using adb am instrument
run_test_group() {
    local group_name="$1"
    local test_class="$2"
    local log_file="$3"
    local start_time=$(date +%s.%3N)
    
    log_with_time "🚀 Starting $group_name in parallel..."
    
    # Run the specific test class using adb am instrument with process isolation
    if adb shell am instrument -w \
        -e class "$test_class" \
        com.example.whiz.debug.test/com.example.whiz.HiltTestRunner \
        > "$log_file" 2>&1; then
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ $group_name completed successfully in ${duration}s"
        
        # Parse results from the specific log file
        parse_test_results_from_file "$group_name" "$log_file"
        return 0
    else
        local exit_code=$?
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ $group_name failed in ${duration}s (exit code: $exit_code)"
        
        # Parse results even from failed runs
        parse_test_results_from_file "$group_name" "$log_file"
        return $exit_code
    fi
}

# Function to parse test results from adb am instrument output
parse_test_results_from_file() {
    local group_name="$1"
    local log_file="$2"
    
    # Look for the instrumentation result summary
    local instrumentation_result=$(grep -E "INSTRUMENTATION_RESULT:" "$log_file" | tail -1)
    local instrumentation_code=$(grep -E "INSTRUMENTATION_CODE:" "$log_file" | tail -1)
    
    # Parse test counts from the output
    local test_passed="0"
    local test_failed="0"
    local test_skipped="0"
    
    # Look for test execution lines
    local test_lines=$(grep -E "^Test running|^OK \([0-9]+ test|^FAILURES!!!" "$log_file")
    
    if [[ -n "$test_lines" ]]; then
        # Count individual test results
        test_passed=$(grep -c "^OK" "$log_file" 2>/dev/null || echo "0")
        test_failed=$(grep -c "^FAILURES!!!" "$log_file" 2>/dev/null || echo "0")
        
        # If we have OK results, extract the number
        local ok_line=$(grep "^OK (" "$log_file" | tail -1)
        if [[ -n "$ok_line" ]]; then
            test_passed=$(echo "$ok_line" | grep -o "[0-9]\+" | head -1)
        fi
        
        # If we have failure results, extract the number
        local failure_line=$(grep "^FAILURES!!!" "$log_file" | tail -1)
        if [[ -n "$failure_line" ]]; then
            # Look for "Tests run: X, Failures: Y" pattern
            local tests_run=$(echo "$failure_line" | grep -o "Tests run: [0-9]\+" | grep -o "[0-9]\+")
            local failures=$(echo "$failure_line" | grep -o "Failures: [0-9]\+" | grep -o "[0-9]\+")
            if [[ -n "$tests_run" && -n "$failures" ]]; then
                test_failed="$failures"
                test_passed=$((tests_run - failures))
            fi
        fi
    fi
    
    # Check instrumentation code for overall success/failure
    local overall_success=true
    if [[ "$instrumentation_code" == *"INSTRUMENTATION_CODE: -1"* ]]; then
        overall_success=false
    fi
    
    # Extract failed test names if any
    local failed_test_names=$(grep -E "FAIL|Error in" "$log_file" | head -5)
    
    if [[ "$test_failed" -gt 0 && -n "$failed_test_names" ]]; then
        log_with_time "📊 $group_name Results: $test_passed passed, $test_failed failed, $test_skipped skipped"
        log_with_time "❌ Failed tests in $group_name:"
        while IFS= read -r test_name; do
            if [[ -n "$test_name" ]]; then
                log_with_time "   • $test_name"
            fi
        done <<< "$failed_test_names"
    else
        log_with_time "📊 $group_name Results: $test_passed passed, $test_failed failed, $test_skipped skipped"
    fi
    
    # Store results in global variables for final summary
    case "$group_name" in
        "Service Tests")
            SERVICE_TESTS_PASSED=$test_passed
            SERVICE_TESTS_FAILED=$test_failed
            SERVICE_TESTS_SKIPPED=$test_skipped
            ;;
        "Voice Tests")
            VOICE_TESTS_PASSED=$test_passed
            VOICE_TESTS_FAILED=$test_failed
            VOICE_TESTS_SKIPPED=$test_skipped
            ;;
    esac
}

# Function to run unit tests
run_unit_tests() {
    local start_time=$(date +%s.%3N)
    log_with_time "🧪 Running unit tests..."
    
    if ./gradlew testDebugUnitTest \
        --console=plain \
        --no-daemon \
        > unit_tests.log 2>&1; then
        
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "✅ Unit tests completed in ${duration}s"
        
        # Parse unit test results
        local unit_summary=$(grep -E "BUILD SUCCESSFUL|BUILD FAILED" unit_tests.log | tail -1)
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
        local end_time=$(date +%s.%3N)
        local duration=$(echo "$end_time - $start_time" | bc)
        log_with_time "❌ Unit tests failed in ${duration}s"
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

# Initialize result variables
UNIT_TESTS_PASSED="0"
UNIT_TESTS_FAILED="0"
SERVICE_TESTS_PASSED="0"
SERVICE_TESTS_FAILED="0"
SERVICE_TESTS_SKIPPED="0"
VOICE_TESTS_PASSED="0"
VOICE_TESTS_FAILED="0"
VOICE_TESTS_SKIPPED="0"

if [[ "$SEQUENTIAL_MODE" == "true" ]]; then
    log_with_time "🧪 Running tests SEQUENTIALLY (old behavior)..."
else
    log_with_time "🚀 Running tests in PARALLEL with PROCESS ISOLATION for maximum speed..."
fi

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

# Grant permissions
log_with_time "🔐 Granting necessary permissions for testing..."
adb shell am start -n com.example.whiz.debug/com.example.whiz.MainActivity >/dev/null 2>&1 || true
sleep 3
adb shell pm grant com.example.whiz.debug android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell am force-stop com.example.whiz.debug >/dev/null 2>&1 || true
sleep 1
log_with_time "✅ Permissions granted"

if [[ "$SEQUENTIAL_MODE" == "true" ]]; then
    # Sequential execution (old behavior)
    log_with_time "📱 Running tests sequentially..."
    
    # Run unit tests first
    run_unit_tests
    unit_exit_code=$?
    
    # Run all instrumented tests together
    if adb shell am instrument -w \
        com.example.whiz.debug.test/com.example.whiz.HiltTestRunner \
        > instrumented_tests.log 2>&1; then
        log_with_time "✅ All instrumented tests completed"
        parse_test_results_from_file "All Instrumented Tests" "instrumented_tests.log"
        instrumented_exit_code=0
    else
        instrumented_exit_code=$?
        log_with_time "❌ Some instrumented tests failed"
        parse_test_results_from_file "All Instrumented Tests" "instrumented_tests.log"
    fi
    
    overall_exit_code=$((unit_exit_code + instrumented_exit_code))
else
    # Parallel execution with process isolation using adb am instrument
    log_with_time "🚀 Starting parallel test execution..."
    
    # Start unit tests in background
    run_unit_tests &
    unit_pid=$!
    
    # Start service tests (MessageDisplayAndLifecycleTest) in background
    run_test_group "Service Tests" "com.example.whiz.integration.MessageDisplayAndLifecycleTest" "service_tests.log" &
    service_pid=$!
    
    # Start voice tests in background  
    run_test_group "Voice Tests" "com.example.whiz.voice.MicButtonDuringResponseTest" "voice_tests.log" &
    voice_pid=$!
    
    log_with_time "⏳ All test groups started in parallel. Waiting for completion..."
    
    # Wait for all background processes and collect exit codes
    wait $unit_pid
    unit_exit_code=$?
    
    wait $service_pid
    service_exit_code=$?
    
    wait $voice_pid
    voice_exit_code=$?
    
    overall_exit_code=$((unit_exit_code + service_exit_code + voice_exit_code))
fi

# Final summary
log_with_time "🏁 TEST EXECUTION COMPLETED"
log_with_time "=================================="

if [[ "$SEQUENTIAL_MODE" == "true" ]]; then
    log_with_time "📊 SEQUENTIAL EXECUTION SUMMARY:"
else
    log_with_time "📊 PARALLEL EXECUTION SUMMARY:"
fi

log_with_time "   Unit Tests: $UNIT_TESTS_PASSED passed, $UNIT_TESTS_FAILED failed"
log_with_time "   Service Tests: $SERVICE_TESTS_PASSED passed, $SERVICE_TESTS_FAILED failed, $SERVICE_TESTS_SKIPPED skipped"
log_with_time "   Voice Tests: $VOICE_TESTS_PASSED passed, $VOICE_TESTS_FAILED failed, $VOICE_TESTS_SKIPPED skipped"

total_passed=$((UNIT_TESTS_PASSED + SERVICE_TESTS_PASSED + VOICE_TESTS_PASSED))
total_failed=$((UNIT_TESTS_FAILED + SERVICE_TESTS_FAILED + VOICE_TESTS_FAILED))
total_skipped=$((SERVICE_TESTS_SKIPPED + VOICE_TESTS_SKIPPED))

log_with_time "📈 TOTAL: $total_passed passed, $total_failed failed, $total_skipped skipped"

if [[ "$overall_exit_code" -eq 0 ]]; then
    log_with_time "🎉 ALL TESTS PASSED!"
else
    log_with_time "❌ SOME TESTS FAILED (exit code: $overall_exit_code)"
    log_with_time "📋 Check individual log files for details:"
    log_with_time "   • unit_tests.log - Unit test details"
    log_with_time "   • service_tests.log - Service test details"  
    log_with_time "   • voice_tests.log - Voice test details"
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
