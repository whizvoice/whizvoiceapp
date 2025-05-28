#!/bin/bash

# Test CI Locally - Mimics GitHub Actions workflow
# Run this script to test what GitHub Actions will do

set -e

echo "🧪 Testing CI Pipeline Locally"
echo "==============================="

# Check if we're in the right directory (should have gradlew)
if [ ! -f "gradlew" ]; then
    echo "❌ Error: gradlew not found in current directory"
    echo "Please run this script from the whizvoiceapp directory"
    exit 1
fi

echo "📁 Current directory: $(pwd)"

# Make gradlew executable (like GitHub Actions does)
echo "🔧 Making gradlew executable..."
chmod +x gradlew

# Run the same commands that GitHub Actions runs
echo ""
echo "🧹 Cleaning previous builds..."
./gradlew clean

echo ""
echo "🔍 Running lint checks..."
./gradlew lint

echo ""
echo "🧪 Running unit tests..."
./gradlew test --stacktrace

echo ""
echo "🏗️  Building debug APK..."
./gradlew assembleDebug

echo ""
echo "✅ All CI steps completed successfully!"
echo ""
echo "📊 Test Results:"
echo "   - Lint results: app/build/reports/lint-results-debug.html"
echo "   - Test results: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   - APK location: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "🚀 Your code is ready for a pull request!" 