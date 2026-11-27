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
    set +eo pipefail  # best-effort collection; nothing here may change the exit code
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
    exit "$exit_code"
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

# Give the emulator a GPS fix (San Francisco, Mission). Without one, Maps routes from the saved
# Home address and shows "Preview" instead of "Start", which the directions tool treats as failure.
adb shell settings put secure location_mode 3 || true
adb emu geo fix -122.4194 37.7749 || echo "    WARNING: geo fix failed"
sleep 2
adb emu geo fix -122.4194 37.7749 || true
echo "==> Location providers: $(adb shell settings get secure location_providers_allowed | tr -d '\r')"

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

# One Gradle invocation per class: a tool retry arriving during teardown can crash the app process
# (BubbleOverlayService created after the Hilt test component is gone), which kills the whole
# instrumentation run and silently drops every class after it.
GRADLE_EXIT=0
IFS=',' read -ra CLASSES <<< "$TEST_CLASSES"
for cls in "${CLASSES[@]}"; do
    short="${cls##*.}"
    echo "==> Running: $cls (allowEmulator=true)"
    adb shell am force-stop com.example.whiz.debug || true
    adb shell am force-stop com.example.whiz.debug.test || true
    # force-stop can drop the accessibility enrollment; re-assert it before each class
    adb shell settings put secure enabled_accessibility_services $PKG/com.example.whiz.accessibility.WhizAccessibilityService || true
    adb shell settings put secure accessibility_enabled 1 || true
    sleep 2
    set +e
    ./gradlew connectedDebugAndroidTest --console=plain --stacktrace --continue \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.class="$cls" \
        -Pandroid.testInstrumentationRunnerArguments.allowEmulator=true \
        > "$OUT/gradle-output-$short.log" 2>&1
    rc=$?
    set -e
    echo "    Gradle exit code: $rc"
    (( rc == 0 )) || GRADLE_EXIT=$rc
    mkdir -p "$OUT/androidTest-results-$short"
    cp -r app/build/outputs/androidTest-results/connected/. "$OUT/androidTest-results-$short/" 2>/dev/null || true
    echo "    Per-test outcomes:"
    find "$OUT/androidTest-results-$short" -name '*.xml' -print0 2>/dev/null \
        | xargs -0 grep -hoE '<testcase name="[^"]+" classname="[^"]+"|<skipped|<failure message="[^"]{0,200}' 2>/dev/null \
        | sed -E 's/<testcase name="([^"]+)" classname="[^"]+\.([^".]+)"/    TEST \2#\1/; s/<skipped.*/        -> SKIPPED/; s/<failure message="(.*)/        -> FAILED: \1/' || true
    if ! grep -q "<testcase" "$OUT/androidTest-results-$short"/*.xml 2>/dev/null; then
        echo "    (no JUnit testcase recorded; see gradle-output-$short.log)"
        grep -E "FAILED|Exception|Unable to|crash" "$OUT/gradle-output-$short.log" | head -5 || true
    fi
done

echo "==> Overall exit code: $GRADLE_EXIT"
echo "==> Skip-rule log lines:"
grep -E "SkipOnCIOrEmulatorRule" "$OUT/logcat.log" | head -20 || true

exit $GRADLE_EXIT
