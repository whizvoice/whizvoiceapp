#!/usr/bin/env python3
"""Autofix verification test for ytmusic_queue_nav_failed.

Tests that the screen agent can queue a YouTube Music song even when the
Now Playing screen is open and pressing back would exit the app (newer
YouTube Music versions). The fix re-launches YouTube Music if navigation
presses back too far and exits the app.
"""

import time


def test_autofix_ytmusic_queue_nav_failed(tester):
    """Verify queuing a YouTube Music song succeeds after playing a song first."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "ytmusic_queue_nav_failed")
    assert success, f"Could not reach My Chats: {error}"

    # Tap New Chat button to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # First, play a song so YouTube Music is open with a Now Playing screen.
    # This replicates the state that caused the original failure.
    send_voice_command("play despacito on youtube music")
    time.sleep(40)  # wait for the play command to complete

    # Navigate back to Whiz and open a new chat for the queue command
    tester.open_app("com.example.whiz.debug")
    time.sleep(2)
    success, error = navigate_to_my_chats(tester, "ytmusic_queue_nav_failed_setup")
    assert success, f"Could not reach My Chats after play: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    # Now queue another song — this exercises the fixed navigation code
    send_voice_command("queue shape of you on youtube music")
    time.sleep(50)  # queue command may need time to navigate and search

    # Take a screenshot to validate the result
    tester.screenshot("/tmp/ytmusic_queue_result.png")

    # Check if YouTube Music is open and shows the queue was successful,
    # or if Whiz chat shows a success/confirmation message
    result = tester.validate_screenshot(
        "/tmp/ytmusic_queue_result.png",
        "The screen shows YouTube Music is open with a song playing or queued, "
        "OR the Whiz Voice app chat showing a success message about queuing a song "
        "on YouTube Music. It should NOT show an error message about failing to "
        "navigate YouTube Music or 'Could not navigate to searchable screen'."
    )

    if not result:
        # Also check the Whiz chat for a success confirmation
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/ytmusic_queue_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/ytmusic_queue_chat_result.png",
                "The Whiz chat shows the assistant successfully queued or added a song "
                "to the YouTube Music queue. There should be a success message or "
                "confirmation about queuing 'shape of you' or similar. "
                "It should NOT show an error about failing to navigate YouTube Music."
            )
        except Exception as e:
            save_failed_screenshot(tester, "ytmusic_queue_nav_failed", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "ytmusic_queue_nav_failed", "validation_failed")
    assert result, "Screen agent did not successfully queue a YouTube Music song"
