package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration tests for microphone button state changes during various scenarios.
 * Tests microphone button behavior in different states: idle, listening, and during response.
 *
 * Following best practices:
 * - Uses ComposeTestHelper for all UI interactions
 * - Takes screenshots on failure for debugging
 * - No arbitrary delays - uses ComposeTestHelper's waitForElement
 * - Extends BaseIntegrationTest for proper setup
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MicrophoneButtonStateTest : BaseIntegrationTest() {

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    private val createdChatIds = mutableListOf<Long>()
    private var createdNewChatThisTest = false

    companion object {
        private const val TAG = "MicrophoneButtonStateTest"
        private const val TEST_TIMEOUT = 10000L
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Grant microphone permission for these tests
        Log.d(TAG, "Granting microphone permission for tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)

        Log.d(TAG, "Microphone button state test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "Cleaning up test chats")
            if (createdNewChatThisTest && createdChatIds.isNotEmpty()) {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = emptyList(),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
            }

            ComposeTestHelper.cleanup()
            Log.d(TAG, "Test cleanup completed")
        }
    }

    /**
     * Test that microphone button auto-starts listening in new chat and can be toggled multiple times.
     * New chats auto-start listening (showing "Stop listening"), and clicking should toggle between states.
     */
    @Test
    fun micButton_autoStartsAndToggles_inNewChat() {
        Log.d(TAG, "Testing microphone button auto-starts and toggles multiple times in new chat")

        // Handle potential voice launch by checking if we're on chat screen
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen, navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                Log.e(TAG, "❌ TEST FAILED: Failed to navigate back to chat list")
                failWithScreenshot("nav_to_chat_list_failed", "Failed to navigate back to chat list")
                return
            }
        }

        // Ensure we're on the chat list
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )

        if (!chatListReady) {
            Log.e(TAG, "❌ TEST FAILED: Chat list not ready")
            failWithScreenshot("chat_list_not_ready", "Chat list not ready")
            return
        }

        // Navigate to new chat
        Log.d(TAG, "Navigating to new chat")
        if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
            Log.e(TAG, "❌ TEST FAILED: Failed to navigate to new chat")
            failWithScreenshot("new_chat_navigation_failed", "Failed to navigate to new chat")
            return
        }

        // No need to track chat for cleanup - no messages sent, so no chat created in DB

        // State 1: Verify microphone button shows "Stop listening" (auto-started listening)
        Log.d(TAG, "State 1: Verifying Stop listening (auto-started)")
        val state1Found = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithContentDescription("Stop listening") },
            TEST_TIMEOUT,
            "stop listening mic button"
        )

        if (!state1Found) {
            failWithScreenshot("state1_wrong", "State 1: Expected 'Stop listening' button not found - new chat should auto-start listening")
            return
        }

        Log.d(TAG, "State 1: Confirmed microphone auto-started listening")

        // Click 1: Stop listening -> Start listening
        Log.d(TAG, "Click 1: Stopping listening")
        composeTestRule.onNodeWithContentDescription("Stop listening").performClick()

        // State 2: Verify "Start listening"
        Log.d(TAG, "State 2: Verifying Start listening")
        val state2Found = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithContentDescription("Start listening") },
            TEST_TIMEOUT,
            "start listening state"
        )

        if (!state2Found) {
            failWithScreenshot("state2_wrong", "State 2: Expected 'Start listening' button not found after stopping")
            return
        }

        Log.d(TAG, "State 2: Confirmed button changed to Start listening")

        // Click 2: Start listening -> Stop listening
        Log.d(TAG, "Click 2: Starting listening again")
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()

        // State 3: Verify back to "Stop listening"
        Log.d(TAG, "State 3: Verifying Stop listening again")
        val state3Found = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithContentDescription("Stop listening") },
            TEST_TIMEOUT,
            "stop listening state again"
        )

        if (!state3Found) {
            failWithScreenshot("state3_wrong", "State 3: Expected 'Stop listening' button not found after starting again")
            return
        }

        Log.d(TAG, "State 3: Confirmed button changed back to Stop listening")
        Log.d(TAG, "Microphone button successfully toggled multiple times: Stop -> Start -> Stop")
    }

    /**
     * Test that microphone button is clickable during bot response.
     * User should be able to click mic button even while the bot is responding.
     */
    @Test
    fun micButton_isClickable_duringBotResponse() {
        runBlocking {
            Log.d(TAG, "Testing microphone button clickability during bot response")

            // Handle potential voice launch
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen, navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("nav_to_chat_list_failed", "Failed to navigate back to chat list")
                    return@runBlocking
                }
            }

            // Voice response is enabled by default in new chats (from previous UI dump we saw "Enable Voice Response" was OFF/muted)
            // We'll enable it after navigating to the new chat

            // Ensure we're on the chat list
            val chatListReady = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("My Chats") },
                TEST_TIMEOUT,
                "chat list to load"
            )

            if (!chatListReady) {
                failWithScreenshot("chat_list_not_ready", "Chat list not ready")
                return@runBlocking
            }

            // Navigate to new chat
            Log.d(TAG, "Navigating to new chat")
            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                failWithScreenshot("new_chat_navigation_failed", "Failed to navigate to new chat")
                return@runBlocking
            }

            createdNewChatThisTest = true

            // Enable voice response (TTS) by clicking the button in top-right
            Log.d(TAG, "Enabling voice response for bot to respond with TTS")
            val enableVoiceButton = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription("Enable Voice Response") },
                3000,
                "enable voice response button"
            )

            if (enableVoiceButton) {
                composeTestRule.onNodeWithContentDescription("Enable Voice Response").performClick()
                Log.d(TAG, "Clicked Enable Voice Response button")

                // Wait for button to change to "Disable Voice Response" (indicating it's now enabled)
                val voiceResponseEnabled = ComposeTestHelper.waitForElement(
                    composeTestRule,
                    { composeTestRule.onNodeWithContentDescription("Disable Voice Response") },
                    3000,
                    "disable voice response button (indicating voice is now enabled)"
                )

                if (!voiceResponseEnabled) {
                    Log.w(TAG, "Voice response may not have been enabled - 'Disable Voice Response' button not found")
                }
            } else {
                Log.w(TAG, "Enable Voice Response button not found - may already be enabled or button text different")
            }

            // First, stop the auto-started listening so we can send a typed message
            Log.d(TAG, "Stopping auto-started listening")
            val stopButton = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription("Stop listening") },
                TEST_TIMEOUT,
                "stop listening button"
            )

            if (!stopButton) {
                failWithScreenshot("stop_button_not_found", "Stop listening button not found")
                return@runBlocking
            }

            composeTestRule.onNodeWithContentDescription("Stop listening").performClick()

            // Wait for state to change to "Start listening"
            val startListeningButton = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription("Start listening") },
                TEST_TIMEOUT,
                "start listening button after stopping"
            )

            if (!startListeningButton) {
                failWithScreenshot("start_listening_not_found", "Start listening button not found after stopping")
                return@runBlocking
            }

            // Send a message that will trigger a longer bot response
            val testMessage = "Explain E=mc² in detail"
            Log.d(TAG, "Sending test message to trigger bot response")

            if (!ComposeTestHelper.sendMessage(composeTestRule, testMessage)) {
                failWithScreenshot("message_send_failed", "Failed to send test message")
                return@runBlocking
            }

            Log.d(TAG, "Message sent successfully")

            // Track the newly created chat for cleanup (chat is created when first message is sent)
            try {
                val currentChats = repository.getAllChats()
                val latestChat = currentChats.maxByOrNull { it.createdAt }
                if (latestChat != null) {
                    createdChatIds.add(latestChat.id)
                    Log.d(TAG, "Tracked new chat for cleanup: ${latestChat.id}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not track newly created chat: ${e.message}")
            }

            // Wait for bot response to start appearing (look for "Whiz" label which appears on all assistant messages)
            Log.d(TAG, "Waiting for bot response to appear...")
            val botResponseAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("Whiz") },
                TEST_TIMEOUT,
                "Whiz label on assistant message"
            )

            if (!botResponseAppeared) {
                Log.e(TAG, "❌ TEST FAILED: Bot response with 'Whiz' label did not appear after sending message (waited ${TEST_TIMEOUT}ms)")
                failWithScreenshot("bot_response_not_appeared", "Bot response with 'Whiz' label did not appear after sending message")
                return@runBlocking
            }

            Log.d(TAG, "Bot response detected (Whiz label found), now testing mic button during response")

            // During bot response, try to click the mic button
            // The button should be available and clickable
            Log.d(TAG, "Attempting to click microphone button during bot response")

            // Try to wait for either "Turn on continuous listening" or "Turn off continuous listening"
            // Try "Turn on continuous listening" first
            var micButtonFound = false
            var buttonDescription = ""

            val turnOnButton = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription("Turn on continuous listening") },
                3000,  // Shorter timeout for first attempt
                "turn on continuous listening"
            )

            if (turnOnButton) {
                micButtonFound = true
                buttonDescription = "Turn on continuous listening"
            } else {
                // Try "Turn off continuous listening"
                val turnOffButton = ComposeTestHelper.waitForElement(
                    composeTestRule,
                    { composeTestRule.onNodeWithContentDescription("Turn off continuous listening") },
                    3000,
                    "turn off continuous listening"
                )

                if (turnOffButton) {
                    micButtonFound = true
                    buttonDescription = "Turn off continuous listening"
                }
            }

            if (!micButtonFound) {
                failWithScreenshot("mic_button_not_available_during_response", "Neither 'Turn on' nor 'Turn off continuous listening' button found during bot response")
                return@runBlocking
            }

            // Click the button we found
            Log.d(TAG, "Found '$buttonDescription' button, attempting to click")
            try {
                composeTestRule.onNodeWithContentDescription(buttonDescription).performClick()
                Log.d(TAG, "Successfully clicked '$buttonDescription' during bot response")
            } catch (e: Exception) {
                failWithScreenshot("mic_button_click_failed_during_response", "Failed to click '$buttonDescription' button during bot response: ${e.message}")
                return@runBlocking
            }

            // Verify the button state changed after clicking
            Log.d(TAG, "Verifying mic button state changed after click")
            val expectedNewState = if (buttonDescription == "Turn on continuous listening") {
                "Turn off continuous listening"
            } else {
                "Turn on continuous listening"
            }

            val stateChanged = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription(expectedNewState) },
                3000,
                "new mic button state"
            )

            if (!stateChanged) {
                failWithScreenshot("mic_button_state_not_changed", "Mic button state did not change to '$expectedNewState' after clicking")
                return@runBlocking
            }

            Log.d(TAG, "Microphone button is clickable during bot response and state changed successfully")
        }
    }
}
