#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_screenshot_testing')

import android_accessibility_tester
import subprocess
import os
import pytest


# Set up Android SDK paths
ANDROID_HOME = os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')
PLATFORM_TOOLS = os.path.join(ANDROID_HOME, 'platform-tools')

# Add platform-tools to PATH for all subprocess calls
os.environ['PATH'] = f"{PLATFORM_TOOLS}:{os.environ.get('PATH', '')}"
os.environ['ANDROID_HOME'] = ANDROID_HOME

# Load Anthropic API key from export_anthropic_key.sh
def load_anthropic_api_key():
    """Load ANTHROPIC_API_KEY from export_anthropic_key.sh file."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    key_file = os.path.join(script_dir, 'export_anthropic_key.sh')

    if os.path.exists(key_file):
        # Read the file and extract the API key
        with open(key_file, 'r') as f:
            for line in f:
                line = line.strip()
                if line.startswith('export ANTHROPIC_API_KEY='):
                    # Extract the value after the =
                    api_key = line.split('=', 1)[1]
                    # Remove quotes if present
                    api_key = api_key.strip('"').strip("'")
                    os.environ['ANTHROPIC_API_KEY'] = api_key
                    print(f"✅ Loaded ANTHROPIC_API_KEY from {key_file}")
                    return
        print(f"⚠️  Could not find ANTHROPIC_API_KEY in {key_file}")
    else:
        print(f"⚠️  API key file not found: {key_file}")
        print("   Please create export_anthropic_key.sh with: export ANTHROPIC_API_KEY='your-key-here'")

# Load the API key when the module is imported
load_anthropic_api_key()


# Global variable to store logcat process
_logcat_process = None


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True)

    # Grant overlay permission using both pm grant and appops
    package_name = "com.example.whiz.debug"
    subprocess.run(['adb', 'shell', 'appops', 'set', package_name, 'SYSTEM_ALERT_WINDOW', 'allow'], check=False)
    subprocess.run(['adb', 'shell', 'pm', 'grant', package_name, 'android.permission.SYSTEM_ALERT_WINDOW'], check=False)


def clear_test_output_dir():
    """Clear the screen agent test output directory."""
    import shutil

    output_dir = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')
    if os.path.exists(output_dir):
        shutil.rmtree(output_dir)
    os.makedirs(output_dir, exist_ok=True)


def start_logcat():
    """Start logcat and return the process."""
    global _logcat_process

    # Create output directory if it doesn't exist
    output_dir = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')
    os.makedirs(output_dir, exist_ok=True)

    # Clear logcat buffer
    subprocess.run(['adb', 'logcat', '-c'], check=False)

    # Start logcat and capture output
    logcat_file = os.path.join(output_dir, 'screen_agent_logcat.log')
    _logcat_process = subprocess.Popen(
        ['adb', 'logcat'],
        stdout=open(logcat_file, 'w'),
        stderr=subprocess.STDOUT
    )
    return _logcat_process


def stop_logcat():
    """Stop the logcat process."""
    global _logcat_process
    if _logcat_process:
        _logcat_process.terminate()
        _logcat_process.wait()
        _logcat_process = None


def save_failed_screenshot(screenshot_path, test_name, step_name):
    """Save a screenshot and UI dump to the test output directory when validation fails."""
    import shutil

    output_dir = os.path.join(os.path.dirname(__file__), 'screen_agent_test_output')
    os.makedirs(output_dir, exist_ok=True)

    # Create a descriptive filename
    timestamp = subprocess.run(
        ['date', '+%Y%m%d_%H%M%S'],
        capture_output=True,
        text=True,
        check=True
    ).stdout.strip()

    # Save screenshot
    screenshot_filename = f"{test_name}_{step_name}_{timestamp}.png"
    screenshot_dest = os.path.join(output_dir, screenshot_filename)
    shutil.copy(screenshot_path, screenshot_dest)
    print(f"📸 Saved failed screenshot to: {screenshot_dest}")

    # Save UI dump
    ui_dump_filename = f"{test_name}_{step_name}_{timestamp}_uidump.xml"
    ui_dump_dest = os.path.join(output_dir, ui_dump_filename)

    # Dump UI hierarchy to device first, then pull it
    device_dump_path = '/sdcard/window_dump.xml'
    dump_result = subprocess.run(
        ['adb', 'shell', 'uiautomator', 'dump', device_dump_path],
        capture_output=True,
        text=True
    )

    if dump_result.returncode == 0:
        # Pull the file from device
        pull_result = subprocess.run(
            ['adb', 'pull', device_dump_path, ui_dump_dest],
            capture_output=True,
            text=True
        )

        if pull_result.returncode == 0:
            print(f"🔍 Saved UI dump to: {ui_dump_dest}")
            # Clean up the file from device
            subprocess.run(['adb', 'shell', 'rm', device_dump_path], check=False)
        else:
            print(f"⚠️  Failed to pull UI dump: {pull_result.stderr}")
    else:
        print(f"⚠️  Failed to dump UI: {dump_result.stderr}")


def check_on_new_chat_screen(tester):
    """Check if we're on the New Chat screen using UI hierarchy.

    Returns:
        bool: True if on New Chat screen, False otherwise
    """
    return check_element_exists_in_ui(tester, text="New Chat", wait_after_dump=2.0)


def navigate_to_my_chats(tester, test_name="unknown"):
    """Navigate to the My Chats page by pressing back until we reach it.

    Args:
        tester: The AndroidAccessibilityTester instance
        test_name: Name of the test calling this function, for screenshot naming
        use_ui_check: If True, use UI dump to check (causes accessibility dialog flicker).
                     If False, just press back a few times without validation (faster, no API cost)

    Returns:
        tuple: (success: bool, error_message: str)
    """
    import time

    # UI check approach (causes accessibility dialog flicker)
    max_attempts = 5
    for attempt in range(max_attempts):
        if check_element_exists_in_ui(tester, text="My Chats", wait_after_dump=2.0):
            print(f"✅ Found My Chats screen on attempt {attempt + 1}")
            return (True, "")

        print(f"🔙 My Chats not found, pressing back (attempt {attempt + 1}/{max_attempts})")

        # Save screenshot and UI dump on each failed attempt for debugging
        screenshot_path = "/tmp/whiz_screen.png"
        tester.screenshot(screenshot_path)
        save_failed_screenshot(screenshot_path, test_name, f"navigate_to_my_chats_attempt_{attempt + 1}")

        tester.press_back()
        time.sleep(1)

    # Failed to reach My Chats after all attempts
    screenshot_path = "/tmp/whiz_screen.png"
    tester.screenshot(screenshot_path)
    save_failed_screenshot(screenshot_path, test_name, f"navigate_to_my_chats_failed_after_{max_attempts}_attempts")
    error_msg = f"Failed to reach My Chats page after {max_attempts} attempts. Screenshot saved to screen_agent_test_output directory."
    return (False, error_msg)


def get_device_model():
    """Get the device model name."""
    result = subprocess.run(
        ['adb', 'shell', 'getprop', 'ro.product.model'],
        capture_output=True,
        text=True,
        check=True
    )
    return result.stdout.strip()


def check_element_exists_in_ui(tester, content_desc=None, text=None, wait_after_dump=0.5):
    """Check if an element exists in the UI hierarchy without using API.

    Args:
        tester: AndroidAccessibilityTester instance
        content_desc: Content description to search for (optional)
        text: Text content to search for (optional)
        wait_after_dump: Seconds to wait after UI dump to let accessibility service reconnect (default 0.5)

    Returns:
        bool: True if element found, False otherwise
    """
    import xml.etree.ElementTree as ET
    import time

    try:
        # Get UI hierarchy XML
        device_path = "/sdcard/ui_hierarchy_check.xml"
        tester.shell(f"uiautomator dump {device_path}")

        # Wait a bit for accessibility service to reconnect after uiautomator
        # (uiautomator temporarily disconnects accessibility services)
        if wait_after_dump > 0:
            time.sleep(wait_after_dump)
            # Take a second dump after accessibility service reconnects
            tester.shell(f"uiautomator dump {device_path}")

        # Pull to local
        local_path = "/tmp/ui_hierarchy_check.xml"
        subprocess.run(['adb', 'pull', device_path, local_path],
                      capture_output=True, check=True)

        # Parse XML
        with open(local_path, 'r') as f:
            xml_content = f.read()
        root = ET.fromstring(xml_content)

        # Search for element
        for node in root.iter():
            if content_desc and node.get('content-desc') == content_desc:
                return True
            if text and node.get('text') == text:
                return True

        return False
    except Exception as e:
        print(f"⚠️  Error checking UI hierarchy: {e}")
        return False


def enable_accessibility_service_if_needed(tester):
    """Enable accessibility service if the dialog is showing."""
    import time

    # Check if accessibility dialog is showing by looking for the title element
    dialog_showing = check_element_exists_in_ui(
        tester,
        content_desc="Enable accessibility service title"
    )

    if dialog_showing:
        device_model = get_device_model()
        print(f"📱 Device model: {device_model}")

        if device_model == "Pixel 8":
            # Pixel 8 specific tap coordinates
            # Click "Open Settings" button
            tester.tap(700, 1725)
            time.sleep(2)

            # Click to select WhizVoice DEBUG
            tester.tap(500, 1000)
            time.sleep(1)

            # Click toggle to enable WhizVoice DEBUG
            tester.tap(925, 400)
            time.sleep(1)

            # Click Allow button
            tester.tap(500, 1650)
            time.sleep(1)

            # Press back twice to return to app
            tester.press_back()
            time.sleep(0.5)
            tester.press_back()
            time.sleep(1)
        elif device_model == "Pixel 7a":
            # Pixel 7a specific tap coordinates
            # Click "Open Settings" button
            tester.tap(700, 1725)
            time.sleep(2)

            # Click to select WhizVoice DEBUG
            tester.tap(500, 500)
            time.sleep(1)

            # Click toggle to enable WhizVoice DEBUG
            tester.tap(925, 600)
            time.sleep(1)

            # Click Allow button
            tester.tap(500, 1650)
            time.sleep(1)

            # Press back twice to return to app
            tester.press_back()
            time.sleep(0.5)
            tester.press_back()
            time.sleep(1)
        else:
            print(f"⚠️  Unknown device model: {device_model}")
            print(f"⚠️  Accessibility service needs to be enabled manually")
            print(f"⚠️  Or add tap coordinates for this device model to enable_accessibility_service_if_needed()")
            # You could add elif blocks here for other device models with their specific coordinates


def login_if_needed(tester):
    """Log in to the app if we're on the login screen."""
    import time

    print("\n========================================")
    print("LOGIN CHECK: Checking if login is needed")
    print("========================================")

    # Check if we're on the login screen by looking for "Sign in with Google" button
    is_login_screen = check_element_exists_in_ui(
        tester,
        text="Sign in with Google",
        wait_after_dump=2.0
    )

    if is_login_screen:
        print("🔐 Login screen detected, proceeding with login...")

        # Click "Sign in with Google" button
        tester.tap(500, 1450)
        time.sleep(2)

        # Click to log in as REDACTED_TEST_EMAIL
        tester.tap(500, 1300)
        time.sleep(3)

        # Verify login succeeded by checking for either My Chats page or accessibility dialog
        reached_my_chats = check_element_exists_in_ui(
            tester,
            text="My Chats",
            wait_after_dump=2.0
        )

        on_accessibility_dialog = check_element_exists_in_ui(
            tester,
            text="Enable Accessibility Service",
            wait_after_dump=2.0
        )

        if not reached_my_chats and not on_accessibility_dialog:
            screenshot_path = "/tmp/whiz_screen.png"
            tester.screenshot(screenshot_path)
            save_failed_screenshot(screenshot_path, "login_if_needed", "failed_after_login")
            assert False, "Failed to log in - expected My Chats page or accessibility dialog. Screenshot saved to screen_agent_test_output directory."

        if reached_my_chats:
            print("✅ Successfully logged in and reached My Chats page")
        else:
            print("✅ Successfully logged in (accessibility dialog shown)")
    else:
        print("✅ Already logged in, skipping login flow")


