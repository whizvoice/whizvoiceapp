#!/usr/bin/env python3
"""Autofix verification test for gmaps_directions_screen_not_found.

In newer Google Maps versions, clicking the Directions button from a location
details page shows an origin-input screen ("Search here") before presenting
the directions form with mode-tabs and a Start button.  The old code only
looked for `directions_mode_tabs` / the Start button, so it always timed out
on this intermediate screen.

The fix adds detection of the origin-search screen inside the polling loop
and tries to click "Your location" / "My location" (or types "My location"
into the search box) so that the directions form can appear.
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

    # Send a voice command that triggers getGoogleMapsDirections with transit mode.
    # This exercises exactly the code path that caused gmaps_directions_screen_not_found.
    send_voice_command("get me transit directions to 1680 Mission Street San Francisco")
    time.sleep(45)  # wait for screen agent to complete (Maps navigation takes time)

    # Take a screenshot of whatever is on screen
    tester.screenshot("/tmp/whiz_gmaps_result.png")

    # Primary check: Google Maps is showing a directions or transit routes screen
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_result.png",
        "Google Maps is showing a directions screen, transit routes, or a route overview "
        "for a destination in San Francisco. Acceptable states: the directions input form "
        "with mode tabs (Drive/Transit/Walk/Bike), a list of transit route options, "
        "a turn-by-turn navigation screen, or a route summary. "
        "NOT acceptable: the main Maps search screen with an empty search box and "
        "recent searches shown, or an error screen."
    )

    if not result:
        # Fallback: check if the Whiz app itself shows a success/confirmation message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz assistant has successfully retrieved transit directions or route "
            "information for 1680 Mission Street in San Francisco. The assistant message "
            "should mention transit directions, a route, or navigation — NOT an error about "
            "failing to find the directions screen or Start button."
        )

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_directions", "validation_failed")

    assert result, (
        "Screen agent did not successfully navigate to the Google Maps directions screen. "
        "The fix for gmaps_directions_screen_not_found (origin search screen detection) "
        "did not resolve the issue."
    )
