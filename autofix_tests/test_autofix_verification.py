#!/usr/bin/env python3
"""Autofix verification tests for WhatsApp screen agent navigation.

Covers:
- whatsapp_input_not_found: finds the input field and drafts a message
- whatsapp_nav_to_chatlist_failed: successfully navigates to the chat list
  even when back-pressing has exited WhatsApp to the launcher
"""

import time


def test_autofix_whatsapp_nav_to_chatlist_failed(tester):
    """Verify fix for whatsapp_nav_to_chatlist_failed.

    The bug: detectWhatsAppScreen returned UNKNOWN for the new WhatsApp chat
    list UI, so the nav loop pressed BACK 6 times, exiting WhatsApp to the
    launcher and then giving up.

    The fix: (1) re-launch WhatsApp when back-press has exited to the launcher,
    (2) broader Chats-tab content-description matching for new WhatsApp versions.
    """
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to the Whiz 'My Chats' page first
    success, error = navigate_to_my_chats(tester, "whatsapp_nav_to_chatlist_failed")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat session so the voice command has a WebSocket to send through
    tester.tap(950, 2225)
    time.sleep(3)

    # Send a voice command that triggers selectWhatsAppChat, exercising the
    # navigation-to-chat-list code path that was failing.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hey whats up")
    time.sleep(60)  # wait for screen agent to complete navigation + draft

    # Check current screen: ideally WhatsApp is open with the chat
    tester.screenshot("/tmp/whiz_nav_chatlist_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_nav_chatlist_result.png",
            "The screen shows evidence that the WhatsApp screen agent navigation succeeded. "
            "This could be: the WhatsApp chat screen open with Ruth Grace Wong showing "
            "the drafted message 'hey whats up' in the input field or already sent, "
            "OR the Whiz app chat showing a success/confirmation message about drafting "
            "or sending a WhatsApp message. "
            "It should NOT show the Android home launcher, an error about failing to "
            "navigate to the WhatsApp chat list, or the app stuck on the home screen."
        )
    except Exception as e:
        save_failed_screenshot(tester, "whatsapp_nav_to_chatlist_failed", "validate_screenshot_error")
        raise

    if not result:
        # Fallback: switch to Whiz app and check for a success/confirmation message there
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_nav_chatlist_whizapp_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_nav_chatlist_whizapp_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. The assistant may ask to confirm "
                "the send, say the draft was prepared, or confirm it was sent. "
                "It should NOT show an error about failing to navigate to the WhatsApp "
                "chat list or failing to open WhatsApp."
            )
        except Exception as e:
            save_failed_screenshot(tester, "whatsapp_nav_to_chatlist_failed", "validate_whizapp_error")
            raise

    if not result:
        save_failed_screenshot(tester, "whatsapp_nav_to_chatlist_failed", "validation_failed")
    assert result, "Screen agent failed to navigate to WhatsApp chat list"


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
