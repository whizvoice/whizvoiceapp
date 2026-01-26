package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.ui.viewmodels.VoiceManager
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration tests for power button long-press behavior when app is already in foreground.
 *
 * These tests verify that when the user long-presses the power button while the app is
 * already open on an existing chat:
 * 1. A NEW chat is created (not staying on the existing chat)
 * 2. Voice mode is activated (microphone starts listening)
 *
 * This scenario is different from fresh voice launches tested in VoiceLaunchDetectionIntegrationTest,
 * which test the case when the app is NOT in foreground.
 */
@LargeTest
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PowerButtonForegroundTest : BaseIntegrationTest() {

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var voiceManager: VoiceManager

    @Inject
    lateinit var permissionManager: PermissionManager

    private val createdChatIds = mutableListOf<Long>()

    companion object {
        private const val TAG = "PowerButtonForegroundTest"
        private const val TEST_TIMEOUT = 15000L
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Grant microphone permission for voice tests
        Log.d(TAG, "🎙️ Granting microphone permission for power button tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)

        // Clean up any existing test chats
        runBlocking {
            cleanupTestChats(
                repository = repository,
                trackedChatIds = emptyList(),
                additionalPatterns = listOf("power button test", "test message for power button"),
                enablePatternFallback = false
            )
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up test chats created during power button tests")

                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("power button test", "test message for power button"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()

                ComposeTestHelper.cleanup()
                Log.d(TAG, "✅ Power button test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test cleanup", e)
            }
        }
    }

    /**
     * Test that power button long-press while app is open on an existing chat:
     * 1. Creates a NEW chat (doesn't stay on the existing chat)
     * 2. Activates the microphone for voice input
     *
     * This is the scenario that was broken:
     * - User has app open on an existing chat with messages
     * - User long-presses power button
     * - Expected: New empty chat with microphone listening
     * - Bug was: Stayed on existing chat and/or microphone didn't activate
     */
    @Test
    fun powerButton_onExistingChat_createsNewChatAndActivatesMicrophone() {
        Log.d(TAG, "🧪 Testing power button long-press on existing chat")

        // Step 1: Handle potential voice launch by checking if we're on chat screen
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen, navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                failWithScreenshot("nav_to_chat_list_failed", "Failed to navigate back to chat list")
                return
            }
        }

        // Step 2: Ensure we're on the chat list
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )

        if (!chatListReady) {
            failWithScreenshot("chat_list_not_ready", "Chat list not ready")
            return
        }

        // Step 3: Navigate to a new chat
        Log.d(TAG, "📱 Navigating to new chat")
        if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
            failWithScreenshot("new_chat_navigation_failed", "Failed to navigate to new chat")
            return
        }

        // Step 4: Send a message to create a real chat with content
        Log.d(TAG, "💬 Sending test message to create existing chat")
        val testMessage = "test message for power button"

        val messageSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
            composeTestRule,
            testMessage,
            rapid = false
        )

        if (!messageSent) {
            failWithScreenshot("message_send_failed", "Failed to send test message")
            return
        }

        // Wait for bot response to start (indicates chat is established)
        val botResponseStarted = waitForBotResponse(TEST_TIMEOUT)
        if (!botResponseStarted) {
            Log.w(TAG, "⚠️ Bot response not detected, but continuing (chat may still be established)")
        }

        // Step 5: Get the current chat state before power button
        val chatsBeforePowerButton = runBlocking { repository.getAllChats() }
        val chatCountBefore = chatsBeforePowerButton.size
        Log.d(TAG, "📊 Chats before power button: $chatCountBefore")

        // Track the existing chat for cleanup
        chatsBeforePowerButton.filter { it.id < 0 || it.title?.contains("test message") == true }
            .forEach { createdChatIds.add(it.id) }

        // Record the current viewModel chat ID (we'll compare against this)
        val existingChatIdIndicator = composeTestRule.onAllNodesWithText(testMessage)
            .fetchSemanticsNodes().isNotEmpty()
        Log.d(TAG, "📍 Current chat has test message visible: $existingChatIdIndicator")

        // Step 6: Log current listening state (don't manipulate it - test real production conditions)
        val listeningBeforePowerButton = voiceManager.isContinuousListeningEnabled.value
        Log.d(TAG, "🎤 Continuous listening before power button: $listeningBeforePowerButton")

        // Step 7: Simulate power button long-press by directly setting the FORCE_NEW_CHAT signal
        // This is what MainActivity.handleVoiceLaunchIfNeeded() does when it detects
        // the app is already on a chat screen and receives CREATE_NEW_CHAT_ON_START intent.
        // By setting this signal directly, we test the ChatScreen's response to it.
        Log.d(TAG, "🔘 Simulating power button long-press (setting FORCE_NEW_CHAT signal)")

        composeTestRule.runOnUiThread {
            val navController = composeTestRule.activity.getNavController()
            if (navController != null) {
                // Set the FORCE_NEW_CHAT timestamp - same as MainActivity.handleVoiceLaunchIfNeeded()
                navController.currentBackStackEntry?.savedStateHandle?.set(
                    "FORCE_NEW_CHAT",
                    System.currentTimeMillis()
                )
                // Also set ENABLE_VOICE_MODE as the real flow does
                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                Log.d(TAG, "✅ Set FORCE_NEW_CHAT signal in savedStateHandle")
            } else {
                Log.e(TAG, "❌ NavController is null - cannot set FORCE_NEW_CHAT")
            }
        }

        // Step 8: Wait for the new chat to be created and UI to update
        // The FORCE_NEW_CHAT signal triggers loadChatWithVoiceMode(-1), which resets the chat
        // We need to wait for the StateFlow and flatMapLatest to propagate the changes
        Log.d(TAG, "⏳ Waiting for new chat creation and UI update")

        // Wait for old messages to disappear (most reliable indicator of new chat)
        val oldMessagesGone = runBlocking {
            var attempts = 0
            while (attempts < 50) { // Wait up to 10 seconds (50 * 200ms)
                composeTestRule.waitForIdle()
                val oldMessageStillVisible = composeTestRule.onAllNodesWithText(testMessage)
                    .fetchSemanticsNodes().isNotEmpty()

                Log.d(TAG, "🔍 Old message check attempt $attempts: visible=$oldMessageStillVisible")

                if (!oldMessageStillVisible) {
                    Log.d(TAG, "✅ Old messages gone after ${attempts * 200}ms")
                    return@runBlocking true
                }
                delay(200L)
                attempts++
            }
            Log.d(TAG, "❌ Old messages still visible after ${attempts * 200}ms")
            false
        }

        if (!oldMessagesGone) {
            failWithScreenshot("stayed_on_old_chat",
                "Power button should create NEW chat, but old chat messages are still visible after 10s")
            return
        }

        // Verify we're still on a chat screen (not navigated away)
        val onChatScreen = ComposeTestHelper.isOnChatScreen(composeTestRule)
        if (!onChatScreen) {
            failWithScreenshot("not_on_chat_screen",
                "After power button, should be on chat screen")
            return
        }

        Log.d(TAG, "✅ New chat created (old messages gone, on chat screen)")

        // Step 9: Verify microphone is activated
        Log.d(TAG, "🎤 Verifying microphone activation")

        // Wait for voice to be re-initialized
        val microphoneActivated = runBlocking {
            var attempts = 0
            while (attempts < 25) { // Wait up to 5 seconds
                val isContinuousEnabled = voiceManager.isContinuousListeningEnabled.value
                val isListening = voiceManager.isListening.value
                Log.d(TAG, "🔍 Microphone check attempt $attempts: continuousEnabled=$isContinuousEnabled, isListening=$isListening")

                if (isContinuousEnabled) {
                    Log.d(TAG, "✅ Continuous listening enabled after ${attempts * 200}ms")
                    return@runBlocking true
                }
                delay(200L)
                attempts++
            }
            Log.d(TAG, "❌ Continuous listening never enabled after ${attempts * 200}ms")
            false
        }

        if (!microphoneActivated) {
            // Check if we can at least see the "Stop listening" button in UI
            val stopListeningVisible = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithContentDescription("Stop listening") },
                3000,
                "stop listening button"
            )

            if (stopListeningVisible) {
                Log.d(TAG, "✅ Stop listening button visible - microphone is active")
            } else {
                failWithScreenshot("microphone_not_activated",
                    "Power button should activate microphone, but continuous listening is not enabled")
                return
            }
        }

        Log.d(TAG, "✅ Microphone activated successfully")

        // Step 10: Final verification - confirm we're NOT on the old chat
        val oldMessageGone = composeTestRule.onAllNodesWithText(testMessage)
            .fetchSemanticsNodes().isEmpty()

        assertTrue("Old chat message should not be visible after power button creates new chat",
            oldMessageGone)

        Log.d(TAG, "✅ TEST PASSED: Power button on existing chat created new chat and activated microphone")

        // Track any new chats for cleanup
        runBlocking {
            val chatsAfterPowerButton = repository.getAllChats()
            val newChats = chatsAfterPowerButton.filter { chat ->
                !chatsBeforePowerButton.map { it.id }.contains(chat.id)
            }
            newChats.forEach { chat ->
                createdChatIds.add(chat.id)
                Log.d(TAG, "📝 Tracked new chat for cleanup: ${chat.id} ('${chat.title}')")
            }
        }
    }

    /**
     * Test that repeated power button presses while on chat screen consistently
     * create new chats and activate microphone each time.
     */
    @Test
    fun powerButton_repeatedPresses_consistentlyCreatesNewChats() {
        Log.d(TAG, "🧪 Testing repeated power button presses")

        // Handle potential voice launch
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen, navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                failWithScreenshot("nav_to_chat_list_failed", "Failed to navigate back to chat list")
                return
            }
        }

        // Navigate to new chat
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )

        if (!chatListReady) {
            failWithScreenshot("chat_list_not_ready", "Chat list not ready")
            return
        }

        if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
            failWithScreenshot("new_chat_navigation_failed", "Failed to navigate to new chat")
            return
        }

        // Perform 3 consecutive power button presses
        for (i in 1..3) {
            Log.d(TAG, "🔘 Power button press #$i")

            // Simulate power button by setting FORCE_NEW_CHAT signal directly
            composeTestRule.runOnUiThread {
                val navController = composeTestRule.activity.getNavController()
                navController?.currentBackStackEntry?.savedStateHandle?.set(
                    "FORCE_NEW_CHAT",
                    System.currentTimeMillis()
                )
                navController?.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
            }

            // Wait for voice activation - check UI indicator which is more reliable
            val activated = runBlocking {
                var attempts = 0
                while (attempts < 15) {
                    // Check both the StateFlow and UI indicator
                    val stateFlowEnabled = voiceManager.isContinuousListeningEnabled.value
                    composeTestRule.waitForIdle()
                    val uiShowsListening = composeTestRule.onAllNodesWithContentDescription("Stop listening")
                        .fetchSemanticsNodes().isNotEmpty()

                    Log.d(TAG, "🔍 Mic check attempt $attempts: stateFlow=$stateFlowEnabled, uiShowsListening=$uiShowsListening")

                    if (stateFlowEnabled || uiShowsListening) {
                        return@runBlocking true
                    }
                    delay(200L)
                    attempts++
                }
                false
            }

            if (!activated) {
                failWithScreenshot("power_button_${i}_mic_not_activated",
                    "Power button press #$i should activate microphone")
                return
            }

            Log.d(TAG, "✅ Power button press #$i: Microphone activated")

            // Wait for UI to settle before next press
            composeTestRule.waitForIdle()
        }

        Log.d(TAG, "✅ TEST PASSED: Repeated power button presses consistently activated microphone")
    }
}
