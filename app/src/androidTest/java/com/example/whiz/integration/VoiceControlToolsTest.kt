package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule
import com.example.whiz.services.BubbleOverlayService

/**
 * Integration tests for voice control tools (disable continuous listening, set TTS enabled/disabled).
 *
 * Tests the server's ability to control voice features via tools:
 * - disable_continuous_listening tool
 * - set_tts_enabled tool (both enable and disable)
 *
 * Includes tests for both:
 * 1. Regular chat mode where messages are typed
 * 2. Voice launch mode where messages are sent via simulated voice transcription
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoiceControlToolsTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "VoiceControlToolsTest"

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
        Log.d(TAG, "🧪 voice control tools test setup complete")

        // Grant microphone permission for continuous listening tests
        Log.d(TAG, "🎙️ Granting microphone permission")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 cleaning up voice control test chats")
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

                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "turn off continuous listening",
                        "turn on tts",
                        "turn off tts",
                        "voice control",
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
    fun testVoiceControlTools_chatMode(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting voice control tools test (regular chat mode)")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Step 1: Verify app is ready
            Log.d(TAG, "📱 step 1: verifying app is ready...")
            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: app not ready")
                failWithScreenshot("voice_control_app_not_ready", "app not ready")
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
                    failWithScreenshot("voice_control_navigate_back_failed", "failed to navigate back")
                    return@runBlocking
                }
            }

            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE: failed to navigate to new chat")
                failWithScreenshot("voice_control_new_chat_failed", "failed to navigate to new chat")
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

            // Step 3: Verify continuous listening is enabled by default
            Log.d(TAG, "🔍 step 3: verifying continuous listening is enabled by default...")
            val initialListeningState = voiceManager.isContinuousListeningEnabled.value
            if (!initialListeningState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not enabled by default")
                failWithScreenshot("voice_control_listening_not_enabled_by_default", "continuous listening not enabled by default")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening is ON by default: $initialListeningState")

            // Step 3b: Verify TTS is disabled by default
            Log.d(TAG, "🔍 step 3b: verifying TTS is disabled by default...")
            val initialTTSState = chatViewModel.isVoiceResponseEnabled.value
            if (initialTTSState) {
                Log.e(TAG, "❌ FAILURE: TTS should be OFF by default")
                failWithScreenshot("voice_control_tts_on_by_default", "TTS on by default (should be off)")
                return@runBlocking
            }
            Log.d(TAG, "✅ TTS is OFF by default: $initialTTSState")

            // Step 4: Send message asking to disable continuous listening (as voice message to preserve continuous listening)
            Log.d(TAG, "💬 step 4: sending voice message to disable continuous listening...")
            val disableMessage = "Please turn off continuous listening - $uniqueTestId"

            val disableMessageSent = ComposeTestHelper.sendVoiceMessage(
                message = disableMessage,
                voiceManager = voiceManager,
                composeTestRule = composeTestRule
            )

            if (!disableMessageSent) {
                Log.e(TAG, "❌ Voice message not sent or displayed")
                failWithScreenshot("voice_control_message_not_displayed", "voice message not sent")
                return@runBlocking
            }

            // Step 5: Wait for bot to process and execute tool
            Log.d(TAG, "⏳ step 5: waiting for bot to execute disable_continuous_listening tool...")
            var listeningDisabled = false
            val disableStartTime = System.currentTimeMillis()
            val disableTimeout = 15000L // 15 seconds for bot response and tool execution

            while (System.currentTimeMillis() - disableStartTime < disableTimeout) {
                val currentState = voiceManager.isContinuousListeningEnabled.value
                if (!currentState) {
                    listeningDisabled = true
                    val elapsed = System.currentTimeMillis() - disableStartTime
                    Log.d(TAG, "✅ Continuous listening disabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!listeningDisabled) {
                Log.e(TAG, "❌ FAILURE: continuous listening not disabled after timeout")
                failWithScreenshot("voice_control_listening_not_disabled", "continuous listening still on")
                return@runBlocking
            }
            // Step 8: Send typed message to enable TTS (continuous listening is now OFF)
            Log.d(TAG, "🔊 step 8: sending typed message to enable TTS...")
            val enableTTSMessage = "Please turn on text to speech - $uniqueTestId"

            val enableTTSMessageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = enableTTSMessage
            )

            if (!enableTTSMessageSent) {
                Log.e(TAG, "❌ Enable TTS typed message not sent or displayed")
                failWithScreenshot("voice_control_enable_tts_message_not_displayed", "typed message not sent")
                return@runBlocking
            }

            // Step 9: Wait for TTS to be enabled
            Log.d(TAG, "⏳ step 9: waiting for set_tts_enabled tool to enable TTS...")
            var ttsEnabled = false
            val enableTTSStartTime = System.currentTimeMillis()
            val enableTTSTimeout = 15000L

            while (System.currentTimeMillis() - enableTTSStartTime < enableTTSTimeout) {
                val currentTTSState = chatViewModel.isVoiceResponseEnabled.value
                if (currentTTSState) {
                    ttsEnabled = true
                    val elapsed = System.currentTimeMillis() - enableTTSStartTime
                    Log.d(TAG, "✅ TTS enabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsEnabled) {
                Log.e(TAG, "❌ FAILURE: TTS not enabled after timeout")
                failWithScreenshot("voice_control_tts_not_enabled", "TTS not enabled")
                return@runBlocking
            }

            // Step 11: Send typed message to disable TTS (continuous listening is still OFF)
            Log.d(TAG, "🔇 step 11: sending typed message to disable TTS...")
            val disableTTSMessage = "Please turn off text to speech - $uniqueTestId"

            val disableTTSMessageSent = ComposeTestHelper.sendMessage(
                composeTestRule = composeTestRule,
                message = disableTTSMessage
            )

            if (!disableTTSMessageSent) {
                Log.e(TAG, "❌ Disable TTS typed message not sent or displayed")
                failWithScreenshot("voice_control_disable_tts_message_not_displayed", "typed message not sent")
                return@runBlocking
            }

            // Step 12: Wait for TTS to be disabled
            Log.d(TAG, "⏳ step 12: waiting for set_tts_enabled tool to disable TTS...")
            var ttsDisabled = false
            val disableTTSStartTime = System.currentTimeMillis()
            val disableTTSTimeout = 15000L

            while (System.currentTimeMillis() - disableTTSStartTime < disableTTSTimeout) {
                val currentTTSState = chatViewModel.isVoiceResponseEnabled.value
                if (!currentTTSState) {
                    ttsDisabled = true
                    val elapsed = System.currentTimeMillis() - disableTTSStartTime
                    Log.d(TAG, "✅ TTS disabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsDisabled) {
                Log.e(TAG, "❌ FAILURE: TTS not disabled after timeout")
                failWithScreenshot("voice_control_tts_not_disabled", "TTS still on")
                return@runBlocking
            }

            // Step 7: Enable continuous listening again for next test
            Log.d(TAG, "🎙️ step 7: re-enabling continuous listening...")
            instrumentation.runOnMainSync {
                voiceManager.updateContinuousListeningEnabled(true)
            }

            val reenabledState = voiceManager.isContinuousListeningEnabled.value
            if (!reenabledState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not re-enabled")
                failWithScreenshot("voice_control_listening_not_reenabled", "listening not re-enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening re-enabled: $reenabledState")

            // Step 14: Send voice message to enable TTS (with continuous listening ON)
            Log.d(TAG, "🔊 step 14: sending voice message to enable TTS (continuous listening ON)...")
            val enableTTSVoiceMessage = "Please turn on text to speech again - $uniqueTestId"

            val enableTTSVoiceMessageSent = ComposeTestHelper.sendVoiceMessage(
                message = enableTTSVoiceMessage,
                voiceManager = voiceManager,
                composeTestRule = composeTestRule
            )

            if (!enableTTSVoiceMessageSent) {
                Log.e(TAG, "❌ Enable TTS voice message not sent or displayed")
                failWithScreenshot("voice_control_enable_tts_voice_message_not_displayed", "voice message not sent")
                return@runBlocking
            }

            // Step 15: Wait for TTS to be enabled
            Log.d(TAG, "⏳ step 15: waiting for TTS to be enabled...")
            var ttsEnabledAgain = false
            val enableTTSAgainStartTime = System.currentTimeMillis()
            val enableTTSAgainTimeout = 15000L

            while (System.currentTimeMillis() - enableTTSAgainStartTime < enableTTSAgainTimeout) {
                val currentTTSState = chatViewModel.isVoiceResponseEnabled.value
                if (currentTTSState) {
                    ttsEnabledAgain = true
                    val elapsed = System.currentTimeMillis() - enableTTSAgainStartTime
                    Log.d(TAG, "✅ TTS enabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsEnabledAgain) {
                Log.e(TAG, "❌ FAILURE: TTS not enabled after timeout")
                failWithScreenshot("voice_control_tts_not_enabled_voice", "TTS not enabled with voice message")
                return@runBlocking
            }

            // Step 17: Send voice message to disable TTS (with continuous listening ON)
            Log.d(TAG, "🔇 step 17: sending voice message to disable TTS (continuous listening ON)...")
            val disableTTSVoiceMessage = "Please turn off text to speech again - $uniqueTestId"

            val disableTTSVoiceMessageSent = ComposeTestHelper.sendVoiceMessage(
                message = disableTTSVoiceMessage,
                voiceManager = voiceManager,
                composeTestRule = composeTestRule
            )

            if (!disableTTSVoiceMessageSent) {
                Log.e(TAG, "❌ Disable TTS voice message not sent or displayed")
                failWithScreenshot("voice_control_disable_tts_voice_message_not_displayed", "voice message not sent")
                return@runBlocking
            }

            // Step 18: Wait for TTS to be disabled
            Log.d(TAG, "⏳ step 18: waiting for TTS to be disabled...")
            var ttsDisabledAgain = false
            val disableTTSAgainStartTime = System.currentTimeMillis()
            val disableTTSAgainTimeout = 15000L

            while (System.currentTimeMillis() - disableTTSAgainStartTime < disableTTSAgainTimeout) {
                val currentTTSState = chatViewModel.isVoiceResponseEnabled.value
                if (!currentTTSState) {
                    ttsDisabledAgain = true
                    val elapsed = System.currentTimeMillis() - disableTTSAgainStartTime
                    Log.d(TAG, "✅ TTS disabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsDisabledAgain) {
                Log.e(TAG, "❌ FAILURE: TTS not disabled after timeout")
                failWithScreenshot("voice_control_tts_not_disabled_voice", "TTS still on")
                return@runBlocking
            }

            // Final verification: Ensure continuous listening is still enabled
            Log.d(TAG, "🔍 final verification: checking continuous listening is still enabled...")
            val finalListeningState = voiceManager.isContinuousListeningEnabled.value
            if (!finalListeningState) {
                Log.e(TAG, "❌ FAILURE: continuous listening should still be ON")
                failWithScreenshot("voice_control_listening_off_at_end", "continuous listening off at end")
                return@runBlocking
            }
            Log.d(TAG, "✅ Final verification passed: Continuous listening is still ON: $finalListeningState")

            Log.d(TAG, "🎉 voice control tools test (chat mode) PASSED!")
            Log.d(TAG, "✅ Test validated: disable continuous listening, enable/disable TTS (typed), enable/disable TTS (voice)")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_control_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun testVoiceControlTools_voiceMode(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting voice control tools test (voice mode)")

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

            // Step 0b: Finish the activity that was auto-launched by composeTestRule
            Log.d(TAG, "🛑 Finishing auto-launched activity before voice launch...")
            composeTestRule.activityRule.scenario.close()
            Thread.sleep(500) // Give it time to fully close

            // Step 1: Voice launch
            Log.d(TAG, "🎤 step 1: Voice launching app...")
            val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000
                putExtra("tracing_intent_id", System.currentTimeMillis())
            }

            // Set up ViewModel capture
            capturedViewModel = null
            MainActivity.testViewModelCallback = { vm ->
                Log.d(TAG, "✅ ChatViewModel captured!")
                capturedViewModel = vm
            }

            // Launch
            instrumentation.startActivitySync(voiceLaunchIntent)

            // Wait for navigation to chat screen
            val navigatedToChat = device.wait(Until.hasObject(
                By.clazz("android.widget.EditText").pkg(packageName)
            ), 5000)

            if (!navigatedToChat) {
                Log.e(TAG, "❌ FAILURE: Voice launch failed")
                failWithScreenshot("voice_control_voice_launch_failed", "Voice launch failed")
                return@runBlocking
            }

            Log.d(TAG, "✅ Voice launch navigation successful")

            // Wait for ChatViewModel capture
            var waitTime = 0
            while (capturedViewModel == null && waitTime < 5000) {
                Thread.sleep(100)
                waitTime += 100
            }

            if (capturedViewModel == null) {
                Log.e(TAG, "❌ FAILURE: ChatViewModel not captured")
                failWithScreenshot("voice_control_viewmodel_not_captured", "ViewModel not captured")
                return@runBlocking
            }

            Log.d(TAG, "✅ ChatViewModel captured")

            // Track chat for cleanup
            try {
                val currentChatId = capturedViewModel?.chatId?.value
                if (currentChatId != null && currentChatId != 0L) {
                    if (!createdChatIds.contains(currentChatId)) {
                        createdChatIds.add(currentChatId)
                        Log.d(TAG, "📝 Tracked chat: $currentChatId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track chat ID: ${e.message}")
            }

            // Send voice transcription to trigger notification bubble mode
            Log.d(TAG, "📱 Sending voice transcription to open Clock app...")
            val openClockMessage = "Open the Clock app"

            try {
                val transcriptionCallbackField = voiceManager.javaClass.getDeclaredField("transcriptionCallback")
                transcriptionCallbackField.isAccessible = true
                val callback = transcriptionCallbackField.get(voiceManager) as? ((String) -> Unit)

                if (callback != null) {
                    instrumentation.runOnMainSync {
                        Log.d(TAG, "🎤 Invoking transcription callback with: '$openClockMessage'")
                        callback.invoke(openClockMessage)
                        Log.d(TAG, "✅ Transcription callback invoked - should trigger notification bubble mode")
                    }
                } else {
                    Log.w(TAG, "⚠️ Transcription callback not available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not send voice transcription: ${e.message}")
            }

            // Wait for notification bubble mode to be active
            Log.d(TAG, "⏳ Waiting for notification bubble mode to activate...")
            var bubbleStarted = false
            val bubbleStartTime = System.currentTimeMillis()
            val bubbleTimeout = 10000L // 5 seconds timeout for bubble to activate

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
                Log.e(TAG, "❌ FAILURE: bubble did not activate after timeout")
                failWithScreenshot("voice_control_bubble_not_active", "bubble not activated")
                return@runBlocking
            }

            // Step 2a: Wait for bubble mode to be set (should be TTS_WITH_LISTENING since voice launch enabled TTS)
            Log.d(TAG, "🔍 step 2a: waiting for bubble mode to be set (expecting TTS_WITH_LISTENING from voice launch)...")
            var modeSet = false
            val modeStartTime = System.currentTimeMillis()
            val modeTimeout = 2000L

            while (System.currentTimeMillis() - modeStartTime < modeTimeout) {
                if (BubbleOverlayService.bubbleListeningMode == com.example.whiz.services.ListeningMode.TTS_WITH_LISTENING) {
                    modeSet = true
                    val elapsed = System.currentTimeMillis() - modeStartTime
                    Log.d(TAG, "✅ Bubble mode set to TTS_WITH_LISTENING after ${elapsed}ms (preserved from voice launch)")
                    break
                }
                delay(100)
            }

            if (!modeSet) {
                Log.e(TAG, "❌ FAILURE: bubble mode not set to TTS_WITH_LISTENING (mode: ${BubbleOverlayService.bubbleListeningMode})")
                failWithScreenshot("voice_control_bubble_mode_not_tts", "bubble mode not TTS_WITH_LISTENING")
                return@runBlocking
            }

            val enabledState = voiceManager.isContinuousListeningEnabled.value
            if (!enabledState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not enabled")
                failWithScreenshot("voice_control_listening_not_enabled", "listening not enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening is ON: $enabledState")

            // Step 2b: Verify TTS is enabled (preserved from voice launch)
            Log.d(TAG, "🔍 step 2b: verifying TTS is enabled (preserved from voice launch)...")
            val ttsState = capturedViewModel?.isVoiceResponseEnabled?.value ?: false
            if (!ttsState) {
                Log.e(TAG, "❌ FAILURE: TTS should be ON (preserved from voice launch)")
                failWithScreenshot("voice_control_tts_not_preserved", "TTS not preserved from voice launch")
                return@runBlocking
            }
            Log.d(TAG, "✅ TTS is ON (bubble mode preserved TTS state from voice launch)")

            // Step 6: Send voice transcription to disable TTS (app is backgrounded in bubble mode, use broadcast)
            Log.d(TAG, "🔇 step 6: sending voice transcription to disable TTS...")
            val disableTTSMessage = "Please turn off text to speech - ${System.currentTimeMillis()}"

            val intent = Intent("com.example.whiz.TEST_TRANSCRIPTION").apply {
                putExtra("text", disableTTSMessage)
                putExtra("fromVoice", true)
                putExtra("autoSend", true)
            }
            instrumentation.targetContext.sendBroadcast(intent)
            Log.d(TAG, "✅ Sent transcription broadcast: '$disableTTSMessage'")

            // Step 7: Wait for bubble to switch to CONTINUOUS_LISTENING mode
            Log.d(TAG, "⏳ step 7: waiting for bubble to switch to CONTINUOUS_LISTENING mode...")
            var ttsDisableModeSwitched = false
            val disableTTSStartTime = System.currentTimeMillis()
            val disableTTSTimeout = 15000L

            while (System.currentTimeMillis() - disableTTSStartTime < disableTTSTimeout) {
                val currentBubbleMode = BubbleOverlayService.bubbleListeningMode
                if (currentBubbleMode == com.example.whiz.services.ListeningMode.CONTINUOUS_LISTENING) {
                    ttsDisableModeSwitched = true
                    val elapsed = System.currentTimeMillis() - disableTTSStartTime
                    Log.d(TAG, "✅ Bubble switched to CONTINUOUS_LISTENING mode after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsDisableModeSwitched) {
                Log.e(TAG, "❌ FAILURE: bubble did not switch to CONTINUOUS_LISTENING mode")
                failWithScreenshot("voice_control_bubble_not_continuous_mode", "bubble not in CONTINUOUS_LISTENING mode")
                return@runBlocking
            }

            // Step 8: Send voice transcription to re-enable TTS (app is backgrounded in bubble mode, use broadcast)
            Log.d(TAG, "🔊 step 8: sending voice transcription to re-enable TTS...")
            val enableTTSMessage = "Please turn on text to speech - ${System.currentTimeMillis()}"

            val enableIntent = Intent("com.example.whiz.TEST_TRANSCRIPTION").apply {
                putExtra("text", enableTTSMessage)
                putExtra("fromVoice", true)
                putExtra("autoSend", true)
            }
            instrumentation.targetContext.sendBroadcast(enableIntent)
            Log.d(TAG, "✅ Sent transcription broadcast: '$enableTTSMessage'")

            // Step 9: Wait for bubble to switch back to TTS_WITH_LISTENING mode
            Log.d(TAG, "⏳ step 9: waiting for bubble to switch back to TTS_WITH_LISTENING mode...")
            var ttsModeSwitched = false
            val enableTTSStartTime = System.currentTimeMillis()
            val enableTTSTimeout = 15000L

            while (System.currentTimeMillis() - enableTTSStartTime < enableTTSTimeout) {
                val currentBubbleMode = BubbleOverlayService.bubbleListeningMode
                if (currentBubbleMode == com.example.whiz.services.ListeningMode.TTS_WITH_LISTENING) {
                    ttsModeSwitched = true
                    val elapsed = System.currentTimeMillis() - enableTTSStartTime
                    Log.d(TAG, "✅ Bubble switched to TTS_WITH_LISTENING mode after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsModeSwitched) {
                Log.e(TAG, "❌ FAILURE: bubble did not switch to TTS_WITH_LISTENING mode")
                failWithScreenshot("voice_control_bubble_not_tts_mode", "bubble not in TTS_WITH_LISTENING mode")
                return@runBlocking
            }

            // Step 10: Send voice transcription to disable continuous listening (app is backgrounded in bubble mode, use broadcast)
            Log.d(TAG, "💬 step 10: sending voice transcription to disable continuous listening...")
            val disableMessage = "Please turn off continuous listening - ${System.currentTimeMillis()}"

            val disableIntent = Intent("com.example.whiz.TEST_TRANSCRIPTION").apply {
                putExtra("text", disableMessage)
                putExtra("fromVoice", true)
                putExtra("autoSend", true)
            }
            instrumentation.targetContext.sendBroadcast(disableIntent)
            Log.d(TAG, "✅ Sent transcription broadcast: '$disableMessage'")

            // Step 11: Wait for bubble to switch to MIC_OFF mode
            Log.d(TAG, "⏳ step 11: waiting for bubble to switch to MIC_OFF mode...")
            var modeSwitched = false
            val disableStartTime = System.currentTimeMillis()
            val disableTimeout = 15000L

            while (System.currentTimeMillis() - disableStartTime < disableTimeout) {
                val currentMode = BubbleOverlayService.bubbleListeningMode
                if (currentMode == com.example.whiz.services.ListeningMode.MIC_OFF) {
                    modeSwitched = true
                    val elapsed = System.currentTimeMillis() - disableStartTime
                    Log.d(TAG, "✅ Bubble switched to MIC_OFF mode after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!modeSwitched) {
                Log.e(TAG, "❌ FAILURE: bubble did not switch to MIC_OFF mode")
                failWithScreenshot("voice_control_bubble_not_mic_off", "bubble not in MIC_OFF mode")
                return@runBlocking
            }

            Log.d(TAG, "🎉 voice control tools test (voice mode) PASSED!")
            Log.d(TAG, "✅ Test validated: voice mode with TTS preservation, disable/enable TTS, disable continuous listening")

            // Track final chat ID
            try {
                val finalChatId = capturedViewModel?.chatId?.value
                if (finalChatId != null && finalChatId > 0 && !createdChatIds.contains(finalChatId)) {
                    createdChatIds.add(finalChatId)
                    Log.d(TAG, "📝 Tracked final chat ID: $finalChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track final chat ID: ${e.message}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_control_voice_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
