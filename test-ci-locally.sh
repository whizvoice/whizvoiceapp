#!/bin/bash

# Test CI Locally - Mimics GitHub Actions workflow
# Run this script to test what GitHub Actions will do
# Now supports both Docker and native execution

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

# Function to run tests natively
run_native() {
    echo "🏃 Running tests natively..."

# Make gradlew executable (like GitHub Actions does)
echo "🔧 Making gradlew executable..."
chmod +x gradlew

# Run the same commands that GitHub Actions runs
echo ""
echo "🧹 Cleaning previous builds..."
./gradlew clean

echo ""
echo "🔍 Running lint checks..."
    ./gradlew lintDebug

echo ""
echo "🧪 Running unit tests..."
    ./gradlew test --stacktrace --parallel --build-cache --configuration-cache

echo ""
echo "🏗️  Building debug APK..."
./gradlew assembleDebug
}

# Function to run tests with Docker
run_docker() {
    echo "🐳 Running tests with Docker..."
    
    # Check if Docker Compose is available
    if ! command -v docker-compose &> /dev/null && ! command -v docker &> /dev/null; then
        echo "❌ Docker not found. Falling back to native execution."
        run_native
        return
    fi

    # Build the Docker image if it doesn't exist or if Dockerfile changed
    echo "🔨 Building Docker image (this may take a few minutes on first run)..."
    
    if command -v docker-compose &> /dev/null; then
        # Use Docker Compose (preferred)
        docker-compose build android-tests
        echo ""
        echo "🧪 Running tests in Docker container..."
        docker-compose run --rm android-tests
    else
        # Use Docker directly
        docker build -t whizvoice-android-tests .
        echo ""
        echo "🧪 Running tests in Docker container..."
        docker run --rm \
            -v "$(pwd):/app" \
            -v gradle-cache:/root/.gradle \
            -v android-cache:/root/.android \
            -e GRADLE_OPTS="-Xmx4g -Dorg.gradle.daemon=false" \
            whizvoice-android-tests
    fi
}

# Check for command line arguments
USE_DOCKER=true
if [ "$1" = "--native" ] || [ "$1" = "-n" ]; then
    USE_DOCKER=false
elif [ "$1" = "--docker" ] || [ "$1" = "-d" ]; then
    USE_DOCKER=true
elif [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    echo "Usage: $0 [--docker|-d] [--native|-n] [--help|-h]"
    echo ""
    echo "Options:"
    echo "  --docker, -d    Run tests using Docker (default)"
    echo "  --native, -n    Run tests natively without Docker"
    echo "  --help, -h      Show this help message"
    echo ""
    echo "Docker advantages:"
    echo "  ✅ Consistent environment"
    echo "  ✅ Pre-installed Android SDK"
    echo "  ✅ Isolated from local setup"
    echo "  ✅ Cached dependencies"
    echo ""
    echo "Native advantages:"
    echo "  ✅ Faster startup (no container overhead)"
    echo "  ✅ Uses local Android Studio setup"
    echo "  ✅ No Docker installation required"
    exit 0
fi

# Auto-detect Docker availability if not specified
if [ "$USE_DOCKER" = true ]; then
    if command -v docker &> /dev/null; then
        run_docker
    else
        echo "⚠️  Docker not found. Falling back to native execution."
        echo "   Install Docker to use containerized testing."
        echo ""
        run_native
    fi
else
    run_native
fi

echo ""
echo "✅ All CI steps completed successfully!"
echo ""
echo "📊 Test Results:"
echo "   - Lint results: app/build/reports/lint-results-debug.html"
echo "   - Test results: app/build/reports/tests/testDebugUnitTest/index.html"
echo "   - APK location: app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "🚀 Your code is ready for a pull request!" 