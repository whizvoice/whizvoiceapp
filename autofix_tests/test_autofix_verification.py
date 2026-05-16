#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_gmaps_directions_screen_not_found(tester):
    """Verify fix for gmaps_directions_screen_not_found.

    Newer Google Maps versions sometimes do not show a "Start" button on the
    directions screen (e.g. very long routes). The original code waited 5
    attempts for either the Start button or transit mode tabs and then
    declared failure even though the trip was fully loaded. The fix also
    accepts the presence of trip_details_summary_header / details_cardlist as
    a success indicator. This test exercises the get-directions screen agent
    flow end-to-end via a voice command and asserts that Maps reaches the
    directions screen rather than failing back to chat with the
    "Directions screen did not fully load" error.
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_directions")
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("give me directions to 49 South Venice")
    time.sleep(35)

    tester.screenshot("/tmp/whiz_gmaps_directions.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_directions.png",
        "Google Maps is showing a directions/route screen for traveling to "
        "'49 S Venice' (or '49 South Venice'). The screen shows a route "
        "drawn on a map and/or a trip details panel with travel time and "
        "distance. Transportation mode tabs (Drive/Transit/Walk/Bike) may "
        "be visible. It is acceptable whether or not a 'Start' button is "
        "visible — the directions/route page itself is what matters."
    )
    if not result:
        save_failed_screenshot(
            tester, "autofix_gmaps_directions", "directions_validation_failed"
        )
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_directions_chat.png")
        chat_result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_directions_chat.png",
            "The Whiz chat shows an assistant response about successfully "
            "getting directions in Google Maps. It should NOT show an error "
            "about 'Directions screen did not fully load' or the Start "
            "button not being found."
        )
        if not chat_result:
            save_failed_screenshot(
                tester, "autofix_gmaps_directions", "chat_validation_failed"
            )
        assert chat_result, (
            "Screen agent failed to get directions — "
            "gmaps_directions_screen_not_found may still be triggering"
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
