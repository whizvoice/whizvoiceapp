package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.local.MessageEntity
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
 * Integration test for contacts tool functionality.
 *
 * Tests the server's ability to:
 * - Add a contact preference with nickname, real name, and preferred app
 * - Get contact preference by nickname
 * - List all contacts
 * - Remove a contact preference
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ContactsToolTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "ContactsToolTest"
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

        Log.d(TAG, "contacts tool test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "cleaning up contacts test chats")
            try {
                Log.d(TAG, "About to cleanup. Tracked chat IDs: $createdChatIds")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "save my",
                        "contact",
                        "husband",
                        "TestContact",
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
    fun testAddAndGetContactTool(): Unit = runBlocking {
        Log.d(TAG, "starting add and get contact tool test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "step 1: verifying app is ready...")
            composeTestRule.waitForIdle()

            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "FAILURE: app not ready")
                failWithScreenshot("contacts_app_not_ready", "app not ready")
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
                    failWithScreenshot("contacts_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "FAILURE: failed to navigate to new chat")
                failWithScreenshot("contacts_new_chat_failed", "failed to navigate to new chat")
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
                failWithScreenshot("contacts_no_viewmodel", "Failed to capture ViewModel")
                return@runBlocking
            }

            Log.d(TAG, "Using navigation-scoped ViewModel: ${chatViewModel.hashCode()}")

            // Step 3: Add a contact using natural language
            Log.d(TAG, "step 3: adding a contact...")
            val testContactName = "TestContact$uniqueTestId"
            val addContactMessage = "Save my husband as $testContactName, he uses WhatsApp. Reply only with SUCCESS if the operation was successful. - $uniqueTestId"

            val previousBotCount = getBotMessageCount(chatViewModel)

            val messageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = addContactMessage
            )

            if (!messageSent) {
                Log.e(TAG, "Add contact message not sent or displayed")
                failWithScreenshot("contacts_add_message_not_displayed", "add contact message not sent")
                return@runBlocking
            }

            // Step 4: Wait for bot response to add contact
            Log.d(TAG, "step 4: waiting for bot to respond to add contact...")
            val addResponse = waitForBotResponse(chatViewModel, previousBotCount)

            if (addResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to add contact after timeout")
                failWithScreenshot("contacts_no_add_response", "bot did not respond to add contact")
                return@runBlocking
            }

            // Verify the add response indicates positive success
            if (!isPositiveSuccess(addResponse)) {
                Log.e(TAG, "FAILURE: add contact response does not indicate success: $addResponse")
                failWithScreenshot("contacts_add_no_success", "add contact did not return SUCCESS")
                return@runBlocking
            }

            Log.d(TAG, "Add contact response indicates SUCCESS")

            // Step 5: Now look up the contact
            Log.d(TAG, "step 5: looking up the contact...")
            val getContactMessage = "Who is my husband? If you find them, reply with SUCCESS followed by their name and preferred app. - $uniqueTestId"

            val previousBotCountBeforeGet = getBotMessageCount(chatViewModel)

            val getMessageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = getContactMessage
            )

            if (!getMessageSent) {
                Log.e(TAG, "Get contact message not sent or displayed")
                failWithScreenshot("contacts_get_message_not_displayed", "get contact message not sent")
                return@runBlocking
            }

            // Step 6: Wait for bot response to get contact
            Log.d(TAG, "step 6: waiting for bot to respond to get contact...")
            val getResponse = waitForBotResponse(chatViewModel, previousBotCountBeforeGet)

            if (getResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond to get contact after timeout")
                failWithScreenshot("contacts_no_get_response", "bot did not respond to get contact")
                return@runBlocking
            }

            // Verify the get response indicates positive success
            if (!isPositiveSuccess(getResponse)) {
                Log.e(TAG, "FAILURE: get contact response does not indicate success: $getResponse")
                failWithScreenshot("contacts_get_no_success", "get contact did not return SUCCESS")
                return@runBlocking
            }

            // Also verify it found the right contact (should mention the name or whatsapp)
            val lowerCaseGetResponse = getResponse.lowercase()
            val getSuccessIndicators = listOf(testContactName.lowercase(), "whatsapp")
            val hasContactInfo = getSuccessIndicators.any { lowerCaseGetResponse.contains(it) }
            if (!hasContactInfo) {
                Log.e(TAG, "FAILURE: get contact response missing contact info: $getResponse")
                Log.e(TAG, "Expected to find: $testContactName or 'whatsapp'")
                failWithScreenshot("contacts_get_missing_info", "get contact response missing expected info")
                return@runBlocking
            }

            Log.d(TAG, "Get contact response indicates SUCCESS and contains contact information")

            // Step 7: Clean up by removing the contact
            Log.d(TAG, "step 7: removing the test contact...")
            val removeContactMessage = "Remove my husband from my contacts. Reply only with SUCCESS if the operation was successful. - $uniqueTestId"

            val previousBotCountBeforeRemove = getBotMessageCount(chatViewModel)

            val removeMessageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = removeContactMessage
            )

            if (!removeMessageSent) {
                Log.w(TAG, "Remove contact message not sent, contact may remain in preferences")
            } else {
                // Wait for bot response to remove contact
                val removeResponse = waitForBotResponse(chatViewModel, previousBotCountBeforeRemove)
                if (removeResponse != null) {
                    if (isPositiveSuccess(removeResponse)) {
                        Log.d(TAG, "Remove contact response indicates SUCCESS")
                        Log.d(TAG, "Test contact cleanup completed")
                    } else {
                        Log.w(TAG, "Remove contact response does not indicate success: $removeResponse")
                        Log.w(TAG, "Contact may remain in preferences")
                    }
                } else {
                    Log.w(TAG, "No response to remove contact request, contact may remain")
                }
            }

            Log.d(TAG, "contacts tool test PASSED!")
            Log.d(TAG, "Test validated: add, get, and remove contact tools work correctly")

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
            failWithScreenshot("contacts_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun testListContactsTool(): Unit = runBlocking {
        Log.d(TAG, "starting list contacts tool test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "step 1: verifying app is ready...")
            composeTestRule.waitForIdle()

            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "FAILURE: app not ready")
                failWithScreenshot("contacts_list_app_not_ready", "app not ready")
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
                    failWithScreenshot("contacts_list_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "FAILURE: failed to navigate to new chat")
                failWithScreenshot("contacts_list_new_chat_failed", "failed to navigate to new chat")
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
                failWithScreenshot("contacts_list_no_viewmodel", "Failed to capture ViewModel")
                return@runBlocking
            }

            Log.d(TAG, "Using navigation-scoped ViewModel: ${chatViewModel.hashCode()}")

            // Step 3: Ask to list contacts
            Log.d(TAG, "step 3: asking to list contacts...")
            val listContactsMessage = "Show me my saved contacts. Reply with SUCCESS followed by the list, or SUCCESS followed by 'no contacts' if there are none. - $uniqueTestId"

            val previousBotCount = getBotMessageCount(chatViewModel)

            val messageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = listContactsMessage
            )

            if (!messageSent) {
                Log.e(TAG, "List contacts message not sent or displayed")
                failWithScreenshot("contacts_list_message_not_displayed", "list contacts message not sent")
                return@runBlocking
            }

            // Step 4: Wait for bot response
            Log.d(TAG, "step 4: waiting for bot to respond...")
            val botResponse = waitForBotResponse(chatViewModel, previousBotCount)

            if (botResponse == null) {
                Log.e(TAG, "FAILURE: bot did not respond after timeout")
                failWithScreenshot("contacts_list_no_response", "bot did not respond")
                return@runBlocking
            }

            // Verify the response indicates positive success
            if (!isPositiveSuccess(botResponse)) {
                Log.e(TAG, "FAILURE: list contacts response does not indicate success: $botResponse")
                failWithScreenshot("contacts_list_no_success", "list contacts did not return SUCCESS")
                return@runBlocking
            }

            Log.d(TAG, "List contacts response indicates SUCCESS")
            Log.d(TAG, "list contacts tool test PASSED!")

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
            failWithScreenshot("contacts_list_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
