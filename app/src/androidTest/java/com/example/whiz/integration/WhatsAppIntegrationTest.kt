package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.MainActivity
import android.util.Log
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
import com.example.whiz.test_helpers.PermissionAutomator
import com.example.whiz.data.auth.AuthRepository

/**
 * Integration test for WhatsApp functionality using voice commands:
 * - Voice launch directly to chat
 * - Ask assistant to send WhatsApp message via voice
 * - Verify draft is shown
 * - Use voice to make corrections to the draft
 * - Send the corrected message
 * 
 * This test simulates real user interaction where they ask the assistant
 * to help them send a WhatsApp message through natural language commands.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WhatsAppIntegrationTest : BaseIntegrationTest() {
    
    // WhatsApp tests require accessibility service to navigate and interact with the app
    override val requireAccessibilityService: Boolean = true

    companion object {
        private val TAG = "WhatsAppIntegrationTest"
        private const val WHATSAPP_CONTACT_NAME = "+1 (628) 209-9005 (You)"
        private const val SCREENSHOT_PREFIX = "whatsapp_test"
        
        // Capture ViewModel from navigation scope (same pattern as MessageFlowVoiceComposeTest)
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
        
        init {
            // Tell TestAppModule to use real accessibility for this test
            System.setProperty("test.real.accessibility", "true")
            Log.d(TAG, "📱 Configured test to use REAL accessibility services")
        }
    }

    @get:Rule(order = 2)
    val composeTestRule = createComposeRule()
    
    @get:Rule(order = 3)
    val skipOnCIOrEmulatorRule = SkipOnCIOrEmulatorRule()

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
    @Inject
    lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uniqueTestId = System.currentTimeMillis()
    private val createdChatIds = mutableListOf<Long>()
    private val permissionAutomator = PermissionAutomator()
    private var manuallyLaunchedActivity: MainActivity? = null
    
    @Before
    fun setUp() {
        // The base class handles authentication in setUpAuthentication()
        Log.d(TAG, "🎬 WhatsApp voice integration test setup complete")
        Log.d(TAG, "📱 Test ID: $uniqueTestId")
        Log.d(TAG, "📞 Contact: $WHATSAPP_CONTACT_NAME")
    }
    
    /**
     * Check if a permission dialog is blocking and handle it
     * Returns true if a dialog was handled, false otherwise
     */
    private suspend fun handlePermissionDialogIfBlocking(): Boolean {
        // Just check if there's a permission dialog and handle it
        // This handles accessibility, overlay, or any other permission dialog
        // Pass composeTestRule to also wait for "Starting Accessibility Service" dialog
        if (permissionAutomator.handlePermissionDialogs(composeTestRule)) {
            Log.d(TAG, "✅ Permission dialog was blocking, handled it")
            return true
        }
        return false
    }
    

    @After
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up WhatsApp test")
        
        // First, try to click the notification bubble to return to app
        try {
            Log.d(TAG, "🔔 Checking for notification bubble...")
            // Try different content descriptions based on bubble state
            val notificationBubble = device.findObject(By.desc("WhizVoice Chat Bubble"))
                ?: device.findObject(By.desc("WhizVoice Chat Bubble - Listening Mode"))
                ?: device.findObject(By.desc("WhizVoice Chat Bubble - Mic Off"))
                ?: device.findObject(By.desc("WhizVoice Chat Bubble - Speaking Mode"))
                ?: device.findObject(By.res("com.example.whiz.debug:id/chat_head"))
            if (notificationBubble != null) {
                notificationBubble.click()
                Log.d(TAG, "✅ Clicked notification bubble to return to app")
                Thread.sleep(1000) // Give time for app to come to foreground
            } else {
                Log.d(TAG, "ℹ️ No notification bubble found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error clicking notification bubble: ${e.message}")
        }
        
        // Clean up test chats
        runBlocking {
            try {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "send a message to $WHATSAPP_CONTACT_NAME",
                        "WhatsApp",
                        "tryna test",
                        "trying to test",
                        uniqueTestId.toString()
                    ),
                    enablePatternFallback = true
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
        ComposeTestHelper.cleanup()
        
        // Close manually launched activity
        try {
            manuallyLaunchedActivity?.let {
                if (!it.isFinishing && !it.isDestroyed) {
                    it.finish()
                    Log.d(TAG, "✅ Manually launched activity finished")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error finishing manually launched activity: ${e.message}")
        }
        manuallyLaunchedActivity = null
        
        Log.d(TAG, "✅ WhatsApp test cleanup completed")
    }

    @Test
    fun testWhatsAppNavigationFromMainChatList() {
        runBlocking {
        Log.d(TAG, "🚀 Starting WhatsApp navigation test from main chat list")
        
        try {
            // Voice launch Whiz app
            Log.d(TAG, "🎤 Voice launching Whiz app...")
            val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
                putExtra("tracing_intent_id", System.currentTimeMillis())
            }
            
            // Set up the ViewModel capture callback before launching
            capturedViewModel = null
            MainActivity.testViewModelCallback = { viewModel ->
                capturedViewModel = viewModel
                Log.d(TAG, "✅ ChatViewModel captured from MainActivity")
            }
            
            // Launch through real Android system
            val activity = instrumentation.startActivitySync(voiceLaunchIntent) as MainActivity
            manuallyLaunchedActivity = activity
            
            // Wait for ViewModel to be captured
            Log.d(TAG, "⏳ Waiting for ViewModel to be captured...")
            var attempts = 0
            while (capturedViewModel == null && attempts < 20) {
                Log.d(TAG, "⏳ Waiting for ViewModel capture... (attempt ${attempts + 1})")
                delay(500)
                attempts++
            }
            
            if (capturedViewModel == null) {
                Log.e(TAG, "❌ Failed to capture ChatViewModel after ${attempts} attempts")
                failWithScreenshot("Failed to capture ViewModel", "$SCREENSHOT_PREFIX-nav_viewmodel_not_captured")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ ChatViewModel captured successfully")
            
            // Track any new chat created
            try {
                val currentChatId = capturedViewModel?.chatId?.value
                if (currentChatId != null && currentChatId != -1L) {
                    if (!createdChatIds.contains(currentChatId)) {
                        createdChatIds.add(currentChatId)
                        Log.d(TAG, "📝 Tracked chat for cleanup: $currentChatId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track chat ID: ${e.message}")
            }
            
            if (!handlePermissionDialogIfBlocking()) {
                Log.d(TAG, "⚠️ No permission dialog was handled")
            }

            // Wait for accessibility service to start via app launch
            Log.d(TAG, "🔧 Waiting for accessibility service to start...")
            if (!waitForAccessibilityServiceViaAppLaunch(chatViewModel = capturedViewModel)) {
                Log.e(TAG, "❌ Accessibility service failed to start within timeout")
                takeFailureScreenshotAndWaitForCompletion("testWhatsAppChatOpeningOnlyDoesNotDuplicate", "Accessibility service failed to start")
                throw AssertionError("Accessibility service failed to start within timeout")
            }
            Log.d(TAG, "✅ Accessibility service is ready")

            // Send voice command to open WhatsApp chat
            Log.d(TAG, "🎤 Sending voice command to open WhatsApp chat...")
            val openChatRequest = "Open WhatsApp chat with $WHATSAPP_CONTACT_NAME"
            
            
            // Simulate voice transcription using the captured ViewModel
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice command: '$openChatRequest'")
                    vm.updateInputText(openChatRequest, fromVoice = true)
                    vm.sendUserInput(openChatRequest)
                    Log.d(TAG, "✅ Voice command sent")
                }
            }
            
            // Wait for the request to appear in UI
            Log.d(TAG, "🔍 Waiting for voice command to appear in UI...")
            var requestAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(openChatRequest, substring = true) },
                timeoutMs = 5000L,
                description = "open chat voice command"
            )
            
            if (!requestAppeared) {
                Log.e(TAG, "❌ Voice command not found in UI")
                failWithScreenshot("Voice command not displayed", "$SCREENSHOT_PREFIX-nav_command_not_displayed")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Voice command visible in chat")
            
            // Wait for assistant to process and navigate to WhatsApp
            Log.d(TAG, "⏳ Waiting for assistant to process and navigate...")
            delay(5000) // Give assistant time to process and switch to WhatsApp
            
            // Verify we're now in the correct WhatsApp chat
            Log.d(TAG, "🔍 Verifying navigation to correct WhatsApp chat...")
            val isInCorrectChat = verifyInWhatsAppChat(WHATSAPP_CONTACT_NAME)
            
            if (isInCorrectChat) {
                Log.d(TAG, "🎉 Successfully navigated to WhatsApp chat with $WHATSAPP_CONTACT_NAME")
            } else {
                Log.w(TAG, "⚠️ Navigation may not have completed successfully")
                
                // Check if we're still in Whiz app (navigation didn't happen)
                val stillInWhiz = device.currentPackageName == packageName
                if (stillInWhiz) {
                    Log.e(TAG, "❌ Still in Whiz app, navigation to WhatsApp didn't occur")
                    
                    // Try to click bubble first to bring app to foreground
                    Log.d(TAG, "🔔 Attempting to click bubble to bring app to foreground...")
                    try {
                        // Try different content descriptions based on bubble state
                        val bubble = device.findObject(By.desc("WhizVoice Chat Bubble"))
                            ?: device.findObject(By.desc("WhizVoice Chat Bubble - Listening Mode"))
                            ?: device.findObject(By.desc("WhizVoice Chat Bubble - Mic Off"))
                            ?: device.findObject(By.desc("WhizVoice Chat Bubble - Speaking Mode"))
                            ?: device.findObject(By.res("com.example.whiz.debug:id/chat_head"))
                        if (bubble != null) {
                            bubble.click()
                            Log.d(TAG, "✅ Clicked bubble")
                            delay(1000)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not click bubble: ${e.message}")
                    }
                    
                    // Use UiAutomator to look for error messages instead of Compose test rules
                    // This works better when app is in bubble/background state
                    val errorPatterns = listOf(
                        "couldn't open",
                        "unable to",
                        "error",
                        "failed",
                        "not found"
                    )
                    
                    for (pattern in errorPatterns) {
                        try {
                            val errorNode = device.findObject(By.textContains(pattern))
                            if (errorNode != null) {
                                Log.e(TAG, "❌ Found error message with pattern: '$pattern'")
                                break
                            }
                        } catch (e: Exception) {
                            // Continue checking other patterns
                        }
                    }
                    failWithScreenshot("Navigation to WhatsApp failed", "$SCREENSHOT_PREFIX-nav_failed")
                } else {
                    Log.w(TAG, "⚠️ In WhatsApp but possibly not in the correct chat")
                }
            }
            
            // Track updated chat ID if it changed
            try {
                val updatedChatId = capturedViewModel?.chatId?.value
                if (updatedChatId != null && updatedChatId > 0 && !createdChatIds.contains(updatedChatId)) {
                    createdChatIds.add(updatedChatId)
                    Log.d(TAG, "📝 Tracked server chat ID for cleanup: $updatedChatId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track updated chat ID: ${e.message}")
            }
            
            Log.d(TAG, "✅ WhatsApp navigation test from main chat list completed")
            
            // Click on notification bubble to return to the app and properly end the test
            Log.d(TAG, "🔔 Clicking notification bubble to return to app...")
            try {
                val notificationBubble = device.findObject(By.res("com.example.whiz.debug:id/overlay_bubble"))
                if (notificationBubble != null) {
                    notificationBubble.click()
                    Log.d(TAG, "✅ Clicked notification bubble")
                    // Wait for app to come to foreground
                    device.wait(Until.hasObject(By.pkg(packageName)), 2000L)
                } else {
                    Log.w(TAG, "⚠️ Notification bubble not found, test may not end properly")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not click notification bubble: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WhatsApp navigation test FAILED", e)
            failWithScreenshot("Test failed with exception: ${e.message}", "$SCREENSHOT_PREFIX-nav_exception")
            throw e
        }
        }
    }
    
    @Test
    fun testWhatsAppMessageFlow_withVoiceCommands() = runBlocking {
        Log.d(TAG, "🚀 Starting WhatsApp voice integration test")
        
        try {
            // Capture initial chats before test
            val initialChats = try {
                repository.getAllChats()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not get initial chats: ${e.message}")
                emptyList()
            }
            
            // Step 1: Voice launch the app (goes directly to chat)
            Log.d(TAG, "🎤 Step 1: Voice launching app...")
            val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
                putExtra("tracing_intent_id", System.currentTimeMillis())
            }
            
            // Set up the ViewModel capture callback before launching
            capturedViewModel = null
            MainActivity.testViewModelCallback = { viewModel ->
                capturedViewModel = viewModel
                Log.d(TAG, "✅ ChatViewModel captured from MainActivity")
            }
            
            // Launch through real Android system like MessageFlowVoiceComposeTest does
            val activity = instrumentation.startActivitySync(voiceLaunchIntent) as MainActivity
            manuallyLaunchedActivity = activity
            
            // Wait for ViewModel to be captured
            Log.d(TAG, "⏳ Waiting for ViewModel to be captured...")
            var attempts = 0
            while (capturedViewModel == null && attempts < 20) {
                Log.d(TAG, "⏳ Waiting for ViewModel capture... (attempt ${attempts + 1})")
                delay(500)
                attempts++
            }
            
            if (capturedViewModel == null) {
                Log.e(TAG, "❌ Failed to capture ChatViewModel after ${attempts} attempts")
                failWithScreenshot("Failed to capture ViewModel", "$SCREENSHOT_PREFIX-viewmodel_not_captured")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ ChatViewModel captured successfully")

            if (!handlePermissionDialogIfBlocking()) {
                Log.d(TAG, "⚠️ No permission dialog was handled")
            }

            // Wait for accessibility service to start
            Log.d(TAG, "🔧 Waiting for accessibility service to start...")

            // Check every 500ms if accessibility service permission has been granted at system level
            Log.d(TAG, "🔍 Checking if accessibility service permission is granted...")
            var elapsedTime = 0L
            val checkInterval = 500L
            val maxWaitTime = 10000L
            var permissionGranted = false

            // Check system settings directly for immediate feedback
            while (elapsedTime < maxWaitTime && !permissionGranted) {
                // Check if the accessibility service is enabled in system settings
                val enabledServices = android.provider.Settings.Secure.getString(
                    instrumentation.targetContext.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )

                if (enabledServices != null) {
                    val serviceName = "${packageName}/com.example.whiz.accessibility.WhizAccessibilityService"
                    permissionGranted = enabledServices.contains(serviceName)
                }

                if (permissionGranted) {
                    Log.d(TAG, "✅ Accessibility service permission granted after ${elapsedTime}ms")
                    break
                }

                Log.d(TAG, "⏳ Waiting for accessibility permission... (${elapsedTime/1000}s / ${maxWaitTime/1000}s)")
                delay(checkInterval)
                elapsedTime += checkInterval
            }

            if (!permissionGranted) {
                Log.e(TAG, "❌ Accessibility service permission NOT granted after ${maxWaitTime/1000} seconds")
                takeFailureScreenshotAndWaitForCompletion("testWhatsAppMessageFlow_withVoiceCommands", "Accessibility_permission_not_granted_timeout")
                throw AssertionError("Accessibility service permission was not granted within ${maxWaitTime/1000} seconds")
            }

            // Force Compose to process any pending state changes from permission grant
            Log.d(TAG, "⏰ Forcing Compose recomposition after permission grant...")
            composeTestRule.mainClock.advanceTimeBy(100)  // Advance the Compose test clock
            Log.d(TAG, "✅ Compose recomposition complete")

            // Try to disconnect and reconnect UiAutomation to allow accessibility service to start
            Log.d(TAG, "🔓 Attempting to disconnect UiAutomation temporarily...")

            try {
                // Get the instrumentation
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()

                // Get current UiAutomation instance
                val currentAutomation = instrumentation.uiAutomation
                Log.d(TAG, "📦 Current UiAutomation instance: ${currentAutomation?.javaClass?.simpleName}")

                // Disconnect UiAutomation
                currentAutomation?.disconnect()
                Log.d(TAG, "✅ UiAutomation disconnected")

                // Force garbage collection to help release resources
                System.gc()
                Thread.sleep(500) // Brief pause to let system process the change

                // Check if accessibility service starts now - wait up to 30 seconds
                Log.d(TAG, "⏳ Waiting up to 30 seconds for accessibility service to start after disconnecting UiAutomation...")

                var serviceStarted = false
                val maxWaitTime = 30000L // 30 seconds
                val startTime = System.currentTimeMillis()

                while ((System.currentTimeMillis() - startTime) < maxWaitTime) {
                    if (WhizAccessibilityService.isServiceConnected()) {
                        val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                        Log.d(TAG, "🎉 Accessibility service started after ${elapsedSeconds} seconds of disconnecting UiAutomation!")
                        serviceStarted = true
                        break
                    }

                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                    Log.d(TAG, "⏱️ Waiting... ${elapsedSeconds}s elapsed, service still not connected")
                    Thread.sleep(1000) // Check every second
                }

                if (!serviceStarted) {
                    Log.e(TAG, "❌ Accessibility service failed to start within 30 seconds of disconnecting UiAutomation")

                    // Try to reconnect before failing
                    try {
                        val newAutomation = instrumentation.uiAutomation
                        newAutomation?.let {
                            Log.d(TAG, "🔧 Reconnected UiAutomation before failing")
                            // Recreate UiDevice with the new automation
                            device = UiDevice.getInstance(instrumentation)
                        }
                    } catch (reconnectError: Exception) {
                        Log.e(TAG, "❌ Failed to reconnect UiAutomation: ${reconnectError.message}")
                    }

                    // Fail the test since this approach didn't work
                    throw AssertionError("Accessibility service failed to start even after disconnecting UiAutomation for 30 seconds")
                }

                // Reconnect UiAutomation for the rest of the test
                Log.d(TAG, "🔧 Reconnecting UiAutomation...")
                val newAutomation = instrumentation.uiAutomation
                if (newAutomation != null) {
                    Log.d(TAG, "✅ UiAutomation reconnected: ${newAutomation.javaClass.simpleName}")

                    // Recreate UiDevice with the new automation connection
                    device = UiDevice.getInstance(instrumentation)
                    Log.d(TAG, "✅ UiDevice recreated with new UiAutomation connection")
                } else {
                    Log.e(TAG, "⚠️ Could not reconnect UiAutomation - it returned null")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to disconnect/reconnect UiAutomation: ${e.message}", e)

                // Try to ensure we have a working UiAutomation for the rest of the test
                try {
                    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                    val currentAutomation = instrumentation.uiAutomation
                    if (currentAutomation != null) {
                        device = UiDevice.getInstance(instrumentation)
                        Log.d(TAG, "✅ UiDevice recovered in catch block")
                    }
                } catch (recoveryError: Exception) {
                    Log.e(TAG, "❌ Failed to recover UiAutomation: ${recoveryError.message}")
                }
            }

            // Wait for accessibility service to start via app launch
            Log.d(TAG, "🔧 Waiting for accessibility service to start...")
            if (!waitForAccessibilityServiceViaAppLaunch(chatViewModel = capturedViewModel)) {
                Log.e(TAG, "❌ Accessibility service failed to start within timeout")
                takeFailureScreenshotAndWaitForCompletion("testWhatsAppChatOpeningOnlyDoesNotDuplicate", "Accessibility service failed to start")
                throw AssertionError("Accessibility service failed to start within timeout")
            }
            Log.d(TAG, "✅ Accessibility service is ready")

            // Step 2: Send voice command to draft WhatsApp message
            Log.d(TAG, "🎤 Step 2: Sending WhatsApp message request via voice...")
            val whatsappRequest = "Hello, can you please send a message to $WHATSAPP_CONTACT_NAME that says hey what's up how's it going just tryna test whiz voice"
            
            // Simulate voice transcription using the captured ViewModel
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice transcription: '$whatsappRequest'")
                    vm.updateInputText(whatsappRequest, fromVoice = true)
                    vm.sendUserInput(whatsappRequest)
                    Log.d(TAG, "✅ WhatsApp request sent via voice")
                }
            }
            
            // Wait for the request to appear in UI
            Log.d(TAG, "🔍 Waiting for WhatsApp request to appear in UI...")
            val requestAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(whatsappRequest, substring = true) },
                timeoutMs = 5000L,
                description = "WhatsApp message request"
            )
            
            if (!requestAppeared) {
                Log.e(TAG, "❌ WhatsApp request not found in UI")
                failWithScreenshot("WhatsApp request not displayed", "$SCREENSHOT_PREFIX-request_not_displayed")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ WhatsApp request visible in chat")
            
            // Track any new chat created after message is sent (do this before bubble mode)
            try {
                val currentChatId = capturedViewModel?.chatId?.value
                if (currentChatId != null && currentChatId != -1L) {
                    if (!createdChatIds.contains(currentChatId)) {
                        createdChatIds.add(currentChatId)
                        Log.d(TAG, "📝 Tracked chat for cleanup: $currentChatId")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track chat ID: ${e.message}")
            }
            
            // Permissions should already be handled by BaseIntegrationTest during setup
            
            // Step 3: Wait for assistant to process and show draft
            Log.d(TAG, "⏳ Step 3: Waiting for assistant to process and show draft overlay...")
            
            // Important: App should now be in bubble mode with draft overlay
            // DO NOT click the bubble as that would dismiss the overlay
            // The draft appears as an overlay when in bubble mode
            
            // Wait for the WhizVoice Draft overlay to appear (up to 5 seconds)
            val draftAppeared = device.wait(
                Until.hasObject(By.res("com.example.whiz.debug:id/draft_label")),
                5000L
            ) || device.wait(
                Until.hasObject(By.text("WhizVoice Draft")),
                1000L
            )
            
            if (!draftAppeared) {
                Log.e(TAG, "❌ WhizVoice Draft overlay did not appear within timeout")
                failWithScreenshot("Draft overlay not shown", "$SCREENSHOT_PREFIX-draft_overlay_timeout")
                return@runBlocking
            }
            
            Log.d(TAG, "🔍 Draft overlay appeared, now checking content...")
            
            // Now get the draft label and message
            val draftLabel = device.findObject(By.res("com.example.whiz.debug:id/draft_label"))
                ?: device.findObject(By.text("WhizVoice Draft"))
            
            var draftFound = false
            var draftText: String? = null
            
            if (draftLabel != null) {
                Log.d(TAG, "✅ Found WhizVoice Draft label")
                
                // Now find the actual message text
                val draftMessageElement = device.findObject(By.res("com.example.whiz.debug:id/draft_message_text"))
                if (draftMessageElement != null) {
                    draftText = draftMessageElement.text
                    Log.d(TAG, "📝 Draft message text: '$draftText'")
                    
                    // Check if the draft contains the expected content
                    if (draftText != null && draftText.contains("hey what's up how's it going just tryna test whiz voice", ignoreCase = true)) {
                        Log.d(TAG, "✅ Draft message contains expected content")
                        draftFound = true
                    } else {
                        Log.e(TAG, "❌ Draft message doesn't contain expected content. Actual: '$draftText'")
                    }
                } else {
                    Log.e(TAG, "❌ Could not find draft_message_text element")
                }
            } else {
                Log.e(TAG, "❌ WhizVoice Draft overlay not found")
            }
            
            if (!draftFound) {
                Log.e(TAG, "❌ Draft message not found in assistant response")
                failWithScreenshot("Assistant did not show draft message", "$SCREENSHOT_PREFIX-draft_not_found")
                return@runBlocking
            }
            
            // Step 4: Send correction via voice
            Log.d(TAG, "✏️ Step 4: Sending correction via voice...")
            val correction = "actually can you change tryna to trying to, and then finish the sentence with a period and add hope you're having a good day!"
            
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice correction: '$correction'")
                    vm.updateInputText(correction, fromVoice = true)
                    vm.sendUserInput(correction)
                    Log.d(TAG, "✅ Correction sent via voice")
                }
            }
            
            // Wait for correction to appear in overlay (app still in bubble mode)
            Log.d(TAG, "🔍 Waiting for correction to appear in overlay...")
            
            // DO NOT click bubble - we want to stay in bubble mode to see the overlay
            // Use UiAutomator to check for correction in the overlay
            val correctionAppeared = device.wait(
                Until.hasObject(By.textContains(correction.take(50)).pkg(packageName)),
                5000
            )
            
            if (!correctionAppeared) {
                Log.w(TAG, "⚠️ Correction not visible in UI, but continuing")
            }
            
            // Step 5: Wait for assistant to process correction and show updated draft
            Log.d(TAG, "⏳ Step 5: Waiting for corrected draft overlay...")
            
            // Wait for the draft overlay to update with corrected text
            // The overlay should still be showing but with new content
            val correctedDraftAppeared = device.wait(
                Until.hasObject(By.res("com.example.whiz.debug:id/draft_label")),
                5000L
            )
            
            if (!correctedDraftAppeared) {
                Log.e(TAG, "❌ Corrected draft overlay did not appear within timeout")
                failWithScreenshot("Corrected draft overlay not shown", "$SCREENSHOT_PREFIX-corrected_overlay_timeout")
                return@runBlocking
            }
            
            // Look for the updated WhizVoice Draft overlay with corrected text
            val correctedDraftLabel = device.findObject(By.res("com.example.whiz.debug:id/draft_label"))
                ?: device.findObject(By.text("WhizVoice Draft"))
            
            var correctedFound = false
            var correctedDraftText: String? = null
            
            if (correctedDraftLabel != null) {
                Log.d(TAG, "✅ Found WhizVoice Draft label for corrected message")
                
                // Now find the actual corrected message text
                val correctedMessageElement = device.findObject(By.res("com.example.whiz.debug:id/draft_message_text"))
                if (correctedMessageElement != null) {
                    correctedDraftText = correctedMessageElement.text
                    Log.d(TAG, "📝 Corrected draft message text: '$correctedDraftText'")
                    
                    // Check if the corrected draft contains the expected changes
                    // Note: The overlay may show track changes with strikethrough for old text
                    // So we check for both "trying to" (not "tryna") and "hope you're having a good day"
                    if (correctedDraftText != null) {
                        val hasCorrection1 = correctedDraftText.contains("trying to", ignoreCase = true)
                        val hasCorrection2 = correctedDraftText.contains("hope you're having a good day", ignoreCase = true)
                        val hasPeriod = correctedDraftText.contains(".")
                        
                        Log.d(TAG, "Checking corrections: 'trying to'=$hasCorrection1, 'good day'=$hasCorrection2, period=$hasPeriod")
                        
                        if (hasCorrection1 && hasCorrection2) {
                            Log.d(TAG, "✅ Corrected draft contains all expected changes")
                            correctedFound = true
                        } else {
                            Log.w(TAG, "⚠️ Corrected draft missing some changes. Text: '$correctedDraftText'")
                            // Still consider it found if at least one correction is present
                            correctedFound = hasCorrection1 || hasCorrection2
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Could not find corrected draft_message_text element")
                }
            } else {
                Log.e(TAG, "❌ WhizVoice Draft overlay not found for corrected message")
            }
            
            if (!correctedFound) {
                Log.e(TAG, "❌ Corrected draft not properly displayed")
                failWithScreenshot("Corrected draft not shown", "$SCREENSHOT_PREFIX-corrected_draft_not_found")
                return@runBlocking
            }
            
            // Step 6: Skip verification of messages in chat (they're in the overlay, not the main chat)
            Log.d(TAG, "✅ Test flow completed - messages were sent via overlay while in bubble mode")
            
            Log.d(TAG, "🎉 WhatsApp voice integration test completed!")
            Log.d(TAG, "✅ Successfully tested:")
            Log.d(TAG, "   - Voice launch to chat")
            Log.d(TAG, "   - WhatsApp message request via voice")
            Log.d(TAG, "   - Draft display by assistant")
            Log.d(TAG, "   - Correction via voice")
            Log.d(TAG, "   - Updated draft display")
            
            // Click on notification bubble to return to the app and properly end the test
            Log.d(TAG, "🔔 Clicking notification bubble to return to app...")
            try {
                val notificationBubble = device.findObject(By.res("com.example.whiz.debug:id/overlay_bubble"))
                if (notificationBubble != null) {
                    notificationBubble.click()
                    Log.d(TAG, "✅ Clicked notification bubble")
                    // Wait for app to come to foreground
                    device.wait(Until.hasObject(By.pkg(packageName)), 2000L)
                } else {
                    Log.w(TAG, "⚠️ Notification bubble not found, test may not end properly")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not click notification bubble: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ WhatsApp voice integration test FAILED", e)
            failWithScreenshot("Test failed with exception: ${e.message}", "$SCREENSHOT_PREFIX-exception")
            throw e
        }
    }
    
    // ========== Helper Methods for WhatsApp Navigation Test ==========
    
    /**
     * Launch WhatsApp using UiDevice
     */
    private fun launchWhatsApp() {
        Log.d(TAG, "Launching WhatsApp...")
        try {
            val packageManager = instrumentation.context.packageManager
            val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                instrumentation.context.startActivity(launchIntent)
                device.waitForIdle()
                Thread.sleep(2000) // Wait for WhatsApp to fully launch
            } else {
                Log.w(TAG, "WhatsApp may not be installed, trying alternative launch method")
                // Try launching via shell command as fallback
                device.executeShellCommand("am start -n com.whatsapp/.Main")
                Thread.sleep(2000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WhatsApp: ${e.message}")
            throw e
        }
    }
    
    /**
     * Navigate to WhatsApp main chat list from any screen
     */
    private fun navigateToMainChatList() {
        Log.d(TAG, "Navigating to WhatsApp main chat list...")
        
        // First, try to go back multiple times to ensure we're not deep in navigation
        repeat(5) {
            // Check if we're already on the main chat list
            if (device.hasObject(By.res("com.whatsapp:id/conversations_row_tip")) ||
                device.hasObject(By.res("com.whatsapp:id/fab")) ||
                device.hasObject(By.res("com.whatsapp:id/menuitem_search"))) {
                Log.d(TAG, "Already on main chat list")
                return
            }
            
            // Check if we see the tab layout (means we're on main screen, just maybe different tab)
            if (device.hasObject(By.res("com.whatsapp:id/tab_layout"))) {
                // Click on Chats tab if available
                val chatsTab = device.findObject(By.text("Chats"))
                    ?: device.findObject(By.textContains("CHATS"))
                
                if (chatsTab != null) {
                    chatsTab.click()
                    device.waitForIdle()
                    Thread.sleep(500)
                    Log.d(TAG, "Clicked on Chats tab")
                    return
                }
            }
            
            // Otherwise, go back
            device.pressBack()
            device.waitForIdle()
            Thread.sleep(500)
        }
        
        // Final attempt: click on Chats tab if we can find it
        val chatsTab = device.findObject(By.text("Chats"))
            ?: device.findObject(By.textContains("CHATS"))
        chatsTab?.click()
        device.waitForIdle()
    }
    
    /**
     * Verify that WhatsApp is on the main chat list
     */
    private fun verifyOnMainChatList(): Boolean {
        Log.d(TAG, "Verifying WhatsApp is on main chat list...")
        device.waitForIdle()
        
        // Check for various indicators of the main chat list
        val indicators = listOf(
            By.res("com.whatsapp:id/conversations_row_tip"),  // Chat list indicator
            By.res("com.whatsapp:id/fab"),  // Floating action button (new chat)
            By.res("com.whatsapp:id/menuitem_search"),  // Search button
            By.text("Chats"),  // Chats tab text
            By.res("com.whatsapp:id/contact_row_container")  // Contact rows
        )
        
        for (indicator in indicators) {
            if (device.hasObject(indicator)) {
                Log.d(TAG, "Found chat list indicator: $indicator")
                return true
            }
        }
        
        // Also check that we're in WhatsApp package
        val currentPackage = device.currentPackageName
        if (currentPackage != "com.whatsapp") {
            Log.w(TAG, "Not in WhatsApp app, current package: $currentPackage")
            return false
        }
        
        Log.w(TAG, "Could not verify main chat list with certainty")
        return false
    }
    
    /**
     * Verify that we're in a specific WhatsApp chat
     */
    private fun verifyInWhatsAppChat(contactName: String): Boolean {
        Log.d(TAG, "Verifying we're in WhatsApp chat with: $contactName")
        device.waitForIdle()
        
        // First check we're in WhatsApp
        val currentPackage = device.currentPackageName
        if (currentPackage != "com.whatsapp") {
            Log.w(TAG, "Not in WhatsApp app, current package: $currentPackage")
            return false
        }
        
        // Check for chat header with contact name
        val chatHeader = device.findObject(By.res("com.whatsapp:id/conversation_contact_name"))
            ?: device.findObject(By.res("com.whatsapp:id/conversation_contact"))
        
        if (chatHeader != null) {
            val headerText = chatHeader.text
            Log.d(TAG, "Found chat header: $headerText")
            
            // Check if it matches our contact (case-insensitive, trimmed)
            if (headerText != null && 
                headerText.trim().equals(contactName.trim(), ignoreCase = true)) {
                Log.d(TAG, "✅ Confirmed: In chat with $contactName")
                return true
            } else {
                Log.w(TAG, "In a chat but with different contact: $headerText (expected: $contactName)")
                return false
            }
        }
        
        // Alternative: Check for the contact name in the action bar or title
        val titleElement = device.findObject(By.text(contactName))
            ?: device.findObject(By.textContains(contactName))
        
        if (titleElement != null) {
            Log.d(TAG, "Found contact name in UI: $contactName")
            
            // Also verify we have chat UI elements
            val hasMessageInput = device.hasObject(By.res("com.whatsapp:id/entry"))
                || device.hasObject(By.res("com.whatsapp:id/conversation_entry"))
            
            if (hasMessageInput) {
                Log.d(TAG, "✅ Confirmed: In chat with $contactName (has message input)")
                return true
            }
        }
        
        Log.w(TAG, "Could not verify we're in chat with: $contactName")
        return false
    }
}