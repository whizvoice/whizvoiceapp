#!/bin/bash

echo "🧪 Running All Tests for Whiz Android App"
echo "=========================================="

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✅ $2${NC}"
    else
        echo -e "${RED}❌ $2${NC}"
    fi
}

# Function to print section headers
print_section() {
    echo -e "\n${YELLOW}📋 $1${NC}"
    echo "----------------------------------------"
}

# Clean previous builds
print_section "Cleaning Project"
./gradlew clean
print_status $? "Project cleaned"

# Run unit tests
print_section "Running Unit Tests"
./gradlew testDebugUnitTest
UNIT_TEST_RESULT=$?
print_status $UNIT_TEST_RESULT "Unit tests completed"

# Run instrumented tests (if connected device/emulator is available)
print_section "Running Instrumented Tests"
echo "Checking for connected devices..."

# Check if there are any connected devices
DEVICES=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)

if [ $DEVICES -gt 0 ]; then
    echo "Found connected device(s). Running instrumented tests..."
    ./gradlew connectedAndroidTest
    INSTRUMENTED_TEST_RESULT=$?
    print_status $INSTRUMENTED_TEST_RESULT "Instrumented tests completed"
else
    echo -e "${YELLOW}⚠️  No connected devices found. Skipping instrumented tests.${NC}"
    echo "To run instrumented tests:"
    echo "1. Connect an Android device via USB with USB debugging enabled, OR"
    echo "2. Start an Android emulator"
    echo "3. Run: ./gradlew connectedAndroidTest"
    INSTRUMENTED_TEST_RESULT=0  # Don't fail the script for missing device
fi

# Generate test reports summary
print_section "Test Results Summary"

if [ $UNIT_TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ Unit Tests: PASSED${NC}"
    echo "   📊 Report: app/build/reports/tests/testDebugUnitTest/index.html"
else
    echo -e "${RED}❌ Unit Tests: FAILED${NC}"
    echo "   📊 Report: app/build/reports/tests/testDebugUnitTest/index.html"
fi

if [ $DEVICES -gt 0 ]; then
    if [ $INSTRUMENTED_TEST_RESULT -eq 0 ]; then
        echo -e "${GREEN}✅ Instrumented Tests: PASSED${NC}"
        echo "   📊 Report: app/build/reports/androidTests/connected/index.html"
    else
        echo -e "${RED}❌ Instrumented Tests: FAILED${NC}"
        echo "   📊 Report: app/build/reports/androidTests/connected/index.html"
    fi
else
    echo -e "${YELLOW}⚠️  Instrumented Tests: SKIPPED (no device)${NC}"
fi

# Overall result
print_section "Overall Result"
if [ $UNIT_TEST_RESULT -eq 0 ] && ([ $DEVICES -eq 0 ] || [ $INSTRUMENTED_TEST_RESULT -eq 0 ]); then
    echo -e "${GREEN}🎉 All available tests PASSED!${NC}"
    exit 0
else
    echo -e "${RED}💥 Some tests FAILED. Check the reports above.${NC}"
    exit 1
fi 