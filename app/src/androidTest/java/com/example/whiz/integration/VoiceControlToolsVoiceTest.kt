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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule

/**
 * Integration test for voice control tools using voice transcription (notification mode).
 *
 * Tests the server's ability to control voice features via tools in voice launch mode:
 * - disable_continuous_listening tool
 * - set_tts_enabled tool (both enable and disable)
 *
 * Uses voice launch mode where messages are sent via simulated voice transcription.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoiceControlToolsVoiceTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "VoiceControlToolsVoiceTest"

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
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager

    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    private val createdChatIds = mutableListOf<Long>()

    @After
    fun tearDown() {
        Log.d(TAG, "TearDown: VoiceControlToolsVoiceTest")

        runBlocking {
            Log.d(TAG, "🧹 Cleaning up voice control test chats")
            try {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "turn off continuous listening",
                        "turn on tts",
                        "turn off tts",
                        "voice control"
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
    fun testVoiceControlTools_voiceMode_disableContinuousListening_toggleTTS(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting voice control tools test (voice mode)")

        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")

        try {
            // Grant microphone permission
            Log.d(TAG, "🎙️ Granting microphone permission")
            device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
            permissionManager.updateMicrophonePermission(true)

            // Capture initial chats before voice launch
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not get initial chats: ${e.message}")
                emptyList()
            }

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

            // Step 2: Verify continuous listening is initially ON
            delay(500)
            val initialListeningState = voiceManager.isContinuousListeningEnabled.value
            Log.d(TAG, "🔍 step 2: initial continuous listening state: $initialListeningState")

            // If it's not on, enable it
            if (!initialListeningState) {
                Log.d(TAG, "🎙️ Enabling continuous listening for test...")
                instrumentation.runOnMainSync {
                    voiceManager.updateContinuousListeningEnabled(true)
                }
                delay(500)
            }

            val enabledState = voiceManager.isContinuousListeningEnabled.value
            if (!enabledState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not enabled")
                failWithScreenshot("voice_control_listening_not_enabled", "listening not enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening is ON: $enabledState")

            // Step 3: Send voice message to disable continuous listening
            Log.d(TAG, "💬 step 3: sending voice message to disable continuous listening...")
            val disableMessage = "Please turn off continuous listening - ${System.currentTimeMillis()}"

            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$disableMessage'")
                    vm.updateInputText(disableMessage, fromVoice = true)
                    vm.sendUserInput(disableMessage)
                    Log.d(TAG, "✅ Voice message sent")
                }
            }

            // Wait for message to appear
            val messageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(disableMessage) },
                timeoutMs = 5000L,
                description = "disable continuous listening message"
            )

            if (!messageAppeared) {
                Log.e(TAG, "❌ Message not displayed")
                failWithScreenshot("voice_control_message_not_displayed", "message not displayed")
                return@runBlocking
            }

            // Step 4: Wait for continuous listening to be disabled by tool
            Log.d(TAG, "⏳ step 4: waiting for continuous listening to be disabled...")
            var listeningDisabled = false
            val disableStartTime = System.currentTimeMillis()
            val disableTimeout = 15000L

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
                Log.e(TAG, "❌ FAILURE: continuous listening not disabled")
                failWithScreenshot("voice_control_listening_not_disabled", "listening not disabled")
                return@runBlocking
            }

            // Verify state
            val disabledState = voiceManager.isContinuousListeningEnabled.value
            Log.d(TAG, "🔍 step 4: verifying continuous listening is OFF: $disabledState")
            if (disabledState) {
                Log.e(TAG, "❌ FAILURE: listening should be OFF")
                failWithScreenshot("voice_control_listening_still_on", "listening still on")
                return@runBlocking
            }

            // Step 5: Re-enable continuous listening for TTS test
            Log.d(TAG, "🎙️ step 5: re-enabling continuous listening...")
            instrumentation.runOnMainSync {
                voiceManager.updateContinuousListeningEnabled(true)
            }
            delay(500)

            val reenabledState = voiceManager.isContinuousListeningEnabled.value
            if (!reenabledState) {
                Log.e(TAG, "❌ FAILURE: listening not re-enabled")
                failWithScreenshot("voice_control_listening_not_reenabled", "listening not re-enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening re-enabled: $reenabledState")

            // Step 6: Send voice message to enable TTS
            Log.d(TAG, "🔊 step 6: sending voice message to enable TTS...")
            val enableTTSMessage = "Please turn on text to speech - ${System.currentTimeMillis()}"

            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$enableTTSMessage'")
                    vm.updateInputText(enableTTSMessage, fromVoice = true)
                    vm.sendUserInput(enableTTSMessage)
                    Log.d(TAG, "✅ Voice message sent")
                }
            }

            // Wait for message to appear
            val enableTTSMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(enableTTSMessage) },
                timeoutMs = 5000L,
                description = "enable TTS message"
            )

            if (!enableTTSMessageAppeared) {
                Log.e(TAG, "❌ Enable TTS message not displayed")
                failWithScreenshot("voice_control_enable_tts_message_not_displayed", "message not displayed")
                return@runBlocking
            }

            // Step 7: Wait for TTS to be enabled
            Log.d(TAG, "⏳ step 7: waiting for TTS to be enabled...")
            var ttsEnabled = false
            val enableTTSStartTime = System.currentTimeMillis()
            val enableTTSTimeout = 15000L

            while (System.currentTimeMillis() - enableTTSStartTime < enableTTSTimeout) {
                val currentTTSState = capturedViewModel?.isVoiceResponseEnabled?.value ?: false
                if (currentTTSState) {
                    ttsEnabled = true
                    val elapsed = System.currentTimeMillis() - enableTTSStartTime
                    Log.d(TAG, "✅ TTS enabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsEnabled) {
                Log.e(TAG, "❌ FAILURE: TTS not enabled")
                failWithScreenshot("voice_control_tts_not_enabled", "TTS not enabled")
                return@runBlocking
            }

            // Verify TTS state
            val ttsEnabledState = capturedViewModel?.isVoiceResponseEnabled?.value ?: false
            Log.d(TAG, "🔍 step 7: verifying TTS is ON: $ttsEnabledState")
            if (!ttsEnabledState) {
                Log.e(TAG, "❌ FAILURE: TTS should be ON")
                failWithScreenshot("voice_control_tts_not_on", "TTS not on")
                return@runBlocking
            }

            // Step 8: Send voice message to disable TTS
            Log.d(TAG, "🔇 step 8: sending voice message to disable TTS...")
            val disableTTSMessage = "Please turn off text to speech - ${System.currentTimeMillis()}"

            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$disableTTSMessage'")
                    vm.updateInputText(disableTTSMessage, fromVoice = true)
                    vm.sendUserInput(disableTTSMessage)
                    Log.d(TAG, "✅ Voice message sent")
                }
            }

            // Wait for message to appear
            val disableTTSMessageAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(disableTTSMessage) },
                timeoutMs = 5000L,
                description = "disable TTS message"
            )

            if (!disableTTSMessageAppeared) {
                Log.e(TAG, "❌ Disable TTS message not displayed")
                failWithScreenshot("voice_control_disable_tts_message_not_displayed", "message not displayed")
                return@runBlocking
            }

            // Step 9: Wait for TTS to be disabled
            Log.d(TAG, "⏳ step 9: waiting for TTS to be disabled...")
            var ttsDisabled = false
            val disableTTSStartTime = System.currentTimeMillis()
            val disableTTSTimeout = 15000L

            while (System.currentTimeMillis() - disableTTSStartTime < disableTTSTimeout) {
                val currentTTSState = capturedViewModel?.isVoiceResponseEnabled?.value ?: false
                if (!currentTTSState) {
                    ttsDisabled = true
                    val elapsed = System.currentTimeMillis() - disableTTSStartTime
                    Log.d(TAG, "✅ TTS disabled after ${elapsed}ms")
                    break
                }
                delay(200)
            }

            if (!ttsDisabled) {
                Log.e(TAG, "❌ FAILURE: TTS not disabled")
                failWithScreenshot("voice_control_tts_not_disabled", "TTS not disabled")
                return@runBlocking
            }

            // Verify TTS state
            val ttsDisabledState = capturedViewModel?.isVoiceResponseEnabled?.value ?: false
            Log.d(TAG, "🔍 step 9: verifying TTS is OFF: $ttsDisabledState")
            if (ttsDisabledState) {
                Log.e(TAG, "❌ FAILURE: TTS should be OFF")
                failWithScreenshot("voice_control_tts_still_on", "TTS still on")
                return@runBlocking
            }

            Log.d(TAG, "🎉 voice control tools test (voice mode) PASSED!")
            Log.d(TAG, "✅ Test validated: voice mode, disable listening, enable/disable TTS")

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
