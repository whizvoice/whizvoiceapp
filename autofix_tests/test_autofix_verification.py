#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_sms_draft_error(tester):
    """Verify fix for sms_draft_error.

    The screen agent crashed with a NullPointerException in smsFindBestInput
    while updating an existing SMS draft in newer Google Messages: the
    generic EditText fallback called String.contains() on a null
    viewIdResourceName (the Compose-based message input has no resource ID).
    The fix null-guards the resource ID so the input is accepted.

    This test drafts an SMS, then asks Whiz to change it, which exercises
    the update-draft path (previousText set) that previously crashed.
    """
    success, error = navigate_to_my_chats(tester, "autofix_sms_draft_error")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    # Initial draft (selectSMSChat + draftSMSMessage).
    send_voice_command("Text Ruth Grace Wong saying Hey, are we still on for lunch")
    time.sleep(30)

    # Update the draft -> draftSMSMessage with previousText, the failing path.
    send_voice_command("Change the message to say Hey, are we still on for dinner tonight")
    time.sleep(30)

    tester.screenshot("/tmp/whiz_sms_draft_update.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_sms_draft_update.png",
        "Google Messages is open in a conversation with Ruth Grace Wong and a "
        "draft message mentioning 'dinner tonight' is shown, either in the "
        "message input box or in a draft preview overlay."
    )

    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_sms_draft_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_sms_draft_chat_result.png",
            "The Whiz chat shows the assistant saying it updated the draft "
            "message (e.g. 'Updated the draft'). It should NOT show an error "
            "about drafting the message (e.g. 'Error drafting message: null')."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_sms_draft_error", "validation_failed")

    assert result, (
        "SMS draft was not updated — sms_draft_error (NPE in smsFindBestInput) "
        "may still be triggering"
    )
