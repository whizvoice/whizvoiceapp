#!/bin/bash

# Run tests on WhizVoice Debug version with latest changes
# This builds, installs latest debug version, then runs all tests
# All output is logged to test_output.log for monitoring

set -e

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

# Function to monitor test progress in background
monitor_tests() {
    local test_type="$1"
    sleep 5 # Wait for tests to start
    
    while true; do
        # Check if gradlew process is still running
        if ! pgrep -f "gradlew.*Test" > /dev/null; then
            break
        fi
        
        # Log current time to show tests are still running
        log_with_time "📊 $test_type still running..."
        sleep 30 # Check every 30 seconds
    done
}

log_with_time "🧪 Running tests on WhizVoice Debug with latest changes..."
echo "" | tee -a test_output.log

# Build the latest debug APK
run_with_log "Building latest debug version" "./gradlew assembleDebug --console=plain --quiet"

# Install the latest debug APK
run_with_log "Installing latest debug APK" "adb install -r app/build/outputs/apk/debug/app-debug.apk"

# Run unit tests first (fast, always use latest code)
log_with_time "🧪 Starting unit tests..."
monitor_tests "Unit tests" &
MONITOR_PID=$!
run_with_log "Running unit tests with latest code" "./gradlew testDebugUnitTest --console=plain --quiet"
kill $MONITOR_PID 2>/dev/null || true

# Run instrumented tests on debug build
log_with_time "🧪 Starting instrumented tests..."

# Start logcat monitoring in background to capture test logs
log_with_time "📱 Starting logcat monitoring for test details..."
adb logcat -c  # Clear logcat
adb logcat "*:I" | grep -E "(TEST_EXECUTION|TestRunner|InstrumentationResultPrinter|started|finished)" | while read line; do
    echo "[$(date '+%H:%M:%S.%3N')] LOGCAT: $line" >> test_output.log
done &
LOGCAT_PID=$!

# Also start a more comprehensive test monitoring that captures individual test starts/ends
adb logcat "*:I" | grep -E "(class.*method.*started|class.*method.*finished)" | while read line; do
    echo "[$(date '+%H:%M:%S.%3N')] TEST_TIMING: $line" >> test_output.log
done &
TEST_TIMING_PID=$!

monitor_tests "Instrumented tests" &
MONITOR_PID=$!
run_with_log "Running instrumented tests on latest debug build" "./gradlew connectedDebugAndroidTest --console=plain --quiet"
kill $MONITOR_PID 2>/dev/null || true
kill $LOGCAT_PID 2>/dev/null || true
kill $TEST_TIMING_PID 2>/dev/null || true

log_with_time "📱 Stopped logcat monitoring"

echo "" | tee -a test_output.log
log_with_time "✅ All tests completed with latest changes!"
echo "" | tee -a test_output.log
echo "📊 Test reports available at:" | tee -a test_output.log
echo "   • Unit tests: app/build/reports/tests/testDebugUnitTest/index.html" | tee -a test_output.log
echo "   • Instrumented tests: app/build/reports/androidTests/connected/debug/index.html" | tee -a test_output.log
echo "" | tee -a test_output.log
echo "📱 Latest debug app (🧪 WhizVoice DEBUG) is ready for manual testing too!" | tee -a test_output.log
echo "" | tee -a test_output.log
echo "📋 Full test log saved to: test_output.log" | tee -a test_output.log 