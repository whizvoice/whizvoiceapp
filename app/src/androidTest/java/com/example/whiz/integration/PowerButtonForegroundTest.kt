package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.AssistantActivity
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.viewmodels.VoiceManager
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
 *
 * Note: This test uses instrumentation-based activity launching (like VoiceLaunchDetectionIntegrationTest)
 * instead of createAndroidComposeRule to avoid ActivityScenario lifecycle conflicts when launching
 * AssistantActivity during the test.
 */
@LargeTest
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PowerButtonForegroundTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var voiceManager: VoiceManager

    @Inject
    lateinit var permissionManager: PermissionManager

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val createdChatIds = mutableListOf<Long>()
    private var mainActivity: MainActivity? = null

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
                    additionalPatterns = listOf("power button test", "test message for power button", "This is a test", "TEST SUCCESSFUL"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()

                // Finish activity if it's still running
                mainActivity?.let {
                    if (!it.isFinishing) {
                        it.finish()
                    }
                }
                mainActivity = null

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

        // Step 1: Launch MainActivity (simulating user manually opening the app)
        Log.d(TAG, "📱 Launching MainActivity")
        val launchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        mainActivity = instrumentation.startActivitySync(launchIntent) as MainActivity

        // Step 2: Wait for chat list to load
        Log.d(TAG, "⏳ Waiting for chat list to load")
        val chatListLoaded = device.wait(
            Until.hasObject(By.textContains("My Chats").pkg("com.example.whiz.debug")),
            TEST_TIMEOUT
        )

        if (!chatListLoaded) {
            // Maybe we're already on a chat screen from a previous voice launch
            val onChatScreen = device.hasObject(By.clazz("android.widget.EditText").pkg("com.example.whiz.debug"))
            if (onChatScreen) {
                Log.d(TAG, "Already on chat screen, pressing back to go to chat list")
                device.pressBack()
                val backToChatList = device.wait(
                    Until.hasObject(By.textContains("My Chats").pkg("com.example.whiz.debug")),
                    5000
                )
                if (!backToChatList) {
                    failWithScreenshot("chat_list_not_ready", "Could not get to chat list")
                    return
                }
            } else {
                failWithScreenshot("chat_list_not_ready", "Chat list not ready")
                return
            }
        }

        // Step 3: Navigate to a new chat by clicking the FAB
        Log.d(TAG, "📱 Navigating to new chat")
        val fab = device.findObject(By.desc("New Chat").pkg("com.example.whiz.debug"))
        if (fab == null) {
            failWithScreenshot("new_chat_fab_not_found", "New Chat FAB not found")
            return
        }
        fab.click()

        // Wait for chat screen (EditText for message input)
        val chatScreenLoaded = device.wait(
            Until.hasObject(By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")),
            5000
        )

        if (!chatScreenLoaded) {
            failWithScreenshot("new_chat_navigation_failed", "Failed to navigate to new chat")
            return
        }

        // Step 4: Send a message to create a real chat with content
        Log.d(TAG, "💬 Sending test message to create existing chat")
        val testMessage = "test message for power button"

        val editText = device.findObject(By.clazz("android.widget.EditText").pkg("com.example.whiz.debug"))
        if (editText == null) {
            failWithScreenshot("edit_text_not_found", "EditText not found")
            return
        }
        editText.text = testMessage

        // Find and click send button - wait for it to appear first
        val sendButtonFound = device.wait(
            Until.hasObject(By.desc("Send typed message").pkg("com.example.whiz.debug")),
            3000
        )
        if (!sendButtonFound) {
            failWithScreenshot("send_button_not_found", "Send button not found")
            return
        }
        val sendButton = device.findObject(By.desc("Send typed message").pkg("com.example.whiz.debug"))
        sendButton.click()

        // Wait for message to appear in the chat
        val messageVisible = device.wait(
            Until.hasObject(By.textContains(testMessage).pkg("com.example.whiz.debug")),
            5000
        )

        if (!messageVisible) {
            failWithScreenshot("message_send_failed", "Failed to send test message")
            return
        }

        // Wait a moment for the chat to be established
        Log.d(TAG, "⏳ Waiting for chat to be established")
        runBlocking { delay(2000) }

        // Step 5: Get the current chat state before power button
        val chatsBeforePowerButton = runBlocking { repository.getAllChats() }
        val chatCountBefore = chatsBeforePowerButton.size
        Log.d(TAG, "📊 Chats before power button: $chatCountBefore")

        // Track the existing chat for cleanup
        chatsBeforePowerButton.filter { it.id < 0 || it.title?.contains("test message") == true }
            .forEach { createdChatIds.add(it.id) }

        // Verify old message is visible
        val oldMessageVisible = device.hasObject(By.textContains(testMessage).pkg("com.example.whiz.debug"))
        Log.d(TAG, "📍 Current chat has test message visible: $oldMessageVisible")

        // Step 6: Log current listening state
        val listeningBeforePowerButton = voiceManager.isContinuousListeningEnabled.value
        Log.d(TAG, "🎤 Continuous listening before power button: $listeningBeforePowerButton")

        // Step 7: Simulate power button long-press by launching AssistantActivity
        Log.d(TAG, "🔘 Simulating power button long-press (launching AssistantActivity)")

        val assistantIntent = Intent(
            instrumentation.targetContext,
            AssistantActivity::class.java
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("IS_ASSISTANT_LAUNCH", true)
        }

        instrumentation.targetContext.startActivity(assistantIntent)
        Log.d(TAG, "✅ Launched AssistantActivity with IS_ASSISTANT_LAUNCH=true")

        // Wait for AssistantActivity to process and MainActivity to receive the intent
        // This gives time for the FORCE_NEW_CHAT flow to execute
        Log.d(TAG, "⏳ Waiting for MainActivity to process FORCE_NEW_CHAT")

        // Wait for app to be visible again
        device.wait(Until.hasObject(By.pkg("com.example.whiz.debug")), 5000)

        // Step 8: Wait for old messages to disappear (indicates new chat created)
        Log.d(TAG, "⏳ Waiting for new chat creation (old messages should disappear)")

        val oldMessagesGone = runBlocking {
            var attempts = 0
            while (attempts < 15) { // Wait up to 3 seconds
                val oldMessageStillVisible = device.hasObject(By.textContains(testMessage).pkg("com.example.whiz.debug"))
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
                "Power button should create NEW chat, but old chat messages are still visible after 3s")
            return
        }

        // Verify we're still on a chat screen
        val onChatScreen = device.hasObject(By.clazz("android.widget.EditText").pkg("com.example.whiz.debug"))
        if (!onChatScreen) {
            failWithScreenshot("not_on_chat_screen", "After power button, should be on chat screen")
            return
        }

        Log.d(TAG, "✅ New chat created (old messages gone, on chat screen)")

        // Step 9: Verify microphone is activated
        Log.d(TAG, "🎤 Verifying microphone activation")

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
            val stopListeningVisible = device.wait(
                Until.hasObject(By.desc("Stop listening").pkg("com.example.whiz.debug")),
                3000
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

}
