#!/bin/bash

# DroidRun Screen Agent Test Runner
# Requires export_anthropic_key.sh (copy from export_anthropic_key.sh.example and add your API key)

set -e

# Function to log with timestamp
log() {
    echo "[$(date '+%H:%M:%S')] $1"
}

# Check if export_anthropic_key.sh exists
if [ ! -f ./droidrun/export_anthropic_key.sh ]; then
    log "Error: export_anthropic_key.sh not found!"
    log "Please copy export_anthropic_key.sh.example to export_anthropic_key.sh and add your Anthropic API key"
    exit 1
fi

# Source the API key
source ./droidrun/export_anthropic_key.sh

# Verify API key is set
if [ -z "$ANTHROPIC_API_KEY" ]; then
    log "Error: ANTHROPIC_API_KEY is not set!"
    log "Please add your API key to export_anthropic_key.sh"
    exit 1
fi

# Default values
PROVIDER="Anthropic"
MODEL="claude-sonnet-4-20250514"

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

log "Installing debug app..."
./install.sh
log "Done installing debug app"

log "Using provider: $PROVIDER"
log "Using model: $MODEL"
echo ""

# Setup DroidRun before running the agent
log "Setting up DroidRun accessibility and keyboard..."

# Hardcode the DroidRun package name
DROIDRUN_PACKAGE="com.droidrun.portal"

# Check if DroidRun accessibility service is already enabled
log "Checking DroidRun accessibility service status..."
ENABLED_SERVICES=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')
log "Current enabled accessibility services: $ENABLED_SERVICES"

if [[ "$ENABLED_SERVICES" == *"$DROIDRUN_PACKAGE"* ]]; then
    log "✅ DroidRun accessibility service is ALREADY ENABLED"
else
    log "❌ DroidRun accessibility service is NOT ENABLED"

    # Close Settings app to ensure clean state when we open it later
    log "Closing Settings app to ensure clean state..."
    adb shell am force-stop com.android.settings
    sleep 1
fi

# Force stop DroidRun app to ensure clean state
log "Closing DroidRun app..."
adb shell am force-stop $DROIDRUN_PACKAGE

sleep 1

# Restart DroidRun app
log "Opening DroidRun app..."
adb shell monkey -p $DROIDRUN_PACKAGE -c android.intent.category.LAUNCHER 1

sleep 1

# Check again after opening the app
log "Checking DroidRun accessibility service status after opening app..."
ENABLED_SERVICES=$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')

if [[ "$ENABLED_SERVICES" == *"$DROIDRUN_PACKAGE"* ]]; then
    log "✅ DroidRun accessibility service is ENABLED - skipping setup"
else
    log "❌ DroidRun accessibility service is NOT ENABLED - need to enable it"

    # Click on DroidRun Portal settings
    log "Clicking on DroidRun Portal settings button..."
    adb shell input tap 500 600
    sleep 0.5
    log "Clicking on DroidRun Portal in Accessibility Settings..."
    adb shell input tap 500 500
    sleep 0.5
    log "Clicking Use Droidrun Portal toggle..."
    adb shell input tap 1000 500
    sleep 0.5
    log "Clicking Allow..."
    adb shell input tap 500 1600

    sleep 1

    # Close Settings app by pressing Home button (more graceful than force-stop)
    log "Returning to home screen..."
    adb shell input keyevent KEYCODE_HOME

    sleep 1
fi

# Check which keyboard is currently active and save it
log "Checking current keyboard..."
ORIGINAL_KEYBOARD=$(adb shell settings get secure default_input_method | tr -d '\r\n')
log "Current keyboard: $ORIGINAL_KEYBOARD"

if [[ "$ORIGINAL_KEYBOARD" == *"droidrun"* ]]; then
    log "✅ DroidRun keyboard is already active"
else
    log "❌ DroidRun keyboard is NOT active"

    # Try to find the DroidRun keyboard IME ID
    log "Looking for DroidRun keyboard IME..."
    DROIDRUN_IME=$(adb shell ime list -s | grep -i droidrun | head -1 | tr -d '\r\n')

    if [ -n "$DROIDRUN_IME" ]; then
        log "Found DroidRun IME: $DROIDRUN_IME"
        log "Switching to DroidRun keyboard..."
        adb shell ime set "$DROIDRUN_IME"

        # Verify the switch worked
        sleep 1
        NEW_KEYBOARD=$(adb shell settings get secure default_input_method | tr -d '\r\n')
        if [[ "$NEW_KEYBOARD" == *"droidrun"* ]]; then
            log "✅ Successfully switched to DroidRun keyboard"
        else
            log "⚠️ Failed to switch to DroidRun keyboard"
        fi
    else
        log "⚠️ DroidRun keyboard not found in IME list"
        log "Available keyboards:"
        adb shell ime list -s
    fi
fi

# echo "Running droidrun setup..."
# droidrun "Are you able to see UI elements through Accessibility Service? If yes, you are done. If not, open Settings. Take a screenshot. If you are not on the settings home page which has no back arrow, click the back arrow on the top left of Settings screen until you are at the settings home page. It may take several clicks. Click Accessibility, scrolling down if necessary. Click Droidrun Portal. Click Use Droidrun Portal. Click Allow on the dialog" --provider "$PROVIDER" --model "$MODEL"
# echo "Finished droidrun setup."


# # Example test: WhatsApp integration test
# echo "Running WhatsApp integration test with DroidRun..."
# echo "=========================================="

# # You can add your specific test commands here
# # For example:
# # droidrun "Open WhatsApp and send a test message" --provider "$PROVIDER" --model "$MODEL"

# # Quick verification test
# echo "Running verification test..."
# droidrun "Open the 🧪 WhizVoice DEBUG app" --provider "$PROVIDER" --model "$MODEL"

# echo ""
# echo "Test completed!"

# droidrun "Open the settings app. If we are not in the home page of the settings app, click the back button until we are. Click on the search bar. Click on the globe icon under [Droidrun Keyboard (ON)] at the bottom of the screen. Scroll down to Accessibility and click it. Click Droidrun Portal. Click [Turn off] on the Turn off Droidrun Portal dialog." --provider "$PROVIDER" --model "$MODEL"

# echo "Test cleanup completed!"

# Restore original keyboard if it was changed
if [[ "$ORIGINAL_KEYBOARD" != *"droidrun"* ]] && [ -n "$ORIGINAL_KEYBOARD" ]; then
    log "Restoring original keyboard: $ORIGINAL_KEYBOARD"
    adb shell ime set "$ORIGINAL_KEYBOARD"

    # Verify restoration
    sleep 1
    FINAL_KEYBOARD=$(adb shell settings get secure default_input_method | tr -d '\r\n')
    if [ "$FINAL_KEYBOARD" = "$ORIGINAL_KEYBOARD" ]; then
        log "✅ Successfully restored original keyboard"
    else
        log "⚠️ Failed to restore original keyboard"
    fi
fi

# Open DroidRun Portal for easy disable
log "Opening DroidRun Portal app..."
adb shell monkey -p $DROIDRUN_PACKAGE -c android.intent.category.LAUNCHER 1
sleep 0.5
adb shell input tap 500 600
sleep 0.5
log "Clicking on DroidRun Portal in Accessibility Settings..."
adb shell input tap 500 500
sleep 0.5
log "Clicking Use Droidrun Portal toggle..."
adb shell input tap 1000 500
sleep 0.5
log "Clicking Disable..."
adb shell input tap 500 1600

log "Script complete!"
