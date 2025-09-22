#!/bin/bash

# DroidRun Screen Agent Test Runner
# Requires export_anthropic_key.sh (copy from export_anthropic_key.sh.example and add your API key)

set -e

# Check if export_anthropic_key.sh exists
if [ ! -f ./export_anthropic_key.sh ]; then
    echo "Error: export_anthropic_key.sh not found!"
    echo "Please copy export_anthropic_key.sh.example to export_anthropic_key.sh and add your Anthropic API key"
    exit 1
fi

# Source the API key
source ./export_anthropic_key.sh

# Verify API key is set
if [ -z "$ANTHROPIC_API_KEY" ]; then
    echo "Error: ANTHROPIC_API_KEY is not set!"
    echo "Please add your API key to export_anthropic_key.sh"
    exit 1
fi

# Activate Python virtual environment if it exists
if [ -d "venv" ]; then
    echo "Activating Python virtual environment..."
    source venv/bin/activate
else
    echo "Warning: venv directory not found. Make sure DroidRun is installed."
fi

# Default values
PROVIDER="Anthropic"
MODEL="claude-3-5-sonnet-20241022"

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --model)
            MODEL="$2"
            shift 2
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --model MODEL    Specify the Claude model to use (default: claude-3-5-sonnet-20241022)"
            echo "  --help           Show this help message"
            echo ""
            echo "Example:"
            echo "  $0"
            echo "  $0 --model claude-3-5-haiku-20241022"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo "Using provider: $PROVIDER"
echo "Using model: $MODEL"
echo ""

# Example test: WhatsApp integration test
echo "Running WhatsApp integration test with DroidRun..."
echo "=========================================="

# You can add your specific test commands here
# For example:
# droidrun "Open WhatsApp and send a test message" --provider "$PROVIDER" --model "$MODEL"

# Quick verification test
echo "Running verification test..."
droidrun "Open the settings app and tell me the Android version" --provider "$PROVIDER" --model "$MODEL"

echo ""
echo "Test completed!"