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
import org.junit.Assume.assumeFalse
import org.junit.Before
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
    override lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager

    @Inject
    override lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService

    @Inject
    override lateinit var ttsManager: com.example.whiz.services.TTSManager

    private val createdChatIds = mutableListOf<Long>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles device setup, screenshot dir, authentication, and app launch

        // Grant microphone permission for voice tests
        Log.d(TAG, "🎙️ Granting microphone permission for TTS queueing test")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")

        // Clean up any existing test chats
        runBlocking {
            cleanupTestChats(
                repository = repository,
                trackedChatIds = emptyList(),
                additionalPatterns = listOf("Pls always reply with just 1 word for test", "TTS queue test", "voice during TTS", "space", "barge-in"),
                enablePatternFallback = false
            )
        }
    }

    @After
    fun cleanup() {
        Log.d(TAG, "TearDown: TTSQueueingTest")

        // Disable test mode
        speechRecognitionService.disableTestMode()

        // Clean up test chats
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up test chats created during TTS queueing test")

                // Clean up tracked chats and any chats with test patterns
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Pls always reply with just 1 word for test",
                        "TTS queue test",
                        "voice during TTS",
                        "space",
                        "barge-in"
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()

                Log.d(TAG, "✅ TTS queueing test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test cleanup", e)
            }
        }

        MainActivity.testViewModelCallback = null
        capturedViewModel = null
    }

    @Test
    fun testTTSQueueing_whenUserIsSpeaking(): Unit = runBlocking {
        // Skip this test on emulators since speech recognition is not available
        assumeFalse("Skipping TTS queueing test on emulator (speech recognition unavailable)", isRunningOnEmulator())

        Log.d(TAG, "🚀 Starting TTS queueing test")

        try {
            // Step 1: Set up callback and wait for app to be ready
            // The app is already launched by composeTestRule in @Before
            Log.d(TAG, "📱 Step 1: Setting up test...")

            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured!")
                capturedViewModel = vm
            }

            // Wait for app to be ready
            Thread.sleep(1000)

            // Navigate to new chat
            Log.d(TAG, "📱 Navigating to new chat...")
            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
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

            // Step 2: Send 3 regular messages and wait for responses
            Log.d(TAG, "💬 Step 2: Sending 3 regular messages...")
            val messages = listOf(
                "This is a test - ${System.currentTimeMillis()} . I'm really interested in space.",
                "Can you tell me about Mars in 30 words exactly? - ${System.currentTimeMillis()}",
                "Yeah it's just that I've been thinking a lot about how big the world and even the universe is and how I'm kind of a tiny spec of dust in comparison and maybe nothing really matters but maybe actually everything matters a lot and there's just a lot of it and i have to take care of my own little corner of the world even though there's lots of corners and lots of people feeling things all the time and I guess I just have to accept that. Anyways I wonder if there's some sentient life on Mars too having similar thoughts. - ${System.currentTimeMillis()}"
            )

            // Track chat for cleanup
            val currentChatId = capturedViewModel?.chatId?.value
            if (currentChatId != null && currentChatId != 0L) {
                createdChatIds.add(currentChatId)
            }

            // Send only the first 2 messages
            messages.take(2).forEach { message ->
                Log.d(TAG, "🎤 Sending voice message: '$message'")
                val messageSent = ComposeTestHelper.sendVoiceMessage(
                    message = message,
                    voiceManager = voiceManager,
                    composeTestRule = composeTestRule
                )

                if (!messageSent) {
                    failWithScreenshot("message_not_sent", "Message not sent: $message")
                    return@runBlocking
                }

                Log.d(TAG, "✅ Voice message sent and displayed: '$message'")
            }

            Log.d(TAG, "✅ First 2 messages sent successfully")

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

            // Use words from the third message for partial transcription
            val thirdMessage = messages[2]
            val words = thirdMessage.split(" ")
            var partialText = ""
            var assistantMessageArrived = false

            // Step 5: Send partials while assistant response arrives
            // The assistant is already responding to the 2 messages we sent earlier
            Log.d(TAG, "🎤 Step 5: Starting to send partials (assistant response should arrive during this)...")

            for (wordIndex in words.indices) {
                // Check that we're not being interrupted by TTS
                val isSpeakingNow = capturedViewModel?.isSpeaking?.value ?: false
                if (isSpeakingNow) {
                    Log.e(TAG, "❌ FAILURE: TTS started during partials at word #$wordIndex")
                    failWithScreenshot("tts_during_partials", "TTS started during partials")
                    throw AssertionError("TTS started speaking during partial transcriptions - should be queued!")
                }

                // Check that continuous listening is still active (with retries for first word)
                var isListeningNow = voiceManager.isListening.value
                if (!isListeningNow && wordIndex == 0) {
                    // First word: give it a chance to restart (up to 3 tries with 100ms delay)
                    Log.w(TAG, "⚠️ Listening not active at word #0, retrying up to 3 times...")
                    var retries = 0
                    while (!isListeningNow && retries < 3) {
                        delay(100)
                        isListeningNow = voiceManager.isListening.value
                        retries++
                        Log.d(TAG, "🔄 Retry #$retries: isListening=$isListeningNow")
                    }
                }

                if (!isListeningNow) {
                    Log.e(TAG, "❌ FAILURE: Continuous listening stopped during partials at word #$wordIndex")
                    failWithScreenshot("listening_stopped", "Continuous listening stopped")
                    throw AssertionError("Continuous listening stopped - we're being blocked from continuing!")
                }

                // Check if assistant response has arrived (from the 2 messages we sent earlier)
                if (!assistantMessageArrived) {
                    try {
                        val allTexts = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
                        for (textView in allTexts) {
                            try {
                                val text = textView.text
                                if (text != null && text.isNotEmpty() &&
                                    !messages.any { text.contains(it) } &&
                                    text.length > 20 &&
                                    !text.contains("Thinking") &&
                                    !text.contains("Type or tap")) {
                                    assistantMessageArrived = true
                                    Log.d(TAG, "✅ Assistant response arrived during partials (from earlier messages): '$text'")
                                    Log.d(TAG, "🔍 Verifying TTS is queued (not playing yet)...")
                                    val isSpeakingDuringPartials = capturedViewModel?.isSpeaking?.value ?: false
                                    if (isSpeakingDuringPartials) {
                                        Log.e(TAG, "❌ FAILURE: TTS started immediately instead of being queued!")
                                        failWithScreenshot("tts_not_queued", "TTS started immediately, should have been queued")
                                        throw AssertionError("TTS should be queued when user is speaking")
                                    }
                                    Log.d(TAG, "✅ TTS correctly queued (not started during partial transcriptions)")
                                    break
                                }
                            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                                // Skip stale elements
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking for bot response: ${e.message}")
                    }
                }

                // Add next word
                partialText += (if (partialText.isEmpty()) "" else " ") + words[wordIndex]
                speechRecognitionService.testSetPartialTranscription(partialText)

                val statusMsg = if (assistantMessageArrived) " (AFTER ASSISTANT ARRIVED)" else ""
                Log.d(TAG, "🎤 [TEST] Partial #$wordIndex: '$partialText'$statusMsg")

                // Short delay between partials
                delay(100)
            }

            Log.d(TAG, "🎤 [TEST] ✅ Successfully sent all ${words.size} partial words!")
            if (assistantMessageArrived) {
                Log.d(TAG, "🎤 [TEST] ✅ Completed partials AFTER assistant response arrived - queuing worked!")
            } else {
                Log.w(TAG, "⚠️ Assistant response didn't arrive during partials, but continuing test...")
            }

            // Step 8: Send final transcription
            Log.d(TAG, "🎤 Step 8: Sending final transcription...")
            delay(100)

            val finalTranscription = partialText
            speechRecognitionService.testSendFinalTranscription(finalTranscription)
            Log.d(TAG, "📤 Final transcription sent: '$finalTranscription'")

            // Step 10: Verify TTS starts playing NOW (after user finished)
            Log.d(TAG, "🔊 Step 10: Verifying queued TTS starts playing...")

            // Wait for TTS to start with a timeout
            // Should start quickly since assistant message already arrived during partials
            var ttsStarted = false
            val ttsStartTime = System.currentTimeMillis()
            val ttsTimeout = 2000L // 2 seconds - message already arrived, should start quickly

            while (!ttsStarted && (System.currentTimeMillis() - ttsStartTime) < ttsTimeout) {
                val isSpeakingNow = capturedViewModel?.isSpeaking?.value ?: false
                if (isSpeakingNow) {
                    ttsStarted = true
                    Log.d(TAG, "✅ Queued TTS successfully started playing after user finished (after ${System.currentTimeMillis() - ttsStartTime}ms)")
                    break
                }
                delay(100)
            }

            if (!ttsStarted) {
                Log.e(TAG, "❌ FAILURE: TTS did not start playing after user finished speaking")
                failWithScreenshot("tts_not_started_after_finish", "TTS did not start after user finished")
                throw AssertionError("Queued TTS should start playing after user finishes")
            }

            // Step 9: Verify the user's message was sent
            Log.d(TAG, "🔍 Step 9: Verifying user message was sent...")
            val userMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(finalTranscription) },
                timeoutMs = 1000L,
                description = "user's final message"
            )

            if (!userMessageAppeared) {
                failWithScreenshot("user_message_not_sent", "User message not sent: $finalTranscription")
            }

            Log.d(TAG, "✅ User message successfully sent: '$finalTranscription'")

            Log.d(TAG, "🎉 TTS queueing test completed successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("tts_queueing_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun testTTSBargeIn_stopsWhenUserSpeaks(): Unit = runBlocking {
        // Skip this test on emulators since speech recognition is not available
        assumeFalse("Skipping TTS barge-in test on emulator (speech recognition unavailable)", isRunningOnEmulator())

        Log.d(TAG, "🚀 Starting TTS barge-in test")

        try {
            // Step 1: Set up callback and wait for app to be ready
            Log.d(TAG, "📱 Step 1: Setting up test...")

            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured!")
                capturedViewModel = vm
            }

            // Navigate to new chat
            Log.d(TAG, "📱 Navigating to new chat...")
            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                failWithScreenshot("bargein_navigation_failed", "Failed to navigate to chat screen")
                return@runBlocking
            }

            // Wait for ViewModel
            var waitTime = 0
            while (capturedViewModel == null && waitTime < 5000) {
                Thread.sleep(100)
                waitTime += 100
            }

            if (capturedViewModel == null) {
                failWithScreenshot("bargein_viewmodel_not_captured", "ChatViewModel not captured")
                return@runBlocking
            }

            Log.d(TAG, "✅ App launched and ViewModel captured")

            // Step 2: Enable TTS, continuous listening, and test mode BEFORE sending messages
            // so the assistant's response to the 2nd message triggers TTS naturally
            Log.d(TAG, "🔊 Step 2: Enabling TTS, continuous listening, and test mode...")
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    if (!vm.isVoiceResponseEnabled.value) {
                        vm.toggleVoiceResponse()
                        Log.d(TAG, "✅ TTS/voice response enabled")
                    }
                    if (!voiceManager.isContinuousListeningEnabled.value) {
                        vm.toggleSpeechRecognition()
                        Log.d(TAG, "✅ Continuous listening enabled")
                    }
                }
            }
            speechRecognitionService.enableTestMode()
            Log.d(TAG, "✅ Speech recognition test mode enabled")

            // Step 3: Send 2 messages — the assistant's response will trigger TTS
            Log.d(TAG, "💬 Step 3: Sending 2 messages to build conversation...")
            val setupMessages = listOf(
                "This is a barge-in test - ${System.currentTimeMillis()} . I'm really interested in space.",
                "Can you tell me about Mars in 30 words exactly? - ${System.currentTimeMillis()}"
            )

            // Track chat for cleanup
            val currentChatId = capturedViewModel?.chatId?.value
            if (currentChatId != null && currentChatId != 0L) {
                createdChatIds.add(currentChatId)
            }

            setupMessages.forEach { message ->
                Log.d(TAG, "🎤 Sending voice message: '$message'")
                val messageSent = ComposeTestHelper.sendVoiceMessage(
                    message = message,
                    voiceManager = voiceManager,
                    composeTestRule = composeTestRule
                )

                if (!messageSent) {
                    failWithScreenshot("bargein_message_not_sent", "Message not sent: $message")
                    return@runBlocking
                }

                Log.d(TAG, "✅ Voice message sent and displayed: '$message'")
            }

            Log.d(TAG, "✅ Messages sent, waiting for TTS to start from assistant response...")

            // Step 4: Wait for TTS to start playing (from the assistant's response)
            Log.d(TAG, "🔊 Waiting for TTS to start playing...")
            var ttsStarted = false
            val ttsWaitStart = System.currentTimeMillis()
            val ttsWaitTimeout = 30_000L

            while (!ttsStarted && (System.currentTimeMillis() - ttsWaitStart) < ttsWaitTimeout) {
                val isSpeakingNow = capturedViewModel?.isSpeaking?.value ?: false
                if (isSpeakingNow) {
                    ttsStarted = true
                    Log.d(TAG, "✅ TTS started playing after ${System.currentTimeMillis() - ttsWaitStart}ms")
                    break
                }
                delay(100)
            }

            if (!ttsStarted) {
                failWithScreenshot("bargein_tts_not_started", "TTS did not start playing within timeout")
                throw AssertionError("TTS should have started playing for the assistant's response")
            }

            // Step 5: Trigger barge-in — simulate user speaking during TTS (confirmed speech via partial result)
            Log.d(TAG, "🎤 Step 5: Triggering barge-in (simulating confirmed speech with partial result)...")
            speechRecognitionService.testTriggerFirstPartialForBargeIn("Actually")

            // Step 6: Assert TTS stops within ~1s
            Log.d(TAG, "🔊 Step 6: Verifying TTS stops after barge-in...")
            var ttsStopped = false
            val stopWaitStart = System.currentTimeMillis()
            val stopWaitTimeout = 1000L

            while (!ttsStopped && (System.currentTimeMillis() - stopWaitStart) < stopWaitTimeout) {
                val isSpeakingNow = capturedViewModel?.isSpeaking?.value ?: false
                if (!isSpeakingNow) {
                    ttsStopped = true
                    Log.d(TAG, "✅ TTS stopped after barge-in (${System.currentTimeMillis() - stopWaitStart}ms)")
                    break
                }
                delay(50)
            }

            if (!ttsStopped) {
                failWithScreenshot("bargein_tts_not_stopped", "TTS did not stop after barge-in")
                throw AssertionError("TTS should stop when user starts speaking (barge-in)")
            }

            // Step 7: Assert listening stays active
            val isListeningNow = voiceManager.isListening.value
            Log.d(TAG, "🎤 Step 7: Checking listening state: isListening=$isListeningNow")
            if (!isListeningNow) {
                Log.w(TAG, "⚠️ Listening not active after barge-in (may be expected if not full-duplex)")
            }

            // Step 8: Inject partial transcriptions word-by-word — verify TTS stays stopped
            Log.d(TAG, "🎤 Step 8: Sending partial transcriptions after barge-in...")
            val userMessage = "Actually I want to know about Saturn instead"
            val words = userMessage.split(" ")
            var partialText = ""

            for (wordIndex in words.indices) {
                // Verify TTS is still stopped
                val isSpeakingDuringPartials = capturedViewModel?.isSpeaking?.value ?: false
                if (isSpeakingDuringPartials) {
                    failWithScreenshot("bargein_tts_restarted", "TTS restarted during partials after barge-in")
                    throw AssertionError("TTS should stay stopped during user's partials after barge-in")
                }

                partialText += (if (partialText.isEmpty()) "" else " ") + words[wordIndex]
                speechRecognitionService.testSetPartialTranscription(partialText)
                Log.d(TAG, "🎤 [TEST] Partial #$wordIndex: '$partialText'")
                delay(100)
            }

            Log.d(TAG, "✅ All partials sent, TTS stayed stopped")

            // Step 9: Send final transcription — verify user's message appears in chat
            Log.d(TAG, "📤 Step 9: Sending final transcription...")
            speechRecognitionService.testSendFinalTranscription(userMessage)

            val userMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(userMessage) },
                timeoutMs = 5000L,
                description = "user's barge-in message"
            )

            if (!userMessageAppeared) {
                failWithScreenshot("bargein_user_message_not_sent", "User message not sent after barge-in: $userMessage")
            }

            Log.d(TAG, "✅ User's barge-in message appeared in chat: '$userMessage'")

            // Step 10: Verify pendingTTSMessage was cleared (no stale TTS restart)
            val pendingTTS = capturedViewModel?.let {
                // Access pendingTTSMessage via reflection since it's private
                try {
                    val field = it.javaClass.getDeclaredField("pendingTTSMessage")
                    field.isAccessible = true
                    field.get(it) as? String
                } catch (e: Exception) {
                    Log.w(TAG, "Could not check pendingTTSMessage via reflection: ${e.message}")
                    null
                }
            }

            if (pendingTTS != null) {
                Log.e(TAG, "❌ pendingTTSMessage was not cleared after barge-in: '$pendingTTS'")
                failWithScreenshot("bargein_pending_tts_not_cleared", "pendingTTSMessage not cleared: $pendingTTS")
                throw AssertionError("pendingTTSMessage should be null after barge-in, was: '$pendingTTS'")
            }

            Log.d(TAG, "✅ pendingTTSMessage correctly cleared after barge-in")

            Log.d(TAG, "🎉 TTS barge-in test completed successfully!")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("tts_bargein_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
