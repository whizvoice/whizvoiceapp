package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.services.BubbleOverlayService

/**
 * Integration test for web search functionality.
 *
 * Tests the server's ability to:
 * - Use the web_search tool to find information
 * - Return accurate results with source citations
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WebSearchTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "WebSearchTest"
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    private val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private val createdChatIds = mutableListOf<Long>()
    private val uniqueTestId = System.currentTimeMillis()

    // Track the navigation-scoped ViewModel
    private var navigationScopedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()

        // Set up callback to capture the navigation-scoped ViewModel
        MainActivity.testViewModelCallback = { viewModel ->
            Log.d(TAG, "📝 Captured navigation-scoped ViewModel: ${viewModel.hashCode()}")
            navigationScopedViewModel = viewModel
        }

        Log.d(TAG, "🧪 web search test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 cleaning up web search test")
            try {
                // Stop bubble service if active (tool execution can trigger bubble mode)
                if (BubbleOverlayService.isActive) {
                    Log.d(TAG, "Stopping bubble service...")
                    BubbleOverlayService.stop(instrumentation.targetContext)
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < 1000L) {
                        if (!BubbleOverlayService.isActive) {
                            Log.d(TAG, "Bubble service stopped")
                            break
                        }
                        delay(100)
                    }
                }

                Log.d(TAG, "🔍 About to cleanup. Tracked chat IDs: $createdChatIds")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "al gore",
                        "birthday",
                        uniqueTestId.toString()
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                ComposeTestHelper.cleanup()
                Log.d(TAG, "✅ test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test chat cleanup", e)
            } finally {
                // Clean up the test callback
                MainActivity.testViewModelCallback = null
                navigationScopedViewModel = null
            }
        }
    }

    @Test
    fun testWebSearch(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting web search test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "📱 step 1: verifying app is ready...")
            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: app not ready")
                failWithScreenshot("websearch_app_not_ready", "app not ready")
                return@runBlocking
            }

            // Step 2: Navigate to new chat
            Log.d(TAG, "➕ step 2: navigating to new chat...")
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not get initial chats: ${e.message}")
                emptyList()
            }

            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    Log.e(TAG, "❌ FAILURE: failed to navigate back")
                    failWithScreenshot("websearch_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: failed to navigate to new chat")
                failWithScreenshot("websearch_new_chat_failed", "failed to navigate to new chat")
                return@runBlocking
            }

            // Track the new chat
            val currentChats = repository.getAllChats()
            val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
            newChats.forEach { chat ->
                if (!createdChatIds.contains(chat.id)) {
                    createdChatIds.add(chat.id)
                    Log.d(TAG, "📝 Tracked new chat: ${chat.id}")
                }
            }

            // Wait for the navigation-scoped ViewModel to be captured
            var chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
            val viewModelCaptureStart = System.currentTimeMillis()
            while (chatViewModel == null && System.currentTimeMillis() - viewModelCaptureStart < 5000) {
                chatViewModel = navigationScopedViewModel
                if (chatViewModel == null) {
                    delay(100)
                }
            }

            if (chatViewModel == null) {
                Log.e(TAG, "❌ Failed to capture navigation-scoped ViewModel")
                failWithScreenshot("websearch_no_viewmodel", "Failed to capture ViewModel")
                return@runBlocking
            }

            Log.d(TAG, "✅ Using navigation-scoped ViewModel: ${chatViewModel.hashCode()}")

            // Step 3: Send message asking about Al Gore's birthday
            Log.d(TAG, "💬 step 3: sending web search message...")
            val searchMessage = "What's Al Gore's birthday? Please search the internet and cite your sources. - $uniqueTestId"

            val messageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = searchMessage
            )

            if (!messageSent) {
                Log.e(TAG, "❌ Web search message not sent or displayed")
                failWithScreenshot("websearch_message_not_displayed", "web search message not sent")
                return@runBlocking
            }

            // Step 4: Wait for bot response
            Log.d(TAG, "⏳ step 4: waiting for bot to respond...")
            var botResponded = false
            var botResponseText = ""
            val responseStartTime = System.currentTimeMillis()
            val responseTimeout = 30000L // 30 seconds for bot response

            while (System.currentTimeMillis() - responseStartTime < responseTimeout) {
                val isResponding = chatViewModel.isResponding.value
                val messages = chatViewModel.messages.value

                // Look for bot response (message from type ASSISTANT)
                val botMessages = messages.filter { it.type == com.example.whiz.data.local.MessageType.ASSISTANT }
                if (botMessages.isNotEmpty()) {
                    val lastBotMessage = botMessages.last()
                    botResponseText = lastBotMessage.content

                    // Make sure the bot is done responding
                    if (!isResponding && botResponseText.isNotEmpty()) {
                        botResponded = true
                        val elapsed = System.currentTimeMillis() - responseStartTime
                        Log.d(TAG, "✅ Bot responded after ${elapsed}ms")
                        Log.d(TAG, "📝 Bot response: $botResponseText")
                        break
                    }
                }
                delay(200)
            }

            if (!botResponded) {
                Log.e(TAG, "❌ FAILURE: bot did not respond after timeout")
                failWithScreenshot("websearch_no_bot_response", "bot did not respond")
                return@runBlocking
            }

            // Step 4.5: Verify message list contains the bot's response
            Log.d(TAG, "📱 step 4.5: bot response received and verified in ViewModel")
            Log.d(TAG, "✅ Bot response confirmed: ${botResponseText.take(100)}...")

            // Step 5: Verify response contains the correct birth year (1948)
            Log.d(TAG, "🔍 step 5: verifying response contains correct birth year...")
            val lowerCaseResponse = botResponseText.lowercase()

            if (!botResponseText.contains("1948")) {
                Log.e(TAG, "❌ FAILURE: bot response does not contain '1948' (Al Gore's birth year)")
                Log.e(TAG, "❌ Full response: $botResponseText")
                failWithScreenshot("websearch_missing_birth_year", "Response does not contain 1948")
                return@runBlocking
            }
            Log.d(TAG, "✅ Bot response contains correct birth year (1948)")

            // Step 6: Verify response contains source citations
            Log.d(TAG, "🔍 step 6: verifying response contains source citations...")
            val hasSourcesLabel = lowerCaseResponse.contains("sources:")
            val hasUrl = botResponseText.contains("http")

            if (!hasSourcesLabel && !hasUrl) {
                Log.e(TAG, "❌ FAILURE: bot response does not contain source citations")
                Log.e(TAG, "❌ Expected 'Sources:' section or URLs (http)")
                Log.e(TAG, "❌ Full response: $botResponseText")
                failWithScreenshot("websearch_missing_citations", "Response does not contain citations")
                return@runBlocking
            }
            Log.d(TAG, "✅ Bot response contains source citations (Sources: $hasSourcesLabel, URL: $hasUrl)")

            // Step 7: Verify response does not contain error words
            Log.d(TAG, "🔍 step 7: verifying response does not contain error indicators...")
            val errorWords = listOf("error", "wrong")
            val foundErrors = errorWords.filter { lowerCaseResponse.contains(it) }

            if (foundErrors.isNotEmpty()) {
                Log.e(TAG, "❌ FAILURE: bot response contains error words: $foundErrors")
                Log.e(TAG, "❌ Full response: $botResponseText")
                failWithScreenshot("websearch_response_has_errors", "Response contains: $foundErrors")
                return@runBlocking
            }
            Log.d(TAG, "✅ Bot response does not contain error words")

            Log.d(TAG, "🎉 web search test PASSED!")
            Log.d(TAG, "✅ Test validated: web search returned accurate result with citations")

            // Track final chat ID for cleanup
            try {
                val finalChatId = chatViewModel.chatId.value
                if (finalChatId != null && finalChatId != -1L && finalChatId != 0L && !createdChatIds.contains(finalChatId)) {
                    createdChatIds.add(finalChatId)
                    Log.d(TAG, "📝 Tracked final chat ID: $finalChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track final chat ID: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("websearch_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
