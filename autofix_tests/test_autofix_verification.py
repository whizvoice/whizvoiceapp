#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_google_maps_unknown_state(tester):
    """Verify fix for google_maps_unknown_state.

    Tests that the screen agent can navigate through the Maps loading state
    (ProgressBar visible while fetching location details) without triggering
    an unknown state error.
    """
    success, error = navigate_to_my_chats(tester, "autofix_google_maps_unknown_state")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("get directions to 1117 Tennessee St San Francisco")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_gmaps_directions.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_directions.png",
        "Google Maps is open and showing either a location details page with a "
        "Directions button, or a turn-by-turn directions/navigation screen, "
        "or a route summary screen"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz chat shows a message from the assistant about opening directions "
            "or navigation in Google Maps. It should NOT show an error about unknown state "
            "or failing to navigate."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_google_maps_unknown_state", "validation_failed")
    assert result, (
        "Google Maps did not reach directions/location details after search — "
        "SEARCH_LOADING state may not be handled correctly"
    )


def test_autofix_whatsapp_input_not_found(tester):
    """Verify fix for whatsapp_input_not_found.

    Tests that the screen agent dismisses the WhatsApp notification permission
    bottom sheet and successfully drafts a message instead of failing with
    'Could not find message input field'.
    """
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_input_not_found")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying Hi just checking in")
    time.sleep(35)

    tester.screenshot("/tmp/whiz_whatsapp_draft.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_whatsapp_draft.png",
        "WhatsApp is open showing a chat conversation with a message draft overlay "
        "or text already entered in the message input field, "
        "OR the Whiz app is showing a success response about a WhatsApp message being drafted"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_whatsapp_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_chat_result.png",
            "The Whiz chat shows a success message about drafting or sending a WhatsApp message "
            "to Ruth Grace Wong. It should NOT show an error about 'could not find message input' "
            "or 'whatsapp_input_not_found'."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_whatsapp_input_not_found", "validation_failed")
    assert result, (
        "WhatsApp draft did not succeed — notification permission dialog may not be dismissed correctly"
    )
