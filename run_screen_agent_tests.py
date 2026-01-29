#!/usr/bin/env python3

import sys
sys.path.insert(0, '/Users/ruthgracewong/android_accessibility_tester')

import android_accessibility_tester
import subprocess
import os
import pytest
import time


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


def get_device_model():
    """Get the connected Android device model."""
    result = subprocess.run(
        ['adb', 'shell', 'getprop', 'ro.product.model'],
        capture_output=True, text=True
    )
    return result.stdout.strip()


def get_phone_numbers():
    """Get phone numbers based on device type.

    Returns tuple of (whatsapp_number, sms_number) in format '+1(628)XXX-XXXX'
    """
    device_model = get_device_model()
    print(f"📱 Detected device: {device_model}")

    if "Pixel 8" in device_model:
        # Pixel 8: WhatsApp uses 209-9005, SMS uses 227-4544
        whatsapp = ("+1(628)209-9005", "(628) 209-9005")
        sms = ("Whiz Voice Test", "+1(628)227-4544")
    else:
        # Pixel 7a (default): WhatsApp uses 227-4544, SMS uses 209-9005
        whatsapp = ("+1(628)227-4544", "(628) 227-4544")
        sms = ("Ruth Grace Wong", "+1(628)209-9005")

    return whatsapp, sms


# Global variable to store logcat process
_logcat_process = None


