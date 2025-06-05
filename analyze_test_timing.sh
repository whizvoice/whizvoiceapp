#!/bin/bash

# Script to analyze test timing and identify slow tests
echo "🕐 Analyzing Test Timing"
echo "========================="

if [ ! -f "test_output.log" ]; then
    echo "❌ test_output.log not found. Run tests first."
    exit 1
fi

echo "📊 Overall Test Run Analysis:"
echo "-----------------------------"

# Extract overall timing
START_TIME=$(grep "Starting instrumented tests" test_output.log | head -1 | sed 's/\[//g' | sed 's/\].*//g')
END_TIME=$(grep "All tests completed" test_output.log | tail -1 | sed 's/\[//g' | sed 's/\].*//g')

if [ -n "$START_TIME" ] && [ -n "$END_TIME" ]; then
    echo "⏱️  Test run: $START_TIME → $END_TIME"
fi

# Count tests
TOTAL_TESTS=$(grep -o "Tests [0-9]*/[0-9]*" test_output.log | tail -1 | sed 's/Tests [0-9]*\///g')
if [ -n "$TOTAL_TESTS" ]; then
    echo "📋 Total tests: $TOTAL_TESTS"
fi

# Look for test completion messages and timing
echo ""
echo "📈 Individual Test Analysis:"
echo "------------------------------"

# Extract test names and completion times
grep -E "SUCCESS|FAILED" test_output.log | grep -v "BUILD" | while read line; do
    TIME=$(echo "$line" | sed 's/\[//g' | sed 's/\].*//g')
    TEST_NAME=$(echo "$line" | sed 's/.*> //g' | sed 's/ \[.*//g')
    STATUS=$(echo "$line" | grep -o "SUCCESS\|FAILED")
    echo "[$TIME] $TEST_NAME - $STATUS"
done | sort

echo ""
echo "⏱️  Test Duration Analysis (if available):"
echo "------------------------------------------"

# Look for test start/end pairs and calculate durations
declare -A test_starts
declare -A test_durations

# Parse test timing logs if available
if grep -q "TEST_TIMING" test_output.log; then
    echo "✅ Found detailed test timing logs"
    
    while IFS= read -r line; do
        if [[ $line =~ \[([0-9:\.]+)\].*TEST_TIMING.*started ]]; then
            timestamp="${BASH_REMATCH[1]}"
            test_name=$(echo "$line" | sed 's/.*TEST_TIMING: //g' | sed 's/ started.*//g')
            test_starts["$test_name"]="$timestamp"
        elif [[ $line =~ \[([0-9:\.]+)\].*TEST_TIMING.*finished ]]; then
            timestamp="${BASH_REMATCH[1]}"
            test_name=$(echo "$line" | sed 's/.*TEST_TIMING: //g' | sed 's/ finished.*//g')
            
            if [[ -n "${test_starts[$test_name]}" ]]; then
                start_time="${test_starts[$test_name]}"
                # Simple duration calculation (this is approximate)
                echo "🕐 $test_name: $start_time → $timestamp"
                test_durations["$test_name"]="calculated"
            fi
        fi
    done < <(grep "TEST_TIMING" test_output.log)
    
    if [ ${#test_durations[@]} -eq 0 ]; then
        echo "ℹ️  No matching start/end pairs found in TEST_TIMING logs"
    fi
else
    echo "ℹ️  No TEST_TIMING logs found - using completion timestamps only"
    echo "📝 Note: These only show when tests completed, not how long they took"
fi

echo ""
echo "🔍 Test Progress Analysis:"
echo "--------------------------"

# Show test progress with timing gaps
grep -E "Tests [0-9]*/[0-9]* completed" test_output.log | while read line; do
    TIME=$(echo "$line" | sed 's/\[//g' | sed 's/\].*//g')
    PROGRESS=$(echo "$line" | grep -o "Tests [0-9]*/[0-9]*")
    echo "[$TIME] $PROGRESS"
done

echo ""
echo "⚠️  Potential Slow Test Areas:"
echo "------------------------------"

# Look for timing gaps in test progress
prev_time=""
prev_count=""
grep -E "Tests [0-9]*/[0-9]* completed" test_output.log | while read line; do
    TIME=$(echo "$line" | sed 's/\[//g' | sed 's/\].*//g')
    PROGRESS=$(echo "$line" | grep -o "Tests [0-9]*/[0-9]*")
    COUNT=$(echo "$PROGRESS" | sed 's/Tests //g' | sed 's/\/.*//g')
    
    if [ -n "$prev_time" ] && [ -n "$prev_count" ]; then
        # Convert times to seconds for comparison (simplified)
        time_h=$(echo "$TIME" | cut -d: -f1)
        time_m=$(echo "$TIME" | cut -d: -f2)  
        time_s=$(echo "$TIME" | cut -d: -f3 | cut -d. -f1)
        current_seconds=$((time_h * 3600 + time_m * 60 + time_s))
        
        prev_h=$(echo "$prev_time" | cut -d: -f1)
        prev_m=$(echo "$prev_time" | cut -d: -f2)
        prev_s=$(echo "$prev_time" | cut -d: -f3 | cut -d. -f1)
        prev_seconds=$((prev_h * 3600 + prev_m * 60 + prev_s))
        
        gap=$((current_seconds - prev_seconds))
        test_gap=$((COUNT - prev_count))
        
        if [ $gap -gt 5 ] && [ $test_gap -gt 0 ]; then
            echo "⏳ Gap of ${gap}s between tests $prev_count-$COUNT ($prev_time → $TIME)"
        fi
    fi
    
    prev_time="$TIME"
    prev_count="$COUNT"
done

echo ""
echo "📝 Looking for detailed test logs:"
echo "----------------------------------"

# Look for our detailed timing logs
if grep -q "LoginRealAuthTest\|GoogleSignInAutomator" test_output.log; then
    echo "✅ Found detailed authentication test logs:"
    grep -E "LoginRealAuthTest|GoogleSignInAutomator" test_output.log | head -10
else
    echo "ℹ️  No detailed timing logs found in this run"
fi

echo ""
echo "💡 Recommendations:"
echo "-------------------"
echo "• Large gaps indicate slow test areas"
echo "• Use the timestamp analysis to identify bottlenecks" 
echo "• Add timing logs to specific slow tests for detailed analysis"
echo "• Consider running individual slow tests: ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=TestClassName" 