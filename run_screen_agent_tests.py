#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_screenshot_testing')

import android_accessibility_tester
import subprocess
import os
import pytest


# Global variable to store logcat process
_logcat_process = None


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True)

    # Grant overlay permission using both pm grant and appops
    package_name = "com.example.whiz.debug"
    subprocess.run(['adb', 'shell', 'appops', 'set', package_name, 'SYSTEM_ALERT_WINDOW', 'allow'], check=False)
    subprocess.run(['adb', 'shell', 'pm', 'grant', package_name, 'android.permission.SYSTEM_ALERT_WINDOW'], check=False)


def start_logcat():
    """Start logcat and return the process."""
    global _logcat_process

    # Create output directory if it doesn't exist
    output_dir = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')
    os.makedirs(output_dir, exist_ok=True)

    # Clear logcat buffer
    subprocess.run(['adb', 'logcat', '-c'], check=False)

    # Start logcat and capture output
    logcat_file = os.path.join(output_dir, 'screen_agent_logcat.log')
    _logcat_process = subprocess.Popen(
        ['adb', 'logcat'],
        stdout=open(logcat_file, 'w'),
        stderr=subprocess.STDOUT
    )
    return _logcat_process


def stop_logcat():
    """Stop the logcat process."""
    global _logcat_process
    if _logcat_process:
        _logcat_process.terminate()
        _logcat_process.wait()
        _logcat_process = None


def save_failed_screenshot(screenshot_path, test_name, step_name):
    """Save a screenshot to the test output directory when validation fails."""
    import shutil

    output_dir = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')
    os.makedirs(output_dir, exist_ok=True)

    # Create a descriptive filename
    timestamp = subprocess.run(
        ['date', '+%Y%m%d_%H%M%S'],
        capture_output=True,
        text=True,
        check=True
    ).stdout.strip()

    dest_filename = f"{test_name}_{step_name}_{timestamp}.png"
    dest_path = os.path.join(output_dir, dest_filename)

    shutil.copy(screenshot_path, dest_path)
    print(f"📸 Saved failed screenshot to: {dest_path}")


def navigate_to_my_chats(tester):
    """Navigate to the My Chats page by pressing back until we reach it."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    max_attempts = 5

    for _ in range(max_attempts):
        tester.screenshot(screenshot_path)
        if tester.validate_screenshot(
            screenshot_path,
            "The screen shows a 'My Chats' or 'Chats List' page with a list of chats or an empty state. "
            "This is the main chat list view of WhizVoice."
        ):
            return True

        # Press back button and try again
        tester.press_back()
        time.sleep(1)

    return False


def enable_accessibility_service_if_needed(tester):
    """Enable accessibility service if the dialog is showing."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    tester.screenshot(screenshot_path)

    if tester.validate_screenshot(
        screenshot_path,
        "The screen shows an 'Enable accessibility service' dialog or prompt"
    ):
        # Click "Open Settings" button
        tester.tap(700, 1725)
        time.sleep(2)

        # Click to select WhizVoice DEBUG
        tester.tap(500, 1000)
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


def login_if_needed(tester):
    """Log in to the app if we're on the login screen."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    tester.screenshot(screenshot_path)

    if tester.validate_screenshot(
        screenshot_path,
        "The screen shows a login or sign-in screen with a 'Sign in with Google' button"
    ):
        # Click "Sign in with Google" button
        tester.tap(500, 1450)
        time.sleep(2)

        # Click to log in as REDACTED_TEST_EMAIL
        tester.tap(500, 1300)
        time.sleep(3)

        # Verify we reached My Chats page
        tester.screenshot(screenshot_path)
        assert tester.validate_screenshot(
            screenshot_path,
            "The screen shows a 'My Chats' or 'Chats List' page with a list of chats or an empty state. "
            "This is the main chat list view of WhizVoice after logging in."
        ), "Failed to log in and reach My Chats page"


@pytest.fixture(scope="session")
def app_installed():
    """Install the debug app once for all tests."""
    install_debug_app(force=True)
    start_logcat()
    yield
    stop_logcat()


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    import time

    tester = android_accessibility_tester.AndroidAccessibilityTester()
    tester.open_app("com.example.whiz.debug")

    # Give the app time to fully launch
    time.sleep(3)

    # Enable accessibility service if needed
    enable_accessibility_service_if_needed(tester)

    # Log in if needed
    login_if_needed(tester)

    yield tester
    # Add any per-test cleanup here if needed


# Example test - add your actual tests below
def test_whatsapp_draft_message(tester):
    """Test that we can draft and modify draft and send message in WhatsApp."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    assert navigate_to_my_chats(tester), "Failed to navigate to My Chats page"

    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Validate we are on the New Chat screen
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The screen shows a 'New Chat' page where users can start a new conversation"
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"

    # Click to focus on the text input
    tester.tap(500, 2250)
    time.sleep(1)

    # Input text
    tester.input_text("Hello, can you please send a message to +1(628)209-9005 that says hey whats up hows it going just tryna test whiz voice")
    time.sleep(1)

    # Click to send the message
    tester.tap(1000, 1300)

    # Wait for pixel at (500, 2250) to change color
    # tester.wait_for_pixel_change(500, 2250, timeout=15.0)
    time.sleep(15)

    # Validate WhatsApp is open with the draft message
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'hey whats up hows it going just tryna test whiz voice'. "
        "There is also a yellow notification bubble with the outline of a robot head "
        "and a microphone icon inside."
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_message_validation")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    time.sleep(30)

    # # Send a voice transcription to modify the message
    # subprocess.run([
    #     'adb', 'shell',
    #     'am', 'broadcast',
    #     '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
    #     '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
    #     '--es', 'text', '"Actually, can you make the message more polite?"',
    #     '--ez', 'fromVoice', 'true',
    #     '--ez', 'autoSend', 'true'
    # ], check=True)
    # time.sleep(10)

    # # Validate that the draft was updated
    # tester.screenshot(screenshot_path)
    # validation_result = tester.validate_screenshot(
    #     screenshot_path,
    #     "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
    #     "At the bottom of the screen, there is a yellow overlay or message input field containing text "
    #     "similar to 'just trying to test whiz voice' but may not be an exact match. "
    #     "The Yellow overlay should have some text in red strike out and some text in blue. "
    #     "There is also a yellow notification bubble with the outline of a robot head "
    #     "and a microphone icon inside."
    # )
    # if not validation_result:
    #     save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_updated_validation")
    # assert validation_result, "Failed to draft WhatsApp message correctly"

    # # Send a voice transcription to send the message
    # subprocess.run([
    #     'adb', 'shell',
    #     'am', 'broadcast',
    #     '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
    #     '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
    #     '--es', 'text', '"That looks good, go ahead and send the message."',
    #     '--ez', 'fromVoice', 'true',
    #     '--ez', 'autoSend', 'true'
    # ], check=True)
    # time.sleep(10)

    # # Validate that the message was sent
    # tester.screenshot(screenshot_path)
    # validation_result = tester.validate_screenshot(
    #     screenshot_path,
    #     "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
    #     "At the bottom of the screen, there is NO yellow overlay. "
    #     "The most recent rescue mission is something with text similar to: "
    #     "just trying to test WhizVoice. Though the message may not be exactly the same. "
    #     "There is also a yellow notification bubble with the outline of a robot head "
    #     "and a microphone icon inside."
    # )
    # if not validation_result:
    #     save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "message_sent_validation")
    # assert validation_result, "Failed to draft WhatsApp message correctly"





