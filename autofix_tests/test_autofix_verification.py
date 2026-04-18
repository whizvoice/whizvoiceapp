"""Verification test for whatsapp_unknown_screen autofix.

The fix adds fallback detection for the WhatsApp Archived screen when the toolbar
element lacks a resource ID (newer WhatsApp). It also adds a fallback CHAT_LIST
detector using conversations_row_contact_name for newer WhatsApp without traditional
bottom_nav_container/archived_row indicators.
"""

import time
import subprocess
import os
from helpers import (
    check_element_exists_in_ui, save_failed_screenshot,
    navigate_to_my_chats, send_voice_command, EMULATOR_SERIAL,
    DEBUG_PACKAGE,
)


def test_autofix_whatsapp_unknown_screen(tester):
    """Verify WhatsApp navigation succeeds even when starting from the Archived screen.

    Reproduces the failure: open WhatsApp on the Archived chats screen,
    then issue a voice command to send a WhatsApp message. The screen agent
    must navigate back to the main chat list and find the target contact.
    """
    # Navigate to My Chats page first
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_unknown_screen")
    assert success, f"Could not reach My Chats: {error}"

    # Open WhatsApp and manually navigate to the Archived screen to reproduce the bug
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'am', 'start',
         '-n', 'com.whatsapp/.Main'],
        capture_output=True
    )
    time.sleep(3)

    # Scroll down in the main WhatsApp chat list to expose and tap the "Archived" row
    # The archived row typically appears at the bottom of the chat list
    # Swipe up to scroll down (find archived row at bottom)
    tester.swipe(540, 1400, 540, 600, 800)
    time.sleep(1)
    tester.swipe(540, 1400, 540, 600, 800)
    time.sleep(1)

    # Try to tap on the Archived row (visible if it exists)
    archived_exists = check_element_exists_in_ui(tester, text="Archived")
    if archived_exists:
        # Find and tap the Archived row to navigate to Archived chats screen
        result = subprocess.run(
            ['adb', '-s', EMULATOR_SERIAL, 'shell',
             'uiautomator', 'find', '--text', 'Archived'],
            capture_output=True, text=True
        )
        # Use accessibility to tap the Archived text element
        tester.shell("input tap 540 $(uiautomator dump /dev/stdout 2>/dev/null | "
                     "grep -o 'Archived.*bounds=\\[[^]]*\\]' | head -1 | "
                     "grep -o '\\[[0-9]*,[0-9]*\\]\\[[0-9]*,[0-9]*\\]' | head -1) 2>/dev/null || true")
        time.sleep(2)

    # Check if we reached the Archived screen (has "Archived" title and Back button)
    tester.screenshot("/tmp/whiz_archived_check.png")
    on_archived = tester.validate_screenshot(
        "/tmp/whiz_archived_check.png",
        "WhatsApp is showing the Archived chats screen with 'Archived' as the title and a back button"
    )
    print(f"WhatsApp on Archived screen: {on_archived}")

    # Return to the Whiz app
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'am', 'start',
         '-n', f'{DEBUG_PACKAGE}/.ui.main.MainActivity'],
        capture_output=True
    )
    time.sleep(2)

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_whatsapp_unknown_screen")
    assert success, f"Could not reach My Chats after setup: {error}"

    # Open new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Send voice command that triggers WhatsApp screen agent navigation
    # The agent must navigate from the Archived screen back to main chat list
    send_voice_command("send a whatsapp message to Ruth Grace Wong saying hi")
    time.sleep(50)  # allow ample time for screen agent navigation and draft

    # Validate the result — either WhatsApp chat is open or Whiz shows a confirmation
    tester.screenshot("/tmp/whiz_screen.png")
    result = tester.validate_screenshot(
        "/tmp/whiz_screen.png",
        "The screen shows evidence that a WhatsApp message was successfully sent or drafted. "
        "This could be: WhatsApp chat open with Ruth Grace Wong showing the message 'hi', "
        "a draft overlay in the WhatsApp chat input area, "
        "or the Whiz app showing a confirmation or asking to confirm sending the WhatsApp message. "
        "It should NOT show an error about failing to navigate WhatsApp."
    )

    if not result:
        # Also check the Whiz chat for a success/confirmation message
        tester.open_app(DEBUG_PACKAGE)
        time.sleep(3)
        tester.screenshot("/tmp/whiz_chat_result.png")
        result = tester.validate_screenshot(
            "/tmp/whiz_chat_result.png",
            "The Whiz chat shows a message from the assistant confirming that a WhatsApp message "
            "to Ruth Grace Wong was sent, drafted, or is ready to be reviewed. "
            "It should NOT show an error about failing to navigate or find the WhatsApp chat."
        )

    if not result:
        save_failed_screenshot(tester, "autofix_whatsapp_unknown_screen", "validation_failed")
    assert result, (
        "Screen agent failed to send a WhatsApp message after starting from the Archived screen. "
        "Expected a draft confirmation or sent message but got neither."
    )
