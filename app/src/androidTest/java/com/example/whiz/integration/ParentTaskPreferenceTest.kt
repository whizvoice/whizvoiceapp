package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.repository.WhizRepository
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
import com.example.whiz.di.AppModule

/**
 * Integration test for parent task preference functionality.
 *
 * Tests the server's ability to:
 * - Set the parent task preference to require a parent task
 * - Get the parent task preference and verify it is set correctly
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ParentTaskPreferenceTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "ParentTaskPrefTest"
        private const val BOT_RESPONSE_TIMEOUT = 30000L
        private const val VIEWMODEL_CAPTURE_TIMEOUT = 5000L
        private const val POLL_INTERVAL_MS = 50L
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    private val createdChatIds = mutableListOf<Long>()
    private val uniqueTestId = System.currentTimeMillis()

    // Track the navigation-scoped ViewModel
    private var navigationScopedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Set up callback to capture the navigation-scoped ViewModel
        MainActivity.testViewModelCallback = { viewModel ->
            Log.d(TAG, "Captured navigation-scoped ViewModel: ${viewModel.hashCode()}")
            navigationScopedViewModel = viewModel
        }

        Log.d(TAG, "parent task preference test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "cleaning up parent task preference test chats")
            try {
                Log.d(TAG, "About to cleanup. Tracked chat IDs: $createdChatIds")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "parent task preference",
                        "require a parent",
                        uniqueTestId.toString()
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                ComposeTestHelper.cleanup()
                Log.d(TAG, "test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "Error during test chat cleanup", e)
            } finally {
                // Clean up the test callback
                MainActivity.testViewModelCallback = null
                navigationScopedViewModel = null
            }
        }
    }

    /**
     * Wait for the navigation-scoped ViewModel to be captured.
     */
    private fun waitForViewModel(timeout: Long = VIEWMODEL_CAPTURE_TIMEOUT): com.example.whiz.ui.viewmodels.ChatViewModel? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val vm = navigationScopedViewModel
            if (vm != null) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "ViewModel captured after ${elapsed}ms")
                return vm
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        Log.e(TAG, "Failed to capture ViewModel within ${timeout}ms")
        return null
    }

    /**
     * Wait for a new bot response after sending a message.
     * @param viewModel The ChatViewModel to monitor
     * @param previousBotMessageCount The number of bot messages before sending the new message
     * @param timeout Maximum time to wait for response
     * @return The bot's response text, or null if timeout
     */
    private fun waitForBotResponse(
        viewModel: com.example.whiz.ui.viewmodels.ChatViewModel,
        previousBotMessageCount: Int,
        timeout: Long = BOT_RESPONSE_TIMEOUT
    ): String? {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val isResponding = viewModel.isResponding.value
            val messages = viewModel.messages.value
            val botMessages = messages.filter { it.type == MessageType.ASSISTANT }

            if (botMessages.size > previousBotMessageCount) {
                val lastBotMessage = botMessages.last()
                val content = lastBotMessage.content

                // Wait until bot is done responding and has non-empty content
                if (!isResponding && content.isNotEmpty()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "Bot responded after ${elapsed}ms")
                    Log.d(TAG, "Bot response: $content")
                    return content
                }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        Log.e(TAG, "Bot did not respond within ${timeout}ms")
        return null
    }

    /**
     * Get current count of bot messages in the ViewModel.
     */
    private fun getBotMessageCount(viewModel: com.example.whiz.ui.viewmodels.ChatViewModel): Int {
        return viewModel.messages.value.filter { it.type == MessageType.ASSISTANT }.size
    }

    /**
     * Check if the response indicates a positive success (not a negated success like "not successful").
     */
    private fun isPositiveSuccess(response: String): Boolean {
        val lowerCase = response.lowercase()

        // Must contain "success"
        if (!lowerCase.contains("success")) {
            return false
        }

        // Check for negative indicators that would negate the success
        val negativeIndicators = listOf(
            "not success",
            "no success",
            "wasn't success",
            "weren't success",
            "wasn't able",
            "unable to",
            "couldn't",
            "could not",
            "failed",
            "error",
            "don't understand",
            "do not understand",
            "not sure what",
            "i can't",
            "i cannot"
        )

        for (indicator in negativeIndicators) {
            if (lowerCase.contains(indicator)) {
                Log.d(TAG, "Found negative indicator '$indicator' in response")
                return false
            }
        }

        return true
    }

    @Test
    fun testSetAndGetParentTaskPreference(): Unit = runBlocking {
        Log.d(TAG, "starting set and get parent task preference test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "step 1: verifying app is ready...")
            composeTestRule.waitForIdle()

            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "FAILURE: app not ready")
                failWithScreenshot("parent_pref_app_not_ready", "app not ready")
                return@runBlocking
            }

            // Step 2: Navigate to new chat
            Log.d(TAG, "step 2: navigating to new chat...")
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "Could not get initial chats: ${e.message}")
                emptyList()
            }

            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    Log.e(TAG, "FAILURE: failed to navigate back")
                    failWithScreenshot("parent_pref_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "FAILURE: failed to navigate to new chat")
                failWithScreenshot("parent_pref_new_chat_failed", "failed to navigate to new chat")
                return@runBlocking
            }

            // Track the new chat
            val currentChats = repository.getAllChats()
            val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
            newChats.forEach { chat ->
                if (!createdChatIds.contains(chat.id)) {
                    createdChatIds.add(chat.id)
                    Log.d(TAG, "Tracked new chat: ${chat.id}")
                }
            }

            // Wait for the navigation-scoped ViewModel to be captured
            val chatViewModel = waitForViewModel()
            if (chatViewModel == null) {
                failWithScreenshot("parent_pref_no_viewmodel", "Failed to capture ViewModel")
                return@runBlocking
            }

            Log.d(TAG, "Using navigation-scoped ViewModel: ${chatViewModel.hashCode()}")

            // Step 3: Set parent task preference to require parent
            Log.d(TAG, "step 3: setting parent task preference...")
            val setPreferenceMessage = "Set my parent task preference to always require a parent task. Reply only with SUCCESS if the operation was successful. - $uniqueTestId"

            val previousBotCount = getBotMessageCount(chatViewModel)

            val messageSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = setPreferenceMessage
            )

            if (!messageSent) {
                Log.e(TAG, "Set preference message not sent or displayed")
                failWithScreenshot("parent_pref_set_message_not_displayed", "set preference message not sent")
                return@runBlocking
            }

            // Step 4: Wait for bot response to set preference
            Log.d(TAG, "step 4: waiting for bot to respond to set preference...")
            val setResponse = waitForBotResponse(chatViewModel, previousBotCount)

            if (setResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to set preference after timeout")
                failWithScreenshot("parent_pref_no_set_response", "bot did not respond to set preference")
                return@runBlocking
            }

            // Verify the set response indicates positive success
            if (!isPositiveSuccess(setResponse)) {
                Log.e(TAG, "FAILURE: set preference response does not indicate success: $setResponse")
                failWithScreenshot("parent_pref_set_no_success", "set preference did not return SUCCESS")
                return@runBlocking
            }

            Log.d(TAG, "Set preference response indicates SUCCESS")

            // Step 5: Get parent task preference to verify it was set
            Log.d(TAG, "step 5: getting parent task preference...")
            val getPreferenceMessage = "What is my parent task preference? Reply with SUCCESS followed by the preference value. - $uniqueTestId"

            val previousBotCountBeforeGet = getBotMessageCount(chatViewModel)

            val getMessageSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = getPreferenceMessage
            )

            if (!getMessageSent) {
                Log.e(TAG, "Get preference message not sent or displayed")
                failWithScreenshot("parent_pref_get_message_not_displayed", "get preference message not sent")
                return@runBlocking
            }

            // Step 6: Wait for bot response to get preference
            Log.d(TAG, "step 6: waiting for bot to respond to get preference...")
            val getResponse = waitForBotResponse(chatViewModel, previousBotCountBeforeGet)

            if (getResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to get preference after timeout")
                failWithScreenshot("parent_pref_no_get_response", "bot did not respond to get preference")
                return@runBlocking
            }

            // Verify the get response indicates positive success
            if (!isPositiveSuccess(getResponse)) {
                Log.e(TAG, "FAILURE: get preference response does not indicate success: $getResponse")
                failWithScreenshot("parent_pref_get_no_success", "get preference did not return SUCCESS")
                return@runBlocking
            }

            // Also verify it mentions the preference value
            val lowerCaseGetResponse = getResponse.lowercase()
            val prefIndicators = listOf("require parent", "true", "always", "enabled")
            val hasPrefInfo = prefIndicators.any { lowerCaseGetResponse.contains(it) }
            if (!hasPrefInfo) {
                Log.e(TAG, "FAILURE: get preference response missing preference info: $getResponse")
                Log.e(TAG, "Expected to find one of: $prefIndicators")
                failWithScreenshot("parent_pref_get_missing_info", "get preference response missing expected info")
                return@runBlocking
            }

            Log.d(TAG, "Get preference response indicates SUCCESS and contains preference information")

            // Step 7: Clean up by turning off the preference
            Log.d(TAG, "step 7: turning off parent task preference...")
            val resetPreferenceMessage = "Turn off the parent task preference. Reply only with SUCCESS if the operation was successful. - $uniqueTestId"

            val previousBotCountBeforeReset = getBotMessageCount(chatViewModel)

            val resetMessageSent = ComposeTestHelper.sendMessageAndVerifyDisplay(
                composeTestRule = composeTestRule,
                message = resetPreferenceMessage
            )

            if (!resetMessageSent) {
                Log.w(TAG, "Reset preference message not sent, preference may remain set")
            } else {
                // Wait for bot response to reset preference
                val resetResponse = waitForBotResponse(chatViewModel, previousBotCountBeforeReset)
                if (resetResponse != null) {
                    if (isPositiveSuccess(resetResponse)) {
                        Log.d(TAG, "Reset preference response indicates SUCCESS")
                        Log.d(TAG, "Preference cleanup completed")
                    } else {
                        Log.w(TAG, "Reset preference response does not indicate success: $resetResponse")
                        Log.w(TAG, "Preference may remain set")
                    }
                } else {
                    Log.w(TAG, "No response to reset preference request, preference may remain set")
                }
            }

            Log.d(TAG, "parent task preference test PASSED!")
            Log.d(TAG, "Test validated: set and get parent task preference tools work correctly")

            // Track final chat ID for cleanup
            try {
                val finalChatId = chatViewModel.chatId.value
                if (finalChatId != null && finalChatId != -1L && finalChatId != 0L && !createdChatIds.contains(finalChatId)) {
                    createdChatIds.add(finalChatId)
                    Log.d(TAG, "Tracked final chat ID: $finalChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not track final chat ID: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Test failed with exception: ${e.message}", e)
            failWithScreenshot("parent_pref_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
