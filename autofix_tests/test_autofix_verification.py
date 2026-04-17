#!/usr/bin/env python3
"""Autofix verification test for whatsapp_chat_not_found.

Tests that the screen agent can successfully select a WhatsApp chat even when
WhatsApp is showing a community navigation page on launch (which can cause the
agent to land on the Communities tab instead of the Chats tab).
"""

import time


def test_autofix_whatsapp_chat_not_found(tester):
    """Verify fix for whatsapp_chat_not_found."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_chat_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Open new chat and let the UI settle before sending a voice command
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that exercises selectWhatsAppChat via the screen agent.
    # Ruth Grace Wong is the standard test contact on the emulator.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hi just checking in")
    time.sleep(60)  # wait for screen agent to complete

    # Check if WhatsApp chat was successfully opened
    tester.screenshot("/tmp/whiz_chat_not_found_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_chat_not_found_result.png",
            "The screen shows evidence that a WhatsApp chat was successfully opened or a message was "
            "drafted/sent. This could be: the WhatsApp chat screen with Ruth Grace Wong open, "
            "a draft message overlay in WhatsApp, or the Whiz app showing a success/confirmation "
            "message about sending a WhatsApp message. "
            "It should NOT show an error about failing to find the chat or contact."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_whatsapp_chat_not_found", "validate_screenshot_error")
        raise

    if not result:
        # Check Whiz app chat for success or error message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_chat_not_found_whiz_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_chat_not_found_whiz_result.png",
                "The Whiz chat shows a message from the assistant about sending or drafting a "
                "WhatsApp message to Ruth Grace Wong. It should NOT show an error about failing "
                "to find the chat, or 'Could not find chat' error."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_whatsapp_chat_not_found", "validate_whiz_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_chat_not_found", "validation_failed")
    assert result, "Screen agent did not successfully select the WhatsApp chat"
