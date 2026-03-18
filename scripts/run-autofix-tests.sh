#!/usr/bin/env bash
#
# Run autofix verification tests inside the emulator-runner.
# This runs as a single bash process to avoid shell state issues
# with the emulator-runner's per-line /usr/bin/sh -c execution.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------
export ANDROID_SERIAL=emulator-5554
export ANDROID_HOME="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PYTHONPATH="${ANDROID_ACCESSIBILITY_TESTER_PATH:-.}:${PYTHONPATH:-}"

# ---------------------------------------------------------------------------
# Wait for emulator boot
# ---------------------------------------------------------------------------
echo "==> Waiting for emulator boot..."
adb wait-for-device

# Poll until boot is complete (max 120s)
TIMEOUT=120
ELAPSED=0
while true; do
    BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
    if [[ "$BOOT" == "1" ]]; then
        echo "==> Emulator boot complete after ${ELAPSED}s"
        break
    fi
    if (( ELAPSED >= TIMEOUT )); then
        echo "ERROR: Emulator did not boot within ${TIMEOUT}s"
        exit 1
    fi
    sleep 5
    (( ELAPSED += 5 ))
done

# Short settle time after boot
sleep 5

# ---------------------------------------------------------------------------
# Push test credentials (conftest.py doesn't handle this)
# ---------------------------------------------------------------------------
echo "==> Pushing test credentials..."
adb push test_credentials.json /data/local/tmp/test_credentials.json

# ---------------------------------------------------------------------------
# Verify QCOW2 snapshots before test (detect if emulator-runner wiped them)
# ---------------------------------------------------------------------------
echo "==> Verifying QCOW2 snapshots before test..."
AVD_DIR="$HOME/.android/avd/whiz-test-device.avd"
for qcow2 in "$AVD_DIR"/cache.img.qcow2 "$AVD_DIR"/userdata-qemu.img.qcow2 "$AVD_DIR"/encryptionkey.img.qcow2; do
    if [[ -f "$qcow2" ]]; then
        echo "    $(basename "$qcow2"):"
        qemu-img snapshot -l "$qcow2" 2>&1 | grep -E "(ID|baseline)" || echo "      NO SNAPSHOTS / FILE REPLACED"
    fi
done
echo "==> AVD directory timestamps before test:"
ls -la "$AVD_DIR"/*.qcow2 "$AVD_DIR"/*.img 2>/dev/null || true

# ---------------------------------------------------------------------------
# Verify required apps are installed (catch cold boot / broken snapshot)
# ---------------------------------------------------------------------------
echo "==> Verifying required apps..."
if adb shell pm list packages 2>/dev/null | grep -q "com.whatsapp"; then
    echo "    [OK] WhatsApp is installed"
else
    echo "    [FAIL] WhatsApp is NOT installed"
    echo "ERROR: Snapshot appears to have not loaded correctly (cold boot fallback)."
    echo "Required apps are missing. Check snapshot configuration."
    exit 1
fi

echo "==> Current foreground activity:"
adb shell dumpsys activity activities 2>/dev/null | grep "mResumedActivity" | head -1 || echo "    unknown"

# ---------------------------------------------------------------------------
# Run pytest (conftest.py handles APK install, permissions, screen wake)
# ---------------------------------------------------------------------------
echo "==> Running autofix tests..."
mkdir -p autofix_test_output

EXIT_CODE=0
venv/bin/pytest autofix_tests/ -v --tb=short || EXIT_CODE=$?

echo "==> Pytest exit code: $EXIT_CODE"
exit "$EXIT_CODE"
