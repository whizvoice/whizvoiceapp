#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_ytmusic_app_not_ready(tester):
    """Verify fix for ytmusic_app_not_ready.

    Tests that the screen agent can detect YouTube Music as ready even when
    the Whiz bubble overlay or another window has the active-window slot.
    The fix adds a windows-list fallback to waitForAppReady so the target
    app is detected as soon as its window appears anywhere in the window list.
    """
    success, error = navigate_to_my_chats(tester, "autofix_ytmusic_app_not_ready")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("play Clean Bandit on YouTube Music")
    time.sleep(40)

    tester.screenshot("/tmp/whiz_ytmusic_play.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_ytmusic_play.png",
        "YouTube Music is open and showing search results, an artist/song page, "
        "a now-playing screen with playback controls, or a sign-in / onboarding "
        "screen with options to sign in or browse device files"
    )
    if not result:
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_ytmusic_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_ytmusic_chat_result.png",
            "The Whiz chat shows an assistant message about playing music or about "
            "YouTube Music. It should NOT show an error about YouTube Music not "
            "becoming ready in time."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_ytmusic_app_not_ready", "validation_failed")
    assert result, (
        "YouTube Music did not reach a ready state after deep link launch — "
        "ytmusic_app_not_ready may still be triggering"
    )