@pytest.fixture(scope="session")
def app_installed():
    """Install the debug app once for all tests."""
    clear_test_output_dir()
    install_debug_app(force=True)
    start_logcat()
    yield
    stop_logcat()


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    import time

    tester = android_accessibility_tester.AndroidAccessibilityTester()
    tester.open_app("com.example.whiz.debug")

    # Give the app time to fully launch
    time.sleep(3)

    # Log in if needed (do this first)
    login_if_needed(tester)

    # Enable accessibility service after login
    enable_accessibility_service_if_needed(tester)

    yield tester
    # Add any per-test cleanup here if needed


# Example test - add your actual tests below
def test_whatsapp_draft_message(tester):
    """Test that we can draft and modify draft and send message in WhatsApp."""
    import time

    print("\n========================================")
    print("STEP 1: Opening WhizVoice Debug app")
    print("========================================")
    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    print("\n========================================")
    print("STEP 2: Navigating to My Chats page")
    print("========================================")
    # Navigate to My Chats page
    success, error_msg = navigate_to_my_chats(tester, "whatsapp_draft_message")
    assert success, error_msg
    print("✅ Successfully navigated to My Chats page")

    print("\n========================================")
    print("STEP 3: Opening new chat")
    print("========================================")
    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    print("\n========================================")
    print("STEP 4: Validating New Chat screen")
    print("========================================")
    # Validate we are on the New Chat screen
    validation_result = check_on_new_chat_screen(tester)
    if not validation_result:
        print("❌ New Chat screen validation failed!")
        screenshot_path_temp = "/tmp/whiz_screen.png"
        tester.screenshot(screenshot_path_temp)
        save_failed_screenshot(screenshot_path_temp, "whatsapp_draft_message", "new_chat_screen")
    else:
        print("✅ Successfully validated New Chat screen")
    assert validation_result, "Failed to reach New Chat screen"

    print("\n========================================")
    print("STEP 5: Sending WhatsApp draft request")
    print("========================================")
    # Send a voice transcription with the test message
    # Note: The app is in continuous listening mode by default (voice app behavior),
    # so we use the TEST_TRANSCRIPTION broadcast instead of keyboard input
    print("📤 Broadcasting: 'Hello, can you please send a message to +1(628)209-9005 that says hey whats up hows it going just tryna test whiz voice'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Hello, can you please send a message to +1(628)209-9005 that says hey whats up hows it going just tryna test whiz voice"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 3 seconds for message to be processed...")
    time.sleep(3)  # Give time for message to be processed

    print("\n========================================")
    print("STEP 6: Waiting for draft overlay to appear")
    print("========================================")
    # wait for draft overlay to appear over whatsapp input text bar
    print("👀 Waiting for yellow overlay at pixel (300, 1380) with color #fffad0...")
    result = tester.wait_for_pixel_color(300, 1380, (255, 250, 208), timeout=15.0)  # #fffad0
    if result['matched']:
        print("✅ Draft overlay detected!")
    else:
        print("❌ Draft overlay not detected!")
    assert result['matched'], f"Failed to detect draft overlay: {result.get('error')}"

    print("\n========================================")
    print("STEP 7: Validating WhatsApp draft message")
    print("========================================")
    # Validate WhatsApp is open with the draft message
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "It's OK if the contact is a self-message with '(You)' at the end of the contact name. "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'hey whats up hows it going just tryna test whiz voice'. "
        "There is also a yellow notification bubble with the outline of a robot head. "
        "There may or may not be an icon inside the robot head outline. "
    )
    if not validation_result:
        print("❌ WhatsApp draft message validation failed!")
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_message_validation")
    else:
        print("✅ WhatsApp draft message validated successfully!")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    print("\n========================================")
    print("STEP 8: Requesting to modify the message")
    print("========================================")
    # Send a voice transcription to modify the message
    print("📤 Broadcasting: 'Actually, can you make the message more polite?'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Actually, can you make the message more polite?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 10 seconds for message modification...")
    time.sleep(10)

    print("\n========================================")
    print("STEP 9: Validating draft message was updated")
    print("========================================")
    # Validate that the draft was updated
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "It's OK if the contact is a self-message with '(You)' at the end of the contact name. "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'just trying to test whiz voice' but may not be an exact match. "
        "The Yellow overlay should have some text in red strike out and some text in blue. "
        "There is also a yellow notification bubble with the outline of a robot head "
        "and a microphone icon inside."
    )
    if not validation_result:
        print("❌ Draft update validation failed!")
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_updated_validation")
    else:
        print("✅ Draft message successfully updated with strikethrough and new text!")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    print("\n========================================")
    print("STEP 10: Sending the WhatsApp message")
    print("========================================")
    # Send a voice transcription to send the message
    print("📤 Broadcasting: 'That looks good, go ahead and send the message.'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"That looks good, go ahead and send the message."',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 15 seconds for message to be sent...")
    time.sleep(15)

    print("\n========================================")
    print("STEP 11: Validating message was sent")
    print("========================================")
    # Validate that the message was sent
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "It's OK if the contact is a self-message with '(You)' at the end of the contact name. "
        "At the bottom of the screen, there is NO yellow overlay. "
        "The most recent rescue mission is something with text similar to: "
        "just trying to test WhizVoice. Though the message may not be exactly the same. "
        "There is also a yellow notification bubble with the outline of a robot head "
        "and a microphone icon inside."
    )
    if not validation_result:
        print("❌ Message sent validation failed!")
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "message_sent_validation")
    else:
        print("✅ WhatsApp message successfully sent!")
    assert validation_result, "Failed to send WhatsApp message correctly"

    print("\n========================================")
    print("STEP 12: Cleaning up - Deleting sent message")
    print("========================================")
    # Cleanup: Delete the sent message
    # Long press on the newly sent message
    print("🖱️  Long pressing on message at (500, 1280)...")
    tester.long_press(500, 1280)
    time.sleep(2)

    # Click delete button
    print("🗑️  Tapping delete button at (800, 200)...")
    tester.tap(800, 200)
    time.sleep(2)

    # Click confirm delete
    print("✔️  Confirming delete at (750, 1290)...")
    tester.tap(750, 1290)
    time.sleep(2)

    print("\n========================================")
    print("STEP 13: Validating message was deleted")
    print("========================================")
    # Validate that the message was deleted
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "It's OK if the contact is a self-message with '(You)' at the end of the contact name. "
        "The most recent message in the chat has been deleted."
    )
    if not validation_result:
        print("❌ Message deletion validation failed!")
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "message_deleted_validation")
    else:
        print("✅ WhatsApp message successfully deleted!")
    assert validation_result, "Failed to delete the sent message"

    print("\n========================================")
    print("🎉 TEST COMPLETED SUCCESSFULLY!")
    print("========================================")


