#!/bin/bash

# Docker Setup Script for WhizVoice Android Tests
# This script builds the Docker image and sets up the environment

set -e

echo "🐳 Setting up Docker environment for Android tests"
echo "=================================================="

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed!"
    echo ""
    echo "Please install Docker first:"
    echo "  macOS: https://docs.docker.com/desktop/mac/install/"
    echo "  Linux: https://docs.docker.com/engine/install/"
    echo "  Windows: https://docs.docker.com/desktop/windows/install/"
    exit 1
fi

echo "✅ Docker found: $(docker --version)"

# Check if Docker Compose is available
if command -v docker-compose &> /dev/null; then
    echo "✅ Docker Compose found: $(docker-compose --version)"
    USE_COMPOSE=true
else
    echo "⚠️  Docker Compose not found, using Docker directly"
    USE_COMPOSE=false
fi

echo ""
echo "🔨 Building Android test environment..."
echo "This may take 5-10 minutes on first run (downloading Android SDK)"

if [ "$USE_COMPOSE" = true ]; then
    # Build with Docker Compose
    docker-compose build android-tests
    
    echo ""
    echo "🧪 Testing the setup..."
    docker-compose run --rm android-tests ./gradlew --version
else
    # Build with Docker directly
    docker build -t whizvoice-android-tests .
    
    echo ""
    echo "🧪 Testing the setup..."
    docker run --rm whizvoice-android-tests ./gradlew --version
fi

echo ""
echo "✅ Docker environment setup complete!"
echo ""
echo "Usage:"
echo "  ./test-ci-locally.sh           # Run tests with Docker (default)"
echo "  ./test-ci-locally.sh --docker  # Run tests with Docker"
echo "  ./test-ci-locally.sh --native  # Run tests natively"
echo ""
echo "Docker commands:"
if [ "$USE_COMPOSE" = true ]; then
    echo "  docker-compose run --rm android-tests                    # Run full test suite"
    echo "  docker-compose run --rm android-tests ./gradlew test     # Run only unit tests"
    echo "  docker-compose run --rm android-shell                    # Interactive shell"
else
    echo "  docker run --rm whizvoice-android-tests                  # Run full test suite"
    echo "  docker run --rm whizvoice-android-tests ./gradlew test   # Run only unit tests"
fi
echo ""
echo "🚀 Ready to test!" 