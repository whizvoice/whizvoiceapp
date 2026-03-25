#!/usr/bin/env bash
#
# Run autofix verification tests inside the emulator-runner.
# This runs as a single bash process to avoid shell state issues
# with the emulator-runner's per-line /usr/bin/sh -c execution.
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Crash-safe artifact collection
# ---------------------------------------------------------------------------
# Copy test output to a backup location on ANY exit (success, failure, or crash).
# This ensures artifacts survive even if the emulator-runner action dies.
ARTIFACT_BACKUP="/tmp/autofix-artifacts-backup"
mkdir -p "$ARTIFACT_BACKUP"
MEMORY_MONITOR_PID=""
SYSTEM_LOGCAT_PID=""

cleanup() {
    local exit_code=$?
    echo "==> Trap fired (exit code: $exit_code). Collecting crash diagnostics..."
    # Stop background monitors
    kill "$MEMORY_MONITOR_PID" 2>/dev/null || true
    kill "$SYSTEM_LOGCAT_PID" 2>/dev/null || true

    mkdir -p autofix_test_output

    # Capture crash diagnostics
    echo "==> Crash diagnostics at exit:"
    echo "    Exit code: $exit_code"

    echo "    OOM killer events:"
    dmesg 2>/dev/null | grep -i "oom\|killed process\|out of memory" | tail -20 || echo "    None detected"

    echo "    Emulator processes at exit:"
    ps aux | grep -i "emulator\|qemu" | grep -v grep || echo "    No emulator processes"

    echo "    Memory at exit:"
    free -m 2>/dev/null || echo "    free not available"

    echo "    QCOW2 file locks at exit:"
    AVD_DIR="$HOME/.android/avd/whiz-test-device.avd"
    for f in "$AVD_DIR"/*.qcow2; do
        if [[ -f "$f" ]]; then
            echo "    $(basename "$f"):"
            fuser -v "$f" 2>&1 | sed 's/^/        /' || echo "        no locks"
        fi
    done

    # Save dmesg at exit
    dmesg --time-format iso 2>/dev/null | tail -100 > autofix_test_output/dmesg_at_exit.log || true

    # Copy whatever we have
    if [[ -d autofix_test_output ]]; then
        cp -r autofix_test_output/* "$ARTIFACT_BACKUP/" 2>/dev/null || true
    fi
    echo "==> Backup artifacts saved to $ARTIFACT_BACKUP"
    ls -la "$ARTIFACT_BACKUP/" 2>/dev/null || true
}
trap cleanup EXIT

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
# System diagnostics at boot
# ---------------------------------------------------------------------------
mkdir -p autofix_test_output
echo "==> System diagnostics at boot:"
echo "    Memory:"
free -m 2>/dev/null || echo "    free not available"
echo "    Swap:"
cat /proc/meminfo 2>/dev/null | grep -E "SwapTotal|SwapFree|MemAvailable" | sed 's/^/    /' || true
echo "    Disk:"
df -h / | sed 's/^/    /'
echo "    Emulator processes:"
ps aux | grep -i "emulator\|qemu" | grep -v grep | sed 's/^/    /' || echo "    No emulator processes"
echo "    QCOW2 file locks at boot:"
AVD_DIR_DIAG="$HOME/.android/avd/whiz-test-device.avd"
for f in "$AVD_DIR_DIAG"/*.qcow2; do
    if [[ -f "$f" ]]; then
        echo "    $(basename "$f"):"
        fuser -v "$f" 2>&1 | sed 's/^/        /' || echo "        no locks"
    fi
done

# Save dmesg at boot for comparison with exit
dmesg --time-format iso 2>/dev/null | tail -50 > autofix_test_output/dmesg_at_boot.log || true

# Check emulator clock (snapshot restore can cause clock skew)
echo "==> Checking emulator clock..."
EMU_DATE=$(adb shell date +%s 2>/dev/null | tr -d '\r')
HOST_DATE=$(date +%s)
SKEW=$((HOST_DATE - EMU_DATE))
echo "    Emulator epoch: $EMU_DATE, Host epoch: $HOST_DATE, Skew: ${SKEW}s"
if [ "${SKEW#-}" -gt 60 ]; then
  echo "WARNING: Clock skew > 60s detected. OAuth may fail."
fi

# ---------------------------------------------------------------------------
# DIAGNOSTIC: cache.img.qcow2 checksum after emulator boot
# ---------------------------------------------------------------------------
AVD_DIR="$HOME/.android/avd/whiz-test-device.avd"
echo "==> DIAGNOSTIC: cache files status AFTER emulator boot:"
ls -la "$AVD_DIR/cache.img" "$AVD_DIR/cache.img.qcow2" 2>&1 || echo "    No cache files (expected if snapshot was created without cache partition)"

echo "==> DIAGNOSTIC: QEMU block devices (what files the emulator actually opened):"
EMU_PID=$(pgrep -f "qemu-system" 2>/dev/null || pgrep -f "emulator" 2>/dev/null | head -1)
if [[ -n "$EMU_PID" ]]; then
    echo "    Emulator PID: $EMU_PID"
    echo "    --- Open files matching cache/qcow2/img ---"
    ls -la /proc/"$EMU_PID"/fd 2>/dev/null | grep -i "cache\|qcow2\|\.img" | sed 's/^/    /' || true
    echo "    --- /proc/$EMU_PID/maps matching cache ---"
    grep -i "cache" /proc/"$EMU_PID"/maps 2>/dev/null | sed 's/^/    /' || echo "    (no cache mappings found)"
    echo "    --- All open .img and .qcow2 files ---"
    readlink -f /proc/"$EMU_PID"/fd/* 2>/dev/null | grep -E "\.img|\.qcow2|cache" | sort -u | sed 's/^/    /' || echo "    (could not read fd links)"
else
    echo "    Could not find emulator PID"
fi
echo "    --- AVD directory listing ---"
ls -la "$AVD_DIR"/*.img "$AVD_DIR"/*.qcow2 2>/dev/null | sed 's/^/    /' || echo "    No img/qcow2 files found"

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
QEMU_IMG_EMU="${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}/emulator/qemu-img"

echo "==> QCOW2 file lock state BEFORE qemu-img:"
for f in "$AVD_DIR"/*.qcow2; do
    if [[ -f "$f" ]]; then
        echo "    $(basename "$f"): $(fuser -v "$f" 2>&1 || echo 'no locks')"
    fi
done

set +e  # Don't exit on qemu-img lock errors (emulator holds locks)
for qcow2 in "$AVD_DIR"/userdata-qemu.img.qcow2 "$AVD_DIR"/encryptionkey.img.qcow2; do
    if [[ -f "$qcow2" ]]; then
        echo "    $(basename "$qcow2") ($(du -h "$qcow2" | cut -f1)):"
        echo "      full info:"
        "$QEMU_IMG_EMU" info "$qcow2" 2>&1 | head -8 | sed 's/^/        /'
        echo "      snapshots:"
        "$QEMU_IMG_EMU" snapshot -l "$qcow2" 2>&1 | sed 's/^/        /' || echo "        FAILED to read snapshots"
    fi
done
set -e

echo "==> QCOW2 file lock state AFTER qemu-img:"
for f in "$AVD_DIR"/*.qcow2; do
    if [[ -f "$f" ]]; then
        echo "    $(basename "$f"): $(fuser -v "$f" 2>&1 || echo 'no locks')"
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
# Start background monitoring (before pytest so we capture everything)
# ---------------------------------------------------------------------------
echo "==> Starting background monitors..."
mkdir -p autofix_test_output

# Memory/resource monitor — logs every 15s with swap details
MEMORY_MONITOR_PID=""
(
    while true; do
        echo "--- $(date -u '+%Y-%m-%d %H:%M:%S UTC') ---"
        free -m 2>/dev/null || vm_stat 2>/dev/null || true
        cat /proc/meminfo 2>/dev/null | grep -E "SwapTotal|SwapFree|MemAvailable|Dirty|Writeback" || true
        # Log emulator RSS and VSZ
        EMU_PID=$(pgrep -f "qemu-system" 2>/dev/null || pgrep -f "emulator64" 2>/dev/null || true)
        if [[ -n "$EMU_PID" ]]; then
            echo "Emulator PID $EMU_PID: RSS=$(ps -o rss= -p "$EMU_PID" 2>/dev/null || echo 'N/A')KB VSZ=$(ps -o vsz= -p "$EMU_PID" 2>/dev/null || echo 'N/A')KB"
        fi
        # Check for recent OOM events
        dmesg 2>/dev/null | grep -i "oom\|killed process" | tail -1 || true
        echo ""
        sleep 15
    done
) > autofix_test_output/memory_monitor.log 2>&1 &
MEMORY_MONITOR_PID=$!
echo "    Memory monitor PID: $MEMORY_MONITOR_PID"

# System-wide logcat (warnings and above) — captures GPU crashes, OOM kills, etc.
SYSTEM_LOGCAT_PID=""
adb logcat -c 2>/dev/null || true  # clear buffer for fresh capture
adb logcat '*:W' > autofix_test_output/system_logcat.log 2>&1 &
SYSTEM_LOGCAT_PID=$!
echo "    System logcat PID: $SYSTEM_LOGCAT_PID"

# ---------------------------------------------------------------------------
# Run pytest (conftest.py handles APK install, permissions, screen wake)
# ---------------------------------------------------------------------------
echo "==> Running autofix tests..."

EXIT_CODE=0
venv/bin/pytest autofix_tests/ -v --tb=short || EXIT_CODE=$?

echo "==> Pytest exit code: $EXIT_CODE"

# Stop monitors (trap will also try, but clean exit is better)
kill "$MEMORY_MONITOR_PID" 2>/dev/null || true
kill "$SYSTEM_LOGCAT_PID" 2>/dev/null || true

echo "==> Final artifact listing:"
ls -la autofix_test_output/ 2>/dev/null || true

exit "$EXIT_CODE"
