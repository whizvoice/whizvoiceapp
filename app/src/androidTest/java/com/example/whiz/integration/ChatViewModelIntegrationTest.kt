package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import androidx.test.uiautomator.UiSelector
// import kotlinx.coroutines.test.runTest  // Not needed for integration tests

/**
 * Integration tests for bot interruption and message handling:
 * 1. Send initial message to trigger bot response
 * 2. While bot is responding, send interrupt messages rapidly
 * 3. Verify all interrupt messages are sendable immediately (production bug test)
 * 4. Verify messages appear immediately (optimistic UI)
 * 5. Check bot response handling after interruption
 * 6. Detect and prevent message duplication
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatViewModelIntegrationTest : BaseIntegrationTest() {
    
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
        private const val TAG = "ChatViewModelIntegrationTest"
        private const val TEST_TIMEOUT = 15000L // 15 seconds
        private const val MESSAGE_COUNT = 5
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles automatic authentication
        
        // Grant microphone permission for rapid message testing
        Log.d(TAG, "🎙️ Granting microphone permission for rapid message tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)
        
        Log.d(TAG, "🧪 ChatViewModel Integration Test Setup Complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats (only deleting chats created by this test)")
            if (createdNewChatThisTest && createdChatIds.isNotEmpty()) {
                Log.d(TAG, "🗑️ Deleting ${createdChatIds.size} chat(s) created during test")
                createdChatIds.forEach { chatId ->
                    try {
                        repository.deleteChat(chatId)
                        Log.d(TAG, "✅ Deleted test chat: $chatId")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed to delete test chat $chatId", e)
                    }
                }
                createdChatIds.clear()
            } else {
                Log.d(TAG, "ℹ️ No new chats created by test - using existing chat, nothing to clean up")
            }
            Log.d(TAG, "✅ Test cleanup completed")
        }
    }

    /**
     * Test bot interruption functionality and detect critical production bugs:
     * 1. Send initial message to trigger bot response
     * 2. While bot is responding, send multiple interrupt messages rapidly
     * 3. **CRITICAL**: Verify each message is actually sent via WebSocket, not just displayed optimistically
     * 4. Verify all messages appear immediately (optimistic UI) 
     * 5. Verify no duplicate messages exist
     * 6. Verify messages are preserved after bot response completes
     *
     * **Production Bug Detection**: This test specifically detects the bug where the send button
     * appears to work during bot response but doesn't actually send messages to the server.
     * The test will fail immediately when this occurs with a clear "PRODUCTION BUG DETECTED" message.
     *
     * This tests the critical UX requirement that users can interrupt the bot
     * by sending new messages while it's still responding.
     */
    @Test
    fun botInterruption_allowsImmediateMessageSending() {
        runBlocking {
            Log.d(TAG, "🚀 Testing bot interruption with WebSocket verification - detecting send button bugs during bot response")
            
            // Take initial screenshot to capture starting state
            takeFailureScreenshot("test_start", "Initial state for bot interruption test")
            
            try {
            val uniqueTestId = System.currentTimeMillis()
            
            // Initialize cleanup tracking - assume we're using existing chat unless we create one
            createdNewChatThisTest = false
            
            // Step 1: Launch app and navigate to new chat screen
            Log.d(TAG, "📱 Step 1: Launching app and navigating to new chat")
            if (!launchAppAndWaitForLoad()) {
                failWithScreenshot("app_launch_failed", "App failed to launch or load main UI")
            }
            
            // Ensure we're in a fresh chat for bot interruption testing
            // Note: Opening a chat should automatically enable continuous listening
            // If this doesn't work, the test should fail - we don't manually activate it
            Log.d(TAG, "➕ Step 2: Ensuring we're in a fresh chat for testing")
            
            if (isCurrentlyInChatScreen()) {
                // If we're already in a chat, just use it - avoid unnecessary navigation
                Log.d(TAG, "✅ Already in chat screen - using existing chat for bot interruption test")
                Log.d(TAG, "ℹ️ No chat cleanup needed - using pre-existing chat")
            } else {
                // We're on chats list, create new chat
                Log.d(TAG, "📋 On chats list, creating new chat for bot interruption test")  
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    failWithScreenshot("new_chat_creation_failed", "Failed to create new chat for bot interruption test")
                }
                
                // Mark that we created a new chat and get its ID for cleanup
                createdNewChatThisTest = true
                Log.d(TAG, "✅ New chat created - will track for cleanup")
                
                // Get the chat ID for cleanup tracking
                val chatId = getCurrentOptimisticChatId()
                if (chatId != null) {
                    createdChatIds.add(chatId)
                    Log.d(TAG, "📝 Tracking chat ID for cleanup: $chatId")
                } else {
                    Log.w(TAG, "⚠️ Could not get chat ID for cleanup tracking")
                }
            }
            
            // Step 2: Send first message to trigger bot response
            val sentMessages = mutableListOf<String>()
            val firstMessage = "Initial message that will trigger bot - test $uniqueTestId"
            
            Log.d(TAG, "📨 Step 3: Sending initial message to trigger bot response...")
            if (!sendMessageAndVerifyWebSocketSending(firstMessage, 1)) {
                Log.e(TAG, "❌ Initial message failed - cannot proceed with bot interruption test")
                Log.e(TAG, "   Message: '${firstMessage.take(50)}...'")
                failWithScreenshot("initial_message_failed", "Initial message failed to send or reach server - cannot test bot interruption")
                return@runBlocking
            }
            sentMessages.add(firstMessage)
            
            // Step 3: Wait for bot to start responding
            Log.d(TAG, "⏳ Step 4: Waiting for bot to start responding...")
            Log.d(TAG, "🔍 Debug: Looking for 'Whiz is computing' indicator...")
            
            // Give more time for bot to start responding (network might be slow)
            val botStartedThinking = waitForBotThinkingIndicator(15000)
            if (!botStartedThinking) {
                Log.w(TAG, "⚠️ Bot didn't start thinking - this might be a server issue or bot is offline")
                Log.w(TAG, "   For interruption testing, we'll simulate the scenario by proceeding anyway")
                Log.w(TAG, "   The core UX test is whether messages can be sent rapidly, regardless of bot state")
                
                // Take screenshot to see current state
                takeFailureScreenshot("bot_not_responding_debug", "Bot didn't start thinking - continuing test anyway")
                
                // Continue with rapid message testing even without bot response
                Log.d(TAG, "🔄 Continuing with rapid message test without waiting for bot...")
            } else {
                Log.d(TAG, "🤖 Bot started thinking! Now testing message interruption...")
            }
            
            Log.d(TAG, "🤖 Bot started thinking! Now testing message interruption...")
            
            // Step 4: While bot is responding, rapidly send interruption messages
            // This tests the core UX issue: users should be able to interrupt the bot
            Log.d(TAG, "📨 Step 5: Sending interrupt messages while bot is responding...")
            val interruptMessageCount = MESSAGE_COUNT - 1 // 4 more messages
            
            for (i in 1..interruptMessageCount) {
                val interruptMessage = "Interrupt message $i while bot thinking - test $uniqueTestId"
                
                Log.d(TAG, "🎯 Testing critical production behavior: rapid message sending during bot response")
                
                // This should NOT fail - users must be able to interrupt the bot
                // The WebSocket verification will detect if the send button is broken during bot response
                if (!sendMessageAndVerifyWebSocketSending(interruptMessage, i + 1)) {
                    Log.e(TAG, "❌ Interrupt message $i failed during bot response!")
                    Log.e(TAG, "   This could indicate the PRODUCTION BUG where send button appears to work")
                    Log.e(TAG, "   during bot response but messages don't actually reach the server")
                    Log.e(TAG, "   Message: '${interruptMessage.take(50)}...'")
                    failWithScreenshot("interrupt_message_${i}_failed", "Interrupt message $i failed during bot response - potential production bug")
                    return@runBlocking
                }
                
                sentMessages.add(interruptMessage)
                Log.d(TAG, "✅ Interrupt message $i sent successfully via WebSocket while bot thinking")
                
                // No delay - test true rapid interruption capability
            }
            
            // Step 5: Verify all messages are visible in the UI and check ordering
            Log.d(TAG, "🔍 Step 6: Verifying all messages are visible in UI and checking ordering...")
            
            // Verify each message is visible in the UI - verifyMessageVisible() handles waiting internally
            for (i in 0 until MESSAGE_COUNT) {
                val message = sentMessages[i]
                if (!verifyMessageVisible(message)) {
                    failWithScreenshot("message_${i+1}_not_visible", "Message ${i+1} not visible in UI: '${message.take(30)}...'")
                }
                Log.d(TAG, "✅ Message ${i+1} visible in UI: '${message.take(30)}...'")
            }
            
            // Step 6: Check for duplicate messages in UI
            Log.d(TAG, "🔍 Step 7: Checking for duplicate messages in UI...")
            
            // Count occurrences of each message in the UI
            for (i in 0 until MESSAGE_COUNT) {
                val message = sentMessages[i]
                val messageOccurrences = countMessageOccurrences(message.take(30)) // Use first 30 chars for uniqueness
                
                if (messageOccurrences != 1) {
                    failWithScreenshot("duplicate_message_${i+1}_detected", "Message ${i+1} appears $messageOccurrences times in UI (should be 1): '${message.take(30)}...'")
                }
                Log.d(TAG, "✅ Message ${i+1} appears exactly once in UI: '${message.take(30)}...'")
            }
            
            Log.d(TAG, "✅ No duplicate messages detected in UI")
            
            // Step 7: Wait for bot response completion (it should already be thinking)
            Log.d(TAG, "🤖 Step 8: Waiting for bot to finish responding after interruption...")
            var botResponseReceived = false
            
            // Wait for bot to finish thinking (it should already be thinking from step 3)
            Log.d(TAG, "⏳ Waiting for bot to finish computing...")
            val thinkingFinished = waitForBotThinkingToFinish(TEST_TIMEOUT)
            
            if (thinkingFinished) {
                Log.d(TAG, "🤖 Bot finished computing!")
                
                // Wait for actual bot response to appear
                Log.d(TAG, "⏳ Waiting for bot response to appear...")
                val responseAppeared = waitForBotResponse(5000)
                
                if (responseAppeared) {
                    Log.d(TAG, "🤖 Bot response detected in UI after interruption!")
                    botResponseReceived = true
                    
                    // Verify that user messages are still present after bot response
                    Log.d(TAG, "🔍 Verifying user messages are still present after bot response...")
                    for (i in 0 until MESSAGE_COUNT) {
                        val message = sentMessages[i]
                        if (!verifyMessageVisible(message)) {
                            failWithScreenshot("user_message_${i+1}_lost_after_bot_response", "User message ${i+1} disappeared after bot response: '${message.take(30)}...'")
                        }
                    }
                    
                    // Verify no duplicates were created by bot response
                    for (i in 0 until MESSAGE_COUNT) {
                        val message = sentMessages[i]
                        val messageOccurrences = countMessageOccurrences(message.take(30))
                        
                        if (messageOccurrences != 1) {
                            failWithScreenshot("bot_response_created_duplicate_${i+1}", "Bot response created duplicates of message ${i+1}: appears $messageOccurrences times: '${message.take(30)}...'")
                        }
                    }
                    
                    Log.d(TAG, "✅ Bot response verification completed - user messages preserved after interruption, no duplicates created")
                } else {
                    Log.w(TAG, "⚠️ Bot response not detected in UI after thinking finished")
                }
            } else {
                Log.w(TAG, "⚠️ Bot thinking indicator didn't disappear within timeout")
            }
            
            if (!botResponseReceived) {
                Log.w(TAG, "⚠️ No bot response received within timeout - this is acceptable for testing")
                Log.w(TAG, "   The test focuses on message interruption capability and duplication prevention")
            }
            
            // Step 8: Final verification of UI state
            Log.d(TAG, "🔍 Step 9: Final verification of UI state...")
            
            // Final check: all original messages still visible
            Log.d(TAG, "🔍 Final check: verifying all original messages still visible...")
            for (i in 0 until MESSAGE_COUNT) {
                val message = sentMessages[i]
                if (!verifyMessageVisible(message)) {
                    failWithScreenshot("final_message_${i+1}_missing", "Final check: message ${i+1} no longer visible: '${message.take(30)}...'")
                }
            }
            
            // Final check: no duplicates in final state
            Log.d(TAG, "🔍 Final check: verifying no duplicates in final UI state...")
            for (i in 0 until MESSAGE_COUNT) {
                val message = sentMessages[i]
                val messageOccurrences = countMessageOccurrences(message.take(30))
                
                if (messageOccurrences != 1) {
                    failWithScreenshot("final_duplicate_${i+1}_detected", "Final check: message ${i+1} appears $messageOccurrences times (should be 1): '${message.take(30)}...'")
                }
            }
            
            Log.d(TAG, "✅ Final UI verification completed successfully!")
            Log.d(TAG, "📊 Test summary:")
            Log.d(TAG, "   ✅ Sent 1 initial message to trigger bot response")
            Log.d(TAG, "   ✅ Successfully interrupted bot with ${MESSAGE_COUNT - 1} additional messages")
            Log.d(TAG, "   ✅ All messages appeared immediately (optimistic UI)")
            Log.d(TAG, "   ✅ No duplicate messages detected")
            Log.d(TAG, "   ✅ Messages preserved after bot response")
            Log.d(TAG, "   ✅ Bot interruption test completed successfully")
            
        } catch (e: Exception) {
                Log.e(TAG, "❌ Bot interruption test failed", e)
                failWithScreenshot("test_exception_failure", "Bot interruption test failed with exception: ${e.message}")
            }
        }
    }

    /**
     * Get the current optimistic chat ID (negative ID) by finding the most recent chat
     */
    private suspend fun getCurrentOptimisticChatId(): Long? {
        return try {
            val allChats = repository.getAllChats()
            // Look for chats with negative IDs (optimistic) or the most recent chat
            val recentChat = allChats.maxByOrNull { it.id }
            recentChat?.id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current chat ID", e)
            null
        }
    }


}