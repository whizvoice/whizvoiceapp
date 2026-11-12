#!/usr/bin/env python3
"""Autofix verification tests."""

import time
import subprocess
import os
from helpers import (
    check_element_exists_in_ui, save_failed_screenshot,
    navigate_to_my_chats, send_voice_command, EMULATOR_SERIAL,
)


def test_autofix_ytmusic_no_action_menu(tester):
    """Verify fix for ytmusic_no_action_menu.

    Reproduces the failing sequence: play a song on YouTube Music (which leaves
    the expanded Now Playing page open), then queue another song. Newer YT Music
    versions keep the expanded player on top of the search deep link, hiding the
    result rows; the fix minimizes the player before looking for the action menu.
    """
    test_name = "autofix_ytmusic_no_action_menu"
    success, error = navigate_to_my_chats(tester, test_name)
    assert success, f"Could not reach My Chats: {error}"

    tester.tap(950, 2225)
    time.sleep(2)

    # Step 1: play a song, leaving the expanded Now Playing page up
    send_voice_command("Play Golden from Kpop Demon Hunters on YouTube Music.")
    time.sleep(35)

    # Step 2: queue another song — this is the action that failed
    send_voice_command("Queue How its Done by HUNTRIX on YouTube Music")
    time.sleep(35)

    # Validate the queue result in the Whiz chat
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)
    result = tester.check(
        "The Whiz chat shows an assistant reply confirming that a song (e.g. "
        "'How It's Done') was added to the queue on YouTube Music. It should NOT "
        "say it couldn't find the song, couldn't open the menu, or couldn't queue it.",
        screenshot_path="/tmp/whiz_ytmusic_queue_result.png",
    )
    if not result:
        save_failed_screenshot(tester, test_name, "validation_failed")
    assert result, (
        "Queueing on YouTube Music after playing a song did not succeed — "
        "ytmusic_no_action_menu may still be triggering"
    )
