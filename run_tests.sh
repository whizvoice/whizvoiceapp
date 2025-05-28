#!/bin/bash

# Test runner script for WhizVoice Android app
# Usage: ./run_tests.sh [unit|integration|ui|all]

set -e

echo "🧪 WhizVoice Test Runner"
echo "========================"

# Default to unit tests if no argument provided
TEST_TYPE=${1:-unit}

case $TEST_TYPE in
    "unit")
        echo "🔬 Running Unit Tests..."
        ./gradlew test --info
        ;;
    "integration")
        echo "🔗 Running Integration Tests..."
        ./gradlew connectedAndroidTest --info
        ;;
    "ui")
        echo "📱 Running UI Tests..."
        ./gradlew connectedAndroidTest --info
        ;;
    "all")
        echo "🚀 Running All Tests..."
        echo "1. Unit Tests..."
        ./gradlew test --info
        echo "2. Integration Tests..."
        ./gradlew connectedAndroidTest --info
        ;;
    "coverage")
        echo "📊 Running Tests with Coverage..."
        ./gradlew testDebugUnitTestCoverage --info
        ;;
    "specific")
        if [ -z "$2" ]; then
            echo "❌ Please specify a test class or method"
            echo "Usage: ./run_tests.sh specific com.example.whiz.ui.viewmodels.ChatsListViewModelTest"
            exit 1
        fi
        echo "🎯 Running Specific Test: $2"
        ./gradlew test --tests "$2" --info
        ;;
    *)
        echo "❌ Unknown test type: $TEST_TYPE"
        echo "Available options:"
        echo "  unit        - Run unit tests only (default)"
        echo "  integration - Run integration tests"
        echo "  ui          - Run UI tests"
        echo "  all         - Run all tests"
        echo "  coverage    - Run tests with coverage report"
        echo "  specific    - Run a specific test class/method"
        echo ""
        echo "Examples:"
        echo "  ./run_tests.sh unit"
        echo "  ./run_tests.sh specific ChatsListViewModelTest"
        echo "  ./run_tests.sh coverage"
        exit 1
        ;;
esac

echo "✅ Tests completed!" 