def test_youtube_music_integration(tester):
    """Test that we can play and queue songs on YouTube Music."""
    import time

    print("\n========================================")
    print("STEP 1: Opening WhizVoice Debug app")
    print("========================================")
    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    print("\n========================================")
    print("STEP 2: Navigating to My Chats page")
    print("========================================")
    # Navigate to My Chats page
    success, error_msg = navigate_to_my_chats(tester, "youtube_music_integration")
    assert success, error_msg
    print("✅ Successfully navigated to My Chats page")

    print("\n========================================")
    print("STEP 3: Opening new chat")
    print("========================================")
    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    print("\n========================================")
    print("STEP 4: Validating New Chat screen")
    print("========================================")
    # Validate we are on the New Chat screen
    validation_result = check_on_new_chat_screen(tester)
    if not validation_result:
        print("❌ New Chat screen validation failed!")
        screenshot_path_temp = "/tmp/whiz_screen.png"
        tester.screenshot(screenshot_path_temp)
        save_failed_screenshot(screenshot_path_temp, "youtube_music", "new_chat_screen")
    else:
        print("✅ Successfully validated New Chat screen")
    assert validation_result, "Failed to reach New Chat screen"

    print("\n========================================")
    print("STEP 5: Requesting to play song on YouTube Music")
    print("========================================")
    # Send a voice transcription to play songs on YouTube Music
    print("📤 Broadcasting: 'Hey can you play Golden from Kpop Demon Hunters on YouTube Music?'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Hey can you play Golden from Kpop Demon Hunters on YouTube Music?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 3 seconds for message to be processed...")
    time.sleep(3)  # Give time for message to be processed

    print("\n========================================")
    print("STEP 6: Waiting for YouTube Music to open and play song")
    print("========================================")
    # Wait for YouTube Music to open and song to start playing
    # The bot should launch YouTube Music, search for the song, and play it
    print("⏳ Waiting 15 seconds for YouTube Music to open and play the song...")
    time.sleep(15)

    print("\n========================================")
    print("STEP 7: Validating song is playing")
    print("========================================")
    # Validate YouTube Music is open and showing the song
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "YouTube Music app is open and showing the song 'Golden'."
        "The song should be the one selected, so it should be showing on the bottom of the screen. "
        "The song may be actively playing, or it may be paused. "
        "There may be a yellow notification bubble with a robot head icon visible on the screen."
    )
    if not validation_result:
        print("❌ Song playing validation failed!")
        save_failed_screenshot(screenshot_path, "youtube_music", "song_playing_validation")
    else:
        print("✅ YouTube Music opened and playing 'Golden' successfully!")
    assert validation_result, "Failed to open YouTube Music and play song"

    print("\n========================================")
    print("STEP 8: Requesting to queue second song")
    print("========================================")
    # Send a voice transcription to queue up "How it's Done" by HUNTRIX
    print("📤 Broadcasting: 'Can you queue up How it's Done by HUNTRIX?'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Can you queue up How it\'s Done by HUNTRIX?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    print("\n========================================")
    print("STEP 9: Waiting for song to be queued")
    print("========================================")
    # Wait 20 seconds for queueing to complete
    print("⏳ Waiting 20 seconds for song to be added to queue...")
    time.sleep(20)

    print("\n========================================")
    print("STEP 10: Opening queue view")
    print("========================================")
    # Tap to full screen the current song
    print("🖱️  Tapping to fullscreen current song at (500, 2100)...")
    subprocess.run(['adb', 'shell', 'input', 'tap', '500', '2100'], check=True)
    time.sleep(1)

    # Tap to see what's up next
    print("🖱️  Tapping to see queue at (300, 2200)...")
    subprocess.run(['adb', 'shell', 'input', 'tap', '300', '2200'], check=True)
    print("⏳ Waiting 2 seconds for queue to appear...")
    time.sleep(2)  # Wait for queue to appear

    print("\n========================================")
    print("STEP 11: Validating song queue")
    print("========================================")
    # Screenshot and validate that it shows the queue with Golden first and How It's Done second
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The screen shows a song queue with 'Golden' as the first song and 'How It's Done' as the second song in the queue."
    )
    if not validation_result:
        print("❌ Queue validation failed!")
        save_failed_screenshot(screenshot_path, "youtube_music", "queue_validation")
    else:
        print("✅ Queue validated successfully with 'Golden' first and 'How It's Done' second!")
    assert validation_result, "Failed to validate queue with Golden first and How It's Done second"

    print("\n========================================")
    print("🎉 TEST COMPLETED SUCCESSFULLY!")
    print("========================================")

