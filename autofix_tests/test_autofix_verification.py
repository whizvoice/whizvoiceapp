#!/usr/bin/env python3
"""Autofix verification test for whatsapp_input_not_found.

Tests that the screen agent can successfully send a WhatsApp message,
including handling the case where WhatsApp navigates to a contact profile
page (with a message_btn) instead of directly to the chat input field.
"""

import time


def test_whatsapp_input_not_found(tester):
    """Verify WhatsApp messaging finds the input field and drafts a message."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "whatsapp_input_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers WhatsApp messaging via screen agent
    # Use a contact name that exists in WhatsApp on the test device
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # wait for screen agent to complete (needs extra time for FAB fallback on new contacts)

    # The draft overlay is transient - it may have already been shown and dismissed.
    # First check if we're still in WhatsApp with the overlay visible
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
            "or the app stuck on a contact profile page."
        )
    except Exception as e:
        save_failed_screenshot(tester, "whatsapp_input_not_found", "validate_screenshot_error")
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
            save_failed_screenshot(tester, "whatsapp_input_not_found", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "whatsapp_input_not_found", "validation_failed")
    assert result, "Screen agent did not successfully send or draft a WhatsApp message"


def test_autofix_whatsapp_unknown_screen(tester):
    """Verify fix for whatsapp_unknown_screen.

    When WhatsApp shows a 'Turn on notifications' bottom-sheet dialog after
    launch, the screen agent previously got stuck because the dialog window
    was not recognized and could not be dismissed.  The fix makes
    dismissWhatsAppNotificationDialog press BACK as a fallback when the
    specific cancel/close button cannot be found, so the screen agent
    recovers and successfully drafts the message.
    """
    import time
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_unknown_screen")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers the WhatsApp screen agent
    # (same action that triggered the original whatsapp_unknown_screen failure)
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # allow screen agent to navigate WhatsApp (including any notification dialog)

    # Check if we're in WhatsApp with the message drafted/sent
    tester.screenshot("/tmp/whiz_whatsapp_unknown_screen_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_unknown_screen_result.png",
            "The screen shows evidence that a WhatsApp message was successfully sent or drafted. "
            "This could be: the WhatsApp chat screen showing the sent message 'hello how are you', "
            "a colored draft message overlay in the WhatsApp chat input field containing the message, "
            "the WhatsApp chat open with Ruth Grace Wong showing a message input field at the bottom, "
            "or the Whiz chat showing a success message or asking for confirmation to send the message. "
            "It should NOT show WhatsApp stuck on an unrecognised screen or an error about navigation."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_whatsapp_unknown_screen", "validate_screenshot_error")
        raise

    if not result:
        # Fallback: check the Whiz chat for a success/confirmation message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_whatsapp_unknown_screen_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_whatsapp_unknown_screen_chat_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. The message may ask for confirmation "
                "to send, say the draft was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to open WhatsApp or an unknown screen."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_whatsapp_unknown_screen", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_unknown_screen", "validation_failed")
    assert result, "Screen agent did not successfully navigate WhatsApp (unknown screen fix not working)"
