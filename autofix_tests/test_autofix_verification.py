#!/usr/bin/env python3
"""Autofix verification test for fitbit_food_tile_not_found.

Tests that the screen agent can successfully navigate to the Fitbit Food tile
and log food, even when starting from a sub-detail page (e.g. Weight detail)
that also contains "Today" text but should not be mistaken for the Today home
screen.
"""

import time


def test_fitbit_food_tile_not_found(tester):
    """Verify Fitbit food logging finds the Food tile and logs calories."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "fitbit_food_tile_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers Fitbit food logging
    send_voice_command("log 400 calories on Fitbit")
    time.sleep(30)  # wait for screen agent to complete

    # Validate the result - Fitbit should show the food was logged
    tester.screenshot("/tmp/whiz_fitbit_food_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_fitbit_food_result.png",
        "The screen shows evidence that food/calories were successfully logged in Fitbit. "
        "This could be: the Fitbit Food detail page showing a logged entry, "
        "the Fitbit Today screen with updated calorie count on the Food tile, "
        "a confirmation that calories were logged, or the Whiz chat showing a success message "
        "about logging food/calories on Fitbit. "
        "It should NOT show an error message, the Fitbit app stuck on a non-food page, "
        "or a failure to find the Food tile."
    )
    if not result:
        save_failed_screenshot(tester, "fitbit_food_tile_not_found", "validation_failed")
    assert result, "Screen agent did not successfully log food on Fitbit"
