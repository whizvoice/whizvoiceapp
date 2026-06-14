#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_app_not_found(tester):
    """Verify fix for app_not_found.

    The screen agent's launchApp() failed to resolve the spoken name
    "Google Maps" because the Google Maps app's display label is just
    "Maps": the fuzzy matcher only checked whether the app label contains
    the (longer) search term, and the common-mappings table only had the
    key "maps", not "google maps". Both returned no match -> app_not_found.

    The fix adds "google maps" -> com.google.android.apps.maps to both
    mapping tables and teaches calculateMatchScore to also match when the
    spoken name contains the app's label as a whole word. This test sends
    the exact voice command that triggered the failure ("open Google Maps")
    and verifies Google Maps actually launches.
    """
    success, error = navigate_to_my_chats(tester, "autofix_app_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle before issuing the voice command.
    tester.tap(950, 2225)
    time.sleep(2)

    # Same user action that caused the original failure.
    send_voice_command("open Google Maps")
    time.sleep(25)  # wait for the screen agent to resolve and launch the app

    tester.screenshot("/tmp/whiz_maps_launch.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_maps_launch.png",
        "The Google Maps app is open, showing a map, a search bar, location "
        "permission/onboarding prompt, or a 'You' / explore screen"
    )
    if not result:
        # Fall back to checking the Whiz chat for a non-error response so we can
        # distinguish 'Maps opened but vision missed it' from a real failure.
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_maps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_maps_chat_result.png",
            "The Whiz chat shows an assistant message about opening Google Maps "
            "or Maps. It should NOT show an error like 'Could not find an app "
            "matching Google Maps' or that the app could not be found."
        )
        if not result:
            save_failed_screenshot(tester, "autofix_app_not_found", "validation_failed")
    assert result, (
        "Google Maps did not launch from the spoken name 'Google Maps' — "
        "app_not_found may still be triggering"
    )
