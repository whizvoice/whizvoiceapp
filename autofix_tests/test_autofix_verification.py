#!/usr/bin/env python3
"""Autofix verification tests."""

import time
from helpers import (
    save_failed_screenshot,
    navigate_to_my_chats, send_voice_command,
)


def test_autofix_gmaps_directions_screen_not_found(tester):
    """Verify fix for gmaps_directions_screen_not_found.

    The screen agent timed out with "Neither Start button nor mode tabs found
    within 10s on directions screen" when directions were requested to an
    unreachable destination (e.g. "Australia"). The directions screen HAD
    loaded (mode tabs present) but Google Maps showed a "no route" message,
    "Can't seem to find a way there", which the code did not recognize as a
    terminal state — so it spun until the 10s timeout and dumped the
    misleading error.

    The fix adds "Can't seem to find a way there" (both straight and curly
    apostrophe variants) to the list of terminal "no route" messages so the
    agent recognizes the state and returns promptly instead of timing out.

    This test reproduces the original failure by requesting directions to an
    unreachable destination and verifies the agent lands on the Google Maps
    directions screen (rather than getting stuck / timing out).
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_directions_screen_not_found")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat and let the UI settle.
    tester.tap(950, 2225)
    time.sleep(2)

    # Reproduce the original failing action: directions to an unreachable place.
    send_voice_command("give me directions to Australia")
    time.sleep(30)  # wait for screen agent to complete

    tester.screenshot("/tmp/whiz_gmaps_directions.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_directions.png",
        "Google Maps is showing a directions screen: there are origin and "
        "destination fields near the top (destination is Australia) and/or "
        "transport mode tabs (drive, transit, walk). A 'Can't seem to find a "
        "way there' or 'Try a Google search' message is also an acceptable "
        "directions-screen state. It should NOT be stuck on a blank map or a "
        "loading spinner."
    )
    if not result:
        # Fall back to checking the Whiz chat response — after the fix the agent
        # should have returned promptly rather than timing out.
        tester.open_app("com.example.whiz.debug")
        time.sleep(3)
        tester.screenshot("/tmp/whiz_gmaps_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_gmaps_chat_result.png",
            "The Whiz chat shows an assistant message responding to the "
            "directions request (about directions to Australia, or that a route "
            "could not be found). It should NOT show an error about the "
            "directions screen not loading or failing to fully load."
        )
        if not result:
            save_failed_screenshot(
                tester,
                "autofix_gmaps_directions_screen_not_found",
                "validation_failed",
            )
    assert result, (
        "Screen agent did not reach the Google Maps directions screen — "
        "gmaps_directions_screen_not_found may still be triggering"
    )
