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
