#!/bin/bash
set -x # Print each command before executing

# --- Configuration ---
# !!! IMPORTANT: Replace these placeholders with your actual app details !!!
PACKAGE_NAME="com.example.whiz"
MAIN_ACTIVITY="com.example.whiz.MainActivity"          # Replace with your app's main activity
# Use environment variable if set, otherwise auto-detect device
ADB_DEVICE_ID="${ADB_DEVICE_ID:-}" # Use environment variable or empty for auto-detection
# !!! --- END IMPORTANT ---

# Project directory is the current directory where the script is located
PROJECT_DIR="." 
# Relative path to the APK from the project directory
# Assumes standard 'app' module and 'debug' build type
APK_PATH_RELATIVE="app/build/outputs/apk/debug/app-debug.apk" 
# Gradle task to build the debug APK
GRADLE_TASK="assembleDebug"

# --- Script Logic ---

echo "------------------------------------"
echo " Android Hot Reload Script "
echo "------------------------------------"

# ADB command prefix. 
# If ADB_DEVICE_ID is set, use it.
if [ -n "$ADB_DEVICE_ID" ]; then
    ADB_CMD="adb -s $ADB_DEVICE_ID"
    echo "[INFO] Targeting device: $ADB_DEVICE_ID"
else
    ADB_CMD="adb"
    echo "[INFO] No ADB_DEVICE_ID set. Using default 'adb'. Ensure only one device/emulator is connected."
fi

echo "[INFO] Navigating to project directory: $PROJECT_DIR"
cd "$PROJECT_DIR" || { 
    echo "[ERROR] Failed to navigate to project directory: $PROJECT_DIR"
    exit 1
}

echo "[INFO] Current directory: $(pwd)"

echo "[INFO] Starting Gradle build (Task: $GRADLE_TASK)..."
# Check if gradlew is executable
if [ ! -x "./gradlew" ]; then
    echo "[ERROR] ./gradlew script not found or not executable in $(pwd)."
    echo "[INFO] Attempting to make it executable: chmod +x ./gradlew"
    chmod +x ./gradlew
    if [ ! -x "./gradlew" ]; then
        echo "[ERROR] Failed to make ./gradlew executable. Please check file permissions and path."
        exit 1
    fi
fi

./gradlew "$GRADLE_TASK"
GRADLE_EXIT_CODE=$?

if [ $GRADLE_EXIT_CODE -ne 0 ]; then
    echo "[ERROR] Gradle build failed with exit code $GRADLE_EXIT_CODE."
    exit 1
fi
echo "[SUCCESS] Gradle build completed successfully."

FULL_APK_PATH="$PROJECT_DIR/$APK_PATH_RELATIVE"
if [ ! -f "$FULL_APK_PATH" ]; then
    # Try to find the APK if the default path is not exact, e.g. if module name isn't 'app'
    # This is a simple search, might need to be more robust
    echo "[WARN] APK not found at default path: $FULL_APK_PATH"
    # Attempt to find any -debug.apk in the expected general outputs directory
    # This is a basic fallback, assumes 'app' module for outputs path
    FOUND_APKS=$(find app/build/outputs/apk -name "*-debug.apk" -print -quit)
    if [ -n "$FOUND_APKS" ]; then
        FULL_APK_PATH=$FOUND_APKS
        echo "[INFO] Found APK at: $FULL_APK_PATH"
    else
        echo "[ERROR] APK file not found after build. Searched in app/build/outputs/apk/*/*-debug.apk. Please check APK_PATH_RELATIVE or Gradle task."
        exit 1
    fi
fi


echo "[INFO] Installing APK: $FULL_APK_PATH"
$ADB_CMD install -r -t "$FULL_APK_PATH"
ADB_INSTALL_EXIT_CODE=$?

if [ $ADB_INSTALL_EXIT_CODE -ne 0 ]; then
    echo "[ERROR] APK installation failed with exit code $ADB_INSTALL_EXIT_CODE."
    echo "[INFO] Common issues: "
    echo "        - No device/emulator connected or detected (run 'adb devices')."
    echo "        - Insufficient storage on device."
    echo "        - Signature mismatch if you changed keys (unlikely for debug builds)."
    echo "        - ADB server issues (try 'adb kill-server && adb start-server')."
    exit 1
fi
echo "[SUCCESS] APK installation completed successfully."

# Construct the full activity name
# If MAIN_ACTIVITY starts with a '.', prepend the PACKAGE_NAME
FULL_ACTIVITY_NAME="$PACKAGE_NAME/$MAIN_ACTIVITY"
if [[ "$MAIN_ACTIVITY" == .* ]]; then
    FULL_ACTIVITY_NAME="$PACKAGE_NAME/$PACKAGE_NAME$MAIN_ACTIVITY"
else
    FULL_ACTIVITY_NAME="$PACKAGE_NAME/$MAIN_ACTIVITY"
fi

echo "[INFO] Launching app (Activity: $FULL_ACTIVITY_NAME)..."
$ADB_CMD shell am start -n "$FULL_ACTIVITY_NAME"
ADB_LAUNCH_EXIT_CODE=$?

if [ $ADB_LAUNCH_EXIT_CODE -ne 0 ]; then
    echo "[ERROR] App launch failed with exit code $ADB_LAUNCH_EXIT_CODE."
    echo "[INFO] Common issues: "
    echo "        - Incorrect PACKAGE_NAME or MAIN_ACTIVITY in the script."
    echo "        - App crashed on startup (check 'adb logcat')."
    exit 1
fi
echo "[SUCCESS] App launched successfully!"

# Play chime sound to notify that hot reload is complete
echo "[INFO] Playing chime sound to notify completion..."
afplay /System/Library/Sounds/Glass.aiff

echo "[INFO] Listing connected devices/emulators..."
$ADB_CMD devices -l

echo "[INFO] Clearing logcat buffer..."
$ADB_CMD logcat -c

echo "------------------------------------"

echo "[INFO] Starting filtered logcat for voice activation debugging..."
# Show all logs from the app's package, not just filtered keywords
$ADB_CMD logcat | grep "$PACKAGE_NAME"

echo "[INFO] Starting logcat. Press Ctrl+C to stop."
# Output logs with tags, at Debug level for all tags, to identify sources
echo "[INFO] Starting general logcat..."
$ADB_CMD logcat *:I | grep -i "whiz"
# Start a second logcat process to capture AndroidRuntime errors and stack traces
echo "[INFO] Starting AndroidRuntime error logcat..."
$ADB_CMD logcat AndroidRuntime:E *:S
# Start a third logcat process to capture all errors
echo "[INFO] Starting error logcat..."
$ADB_CMD logcat *:E
# Wait for all background processes
wait

echo "------------------------------------"

exit 0 