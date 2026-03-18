#!/usr/bin/env bash
#
# Run autofix verification tests on the emulator.
#
# This script:
#   1. Boots the emulator from the baseline_clean snapshot
#   2. Installs the latest debug APK
#   3. Runs tests in autofix_tests/ folder
#   4. Reports results
#
# Usage:
#   ./run_autofix_tests.sh                   # Run all autofix tests
#   ./run_autofix_tests.sh -k "test_name"    # Run a specific test
#   ./run_autofix_tests.sh --skip-boot       # Skip emulator boot (if already running)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

AVD_NAME="whiz-test-device"
SNAPSHOT_NAME="baseline_clean"
EMULATOR_SERIAL="emulator-5556"
ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
EMULATOR_BIN="$ANDROID_HOME/emulator/emulator"
ADB_BIN="$ANDROID_HOME/platform-tools/adb"

SKIP_BOOT=false
PYTEST_ARGS=()

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-boot)
            SKIP_BOOT=true
            shift
            ;;
        *)
            PYTEST_ARGS+=("$1")
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Helper functions
# ---------------------------------------------------------------------------

log() {
    echo "[$(date '+%H:%M:%S')] $*"
}

wait_for_emulator() {
    log "Waiting for emulator to be ready..."
    local max_wait=120
    local elapsed=0
    while [[ $elapsed -lt $max_wait ]]; do
        local boot_completed
        boot_completed=$("$ADB_BIN" -s "$EMULATOR_SERIAL" shell getprop sys.boot_completed 2>/dev/null || echo "")
        if [[ "$boot_completed" == "1" ]]; then
            log "Emulator is ready"
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    log "ERROR: Emulator did not boot within ${max_wait}s"
    return 1
}

is_emulator_running() {
    "$ADB_BIN" -s "$EMULATOR_SERIAL" get-state 2>/dev/null | grep -q "device"
}

verify_snapshot_environment() {
    # Verify the snapshot loaded correctly by checking expected apps/state
    log "Verifying snapshot environment..."
    local failed=false

    # Check WhatsApp is installed
    if "$ADB_BIN" -s "$EMULATOR_SERIAL" shell pm list packages 2>/dev/null | grep -q "com.whatsapp"; then
        log "  [OK] WhatsApp is installed"
    else
        log "  [FAIL] WhatsApp is NOT installed - snapshot may not have loaded correctly"
        failed=true
    fi

    # Check WhizVoice debug app is installed
    if "$ADB_BIN" -s "$EMULATOR_SERIAL" shell pm list packages 2>/dev/null | grep -q "com.example.whiz.debug"; then
        log "  [OK] WhizVoice debug app is installed"
    else
        log "  [WARN] WhizVoice debug app not installed (will be installed by test fixture)"
    fi

    # Log all installed packages for debugging
    log "  Installed packages:"
    "$ADB_BIN" -s "$EMULATOR_SERIAL" shell pm list packages 2>/dev/null | sort | while read -r pkg; do
        log "    $pkg"
    done

    # Check current foreground activity
    local current_activity
    current_activity=$("$ADB_BIN" -s "$EMULATOR_SERIAL" shell dumpsys activity activities 2>/dev/null | grep "mResumedActivity" | head -1 || echo "unknown")
    log "  Current activity: $current_activity"

    if [[ "$failed" == true ]]; then
        log "ERROR: Snapshot environment verification failed."
        log "The '$SNAPSHOT_NAME' snapshot appears to be missing required apps."
        log "Please recreate the snapshot with WhatsApp installed and configured."
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Emulator boot
# ---------------------------------------------------------------------------

if [[ "$SKIP_BOOT" == false ]]; then
    # Verify snapshot exists before attempting to boot
    AVD_DIR="$HOME/.android/avd/${AVD_NAME}.avd"
    SNAPSHOT_DIR="$AVD_DIR/snapshots/$SNAPSHOT_NAME"
    if [[ ! -d "$SNAPSHOT_DIR" ]]; then
        log "ERROR: Snapshot '$SNAPSHOT_NAME' not found at $SNAPSHOT_DIR"
        log "Available snapshots:"
        ls "$AVD_DIR/snapshots/" 2>/dev/null || log "  (no snapshots directory found)"
        log ""
        log "To create the snapshot, boot the emulator manually, set up the test"
        log "environment (install WhatsApp, sign in, etc.), then save a snapshot."
        exit 1
    fi
    log "Snapshot '$SNAPSHOT_NAME' found at $SNAPSHOT_DIR"
    ls -lh "$SNAPSHOT_DIR/" | while read -r line; do log "  $line"; done

    log "Booting emulator '$AVD_NAME' from snapshot '$SNAPSHOT_NAME'..."

    # Kill any existing emulator on this port
    if is_emulator_running; then
        log "Stopping existing emulator on $EMULATOR_SERIAL..."
        "$ADB_BIN" -s "$EMULATOR_SERIAL" emu kill 2>/dev/null || true
        sleep 3
    fi

    # Boot emulator from snapshot (use -no-snapshot-save to avoid overwriting
    # the baseline snapshot on exit)
    "$EMULATOR_BIN" -avd "$AVD_NAME" \
        -snapshot "$SNAPSHOT_NAME" \
        -no-snapshot-save \
        -port 5556 -no-audio -gpu swiftshader_indirect &
    EMULATOR_PID=$!
    log "Emulator PID: $EMULATOR_PID"

    # Wait for boot
    wait_for_emulator

    # Give the system a moment to settle after boot
    sleep 5

    # Verify the snapshot actually loaded with the right environment
    verify_snapshot_environment
else
    if ! is_emulator_running; then
        log "ERROR: --skip-boot specified but emulator is not running on $EMULATOR_SERIAL"
        exit 1
    fi
    log "Using already-running emulator on $EMULATOR_SERIAL"

    # Still verify environment even when reusing a running emulator
    verify_snapshot_environment
fi

# ---------------------------------------------------------------------------
# Venv setup
# ---------------------------------------------------------------------------

VENV_DIR="$SCRIPT_DIR/venv"

if [[ ! -d "$VENV_DIR" ]]; then
    log "Creating Python virtual environment..."
    python3 -m venv "$VENV_DIR"
fi

# Install dependencies if needed
log "Checking Python dependencies..."
"$VENV_DIR/bin/pip" install -q -r "$SCRIPT_DIR/requirements.txt" 2>/dev/null

# ---------------------------------------------------------------------------
# Run tests
# ---------------------------------------------------------------------------

export ANDROID_SERIAL="$EMULATOR_SERIAL"

log "Running autofix tests..."
log "=================================================="

"$VENV_DIR/bin/pytest" autofix_tests/ -v "${PYTEST_ARGS[@]+"${PYTEST_ARGS[@]}"}"
TEST_EXIT=$?

log "=================================================="
if [[ $TEST_EXIT -eq 0 ]]; then
    log "All autofix tests PASSED"
else
    log "Some autofix tests FAILED (exit code: $TEST_EXIT)"
    log "Check autofix_test_output/ for screenshots and logs"
fi

exit $TEST_EXIT
