#!/bin/bash

echo "🔄 Starting test loop - will stop on first failure..."
count=0
max_runs=50

while [ $count -lt $max_runs ]; do
    count=$((count + 1))
    echo "=================="
    echo "🏃 Run #$count of $max_runs"
    echo "=================="
    
    ./run_tests_on_debug.sh --skip-unit --test "com.example.whiz.integration.ChatViewModelComposeTest#botInterruption_allowsImmediateMessageSending"
    
    if [ $? -ne 0 ]; then
        echo "❌ Test failed on run #$count"
        echo "📋 Preserving logs for debugging"
        exit 1
    fi
    
    echo "✅ Run #$count passed"
    sleep 2
done

echo "🎉 All $max_runs runs passed successfully!"