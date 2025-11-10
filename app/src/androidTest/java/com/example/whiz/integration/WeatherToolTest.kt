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

/**
 * Integration test for weather tool functionality.
 *
 * Tests the server's ability to:
 * - Get weather forecast using the get_weather tool
 * - Return valid weather data without errors
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WeatherToolTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "WeatherToolTest"
    }

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    private val createdChatIds = mutableListOf<Long>()
    private val uniqueTestId = System.currentTimeMillis()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        Log.d(TAG, "🧪 weather tool test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 cleaning up weather test chats")
            try {
                Log.d(TAG, "🔍 About to cleanup. Tracked chat IDs: $createdChatIds")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "what's the weather",
                        "weather",
                        uniqueTestId.toString()
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                ComposeTestHelper.cleanup()
                Log.d(TAG, "✅ test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test chat cleanup", e)
            }
        }
    }

    @Test
    fun testWeatherTool(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting weather tool test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "📱 step 1: verifying app is ready...")
            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: app not ready")
                failWithScreenshot("weather_app_not_ready", "app not ready")
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
                    failWithScreenshot("weather_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: failed to navigate to new chat")
                failWithScreenshot("weather_new_chat_failed", "failed to navigate to new chat")
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

            // Get ChatViewModel for state checking
            val chatViewModel = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.example.whiz.ui.viewmodels.ChatViewModel::class.java]

            // Step 3: Send message asking for weather in San Francisco
            Log.d(TAG, "💬 step 3: sending message asking for weather in San Francisco...")
            val weatherMessage = "What's the weather in San Francisco? - $uniqueTestId"

            val messageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = weatherMessage
            )

            if (!messageSent) {
                Log.e(TAG, "❌ Weather message not sent or displayed")
                failWithScreenshot("weather_message_not_displayed", "weather message not sent")
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
                delay(500)
            }

            if (!botResponded) {
                Log.e(TAG, "❌ FAILURE: bot did not respond after timeout")
                failWithScreenshot("weather_no_bot_response", "bot did not respond")
                return@runBlocking
            }

            // Step 5: Verify response does not contain error words
            Log.d(TAG, "🔍 step 5: verifying response does not contain error indicators...")
            val lowerCaseResponse = botResponseText.lowercase()

            val errorWords = listOf("error", "wrong")
            val foundErrors = errorWords.filter { lowerCaseResponse.contains(it) }

            if (foundErrors.isNotEmpty()) {
                Log.e(TAG, "❌ FAILURE: bot response contains error words: $foundErrors")
                Log.e(TAG, "❌ Full response: $botResponseText")
                failWithScreenshot("weather_response_has_errors", "Response contains: $foundErrors")
                return@runBlocking
            }

            Log.d(TAG, "✅ Bot response does not contain error words")

            // Optional: Check for weather-related keywords to ensure it's actually about weather
            val weatherKeywords = listOf("weather", "temperature", "forecast", "°", "degrees", "cloudy", "sunny", "rain", "wind")
            val hasWeatherKeywords = weatherKeywords.any { lowerCaseResponse.contains(it) }

            if (!hasWeatherKeywords) {
                Log.w(TAG, "⚠️ Warning: Bot response doesn't seem to contain weather information")
                Log.w(TAG, "⚠️ Response: $botResponseText")
                // Not failing the test, just logging a warning
            } else {
                Log.d(TAG, "✅ Bot response appears to contain weather information")
            }

            Log.d(TAG, "🎉 weather tool test PASSED!")
            Log.d(TAG, "✅ Test validated: weather query returned valid response without errors")

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
            failWithScreenshot("weather_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
