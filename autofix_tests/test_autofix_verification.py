#!/usr/bin/env python3
"""Autofix verification test for whatsapp_contact_picker_no_search.

Tests that the screen agent can successfully navigate WhatsApp to send a
message even when the contact picker ("Select contact" screen) appears with
contacts still loading. The fix waits for contacts to finish loading before
looking for the search input.
"""

import time


def test_autofix_whatsapp_contact_picker_no_search(tester):
    """Verify fix for whatsapp_contact_picker_no_search."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_contact_picker_no_search")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button to open a new conversation
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers WhatsApp contact search via screen agent.
    # Ruth Grace Wong exists on the test emulator.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # wait for screen agent to complete (contact picker may need to load)

    # The draft overlay is transient — check if we're still in WhatsApp with overlay visible,
    # or if the message was already sent/drafted.
    tester.screenshot("/tmp/whiz_contact_picker_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_contact_picker_result.png",
            "The screen shows evidence that a WhatsApp message was successfully sent or drafted. "
            "This could be: the WhatsApp chat screen showing the sent message 'hello how are you', "
            "a colored draft message overlay in the WhatsApp chat input field containing the message, "
            "the WhatsApp chat open with Ruth Grace Wong showing a message input field at the bottom, "
            "or the Whiz chat showing a success message or asking for confirmation to send the message. "
            "It should NOT show an error message, a failure to find the message input field, "
            "or the app stuck on a 'Select contact' screen with a loading spinner."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_whatsapp_contact_picker_no_search", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check if the chat shows a success message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_contact_picker_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_contact_picker_chat_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. The message may ask for confirmation "
                "to send, say the draft was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to find the message input field, "
                "failing to open the WhatsApp chat, or the contact picker not loading."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_whatsapp_contact_picker_no_search", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_contact_picker_no_search", "validation_failed")
    assert result, "Screen agent did not successfully send or draft a WhatsApp message to Ruth Grace Wong"
