#!/usr/bin/env python3
"""Placeholder autofix verification test.

This file gets overwritten by the autofix pipeline for each PR.
This placeholder test just verifies the test infrastructure works.
"""

import time


def test_autofix_infrastructure(tester):
    """Verify the autofix test infrastructure works on the emulator."""
    from helpers import navigate_to_my_chats, check_element_exists_in_ui

    # Navigate to My Chats page
    success, error = navigate_to_my_chats(tester, "autofix_infrastructure")
    assert success, f"Could not reach My Chats: {error}"

    # Verify we see the New Chat button (confirms app is working)
    has_new_chat = check_element_exists_in_ui(tester, content_desc="New Chat", wait_after_dump=2.0)
    assert has_new_chat, "Expected to see 'New Chat' button on My Chats page"
