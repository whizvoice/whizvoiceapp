#!/usr/bin/env bash
#
# SPIKE: run the instrumented UiAutomator screen agent tests on the CI snapshot emulator.
#
# Runs inside reactivecircus/android-emulator-runner (single bash process, see
# run-autofix-tests.sh for why). Expects the x86_64 snapshot to already be booted.
#
#   TEST_CLASSES  comma-separated fully-qualified test classes (required)
#
# Output lands in screen_agent_test_output/ for the upload-artifact step.
#
set -euo pipefail

: "${TEST_CLASSES:?TEST_CLASSES is required}"

OUT=screen_agent_test_output
mkdir -p "$OUT"
LOGCAT_PID=""

cleanup() {
    local exit_code=$?
    echo "==> Exit trap (code $exit_code): collecting artifacts..."
    kill "$LOGCAT_PID" 2>/dev/null || true
    adb shell sync 2>/dev/null || true
    mkdir -p "$OUT/test_screenshots"
    adb pull /sdcard/Download/test_screenshots "$OUT/" 2>/dev/null || echo "    no test_screenshots on device"
    adb shell 'ls /sdcard/Download/whiz_ui_dump_*.txt 2>/dev/null' | tr -d '\r' | while read -r f; do
        [[ -n "$f" ]] && adb pull "$f" "$OUT/" 2>/dev/null || true
    done
    adb shell dumpsys accessibility 2>/dev/null > "$OUT/dumpsys_accessibility_at_exit.txt" || true
    cp -r app/build/outputs/androidTest-results "$OUT/" 2>/dev/null || true
    cp -r app/build/reports/androidTests "$OUT/" 2>/dev/null || true
    ls -la "$OUT" || true
}
trap cleanup EXIT

export ANDROID_SERIAL=emulator-5554
export ANDROID_HOME="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# ---------------------------------------------------------------------------
# Wait for boot
# ---------------------------------------------------------------------------
echo "==> Waiting for emulator boot..."
adb wait-for-device
ELAPSED=0
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( ELAPSED >= 120 )); then echo "ERROR: emulator did not boot in 120s"; exit 1; fi
    sleep 5; (( ELAPSED += 5 ))
done
echo "==> Boot complete after ${ELAPSED}s"
sleep 5

# ---------------------------------------------------------------------------
# What is actually in this snapshot? (the question this spike exists to answer)
# ---------------------------------------------------------------------------
echo "==> Installed packages of interest:"
adb shell pm list packages | tr -d '\r' | sort > "$OUT/pm_list_packages.txt"
for pkg in com.whatsapp com.google.android.apps.maps com.google.android.apps.youtube.music \
           com.google.android.apps.messaging com.fitbit.FitbitMobile com.example.whiz.debug; do
    if grep -q "^package:$pkg$" "$OUT/pm_list_packages.txt"; then
        echo "    [OK]      $pkg"
    else
        echo "    [MISSING] $pkg"
    fi
done
if ! grep -q "^package:com.whatsapp$" "$OUT/pm_list_packages.txt"; then
    echo "ERROR: WhatsApp missing -> snapshot did not load (cold boot fallback)."
    exit 1
fi

echo "==> Contacts named 'Ruth Grace Wong' on device:"
adb shell content query --uri content://com.android.contacts/contacts --projection display_name \
    --where "\"display_name LIKE '%Ruth%'\"" 2>&1 | tr -d '\r' | head -5 || true

echo "==> Build fingerprint: $(adb shell getprop ro.build.fingerprint | tr -d '\r')"

# ---------------------------------------------------------------------------
# Device prep (mirrors android-ci.yml instrumented job)
# ---------------------------------------------------------------------------
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 2147483647
adb push test_credentials.json /data/local/tmp/test_credentials.json

echo "==> Installing debug app over the snapshot's copy..."
./gradlew installDebug --console=plain --stacktrace > "$OUT/install-output.log" 2>&1

PKG=com.example.whiz.debug
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || true
adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell pm grant $PKG android.permission.READ_CONTACTS || true
adb shell pm grant $PKG android.permission.SEND_SMS || true
adb shell pm grant $PKG android.permission.READ_SMS || true
adb shell settings put secure enabled_accessibility_services $PKG/com.example.whiz.accessibility.WhizAccessibilityService
adb shell settings put secure accessibility_enabled 1
sleep 2
echo "==> Accessibility services bound:"
adb shell dumpsys accessibility | grep -iE "bound|WhizAccessibilityService" | head -5 || true

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
adb logcat -c || true
adb logcat -v threadtime > "$OUT/logcat.log" 2>&1 &
LOGCAT_PID=$!

echo "==> Running: $TEST_CLASSES (allowEmulator=true)"
set +e
./gradlew connectedDebugAndroidTest --console=plain --stacktrace --continue \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASSES" \
    -Pandroid.testInstrumentationRunnerArguments.allowEmulator=true \
    > "$OUT/gradle-output.log" 2>&1
GRADLE_EXIT=$?
set -e

echo "==> Gradle exit code: $GRADLE_EXIT"
echo "==> Per-test outcomes (from JUnit XML):"
find app/build/outputs/androidTest-results/connected -name '*.xml' -print0 2>/dev/null \
    | xargs -0 grep -hoE '<testcase [^>]*name="[^"]+"[^>]*>|<skipped|<failure' 2>/dev/null \
    | sed -E 's/.*name="([^"]+)".*/TEST \1/; s/<skipped.*/    -> SKIPPED/; s/<failure.*/    -> FAILED/' || true
echo "==> Skip-rule log lines:"
grep -E "SkipOnCIOrEmulatorRule" "$OUT/logcat.log" | head -20 || true
echo "==> Failures (tail of gradle output):"
grep -E "FAILED|SKIPPED|tests completed" "$OUT/gradle-output.log" | head -40 || true

exit $GRADLE_EXIT
