#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_screenshot_testing')

import android_accessibility_tester
import subprocess
import os


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True)


if __name__ == "__main__":
    install_debug_app(force=True)

    tester = android_accessibility_tester.AndroidAccessibilityTester()
    tester.open_app("com.example.whiz.debug")
