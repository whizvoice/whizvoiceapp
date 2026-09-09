#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_gmaps_directions_button_not_found(tester):
    """Verify fix for gmaps_directions_button_not_found.

    Newer Google Maps place sheets render the Directions/Details/Save action row
    inside a Compose view, whose virtual nodes are not returned by
    findAccessibilityNodeInfosByText — the built-in text search came back with 0
    hits even though a "Directions" TextView was on screen. The fix adds a manual
    tree-traversal fallback so the Directions button is still found and clicked.
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_directions_button_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before sending the voice command
    tester.tap(950, 2225)
    time.sleep(2)

    send_voice_command("give me directions to the Ferry Building")
    time.sleep(45)  # wait for the screen agent to search, select and open directions

    tester.screenshot("/tmp/whiz_gmaps_directions.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_directions.png",
        "Google Maps is showing a directions/route screen: a route drawn on the map "
        "with travel time and distance, transport mode tabs (driving/transit/walking), "
        "and/or a Start button for navigation, or turn-by-turn navigation already running"
    )
    if not result:
        # The agent may have handed control back to Whiz after starting navigation —
        # check the chat transcript for the tool outcome instead.
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_directions_chat.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_directions_chat.png",
            "The Whiz chat shows an assistant message about directions or navigation "
            "to the Ferry Building. It should NOT show an error about not being able "
            "to find the Directions button in Google Maps."
        )
        if not result:
            save_failed_screenshot(
                tester, "autofix_gmaps_directions_button_not_found", "validation_failed"
            )
    assert result, (
        "Screen agent did not reach the Google Maps directions screen — "
        "gmaps_directions_button_not_found may still be triggering"
    )
