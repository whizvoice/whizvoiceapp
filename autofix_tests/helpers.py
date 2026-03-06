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
DEVICE_SERIAL = os.environ.get('ANDROID_SERIAL', 'emulator-5556')
DEBUG_PACKAGE = "com.example.whiz.debug"
ACCESSIBILITY_SERVICE = f"{DEBUG_PACKAGE}/com.example.whiz.accessibility.WhizAccessibilityService"


def is_emulator(serial=None):
    """Check if the target device is an emulator."""
    serial = serial or DEVICE_SERIAL
    return serial.startswith('emulator-')


def get_device_model():
    """Get the connected Android device model."""
    result = subprocess.run(
        ['adb', '-s', DEVICE_SERIAL, 'shell', 'getprop', 'ro.product.model'],
        capture_output=True, text=True
    )
    return result.stdout.strip()


def enable_accessibility_service_adb():
    """Enable the WhizVoice accessibility service via adb settings (emulator only)."""
    subprocess.run(
        ['adb', '-s', DEVICE_SERIAL, 'shell', 'settings', 'put', 'secure',
         'enabled_accessibility_services', ACCESSIBILITY_SERVICE],
        check=True, capture_output=True
    )
    subprocess.run(
        ['adb', '-s', DEVICE_SERIAL, 'shell', 'settings', 'put', 'secure',
         'accessibility_enabled', '1'],
        check=True, capture_output=True
    )
    time.sleep(1)

    result = subprocess.run(
        ['adb', '-s', DEVICE_SERIAL, 'shell', 'settings', 'get', 'secure',
         'enabled_accessibility_services'],
        capture_output=True, text=True
    )
    if 'WhizAccessibilityService' in result.stdout:
        print("Accessibility service enabled via adb settings")
    else:
        print(f"WARNING: Accessibility service may not be enabled: {result.stdout.strip()}")


def enable_accessibility_service_ui(tester):
    """Enable accessibility service via UI taps (physical devices).

    Checks if the accessibility dialog is showing and taps through it
    using device-specific coordinates.
    """
    dialog_showing = check_element_exists_in_ui(
        tester, content_desc="Enable accessibility service title"
    )

    if not dialog_showing:
        return

    device_model = get_device_model()
    print(f"Device model: {device_model}")

    if device_model == "Pixel 8":
        # Click "Open Settings" button
        tester.tap(700, 1725)
        time.sleep(2)
        # Click to select WhizVoice DEBUG
        tester.tap(500, 900)
        time.sleep(1)
        # Click toggle to enable WhizVoice DEBUG
        tester.tap(925, 400)
        time.sleep(1)
        # Click Allow button
        tester.tap(500, 1650)
        time.sleep(1)
        # Press back twice to return to app
        tester.press_back()
        time.sleep(0.5)
        tester.press_back()
        time.sleep(1)
    elif device_model == "Pixel 7a":
        # Click "Open Settings" button
        tester.tap(700, 1725)
        time.sleep(2)
        # Click to select WhizVoice DEBUG
        tester.tap(500, 500)
        time.sleep(1)
        # Click toggle to enable WhizVoice DEBUG
        tester.tap(925, 400)
        time.sleep(1)
        # Click Allow button
        tester.tap(500, 1650)
        time.sleep(1)
        # Press back twice to return to app
        tester.press_back()
        time.sleep(0.5)
        tester.press_back()
        time.sleep(1)
    else:
        print(f"WARNING: Unknown device model: {device_model}")
        print(f"WARNING: Accessibility service needs to be enabled manually")
        print(f"WARNING: Or add tap coordinates for this device model")


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

    Args:
        tester: AndroidAccessibilityTester instance
        content_desc: Content description to search for (optional)
        text: Text content to search for (optional)
        wait_after_dump: Seconds to wait after UI dump (default 0.5)

    Returns:
        bool: True if element found, False otherwise
    """
    return tester.check_element_exists(
        content_desc=content_desc, text=text, wait_after_dump=wait_after_dump
    )


def save_failed_screenshot(tester, test_name, step_name):
    """Save a screenshot and UI dump when a test step fails.

    Thin wrapper around tester.save_debug_artifacts() for backwards compatibility.
    """
    output_dir = os.path.join(os.path.dirname(__file__), '..', 'autofix_test_output')
    tester.save_debug_artifacts(output_dir, test_name, step_name)


def login_if_needed(tester):
    """Log in to the app if we're on the login screen."""
    is_login_screen = check_element_exists_in_ui(
        tester, text="Sign in with Google", wait_after_dump=2.0
    )

    if is_login_screen:
        print("Login screen detected, proceeding with login...")
        tester.tap(540, 1450)
        time.sleep(2)
        tester.tap(540, 1300)
        time.sleep(3)

        reached_my_chats = check_element_exists_in_ui(
            tester, text="My Chats", wait_after_dump=2.0
        )
        on_accessibility_dialog = check_element_exists_in_ui(
            tester, text="Enable Accessibility Service", wait_after_dump=2.0
        )
        if not reached_my_chats and not on_accessibility_dialog:
            save_failed_screenshot(tester, "login_if_needed", "failed_after_login")
            assert False, "Failed to log in - expected My Chats page or accessibility dialog"

        if reached_my_chats:
            print("Successfully logged in and reached My Chats page")
        else:
            print("Successfully logged in (accessibility dialog shown)")
    else:
        print("Already logged in, skipping login flow")


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

        subprocess.run(
            ['adb', '-s', DEVICE_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_BACK'],
            check=False
        )
        time.sleep(2)

    save_failed_screenshot(tester, test_name, f"navigate_to_my_chats_failed_after_{max_attempts}_attempts")
    return False, f"Could not reach My Chats page after {max_attempts} back presses"
