#!/bin/bash

echo "🚀 Direct Test Runner (Bypassing Gradle)"
echo "========================================"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo -e "${RED}❌ No Android device/emulator connected${NC}"
    echo "Please start an emulator or connect a device"
    exit 1
fi

echo -e "${BLUE}📱 Device: $(adb shell getprop ro.product.model) (API $(adb shell getprop ro.build.version.sdk))${NC}"

# Build APKs if they don't exist
echo ""
echo -e "${YELLOW}🔨 Building APKs...${NC}"
if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ] || [ ! -f "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" ]; then
    echo "Building debug and test APKs..."
    ./gradlew assembleDebug assembleDebugAndroidTest
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ Build failed${NC}"
        exit 1
    fi
fi

# Install APKs
echo ""
echo -e "${YELLOW}📦 Installing APKs...${NC}"
echo "Installing main app..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Failed to install main APK${NC}"
    exit 1
fi

echo "Installing test APK..."
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Failed to install test APK${NC}"
    exit 1
fi

# Run unit tests first
echo ""
echo -e "${BLUE}🧪 Running Unit Tests...${NC}"
./gradlew test --continue
UNIT_TEST_RESULT=$?

# Run instrumented tests
echo ""
echo -e "${BLUE}🎯 Running Instrumented Tests (Direct ADB)...${NC}"
START_TIME=$(date +%s)

# Run all instrumented tests
adb shell am instrument -w -r com.example.whiz.test/com.example.whiz.HiltTestRunner > test_results.txt 2>&1

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# Parse results
if grep -q "OK (" test_results.txt; then
    PASSED_TESTS=$(grep "OK (" test_results.txt | sed 's/.*OK (\([0-9]*\) tests).*/\1/')
    echo -e "${GREEN}✅ All $PASSED_TESTS instrumented tests passed in ${DURATION}s${NC}"
    INSTRUMENTED_RESULT=0
else
    echo -e "${RED}❌ Some instrumented tests failed${NC}"
    INSTRUMENTED_RESULT=1
fi

# Show summary
echo ""
echo "📊 Test Summary"
echo "==============="
if [ $UNIT_TEST_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ Unit Tests: PASSED${NC}"
else
    echo -e "${RED}❌ Unit Tests: FAILED${NC}"
fi

if [ $INSTRUMENTED_RESULT -eq 0 ]; then
    echo -e "${GREEN}✅ Instrumented Tests: PASSED ($PASSED_TESTS tests)${NC}"
else
    echo -e "${RED}❌ Instrumented Tests: FAILED${NC}"
fi

# Show detailed results if requested
if [ "$1" = "--verbose" ] || [ "$1" = "-v" ]; then
    echo ""
    echo "📋 Detailed Instrumented Test Results:"
    echo "======================================"
    cat test_results.txt
fi

# Clean up
rm -f test_results.txt

# Exit with appropriate code
if [ $UNIT_TEST_RESULT -eq 0 ] && [ $INSTRUMENTED_RESULT -eq 0 ]; then
    echo ""
    echo -e "${GREEN}🎉 All tests passed!${NC}"
    exit 0
else
    echo ""
    echo -e "${RED}💥 Some tests failed${NC}"
    exit 1
fi 