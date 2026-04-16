#!/usr/bin/env python3
"""Autofix verification test for sms_chat_not_found.

Tests that the screen agent can successfully open an SMS conversation,
including the Compose-based Google Messages UI where the old search
results resource IDs (zero_state_search_chat_results, etc.) no longer exist.
"""

import time


def test_autofix_sms_chat_not_found(tester):
    """Verify fix for sms_chat_not_found."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_sms_chat_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button and let UI settle before sending voice command
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers the SMS selectSMSChat screen agent path.
    # "Ruth Grace Wong" is the test contact that exists on the emulator.
    send_voice_command("send an SMS to Ruth Grace Wong saying hello")
    time.sleep(45)  # wait for screen agent to navigate Messages and open the conversation

    # Check if we ended up in the SMS conversation or got a success response in Whiz
    tester.screenshot("/tmp/whiz_sms_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_sms_result.png",
            "The screen shows evidence that the SMS conversation was successfully opened. "
            "This could be: the Google Messages app showing a conversation with Ruth Grace Wong "
            "with an SMS compose field at the bottom, a draft message 'hello' in the input field, "
            "or the Whiz chat showing a success message about drafting/sending the SMS. "
            "It should NOT show an error saying the contact or conversation could not be found."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_sms_chat_not_found", "validate_screenshot_error")
        raise

    if not result:
        # Check back in the Whiz app for a success or confirmation message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_sms_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_sms_chat_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "an SMS to Ruth Grace Wong. It may ask for confirmation to send, say a draft "
                "was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to find the SMS conversation or contact."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_sms_chat_not_found", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_sms_chat_not_found", "validation_failed")
    assert result, "Screen agent did not successfully open the SMS conversation"
