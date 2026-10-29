#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_sms_stuck_in_search_screen(tester):
    """Verify fix for sms_stuck_in_search_screen.

    The screen agent searches for an SMS contact via the Messages search box,
    then must click the matching search result to open the conversation before
    drafting. Newer Google Messages renders search results into a
    `zero_state_search_list_view` ListView instead of the older
    `zero_state_search_chat_results` RecyclerView, so the agent never detected
    the results, never opened the conversation, and got stuck on the search
    screen ("Cannot draft message: still in search screen").

    This test sends a voice command that drafts an SMS to a contact that exists
    on the emulator and verifies the agent successfully leaves the search screen
    and opens/drafts the conversation rather than getting stuck.
    """
    success, error = navigate_to_my_chats(tester, "autofix_sms_stuck_in_search_screen")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before sending a voice command
    tester.tap(950, 2225)
    time.sleep(2)

    # Draft an SMS to a contact that exists on the emulator. This exercises the
    # search-then-select flow inside selectSMSChat / performSMSSearch.
    send_voice_command("Send a text message to Ruth Grace Wong saying see you soon")
    time.sleep(30)  # wait for the screen agent to search, open the chat, and draft

    # Validate the agent is NOT stuck on the SMS search screen and actually
    # opened the conversation / drafted the message.
    tester.screenshot("/tmp/whiz_sms_search.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_sms_search.png",
        "Google Messages has an open SMS conversation with Ruth Grace Wong "
        "(a message thread / text input field is visible), or a Whiz draft "
        "overlay showing the drafted message is visible. It must NOT be the "
        "Messages search screen showing 'No results found'."
    )
    if not result:
        # Fall back to checking the Whiz chat for a successful draft message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_sms_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_sms_chat_result.png",
            "The Whiz chat shows an assistant message about drafting or sending a "
            "text message to Ruth Grace Wong. It must NOT show an error about "
            "being stuck in the search screen or being unable to find the contact."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_sms_stuck_in_search_screen", "validation_failed"
            )
    assert result, (
        "Screen agent got stuck on the SMS search screen — "
        "sms_stuck_in_search_screen may still be triggering"
    )
