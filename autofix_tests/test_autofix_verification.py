#!/usr/bin/env python3
"""Autofix verification test for gmaps_search_results_timeout.

Tests that the screen agent can navigate Google Maps search results even when
Google Maps shows results in the new fullscreens_group UI (suggestions list)
instead of the old search_list_layout.
"""

import time


def test_autofix_gmaps_search_results_timeout(tester):
    """Verify Google Maps search results load successfully with new UI."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "gmaps_search_results_timeout")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers Google Maps search via screen agent
    send_voice_command("show me directions to Casemates du Bock")
    time.sleep(40)  # wait for screen agent to complete Google Maps navigation

    # Check if Google Maps is showing directions or location details
    tester.screenshot("/tmp/whiz_gmaps_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_result.png",
            "The screen shows Google Maps with either: a location details page for Casemates du Bock, "
            "a directions screen showing a route, search results list with places, "
            "or the Whiz chat showing a success message about finding the location. "
            "It should NOT show a timeout error or 'Search results did not load in time' message."
        )
    except Exception as e:
        save_failed_screenshot(tester, "gmaps_search_results_timeout", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check if the chat shows a response
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_gmaps_chat_result.png",
                "The Whiz chat shows a message from the assistant related to finding or showing "
                "Casemates du Bock on Google Maps, or asking for confirmation, or showing directions. "
                "It should NOT show a 'Search results did not load in time' error."
            )
        except Exception as e:
            save_failed_screenshot(tester, "gmaps_search_results_timeout", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "gmaps_search_results_timeout", "validation_failed")
    assert result, "Screen agent failed to load Google Maps search results"
