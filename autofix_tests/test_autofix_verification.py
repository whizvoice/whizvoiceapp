#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_whatsapp_unknown_screen(tester):
    """Verify fix for whatsapp_unknown_screen.

    Tests that the screen agent can navigate WhatsApp even when a 'Turn on
    notifications' permission dialog appears on launch (fresh install / emulator).
    The fix adds com.whatsapp:id/fab as a CHAT_LIST indicator so an empty chat
    list is correctly detected after the dialog is dismissed.
    """
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_unknown_screen")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("send a WhatsApp message to Ruth Grace Wong saying hello how are you")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_whatsapp_screen.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_whatsapp_screen.png",
        "WhatsApp is open showing a chat with Ruth Grace Wong, "
        "with a message draft overlay or the chat input visible"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_whatsapp_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_whatsapp_chat_result.png",
            "The Whiz chat shows a message from the assistant about drafting or sending "
            "a WhatsApp message to Ruth Grace Wong. It should NOT show an error about "
            "an unknown screen or failing to navigate WhatsApp."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_whatsapp_unknown_screen", "validation_failed")
    assert result, (
        "WhatsApp draft did not reach Ruth Grace Wong's chat — "
        "notification dialog or empty chat list may not be handled correctly"
    )


def test_autofix_ytmusic_play_deeplink_no_result(tester):
    """Verify fix for ytmusic_play_deeplink_no_result.

    Tests that the screen agent can find and click a song result in YouTube Music
    search even when result rows are not long-clickable or type indicators are
    nested deeper than 2 levels (newer YouTube Music versions).
    """
    success, error = navigate_to_my_chats(tester, "autofix_ytmusic_play_deeplink_no_result")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("play Lush on YouTube Music")
    time.sleep(35)

    tester.screenshot("/tmp/whiz_ytmusic_screen.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_ytmusic_screen.png",
        "YouTube Music is open and playing a song, showing a mini player or now-playing screen"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_ytmusic_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_ytmusic_chat_result.png",
            "The Whiz chat shows a message from the assistant confirming that a song is "
            "playing on YouTube Music. It should NOT show an error about failing to find "
            "or click a search result."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_ytmusic_play_deeplink_no_result", "validation_failed")
    assert result, (
        "YouTube Music did not start playing after voice command — "
        "song result row may not have been found/clicked in search results"
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
