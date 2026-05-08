#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_ytmusic_play_deeplink_no_result(tester):
    """Verify fix for ytmusic_play_deeplink_no_result.

    YouTube Music's first-launch sign-in/onboarding screen swallows the deep
    link search query, so the search results never load and the screen agent
    times out. The fix detects the sign-in screen on the deep-link path,
    dismisses it, and re-fires the deep link so the search runs.
    """
    success, error = navigate_to_my_chats(tester, "autofix_ytmusic_play_deeplink_no_result")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("play Clean Bandit on YouTube Music")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_ytmusic_deeplink_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_ytmusic_deeplink_result.png",
        "YouTube Music is open and showing either search results for "
        "'Clean Bandit' (a list of songs/artists/albums), an artist page for "
        "Clean Bandit, or a now-playing screen with a Clean Bandit track. "
        "It should NOT be showing the YouTube Music sign-in/onboarding "
        "screen with 'Over 100 million songs and counting' text and a "
        "'Sign in' button."
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_ytmusic_deeplink_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_ytmusic_deeplink_chat_result.png",
            "The Whiz chat shows a successful response from the assistant "
            "about playing Clean Bandit on YouTube Music. It should NOT "
            "show an error about being unable to find a search result or "
            "YouTube Music not being ready."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_ytmusic_play_deeplink_no_result", "validation_failed"
            )
    assert result, (
        "YouTube Music deep-link search did not produce a result — the "
        "sign-in screen dismissal fix may not be working correctly"
    )
