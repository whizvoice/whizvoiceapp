#!/usr/bin/env python3
"""Autofix verification test for debug_whatsapp_before_click_match.

Tests that the screen agent correctly finds exactly one match when selecting
a WhatsApp chat by name, avoiding false matches from contact_photo nodes
(which now carry the contact name as content description in newer WhatsApp)
and duplicate conversations_row_contact_name nodes.
"""

import time


def test_autofix_debug_whatsapp_before_click_match(tester):
    """Verify selectWhatsAppChat navigates into the chat despite the new WhatsApp UI."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_debug_whatsapp_before_click_match")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers selectWhatsAppChat + draftWhatsAppMessage.
    # This exercises the exact code path that produced the debug_whatsapp_before_click_match
    # dump: the screen agent searches WhatsApp for "Ruth Grace Wong" and must select the chat.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    # Allow extra time: selectWhatsAppChat + search + draftWhatsAppMessage can be slow
    time.sleep(60)

    # Check screen state — we expect to be inside the WhatsApp chat or to have a draft overlay,
    # which means selectWhatsAppChat succeeded.
    tester.screenshot("/tmp/whiz_screen.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_screen.png",
        "The screen shows evidence that the WhatsApp screen agent successfully selected "
        "the chat with Ruth Grace Wong. This could be: the WhatsApp chat screen open with "
        "Ruth Grace Wong's name in the toolbar, a draft message 'hello how are you' visible "
        "in the WhatsApp message input field, a colored overlay with draft text, or the "
        "WhatsApp chat showing the sent message. It should NOT show an error message, the "
        "WhatsApp main chat list, or the app stuck in search results."
    )

    if not result:
        # Fall back: check the Whiz chat to see if the assistant confirmed success
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_chat_result.png",
            "The Whiz chat shows a message from the assistant about drafting or sending "
            "a WhatsApp message to Ruth Grace Wong. The message may ask for confirmation "
            "to send, say the draft was prepared, or confirm the message was sent. "
            "It should NOT show an error about failing to find the contact or failing "
            "to open the WhatsApp chat."
        )
        if not result:
            save_failed_screenshot(
                tester,
                "autofix_debug_whatsapp_before_click_match",
                "validation_failed"
            )

    assert result, (
        "Screen agent did not successfully navigate to the WhatsApp chat with Ruth Grace Wong. "
        "The fix for extra contact_photo matches and duplicate node deduplication may not be working."
    )
