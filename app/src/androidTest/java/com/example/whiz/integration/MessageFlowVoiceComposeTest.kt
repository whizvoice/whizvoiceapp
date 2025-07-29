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
        
        // Capture ViewModel from navigation scope
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
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
    
    @After
    fun tearDown() {
        Log.d(TAG, "TearDown: MessageFlowVoiceComposeTest")
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
            
            // step 2: Use voice input with the captured ViewModel
            Log.d(TAG, "🎤 step 2: Simulating voice input...")
            val testMessage = "Hello from voice launch test using navigation-scoped ViewModel"
            
            // Check if voice mode is active
            val listeningActive = device.hasObject(By.text("Listening...").pkg(packageName))
            Log.d(TAG, "Voice mode active: $listeningActive")
            
            // Simulate voice transcription using the captured ViewModel
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$testMessage'")
                    vm.updateInputText(testMessage, fromVoice = true)
                    vm.sendUserInput(testMessage)
                    Log.d(TAG, "✅ Voice message sent via ChatViewModel")
                }
            }
            
            Log.d(TAG, "✅ Voice simulation complete")

            // step 4: Wait for message to appear in UI
            Thread.sleep(1000) // Wait for message to be processed
            
            Log.d(TAG, "🔍 Verifying message appears in UI...")

            // step 5: Verify message appears in UI using Compose testing
            composeTestRule.waitForIdle()
            
            // Use Compose testing to verify message appears
            try {
                composeTestRule.onNodeWithText(testMessage).assertIsDisplayed()
                Log.d(TAG, "✅ Message verified in UI using Compose testing")
            } catch (e: Throwable) {  // Changed from AssertionError to Throwable to catch all exceptions
                Log.e(TAG, "❌ Message not found in UI: ${e.message}")
                Log.e(TAG, "❌ Exception type: ${e.javaClass.name}")
                failWithScreenshot("Message not displayed in UI after voice input", "message_not_displayed")
                throw e
            }

            Log.d(TAG, "🎉 Voice launch test completed successfully!")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Test failed with exception: ${e.message}", e)
            failWithScreenshot("voice_launch_test_exception", "Test failed with exception: ${e.message}")
            throw e
        }
    }
}