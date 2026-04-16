#!/usr/bin/env python3
"""Autofix verification test for whatsapp_input_not_found.

Tests that the screen agent can successfully draft a WhatsApp message to a
contact, including when WhatsApp's accessibility tree is not immediately
available after launch (new WhatsApp versions with Meta AI take longer to
initialize, causing the root node check to return null and previously
skipping chat navigation entirely).
"""

import time


def test_autofix_whatsapp_input_not_found(tester):
    """Verify that draftWhatsAppMessage navigates to the correct chat even when
    the WhatsApp accessibility tree is slow to load after launch."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_input_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button to open a new chat session
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that exercises draftWhatsAppMessage via the screen agent.
    # This triggers the code path fixed: when WhatsApp's root node is null on first
    # check after launch, we now still call selectWhatsAppChat to navigate correctly.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # wait for screen agent to complete (search + navigate + draft)

    # The draft overlay is transient — check if we can see evidence of success.
    # First check if we're in WhatsApp with a draft overlay or sent message visible.
    tester.screenshot("/tmp/whiz_whatsapp_input_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_input_result.png",
            "The screen shows evidence that a WhatsApp message was successfully sent or drafted. "
            "This could be: the WhatsApp chat screen showing the sent message 'hello how are you', "
            "a colored draft message overlay in the WhatsApp chat input field containing the message, "
            "the WhatsApp chat open with Ruth Grace Wong showing a message input field at the bottom, "
            "or the Whiz chat showing a success message or asking for confirmation to send the message. "
            "It should NOT show an error message, a failure to find the message input field, "
            "or the app stuck on the WhatsApp main chat list without entering a conversation."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_whatsapp_input_not_found", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check if the chat shows a success message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_whatsapp_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_whatsapp_chat_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. The message may ask for confirmation "
                "to send, say the draft was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to find the message input field "
                "or failing to open the WhatsApp chat."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_whatsapp_input_not_found", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_input_not_found", "validation_failed")
    assert result, "Screen agent did not successfully send or draft a WhatsApp message"
