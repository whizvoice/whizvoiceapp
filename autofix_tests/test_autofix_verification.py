#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_gmaps_start_button_not_found(tester):
    """Verify fix for gmaps_start_button_not_found.

    When the user asks for directions without specifying a travel mode, Google
    Maps opens directions in its last-used mode. If that mode is Transit, there
    is no single "Start" button — Maps shows a transit route list instead. The
    screen agent previously failed with gmaps_start_button_not_found because
    selectTransportModeAndStart only handled the route list for an explicit
    "transit" mode. The fix treats the transit route list as a successful result
    when no explicit mode was requested.

    This test asks for directions with no mode and verifies Google Maps ends on
    a directions/route screen (any travel mode) rather than the tool reporting a
    Start-button failure.
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_start_button_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before sending a voice command.
    tester.tap(950, 2225)
    time.sleep(2)

    # Trigger the screen agent directions flow with NO explicit mode — this is the
    # exact user action that produced the original failure.
    send_voice_command("give me directions to 2238 Geary Street")
    time.sleep(35)  # wait for search + directions screen agent flow to complete

    tester.screenshot("/tmp/whiz_gmaps_directions.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_directions.png",
        "Google Maps is showing a directions/route screen for a trip. This is "
        "valid if you see ANY of: travel-mode tabs with time estimates (e.g. "
        "driving/transit/walking/cycling times like '15 min', '29 min'), a list "
        "of transit route options (buses/trains with departure times), a 'Start' "
        "button for turn-by-turn navigation, or a route drawn on the map with an "
        "origin and destination. Return True if a directions/route view is shown."
    )
    if not result:
        # Fall back to checking the Whiz chat did not report a directions failure.
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz chat shows an assistant message indicating directions were "
            "found or navigation started. It should NOT show an error about the "
            "directions screen not loading or a Start button not being found."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_gmaps_start_button_not_found", "validation_failed"
            )
    assert result, (
        "Screen agent did not produce a directions result for a no-mode request — "
        "gmaps_start_button_not_found may still be triggering"
    )
