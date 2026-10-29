#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_sms_send_error(tester):
    """Verify fix for sms_send_error.

    The screen agent crashed with a NullPointerException in smsFindBestInput
    when sending an SMS via the updated Google Messages app: the generic
    EditText fallback called String.contains() on a null viewIdResourceName
    (newer Compose-based message inputs have no resource ID). The fix null-
    guards the resource ID so the compose input is accepted instead of
    crashing the whole send.

    This test drives the actual screen agent: it asks Whiz to text a contact
    via SMS, then confirms sending, and validates that the message was
    delivered (i.e. no "Error sending message: null" failure).
    """
    success, error = navigate_to_my_chats(tester, "autofix_sms_send_error")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before sending a voice command.
    tester.tap(950, 2225)
    time.sleep(2)

    # Ask Whiz to draft an SMS to the test contact. This routes through the
    # screen agent's SMS draft pipeline (selectSMSChat + draftSMSMessage).
    send_voice_command(
        "Send a text message to Ruth Grace Wong saying No rush at all. Godspeed"
    )
    time.sleep(30)  # wait for draft to be shown in Messages

    # Confirm the send. This triggers agent_sms_send_message -> sendSMSMessage,
    # which is exactly the path that previously NPE'd in smsFindBestInput.
    send_voice_command("Yes send it")
    time.sleep(20)  # wait for screen agent to send

    # Validate the message was actually sent in Google Messages.
    tester.screenshot("/tmp/whiz_sms_sent.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_sms_sent.png",
        "The Google Messages conversation with Ruth Grace Wong shows the sent "
        "message bubble containing 'No rush at all' / 'Godspeed' in the "
        "conversation thread (a sent SMS, not just a draft in the input box)."
    )

    if not result:
        # Fall back to checking the Whiz chat: the assistant should NOT report
        # an SMS send error like "Error sending message: null".
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_sms_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_sms_chat_result.png",
            "The Whiz chat shows the assistant confirming the text message was "
            "sent to Ruth Grace Wong. It should NOT show an error about failing "
            "to send the message (e.g. 'Error sending message: null')."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_sms_send_error", "validation_failed")

    assert result, (
        "SMS message was not sent — sms_send_error (NPE in smsFindBestInput) "
        "may still be triggering"
    )
