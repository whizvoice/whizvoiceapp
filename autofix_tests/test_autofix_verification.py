#!/usr/bin/env python3
"""Autofix verification test for gmaps_start_button_not_found.

Tests that the screen agent can successfully get transit directions in Google
Maps without getting stuck waiting for a Start button that never appears in
transit mode. The fix ensures that when directions_mode_tabs is visible and
transit mode was requested, the code proceeds without requiring isSelected on
the tab accessibility nodes (newer Maps versions may not set this property).
"""

import time


def test_autofix_gmaps_start_button_not_found(tester):
    """Verify fix for gmaps_start_button_not_found: transit directions complete."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_start_button_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(3)

    # Send a voice command that triggers Google Maps transit directions via screen agent
    send_voice_command("get me transit directions to downtown")
    time.sleep(40)  # wait for screen agent to launch Maps and select transit mode

    # Take screenshot and validate that Google Maps is showing transit directions
    tester.screenshot("/tmp/whiz_gmaps_transit_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_transit_result.png",
            "Google Maps is open and showing transit directions, route options, "
            "or a navigation screen. This could be: the Google Maps directions "
            "screen with transit mode tabs visible, transit route cards/options "
            "shown, or turn-by-turn navigation started. "
            "It should NOT show an error screen, the Whiz app chat stuck on a "
            "failed request, or Google Maps stuck on the main search screen "
            "without any directions shown."
        )
    except Exception as e:
        save_failed_screenshot(tester, "autofix_gmaps_start_button_not_found", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check if the chat shows a success/error message
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_transit_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_gmaps_transit_chat_result.png",
                "The Whiz chat shows a message from the assistant about getting "
                "transit directions or navigating to downtown. The message may "
                "confirm that directions were found, ask for confirmation, or "
                "show the transit route summary. "
                "It should NOT show an error about 'Start button not found' or "
                "failing to get directions."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_gmaps_start_button_not_found", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_gmaps_start_button_not_found", "validation_failed")
    assert result, "Screen agent did not successfully get transit directions in Google Maps"
