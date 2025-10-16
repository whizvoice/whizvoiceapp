package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * Integration test for voice control tools (disable continuous listening, set TTS enabled/disabled).
 *
 * Tests the server's ability to control voice features via tools:
 * - disable_continuous_listening tool
 * - set_tts_enabled tool (both enable and disable)
 *
 * Uses regular chat mode where messages are typed.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoiceControlToolsTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "VoiceControlToolsTest"
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
            cleanupTestChats(
                repository = repository,
                trackedChatIds = createdChatIds,
                additionalPatterns = listOf(
                    "turn off continuous listening", // Match test messages
                    "turn on tts",
                    "turn off tts",
                    uniqueTestId.toString()
                ),
                enablePatternFallback = true
            )
            createdChatIds.clear()
            ComposeTestHelper.cleanup()
            Log.d(TAG, "✅ test cleanup completed")
        }
    }

    @Test
    fun testVoiceControlTools_disableContinuousListening_toggleTTS(): Unit = runBlocking {
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
            delay(500)
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

            // Step 3: Enable continuous listening initially
            Log.d(TAG, "🎙️ step 3: enabling continuous listening...")
            instrumentation.runOnMainSync {
                voiceManager.updateContinuousListeningEnabled(true)
            }
            delay(500)

            val initialListeningState = voiceManager.isContinuousListeningEnabled.value
            if (!initialListeningState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not enabled initially")
                failWithScreenshot("voice_control_listening_not_enabled", "continuous listening not enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening is ON: $initialListeningState")

            // Step 4: Send message asking to disable continuous listening
            Log.d(TAG, "💬 step 4: sending message to disable continuous listening...")
            val disableMessage = "Please turn off continuous listening - $uniqueTestId"

            if (!ComposeTestHelper.sendMessageWithWebSocketVerification(composeTestRule, disableMessage, chatViewModel)) {
                Log.e(TAG, "❌ FAILURE: failed to send disable message")
                failWithScreenshot("voice_control_disable_message_failed", "failed to send message")
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

            // Step 6: Verify continuous listening is off
            val disabledState = voiceManager.isContinuousListeningEnabled.value
            Log.d(TAG, "🔍 step 6: verifying continuous listening is OFF: $disabledState")
            if (disabledState) {
                Log.e(TAG, "❌ FAILURE: continuous listening should be OFF")
                failWithScreenshot("voice_control_listening_still_on", "listening should be off")
                return@runBlocking
            }

            // Step 7: Enable continuous listening again for next test
            Log.d(TAG, "🎙️ step 7: re-enabling continuous listening...")
            instrumentation.runOnMainSync {
                voiceManager.updateContinuousListeningEnabled(true)
            }
            delay(500)

            val reenabledState = voiceManager.isContinuousListeningEnabled.value
            if (!reenabledState) {
                Log.e(TAG, "❌ FAILURE: continuous listening not re-enabled")
                failWithScreenshot("voice_control_listening_not_reenabled", "listening not re-enabled")
                return@runBlocking
            }
            Log.d(TAG, "✅ Continuous listening re-enabled: $reenabledState")

            // Step 8: Send message to enable TTS
            Log.d(TAG, "🔊 step 8: sending message to enable TTS...")
            val enableTTSMessage = "Please turn on text to speech - $uniqueTestId"

            if (!ComposeTestHelper.sendMessageWithWebSocketVerification(composeTestRule, enableTTSMessage, chatViewModel)) {
                Log.e(TAG, "❌ FAILURE: failed to send enable TTS message")
                failWithScreenshot("voice_control_enable_tts_message_failed", "failed to send message")
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

            // Step 10: Verify TTS is enabled
            val ttsEnabledState = chatViewModel.isVoiceResponseEnabled.value
            Log.d(TAG, "🔍 step 10: verifying TTS is ON: $ttsEnabledState")
            if (!ttsEnabledState) {
                Log.e(TAG, "❌ FAILURE: TTS should be ON")
                failWithScreenshot("voice_control_tts_not_on", "TTS should be on")
                return@runBlocking
            }

            // Step 11: Send message to disable TTS
            Log.d(TAG, "🔇 step 11: sending message to disable TTS...")
            val disableTTSMessage = "Please turn off text to speech - $uniqueTestId"

            if (!ComposeTestHelper.sendMessageWithWebSocketVerification(composeTestRule, disableTTSMessage, chatViewModel)) {
                Log.e(TAG, "❌ FAILURE: failed to send disable TTS message")
                failWithScreenshot("voice_control_disable_tts_message_failed", "failed to send message")
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

            // Step 13: Verify TTS is disabled
            val ttsDisabledState = chatViewModel.isVoiceResponseEnabled.value
            Log.d(TAG, "🔍 step 13: verifying TTS is OFF: $ttsDisabledState")
            if (ttsDisabledState) {
                Log.e(TAG, "❌ FAILURE: TTS should be OFF")
                failWithScreenshot("voice_control_tts_still_on", "TTS should be off")
                return@runBlocking
            }

            Log.d(TAG, "🎉 voice control tools test PASSED!")
            Log.d(TAG, "✅ Test validated: disable continuous listening, enable TTS, disable TTS")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_control_test_exception", "Test failed: ${e.message}")
            throw e
        }
    }
}
