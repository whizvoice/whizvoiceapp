#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_screenshot_testing')

import android_accessibility_tester
import subprocess
import os
import pytest


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True)


@pytest.fixture(scope="session")
def app_installed():
    """Install the debug app once for all tests."""
    install_debug_app(force=True)
    yield


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    tester = android_accessibility_tester.AndroidAccessibilityTester()
    tester.open_app("com.example.whiz.debug")
    yield tester
    # Add any per-test cleanup here if needed


# Example test - add your actual tests below
def test_app_opens(tester):
    """Test that the app opens successfully."""
    # Your test assertions here
    pass
