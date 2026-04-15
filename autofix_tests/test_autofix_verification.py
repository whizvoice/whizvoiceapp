#!/usr/bin/env python3
"""Autofix verification test for whatsapp_click_opened_wrong_screen.

Tests that the screen agent can successfully navigate to a WhatsApp chat
even when the initial 5-second wait for INSIDE_CHAT detection expires before
the chat finishes loading. The fix adds a re-check for INSIDE_CHAT after the
wait expires, so the agent returns success instead of pressing back.
"""

import time


def test_autofix_whatsapp_click_opened_wrong_screen(tester):
    """Verify fix for whatsapp_click_opened_wrong_screen."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_click_opened_wrong_screen")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button to open a fresh chat session
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers WhatsApp chat navigation via screen agent
    # This exercises the selectWhatsAppChat path that was failing
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # wait for screen agent to complete

    # Check the current screen for success indicators
    tester.screenshot("/tmp/whiz_screen.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_screen.png",
            "The screen shows evidence that a WhatsApp message was successfully sent or drafted. "
            "This could be: the WhatsApp chat screen open with Ruth Grace Wong showing a sent message "
            "or a message draft in the input field, a colored draft overlay with the message text, "
            "or the Whiz app showing a success/confirmation message about the WhatsApp message. "
            "It should NOT show an error about failing to open the WhatsApp chat, "
            "or the app stuck looping back to the chat list."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_whatsapp_click_opened_wrong_screen", "validate_screenshot_error")
        raise

    if not result:
        # Fall back: check the Whiz chat for a success or confirmation message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_chat_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. It may ask for confirmation to send, "
                "say the draft was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to open or navigate to the WhatsApp chat."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_whatsapp_click_opened_wrong_screen", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_click_opened_wrong_screen", "validation_failed")
    assert result, "Screen agent did not successfully open the WhatsApp chat and draft a message"
