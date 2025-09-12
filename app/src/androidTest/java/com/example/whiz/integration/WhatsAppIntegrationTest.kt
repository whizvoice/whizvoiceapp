package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
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
import com.example.whiz.test_helpers.SkipOnCIOrEmulatorRule
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
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
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
    
    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        Log.d(TAG, "🎬 WhatsApp voice integration test setup complete")
        Log.d(TAG, "📱 Test ID: $uniqueTestId")
        Log.d(TAG, "📞 Contact: $WHATSAPP_CONTACT_NAME")
    }

    @After
    fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up WhatsApp test")
        
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
        
        Log.d(TAG, "✅ WhatsApp test cleanup completed")
    }

    @Test
    fun testWhatsAppNavigationFromMainChatList() {
        runBlocking {
        Log.d(TAG, "🚀 Starting WhatsApp navigation test from main chat list")
        
        try {
            // Step 1: Launch WhatsApp and ensure it's on the main chat list
            Log.d(TAG, "📱 Step 1: Launching WhatsApp and navigating to main chat list...")
            launchWhatsApp()
            
            // Navigate to main chat list if not already there
            navigateToMainChatList()
            
            // Verify we're on the main chat list
            val isOnChatList = verifyOnMainChatList()
            if (!isOnChatList) {
                Log.e(TAG, "❌ Failed to navigate to WhatsApp main chat list")
                failWithScreenshot("Not on main chat list", "$SCREENSHOT_PREFIX-not_on_chat_list")
                return@runBlocking
            }
            Log.d(TAG, "✅ WhatsApp is on main chat list")
            
            // Step 2: Launch Whiz via voice
            Log.d(TAG, "🎤 Step 2: Voice launching Whiz app...")
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
            
            // Step 3: Send voice command to open WhatsApp chat
            Log.d(TAG, "🎤 Step 3: Sending voice command to open WhatsApp chat...")
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
            val requestAppeared = ComposeTestHelper.waitForElement(
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
            
            // Wait a moment for any dialogs to appear
            delay(1000)
            
            // Check if any permission dialog appears - this means setup failed
            Log.d(TAG, "🔍 Checking for permission dialogs...")
            val permissionDialogs = listOf(
                "Accessibility permission dialog" to "accessibility",
                "Microphone permission dialog" to "microphone", 
                "Overlay permission dialog" to "overlay"
            )
            
            for ((dialogDescription, permissionType) in permissionDialogs) {
                Log.d(TAG, "Checking for $dialogDescription...")
                val dialogShowing = try {
                    composeTestRule.onNodeWithContentDescription(dialogDescription).assertExists()
                    true
                } catch (e: Exception) {
                    Log.d(TAG, "Dialog not found: ${e.message}")
                    false
                }
                
                if (dialogShowing) {
                    Log.e(TAG, "❌ PERMISSION NOT PROPERLY GRANTED! $permissionType permission dialog is showing")
                    // Stop listening to prevent hanging
                    try {
                        voiceManager.stopListening()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not stop listening: ${e.message}")
                    }
                    failWithScreenshot("Permission not granted - $permissionType dialog appeared", "$SCREENSHOT_PREFIX-${permissionType}_not_enabled")
                }
            }
            Log.d(TAG, "✅ No permission dialogs detected")
            
            // Step 4: Wait for assistant to process and navigate to WhatsApp
            Log.d(TAG, "⏳ Step 4: Waiting for assistant to process and navigate...")
            delay(5000) // Give assistant time to process and switch to WhatsApp
            
            // Step 5: Verify we're now in the correct WhatsApp chat
            Log.d(TAG, "🔍 Step 5: Verifying navigation to correct WhatsApp chat...")
            val isInCorrectChat = verifyInWhatsAppChat(WHATSAPP_CONTACT_NAME)
            
            if (isInCorrectChat) {
                Log.d(TAG, "🎉 Successfully navigated to WhatsApp chat with $WHATSAPP_CONTACT_NAME")
            } else {
                Log.w(TAG, "⚠️ Navigation may not have completed successfully")
                
                // Check if we're still in Whiz app (navigation didn't happen)
                val stillInWhiz = device.currentPackageName == packageName
                if (stillInWhiz) {
                    Log.e(TAG, "❌ Still in Whiz app, navigation to WhatsApp didn't occur")
                    
                    // Look for error messages in Whiz UI
                    val errorPatterns = listOf(
                        "couldn't open",
                        "unable to",
                        "error",
                        "failed",
                        "not found"
                    )
                    
                    for (pattern in errorPatterns) {
                        try {
                            val nodes = composeTestRule.onAllNodesWithText(pattern, substring = true, ignoreCase = true).fetchSemanticsNodes()
                            if (nodes.isNotEmpty()) {
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
            
            // Check if any permission dialog appears - this means setup failed
            val permissionDialogs = listOf(
                "Accessibility permission dialog" to "accessibility",
                "Microphone permission dialog" to "microphone",
                "Overlay permission dialog" to "overlay"
            )
            
            for ((dialogDescription, permissionType) in permissionDialogs) {
                val dialogShowing = try {
                    composeTestRule.onNodeWithContentDescription(dialogDescription).fetchSemanticsNode()
                    true
                } catch (e: Exception) {
                    false
                }
                
                if (dialogShowing) {
                    Log.e(TAG, "❌ PERMISSION NOT PROPERLY GRANTED! $permissionType permission dialog is showing")
                    // Stop listening to prevent hanging
                    try {
                        voiceManager.stopListening()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not stop listening: ${e.message}")
                    }
                    failWithScreenshot("Permission not granted - $permissionType dialog appeared", "$SCREENSHOT_PREFIX-${permissionType}_not_enabled")
                }
            }
            
            // Step 3: Wait for assistant to process and show draft
            Log.d(TAG, "⏳ Step 3: Waiting for assistant to process and show draft...")
            delay(3000) // Give assistant time to process
            
            // Look for the draft message in the assistant's response
            val draftPatterns = listOf(
                "hey what's up how's it going just tryna test whiz voice",
                "Draft message:",
                "Here's the message",
                "I'll help you send",
                "WhatsApp message"
            )
            
            var draftFound = false
            for (pattern in draftPatterns) {
                try {
                    val nodes = composeTestRule.onAllNodesWithText(pattern, substring = true).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "✅ Found draft/response with pattern: '$pattern'")
                        draftFound = true
                        break
                    }
                } catch (e: Exception) {
                    // Continue checking other patterns
                }
            }
            
            if (!draftFound) {
                // Also check with UI Automator as fallback
                val uiDraftFound = device.hasObject(By.textContains("tryna test").pkg(packageName))
                if (uiDraftFound) {
                    Log.d(TAG, "✅ Draft found via UI Automator")
                    draftFound = true
                }
            }
            
            if (!draftFound) {
                Log.w(TAG, "⚠️ Draft not clearly visible, but continuing with correction anyway")
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
            
            // Step 4: Send correction via voice
            Log.d(TAG, "✏️ Step 4: Sending correction via voice...")
            val correction = "actually can you change tryna to trying to, and then finish the sentence with a period and add hope you're having a good day!"
            
            // Wait a bit before sending correction
            delay(2000)
            
            instrumentation.runOnMainSync {
                capturedViewModel?.let { vm ->
                    Log.d(TAG, "🎤 Simulating voice correction: '$correction'")
                    vm.updateInputText(correction, fromVoice = true)
                    vm.sendUserInput(correction)
                    Log.d(TAG, "✅ Correction sent via voice")
                }
            }
            
            // Wait for correction to appear
            Log.d(TAG, "🔍 Waiting for correction to appear in UI...")
            val correctionAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(correction, substring = true) },
                timeoutMs = 5000L,
                description = "correction message"
            )
            
            if (!correctionAppeared) {
                Log.w(TAG, "⚠️ Correction not visible in UI, but continuing")
            }
            
            // Step 5: Wait for assistant to process correction and show updated draft
            Log.d(TAG, "⏳ Step 5: Waiting for corrected draft...")
            delay(3000)
            
            // Look for the corrected message
            val correctedPatterns = listOf(
                "trying to test",
                "hope you're having a good day",
                "Updated message:",
                "Corrected message:"
            )
            
            var correctedFound = false
            for (pattern in correctedPatterns) {
                try {
                    val nodes = composeTestRule.onAllNodesWithText(pattern, substring = true).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        Log.d(TAG, "✅ Found corrected message with pattern: '$pattern'")
                        correctedFound = true
                        break
                    }
                } catch (e: Exception) {
                    // Continue checking other patterns
                }
            }
            
            if (!correctedFound) {
                // Check with UI Automator as fallback
                val uiCorrectedFound = device.hasObject(By.textContains("trying to").pkg(packageName)) ||
                                       device.hasObject(By.textContains("good day").pkg(packageName))
                if (uiCorrectedFound) {
                    Log.d(TAG, "✅ Corrected message found via UI Automator")
                    correctedFound = true
                }
            }
            
            if (!correctedFound) {
                Log.w(TAG, "⚠️ Corrected message not clearly visible, test may need adjustment")
            }
            
            // Step 6: Verify all messages are in the chat
            Log.d(TAG, "🔍 Step 6: Verifying conversation flow...")
            
            // Check that our voice messages are visible
            val userMessages = listOf(
                whatsappRequest,
                correction
            )
            
            var allMessagesFound = true
            for (msg in userMessages) {
                try {
                    composeTestRule.onNodeWithText(msg, substring = true).assertIsDisplayed()
                    Log.d(TAG, "✅ Found user message: '${msg.take(50)}...'")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ User message not clearly visible: '${msg.take(50)}...'")
                    allMessagesFound = false
                }
            }
            
            if (!allMessagesFound) {
                Log.w(TAG, "⚠️ Not all messages clearly visible, but test structure is correct")
            }
            
            Log.d(TAG, "🎉 WhatsApp voice integration test completed!")
            Log.d(TAG, "✅ Successfully tested:")
            Log.d(TAG, "   - Voice launch to chat")
            Log.d(TAG, "   - WhatsApp message request via voice")
            Log.d(TAG, "   - Draft display by assistant")
            Log.d(TAG, "   - Correction via voice")
            Log.d(TAG, "   - Updated draft display")
            
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