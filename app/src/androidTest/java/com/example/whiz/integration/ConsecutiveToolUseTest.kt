package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.BaseIntegrationTest
import org.junit.After
import android.util.Log
import android.provider.Settings
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.MainActivity
import org.junit.Assert.*

/**
 * Integration test for consecutive tool use scenario:
 * Tests sending two messages in quick succession where the second message
 * modifies the first request (Asana task update case).
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ConsecutiveToolUseTest : BaseIntegrationTest() {

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()
    private var createdNewChatThisTest = false
    private var testUniqueId: Long = 0

    // ChatViewModel capture for direct state access
    private var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null

    companion object {
        private const val TAG = "ConsecutiveToolUseTest"
        private const val TEST_TIMEOUT = 15000L
    }

    private val uniqueTestId = "CONSECUTIVE_TOOL_USE_TEST_${System.currentTimeMillis()}"

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Grant microphone permission for rapid message testing
        Log.d(TAG, "🎙️ Granting microphone permission for rapid message tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")

        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)

        val animationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        Log.d("Test", "Animation scale: $animationScale")

        Log.d(TAG, "🧪 Consecutive Tool Use Test Setup Complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats (only deleting chats created by this test)")
            if (createdNewChatThisTest && createdChatIds.isNotEmpty()) {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Asana",
                        "bread",
                        "milk",
                        "picking up the kids",
                        testUniqueId.toString(),
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
            } else if (createdNewChatThisTest) {
                Log.w(TAG, "⚠️ Chat tracking failed but test created chats - using pattern fallback cleanup")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = emptyList(),
                    additionalPatterns = listOf(
                        "Asana",
                        "bread",
                        "milk",
                        "picking up the kids",
                        testUniqueId.toString(),
                    ),
                    enablePatternFallback = true
                )
            } else {
                Log.d(TAG, "ℹ️ No new chats created by test - using existing chat, nothing to clean up")
            }

            // Clean up ComposeTestHelper resources
            ComposeTestHelper.cleanup()

            // Clean up ViewModel callback
            MainActivity.testViewModelCallback = null
            capturedViewModel = null

            Log.d(TAG, "✅ Test cleanup completed")
        }
    }

    /**
     * Wait for a message matching the predicate to appear in the ViewModel's messages state.
     */
    private fun waitForMessageInViewModel(
        viewModel: com.example.whiz.ui.viewmodels.ChatViewModel,
        predicate: (MessageEntity) -> Boolean,
        timeout: Long = 10000L
    ): MessageEntity? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val messages = viewModel.messages.value
            val found = messages.find(predicate)
            if (found != null) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "✅ Found matching message in ViewModel after ${elapsed}ms: '${found.content.take(50)}...'")
                return found
            }
            Thread.sleep(20)
        }

        Log.e(TAG, "❌ Message not found in ViewModel after ${timeout}ms timeout")
        return null
    }

    @Test
    fun consecutiveToolUse_receivesValidResponse() {
        runBlocking {
            Log.d(TAG, "🚀 Testing consecutive tool use scenario")

            val uniqueTestId = System.currentTimeMillis()
            testUniqueId = uniqueTestId

            // Initialize cleanup tracking
            createdNewChatThisTest = true

            // Capture initial chats before creating new ones
            val initialChats = repository.getAllChats()

            // Step 1: Verify app is ready
            Log.d(TAG, "📱 Step 1: Verifying app is ready")

            try {
                composeTestRule.waitForIdle()
                Log.d(TAG, "✅ Compose test rule is properly initialized")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose test rule initialization failed", e)
                failWithScreenshot("compose_test_rule_failed", "Compose test rule initialization failed: ${e.message}")
                return@runBlocking
            }

            try {
                val activity = composeTestRule.activity
                Log.d(TAG, "✅ Activity is accessible: ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Activity access failed", e)
                failWithScreenshot("activity_access_failed", "Activity access failed: ${e.message}")
                return@runBlocking
            }

            val appReady = ComposeTestHelper.isAppReady(composeTestRule)
            Log.d(TAG, "🔍 App readiness check result: $appReady")

            if (!appReady) {
                Log.e(TAG, "❌ App is not ready for testing")
                failWithScreenshot("app_not_ready", "App is not ready for testing - UI elements not found")
                return@runBlocking
            }

            Log.d(TAG, "✅ App is ready for testing - proceeding with test")

            // Step 2: Navigate to new chat
            Log.d(TAG, "➕ Step 2: Navigating to new chat")

            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "🔄 Currently in chat screen, going back to chats list first")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("navigate_to_chats_list_failed", "Failed to navigate from chat screen to chats list")
                    return@runBlocking
                }
            }

            Log.d(TAG, "📋 On chats list, clicking new chat button")

            // Set up ViewModel capture callback
            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured from navigation!")
                capturedViewModel = vm
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                failWithScreenshot("new_chat_creation_failed", "New chat button not found or chat screen failed to load")
                return@runBlocking
            }

            Log.d(TAG, "✅ Successfully navigated to new chat screen")

            // Wait for ChatViewModel capture
            Log.d(TAG, "⏳ Waiting for ChatViewModel capture...")
            var waitTime = 0
            while (capturedViewModel == null && waitTime < 3000) {
                Thread.sleep(50)
                waitTime += 50
            }

            if (capturedViewModel == null) {
                Log.e(TAG, "❌ ChatViewModel not captured - will fall back to UI-only checks")
            } else {
                Log.d(TAG, "✅ ChatViewModel captured successfully")
            }

            // Step 3: Send first Asana message
            val sentMessages = mutableListOf<String>()
            val firstMessage = "Can you add to my Asana to remember to pick up bread on the way home from picking up the kids? - $uniqueTestId"

            Log.d(TAG, "📨 Step 3: Sending first Asana message...")

            val chatScreenStatus = ComposeTestHelper.isOnChatScreen(composeTestRule)
            Log.d(TAG, "🔍 Chat screen status: $chatScreenStatus")

            if (!chatScreenStatus) {
                Log.e(TAG, "❌ Not on chat screen - navigation failed")
                failWithScreenshot("not_on_chat_screen", "Not on chat screen - navigation failed")
                return@runBlocking
            } else {
                Log.d(TAG, "✅ Confirmed we made it to chat screen")
            }

            if (!ComposeTestHelper.sendMessageAndVerifyDisplay(composeTestRule, firstMessage)) {
                Log.e(TAG, "❌ First message failed to send or appear in UI as USER message")
                failWithScreenshot("first_message_failed", "First message failed to send or appear in UI")
                return@runBlocking
            }
            sentMessages.add(firstMessage)
            Log.d(TAG, "✅ First message sent and verified in UI")

            // Track the newly created chat for cleanup
            try {
                val currentChats = repository.getAllChats()
                val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
                newChats.forEach { chat ->
                    createdChatIds.add(chat.id)
                    Log.d(TAG, "📝 Tracked new chat for cleanup: ${chat.id} ('${chat.title}')")
                }
                Log.d(TAG, "📊 Total tracked chats: ${createdChatIds.size}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track newly created chat: ${e.message}")
            }

            // Step 4: Send rapid correction message
            Log.d(TAG, "📨 Step 4: Sending rapid correction message...")
            val secondMessage = "Actually can you change it to bread and milk?"

            if (!ComposeTestHelper.sendMessageAndVerifyDisplay(composeTestRule, secondMessage, rapid = true)) {
                Log.e(TAG, "❌ Second message failed to send or appear in UI as USER message")
                failWithScreenshot("second_message_failed", "Second message failed to send or appear in UI")
                return@runBlocking
            }
            Log.d(TAG, "✅ Second message sent and verified in UI")
            sentMessages.add(secondMessage)

            // Step 5: Wait for FINAL bot response (after second message) in ViewModel
            Log.d(TAG, "🤖 Step 5: Waiting for FINAL bot response (after second message) in ViewModel...")

            // We want the LAST assistant message - the one that responds to BOTH user messages
            val assistantMessage = if (capturedViewModel != null) {
                Log.d(TAG, "⏳ Waiting up to 30 seconds for final assistant response...")

                // Wait for an assistant message that comes AFTER both user messages
                var lastAssistantMessage: MessageEntity? = null
                val startTime = System.currentTimeMillis()
                val timeout = 30000L

                while ((System.currentTimeMillis() - startTime) < timeout) {
                    val messages = capturedViewModel!!.messages.value

                    // Find all assistant messages
                    val assistantMessages = messages.filter {
                        it.type == MessageType.ASSISTANT && it.content.trim().isNotEmpty()
                    }

                    if (assistantMessages.isNotEmpty()) {
                        // Get the most recent one
                        lastAssistantMessage = assistantMessages.last()

                        // Check if it comes after our second user message
                        val secondUserMessageIndex = messages.indexOfLast {
                            it.type == MessageType.USER && it.content == secondMessage
                        }
                        val lastAssistantIndex = messages.indexOf(lastAssistantMessage)

                        if (secondUserMessageIndex >= 0 && lastAssistantIndex > secondUserMessageIndex) {
                            Log.d(TAG, "✅ Found assistant message after second user message")
                            break
                        }
                    }
                    Thread.sleep(200)
                }

                lastAssistantMessage
            } else {
                Log.w(TAG, "⚠️ ViewModel not captured, will rely on UI verification only")
                null
            }

            if (assistantMessage == null && capturedViewModel != null) {
                Log.e(TAG, "❌ No assistant response received in ViewModel within timeout")
                failWithScreenshot("no_assistant_response_viewmodel", "No assistant response in ViewModel")
                return@runBlocking
            }

            val response = assistantMessage?.content ?: ""
            if (response.isNotEmpty()) {
                Log.d(TAG, "✅ Received final bot response in ViewModel: '${response.take(100)}...'")

                // Verify it's not blank (whitespace only)
                if (response.trim().isEmpty()) {
                    Log.e(TAG, "❌ Bot response is only whitespace!")
                    failWithScreenshot("blank_assistant_response", "Assistant response is blank/whitespace only: '$response'")
                    return@runBlocking
                }
            }

            // Step 6: Verify response appears in UI
            Log.d(TAG, "🔍 Step 6: Verifying response appears in UI...")

            if (response.isNotEmpty()) {
                // Wait for UI to recompose and auto-scroll to complete
                // The UI needs time to: 1) recompose with new messages, 2) layout items,
                // 3) wait for LaunchedEffect delay (100ms), 4) complete scroll animation
                Log.d(TAG, "⏳ Waiting for UI to recompose and scroll...")
                composeTestRule.waitForIdle()
                Thread.sleep(500)

                // Search using content description which contains the raw message content
                Log.d(TAG, "🔍 Searching for response in UI via content description: '${response.take(50)}...'")

                // Try to find the response in the UI using content description
                try {
                    composeTestRule.onNodeWithContentDescription("Assistant message: $response", substring = true, useUnmergedTree = true).assertIsDisplayed()
                    Log.d(TAG, "✅ Assistant response verified in UI")
                } catch (e: AssertionError) {
                    Log.e(TAG, "❌ Bot response found in ViewModel but NOT in UI")
                    Log.e(TAG, "   ViewModel content: '$response'")
                    Log.e(TAG, "   Search string: 'Assistant message: $response'")
                    failWithScreenshot("assistant_response_not_in_ui", "Assistant response in ViewModel but not visible in UI")
                    return@runBlocking
                }
            } else {
                // Fallback: no ViewModel, just wait for any assistant message in UI
                Log.d(TAG, "🔍 No ViewModel response, waiting for any assistant message in UI...")

                val startTime = System.currentTimeMillis()
                val timeoutMs = 25000L
                var foundResponse = false

                while ((System.currentTimeMillis() - startTime) < timeoutMs && !foundResponse) {
                    try {
                        // Look for any assistant message content description
                        val assistantNodes = composeTestRule.onAllNodesWithContentDescription("Assistant message:", substring = true)
                        if (assistantNodes.fetchSemanticsNodes().isNotEmpty()) {
                            foundResponse = true
                            Log.d(TAG, "✅ Assistant response found in UI")
                            break
                        }
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Thread.sleep(100)
                    }
                }

                if (!foundResponse) {
                    Log.e(TAG, "❌ No assistant response found in UI")
                    failWithScreenshot("no_assistant_response_ui", "No assistant response visible in UI")
                    return@runBlocking
                }
            }

            // Step 7: Verify response is valid
            Log.d(TAG, "🔍 Step 7: Verifying response validity...")

            // Check response is not empty
            if (response.trim().isEmpty()) {
                Log.e(TAG, "❌ Bot response is empty")
                failWithScreenshot("empty_bot_response", "Bot returned empty response")
                return@runBlocking
            }
            Log.d(TAG, "✅ Response is non-empty (length: ${response.length})")

            // Check response doesn't contain "error" or "wrong"
            val lowerResponse = response.lowercase()
            if (lowerResponse.contains("error")) {
                Log.e(TAG, "❌ Bot response contains the word 'error'")
                failWithScreenshot("response_contains_error", "Bot response contains 'error': $response")
                return@runBlocking
            }
            Log.d(TAG, "✅ Response doesn't contain 'error'")

            if (lowerResponse.contains("wrong")) {
                Log.e(TAG, "❌ Bot response contains the word 'wrong'")
                failWithScreenshot("response_contains_wrong", "Bot response contains 'wrong': $response")
                return@runBlocking
            }
            Log.d(TAG, "✅ Response doesn't contain 'wrong'")

            // Step 8: Send follow-up message to delete the task
            Log.d(TAG, "🗑️ Step 8: Sending follow-up message to delete the task...")
            val deleteMessage = "Thanks. Actually, i changed my mind. Can you delete the task? And then say DELETE SUCCESSFUL when you see the successful delete tool result."

            if (!ComposeTestHelper.sendMessageAndVerifyDisplay(composeTestRule, deleteMessage)) {
                Log.e(TAG, "❌ Delete message failed to send or appear in UI as USER message")
                failWithScreenshot("delete_message_failed", "Delete message failed to send or appear in UI")
                return@runBlocking
            }
            Log.d(TAG, "✅ Delete message sent and verified in UI")
            sentMessages.add(deleteMessage)

            // Step 9: Wait for final bot response to deletion request
            Log.d(TAG, "🤖 Step 9: Waiting for bot response to deletion request...")

            val deleteResponseMessage = if (capturedViewModel != null) {
                Log.d(TAG, "⏳ Waiting up to 30 seconds for deletion response containing 'DELETE SUCCESSFUL'...")

                var lastAssistantMessage: MessageEntity? = null
                val startTime = System.currentTimeMillis()
                val timeout = 30000L

                while ((System.currentTimeMillis() - startTime) < timeout) {
                    val messages = capturedViewModel!!.messages.value

                    // Find all assistant messages
                    val assistantMessages = messages.filter {
                        it.type == MessageType.ASSISTANT && it.content.trim().isNotEmpty()
                    }

                    if (assistantMessages.isNotEmpty()) {
                        // Get the most recent one
                        lastAssistantMessage = assistantMessages.last()

                        // Check if it comes after our delete message
                        val deleteMessageIndex = messages.indexOfLast {
                            it.type == MessageType.USER && it.content == deleteMessage
                        }
                        val lastAssistantIndex = messages.indexOf(lastAssistantMessage)

                        if (deleteMessageIndex >= 0 && lastAssistantIndex > deleteMessageIndex) {
                            // Check if the response contains "DELETE SUCCESSFUL"
                            if (lastAssistantMessage.content.contains("DELETE SUCCESSFUL", ignoreCase = true)) {
                                Log.d(TAG, "✅ Found assistant response with 'DELETE SUCCESSFUL' after delete message")
                                break
                            } else {
                                Log.d(TAG, "⏳ Found assistant response after delete message, but waiting for 'DELETE SUCCESSFUL': '${lastAssistantMessage.content.take(50)}...'")
                                // Continue waiting for the correct response
                                lastAssistantMessage = null
                            }
                        }
                    }
                    Thread.sleep(200)
                }

                lastAssistantMessage
            } else {
                Log.w(TAG, "⚠️ ViewModel not captured, will rely on UI verification only")
                null
            }

            if (deleteResponseMessage == null && capturedViewModel != null) {
                Log.e(TAG, "❌ No assistant response to deletion request received in ViewModel within timeout")
                failWithScreenshot("no_delete_response_viewmodel", "No response to delete request in ViewModel")
                return@runBlocking
            }

            val deleteResponse = deleteResponseMessage?.content ?: ""
            if (deleteResponse.isNotEmpty()) {
                Log.d(TAG, "✅ Received deletion response in ViewModel: '${deleteResponse.take(100)}...'")

                // Verify it's not blank (whitespace only)
                if (deleteResponse.trim().isEmpty()) {
                    Log.e(TAG, "❌ Deletion response is only whitespace!")
                    failWithScreenshot("blank_delete_response", "Delete response is blank/whitespace only: '$deleteResponse'")
                    return@runBlocking
                }

                // Verify the response appears in UI
                Log.d(TAG, "🔍 Verifying deletion response appears in UI...")
                Log.d(TAG, "🔍 Searching for deletion response in UI via content description: 'Assistant message: $deleteResponse'")

                // Use ComposeTestHelper.waitForElement() with polling to handle timing variations
                // Use onNodeWithContentDescription (like first response check) since content-desc includes raw markdown
                val foundInUI = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithContentDescription("Assistant message: $deleteResponse", substring = true, useUnmergedTree = true) },
                    timeoutMs = 5000L,
                    description = "Delete response in UI"
                )

                if (foundInUI) {
                    Log.d(TAG, "✅ Deletion response verified in UI")
                } else {
                    Log.e(TAG, "❌ Deletion response found in ViewModel but NOT in UI after 5000ms")
                    Log.e(TAG, "   ViewModel content: '$deleteResponse'")
                    Log.e(TAG, "   Search string: 'Assistant message: $deleteResponse'")
                    failWithScreenshot("delete_response_not_in_ui", "Delete response in ViewModel but not visible in UI")
                    return@runBlocking
                }

                // Strip markdown for subsequent validations
                val strippedDeleteResponse = deleteResponse.replace("**", "").replace("*", "")

                // Check response is valid (not empty, no errors)
                if (strippedDeleteResponse.trim().isEmpty()) {
                    Log.e(TAG, "❌ Deletion response is empty")
                    failWithScreenshot("empty_delete_response", "Delete response is empty")
                    return@runBlocking
                }

                val lowerDeleteResponse = deleteResponse.lowercase()
                if (lowerDeleteResponse.contains("error")) {
                    Log.e(TAG, "❌ Deletion response contains the word 'error'")
                    failWithScreenshot("delete_response_contains_error", "Delete response contains 'error': $deleteResponse")
                    return@runBlocking
                }

                if (lowerDeleteResponse.contains("wrong")) {
                    Log.e(TAG, "❌ Deletion response contains the word 'wrong'")
                    failWithScreenshot("delete_response_contains_wrong", "Delete response contains 'wrong': $deleteResponse")
                    return@runBlocking
                }

                if (lowerDeleteResponse.contains("issue")) {
                    Log.e(TAG, "❌ Deletion response contains the word 'issue'")
                    failWithScreenshot("delete_response_contains_wrong", "Delete response contains 'wrong': $deleteResponse")
                    return@runBlocking
                }

                Log.d(TAG, "✅ Deletion response is valid")
            }

            Log.d(TAG, "📊 Test summary:")
            Log.d(TAG, "   ✅ Sent first Asana task message (bread)")
            Log.d(TAG, "   ✅ Sent rapid correction message (bread and milk)")
            Log.d(TAG, "   ✅ Received non-empty bot response to task creation/update")
            Log.d(TAG, "   ✅ Response doesn't contain 'error' or 'wrong'")
            Log.d(TAG, "   ✅ Sent deletion request message")
            Log.d(TAG, "   ✅ Received non-empty bot response to deletion")
            Log.d(TAG, "   ✅ Deletion response doesn't contain 'error' or 'wrong'")
            Log.d(TAG, "   ✅ Consecutive tool use test completed successfully")
        }
    }
}