def test_google_maps_directions(tester):
    """Test that we can get directions to multiple locations using Google Maps."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    success, error_msg = navigate_to_my_chats(tester, "google_maps_directions")
    assert success, error_msg

    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Validate we are on the New Chat screen
    tester.screenshot(screenshot_path)
    validation_result = check_on_new_chat_screen(tester)
    if not validation_result:
        screenshot_path_temp = "/tmp/whiz_screen.png"
        tester.screenshot(screenshot_path_temp)
        save_failed_screenshot(screenshot_path_temp, "google_maps_directions", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"

    # Send a voice transcription to ask for directions to Trader Joe's
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"what are the trader joes near me ?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # Wait 15 seconds for the search to complete and "See locations" to appear
    time.sleep(30)

    # Validate that Google Maps is showing the "See locations" list for Trader Joe's
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Google Maps is open and showing more than one Trader Joe's locations. "
        "The screen should show more than one Trader Joe's results with addresses at least partially visible."
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "google_maps_directions", "trader_joes_see_locations")
    assert validation_result, "Failed to show Trader Joe's location list"

    # Send a voice transcription to select the one on Fulton Street
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Can you give me directions to the one on Fulton Street"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    # Wait for the location to be selected and directions to appear
    time.sleep(30)

    # Validate that Google Maps is showing directions or navigation screen
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Google Maps is open and showing the navigation screen for a route. "
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "google_maps_directions", "trader_joes_directions")
    assert validation_result, "Failed to show Trader Joe's directions"

    # Send a voice transcription to change destination to office at 1885 Mission Street
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Actually, I need to go to my office first at 1885 Mission Street. Can you get directions to there instead?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # Wait 15 seconds for the new location to be searched
    time.sleep(15)

    # Validate that Google Maps is showing the directions for 1885 Mission Street
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Google Maps is open and showing the navigation screen for a route. "
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "google_maps_directions", "mission_street_search")
    assert validation_result, "Failed to show 1885 Mission Street search results"

    # Send a voice transcription to request driving directions specifically
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Actually, just this time can you get driving directions for me?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # Wait for driving directions to be displayed
    time.sleep(20)

    # Validate that Google Maps is showing driving directions to 1885 Mission Street
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Google Maps is open and showing the navigation screen for a route. "
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "google_maps_directions", "mission_street_driving_directions")
    assert validation_result, "Failed to show driving directions to 1885 Mission Street"

    # Bring WhizVoice Debug app to foreground by using monkey to resume the app
    # This brings the app to foreground without starting a new activity
    subprocess.run([
        'adb', 'shell',
        'monkey', '-p', 'com.example.whiz.debug', '-c', 'android.intent.category.LAUNCHER', '1'
    ], check=True, capture_output=True)
    time.sleep(2)  # Give time for app to come to foreground

    # Take screenshot of WhizVoice app showing chat
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The WhizVoice chat screen is showing, and the most recent assistant message mentions the address '1885 Mission Street' or '1885 Mission St' in San Francisco"
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "google_maps_directions", "whizvoice_mission_address_confirmation")
    assert validation_result, "Assistant did not mention the 1885 Mission Street address in the chat"


def test_sms_draft_message(tester):
    """Test that we can draft, modify draft, and send SMS messages."""
    import time

    print("\n========================================")
    print("STEP 1: Opening WhizVoice Debug app")
    print("========================================")
    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    print("\n========================================")
    print("STEP 2: Navigating to My Chats page")
    print("========================================")
    # Navigate to My Chats page
    success, error_msg = navigate_to_my_chats(tester, "sms_draft_message")
    assert success, error_msg
    print("✅ Successfully navigated to My Chats page")

    print("\n========================================")
    print("STEP 3: Opening new chat")
    print("========================================")
    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    print("\n========================================")
    print("STEP 4: Validating New Chat screen")
    print("========================================")
    # Validate we are on the New Chat screen
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The screen shows a 'New Chat' page where users can start a new conversation"
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "sms_draft_message", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"
    print("✅ Successfully validated New Chat screen")

    print("\n========================================")
    print("STEP 5: Sending SMS draft request")
    print("========================================")
    # Send a voice transcription with the test message to send an SMS
    # Note: The app is in continuous listening mode by default (voice app behavior),
    # so we use the TEST_TRANSCRIPTION broadcast instead of keyboard input
    print("📤 Broadcasting: 'Hello, can you please send a text message to +1(628)209-9005 that says hey just testing SMS from whiz voice'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Hello, can you please send a text message to +1(628)209-9005 that says hey just testing SMS from whiz voice"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 3 seconds for message to be processed...")
    time.sleep(3)  # Give time for message to be processed

    print("\n========================================")
    print("STEP 6: Waiting for draft overlay to appear")
    print("========================================")
    # wait for draft overlay to appear over SMS input text bar
    print("👀 Waiting for yellow overlay at pixel (300, 1380) with color #fffad0...")
    result = tester.wait_for_pixel_color(300, 1380, (255, 250, 208), timeout=20.0)  # #fffad0

    # If overlay detection failed, capture diagnostics before asserting
    if not result['matched']:
        print("❌ Draft overlay not detected!")
        tester.screenshot(screenshot_path)
        save_failed_screenshot(screenshot_path, "sms_draft_message", "draft_overlay_not_detected")
    else:
        print("✅ Draft overlay detected!")

    assert result['matched'], f"Failed to detect draft overlay: {result.get('error')}"

    print("\n========================================")
    print("STEP 7: Validating SMS draft message")
    print("========================================")
    # Validate Messages app is open with the draft message
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Messages app (Google Messages or SMS app) is open showing a conversation with the contact +1(628)209-9005 or '(628) 209-9005'  or Ruth Wong or Ruth Grace Wong. "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'hey testing SMS from whiz voice'. "
        "There is also a yellow notification bubble with the outline of a robot head. "
        "There may or may not be an icon inside the robot head outline. "
    )
    if not validation_result:
        print("❌ Draft message validation failed!")
        save_failed_screenshot(screenshot_path, "sms_draft_message", "draft_message_validation")
    else:
        print("✅ SMS draft message validated successfully!")
    assert validation_result, "Failed to draft SMS message correctly"

    print("\n========================================")
    print("STEP 8: Requesting to modify the message")
    print("========================================")
    # Send a voice transcription to modify the message
    print("📤 Broadcasting: 'Actually, can you make the message more polite?'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Actually, can you make the message more polite?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 10 seconds for message modification...")
    time.sleep(10)

    print("\n========================================")
    print("STEP 9: Validating draft message was updated")
    print("========================================")
    # Validate that the draft was updated
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Messages app (Google Messages or SMS app) is open showing a conversation with the contact +1(628)209-9005 or '(628) 209-9005' or Ruth Wong or Ruth Grace Wong. "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'just testing SMS'. "
        "The Yellow overlay should have some text in red strike out and some text in blue. "
        "There is also a yellow notification bubble with the outline of a robot head. "
        "There may or may not be an icon inside the robot head outline. "
    )
    if not validation_result:
        print("❌ Draft update validation failed!")
        save_failed_screenshot(screenshot_path, "sms_draft_message", "draft_updated_validation")
    else:
        print("✅ Draft message successfully updated with strikethrough and new text!")
    assert validation_result, "Failed to update SMS draft message correctly"

    print("\n========================================")
    print("STEP 10: Sending the SMS message")
    print("========================================")
    # Send a voice transcription to send the message
    print("📤 Broadcasting: 'That looks good, go ahead and send the text.'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"That looks good, go ahead and send the text."',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 15 seconds for message to be sent...")
    time.sleep(15)

    print("\n========================================")
    print("STEP 11: Validating message was sent")
    print("========================================")
    # Validate that the message was sent
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Messages app (Google Messages or SMS app) is open showing a conversation with the contact +1(628)209-9005 or '(628) 209-9005' or Ruth Wong or Ruth Grace Wong. "
        "At the bottom of the screen, there is NO yellow overlay. "
        "The most recent message is something with text similar to 'just testing SMS'. "
        "There is also a yellow notification bubble with the outline of a robot head. "
        "There may or may not be an icon inside the robot head outline. "
    )
    if not validation_result:
        print("❌ Message sent validation failed!")
        save_failed_screenshot(screenshot_path, "sms_draft_message", "message_sent_validation")
    else:
        print("✅ SMS message successfully sent!")
    assert validation_result, "Failed to send SMS message correctly"

    print("\n========================================")
    print("STEP 12: Cleaning up - Deleting sent message")
    print("========================================")
    # Cleanup: Delete the sent message
    # Long press on the newly sent message
    print("🖱️  Long pressing on message at (500, 1280)...")
    tester.long_press(500, 1280)
    time.sleep(2)

    # Click delete button (may vary by SMS app)
    print("🗑️  Tapping delete button at (800, 200)...")
    tester.tap(800, 200)
    time.sleep(2)

    # Click confirm delete
    print("✔️  Confirming delete at (750, 1290)...")
    tester.tap(750, 1290)
    time.sleep(2)

    print("\n========================================")
    print("STEP 13: Validating message was deleted")
    print("========================================")
    # Validate that the message was deleted
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "Messages app (Google Messages or SMS app) is open showing a conversation with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "The most recent message in the chat has been deleted."
    )
    if not validation_result:
        print("❌ Message deletion validation failed!")
        save_failed_screenshot(screenshot_path, "sms_draft_message", "message_deleted_validation")
    else:
        print("✅ SMS message successfully deleted!")
    assert validation_result, "Failed to delete the sent SMS message"

    print("\n========================================")
    print("🎉 TEST COMPLETED SUCCESSFULLY!")
    print("========================================")


def test_open_sms_app_debug(tester):
    """Test that opens the SMS app and then intentionally fails to capture screen state."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    success, error_msg = navigate_to_my_chats(tester, "open_sms_app_debug")
    assert success, error_msg

    # Click on coordinates to open a new chat
    tester.tap(950, 2225)
    time.sleep(2)

    # Validate we are on the New Chat screen
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The screen shows a 'New Chat' page where users can start a new conversation"
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "open_sms_app_debug", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"

    # Send a voice transcription asking to open the Messages app
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Can you open the Messages app?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # Wait for the SMS/Messages app to open
    time.sleep(10)

    # Take a screenshot before intentionally failing
    tester.screenshot(screenshot_path)

    # Save screenshot and UI dump for debugging
    save_failed_screenshot(screenshot_path, "open_sms_app_debug", "messages_app_opened")

    # Intentionally fail to trigger screenshot/dump save
    assert False, "Intentional failure - check screen_agent_test_output directory for screenshot and UI dump of Messages app"
