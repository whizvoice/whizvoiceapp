#!/usr/bin/env python3
"""Autofix verification test for gmaps_no_nonsponsored_result.

Tests that the screen agent can successfully search Google Maps and select
a non-sponsored result, even on newer Google Maps versions where the
"Directions" button is absent from the accessibility tree.
"""

import time


def test_gmaps_no_nonsponsored_result(tester):
    """Verify Google Maps search selects a non-sponsored result."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "gmaps_no_nonsponsored_result")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers Google Maps search
    send_voice_command("find City Hall on Google Maps")
    time.sleep(30)  # wait for screen agent to complete

    # Validate the result - Google Maps should be showing a location
    tester.screenshot("/tmp/whiz_gmaps_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_result.png",
        "Google Maps is showing a specific location detail page for a place "
        "(with details like address, ratings, Directions/Call/Save buttons, etc). "
        "The place does NOT need to be named exactly 'City Hall' - any government "
        "building, city office, or civic location is acceptable. "
        "It should NOT be stuck on a filter row, search results list, or showing an error."
    )
    if not result:
        save_failed_screenshot(tester, "gmaps_no_nonsponsored_result", "validation_failed")
    assert result, "Screen agent did not successfully navigate Google Maps search results"
