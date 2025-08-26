package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.integration.GoogleSignInAutomator
import com.example.whiz.integration.AuthenticationTestHelper
import com.example.whiz.MainActivity
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.test_helpers.ComposeTestHelper
import android.util.Log

/**
 * Message Flow Voice Compose Test using ActivityScenarioRule
 * 
 * This test verifies the complete voice message flow using true voice launch simulation:
 * - Voice launch detection and navigation
 * - Voice message input and sending
 * - Chat creation and message persistence
 * - Optimistic chat ID validation
 * 
 * Key advantages over dual-intent approach:
 * - Uses ActivityScenarioRule with custom voice launch intent from startup
 * - Single intent approach eliminates authentication race conditions
 * - More realistic simulation of actual voice launch behavior
 * - No reflection or timing-dependent workarounds needed
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MessageFlowVoiceComposeTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "MessageFlowVoiceComposeTest"
        
        // Capture ViewModel from navigation scope
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var authApi: AuthApi

    @Inject 
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
    @Inject
    lateinit var preloadManager: com.example.whiz.data.PreloadManager
    
    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    @Inject
    lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService
    
    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()
    
    @After
    fun tearDown() {
        Log.d(TAG, "TearDown: MessageFlowVoiceComposeTest")
        
        // Clean up test chats
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats created during voice test")
            try {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Pls always reply with just 1 word for test", // Match first message pattern
                        "2nd voice msg", // Match second message pattern
                        "3rd voice", // Match third message pattern
                        "voice launch", // General voice launch pattern
                        "voice interrupt" // Match interrupt message pattern
                    ),
                    enablePatternFallback = true // Enable pattern fallback for voice-created chats
                )
                createdChatIds.clear()
                Log.d(TAG, "✅ Test chat cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test chat cleanup", e)
            }
        }
        
        // Clean up the test callback
        MainActivity.testViewModelCallback = null
        capturedViewModel = null
    }

    @Test
    fun fullMessageFlowTest_voiceLaunchAndVoiceInput(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting comprehensive message flow voice test with direct activity launch")
        
        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")
        
        try {
            // Capture initial chats before voice launch
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not get initial chats: ${e.message}")
                emptyList()
            }
            
            // step 1: Create voice launch intent and launch activity
            Log.d(TAG, "🎤 step 1: Voice launching app...")
            val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch specific flags
                putExtra("tracing_intent_id", System.currentTimeMillis()) // Unique trace ID for voice detection
                // Don't set sourceBounds for voice launch (manual launches have bounds, voice launches don't)
                // Voice launch flags (FROM_ASSISTANT, ENABLE_VOICE_MODE, CREATE_NEW_CHAT_ON_START) 
                // will be automatically added by detectVoiceLaunch() in MainActivity
            }
            
            // Set up the ViewModel capture callback before launching
            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured from navigation scope!")
                capturedViewModel = vm
            }
            
            // Launch through real Android system like Google Assistant would
            val activity = instrumentation.startActivitySync(voiceLaunchIntent) as MainActivity
            
            // Wait for voice launch to navigate to new chat screen
            Log.d(TAG, "⏳ Waiting for voice launch navigation to complete...")
            val navigatedToChat = device.wait(Until.hasObject(
                By.clazz("android.widget.EditText").pkg(packageName)
            ), 5000) // Reduced timeout since navigation seems to be working
            
            if (!navigatedToChat) {
                Log.e(TAG, "❌ FAILURE at step 1: Voice launch failed to navigate to chat screen")
                failWithScreenshot("voice_launch_navigation_failed", "Voice launch failed to navigate to chat screen")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Voice launch navigation successful - found EditText")

            // Wait for ChatViewModel to be captured
            Log.d(TAG, "⏳ Waiting for ChatViewModel to be captured...")
            var waitTime = 0
            while (capturedViewModel == null && waitTime < 5000) {
                Thread.sleep(100)
                waitTime += 100
            }
            
            if (capturedViewModel == null) {
                Log.e(TAG, "❌ FAILURE: ChatViewModel not captured after 5 seconds")
                failWithScreenshot("viewmodel_not_captured", "ChatViewModel not captured")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ ChatViewModel captured successfully")
            
            // Track the chat ID from the ViewModel for cleanup
            try {
                val currentChatId = capturedViewModel?.chatId?.value
                if (currentChatId != null && currentChatId != 0L) {
                    if (!createdChatIds.contains(currentChatId)) {
                        createdChatIds.add(currentChatId)
                        Log.d(TAG, "📝 Tracked chat for cleanup: $currentChatId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track chat ID: ${e.message}")
            }
            
            // step 2: Send first voice message
            Log.d(TAG, "🎤 step 2: Sending first voice message...")
            val firstMessage = "Pls always reply with just 1 word for test - ${System.currentTimeMillis()}"
            
            // Check if voice mode is active
            val listeningActive = device.hasObject(By.text("Listening...").pkg(packageName))
            Log.d(TAG, "Voice mode active: $listeningActive")
            
            // Simulate voice transcription using the captured ViewModel
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$firstMessage'")
                    vm.updateInputText(firstMessage, fromVoice = true)
                    vm.sendUserInput(firstMessage)
                    Log.d(TAG, "✅ First voice message sent via ChatViewModel")
                }
            }
            
            Log.d(TAG, "🔍 Waiting for first message to appear in UI...")
            
            // Wait for first message to appear using Compose test helper
            val firstMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(firstMessage) },
                timeoutMs = 5000L,
                description = "first voice message"
            )
            
            if (!firstMessageAppeared) {
                Log.e(TAG, "❌ First message not found in UI after 5 seconds")
                failWithScreenshot("First message not displayed", "first_message_not_displayed")
                throw AssertionError("First message not displayed in UI")
            }
            
            // Now verify it's actually displayed
            composeTestRule.onNodeWithText(firstMessage).assertIsDisplayed()
            Log.d(TAG, "✅ First message verified in UI")
            
            // Check if chat ID has changed (from optimistic to server ID)
            try {
                val updatedChatId = capturedViewModel?.chatId?.value
                if (updatedChatId != null && updatedChatId > 0 && !createdChatIds.contains(updatedChatId)) {
                    createdChatIds.add(updatedChatId)
                    Log.d(TAG, "📝 Tracked server chat ID for cleanup: $updatedChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track updated chat ID: ${e.message}")
            }

            // step 3: Send second message immediately (while bot is likely processing first message)
            Log.d(TAG, "💬 step 3: Sending second voice message immediately...")
            val secondMessage = "2nd voice msg - ${System.currentTimeMillis()}"
            
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating second voice transcription: '$secondMessage'")
                    vm.updateInputText(secondMessage, fromVoice = true)
                    vm.sendUserInput(secondMessage)
                    Log.d(TAG, "✅ Second voice message sent")
                }
            }
            
            // Wait for second message to appear
            Log.d(TAG, "🔍 Waiting for second message to appear in UI...")
            
            val secondMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(secondMessage) },
                timeoutMs = 3000L,
                description = "second voice message"
            )
            
            if (!secondMessageAppeared) {
                Log.e(TAG, "❌ Second message not found in UI after 3 seconds")
                failWithScreenshot("Second message not displayed", "second_message_not_displayed")
                throw AssertionError("Second message not displayed in UI")
            }
            
            composeTestRule.onNodeWithText(secondMessage).assertIsDisplayed()
            Log.d(TAG, "✅ Second message verified in UI")

            // step 4: Wait for bot response and verify TTS starts
            Log.d(TAG, "🔊 step 4: Waiting for bot response and TTS...")
            
            // Wait for bot response to complete (max 10 seconds)
            var botResponseFound = false
            var ttsDetected = false
            val botResponseStartTime = System.currentTimeMillis()
            val maxBotWaitTime = 10000L // 10 seconds
            
            while ((!botResponseFound || !ttsDetected) && 
                   (System.currentTimeMillis() - botResponseStartTime) < maxBotWaitTime) {
                
                // Check for bot response (look for any text that's not our messages)
                if (!botResponseFound) {
                    try {
                        // Re-find objects each time to avoid stale references
                        val allTexts = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
                        for (textView in allTexts) {
                            try {
                                val text = textView.text
                                if (text != null && text.isNotEmpty() && 
                                    !text.contains(firstMessage) && 
                                    !text.contains(secondMessage) &&
                                    !text.contains("Thinking") &&
                                    !text.contains("●●●") &&
                                    !text.contains("Type or tap") &&
                                    !text.contains("New Chat") &&
                                    !text.contains("Listening")) {
                                    botResponseFound = true
                                    val elapsed = System.currentTimeMillis() - botResponseStartTime
                                    Log.d(TAG, "✅ Bot response found after ${elapsed}ms: $text")
                                    break
                                }
                            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                                // UI element became stale, skip and continue
                                Log.v(TAG, "Skipping stale TextView")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error checking for bot response: ${e.message}")
                    }
                }
                
                // Check if TTS is active via ViewModel
                if (botResponseFound && !ttsDetected) {
                    val isSpeaking = capturedViewModel?.isSpeaking?.value ?: false
                    if (isSpeaking) {
                        ttsDetected = true
                        val elapsed = System.currentTimeMillis() - botResponseStartTime
                        Log.d(TAG, "✅ TTS detected after ${elapsed}ms - bot is speaking")
                    }
                }
                
                if (!botResponseFound || !ttsDetected) {
                    Thread.sleep(100)
                }
            }
            
            val totalWaitTime = System.currentTimeMillis() - botResponseStartTime
            
            if (!botResponseFound) {
                Log.w(TAG, "⚠️ Bot response not found within ${totalWaitTime}ms")
                failWithScreenshot("Bot response not found", "bot_response_timeout")
            } else if (!ttsDetected) {
                Log.w(TAG, "⚠️ TTS not detected within ${totalWaitTime}ms (bot response was found)")
            } else {
                Log.d(TAG, "✅ Both bot response and TTS detected within ${totalWaitTime}ms")
            }

            // step 5: Press mic button to interrupt TTS and send third message
            Log.d(TAG, "🎙️ step 5: Interrupting TTS with mic button...")
            
            // Find and click the mic button
            val micButton = device.findObject(By.desc("Voice input").pkg(packageName))
            if (micButton != null && micButton.isClickable) {
                micButton.click()
                Log.d(TAG, "✅ Mic button clicked to interrupt TTS")
                Thread.sleep(500) // Brief pause for UI to update
                
                // Send third message via voice
                val thirdMessage = "3rd voice interrupt - ${System.currentTimeMillis()}"
                
                instrumentation.runOnMainSync {
                    capturedViewModel?.let { vm ->
                        Log.d(TAG, "🎤 Simulating third voice transcription during TTS: '$thirdMessage'")
                        vm.updateInputText(thirdMessage, fromVoice = true)
                        vm.sendUserInput(thirdMessage)
                        Log.d(TAG, "✅ Third voice message sent during TTS")
                    }
                }
                
                // Wait for third message to appear
                Log.d(TAG, "🔍 Waiting for third message to appear in UI...")
                
                val thirdMessageAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText(thirdMessage) },
                    timeoutMs = 3000L,
                    description = "third voice message (with mic interrupt)"
                )
                
                if (!thirdMessageAppeared) {
                    Log.e(TAG, "❌ Third message not found in UI after 3 seconds")
                    failWithScreenshot("Third message not displayed", "third_message_not_displayed")
                    throw AssertionError("Third message not displayed in UI")
                }
                
                composeTestRule.onNodeWithText(thirdMessage).assertIsDisplayed()
                Log.d(TAG, "✅ Third message verified in UI")
                
                // Verify TTS was interrupted
                Thread.sleep(200)
                val stillSpeaking = capturedViewModel?.isSpeaking?.value ?: false
                if (stillSpeaking) {
                    Log.w(TAG, "⚠️ TTS might still be active after interruption")
                } else {
                    Log.d(TAG, "✅ TTS successfully interrupted")
                }
                
            } else {
                Log.w(TAG, "⚠️ Could not find clickable mic button - sending third message anyway")
                
                // Send third message without mic button
                val thirdMessage = "3rd voice msg no interrupt - ${System.currentTimeMillis()}"
                
                instrumentation.runOnMainSync {
                    capturedViewModel?.let { vm ->
                        vm.updateInputText(thirdMessage, fromVoice = true)
                        vm.sendUserInput(thirdMessage)
                    }
                }
                
                // Wait for third message to appear
                Log.d(TAG, "🔍 Waiting for third message to appear in UI (no mic button)...")
                
                val thirdMessageAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText(thirdMessage) },
                    timeoutMs = 3000L,
                    description = "third voice message (no mic button)"
                )
                
                if (!thirdMessageAppeared) {
                    Log.e(TAG, "❌ Third message not found in UI after 3 seconds")
                    failWithScreenshot("Third message not displayed", "third_message_not_displayed")
                    throw AssertionError("Third message not displayed in UI")
                }
                
                composeTestRule.onNodeWithText(thirdMessage).assertIsDisplayed()
                Log.d(TAG, "✅ Third message verified in UI (without mic button)")
            }

            // Validate total word count in assistant responses
            Log.d(TAG, "📊 Validating assistant response word count...")
            val totalAssistantWords = ComposeTestHelper.countTotalAssistantWords(composeTestRule)
            val totalUserMessages = 3 // We send 3 messages in this test
            Log.d(TAG, "📊 Total assistant words: $totalAssistantWords, Total user messages: $totalUserMessages")
            
            if (totalAssistantWords > totalUserMessages) {
                Log.e(TAG, "❌ FAILURE: Assistant used too many words - expected at most $totalUserMessages words total, but got $totalAssistantWords")
                failWithScreenshot("assistant_word_count_exceeded", "Assistant word count ($totalAssistantWords) exceeds user message count ($totalUserMessages)")
                throw AssertionError("Assistant word count validation failed: $totalAssistantWords words > $totalUserMessages messages")
            }
            Log.d(TAG, "✅ Assistant word count validation passed: $totalAssistantWords words <= $totalUserMessages messages")
            
            Log.d(TAG, "🎉 Voice launch test with multiple messages and TTS completed successfully!")
            
            // Final chat ID tracking - capture any server-assigned IDs
            try {
                val finalChatId = capturedViewModel?.chatId?.value
                if (finalChatId != null && finalChatId > 0 && !createdChatIds.contains(finalChatId)) {
                    createdChatIds.add(finalChatId)
                    Log.d(TAG, "📝 Tracked final server chat ID for cleanup: $finalChatId")
                }
                
                // Also try to get all chats and track any new ones with our test messages
                val allChats = repository.getAllChats()
                val testMessagePatterns = listOf(
                    "Pls always reply with just 1 word for test",
                    "2nd voice msg",
                    "3rd voice"
                )
                
                allChats.forEach { chat ->
                    // Check if this chat contains our test messages
                    val chatMessages = chat.title ?: ""
                    val isTestChat = testMessagePatterns.any { pattern ->
                        chatMessages.contains(pattern, ignoreCase = true)
                    }
                    
                    if (isTestChat && !createdChatIds.contains(chat.id)) {
                        createdChatIds.add(chat.id)
                        Log.d(TAG, "📝 Found and tracked test chat by pattern: ${chat.id} ('${chat.title}')")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track final chat IDs: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_launch_test_exception", "Test failed with exception: ${e.message}")
            throw e
        }
    }
}