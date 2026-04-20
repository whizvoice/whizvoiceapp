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


def test_autofix_gmaps_no_nonsponsored_result(tester):
    """Verify fix for gmaps_no_nonsponsored_result.

    Tests that the screen agent can select a result from a Google Maps search list
    even when the first item in the list has zero/negative height (invisible/collapsed).
    Previously the agent would click the invisible first item repeatedly and fail.
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_no_nonsponsored_result")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("show me City Hall on Google Maps")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_gmaps_cityhall.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_cityhall.png",
        "Google Maps is open and showing a location details page for a 'City Hall' "
        "or a similar civic building — NOT a search results list and NOT an error"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_cityhall_chat.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_cityhall_chat.png",
            "The Whiz chat shows a message from the assistant indicating it successfully "
            "found or navigated to City Hall in Google Maps. It should NOT show an error "
            "about failing to select a non-sponsored result."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_gmaps_no_nonsponsored_result", "validation_failed")
    assert result, (
        "Screen agent failed to select a visible result from Google Maps search list — "
        "invisible/collapsed first result may not be skipped correctly"
    )
