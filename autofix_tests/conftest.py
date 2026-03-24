#!/usr/bin/env python3
"""Shared fixtures for autofix verification tests.

These tests run on the emulator (not physical device) to validate that
autofix PRs actually resolve screen agent navigation failures.
"""

import sys
import os

# Add this directory to path so helpers.py can be imported
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# Support CI via ANDROID_ACCESSIBILITY_TESTER_PATH env var, fallback to ~/android_accessibility_tester
_aat_path = os.environ.get(
    'ANDROID_ACCESSIBILITY_TESTER_PATH',
    os.path.expanduser('~/android_accessibility_tester')
)
sys.path.insert(0, _aat_path)

import android_accessibility_tester
import subprocess
import pytest
import time

from helpers import (
    EMULATOR_SERIAL, DEBUG_PACKAGE,
    enable_accessibility_service, login_if_needed,
    verify_required_apps,
)

ANDROID_HOME = os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, 'platform-tools')
os.environ['PATH'] = f"{PLATFORM_TOOLS}:{os.environ.get('PATH', '')}"
os.environ['ANDROID_HOME'] = ANDROID_HOME

# Default to emulator-5554 unless overridden
os.environ['ANDROID_SERIAL'] = EMULATOR_SERIAL
print(f"Targeting emulator: {EMULATOR_SERIAL}")


def load_anthropic_api_key():
    """Load ANTHROPIC_API_KEY from environment or export_anthropic_key.sh file."""
    if os.environ.get('ANTHROPIC_API_KEY'):
        print("ANTHROPIC_API_KEY already set in environment")
        return

    script_dir = os.path.dirname(os.path.abspath(__file__))
    key_file = os.path.join(script_dir, '..', 'export_anthropic_key.sh')

    if os.path.exists(key_file):
        with open(key_file, 'r') as f:
            for line in f:
                line = line.strip()
                if line.startswith('export ANTHROPIC_API_KEY='):
                    api_key = line.split('=', 1)[1].strip('"').strip("'")
                    os.environ['ANTHROPIC_API_KEY'] = api_key
                    print(f"Loaded ANTHROPIC_API_KEY from {key_file}")
                    return
        print(f"Could not find ANTHROPIC_API_KEY in {key_file}")
    else:
        print(f"API key file not found: {key_file}")

load_anthropic_api_key()


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

_logcat_process = None


@pytest.fixture(scope="session")
def app_installed():
    """Install the debug app and enable accessibility service on the emulator."""
    global _logcat_process

    # Create output directory
    output_dir = os.path.join(os.path.dirname(__file__), '..', 'autofix_test_output')
    import shutil
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir, exist_ok=True)

    # Verify snapshot loaded with required apps (e.g., WhatsApp)
    verify_required_apps()

    # Install using install.sh (handles permissions, signature mismatches, accessibility)
    project_dir = os.path.join(os.path.dirname(__file__), '..')
    apk_path = os.path.join(project_dir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
    if not os.path.exists(apk_path):
        print(f"APK not found at {apk_path}, building...")
        subprocess.run(
            [os.path.join(project_dir, 'gradlew'), 'assembleDebug', '--console=plain', '--quiet'],
            check=True, cwd=project_dir
        )
    subprocess.run(
        [os.path.join(project_dir, 'install.sh'), '--skip-build', '--force'],
        check=True, cwd=project_dir, env=os.environ
    )

    # Grant overlay permission (install.sh doesn't handle this)
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'appops', 'set', DEBUG_PACKAGE,
         'SYSTEM_ALERT_WINDOW', 'allow'],
        check=False
    )
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'pm', 'grant', DEBUG_PACKAGE,
         'android.permission.SYSTEM_ALERT_WINDOW'],
        check=False
    )

    # Enable accessibility service via adb settings (reliable on emulator)
    enable_accessibility_service()

    # Wake device and keep screen on
    print("Waking emulator and keeping screen on during tests...")
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=False)
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_MENU'], check=False)
    time.sleep(1)
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'settings', 'put', 'system',
         'screen_off_timeout', '2147483647'],
        check=False
    )
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'svc', 'power', 'stayon', 'true'],
        check=False
    )

    # Start logcat
    logcat_file = os.path.join(output_dir, 'autofix_logcat.log')
    subprocess.run(['adb', '-s', EMULATOR_SERIAL, 'logcat', '-c'], check=False)
    _logcat_process = subprocess.Popen(
        ['adb', '-s', EMULATOR_SERIAL, 'logcat', '-s',
         'WhizVoice:*', 'ScreenAgentTools:*', 'WhizAccessibilityService:*',
         'WhizRepository:*', 'ChatViewModel:*', 'WebSocketManager:*',
         'AuthRepository:*', 'AuthViewModel:*', 'GoogleSignIn:*',
         'Credentials:*', 'GmsClient:*', 'SignInClient:*', 'Auth:*'],
        stdout=open(logcat_file, 'w'),
        stderr=subprocess.STDOUT
    )

    yield

    # Cleanup
    print("Restoring emulator settings...")
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'settings', 'put', 'system',
         'screen_off_timeout', '30000'],
        check=False
    )
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'svc', 'power', 'stayon', 'false'],
        check=False
    )
    if _logcat_process:
        _logcat_process.terminate()
        _logcat_process.wait()


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    # Force stop the app to ensure a clean start
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'am', 'force-stop', DEBUG_PACKAGE],
        check=False
    )
    time.sleep(0.5)

    # Press home button
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'input', 'keyevent', 'KEYCODE_HOME'],
        check=False
    )
    time.sleep(0.5)

    tester = android_accessibility_tester.AndroidAccessibilityTester(
        device_id=EMULATOR_SERIAL
    )
    tester.open_app(DEBUG_PACKAGE)
    time.sleep(3)

    # Log in if needed
    login_if_needed(tester)

    # Enable accessibility service (in case app restart cleared it)
    enable_accessibility_service()

    yield tester
