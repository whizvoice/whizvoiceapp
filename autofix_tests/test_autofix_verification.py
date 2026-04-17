#!/usr/bin/env python3
"""Autofix verification test for ytmusic_play_deeplink_no_result.

Tests that the screen agent can play music by an artist (Lush) even when
YouTube Music navigates directly to the artist page instead of showing search
results, by clicking the 'Play all' button on the artist detail page.
"""

import time


def test_autofix_ytmusic_play_deeplink_no_result(tester):
    """Verify fix for ytmusic_play_deeplink_no_result."""
    from helpers import navigate_to_my_chats, send_voice_command, save_failed_screenshot

    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_ytmusic_deeplink")
    assert success, f"Could not reach My Chats: {error}"

    # Open new chat and let the UI settle before sending a voice command
    tester.tap(950, 2225)
    time.sleep(2)

    # Send a voice command that triggers YouTube Music playback for artist "Lush"
    # This is the same query that caused the original failure - a well-known artist
    # where YouTube Music navigates directly to the artist page instead of search results
    send_voice_command("play Lush on YouTube Music")
    time.sleep(30)  # wait for screen agent to complete (deep link + artist page navigation)

    # Take a screenshot and validate the result
    tester.screenshot("/tmp/whiz_ytmusic_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_ytmusic_result.png",
        "YouTube Music is open and music is playing. The screen should show either: "
        "(1) The YouTube Music app with a mini player bar at the bottom showing a song title and artist, "
        "(2) The Lush artist page in YouTube Music with a mini player indicating music is playing, "
        "(3) Any YouTube Music playback screen showing audio is active. "
        "It should NOT show an error or a blank/empty state."
    )

    if not result:
        # Check if Whiz app shows a success response about playing music
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_ytmusic_chat_result.png")
        try:
            result = tester.validate_screenshot(
                "/tmp/whiz_ytmusic_chat_result.png",
                "The Whiz chat shows a success message about playing music by Lush or playing music "
                "on YouTube Music. The assistant message should confirm that music is now playing. "
                "It should NOT show an error message about failing to find or click a result."
            )
        except Exception as e:
            save_failed_screenshot(tester, "autofix_ytmusic_deeplink", "validate_chat_error")
            raise

    if not result:
        save_failed_screenshot(tester, "autofix_ytmusic_deeplink", "validation_failed")
    assert result, "Screen agent did not successfully play music via YouTube Music artist page"
