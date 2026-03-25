#!/usr/bin/env python3
"""Shared helper functions for autofix verification tests.

Works on both emulator and physical devices. On emulators, accessibility
is enabled via `adb settings`; on physical devices, it uses the UI tap
approach (same as run_screen_agent_tests.py).
"""

import subprocess
import os
import time


ANDROID_HOME = os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, 'platform-tools')
EMULATOR_SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5554')
DEBUG_PACKAGE = "com.example.whiz.debug"
ACCESSIBILITY_SERVICE = f"{DEBUG_PACKAGE}/com.example.whiz.accessibility.WhizAccessibilityService"
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'autofix_test_output')


REQUIRED_PACKAGES = [
    ("com.whatsapp", "WhatsApp"),
]


def is_emulator(serial=None):
    """Check if the target device is an emulator."""
    serial = serial or EMULATOR_SERIAL
    return serial.startswith('emulator-')


def verify_required_apps():
    """Verify that required apps are installed on the device.

    This catches the case where an emulator snapshot failed to load
    (cold boot fallback) and the device is missing apps that should
    have been in the snapshot.
    """
    result = subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'pm', 'list', 'packages'],
        capture_output=True, text=True
    )
    installed = result.stdout

    missing = []
    for package, name in REQUIRED_PACKAGES:
        if package in installed:
            print(f"  [OK] {name} ({package}) is installed")
        else:
            print(f"  [FAIL] {name} ({package}) is NOT installed")
            missing.append(name)

    if missing:
        raise RuntimeError(
            f"Required apps not installed: {', '.join(missing)}. "
            f"The emulator snapshot may not have loaded correctly (cold boot fallback). "
            f"Recreate the snapshot with all required apps installed and configured."
        )


def get_device_model():
    """Get the connected Android device model."""
    result = subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'getprop', 'ro.product.model'],
        capture_output=True, text=True
    )
    return result.stdout.strip()


def enable_accessibility_service_adb():
    """Enable the WhizVoice accessibility service via adb settings (emulator only)."""
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'settings', 'put', 'secure',
         'enabled_accessibility_services', ACCESSIBILITY_SERVICE],
        check=True, capture_output=True
    )
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'settings', 'put', 'secure',
         'accessibility_enabled', '1'],
        check=True, capture_output=True
    )
    time.sleep(1)

    result = subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'settings', 'get', 'secure',
         'enabled_accessibility_services'],
        capture_output=True, text=True
    )
    if 'WhizAccessibilityService' in result.stdout:
        print("Accessibility service enabled via adb settings")
    else:
        print(f"WARNING: Accessibility service may not be enabled: {result.stdout.strip()}")


def enable_accessibility_service_ui(tester):
    """Enable accessibility service via UI taps (physical devices)."""
    dialog_showing = check_element_exists_in_ui(
        tester, content_desc="Enable accessibility service title"
    )

    if not dialog_showing:
        return

    device_model = get_device_model()
    print(f"Device model: {device_model}")

    if device_model == "Pixel 8":
        tester.tap(700, 1725)
        time.sleep(2)
        tester.tap(500, 900)
        time.sleep(1)
        tester.tap(925, 400)
        time.sleep(1)
        tester.tap(500, 1650)
        time.sleep(1)
        tester.press_back()
        time.sleep(0.5)
        tester.press_back()
        time.sleep(1)
    elif device_model == "Pixel 7a":
        tester.tap(700, 1725)
        time.sleep(2)
        tester.tap(500, 500)
        time.sleep(1)
        tester.tap(925, 400)
        time.sleep(1)
        tester.tap(500, 1650)
        time.sleep(1)
        tester.press_back()
        time.sleep(0.5)
        tester.press_back()
        time.sleep(1)
    else:
        print(f"WARNING: Unknown device model: {device_model}")
        print(f"WARNING: Accessibility service needs to be enabled manually")


def enable_accessibility_service(tester=None):
    """Enable accessibility service using the appropriate method.

    On emulators: uses adb settings (reliable, no UI interaction needed).
    On physical devices: uses UI tap approach (requires tester instance).
    """
    if is_emulator():
        enable_accessibility_service_adb()
    else:
        if tester is None:
            print("WARNING: Physical device detected but no tester provided for UI-based "
                  "accessibility enable. Trying adb settings as fallback.")
            enable_accessibility_service_adb()
        else:
            enable_accessibility_service_ui(tester)


def check_element_exists_in_ui(tester, content_desc=None, text=None, wait_after_dump=0.5):
    """Check if an element exists in the UI hierarchy.

    Thin wrapper around tester.check_element_exists() for backwards compatibility.
    """
    return tester.check_element_exists(
        content_desc=content_desc, text=text, wait_after_dump=wait_after_dump
    )


def check_screen_shows(tester, description):
    """Check if the current screen matches a description using screenshot + Claude vision.

    Unlike check_element_exists_in_ui, this does NOT use uiautomator dump,
    so it won't disrupt the accessibility service.

    Args:
        tester: AndroidAccessibilityTester instance
        description: What should be visible on screen

    Returns:
        True if the screenshot matches the description, False otherwise
    """
    try:
        screenshot_path = "/tmp/screen_check.png"
        tester.screenshot(screenshot_path)
        result = tester.validate_screenshot(screenshot_path, description)
        return result.result
    except Exception as e:
        print(f"Error checking screen: {e}")
        return False


