#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_ytmusic_play_deeplink_no_result(tester):
    """Verify fix for ytmusic_play_deeplink_no_result.

    Reproduces the original failure: two back-to-back YouTube Music play
    requests for the same song. The first request starts playback, which makes
    YouTube Music re-render the top-result card's action button as
    "Pause <title>" instead of "Play <title>". The second request then found no
    "Play " button, and the row fallback could not see the "Song • ..." byline
    because newer YT Music nests it deeper than the old child/grandchild scan
    reached — so the agent dumped ytmusic_play_deeplink_no_result.

    The fix (a) treats a "Pause <title>" top-result card as already playing
    instead of failing, and (b) deepens the result-row type-indicator scan.
    """
    success, error = navigate_to_my_chats(tester, "autofix_ytmusic_play_deeplink_no_result")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    # First play — starts playback and puts the top-result card into the
    # "Pause <title>" state that broke the second request.
    send_voice_command("play the song Me Rehuso by Danny Ocean on YouTube Music")
    time.sleep(40)

    # Second play of the same song — this is the request that used to fail.
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)
    send_voice_command("play Me Rehuso Danny Ocean on YouTube Music")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_ytmusic_deeplink_replay.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_ytmusic_deeplink_replay.png",
        "YouTube Music is showing a song playing — either a now-playing screen "
        "with playback controls, or search results with a mini player bar at the "
        "bottom showing a track title and a play/pause button"
    )

    if not result:
        # The agent may have handed control back to Whiz after succeeding, so
        # fall back to checking the chat for a success (not error) response.
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_ytmusic_deeplink_chat.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_ytmusic_deeplink_chat.png",
            "The Whiz chat shows an assistant message confirming a song is "
            "playing on YouTube Music. It must NOT show an error about being "
            "unable to find a search result to click."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_ytmusic_play_deeplink_no_result", "validation_failed"
            )

    assert result, (
        "Repeat YouTube Music play request did not reach a playing state — "
        "ytmusic_play_deeplink_no_result may still be triggering"
    )
