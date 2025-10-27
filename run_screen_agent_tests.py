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


def navigate_to_my_chats(tester):
    """Navigate to the My Chats page by pressing back until we reach it."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    max_attempts = 5

    for _ in range(max_attempts):
        tester.screenshot(screenshot_path)
        if tester.validate_screenshot(
            screenshot_path,
            "The screen shows a 'My Chats' or 'Chats List' page with a list of chats or an empty state. "
            "This is the main chat list view of WhizVoice."
        ):
            return True

        # Press back button and try again
        tester.press_back()
        time.sleep(1)

    return False


def enable_accessibility_service_if_needed(tester):
    """Enable accessibility service if the dialog is showing."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    tester.screenshot(screenshot_path)

    if tester.validate_screenshot(
        screenshot_path,
        "The screen shows an 'Enable accessibility service' dialog or prompt"
    ):
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


def login_if_needed(tester):
    """Log in to the app if we're on the login screen."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"
    tester.screenshot(screenshot_path)

    if tester.validate_screenshot(
        screenshot_path,
        "The screen shows a login or sign-in screen with a 'Sign in with Google' button"
    ):
        # Click "Sign in with Google" button
        tester.tap(500, 1450)
        time.sleep(2)

        # Click to log in as REDACTED_TEST_EMAIL
        tester.tap(500, 1300)
        time.sleep(3)

        # Verify we reached My Chats page
        tester.screenshot(screenshot_path)
        assert tester.validate_screenshot(
            screenshot_path,
            "The screen shows a 'My Chats' or 'Chats List' page with a list of chats or an empty state. "
            "This is the main chat list view of WhizVoice after logging in."
        ), "Failed to log in and reach My Chats page"


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

    # Enable accessibility service if needed
    enable_accessibility_service_if_needed(tester)

    # Log in if needed
    login_if_needed(tester)

    yield tester
    # Add any per-test cleanup here if needed


# Example test - add your actual tests below
def test_whatsapp_draft_message(tester):
    """Test that we can draft and modify draft and send message in WhatsApp."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    assert navigate_to_my_chats(tester), "Failed to navigate to My Chats page"

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
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"

    # Send a voice transcription with the test message
    # Note: The app is in continuous listening mode by default (voice app behavior),
    # so we use the TEST_TRANSCRIPTION broadcast instead of keyboard input
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Hello, can you please send a message to +1(628)209-9005 that says hey whats up hows it going just tryna test whiz voice"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # wait for draft overlay to appear over whatsapp input text bar
    result = tester.wait_for_pixel_color(300, 1380, (255, 250, 208), timeout=15.0)  # #fffad0
    assert result['matched'], f"Failed to detect draft overlay: {result.get('error')}"

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
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_message_validation")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    # Send a voice transcription to modify the message
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Actually, can you make the message more polite?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(10)

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
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "draft_updated_validation")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    # Send a voice transcription to send the message
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"That looks good, go ahead and send the message."',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(10)

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
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "message_sent_validation")
    assert validation_result, "Failed to draft WhatsApp message correctly"

    # Cleanup: Delete the sent message
    # Long press on the newly sent message
    tester.long_press(500, 1280)
    time.sleep(1)

    # Click delete button
    tester.tap(800, 200)
    time.sleep(2)

    # Click confirm delete
    tester.tap(750, 1290)
    time.sleep(2)

    # Validate that the message was deleted
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "WhatsApp is open showing a chat with the contact +1(628)209-9005 or '(628) 209-9005'. "
        "It's OK if the contact is a self-message with '(You)' at the end of the contact name. "
        "The most recent message in the chat has been deleted."
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "whatsapp_draft_message", "message_deleted_validation")
    assert validation_result, "Failed to delete the sent message"


def test_youtube_music_integration(tester):
    """Test that we can play and queue songs on YouTube Music."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    assert navigate_to_my_chats(tester), "Failed to navigate to My Chats page"

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
        save_failed_screenshot(screenshot_path, "youtube_music", "new_chat_screen")
    assert validation_result, "Failed to reach New Chat screen"

    # Send a voice transcription to play songs on YouTube Music
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Hey can you play Golden from Kpop Demon Hunters on YouTube Music?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)
    time.sleep(3)  # Give time for message to be processed

    # Wait for YouTube Music to open and song to start playing
    # The bot should launch YouTube Music, search for the song, and play it
    time.sleep(15)

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
        save_failed_screenshot(screenshot_path, "youtube_music", "song_playing_validation")
    assert validation_result, "Failed to open YouTube Music and play song"

    # Send a voice transcription to queue up "How it's Done" by HUNTRIX
    subprocess.run([
        'adb', 'shell',
        'am', 'broadcast',
        '-a', 'com.example.whiz.TEST_TRANSCRIPTION',
        '-n', 'com.example.whiz.debug/com.example.whiz.test.TestTranscriptionReceiver',
        '--es', 'text', '"Can you queue up How it\'s Done by HUNTRIX?"',
        '--ez', 'fromVoice', 'true',
        '--ez', 'autoSend', 'true'
    ], check=True)

    # Wait 15 seconds for queueing to complete
    time.sleep(20)

    # Tap to full screen the current song
    subprocess.run(['adb', 'shell', 'input', 'tap', '500', '2100'], check=True)
    time.sleep(1)

    # Tap to see what's up next
    subprocess.run(['adb', 'shell', 'input', 'tap', '300', '2200'], check=True)
    time.sleep(2)  # Wait for queue to appear

    # Screenshot and validate that it shows the queue with Golden first and How It's Done second
    tester.screenshot(screenshot_path)
    validation_result = tester.validate_screenshot(
        screenshot_path,
        "The screen shows a song queue with 'Golden' as the first song and 'How It's Done' as the second song in the queue."
    )
    if not validation_result:
        save_failed_screenshot(screenshot_path, "youtube_music", "queue_validation")
    assert validation_result, "Failed to validate queue with Golden first and How It's Done second"


def test_google_maps_ui_dump(tester):
    """Test to dump Google Maps UI to understand the interface."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open Google Maps directly using tester
    tester.open_app("com.google.android.apps.maps")
    time.sleep(5)

    # Take screenshot
    tester.screenshot(screenshot_path)

    # Save screenshot and UI dump
    save_failed_screenshot(screenshot_path, "google_maps_ui_dump", "main_screen")

    # Force fail to trigger UI dump
    assert False, "Intentional fail to capture Google Maps UI dump"


def test_google_maps_directions(tester):
    """Test that we can get directions to multiple locations using Google Maps."""
    import time

    screenshot_path = "/tmp/whiz_screen.png"

    # Open WhizVoice Debug app
    tester.open_app("com.example.whiz.debug")
    time.sleep(3)

    # Navigate to My Chats page
    assert navigate_to_my_chats(tester), "Failed to navigate to My Chats page"

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
        save_failed_screenshot(screenshot_path, "google_maps_directions", "new_chat_screen")
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
    time.sleep(25)

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
    time.sleep(3)  # Give time for message to be processed

    # Wait 15 seconds for the location to be selected and directions to appear
    time.sleep(15)

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

    # Wait 15 seconds for driving directions to be displayed
    time.sleep(15)

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