def save_failed_screenshot(tester, test_name, step_name):
    """Save a screenshot and UI dump when a test step fails.

    Thin wrapper around tester.save_debug_artifacts() for backwards compatibility.
    """
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    tester.save_debug_artifacts(OUTPUT_DIR, test_name, step_name)


def login_if_needed(tester):
    """Log in to the app if we're on the login screen.

    Uses screenshot + Claude vision to check screen state (no uiautomator dump)
    so the accessibility service permission is not disrupted.
    """
    is_login_screen = check_screen_shows(
        tester,
        "The WhizVoice app login/welcome screen with a 'Sign in with Google' button"
    )

    if not is_login_screen:
        print("Already logged in, skipping login flow")
        return

    print("Login screen detected, proceeding with login...")
    # Wake screen in case it went dark before we could tap
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=False)
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'svc', 'power', 'stayon', 'true'], check=False)
    time.sleep(1)

    # Phase 1: Tap "Sign in with Google" and wait for account chooser.
    # Retry if the account chooser doesn't appear (ANR dialogs can steal focus).
    max_sign_in_attempts = 3
    poll_interval = 3
    login_done = False

    for attempt in range(1, max_sign_in_attempts + 1):
        # Dismiss any ANR dialog before tapping sign-in
        if check_screen_shows(tester, "An ANR dialog saying 'System UI isn't responding' with a 'Wait' button"):
            print("ANR dialog detected before sign-in tap, dismissing...")
            tester.tap(540, 1395)  # "Wait" button
            time.sleep(2)

        # Tap "Sign in with Google" button (center of our login screen button)
        print(f"Tapping 'Sign in with Google' (attempt {attempt})")
        tester.tap(540, 1488)
        time.sleep(3)

        # Check what appeared: account chooser, or did login complete directly?
        chooser_timeout = 15
        elapsed = 0
        while elapsed < chooser_timeout:
            if check_screen_shows(
                tester,
                "A Google account chooser dialog showing 'Choose an account' with one or more account options"
            ):
                print("Account chooser detected, selecting test account...")
                # Tap the first account in the chooser list
                tester.tap(540, 1271)
                time.sleep(3)
                login_done = True
                break

            if check_screen_shows(
                tester,
                "The WhizVoice app showing 'My Chats' page with chat list, "
                "OR an 'Enable Accessibility Service' dialog"
            ):
                print("Login completed directly")
                login_done = True
                break

            if check_screen_shows(
                tester,
                "An ANR dialog saying 'System UI isn't responding' with a 'Wait' button"
            ):
                print("ANR dialog detected, dismissing and retrying...")
                tester.tap(540, 1395)  # "Wait" button
                time.sleep(2)
                break  # Retry sign-in tap

            time.sleep(poll_interval)
            elapsed += poll_interval
            print(f"Waiting for account chooser... ({elapsed}s/{chooser_timeout}s)")

        if login_done:
            break
        print(f"Account chooser did not appear after attempt {attempt}, retrying...")

    # Phase 2: Wait for login to finish (up to 30s)
    login_timeout = 30
    elapsed = 0
    success = False
    while elapsed < login_timeout:
        time.sleep(poll_interval)
        elapsed += poll_interval

        if check_screen_shows(
            tester,
            "The WhizVoice app showing 'My Chats' page with chat list, "
            "OR an 'Enable Accessibility Service' dialog"
        ):
            success = True
            break

        if check_screen_shows(
            tester,
            "An ANR dialog saying 'System UI isn't responding' with a 'Wait' button"
        ):
            print("ANR dialog detected, dismissing...")
            tester.tap(540, 1395)
            time.sleep(2)
            continue

        print(f"Waiting for login to complete... ({elapsed}s/{login_timeout}s)")

    if not success:
        save_failed_screenshot(tester, "login_if_needed", "failed_after_login")
        assert False, "Failed to log in - expected My Chats page or accessibility dialog"

    print("Successfully logged in")


def navigate_to_my_chats(tester, test_name="unknown"):
    """Navigate to the My Chats page by pressing back until we reach it.

    Uses screenshot + Claude vision to check screen state (no uiautomator dump).

    Returns:
        tuple: (success: bool, error_message: str)
    """
    max_attempts = 5
    for attempt in range(max_attempts):
        if check_screen_shows(
            tester,
            "The WhizVoice app 'My Chats' page showing a list of chats "
            "with a 'New Chat' button visible"
        ):
            print("Successfully navigated to My Chats page")
            return True, ""

        print(f"My Chats not found, pressing back (attempt {attempt + 1}/{max_attempts})")
        save_failed_screenshot(tester, test_name, f"navigate_to_my_chats_attempt_{attempt + 1}")

        tester.press_back()
        time.sleep(2)

    save_failed_screenshot(tester, test_name, f"navigate_to_my_chats_failed_after_{max_attempts}_attempts")
    return False, f"Could not reach My Chats page after {max_attempts} back presses"


def send_voice_command(text):
    """Send a test voice transcription to the Whiz app.

    Args:
        text: The voice command text to send
    """
    subprocess.run(
        [
            "adb", "shell",
            "am", "broadcast",
            "-a", "com.example.whiz.TEST_TRANSCRIPTION",
            "-n", f"{DEBUG_PACKAGE}/com.example.whiz.test.TestTranscriptionReceiver",
            "--es", "text", f'"{text}"',
            "--ez", "fromVoice", "true",
            "--ez", "autoSend", "true",
        ],
        check=True,
    )
