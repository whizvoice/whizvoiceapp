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

# Target apps on the snapshot have never been opened, so their first launch raises runtime
# permission prompts (YouTube Music asked for notifications) and first-run screens that the
# screen agent then has to fight through. Pre-grant what we can and open Messages once to get
# its express sign-in prompt out of the way.
for app in com.google.android.apps.youtube.music com.google.android.apps.messaging \
           com.google.android.apps.maps com.whatsapp; do
    adb shell pm grant $app android.permission.POST_NOTIFICATIONS 2>/dev/null || true
done
# Maps routes from the saved Home address unless it can read the GPS fix; Messages can't find a
# contact by name without contacts access. Neither app has been granted anything on the snapshot.
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION; do
    adb shell pm grant com.google.android.apps.maps android.permission.$p 2>/dev/null || true
done
for p in READ_CONTACTS READ_SMS SEND_SMS RECEIVE_SMS READ_PHONE_STATE; do
    adb shell pm grant com.google.android.apps.messaging android.permission.$p 2>/dev/null || true
done
adb shell cmd role add-role-holder android.app.role.SMS com.google.android.apps.messaging 2>/dev/null || true
echo "==> Default SMS app: $(adb shell cmd role get-role-holders android.app.role.SMS 2>/dev/null | tr -d '\r')"
adb shell monkey -p com.google.android.apps.messaging -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 10
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am force-stop com.google.android.apps.messaging || true
adb shell input keyevent KEYCODE_HOME
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
    if ! find "$OUT/androidTest-results-$short" -name '*.xml' -exec grep -q "<testcase" {} + 2>/dev/null; then
        echo "    (no JUnit testcase recorded; see gradle-output-$short.log)"
        grep -E "FAILED|Exception|Unable to|crash" "$OUT/gradle-output-$short.log" | head -5 || true
    fi
done

echo "==> Overall exit code: $GRADLE_EXIT"
echo "==> Skip-rule log lines:"
grep -E "SkipOnCIOrEmulatorRule" "$OUT/logcat.log" | head -20 || true

exit $GRADLE_EXIT
