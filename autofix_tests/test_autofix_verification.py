#!/usr/bin/env python3
"""Autofix verification test for debug_whatsapp_after_search.

Tests that after WhatsApp search finds a contact, the screen agent can
successfully navigate to the chat and draft a message. This validates the fix
that adds SEARCH_ACTIVE detection to detectWhatsAppScreen so the search results
screen is no longer treated as UNKNOWN (which caused navigation issues).
"""

import time


def test_autofix_debug_whatsapp_after_search(tester):
    """Verify WhatsApp chat selection succeeds after search finds a contact."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "debug_whatsapp_after_search")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers WhatsApp chat selection via search.
    # Ruth Grace Wong is a contact that appears in WhatsApp search results under
    # "Contacts" (not in the main chat list), exercising the post-search navigation path.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # wait for screen agent to complete (search + navigate + draft)

    # Check if WhatsApp is showing the drafted message or the chat
    tester.screenshot("/tmp/whiz_whatsapp_after_search_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_after_search_result.png",
            "The screen shows evidence that a WhatsApp message was successfully drafted or sent. "
            "This could be: the WhatsApp chat screen for Ruth Grace Wong with a message draft "
            "in the input field, the chat showing the sent message 'hello how are you', "
            "a colored draft message overlay in the WhatsApp chat input field, "
            "or the Whiz chat showing a success/confirmation message about drafting or sending "
            "a WhatsApp message to Ruth Grace Wong. "
            "It should NOT show an error, the WhatsApp search results screen, "
            "or the app stuck on any non-chat screen."
        )
    except Exception as e:
        save_failed_screenshot(tester, "debug_whatsapp_after_search", "validate_screenshot_error")
        raise

    if not result:
        # Check Whiz app for a success or confirmation message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_whatsapp_after_search_chat.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_whatsapp_after_search_chat.png",
                "The Whiz chat shows a message from the assistant confirming it drafted or sent "
                "a WhatsApp message to Ruth Grace Wong, or asking the user to confirm sending. "
                "It should NOT show an error about failing to find the chat or navigate WhatsApp."
            )
        except Exception as e:
            save_failed_screenshot(tester, "debug_whatsapp_after_search", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "debug_whatsapp_after_search", "validation_failed")
    assert result, "Screen agent failed to navigate to WhatsApp chat after search"
