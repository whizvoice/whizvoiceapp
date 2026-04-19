#!/usr/bin/env python3
"""Autofix verification test for gmaps_search_results_timeout.

Google Maps now shows a "No results found" screen when a search yields no
matches. The old code only waited for SEARCH_RESULTS_LIST or LOCATION_DETAILS,
so it always timed out (8s wait) instead of detecting this terminal state.

The fix adds a NO_RESULTS state to detectGoogleMapsScreenState, detects the
"No results found" text, and exits the polling loop immediately with a proper
error message rather than timing out.

This test exercises the happy path (a real location search that produces
results) to confirm the fix doesn't break normal search flows.
"""

import time


def test_autofix_gmaps_search_results_timeout(tester):
    """Verify fix for gmaps_search_results_timeout."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_search_results_timeout")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before sending a voice command
    tester.tap(950, 2225)
    time.sleep(3)

    # Send a voice command that triggers a Google Maps location search.
    # "Trader Joe's near me" is a reliable query that produces real search results,
    # exercising the SEARCH_RESULTS_LIST path that must still work after the fix.
    send_voice_command("what are the trader joes near me")
    time.sleep(35)  # wait for screen agent to complete

    # Take a screenshot of whatever is on screen
    tester.screenshot("/tmp/whiz_gmaps_search_result.png")

    # Primary check: Google Maps is showing search results for Trader Joe's
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_search_result.png",
        "Google Maps is showing a list of search results for Trader Joe's, "
        "or a Trader Joe's location details page. Acceptable states: a list of "
        "Trader Joe's store locations, or a specific Trader Joe's store details. "
        "NOT acceptable: the main Maps search screen with no results, an error "
        "screen, or a completely unrelated screen."
    )

    if not result:
        # Fallback: check if the Whiz app itself shows a useful response about Trader Joe's
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz assistant has responded with information about Trader Joe's "
            "locations, including store names, addresses, or hours. The assistant "
            "message should mention Trader Joe's — NOT a timeout error or a message "
            "saying search results did not load in time."
        )

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_search_results_timeout", "validation_failed")

    assert result, (
        "Screen agent did not successfully show Google Maps search results. "
        "The fix for gmaps_search_results_timeout (NO_RESULTS state detection) "
        "may have broken the normal search results path."
    )
