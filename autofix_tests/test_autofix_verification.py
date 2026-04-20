#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_sms_send_button(tester):
    """Verify SMS send works, including Send MMS button variant.

    Google Messages uses different send button labels depending on context:
    "Send SMS", "Send MMS", "Send message", "Send encrypted message".
    This test verifies the screen agent can find and click the send button
    regardless of which variant Messages shows.
    """
    success, error = navigate_to_my_chats(tester, "autofix_sms_send_button")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("send a text to Ruth Grace Wong saying hey what time are you getting here")

    result = tester.wait_for_logcat(
        tag="ScreenAgentTools",
        message="Found send button by content description",
        timeout=45
    )
    assert result, (
        "SMS send failed — screen agent could not find the send button. "
        "Expected 'Found send button by content description' in logcat."
    )
