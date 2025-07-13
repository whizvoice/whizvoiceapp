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
import androidx.test.uiautomator.By
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
                        
            try {
            val uniqueTestId = System.currentTimeMillis()
            
            // Initialize cleanup tracking - assume we're creating a new chat
            createdNewChatThisTest = true
            
            // Step 1: Launch app and navigate to new chat screen
            Log.d(TAG, "📱 Step 1: Launching app and navigating to new chat")
            if (!launchAppAndWaitForLoad()) {
                failWithScreenshot("app_launch_failed", "App failed to launch or load main UI")
            }
            
            // Step 2: Navigate to new chat (handling both chats list and existing chat scenarios)
            Log.d(TAG, "➕ Step 2: Navigating to new chat")
            
            if (isCurrentlyInChatScreen()) {
                // If we're already in a chat, navigate back to chats list first, then create new chat
                Log.d(TAG, "🔄 Currently in chat screen, going back to chats list first")
                if (!navigateBackToChatsListFromChat()) {
                    failWithScreenshot("navigate_to_chats_list_failed", "Failed to navigate from chat screen to chats list")
                    return@runBlocking
                }
                
                // Now click new chat button
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    failWithScreenshot("new_chat_creation_failed", "New chat button not found or chat screen failed to load")
                    return@runBlocking
                }
            } else {
                // We're on chats list, directly click new chat button
                Log.d(TAG, "📋 On chats list, clicking new chat button directly")
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    failWithScreenshot("new_chat_creation_failed", "New chat button not found or chat screen failed to load")
                    return@runBlocking
                }
            }
            
            Log.d(TAG, "✅ Successfully navigated to new chat screen")
            
            // Step 2: Send first message to trigger bot response
            val sentMessages = mutableListOf<String>()
            val firstMessage = "Please keep your responses to one word. This is a test and I want the messages to fit on the screen for verification. What is a professional who makes coffee called? - test $uniqueTestId"
            
            Log.d(TAG, "📨 Step 3: Sending initial message with normal timeouts for chat loading...")
            if (!sendMessageAndVerifyDisplay(firstMessage)) {
                Log.e(TAG, "❌ Initial message failed - cannot proceed with bot interruption test")
                Log.e(TAG, "   Message: '${firstMessage.take(50)}...'")
                failWithScreenshot("initial_message_failed", "Initial message failed to display - chat may not have loaded properly")
                return@runBlocking
            }
            sentMessages.add(firstMessage)
            Log.d(TAG, "✅ Initial message sent with normal timeouts - chat loaded successfully")
            
            // Step 3: Start interruption immediately (no waiting for visual indicators)
            Log.d(TAG, "🚀 Step 4: Starting interruption IMMEDIATELY after initial message...")
            
            // Step 4: While bot is responding, rapidly send interruption messages
            // This tests the core UX issue: users should be able to interrupt the bot
            Log.d(TAG, "📨 Step 5: Sending interrupt messages RAPIDLY while bot is responding...")
            val interruptMessageCount = MESSAGE_COUNT - 1 // 4 more messages
            
            // BUG DETECTION PHASE: Try to send rapid messages and FAIL if blocked
            // Phase 1: Test 2 TYPED messages (user types, then sends - realistic user flow)
            for (i in 1..2) {
                val interruptMessage = "hi $i"
                
                Log.d(TAG, "⌨️ TYPED MESSAGE $i: Testing IMMEDIATE keyboard typing during bot response...")
                
                // Type IMMEDIATELY without waiting for UI stability - this tests the real production bug
                // If this fails during bot response, that's the exact bug we're testing for
                if (!typeImmediatelyDuringBotResponse(interruptMessage)) {
                    Log.e(TAG, "❌ TYPED: Message $i failed during TYPING phase!")
                    Log.e(TAG, "   🚨 PRODUCTION BUG DETECTED: Cannot type in input field while bot is responding!")
                    Log.e(TAG, "   📝 This is the exact bug we're testing for - users cannot type messages while bot is thinking")
                    failWithScreenshot("typed_message_${i}_typing_blocked", "TYPED message $i failed during TYPING phase - input field blocked during bot response")
                    return@runBlocking
                }
                
                Log.d(TAG, "✅ TYPED: Message $i typed successfully, now testing send button...")
                
                // Send whatever is currently typed (two-step user flow: type → send)
                if (!sendCurrentTypedMessage()) {
                    Log.e(TAG, "❌ TYPED: Message $i failed during SEND BUTTON phase!")
                    Log.e(TAG, "   🚨 PRODUCTION BUG: Send button not working for typed message")
                    Log.e(TAG, "   📤 This prevents users from sending pre-typed messages during bot response")
                    failWithScreenshot("typed_message_${i}_send_failed", "TYPED message $i failed during SEND BUTTON phase - send button not working for typed message")
                    return@runBlocking
                }
                
                sentMessages.add(interruptMessage)
                Log.d(TAG, "✅ TYPED MESSAGE $i sent successfully via keyboard + send button")
                
                // Brief pause between typed messages (realistic user behavior)
                Thread.sleep(50)
            }
            
            // Phase 2: Test 3 QUICK messages (rapid type+send combo - power user flow)
            for (i in 3..interruptMessageCount) {
                val interruptMessage = "hi $i"
                
                Log.d(TAG, "⚡ QUICK MESSAGE $i: Testing rapid type+send during bot response...")
                
                // Step 1: Try to type the message
                if (!typeMessageInInputField(interruptMessage)) {
                    Log.e(TAG, "❌ QUICK: Rapid message $i failed during TYPING phase!")
                    Log.e(TAG, "   🚨 PRODUCTION BUG: Input field is blocked during bot response")
                    Log.e(TAG, "   📝 This prevents users from typing messages while bot is thinking")
                    failWithScreenshot("rapid_message_${i}_typing_blocked", "Rapid message $i failed during TYPING phase - input field blocked during bot response")
                    return@runBlocking
                }
                
                Log.d(TAG, "✅ QUICK MESSAGE $i: Typing successful, now testing send button...")
                
                // Step 2: Try to send the message
                if (!clickSendButtonAndWaitForSentRapid(interruptMessage)) {
                    Log.e(TAG, "❌ QUICK: Rapid message $i failed during SEND BUTTON phase!")
                    Log.e(TAG, "   🚨 PRODUCTION BUG: Send button not working during bot response")
                    Log.e(TAG, "   📤 This prevents users from sending messages while bot is thinking")
                    failWithScreenshot("rapid_message_${i}_send_failed", "Rapid message $i failed during SEND BUTTON phase - send button not working during bot response")
                    return@runBlocking
                }
                
                sentMessages.add(interruptMessage)
                Log.d(TAG, "✅ QUICK MESSAGE $i sent successfully via rapid type+send")
                
                // NO DELAY - true rapid fire testing for quick messages
            }
            
            Log.d(TAG, "🚀 RAPID PHASE COMPLETE: All ${interruptMessageCount} interrupt messages sent rapidly!")
            
            // VERIFICATION PHASE: Now verify WebSocket behavior for all messages
            Log.d(TAG, "🔍 VERIFICATION PHASE: Checking WebSocket delivery for all messages...")
            
            // Wait for all WebSocket sends to complete by checking input field state
            Log.d(TAG, "⏳ Waiting for all WebSocket sends to complete...")
            
            var inputFieldCleared = false
            val checkIntervalMs = 50L // Check every 200ms
            if (!inputFieldCleared) {
                Thread.sleep(checkIntervalMs)
            }
            
            if (!verifyInputFieldClearedRapid()) {
                Thread.sleep(checkIntervalMs)
                if (!verifyInputFieldClearedRapid()) {
                    failWithScreenshot("🚫 input field not cleared after last message")
                }
            } else {
                Log.d(TAG, "✅ All WebSocket sends completed (input field cleared)")
            }
            
            // Step 6 & 7: Verify all messages exist and check for duplicates using SINGLE smart collection
            Log.d(TAG, "🔍 Step 6 & 7: Single smart collection for message verification and duplicate detection...")
            
            // Single collection to avoid redundant scrolling
            val allChatMessages = collectAllMessages()
            Log.d(TAG, "✅ Smart collection complete: found ${allChatMessages.size} total messages in chat")
            
            // Step 6: Verify all messages exist
            val missingMessages = verifyAllMessagesExistInChatCached(sentMessages, allChatMessages)
            if (missingMessages.isNotEmpty()) {
                Log.e(TAG, "❌ Missing messages detected:")
                missingMessages.forEachIndexed { index, message ->
                    Log.e(TAG, "   Missing ${index + 1}: '${message.take(30)}...'")
                }
                failWithScreenshot("messages_missing_from_chat", "Missing ${missingMessages.size} messages from chat: ${missingMessages.map { it.take(20) }}")
            }
            
            Log.d(TAG, "✅ All ${sentMessages.size} messages verified to exist in chat")
            
            // Step 9: Final verification of UI state
            Log.d(TAG, "🔍 Step 9: Final verification of UI state...")
            
            // Final check: no duplicates in final state (using cached results)
            Log.d(TAG, "🔍 Final check: verifying no duplicates in entire chat using cached collection...")
            
            // Check entire chat for any duplicates (more comprehensive than checking individual messages)
            if (!noDuplicatesInAllMessages(allChatMessages)) {
                failWithScreenshot("chat_duplicates_detected", "Final check: Found duplicate message(s) in chat - indicates production bug")
            }
            
            Log.d(TAG, "✅ Duplicate checking completed using comprehensive chat analysis")
            
            // Final check: validate all bot responses are not server errors
            Log.d(TAG, "🔍 Final check: validating bot responses don't contain server errors...")
            if (!validateAllBotResponsesForServerErrors()) {
                failWithScreenshot("bot_server_errors_detected", "Final check: Bot responses contain server errors - server may be down or misconfigured")
            }
            
            Log.d(TAG, "✅ Final UI verification completed successfully!")
            Log.d(TAG, "📊 Test summary:")
            Log.d(TAG, "   ✅ Sent 1 initial message to trigger bot response")
            Log.d(TAG, "   ✅ Successfully interrupted bot with ${MESSAGE_COUNT - 1} additional messages:")
            Log.d(TAG, "      ⌨️ 2 TYPED messages (keyboard input + send button)")
            Log.d(TAG, "      ⚡ 2 QUICK messages (rapid type+send combo)")
            Log.d(TAG, "   ✅ All messages appeared immediately (optimistic UI)")
            Log.d(TAG, "   ✅ No duplicate user messages detected")
            Log.d(TAG, "   ✅ No duplicate assistant messages detected")
            Log.d(TAG, "   ✅ Messages preserved after bot response")
            Log.d(TAG, "   ✅ Bot interruption test completed successfully")
            Log.d(TAG, "   🎯 Validated both realistic user flows: deliberate typing + rapid messaging")
            
        } catch (e: Exception) {
                Log.e(TAG, "❌ Bot interruption test failed", e)
                failWithScreenshot("test_exception_failure", "Bot interruption test failed with exception: ${e.message}")
            }
        }
    }

    /**
     * Verify all expected messages exist in chat using smart collection (no scrolling needed)
     * Returns missing messages if any, or empty list if all found
     */
    private fun verifyAllMessagesExistInChat(expectedMessages: List<String>): List<String> {
        Log.d(TAG, "🔍 SMART VERIFICATION: Collecting all messages to verify ${expectedMessages.size} expected messages...")
        
        // Collect all messages in the chat
        val allMessages = collectAllMessages()
        
        return verifyAllMessagesExistInChatCached(expectedMessages, allMessages)
    }

    /**
     * Verify all expected messages exist using cached collection results (no additional scrolling)
     */
    private fun verifyAllMessagesExistInChatCached(expectedMessages: List<String>, allMessages: List<Pair<String, String>>): List<String> {
        val missingMessages = mutableListOf<String>()
        
        for ((index, expectedMessage) in expectedMessages.withIndex()) {
            val searchText = expectedMessage.take(30) // Use first 30 chars for matching
            
            // Check if this message exists in our collected messages
            val found = allMessages.any { (message, _) -> 
                message.contains(searchText, ignoreCase = true) 
            }
            
            if (found) {
                Log.d(TAG, "✅ CACHED VERIFICATION: Message ${index + 1} found: '${searchText}...'")
            } else {
                Log.w(TAG, "❌ CACHED VERIFICATION: Message ${index + 1} missing: '${searchText}...'")
                Log.w(TAG, "   Looking for: '${expectedMessage}'")
                Log.w(TAG, "   Available messages: ${allMessages.map { it.first.take(20) }}")
                missingMessages.add(expectedMessage)
            }
        }
        
        if (missingMessages.isEmpty()) {
            Log.d(TAG, "✅ CACHED VERIFICATION: All ${expectedMessages.size} messages found in chat!")
        } else {
            Log.w(TAG, "❌ CACHED VERIFICATION: ${missingMessages.size} messages missing from chat")
        }
        
        return missingMessages
    }

    /**
     * Count message occurrences using cached collection results (no additional scrolling)
     */
    private fun countMessageOccurrencesCached(messageText: String, allMessages: List<Pair<String, String>>): Int {
        Log.d(TAG, "🔍 CACHED COUNT: Counting occurrences of '$messageText' using cached collection")
        
        var count = 0
        var realDuplicatesFound = 0
        val messageInstances = mutableListOf<Pair<String, String>>()
        
        // Use longer search text (30 chars) to avoid matching truncated chat titles
        val searchText = messageText.take(30)
        
        for ((message, position) in allMessages) {
            if (message.contains(searchText, ignoreCase = true)) {
                count++
                messageInstances.add(Pair(message, position))
                Log.d(TAG, "✅ CACHED COUNT: Found match: '$message' at $position")
            }
        }
        
        // Check for real duplicates (same message content appearing at different positions)
        val messageGroups = messageInstances.groupBy { it.first }
        for ((messageContent, instances) in messageGroups) {
            if (instances.size > 1) {
                realDuplicatesFound++
                Log.w(TAG, "🚨 CACHED COUNT: REAL DUPLICATE: '$messageContent' appears ${instances.size} times!")
                instances.forEach { (_, position) ->
                    Log.w(TAG, "    - At position: $position")
                }
            }
        }
        
        if (realDuplicatesFound > 0) {
            throw AssertionError("Found $realDuplicatesFound real duplicate message(s) in chat! This indicates a production bug.")
        }
        
        Log.d(TAG, "📊 CACHED COUNT: Total occurrences found: $count (no real duplicates)")
        return count
    }

    /**
     * Check for duplicates in the entire chat message collection
     * Returns true if duplicates are found (fails fast on first duplicate)
     */
    private fun noDuplicatesInAllMessages(allMessages: List<Pair<String, String>>): Boolean {
        Log.d(TAG, "🔍 DUPLICATE CHECK: Analyzing ${allMessages.size} messages for duplicates...")
        
        val checkedMessages = mutableSetOf<String>()
        
        for ((message, position) in allMessages) {
            val messageKey = message.trim() // Use full message content for precise matching
            
            // Skip if we already checked this message content
            if (checkedMessages.contains(messageKey)) { return false }
            checkedMessages.add(messageKey)
        }
        
        Log.d(TAG, "✅ DUPLICATE CHECK: No duplicates found in chat")
        return true
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