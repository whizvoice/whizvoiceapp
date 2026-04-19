#!/usr/bin/env python3
"""Autofix verification test for gmaps_directions_screen_not_found.

In newer Google Maps versions the directions screen shows "Choose start location"
instead of auto-populating the current GPS position.  The old code interpreted the
missing Start button as "screen not ready" and timed out after 5 attempts.

The fix detects the "Choose start location" placeholder, taps it, and selects
"Your location" / "My location" so that a route is calculated and the Start button
can appear.
"""

import time


def test_autofix_gmaps_directions_screen_not_found(tester):
    """Verify fix for gmaps_directions_screen_not_found."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_directions")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat
    tester.tap(950, 2225)
    time.sleep(3)

    # Ask for driving directions — this exercises the non-transit code path where
    # "Choose start location" appears in newer Google Maps versions.
    send_voice_command("get me directions to 49 South Venice Boulevard")
    time.sleep(45)  # wait for screen agent to complete (Maps navigation takes time)

    # Take a screenshot of whatever is on screen
    tester.screenshot("/tmp/whiz_gmaps_result.png")

    # Primary check: Google Maps is showing a directions or navigation screen
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_result.png",
        "Google Maps is showing a directions screen, a route overview, or turn-by-turn "
        "navigation for a destination. Acceptable states: the directions input form with "
        "mode tabs (Drive/Transit/Walk/Bike), a route summary with distance and duration, "
        "or an active navigation screen. "
        "NOT acceptable: the main Maps search screen with an empty search box, or an "
        "'Choose start location' prompt with no route calculated."
    )

    if not result:
        # Fallback: check if the Whiz app shows a success message in the chat
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz assistant has successfully retrieved directions or navigation "
            "information. The assistant message should mention a route, directions, "
            "navigation, or travel time — NOT an error about failing to find the "
            "directions screen or Start button."
        )

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_directions", "validation_failed")

    assert result, (
        "Screen agent did not successfully navigate to the Google Maps directions screen. "
        "The fix for gmaps_directions_screen_not_found ('Choose start location' detection) "
        "did not resolve the issue."
    )
