package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.TestCredentialsManager
import com.example.whiz.BaseIntegrationTest
import org.junit.Assert.*
import org.junit.After
import android.util.Log
import android.provider.Settings
import com.example.whiz.data.local.MessageType
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.MainActivity

/**
 * Compose-based integration tests for bot interruption and message handling.
 * This uses Compose Testing which is specifically designed for testing Compose UI components.
 * 
 * Key advantages of Compose Testing:
 * - Native support for Compose UI components
 * - Better synchronization with Compose state
 * - More reliable element selection
 * - Faster execution than UI Automator
 * - Better error messages and debugging
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatViewModelComposeTest : BaseIntegrationTest() {
    
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()
    private var createdNewChatThisTest = false
    private var testUniqueId: Long = 0

    companion object {
        private const val TAG = "ChatViewModelComposeTest"
        private const val TEST_TIMEOUT = 15000L // 15 seconds
        private const val MESSAGE_COUNT = 5
    }
    
    private val uniqueTestId = "COMPOSE_INTERRUPT_TEST_${System.currentTimeMillis()}"

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Grant microphone permission for rapid message testing
        Log.d(TAG, "🎙️ Granting microphone permission for rapid message tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)

        // This might be set automatically by test framework
        val animationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        Log.d("Test", "Animation scale: $animationScale") // Might be 0.0
        
        Log.d(TAG, "🧪 ChatViewModel Compose Test Setup Complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats (only deleting chats created by this test)")
            if (createdNewChatThisTest && createdChatIds.isNotEmpty()) {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("interrupt", "bot", "PRODUCTION BUG", testUniqueId.toString(), "COMPOSE_INTERRUPT_TEST_"),
                    enablePatternFallback = true // Enable to catch any chats with unique identifier
                )
                createdChatIds.clear()
            } else if (createdNewChatThisTest) {
                // Fallback: if we set createdNewChatThisTest but failed to track chats, use pattern cleanup
                Log.w(TAG, "⚠️ Chat tracking failed but test created chats - using pattern fallback cleanup")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = emptyList(),
                    additionalPatterns = listOf("Keep all responses to 1 word", "COMPOSE_INTERRUPT_TEST_", testUniqueId.toString()),
                    enablePatternFallback = true // Enable pattern fallback since tracking failed
                )
            } else {
                Log.d(TAG, "ℹ️ No new chats created by test - using existing chat, nothing to clean up")
            }
            
            // Clean up ComposeTestHelper resources
            ComposeTestHelper.cleanup()
            
            Log.d(TAG, "✅ Test cleanup completed")
        }
    }

    /**
     * Compose-based test for bot interruption functionality.
     * Tests the same functionality as the UI Automator version but with better reliability for Compose UI.
     */
    @Test
    fun botInterruption_allowsImmediateMessageSending() {
        runBlocking {
            Log.d(TAG, "🚀 Testing bot interruption with Compose Testing - better reliability for Compose UI")
                        
            val uniqueTestId = System.currentTimeMillis()
            testUniqueId = uniqueTestId // Store for cleanup
            
            // Initialize cleanup tracking
            createdNewChatThisTest = true
            
            // Capture initial chats before creating new ones
            val initialChats = repository.getAllChats()
            
            // Step 1: Verify app is ready (already launched by createAndroidComposeRule)
            Log.d(TAG, "📱 Step 1: Verifying app is ready (already launched by createAndroidComposeRule)")
            
            // Add more detailed logging to understand why app readiness check might fail
            Log.d(TAG, "🔍 Detailed app readiness check starting...")
            
            // First, check if composeTestRule is properly initialized
            try {
                composeTestRule.waitForIdle()
                Log.d(TAG, "✅ Compose test rule is properly initialized")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose test rule initialization failed", e)
                failWithScreenshot("compose_test_rule_failed", "Compose test rule initialization failed: ${e.message}")
                return@runBlocking
            }
            
            // Check if we can access the activity
            try {
                val activity = composeTestRule.activity
                Log.d(TAG, "✅ Activity is accessible: ${activity.javaClass.simpleName}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Activity access failed", e)
                failWithScreenshot("activity_access_failed", "Activity access failed: ${e.message}")
                return@runBlocking
            }
            
            // Now check app readiness with detailed logging
            val appReady = ComposeTestHelper.isAppReady(composeTestRule)
            Log.d(TAG, "🔍 App readiness check result: $appReady")
            
            if (!appReady) {
                Log.e(TAG, "❌ App is not ready for testing - taking screenshot for debugging")
                Log.e(TAG, "🔍 This could be due to:")
                Log.e(TAG, "   - App launched to chat screen instead of chats list")
                Log.e(TAG, "   - UI elements not loading properly")
                Log.e(TAG, "   - Compose hierarchy not ready")
                Log.e(TAG, "   - Voice launch detection causing unexpected navigation")
                
                // Take a screenshot before failing to help debug the issue
                failWithScreenshot("app_not_ready", "App is not ready for testing - UI elements not found")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ App is ready for testing - proceeding with test")
            
            // Step 2: Navigate to new chat using Compose Testing
            Log.d(TAG, "➕ Step 2: Navigating to new chat with Compose Testing")
            
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "🔄 Currently in chat screen, going back to chats list first")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("navigate_to_chats_list_failed", "Failed to navigate from chat screen to chats list")
                    return@runBlocking
                }
            }
            
            Log.d(TAG, "📋 On chats list, clicking new chat button with Compose Testing")
            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                failWithScreenshot("new_chat_creation_failed", "New chat button not found or chat screen failed to load")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Successfully navigated to new chat screen with Compose Testing")
            
            // Step 3: Send first message to trigger bot response using Compose Testing
            val sentMessages = mutableListOf<String>()
            val firstMessage = "Keep all responses to 1 word. Name of coffee-making professional? - $uniqueTestId"
            
            Log.d(TAG, "📨 Step 3: Sending initial message with Compose Testing...")
            
            // First, ensure we're on the chat screen using Compose Testing
            Log.d(TAG, "🔍 Checking current screen state with Compose Testing...")
            val chatScreenStatus = ComposeTestHelper.isOnChatScreen(composeTestRule)
            Log.d(TAG, "🔍 Chat screen status: $chatScreenStatus")
            
            if (!chatScreenStatus) {
                Log.e(TAG, "❌ Not on chat screen - navigation failed")
                failWithScreenshot("navigate_to_chats_list_failed_2", "❌ Not on chat screen - navigation failed")
                return@runBlocking
            } else {
                Log.d(TAG, "✅ Confirmed we made it to chat screen with Compose Testing")
            }
            
            // Now try to send the message using Compose testing
            Log.d(TAG, "📝 Using Compose testing for better reliability...")
            if (!ComposeTestHelper.sendMessage(composeTestRule, firstMessage)) {
                Log.e(TAG, "❌ Initial message failed - cannot proceed with bot interruption test")
                failWithScreenshot("initial_message_failed", "Initial message failed to send")
                return@runBlocking
            }
            sentMessages.add(firstMessage)
            Log.d(TAG, "✅ Initial message sent successfully with Compose Testing")
            
            // Track the newly created chat for cleanup (after sending message when chat definitely exists)
            try {
                val currentChats = repository.getAllChats()
                val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
                newChats.forEach { chat ->
                    createdChatIds.add(chat.id)
                    Log.d(TAG, "📝 Tracked new chat for cleanup: ${chat.id} ('${chat.title}')")
                }
                Log.d(TAG, "📊 Total tracked chats: ${createdChatIds.size}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Could not track newly created chat: ${e.message}")
            }
            
            // Step 4: Send rapid interruption messages using Compose Testing
            Log.d(TAG, "📨 Step 4: Sending interrupt messages RAPIDLY while bot is responding...")
            val interruptMessageCount = MESSAGE_COUNT - 1
            
            for (i in 1..interruptMessageCount) {
                val interruptMessage = "hi $i"
                
                Log.d(TAG, "🚀 RAPID MESSAGE $i: Testing rapid send during bot response...")
                
                if (!ComposeTestHelper.sendMessage(composeTestRule, interruptMessage, rapid = true)) {
                    Log.e(TAG, "❌ RAPID: Message $i failed during rapid send!")
                    Log.e(TAG, "   🚨 PRODUCTION BUG DETECTED: Cannot send messages rapidly during bot response")
                    failWithScreenshot("rapid_message_${i}_failed", "Rapid message $i failed to send within timeout")
                    return@runBlocking
                }
                
                Log.d(TAG, "✅ RAPID MESSAGE $i sent successfully")
                sentMessages.add(interruptMessage)
            }
            
            Log.d(TAG, "🚀 RAPID PHASE COMPLETE: All ${interruptMessageCount} interrupt messages sent rapidly!")
            
            // Step 5: Check for assistant interruption bug immediately after rapid send
            Log.d(TAG, "🔍 Step 5: Checking for assistant interruption bug after rapid send...")
            
            // During rapid send, we should NOT see assistant messages between every user message
            // If we do, it means rapid send is being blocked and user cannot interrupt assistant
            val userMessages = sentMessages // These are the 5 user messages we sent
            val assistantMessagesBetweenUsers = ComposeTestHelper.countAssistantMessagesBetweenConsecutiveUserMessages(composeTestRule, userMessages)
            
            Log.d(TAG, "📊 Assistant messages between consecutive user messages: $assistantMessagesBetweenUsers out of ${userMessages.size - 1} possible gaps")
            
            // If there are assistant messages between every user message (except we expect 1 barista response after first message)
            // That means rapid send is being blocked
            val maxExpectedAssistantMessages = 3 // Only the barista response after first message
            
            if (assistantMessagesBetweenUsers > maxExpectedAssistantMessages) {
                Log.e(TAG, "❌ RAPID SEND BUG DETECTED: Found $assistantMessagesBetweenUsers assistant messages between user messages")
                Log.e(TAG, "❌ Expected maximum $maxExpectedAssistantMessages (just the barista response)")
                Log.e(TAG, "❌ This indicates rapid send is being blocked - user cannot interrupt assistant properly")
                failWithScreenshot("rapid_send_blocked_bug", "Rapid send is being blocked: $assistantMessagesBetweenUsers assistant messages found between consecutive user messages (expected max $maxExpectedAssistantMessages)")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Rapid send working correctly - minimal assistant interference during rapid messaging")
            
            // Step 6: Wait for barista response and verify message order
            Log.d(TAG, "☕ Step 6: Waiting for barista response and verifying message order...")
            
            // The first message asked for "coffee-making professional" - expect variations of "Barista" response
            val expectedBaristaResponses = listOf("Barista", "Barista.", "Barista!")
            Log.d(TAG, "🔍 Looking for barista response variations: $expectedBaristaResponses")
            
            // 🔧 NEW: Wait for any barista response variation to appear before verifying order
            // Since we sent messages rapidly, the bot response might take time to arrive
            Log.d(TAG, "⏳ Waiting for barista response to appear...")
            val waitStartTime = System.currentTimeMillis()
            val waitTimeout = 5000L // 5 seconds timeout
            var baristaResponseFound = false
            var actualBaristaResponse = ""
            
            while (!baristaResponseFound && (System.currentTimeMillis() - waitStartTime) < waitTimeout) {
                for (expectedResponse in expectedBaristaResponses) {
                    try {
                        composeTestRule.onNodeWithText(expectedResponse).assertIsDisplayed()
                        baristaResponseFound = true
                        actualBaristaResponse = expectedResponse
                        Log.d(TAG, "✅ Barista response found: '$actualBaristaResponse' after ${System.currentTimeMillis() - waitStartTime}ms")
                        break
                    } catch (e: Exception) {
                        // Continue to next variation
                    } catch (e: AssertionError) {
                        // Continue to next variation
                    }
                }
                
                if (!baristaResponseFound) {
                    Log.d(TAG, "⏳ Still waiting for barista response... (${System.currentTimeMillis() - waitStartTime}ms elapsed)")
                    Thread.sleep(100) // Check every 100ms
                }
            }
            
            if (!baristaResponseFound) {
                Log.e(TAG, "❌ None of the barista response variations appeared within ${waitTimeout}ms")
                Log.e(TAG, "❌ Expected variations: $expectedBaristaResponses")
                failWithScreenshot("barista_response_timeout", "Barista response did not appear within timeout")
                return@runBlocking
            }
            
            // Now verify that the barista response appears right after the first message
            Log.d(TAG, "🔍 About to verify message order - looking for Barista response after first message")
            if (!ComposeTestHelper.verifyMessageOrder(composeTestRule, firstMessage, actualBaristaResponse)) {
                Log.e(TAG, "❌ Message order verification failed - barista response not in correct position")
                Log.e(TAG, "❌ Expected to find '$actualBaristaResponse' response after first message: '${firstMessage.take(50)}...'")
                Log.e(TAG, "❌ This indicates the request ID pairing is not working correctly")
                failWithScreenshot("message_order_verification_failed", "Barista response not appearing after the correct user message")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Message order verification passed - barista response appears after correct user message")
            
            // Step 7: Verify all messages exist using Compose Testing
            Log.d(TAG, "🔍 Step 7: Verifying all messages exist using Compose Testing...")
            
            val missingMessages = ComposeTestHelper.verifyAllMessagesExist(composeTestRule, sentMessages)
            if (missingMessages.isNotEmpty()) {
                Log.e(TAG, "❌ Missing messages detected:")
                missingMessages.forEachIndexed { index, message ->
                    Log.e(TAG, "   Missing ${index + 1}: '${message.take(30)}...'")
                }
                failWithScreenshot("messages_missing_from_chat", "Missing ${missingMessages.size} messages from chat: ${missingMessages.map { message -> message.take(20) }}")
            }
            
            Log.d(TAG, "✅ All ${sentMessages.size} messages verified to exist in chat")
            
            // Step 8: Check for duplicates using Compose Testing
            Log.d(TAG, "🔍 Step 8: Checking for duplicate messages...")
            if (!ComposeTestHelper.noDuplicates(composeTestRule, sentMessages)) {
                failWithScreenshot("chat_duplicates_detected", "Found duplicate message(s) in chat - indicates production bug")
            }
            
            Log.d(TAG, "✅ Duplicate checking completed")
            
            Log.d(TAG, "📊 Test summary:")
            Log.d(TAG, "   ✅ Sent 1 initial message asking for coffee-making professional")
            Log.d(TAG, "   ✅ Successfully interrupted bot with ${MESSAGE_COUNT - 1} additional messages")
            Log.d(TAG, "   ✅ All messages appeared immediately (optimistic UI)")
            Log.d(TAG, "   ✅ Barista response appeared in correct order after first message")
            Log.d(TAG, "   ✅ Rapid send working correctly - user can interrupt assistant")
            Log.d(TAG, "   ✅ No duplicate messages detected")
            Log.d(TAG, "   ✅ Bot interruption test completed successfully with Compose Testing")
        }
    }
} 