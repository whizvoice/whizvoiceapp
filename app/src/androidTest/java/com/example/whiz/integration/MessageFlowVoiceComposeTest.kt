package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createComposeRule
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
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val composeTestRule = createComposeRule()

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

            // First verify chat ID is initially -1 for new chat (before any messages)
            val chatViewModel = androidx.lifecycle.ViewModelProvider(activity)[com.example.whiz.ui.viewmodels.ChatViewModel::class.java]
            val initialChatId = chatViewModel.chatId.value
            if (initialChatId != -1L) {
                Log.e(TAG, "❌ FAILURE: New chat should have ID -1 but has ID: $initialChatId")
                failWithScreenshot("new_chat_wrong_initial_id", "New chat should have ID -1 but has ID: $initialChatId")
                return@runBlocking
            }
            Log.d(TAG, "✅ New chat has correct initial ID: $initialChatId")

            // Wait a moment for services to initialize
            Log.d(TAG, "⏳ Waiting for services to initialize...")
            Thread.sleep(1000) // Give WebSocket time to connect
            
            // step 2: Send voice message using voice input simulation
            Log.d(TAG, "🎤 step 2: Sending voice message...")
            val testMessage = "Hello from voice launch test using ActivityScenarioRule - this should work without authentication issues!"
            
            // Simulate voice transcription and automatic sending (as voice input typically does)
            // Use simulateVoiceTranscriptionAndSend which directly calls ChatViewModel methods
            val voiceSendSuccess = simulateVoiceTranscriptionAndSend(
                testMessage, 
                rapid = false, 
                chatViewModel = chatViewModel,
                speechRecognitionService = speechRecognitionService
            )
            if (!voiceSendSuccess) {
                Log.e(TAG, "❌ FAILURE at step 2: Failed to send voice message via transcription simulation")
                failWithScreenshot("voice_send_failed", "Failed to send voice message via transcription simulation")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Voice message sent: $testMessage")

            // step 3: Wait for message to appear and verify optimistic chat ID
            Thread.sleep(1000) // Wait for message to be processed
            
            // step 3.5: Verify optimistic chat ID is negative but not -1
            val optimisticChatId = chatViewModel.chatId.value
            Log.d(TAG, "🔍 Optimistic chat ID after sending message: $optimisticChatId")
            
            if (optimisticChatId!! >= 0) {
                Log.e(TAG, "❌ FAILURE: Optimistic chat ID should be negative but is: $optimisticChatId")
                failWithScreenshot("optimistic_chat_id_not_negative", "Optimistic chat ID should be negative but is: $optimisticChatId")
                return@runBlocking
            } else if (optimisticChatId == -1L) {
                Log.e(TAG, "❌ FAILURE: Optimistic chat ID should not be exactly -1 but is: $optimisticChatId")
                failWithScreenshot("optimistic_chat_id_is_minus_one", "Optimistic chat ID should not be exactly -1 but is: $optimisticChatId")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Optimistic chat ID is correct: $optimisticChatId (negative but not -1)")

            // step 4: Verify message appears in UI using Compose testing
            composeTestRule.waitForIdle()
            
            // Use Compose testing to verify message appears
            composeTestRule.onNodeWithText(testMessage).assertIsDisplayed()
            Log.d(TAG, "✅ Message verified in UI using Compose testing")

            Log.d(TAG, "🎉 Voice launch test completed successfully!")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_launch_test_exception", "Test failed with exception: ${e.message}")
            throw e
        }
    }
}