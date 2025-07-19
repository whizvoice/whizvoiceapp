package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    companion object {
        private const val TAG = "ChatViewModelComposeTest"
        private const val TEST_TIMEOUT = 15000L // 15 seconds
        private const val MESSAGE_COUNT = 5
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Grant microphone permission for rapid message testing
        Log.d(TAG, "🎙️ Granting microphone permission for rapid message tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)
        
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
                    additionalPatterns = listOf("interrupt", "bot", "PRODUCTION BUG"),
                    enablePatternFallback = false
                )
                createdChatIds.clear()
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
                        
            try {
                val uniqueTestId = System.currentTimeMillis()
                
                // Initialize cleanup tracking
                createdNewChatThisTest = true
                
                // Step 1: Launch app using ComposeTestHelper
                Log.d(TAG, "📱 Step 1: Launching app with ComposeTestHelper")
                if (!ComposeTestHelper.launchApp()) {
                    failWithScreenshot("app_launch_failed", "App failed to launch via ComposeTestHelper")
                    return@runBlocking
                }
                
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
                val firstMessage = "Keep all responses to 1 word. Name of coffee-making professional? - test $uniqueTestId"
                
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
                if (!ComposeTestHelper.sendMessage(
                    composeTestRule, 
                    firstMessage,
                    onFailure = { failureType, reason ->
                        Log.e(TAG, "❌ Initial message failed - cannot proceed with bot interruption test")
                        failWithScreenshot("initial_message_${failureType}", reason)
                    }
                )) {
                    return@runBlocking
                }
                sentMessages.add(firstMessage)
                Log.d(TAG, "✅ Initial message sent successfully with Compose Testing")
                
                // Step 4: Send rapid interruption messages using Compose Testing
                Log.d(TAG, "📨 Step 4: Sending interrupt messages RAPIDLY while bot is responding...")
                val interruptMessageCount = MESSAGE_COUNT - 1
                
                for (i in 1..interruptMessageCount) {
                    val interruptMessage = "hi $i"
                    
                    Log.d(TAG, "🚀 RAPID MESSAGE $i: Testing rapid send during bot response...")
                    
                    if (!ComposeTestHelper.sendMessage(
                        composeTestRule, 
                        interruptMessage, 
                        rapid = true,
                        onFailure = { failureType, reason ->
                            Log.e(TAG, "❌ RAPID: Message $i failed during rapid send!")
                            Log.e(TAG, "   🚨 PRODUCTION BUG DETECTED: Cannot send messages rapidly during bot response")
                            failWithScreenshot("rapid_message_${i}_${failureType}", reason)
                        }
                    )) {
                        return@runBlocking
                    }
                    
                    Log.d(TAG, "✅ RAPID MESSAGE $i sent successfully")
                    sentMessages.add(interruptMessage)
                }
                
                Log.d(TAG, "🚀 RAPID PHASE COMPLETE: All ${interruptMessageCount} interrupt messages sent rapidly!")
                
                // Step 5: Verify all messages exist using Compose Testing
                Log.d(TAG, "🔍 Step 5: Verifying all messages exist using Compose Testing...")
                
                val missingMessages = ComposeTestHelper.verifyAllMessagesExist(composeTestRule, sentMessages)
                if (missingMessages.isNotEmpty()) {
                    Log.e(TAG, "❌ Missing messages detected:")
                    missingMessages.forEachIndexed { index, message ->
                        Log.e(TAG, "   Missing ${index + 1}: '${message.take(30)}...'")
                    }
                    failWithScreenshot("messages_missing_from_chat", "Missing ${missingMessages.size} messages from chat: ${missingMessages.map { message -> message.take(20) }}")
                }
                
                Log.d(TAG, "✅ All ${sentMessages.size} messages verified to exist in chat")
                
                // Step 6: Check for duplicates using Compose Testing
                Log.d(TAG, "🔍 Step 6: Checking for duplicate messages...")
                if (!ComposeTestHelper.noDuplicates(composeTestRule, sentMessages)) {
                    failWithScreenshot("chat_duplicates_detected", "Found duplicate message(s) in chat - indicates production bug")
                }
                
                Log.d(TAG, "✅ Duplicate checking completed")
                
                Log.d(TAG, "📊 Test summary:")
                Log.d(TAG, "   ✅ Sent 1 initial message to trigger bot response")
                Log.d(TAG, "   ✅ Successfully interrupted bot with ${MESSAGE_COUNT - 1} additional messages")
                Log.d(TAG, "   ✅ All messages appeared immediately (optimistic UI)")
                Log.d(TAG, "   ✅ No duplicate messages detected")
                Log.d(TAG, "   ✅ Bot interruption test completed successfully with Compose Testing")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Bot interruption test failed", e)
                failWithScreenshot("test_exception_failure", "Bot interruption test failed with exception: ${e.message}")
            }
        }
    }
} 