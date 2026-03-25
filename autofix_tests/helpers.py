#!/usr/bin/env python3
"""Shared helper functions for autofix verification tests.

Works on both emulator and physical devices. On emulators, accessibility
is enabled via `adb settings`; on physical devices, it uses the UI tap
approach (same as run_screen_agent_tests.py).
"""

import subprocess
import os
import time
import xml.etree.ElementTree as ET
import re


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


def find_element_center(tester, content_desc=None, text=None):
    """Find an element in the UI hierarchy and return its center coordinates.

    Args:
        tester: AndroidAccessibilityTester instance
        content_desc: Content description to match (exact)
        text: Text content to match (exact)

    Returns:
        (x, y) tuple of center coordinates, or None if not found
    """
    try:
        device_path = "/sdcard/ui_hierarchy_find.xml"
        local_path = "/tmp/ui_hierarchy_find.xml"
        adb_prefix = ['adb', '-s', EMULATOR_SERIAL]

        subprocess.run(adb_prefix + ['shell', f'uiautomator dump {device_path}'],
                       capture_output=True, check=True)
        time.sleep(0.5)
        subprocess.run(adb_prefix + ['pull', device_path, local_path],
                       capture_output=True, check=True)

        with open(local_path, 'r') as f:
            xml_content = f.read()
        root = ET.fromstring(xml_content)

        for node in root.iter():
            if content_desc and node.get('content-desc') == content_desc:
                pass
            elif text and node.get('text') == text:
                pass
            else:
                continue

            bounds = node.get('bounds', '')
            match = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
            if match:
                x1, y1, x2, y2 = [int(v) for v in match.groups()]
                return ((x1 + x2) // 2, (y1 + y2) // 2)

        return None
    except Exception as e:
        print(f"Error finding element: {e}")
        return None


def save_failed_screenshot(tester, test_name, step_name):
    """Save a screenshot and UI dump when a test step fails.

    Thin wrapper around tester.save_debug_artifacts() for backwards compatibility.
    """
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    tester.save_debug_artifacts(OUTPUT_DIR, test_name, step_name)


def login_if_needed(tester):
    """Log in to the app if we're on the login screen."""
    is_login_screen = check_element_exists_in_ui(
        tester, text="Sign in with Google", wait_after_dump=2.0
    )

    if not is_login_screen:
        print("Already logged in, skipping login flow")
        return

    print("Login screen detected, proceeding with login...")
    # Wake screen in case it went dark before we could tap
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=False)
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'svc', 'power', 'stayon', 'true'], check=False)
    time.sleep(1)

    # Phase 1: Find and tap "Sign in with Google", then wait for account chooser.
    # Retry the tap if the account chooser doesn't appear (ANR dialogs can steal focus).
    max_sign_in_attempts = 3
    chooser_timeout = 15
    poll_interval = 3
    account_selected = False

    for attempt in range(1, max_sign_in_attempts + 1):
        # Dismiss any ANR dialog before attempting to tap sign-in
        has_anr = check_element_exists_in_ui(
            tester, text="System UI isn't responding", wait_after_dump=0.5
        )
        if has_anr:
            print("ANR dialog detected before sign-in tap, dismissing...")
            wait_btn = find_element_center(tester, text="Wait")
            if wait_btn:
                tester.tap(*wait_btn)
            else:
                tester.tap(540, 1395)
            time.sleep(2)

        # Find and tap "Sign in with Google" by its actual position in the UI tree
        sign_in_coords = find_element_center(tester, text="Sign in with Google")
        if sign_in_coords:
            print(f"Tapping 'Sign in with Google' at {sign_in_coords} (attempt {attempt})")
            tester.tap(*sign_in_coords)
        else:
            print(f"Could not find 'Sign in with Google' in UI tree, using fallback tap (attempt {attempt})")
            tester.tap(540, 1488)

        # Poll for account chooser dialog
        elapsed = 0
        while elapsed < chooser_timeout:
            time.sleep(poll_interval)
            elapsed += poll_interval

            # Check for account chooser
            has_account_chooser = check_element_exists_in_ui(
                tester, text="Choose an account", wait_after_dump=1.0
            )
            if has_account_chooser:
                print("Account chooser dialog detected, selecting test account...")
                # Find the first account entry (email or name) to tap
                account_coords = find_element_center(tester, text="Whiz Voice Test")
                if account_coords:
                    print(f"Tapping account at {account_coords}")
                    tester.tap(*account_coords)
                else:
                    # Fallback: tap approximate center of first account row
                    print("Account text not found, using fallback tap")
                    tester.tap(540, 1271)
                time.sleep(2)
                account_selected = True
                break

            # Check if login already completed (no chooser needed)
            reached_my_chats = check_element_exists_in_ui(
                tester, text="My Chats", wait_after_dump=0.5
            )
            if reached_my_chats:
                print("Login completed without account chooser")
                account_selected = True
                break
            on_accessibility_dialog = check_element_exists_in_ui(
                tester, text="Enable Accessibility Service", wait_after_dump=0.5
            )
            if on_accessibility_dialog:
                print("Login completed (accessibility dialog shown)")
                account_selected = True
                break

            # Dismiss ANR if it appeared during the wait
            has_anr = check_element_exists_in_ui(
                tester, text="System UI isn't responding", wait_after_dump=0.5
            )
            if has_anr:
                print("ANR dialog detected while waiting for chooser, dismissing...")
                wait_btn = find_element_center(tester, text="Wait")
                if wait_btn:
                    tester.tap(*wait_btn)
                else:
                    tester.tap(540, 1395)
                time.sleep(2)
                # Break out to retry the sign-in tap
                break

            print(f"Waiting for account chooser... ({elapsed}s/{chooser_timeout}s)")

        if account_selected:
            break
        print(f"Account chooser did not appear after attempt {attempt}, retrying sign-in tap...")

    # Phase 2: Poll for login to complete (up to 30s)
    login_timeout = 30
    elapsed = 0
    reached_my_chats = False
    on_accessibility_dialog = False
    while elapsed < login_timeout:
        time.sleep(poll_interval)
        elapsed += poll_interval
        reached_my_chats = check_element_exists_in_ui(
            tester, text="My Chats", wait_after_dump=1.0
        )
        if reached_my_chats:
            break
        on_accessibility_dialog = check_element_exists_in_ui(
            tester, text="Enable Accessibility Service", wait_after_dump=1.0
        )
        if on_accessibility_dialog:
            break
        # Dismiss ANR dialogs
        has_anr = check_element_exists_in_ui(
            tester, text="System UI isn't responding", wait_after_dump=0.5
        )
        if has_anr:
            print("ANR dialog detected, tapping Wait to dismiss...")
            wait_btn = find_element_center(tester, text="Wait")
            if wait_btn:
                tester.tap(*wait_btn)
            else:
                tester.tap(540, 1395)
            time.sleep(2)
            continue
        print(f"Waiting for login to complete... ({elapsed}s/{login_timeout}s)")

    if not reached_my_chats and not on_accessibility_dialog:
        save_failed_screenshot(tester, "login_if_needed", "failed_after_login")
        assert False, "Failed to log in - expected My Chats page or accessibility dialog"

    if reached_my_chats:
        print("Successfully logged in and reached My Chats page")
    else:
        print("Successfully logged in (accessibility dialog shown)")


def navigate_to_my_chats(tester, test_name="unknown"):
    """Navigate to the My Chats page by pressing back until we reach it.

    Returns:
        tuple: (success: bool, error_message: str)
    """
    max_attempts = 5
    for attempt in range(max_attempts):
        has_my_chats_title = check_element_exists_in_ui(
            tester, content_desc="My Chats Title", wait_after_dump=2.0
        )
        has_new_chat = check_element_exists_in_ui(
            tester, content_desc="New Chat", wait_after_dump=0
        )

        if has_my_chats_title and has_new_chat:
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
