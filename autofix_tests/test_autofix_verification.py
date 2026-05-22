#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_fitbit_food_tile_not_found(tester):
    """Verify fix for fitbit_food_tile_not_found.

    The Fitbit app was redesigned (now branded "Google Health") and the Today
    tab no longer carries a Food tile. The fix adds a fallback that taps the
    new "Health" bottom-nav tab and scrolls there before giving up. This test
    triggers the addFitbitQuickCalories screen-agent path by sending a voice
    command that asks the assistant to log calories, then checks that the
    agent advanced past the Today tab (i.e. did not stop at the
    fitbit_food_tile_not_found error).
    """
    success, error = navigate_to_my_chats(tester, "autofix_fitbit_food_tile_not_found")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("log 1500 calories in fitbit")
    time.sleep(45)

    tester.screenshot("/tmp/whiz_fitbit_food.png")
    on_fitbit_food = tester.validate_screenshot(
        "/tmp/whiz_fitbit_food.png",
        "The Fitbit / Google Health app is showing a food-related screen such "
        "as 'Food', 'Add Quick Calories', a calories entry field, a food log "
        "detail screen, or a food/nutrition tab"
    )

    if on_fitbit_food:
        return

    tester.open_app("com.example.whiz.debug")
    time.sleep(3)
    tester.screenshot("/tmp/whiz_fitbit_food_chat.png")
    chat_ok = tester.validate_screenshot(
        "/tmp/whiz_fitbit_food_chat.png",
        "The Whiz chat shows an assistant message about logging calories, food, "
        "or Fitbit. It must NOT show an error message containing 'Could not "
        "find Food tile on Fitbit Today screen'."
    )
    if not chat_ok:
        save_failed_screenshot(
            tester, "autofix_fitbit_food_tile_not_found", "validation_failed"
        )
    assert chat_ok, (
        "Screen agent did not advance past the Today tab — "
        "fitbit_food_tile_not_found appears to still be triggering"
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
