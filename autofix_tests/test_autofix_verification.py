#!/usr/bin/env python3
"""Autofix verification test for gmaps_app_not_ready.

Tests that the screen agent can successfully navigate Google Maps to show
location details or search results after a geo: intent search — including
when Maps was previously in a navigation-like state (terra_navigation_header_close_button
visible) which caused the old 5000ms waitForAppReady timeout to expire.
"""

import time


def test_autofix_gmaps_app_not_ready(tester):
    """Verify fix for gmaps_app_not_ready.

    The fix: increased waitForAppReady timeout to 10000ms and added
    terra_navigation_header_close_button detection as LOCATION_DETAILS.
    """
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_app_not_ready")
    assert success, f"Could not reach My Chats: {error}"

    # Open new chat
    tester.tap(950, 2225)
    time.sleep(5)  # wait for new chat to open and WebSocket to connect

    # Send a voice command that triggers getGoogleMapsDirections(mode=transit, search=downtown)
    # This exercises the exact code path that failed: searchGoogleMapsLocation -> waitForAppReady
    send_voice_command("get me transit directions to downtown")
    time.sleep(45)  # wait for screen agent to complete (Maps launch + search + selection)

    # Take screenshot to validate result
    tester.screenshot("/tmp/whiz_gmaps_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_result.png",
            "Google Maps is open and showing either: a location details page (with a place "
            "name and a Directions button), search results for 'downtown', transit route "
            "options, or a directions screen. The screen should show Maps content, not an "
            "error message or a loading spinner with no content."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_gmaps_app_not_ready", "validate_screenshot_error")
        raise

    if not result:
        # Fall back: check if Whiz app shows a successful Maps response
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_gmaps_chat_result.png",
                "The Whiz chat shows a message about Google Maps directions to downtown, "
                "transit options, or a successful navigation result. It should NOT show "
                "an error like 'Google Maps did not become ready in time'."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_gmaps_app_not_ready", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_app_not_ready", "validation_failed")
    assert result, (
        "Screen agent did not successfully navigate Google Maps for transit directions to downtown. "
        "Expected Maps to show location details or route options."
    )
