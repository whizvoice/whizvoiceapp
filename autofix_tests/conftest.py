<<<<<<< Updated upstream
#!/usr/bin/env python3
"""Shared fixtures for autofix verification tests.

These tests run on the emulator (not physical device) to validate that
autofix PRs actually resolve screen agent navigation failures.
"""

import sys
import os

# Add this directory to path so helpers.py can be imported
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, '/Users/ruthgracewong/android_accessibility_tester')

import android_accessibility_tester
import subprocess
import pytest
import time

from helpers import (
    EMULATOR_SERIAL, DEBUG_PACKAGE,
    enable_accessibility_service, login_if_needed,
)

ANDROID_HOME = os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, 'platform-tools')
os.environ['PATH'] = f"{PLATFORM_TOOLS}:{os.environ.get('PATH', '')}"
os.environ['ANDROID_HOME'] = ANDROID_HOME

# Default to emulator-5556 unless overridden
os.environ['ANDROID_SERIAL'] = EMULATOR_SERIAL
print(f"Targeting emulator: {EMULATOR_SERIAL}")


def load_anthropic_api_key():
    """Load ANTHROPIC_API_KEY from export_anthropic_key.sh file."""
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

    # Install the debug app
    install_script = os.path.join(os.path.dirname(__file__), '..', 'install.sh')
    subprocess.run([install_script, '--force'], check=True, env=os.environ)

    # Grant overlay permission
    subprocess.run(
        ['adb', '-s', EMULATOR_SERIAL, 'shell', 'appops', 'set', DEBUG_PACKAGE,
         'SYSTEM_ALERT_WINDOW', 'allow'],
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
         'WhizRepository:*', 'ChatViewModel:*', 'WebSocketManager:*'],
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
=======
"""Pytest configuration for autofix verification tests (emulator-based)."""

import os
import subprocess
import sys
import time

import pytest

# Add the accessibility tester to the path
sys.path.insert(0, os.path.expanduser("~/android_accessibility_tester"))
import android_accessibility_tester

EMULATOR_SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5556")
ANDROID_HOME = os.environ.get(
    "ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"
)
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, "platform-tools")

# Ensure adb is on PATH
os.environ["PATH"] = f"{PLATFORM_TOOLS}:{os.environ.get('PATH', '')}"
os.environ["ANDROID_HOME"] = ANDROID_HOME
os.environ["ANDROID_SERIAL"] = EMULATOR_SERIAL


def _load_anthropic_api_key():
    """Load ANTHROPIC_API_KEY from export_anthropic_key.sh."""
    key_file = os.path.join(
        os.path.dirname(__file__), "..", "export_anthropic_key.sh"
    )
    if os.path.exists(key_file):
        with open(key_file) as f:
            for line in f:
                line = line.strip()
                if line.startswith("export ANTHROPIC_API_KEY="):
                    api_key = line.split("=", 1)[1].strip("\"'")
                    os.environ["ANTHROPIC_API_KEY"] = api_key
                    return


_load_anthropic_api_key()


@pytest.fixture(scope="session")
def emulator_ready():
    """Ensure the emulator is running and the screen is on."""
    # Wake device and keep screen on
    subprocess.run(
        ["adb", "shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False
    )
    subprocess.run(
        ["adb", "shell", "input", "keyevent", "KEYCODE_MENU"], check=False
    )
    time.sleep(1)
    subprocess.run(
        ["adb", "shell", "settings", "put", "system", "screen_off_timeout", "2147483647"],
        check=False,
    )
    subprocess.run(
        ["adb", "shell", "svc", "power", "stayon", "true"], check=False
    )

    # Install the debug app
    install_script = os.path.join(os.path.dirname(__file__), "..", "install.sh")
    if os.path.exists(install_script):
        subprocess.run([install_script], check=True, env=os.environ)
        # Grant overlay permission
        pkg = "com.example.whiz.debug"
        subprocess.run(
            ["adb", "shell", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"],
            check=False,
        )

    yield

    # Restore screen timeout
    subprocess.run(
        ["adb", "shell", "settings", "put", "system", "screen_off_timeout", "30000"],
        check=False,
    )
    subprocess.run(
        ["adb", "shell", "svc", "power", "stayon", "false"], check=False
    )


@pytest.fixture(scope="function")
def tester(emulator_ready):
    """Create a fresh tester instance with the Whiz app open."""
    # Force stop the app to ensure a clean start
    subprocess.run(
        ["adb", "shell", "am", "force-stop", "com.example.whiz.debug"],
        check=False,
    )
    time.sleep(0.5)

    # Press home for a clean starting state
    subprocess.run(
        ["adb", "shell", "input", "keyevent", "KEYCODE_HOME"], check=False
    )
    time.sleep(0.5)

    t = android_accessibility_tester.AndroidAccessibilityTester(
        device_id=EMULATOR_SERIAL
    )
    t.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Import helpers here to avoid circular imports
    from helpers import (
        check_element_exists_in_ui,
        enable_accessibility_service_if_needed,
        login_if_needed,
    )

    login_if_needed(t)
    enable_accessibility_service_if_needed(t)

    yield t
>>>>>>> Stashed changes
