#!/usr/bin/env python3
"""Autofix verification tests."""

import time
import subprocess
import os
import pytest
from helpers import (
    check_element_exists_in_ui, save_failed_screenshot,
    navigate_to_my_chats, send_voice_command, EMULATOR_SERIAL,
)

KEEP_PACKAGE = "com.google.android.keep"


def _keep_installed():
    result = subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'pm', 'list', 'packages', KEEP_PACKAGE],
        capture_output=True, text=True
    )
    return f"package:{KEEP_PACKAGE}" in result.stdout


def _foreground_package():
    result = subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'dumpsys', 'activity', 'activities'],
        capture_output=True, text=True
    )
    for line in result.stdout.splitlines():
        if 'topResumedActivity' in line or 'mResumedActivity' in line:
            return line
    return ""


def test_autofix_app_not_found(tester):
    """Verify fix for app_not_found.

    Google Keep's launcher label is now "Keep Notes", so asking Whiz to open
    "Google Keep" no longer fuzzy-matched any installed app. The fix adds an
    explicit "google keep" -> com.google.android.keep mapping.
    """
    if not _keep_installed():
        pytest.skip(f"{KEEP_PACKAGE} is not installed on {EMULATOR_SERIAL}")

    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'am', 'force-stop', KEEP_PACKAGE],
                   check=False)

    success, error = navigate_to_my_chats(tester, "autofix_app_not_found")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("open Google Keep")
    time.sleep(20)

    top = _foreground_package()
    tester.screenshot("/tmp/whiz_keep_open.png")
    result = KEEP_PACKAGE in top
    if not result:
        result = tester.validate_screenshot(
            "/tmp/whiz_keep_open.png",
            "The Google Keep / Keep Notes app is open (notes list, onboarding, or "
            "sign-in screen of Keep Notes)"
        )
    if not result:
        save_failed_screenshot(tester, "autofix_app_not_found", "keep_not_open")
    assert result, f"Google Keep was not launched (top activity: {top.strip()})"
