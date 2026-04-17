import time
import subprocess
import os
from helpers import (
    check_element_exists_in_ui, save_failed_screenshot,
    navigate_to_my_chats, send_voice_command, EMULATOR_SERIAL,
    check_screen_shows, TIMEOUT_MULTIPLIER,
)


def test_autofix_gmaps_no_nonsponsored_result(tester):
    """Verify fix for gmaps_no_nonsponsored_result.

    After clicking a search result card in newer Google Maps, the expandingscrollview_container
    expands to show location details while search_list_layout stays in the accessibility tree.
    The fix adds a check for expandingscrollview_container width before search_list_layout so
    the state is correctly classified as LOCATION_DETAILS instead of SEARCH_RESULTS_LIST.
    """
    success, error = navigate_to_my_chats(tester, "autofix_gmaps_no_nonsponsored_result")
    assert success, f"Could not reach My Chats: {error}"

    # Open a new chat
    tester.tap(950, 2225)
    time.sleep(2 * TIMEOUT_MULTIPLIER)

    # Send a voice command that triggers the Google Maps search screen agent
    send_voice_command("navigate to city hall")
    # Wait for the screen agent to navigate Google Maps and select a location
    time.sleep(40 * TIMEOUT_MULTIPLIER)

    tester.screenshot("/tmp/whiz_gmaps_result.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_gmaps_result.png",
        "Google Maps is showing location details for a City Hall (not just a search results list)"
    )
    if not result.result:
        save_failed_screenshot(tester, "autofix_gmaps_no_nonsponsored_result", "validation_failed")
    assert result.result, (
        "Screen agent did not successfully navigate to a City Hall location in Google Maps. "
        f"Details: {result.error}"
    )
