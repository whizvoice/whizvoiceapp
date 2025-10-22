package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.services.BubbleOverlayService

/**
 * Integration test for YouTube Music tools.
 *
 * Tests the ability to:
 * 1. Play songs on YouTube Music via chat mode
 * 2. Queue songs on YouTube Music via bubble overlay mode (voice transcription)
 *
 * User flow:
 * - Send typed message: "Hey can you play Golden from Kpop Demon Hunters and then How it's done from Kpop Demon Hunters?"
 * - Switch to bubble overlay mode
 * - Send voice transcription: "can you add What it sounds like by Huntrix to the music queue?"
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class YouTubeMusicIntegrationTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "YouTubeMusicTest"

        // Capture ViewModel from navigation scope for voice mode tests
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager

    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    private val createdChatIds = mutableListOf<Long>()
    private val uniqueTestId = System.currentTimeMillis()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        Log.d(TAG, "🧪 YouTube Music integration test setup complete")

        // Grant microphone permission for voice mode tests
        Log.d(TAG, "🎙️ Granting microphone permission")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 cleaning up YouTube Music test chats")
            try {
                // Stop bubble service if active
                if (BubbleOverlayService.isActive) {
                    Log.d(TAG, "🔵 Stopping bubble service...")
                    BubbleOverlayService.stop(instrumentation.targetContext)
                    // Wait for service to actually stop
                    val cleanupStartTime = System.currentTimeMillis()
                    val cleanupTimeoutMs = 1000L
                    var stopped = false
                    while (System.currentTimeMillis() - cleanupStartTime < cleanupTimeoutMs) {
                        if (!BubbleOverlayService.isActive) {
                            stopped = true
                            Log.d(TAG, "✅ Bubble service stopped")
                            break
                        }
                        delay(100)
                    }
                    if (!stopped) {
                        Log.w(TAG, "⚠️ Bubble service did not stop within timeout")
                    }
                }

                Log.d(TAG, "🔍 About to cleanup. Tracked chat IDs: $createdChatIds")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "play Golden from Kpop Demon Hunters",
                        "How it's done from Kpop Demon Hunters",
                        "What it sounds like by Huntrix",
                        "youtube music",
                        "YouTube Music",
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

        MainActivity.testViewModelCallback = null
        capturedViewModel = null
    }

    @Test
    fun testYouTubeMusicIntegration(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting YouTube Music integration test")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "📱 step 1: verifying app is ready...")
            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: app not ready")
                failWithScreenshot("youtube_music_app_not_ready", "app not ready")
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
                    failWithScreenshot("youtube_music_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: failed to navigate to new chat")
                failWithScreenshot("youtube_music_new_chat_failed", "failed to navigate to new chat")
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

            // Step 3: Send message to play two songs on YouTube Music
            Log.d(TAG, "🎵 step 3: sending message to play songs on YouTube Music...")
            val playMessage = "Hey can you play Golden from Kpop Demon Hunters and then How it's done from Kpop Demon Hunters on YouTube Music? - $uniqueTestId"

            val playMessageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = playMessage
            )

            if (!playMessageSent) {
                Log.e(TAG, "❌ Play message not sent or displayed")
                failWithScreenshot("youtube_music_play_message_not_displayed", "play message not sent")
                return@runBlocking
            }

            // Step 4: Wait for bot to process and play songs
            Log.d(TAG, "⏳ step 4: waiting for bot to execute YouTube Music play tools...")
            // We'll wait for bot response which indicates tool execution completed
            // This is a longer timeout because we need to:
            // 1. Open YouTube Music
            // 2. Search for and play first song
            // 3. Search for and play second song
            val botResponseTimeout = 60000L // 60 seconds for two song plays
            val playStartTime = System.currentTimeMillis()
            var botResponded = false

            while (System.currentTimeMillis() - playStartTime < botResponseTimeout) {
                // Check if we have a bot response (assistant message after our user message)
                try {
                    val chatId = chatViewModel.chatId.value
                    if (chatId != null && chatId > 0) {
                        val messages = repository.getMessagesForChat(chatId).first()
                        // Look for assistant message after our play request
                        val assistantMessages = messages.filter {
                            it.type == com.example.whiz.data.local.MessageType.ASSISTANT && it.timestamp > (playStartTime - 5000)
                        }
                        if (assistantMessages.isNotEmpty()) {
                            botResponded = true
                            val elapsed = System.currentTimeMillis() - playStartTime
                            Log.d(TAG, "✅ Bot responded after ${elapsed}ms")
                            Log.d(TAG, "Bot response: ${assistantMessages.last().content}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking for bot response: ${e.message}")
                }
                delay(500)
            }

            if (!botResponded) {
                Log.e(TAG, "❌ FAILURE: bot did not respond after play request")
                failWithScreenshot("youtube_music_no_bot_response_play", "bot did not respond to play request")
                return@runBlocking
            }

            Log.d(TAG, "✅ Bot successfully processed play songs request")

            // Step 5: Switch to bubble overlay mode by opening another app
            Log.d(TAG, "📱 step 5: switching to bubble overlay mode...")

            // Send voice transcription to trigger bubble mode by opening an app
            val openAppMessage = "Open YouTube Music"

            try {
                val transcriptionCallbackField = voiceManager.javaClass.getDeclaredField("transcriptionCallback")
                transcriptionCallbackField.isAccessible = true
                val callback = transcriptionCallbackField.get(voiceManager) as? ((String) -> Unit)

                if (callback != null) {
                    instrumentation.runOnMainSync {
                        Log.d(TAG, "🎤 Invoking transcription callback with: '$openAppMessage'")
                        callback.invoke(openAppMessage)
                        Log.d(TAG, "✅ Transcription callback invoked - should trigger bubble mode")
                    }
                } else {
                    Log.w(TAG, "⚠️ Transcription callback not available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not send voice transcription: ${e.message}")
            }

            // Wait for bubble overlay to become active
            Log.d(TAG, "⏳ Waiting for bubble overlay mode to activate...")
            var bubbleStarted = false
            val bubbleStartTime = System.currentTimeMillis()
            val bubbleTimeout = 15000L

            while (System.currentTimeMillis() - bubbleStartTime < bubbleTimeout) {
                if (BubbleOverlayService.isActive) {
                    bubbleStarted = true
                    val elapsed = System.currentTimeMillis() - bubbleStartTime
                    Log.d(TAG, "✅ Bubble service became active after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!bubbleStarted) {
                Log.e(TAG, "❌ FAILURE: bubble overlay did not activate")
                failWithScreenshot("youtube_music_bubble_not_active", "bubble not activated")
                return@runBlocking
            }

            // Step 6: Send voice transcription to queue a song while in bubble mode
            Log.d(TAG, "🎵 step 6: sending voice transcription to queue a song...")
            val queueMessage = "can you add What it sounds like by Huntrix to the YouTube Music queue?"

            val queueIntent = Intent("com.example.whiz.TEST_TRANSCRIPTION").apply {
                putExtra("text", queueMessage)
                putExtra("fromVoice", true)
                putExtra("autoSend", true)
            }
            instrumentation.targetContext.sendBroadcast(queueIntent)
            Log.d(TAG, "✅ Sent transcription broadcast: '$queueMessage'")

            // Step 7: Wait for bot to process and queue the song
            Log.d(TAG, "⏳ step 7: waiting for bot to execute YouTube Music queue tool...")
            // This is a longer timeout because we need to:
            // 1. Process the voice transcription
            // 2. Search for the song
            // 3. Open context menu
            // 4. Click "Add to queue"
            val queueResponseTimeout = 45000L // 45 seconds for queueing
            val queueStartTime = System.currentTimeMillis()
            var queueBotResponded = false

            while (System.currentTimeMillis() - queueStartTime < queueResponseTimeout) {
                // Check if we have a bot response
                try {
                    val chatId = chatViewModel.chatId.value
                    if (chatId != null && chatId > 0) {
                        val messages = repository.getMessagesForChat(chatId).first()
                        // Look for assistant message after our queue request
                        val assistantMessages = messages.filter {
                            it.type == com.example.whiz.data.local.MessageType.ASSISTANT && it.timestamp > (queueStartTime - 5000)
                        }
                        // Check if any message mentions queueing or adding to queue
                        val queueResponse = assistantMessages.find {
                            it.content.contains("queue", ignoreCase = true) ||
                            it.content.contains("Huntrix", ignoreCase = true) ||
                            it.content.contains("What it sounds like", ignoreCase = true)
                        }
                        if (queueResponse != null) {
                            queueBotResponded = true
                            val elapsed = System.currentTimeMillis() - queueStartTime
                            Log.d(TAG, "✅ Bot responded to queue request after ${elapsed}ms")
                            Log.d(TAG, "Bot response: ${queueResponse.content}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking for queue response: ${e.message}")
                }
                delay(500)
            }

            if (!queueBotResponded) {
                Log.e(TAG, "❌ FAILURE: bot did not respond after queue request")
                failWithScreenshot("youtube_music_no_bot_response_queue", "bot did not respond to queue request")
                return@runBlocking
            }

            Log.d(TAG, "✅ Bot successfully processed queue song request")

            // Step 8: Verify bubble overlay is still active
            Log.d(TAG, "🔍 step 8: verifying bubble overlay is still active...")
            if (!BubbleOverlayService.isActive) {
                Log.e(TAG, "❌ FAILURE: bubble overlay should still be active")
                failWithScreenshot("youtube_music_bubble_not_active_at_end", "bubble not active at end")
                return@runBlocking
            }
            Log.d(TAG, "✅ Bubble overlay still active")

            Log.d(TAG, "🎉 YouTube Music integration test PASSED!")
            Log.d(TAG, "✅ Test validated: play two songs in chat mode, queue song in bubble overlay mode")

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
            failWithScreenshot("youtube_music_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
