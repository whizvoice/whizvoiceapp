#!/usr/bin/env python3
"""Autofix verification test for debug_whatsapp_after_search.

Tests that the screen agent can successfully select a WhatsApp chat via the
search flow, specifically exercising the case where the new WhatsApp version
shows filter buttons (Unread, Photos, Videos…) before contact results load.
"""

import time


def test_autofix_debug_whatsapp_after_search(tester):
    """Verify fix for debug_whatsapp_after_search.

    The fix adds:
    1. SEARCH_ACTIVE detection in detectWhatsAppScreen so the search results
       screen is not misidentified as UNKNOWN (preventing a spurious
       whatsapp_unknown_screen dump).
    2. An extra 3-second wait after waitForSearchResults when the new WhatsApp
       filter row (Unread, Photos, Videos…) is detected, giving contact results
       time to populate the accessibility tree before the retry loop gives up.
    """
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_debug_whatsapp_after_search")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button to open a new chat session
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers WhatsApp chat selection via the screen agent.
    # This exercises selectWhatsAppChat which performs a search when the contact is
    # not immediately visible in the recent chat list.
    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(65)  # wait for screen agent to complete (search + contact load + draft/send)

    # Take screenshot and validate the result
    tester.screenshot("/tmp/whiz_whatsapp_after_search_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_after_search_result.png",
            "The screen shows evidence that a WhatsApp message was successfully sent or drafted "
            "to Ruth Grace Wong. This could be: the WhatsApp chat screen open with Ruth Grace Wong "
            "showing the sent message 'hello how are you', a draft message overlay in the WhatsApp "
            "chat input field, or the Whiz chat showing a success or confirmation message. "
            "It should NOT show the WhatsApp search results screen still open, an error message, "
            "or the app stuck on a contact profile page."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_debug_whatsapp_after_search", "validate_screenshot_error")
        raise

    if not result:
        # Fall back: navigate to Whiz app and check assistant response
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_chat_after_search_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_chat_after_search_result.png",
                "The Whiz chat shows a message from the assistant about drafting or sending "
                "a WhatsApp message to Ruth Grace Wong. The assistant may ask for confirmation "
                "to send, say the draft was prepared, or confirm the message was sent. "
                "It should NOT show an error about failing to find the chat or failing to open WhatsApp."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_debug_whatsapp_after_search", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_debug_whatsapp_after_search", "validation_failed")
    assert result, "Screen agent did not successfully select WhatsApp chat and draft/send message to Ruth Grace Wong"
