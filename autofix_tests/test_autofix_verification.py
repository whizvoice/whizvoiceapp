#!/usr/bin/env python3
"""Autofix verification test for debug_whatsapp_click_wrong_screen.

Tests that selectWhatsAppChat correctly returns success when the chat
loads slightly after the waitForCondition timeout expires — i.e. the
entry field is present in the accessibility tree by the time the
else-branch re-reads the root, but the 5-second polling window had
already elapsed without detecting INSIDE_CHAT.
"""

import time


def test_autofix_debug_whatsapp_click_wrong_screen(tester):
    """Verify fix for debug_whatsapp_click_wrong_screen.

    The failure occurred when selectWhatsAppChat clicked a chat row for
    Ruth Grace Wong, but newer WhatsApp versions render the message input
    field (com.whatsapp:id/entry) slightly after the transition completes.
    The 5-second waitForCondition timed out, the else-branch obtained a
    profileRoot that already contained the entry field but only checked for
    message_btn (a contact-profile indicator), found nothing, dumped the UI
    and pressed Back — abandoning a successful navigation.

    The fix adds a detectWhatsAppScreen() check on profileRoot before the
    message_btn search, so the function returns success when the chat is
    already open rather than incorrectly retrying.
    """
    from helpers import (
        navigate_to_my_chats, send_voice_command,
        save_failed_screenshot, wait_for_websocket_connected,
    )

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_debug_whatsapp_click_wrong_screen")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and wait for the WebSocket to connect
    tester.tap(950, 2225)
    time.sleep(2)
    assert wait_for_websocket_connected(), "WebSocket did not connect in time"

    # Send a voice command that triggers selectWhatsAppChat for Ruth Grace Wong
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(60)  # Allow time for screen agent to navigate and draft

    # Primary validation: WhatsApp chat with Ruth Grace Wong is open and
    # shows the message input field or a drafted/sent message
    tester.screenshot("/tmp/whiz_click_wrong_screen_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_click_wrong_screen_result.png",
        "The screen shows the WhatsApp chat with Ruth Grace Wong open. "
        "There should be a message input field visible at the bottom of the screen, "
        "or a Whiz draft overlay on top of the WhatsApp chat, "
        "or a sent message bubble in the chat. "
        "It should NOT show the WhatsApp chat list or an error screen."
    )

    if not result:
        # Fallback: check the Whiz chat for a success confirmation
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_click_wrong_screen_chat.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_click_wrong_screen_chat.png",
            "The Whiz chat shows an assistant message confirming it drafted or sent "
            "a WhatsApp message to Ruth Grace Wong, or asking for confirmation to send. "
            "It should NOT show an error about failing to open the WhatsApp chat."
        )

    if not result:
        save_failed_screenshot(tester, "autofix_debug_whatsapp_click_wrong_screen", "validation_failed")
    assert result, "Screen agent failed to open WhatsApp chat with Ruth Grace Wong"
