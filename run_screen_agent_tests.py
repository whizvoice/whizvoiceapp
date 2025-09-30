#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_screenshot_testing')

import android_accessibility_tester
import subprocess
import os
import pytest


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True)


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
    yield


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    import time

    tester = android_accessibility_tester.AndroidAccessibilityTester()
    tester.open_app("com.example.whiz.debug")

    # Give the app time to fully launch
    time.sleep(3)

    # Log in if needed
    login_if_needed(tester)

    yield tester
    # Add any per-test cleanup here if needed


# Example test - add your actual tests below
def test_whatsapp_draft_message(tester):
    """Test that we can draft and modify draft and send message in WhatsApp."""
    # At this point, we're already logged in and on My Chats page (handled by fixture)

    # TODO: Add test steps for WhatsApp draft message functionality
    pass
