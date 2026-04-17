#!/usr/bin/env python3
"""Autofix verification test for fitbit_calories_entry_failed.

Tests that the screen agent can successfully enter calories in Fitbit's
Add Quick Calories screen, including handling the case where the Fitbit
app uses Compose-based text fields that require a tap gesture before
ACTION_SET_TEXT will work.
"""

import time


def test_autofix_fitbit_calories_entry_failed(tester):
    """Verify Fitbit quick calories entry succeeds with tap-to-focus fallback."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "fitbit_calories_entry_failed")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers addFitbitQuickCalories via screen agent.
    # Fitbit navigation: Today -> Food tile -> Food detail -> More options ->
    # Add Quick Calories -> enter amount -> LOG THIS
    send_voice_command("add 500 quick calories to Fitbit")
    time.sleep(60)  # wait for screen agent to complete multi-step Fitbit navigation

    # Check if the action completed: either the Fitbit log page is shown or the
    # Whiz chat confirms success
    tester.screenshot("/tmp/fitbit_calories_result.png")
    try:
        result = tester.validate_screenshot(
            "/tmp/fitbit_calories_result.png",
            "The screen shows evidence that quick calories were successfully logged in Fitbit. "
            "This could be: the Fitbit food log page after logging, the Fitbit Today screen, "
            "or the Whiz chat showing a success message about logging 500 calories to Fitbit. "
            "It should NOT show an error about 'Failed to enter calorie amount' or be stuck on "
            "the Fitbit Add Quick Calories entry screen unable to input the number."
        )
    except Exception as e:
        save_failed_screenshot(tester, "fitbit_calories_entry_failed", "validate_screenshot_error")
        raise

    if not result:
        # Navigate back to Whiz app to check for success/error message in chat
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/fitbit_calories_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/fitbit_calories_chat_result.png",
                "The Whiz chat shows a message from the assistant confirming that calories "
                "were logged to Fitbit, or asking for confirmation. "
                "It should NOT show an error about failing to enter the calorie amount."
            )
        except Exception as e:
            save_failed_screenshot(tester, "fitbit_calories_entry_failed", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "fitbit_calories_entry_failed", "validation_failed")
    assert result, "Screen agent did not successfully log quick calories to Fitbit"
