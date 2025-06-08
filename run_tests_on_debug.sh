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

log_with_time "🧪 Running tests on WhizVoice Debug with latest changes..."

# Count and display total tests
count_total_tests

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

# Run unit tests first (fast, always use latest code)
log_with_time "🧪 Starting unit tests..."
monitor_tests "Unit tests" &
MONITOR_PID=$!
run_with_log "Running unit tests with latest code" "./gradlew testDebugUnitTest --console=plain"
kill $MONITOR_PID 2>/dev/null || true

# Run instrumented tests on debug build
log_with_time "🧪 Starting instrumented tests..."

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
{
    # Follow the gradle log output and parse test information
    tail -f test_output.log 2>/dev/null | while IFS= read -r line; do
        # Look for gradle test execution patterns
        if [[ "$line" == *"started"* && "$line" == *"Test"* ]]; then
            test_name=$(echo "$line" | sed -E 's/.*Test ([^[:space:]]+) started.*/\1/' | head -c 50)
            if [[ -n "$test_name" && "$test_name" != "$line" ]]; then
                current_count=$(($(grep -c "CURRENT_TEST=" .test_status 2>/dev/null || echo "0") + 1))
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
} &
TEST_PROGRESS_PID=$!

monitor_tests "Instrumented tests" &
MONITOR_PID=$!
run_with_log "Running instrumented tests on latest debug build" "./gradlew connectedDebugAndroidTest --console=plain"
kill $MONITOR_PID 2>/dev/null || true
kill $LOGCAT_PID 2>/dev/null || true
kill $TEST_PROGRESS_PID 2>/dev/null || true

# Clean up progress files
rm -f .test_progress .test_status 2>/dev/null || true

log_with_time "📱 Stopped logcat monitoring"

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
echo "📋 Full test log saved to: test_output.log" | tee -a test_output.log 