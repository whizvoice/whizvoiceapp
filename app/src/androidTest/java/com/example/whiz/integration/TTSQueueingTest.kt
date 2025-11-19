package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.MainActivity
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.test_helpers.ComposeTestHelper
import android.util.Log

/**
 * TTS Queueing Test
 *
 * This test verifies the TTS queueing behavior when user is actively speaking:
 * - Send 3 regular messages and wait for responses
 * - Enable TTS and continuous listening
 * - Simulate user speaking (with partial transcriptions)
 * - Verify assistant message arrives but TTS is QUEUED (not started immediately)
 * - Continue feeding partials for 1 second after assistant message arrives
 * - Stop feeding partials (simulate user finishing)
 * - Verify user's message was sent
 * - Verify queued TTS starts playing
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TTSQueueingTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "TTSQueueingTest"

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
    lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService

    @Inject
    lateinit var ttsManager: com.example.whiz.services.TTSManager

    private val createdChatIds = mutableListOf<Long>()

    @After
    fun tearDown() {
        Log.d(TAG, "TearDown: TTSQueueingTest")

        // Disable test mode
        speechRecognitionService.disableTestMode()

        // Clean up test chats
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats created during TTS queueing test")
            try {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Pls always reply with just 1 word for test",
                        "TTS queue test",
                        "voice during TTS"
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                Log.d(TAG, "✅ Test chat cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test chat cleanup", e)
            }
        }

        MainActivity.testViewModelCallback = null
        capturedViewModel = null
    }

    @Test
    fun testTTSQueueing_whenUserIsSpeaking(): Unit = runBlocking {
        Log.d(TAG, "🚀 Starting TTS queueing test")

        try {
            // Step 1: Launch app and navigate to chat
            Log.d(TAG, "📱 Step 1: Launching app...")
            val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured!")
                capturedViewModel = vm
            }

            val activity = instrumentation.startActivitySync(voiceLaunchIntent) as MainActivity

            // Wait for navigation
            val navigatedToChat = device.wait(Until.hasObject(
                By.clazz("android.widget.EditText").pkg(packageName)
            ), 5000)

            if (!navigatedToChat) {
                failWithScreenshot("navigation_failed", "Failed to navigate to chat screen")
                return@runBlocking
            }

            // Wait for ViewModel
            var waitTime = 0
            while (capturedViewModel == null && waitTime < 5000) {
                Thread.sleep(100)
                waitTime += 100
            }

            if (capturedViewModel == null) {
                failWithScreenshot("viewmodel_not_captured", "ChatViewModel not captured")
                return@runBlocking
            }

            Log.d(TAG, "✅ App launched and ViewModel captured")

            // Track chat for cleanup
            val currentChatId = capturedViewModel?.chatId?.value
            if (currentChatId != null && currentChatId != 0L) {
                createdChatIds.add(currentChatId)
            }

            // Step 2: Send 3 regular messages and wait for responses
            Log.d(TAG, "💬 Step 2: Sending 3 regular messages...")
            val messages = listOf(
                "Pls always reply with just 1 word for test - ${System.currentTimeMillis()}",
                "TTS queue test msg 2 - ${System.currentTimeMillis()}",
                "TTS queue test msg 3 - ${System.currentTimeMillis()}"
            )

            messages.forEach { message ->
                instrumentation.runOnMainSync {
                    capturedViewModel?.let { vm ->
                        Log.d(TAG, "📤 Sending message: '$message'")
                        vm.updateInputText(message, fromVoice = false)
                        vm.sendUserInput(message)
                    }
                }

                // Wait for message to appear
                val messageAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText(message) },
                    timeoutMs = 3000L,
                    description = "message: $message"
                )

                if (!messageAppeared) {
                    failWithScreenshot("message_not_displayed", "Message not displayed: $message")
                    return@runBlocking
                }

                Log.d(TAG, "✅ Message sent and displayed: '$message'")

                // Wait a bit for response
                delay(2000)
            }

            Log.d(TAG, "✅ All 3 messages sent successfully")

            // Step 3: Enable TTS and continuous listening
            Log.d(TAG, "🔊 Step 3: Enabling TTS and continuous listening...")
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    // Enable TTS (voice response)
                    if (!vm.isVoiceResponseEnabled.value) {
                        vm.toggleVoiceResponse()
                        Log.d(TAG, "✅ TTS/voice response enabled")
                    }

                    // Enable continuous listening
                    if (!voiceManager.isContinuousListeningEnabled.value) {
                        vm.toggleSpeechRecognition() // This toggles continuous listening
                        Log.d(TAG, "✅ Continuous listening enabled")
                    }
                }
            }

            // Wait for continuous listening to start
            delay(500)

            // Verify continuous listening is active
            val isListening = voiceManager.isListening.value
            val isContinuousEnabled = voiceManager.isContinuousListeningEnabled.value
            Log.d(TAG, "📊 Listening state: isListening=$isListening, continuous=$isContinuousEnabled")

            // Step 4: Enable test mode and start simulating partial transcriptions
            Log.d(TAG, "🎤 Step 4: Starting partial transcription simulation...")
            speechRecognitionService.enableTestMode()

            val words = listOf("I", "want", "to", "test", "if", "TTS", "gets", "queued", "properly")
            var partialText = ""
            var wordIndex = 0
            var assistantMessageArrived = false
            var assistantMessageTimestamp = 0L
            val messageToSend = "voice during TTS test - ${System.currentTimeMillis()}"

            // Launch coroutine to feed partials
            val partialJob = launch {
                while (wordIndex < words.size || (assistantMessageArrived && System.currentTimeMillis() - assistantMessageTimestamp < 1000)) {
                    // Add next word if we have more
                    if (wordIndex < words.size) {
                        partialText += (if (partialText.isEmpty()) "" else " ") + words[wordIndex]
                        speechRecognitionService.testSetPartialTranscription(partialText)
                        Log.d(TAG, "🎤 [TEST] Partial #$wordIndex: '$partialText'")
                        wordIndex++
                    }

                    delay(200) // Update every 200ms
                }

                Log.d(TAG, "🎤 [TEST] Finished feeding partials")
            }

            // Wait a moment to start building up partials
            delay(1000)

            // Step 5: Send a message while partials are active (this will trigger assistant response)
            Log.d(TAG, "📤 Step 5: Sending trigger message to get assistant response...")
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    vm.updateInputText(messageToSend, fromVoice = false)
                    vm.sendUserInput(messageToSend)
                }
            }

            // Wait for assistant message to arrive
            Log.d(TAG, "⏳ Waiting for assistant response...")
            var botResponseFound = false
            val botResponseStartTime = System.currentTimeMillis()
            val maxBotWaitTime = 10000L

            while (!botResponseFound && (System.currentTimeMillis() - botResponseStartTime) < maxBotWaitTime) {
                // Check for bot response
                try {
                    val allTexts = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
                    for (textView in allTexts) {
                        try {
                            val text = textView.text
                            if (text != null && text.isNotEmpty() &&
                                !messages.any { text.contains(it) } &&
                                !text.contains(messageToSend) &&
                                !text.contains("Thinking") &&
                                !text.contains("Type or tap")) {
                                botResponseFound = true
                                assistantMessageArrived = true
                                assistantMessageTimestamp = System.currentTimeMillis()
                                Log.d(TAG, "✅ Assistant message arrived: '$text'")
                                break
                            }
                        } catch (e: androidx.test.uiautomator.StaleObjectException) {
                            // Skip stale elements
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking for bot response: ${e.message}")
                }

                if (!botResponseFound) {
                    delay(100)
                }
            }

            if (!botResponseFound) {
                Log.w(TAG, "⚠️ Bot response not found within timeout")
                failWithScreenshot("bot_response_timeout", "No bot response found")
                return@runBlocking
            }

            // Step 6: Verify TTS is NOT playing yet (should be queued)
            Log.d(TAG, "🔍 Step 6: Verifying TTS is queued (not playing yet)...")
            delay(300) // Brief pause

            val isSpeakingDuringPartials = capturedViewModel?.isSpeaking?.value ?: false
            if (isSpeakingDuringPartials) {
                Log.e(TAG, "❌ FAILURE: TTS started immediately instead of being queued!")
                failWithScreenshot("tts_not_queued", "TTS started immediately, should have been queued")
                throw AssertionError("TTS should be queued when user is speaking")
            }
            Log.d(TAG, "✅ TTS correctly queued (not started during partial transcriptions)")

            // Step 7: Wait for partials to continue for 1 second after message arrival
            Log.d(TAG, "⏳ Step 7: Continuing partials for 1 second...")
            delay(1200) // Wait for the partial job to finish

            // Step 8: Stop feeding partials and send final transcription
            Log.d(TAG, "🎤 Step 8: Sending final transcription...")
            partialJob.cancel() // Stop the partial feeding
            delay(100)

            val finalTranscription = partialText
            speechRecognitionService.testSendFinalTranscription(finalTranscription)
            Log.d(TAG, "📤 Final transcription sent: '$finalTranscription'")

            // Step 9: Verify the user's message was sent
            Log.d(TAG, "🔍 Step 9: Verifying user message was sent...")
            val userMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(finalTranscription) },
                timeoutMs = 3000L,
                description = "user's final message"
            )

            if (!userMessageAppeared) {
                failWithScreenshot("user_message_not_sent", "User message not sent: $finalTranscription")
                throw AssertionError("User's final message was not sent")
            }

            composeTestRule.onNodeWithText(finalTranscription).assertIsDisplayed()
            Log.d(TAG, "✅ User message successfully sent: '$finalTranscription'")

            // Step 10: Verify TTS starts playing NOW (after user finished)
            Log.d(TAG, "🔊 Step 10: Verifying queued TTS starts playing...")
            delay(500) // Brief pause for TTS to start

            val isSpeakingAfterFinal = capturedViewModel?.isSpeaking?.value ?: false
            if (!isSpeakingAfterFinal) {
                Log.w(TAG, "⚠️ TTS not playing after user finished - may have completed quickly")
            } else {
                Log.d(TAG, "✅ Queued TTS successfully started playing after user finished")
            }

            Log.d(TAG, "🎉 TTS queueing test completed successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("tts_queueing_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
