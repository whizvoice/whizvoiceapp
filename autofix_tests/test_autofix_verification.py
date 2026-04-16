#!/usr/bin/env python3
"""Autofix verification test for gmaps_location_select_failed.

Tests that the screen agent can select a specific location from Google Maps
search results, including when results appear inside the new "Curated with
Gemini" horizontal category section (child 0 of search_list_layout).
"""

import time


def test_autofix_gmaps_location_select_failed(tester):
    """Verify that the screen agent can find and select a location from Maps search results."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_location_select")
    assert success, f"Could not reach My Chats: {error}"

    # Open new chat and let the UI settle
    tester.tap(950, 2225)
    time.sleep(2)

    # Ask about restaurants near me — this triggers the Maps screen agent which searches
    # and then selects a specific location by fragment. "mango" is the failing fragment.
    send_voice_command("find mango restaurants near me on Google Maps")
    time.sleep(35)  # wait for screen agent to complete (Maps launch + search + select)

    # Check what's on screen now
    tester.screenshot("/tmp/whiz_gmaps_select_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_select_result.png",
            "Google Maps is open and showing search results for mango restaurants, "
            "OR a specific mango restaurant detail page is open, "
            "OR the Whiz app chat shows location information or a success message about mango restaurants. "
            "It should NOT show an error about failing to find or click a location."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_gmaps_location_select", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check if the chat shows results
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_gmaps_chat_result.png",
                "The Whiz chat shows a response from the assistant about mango restaurants. "
                "The response might show location details, a map link, directions, or ask "
                "for confirmation. It should NOT show an error about failing to select a location."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_gmaps_location_select", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_location_select", "validation_failed")
    assert result, "Screen agent did not successfully find and select a Maps location"
