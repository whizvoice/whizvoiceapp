#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_gmaps_search_results_timeout(tester):
    """Verify fix for gmaps_search_results_timeout.

    Tests that the screen agent correctly detects Google Maps search results
    even when the Whiz app or IME becomes the active window during polling
    (getRootNodeForPackage searches all windows, not just rootInActiveWindow).
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_search_results_timeout")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    # This query triggers searchGoogleMapsLocation which was failing with timeout
    send_voice_command("find coffee shops near me")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_gmaps_search_results.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_search_results.png",
        "Google Maps is open and showing either a list of search results for "
        "coffee shops, or a location details page for a coffee shop"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_search_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_search_chat_result.png",
            "The Whiz chat shows a message from the assistant about coffee shops "
            "or a location found in Google Maps. It should NOT show an error about "
            "search results not loading or timing out."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_gmaps_search_results_timeout", "validation_failed"
            )
    assert result, (
        "Google Maps did not show search results — getRootNodeForPackage fix may "
        "not be working correctly when Whiz/IME is the active window"
    )
