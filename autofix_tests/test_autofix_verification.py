#!/usr/bin/env python3
"""Autofix verification test for fitbit_food_tile_not_found.

Tests that the screen agent can successfully navigate to the Fitbit Food tile
even when Fitbit starts on (or accidentally navigates to) a sub-detail page
such as the Weight detail screen.
"""

import time


def test_autofix_fitbit_food_tile_not_found(tester):
    """Verify Fitbit food tile navigation recovers from accidental sub-detail navigation."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "fitbit_food_tile_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers addFitbitQuickCalories via the screen agent
    send_voice_command("add 300 calories to fitbit")
    time.sleep(45)  # Fitbit navigation can be slow; allow extra time

    # Check screen state after the agent finishes
    tester.screenshot("/tmp/fitbit_food_result.png")
    result = tester.validate_screenshot(
        "/tmp/fitbit_food_result.png",
        "The Fitbit app is open showing either the food log entry screen, a calorie input field, "
        "or a confirmation that calories were logged. Alternatively, the Whiz app may be showing "
        "a success message confirming calories were added to Fitbit. "
        "It should NOT show an error saying 'Could not find Food tile' or the Fitbit Weight detail screen."
    )

    if not result:
        # Fall back: check if Whiz app shows a success response
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/fitbit_food_whiz_chat.png")
        result = tester.validate_screenshot(
            "/tmp/fitbit_food_whiz_chat.png",
            "The Whiz chat shows a message confirming that calories were logged to Fitbit, "
            "or asking for confirmation. It should NOT show an error about failing to find "
            "the Food tile on the Fitbit Today screen."
        )

    if not result:
        save_failed_screenshot(tester, "fitbit_food_tile_not_found", "validation_failed")
    assert result, "Screen agent failed to log calories to Fitbit (Food tile not found or navigation error)"