def install_debug_app(force=False):
    """Install the debug app, optionally with force restart."""
    script_path = os.path.join(os.path.dirname(__file__), 'install.sh')
    cmd = [script_path]
    if force:
        cmd.append('--force')
    subprocess.run(cmd, check=True, env=os.environ)

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

    # Start logcat and capture output (filtered to WhizVoice app to avoid buffer overflow)
    logcat_file = os.path.join(output_dir, 'screen_agent_logcat.log')
    _logcat_process = subprocess.Popen(
        ['adb', 'logcat', '-s', 'WhizVoice:*', 'ScreenAgentTools:*', 'WhizAccessibilityService:*', 'WhizRepository:*', 'ChatViewModel:*', 'WebSocketManager:*'],
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
        # Check for both "My Chats Title" content-desc AND "New Chat" button to ensure we're on the actual list screen
        # (not just viewing a chat where "My Chats" might appear as a navigation element)
        has_my_chats_title = check_element_exists_in_ui(tester, content_desc="My Chats Title", wait_after_dump=2.0)
        has_new_chat_button = check_element_exists_in_ui(tester, content_desc="New Chat", wait_after_dump=0.5)

        if has_my_chats_title and has_new_chat_button:
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
            tester.tap(500, 900)
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

    # Wake device and keep screen on during tests
    print("📱 Waking device and keeping screen on during tests...")
    subprocess.run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP'], check=False)
    subprocess.run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_MENU'], check=False)  # Dismiss lock screen
    time.sleep(1)

    # Prevent screen from sleeping during tests
    subprocess.run(['adb', 'shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647'], check=False)  # Max timeout (~24 days)
    subprocess.run(['adb', 'shell', 'svc', 'power', 'stayon', 'true'], check=False)  # Keep screen on while USB connected

    start_logcat()
    yield

    # Restore screen timeout settings
    print("📱 Restoring screen timeout settings...")
    subprocess.run(['adb', 'shell', 'settings', 'put', 'system', 'screen_off_timeout', '30000'], check=False)  # 30 seconds default
    subprocess.run(['adb', 'shell', 'svc', 'power', 'stayon', 'false'], check=False)

    stop_logcat()


@pytest.fixture(scope="function")
def tester(app_installed):
    """Create a fresh tester instance and open the app for each test."""
    import time

    # Force stop the app to ensure a clean start (not resuming from bubble mode or previous chat)
    subprocess.run(['adb', 'shell', 'am', 'force-stop', 'com.example.whiz.debug'], check=False)
    time.sleep(1)

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

    # Get phone numbers based on device
    whatsapp_numbers, _ = get_phone_numbers()
    whatsapp_full, whatsapp_short = whatsapp_numbers

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
    print(f"📤 Broadcasting: 'Hello, can you please send a WhatsApp message to {whatsapp_full} that says hey whats up hows it going just tryna test whiz voice'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"Hello, can you please send a WhatsApp message to {whatsapp_full} that says hey whats up hows it going just tryna test whiz voice"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 3 seconds for message to be processed...")
    time.sleep(3)  # Give time for message to be processed

    print("\n========================================")
    print("STEP 6: Waiting for draft overlay to appear")
    print("========================================")
    # wait for draft overlay to appear over whatsapp input text bar
    # Y position varies by device: Pixel 8 uses 1380, Pixel 7a uses 1425
    device_model = get_device_model()
    overlay_y = 1380 if "Pixel 8" in device_model else 1425
    # Support light_yellow (#FFF9C4), and other yellow variants (#fffad0, #fff176, #d2cea4, #d2cfa5)
    print(f"👀 Waiting for yellow overlay at pixel (300, {overlay_y}) with color #fff9c4, #fffad0, #fff176, #d2cea4, or #d2cfa5...")
    result = tester.wait_for_pixel_color(300, overlay_y, ['#fff9c4', '#fffad0', '#fff176', '#d2cea4', '#d2cfa5'], timeout=30.0)
    if result['matched']:
        print("✅ Draft overlay detected!")
    else:
        print("❌ Draft overlay not detected!")
        tester.screenshot(screenshot_path)
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_overlay_not_detected")
    assert result['matched'], f"Failed to detect draft overlay: {result.get('error')}"

    print("\n========================================")
    print("STEP 7: Validating WhatsApp draft message")
    print("========================================")
    # Validate WhatsApp is open with the draft message
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        f"WhatsApp is open showing a chat with the contact {whatsapp_full} or '{whatsapp_short}'. "
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
    print("⏳ Waiting 15 seconds for message modification...")
    time.sleep(15)

    print("\n========================================")
    print("STEP 9: Validating draft message was updated")
    print("========================================")
    # Validate that the draft was updated
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        f"WhatsApp is open showing a chat with the contact {whatsapp_full} or '{whatsapp_short}'. "
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
        f"WhatsApp is open showing a chat with the contact {whatsapp_full} or '{whatsapp_short}'. "
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
        f"WhatsApp is open showing a chat with the contact {whatsapp_full} or '{whatsapp_short}'. "
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
    # Ask for specific response format so we can detect success/failure
    play_message = 'Play Golden from Kpop Demon Hunters on YouTube Music.'
    print(f"📤 Broadcasting: '{play_message}'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"{play_message}"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    print("\n========================================")
    print("STEP 6: Waiting for response and validating song is loaded")
    print("========================================")
    # Poll until we see YouTube Music actually playing (not just a menu or search result)
    max_wait = 30
    poll_interval = 3
    play_succeeded = False
    for i in range(max_wait // poll_interval):
        time.sleep(poll_interval)
        tester.screenshot(screenshot_path)

        # Check if YouTube Music is showing "Golden" as the current track in the now-playing view
        # The song can be playing or paused - we just need to verify it's loaded
        validation_result = tester.validate_screenshot(
            screenshot_path,
            "Check if YouTube Music is showing 'Golden' as the current track in the now-playing view. Requirements: 1) You must see the song title 'Golden' displayed as the currently playing track, AND 2) You must see the now-playing screen with album art and playback controls (play/pause button, progress bar, skip buttons). The song can be either playing or paused - we just need to verify 'Golden' is loaded as the current track. Return False if: it's a search results page, it's a context menu with options like 'Play next' or 'Add to queue', or the song title shown is not 'Golden'."
        )
        if validation_result:
            print(f"✅ Song loaded after {(i+1)*poll_interval} seconds")
            play_succeeded = True
            break
        print(f"⏳ Waiting for song to load... ({(i+1)*poll_interval}/{max_wait}s)")

    if not play_succeeded:
        save_failed_screenshot(screenshot_path, "youtube_music", "song_playing_validation")
    assert play_succeeded, "Failed to load Golden on YouTube Music - song never appeared as current track"
    print("✅ YouTube Music has 'Golden' loaded successfully!")

    print("\n========================================")
    print("STEP 7: Requesting to queue second song")
    print("========================================")
    # Send a voice transcription to queue up "How it's Done" by HUNTRIX
    queue_message = "Queue How its Done by HUNTRIX on YouTube Music"
    print(f"📤 Broadcasting: '{queue_message}'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"{queue_message}"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    # Wait for the queue action to complete on the device
    print(f"⏳ Waiting {max_wait} seconds for queue action to complete...")
    time.sleep(max_wait)

    print("\n========================================")
    print("STEP 9: Opening queue view")
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
    print("STEP 10: Validating song queue")
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
    print("STEP 11: Requesting to play 90s pop instead")
    print("========================================")
    # Send a voice transcription to change to 90s pop
    change_message = "Actually can you play some 90s pop instead"
    print(f"📤 Broadcasting: '{change_message}'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"{change_message}"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    print("\n========================================")
    print("STEP 12: Waiting for music to change from Golden")
    print("========================================")
    # Poll until we see the song has changed from Golden (indicating playlist started)
    music_changed = False
    for i in range(max_wait // poll_interval):
        time.sleep(poll_interval)
        tester.screenshot(screenshot_path)

        # Check if the music changed from "Golden" - meaning a new song/playlist started
        validation_result = tester.validate_screenshot(
            screenshot_path,
            "Check if the currently playing song is NO LONGER 'Golden' by Kpop Demon Hunters. "
            "Return True if you see a DIFFERENT song playing (like 'Baby One More Time', or any other song that is NOT 'Golden'). "
            "Return False if 'Golden' is still showing as the currently playing track."
        )
        if validation_result:
            print(f"✅ Music changed after {(i+1)*poll_interval} seconds")
            music_changed = True
            break
        print(f"⏳ Waiting for music to change... ({(i+1)*poll_interval}/{max_wait}s)")

    if not music_changed:
        save_failed_screenshot(screenshot_path, "youtube_music", "nineties_music_change_validation")
    assert music_changed, "Failed to change music from Golden"
    print("✅ Music changed successfully!")

    print("\n========================================")
    print("STEP 13: Pressing back to see playlist")
    print("========================================")
    # Press back to exit the full player view and see the playlist
    subprocess.run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK'], check=True)
    time.sleep(2)

    print("\n========================================")
    print("STEP 14: Validating 90s pop playlist is visible")
    print("========================================")
    tester.screenshot(screenshot_path)
    playlist_validation = tester.validate_screenshot(
        screenshot_path,
        "Check if this is a 90s pop playlist or similar. Requirements: "
        "1) You should see a playlist page with a title containing '90s', 'nineties', '90's', or similar 90s-related text, AND "
        "2) You should see a list of songs that are typical 90s pop hits (e.g., Britney Spears, Backstreet Boys, NSYNC, Spice Girls, etc.). "
        "Return True if this appears to be a 90s pop playlist. Return False if it's a different playlist, a search results page, or not a playlist at all. "
        "There may or may not be a yellow notification bubble with an icon inside floating on the screen - the test should pass even if the bubble is covering something."
    )
    if not playlist_validation:
        print("❌ 90s pop playlist validation failed!")
        save_failed_screenshot(screenshot_path, "youtube_music", "nineties_playlist_validation")
    else:
        print("✅ 90s pop playlist validated successfully!")
    assert playlist_validation, "Failed to validate 90s pop playlist"

    print("\n========================================")
    print("STEP 15: Requesting to play 99% Invisible podcast")
    print("========================================")
    podcast_message = "Play the 99% Invisible podcast"
    print(f"📤 Broadcasting: '{podcast_message}'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"{podcast_message}"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    print("\n========================================")
    print("STEP 16: Waiting for podcast to start playing")
    print("========================================")
    # Poll until we see the podcast playing
    podcast_succeeded = False
    for i in range(max_wait // poll_interval):
        time.sleep(poll_interval)
        tester.screenshot(screenshot_path)

        # Check if 99% Invisible podcast is playing
        validation_result = tester.validate_screenshot(
            screenshot_path,
            "Check if the '99% Invisible' podcast is playing in YouTube Music. Requirements: "
            "1) You must see '99% Invisible' or '99 Percent Invisible' displayed as the currently playing content, AND "
            "2) You must see a PAUSE button (not play button) or playback progress showing it's actively playing. "
            "Return True if 99% Invisible podcast content is playing. Return False if it's still showing 90s pop music, "
            "a search results page, or anything other than the 99% Invisible podcast playing."
        )
        if validation_result:
            print(f"✅ Podcast playing after {(i+1)*poll_interval} seconds")
            podcast_succeeded = True
            break
        print(f"⏳ Waiting for podcast to start playing... ({(i+1)*poll_interval}/{max_wait}s)")

    if not podcast_succeeded:
        save_failed_screenshot(screenshot_path, "youtube_music", "podcast_playing_validation")
    assert podcast_succeeded, "Failed to play 99% Invisible podcast"
    print("✅ 99% Invisible podcast playing successfully!")

    # Pause the podcast so it doesn't keep playing after the test
    print("⏸️  Pausing podcast...")
    subprocess.run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_MEDIA_PAUSE'], check=True)

    print("\n========================================")
    print("🎉 TEST COMPLETED SUCCESSFULLY!")
    print("========================================")

def test_google_maps_directions(tester):
    """Test that we can get directions to multiple locations using Google Maps."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Detect location based on timezone to use appropriate test data
    is_pacific = any(tz in time.tzname for tz in ["Pacific", "PST", "PDT"])

    if is_pacific:
        # San Francisco config
        store_name = "Trader Joe's"
        search_query = "what are the trader joes near me ?"
        location_selector = "Can you give me directions to the one on Fulton Street"
        secondary_address = "1680 Mission Street"
        secondary_address_short = "1680 Mission St"
        city_name = "San Francisco"
    else:
        # Toronto config (or any non-Pacific timezone)
        store_name = "Shoppers Drug Mart"
        search_query = "what are the shoppers drug mart near me ?"
        location_selector = "Can you give me directions to the closest one"
        secondary_address = "220 Yonge Street"
        secondary_address_short = "220 Yonge St"
        city_name = "Toronto"

    def cleanup_google_maps():
        """Close Google Maps to prevent overlay from interfering with future tests."""
        print("🧹 Cleaning up: Force stopping Google Maps...")
        subprocess.run([
            'adb', 'shell', 'am', 'force-stop', 'com.google.android.apps.maps'
        ], capture_output=True)
        time.sleep(1)

    # Clean up Google Maps at the start to ensure fresh state
    cleanup_google_maps()

    try:
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
            '--es', 'text', f'"{search_query}"',
            '--ez', 'fromVoice', 'true',
            '--ez', 'autoSend', 'true'
        ], check=True)
        time.sleep(3)  # Give time for message to be processed

        # Wait for the search to complete and "See locations" to appear
        time.sleep(20)

        # Validate that Google Maps is showing the "See locations" list for Trader Joe's
        tester.screenshot(screenshot_path)
        validation_result = tester.validate_screenshot(
            screenshot_path,
            f"Google Maps is open and showing more than one {store_name} locations. "
            f"The screen should show more than one {store_name} results with addresses at least partially visible."
        )
        if not validation_result:
            store_name_slug = store_name.lower().replace(' ', '_').replace("'", '')
            save_failed_screenshot(screenshot_path, "google_maps_directions", f"{store_name_slug}_see_locations")
        assert validation_result, f"Failed to show {store_name} location list"

        # Send a voice transcription to select the one on Fulton Street
        subprocess.run([
            'adb', 'shell',
            'am', 'broadcast',
            '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
            '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
            '--es', 'text', f'"{location_selector}"',
            '--ez', 'fromVoice', 'true',
            '--ez', 'autoSend', 'true'
        ], check=True)

        # Wait for the location to be selected and directions to appear
        time.sleep(30)

        # Validate that Google Maps is showing directions or navigation screen
        tester.screenshot(screenshot_path)
        validation_result = tester.validate_screenshot(
            screenshot_path,
            f"This is an Android device screenshot. Check if Google Maps is showing directions or navigation. "
            f"Return True if you see ANY of: route lines on a map, turn-by-turn directions, 'Start' navigation button, "
            f"estimated travel time, or directions to {store_name}. "
            f"Return False only if Google Maps is not showing any navigation/directions content."
        )
        if not validation_result:
            store_name_slug = store_name.lower().replace(' ', '_').replace("'", '')
            save_failed_screenshot(screenshot_path, "google_maps_directions", f"{store_name_slug}_directions")
        assert validation_result, f"Failed to show {store_name} directions"

        # Send a voice transcription to change destination to secondary address
        # Note: Using a destination that's far enough that navigation won't complete immediately
        subprocess.run([
            'adb', 'shell',
            'am', 'broadcast',
            '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
            '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
            '--es', 'text', f'"Actually, I need to go to {secondary_address} first. Can you get directions to there instead?"',
            '--ez', 'fromVoice', 'true',
            '--ez', 'autoSend', 'true'
        ], check=True)
        time.sleep(3)  # Give time for message to be processed

        # Wait 15 seconds for the new location to be searched
        time.sleep(15)

        # Validate that Google Maps is showing the directions for 1680 Mission Street
        tester.screenshot(screenshot_path)
        validation_result = tester.validate_screenshot(
            screenshot_path,
            "Google Maps is open and showing the navigation screen for a route (doesn't matter what route)."
        )
        if not validation_result:
            save_failed_screenshot(screenshot_path, "google_maps_directions", "secondary_address_search")
        assert validation_result, f"Failed to show {secondary_address} search results"

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

        # Validate that Google Maps is showing driving directions to 1680 Mission Street
        tester.screenshot(screenshot_path)
        validation_result = tester.validate_screenshot(
            screenshot_path,
            "Google Maps is open and showing the navigation screen for a route with transportation mode DRIVING/CAR."
        )
        if not validation_result:
            save_failed_screenshot(screenshot_path, "google_maps_directions", "secondary_address_driving_directions")
        assert validation_result, f"Failed to show driving directions to {secondary_address}"

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
            f"The WhizVoice chat screen is showing, and the most recent assistant message mentions the address '{secondary_address}' or '{secondary_address_short}' in {city_name}"
        )
        if not validation_result:
            save_failed_screenshot(screenshot_path, "google_maps_directions", "whizvoice_address_confirmation")
        assert validation_result, f"Assistant did not mention the {secondary_address} address in the chat"

    finally:
        # Always clean up Google Maps to prevent overlay from interfering with future tests
        cleanup_google_maps()


def test_sms_draft_message(tester):
    """Test that we can draft, modify draft, and send SMS messages."""
    import time

    # Get phone numbers based on device
    _, sms_numbers = get_phone_numbers()
    sms_full, sms_short = sms_numbers

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
    print(f"📤 Broadcasting: 'Hello, can you please send a text message to {sms_full} that says hey testing SMS from whiz voice'")
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', f'"Hello, can you please send a text message to {sms_full} that says hey testing SMS from whiz voice"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    print("⏳ Waiting 3 seconds for message to be processed...")
    time.sleep(3)  # Give time for message to be processed

    print("\n========================================")
    print("STEP 6: Waiting for draft overlay to appear")
    print("========================================")
    # wait for draft overlay to appear over SMS input text bar
    # Support light mode (#fffad0), dark mode (#d2cea4), and other yellow variants (#aead93, #afad92, #fbf7cd)
    print("👀 Waiting for yellow overlay at pixel (300, 1380) with color #fffad0, #d2cea4, #aead93, #afad92, or #fbf7cd...")
    result = tester.wait_for_pixel_color(300, 1380, ['#fffad0', '#d2cea4', '#aead93', '#afad92', '#fbf7cd'], timeout=30.0)

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
        f"Messages app (Google Messages or SMS app) is open showing a conversation with a contact (could be {sms_full} or '{sms_short}'  (either is fine). "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'hey testing SMS from whiz voice'. "
        "There is also a yellow notification bubble with an outline of something (it's a robot head). "
        "There may or may not be an icon inside the outline. "
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
    print("⏳ Waiting 15 seconds for message modification...")
    time.sleep(15)

    print("\n========================================")
    print("STEP 9: Validating draft message was updated")
    print("========================================")
    # Validate that the draft was updated
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        f"Messages app (Google Messages or SMS app) is open showing a conversation with the contact {sms_full} or '{sms_short}' (either is fine). "
        "At the bottom of the screen, there is a yellow overlay or message input field containing text "
        "similar to 'testing SMS'. "
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
    print("⏳ Waiting 30 seconds for message to be sent...")
    time.sleep(30)

    print("\n========================================")
    print("STEP 11: Validating message was sent")
    print("========================================")
    # Validate that the message was sent
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        f"Messages app (Google Messages or SMS app) is open showing a conversation with the contact {sms_full} or '{sms_short}' (either is fine). "
        "Check that the message was SENT (appears in the conversation thread as a sent message bubble, not in the text input field). "
        "The most recent sent message should contain text similar to 'testing SMS' (may have polite words added). "
        "There should be NO yellow overlay covering the bottom of the screen. "
        "A yellow notification bubble with a robot head outline may be visible. "
        "Return True if the message appears as a SENT message in the conversation. "
        "Return False if the message text is still in the input field at the bottom."
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
    tester.long_press(500, 1180)
    time.sleep(2)

    # Click delete button (may vary by SMS app)
    print("🗑️  Tapping delete button at (800, 200)...")
    tester.tap(800, 200)
    time.sleep(2)

    # Click confirm delete
    print("✔️  Confirming delete at (750, 1490)...")
    tester.tap(750, 1490)
    time.sleep(2)

    print("\n========================================")
    print("STEP 13: Validating message was deleted")
    print("========================================")
    # Validate that the message was deleted
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        f"Messages app (Google Messages or SMS app) is open showing a conversation with the contact {sms_full} or '{sms_short}' (either is fine). "
        "The most recent message in the chat has been deleted. Other messages may or may not be deleted."
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

