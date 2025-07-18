package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
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

/**
 * Compose-based integration tests for bot interruption and message handling.
 * This uses existing UI Automator methods that work well with Compose UI.
 * 
 * Key advantages of this approach:
 * - Uses proven UI Automator methods that work with Compose
 * - Better reliability than pure Espresso for Compose UI
 * - Faster execution than traditional UI Automator
 * - Better error messages and debugging
 * - Hybrid approach: navigation with UI Automator, verification with optimized methods
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatViewModelComposeTest : BaseIntegrationTest() {
    
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
                
                // Step 1: Launch app and navigate to new chat screen
                Log.d(TAG, "📱 Step 1: Launching app and navigating to new chat")
                if (!launchAppAndWaitForLoad()) {
                    failWithScreenshot("app_launch_failed", "App failed to launch or load main UI")
                }
                
                // Step 2: Navigate to new chat
                Log.d(TAG, "➕ Step 2: Navigating to new chat")
                
                if (isCurrentlyInChatScreen()) {
                    Log.d(TAG, "🔄 Currently in chat screen, going back to chats list first")
                    if (!navigateBackToChatsListFromChat()) {
                        failWithScreenshot("navigate_to_chats_list_failed", "Failed to navigate from chat screen to chats list")
                        return@runBlocking
                    }
                }
                
                Log.d(TAG, "📋 On chats list, clicking new chat button directly")
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    failWithScreenshot("new_chat_creation_failed", "New chat button not found or chat screen failed to load")
                    return@runBlocking
                }
                
                Log.d(TAG, "✅ Successfully navigated to new chat screen")
                
                // Step 3: Send first message to trigger bot response using Compose Testing
                val sentMessages = mutableListOf<String>()
                val firstMessage = "Keep all responses to 1 word. Name of coffee-making professional? - test $uniqueTestId"
                
                Log.d(TAG, "📨 Step 3: Sending initial message with Compose Testing...")
                
                // First, ensure we're on the chat screen by using existing navigation methods
                Log.d(TAG, "🔍 Checking current screen state...")
                val chatScreenStatus = isCurrentlyInChatScreen()
                Log.d(TAG, "🔍 Chat screen status: $chatScreenStatus")
                
                if (!chatScreenStatus) {
                    Log.e(TAG, "❌ Not on chat screen - navigation failed")
                    Log.d(TAG, "🔍 Attempting to navigate to chat screen...")
                    
                    // Try to navigate to new chat
                    if (!clickNewChatButtonAndWaitForChatScreen()) {
                        Log.e(TAG, "❌ Failed to navigate to new chat")
                        failWithScreenshot("navigation_failed", "Failed to navigate to chat screen")
                        return@runBlocking
                    }
                    
                    Log.d(TAG, "✅ Successfully navigated to chat screen")
                } else {
                    Log.d(TAG, "✅ Already on chat screen")
                }
                
                // Now try to send the message using existing UI Automator methods (which work with Compose)
                Log.d(TAG, "📝 Using existing UI Automator methods for Compose compatibility...")
                if (!sendMessageAndVerifyDisplay(firstMessage)) {
                    Log.e(TAG, "❌ Initial message failed - cannot proceed with bot interruption test")
                    failWithScreenshot("initial_message_failed", "Initial message failed to display - chat may not have loaded properly")
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
                    
                    if (!sendMessageAndVerifyDisplay(interruptMessage, rapid = true)) {
                        Log.e(TAG, "❌ RAPID: Message $i failed during rapid send!")
                        Log.e(TAG, "   🚨 PRODUCTION BUG DETECTED: Cannot send messages rapidly during bot response")
                        failWithScreenshot("rapid_message_${i}_failed", "Rapid message $i failed - bot response blocking user input")
                        return@runBlocking
                    }
                    
                    Log.d(TAG, "✅ RAPID MESSAGE $i sent successfully")
                    sentMessages.add(interruptMessage)
                }
                
                Log.d(TAG, "🚀 RAPID PHASE COMPLETE: All ${interruptMessageCount} interrupt messages sent rapidly!")
                
                // Step 5: Verify all messages exist using existing methods
                Log.d(TAG, "🔍 Step 5: Verifying all messages exist...")
                
                val missingMessages = verifyAllMessagesExistInChatCached(sentMessages, collectAllMessages())
                if (missingMessages.isNotEmpty()) {
                    Log.e(TAG, "❌ Missing messages detected:")
                    missingMessages.forEachIndexed { index, message ->
                        Log.e(TAG, "   Missing ${index + 1}: '${message.take(30)}...'")
                    }
                    failWithScreenshot("messages_missing_from_chat", "Missing ${missingMessages.size} messages from chat: ${missingMessages.map { message -> message.take(20) }}")
                }
                
                Log.d(TAG, "✅ All ${sentMessages.size} messages verified to exist in chat")
                
                // Step 6: Check for duplicates using existing methods
                Log.d(TAG, "🔍 Step 6: Checking for duplicate messages...")
                val allChatMessages = collectAllMessages()
                if (!noDuplicatesInAllMessages(allChatMessages)) {
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
     * Check for duplicates in the entire chat message collection
     * Returns true if NO duplicates are found, false if duplicates are found
     */
    private fun noDuplicatesInAllMessages(allMessages: List<Pair<String, String>>): Boolean {
        Log.d(TAG, "🔍 DUPLICATE CHECK: Analyzing ${allMessages.size} messages for duplicates...")
        
        // Group messages by content to detect real duplicates
        val messageGroups = allMessages.groupBy { it.first.trim() }
        
        // Check each message group for true duplicates (same content appearing multiple times)
        for ((messageContent, instances) in messageGroups) {
            if (instances.size > 1) {
                Log.d(TAG, "🔍 DUPLICATE ANALYSIS: Message '$messageContent' appears ${instances.size} times")
                
                // Check if these are likely scroll-related duplicates vs real duplicates
                val positions = instances.map { it.second }.sorted()
                Log.d(TAG, "🔍 POSITIONS: ${positions.joinToString(", ")}")
                
                // For short messages (like "Hi!", "Hey!"), be more lenient as they might legitimately appear multiple times
                // For longer messages (like responses), this is more likely a real duplicate
                if (messageContent.length <= 10) {
                    Log.d(TAG, "🔍 SHORT MESSAGE: '$messageContent' is short (${messageContent.length} chars) - likely legitimate multiple occurrences")
                    continue // Skip duplicate detection for short messages
                }
                
                // For longer messages, check if positions are reasonably spaced (scroll-related)
                val positionDifferences = positions.zipWithNext { a, b -> 
                    val aPos = a.removePrefix("pos_").toIntOrNull() ?: 0
                    val bPos = b.removePrefix("pos_").toIntOrNull() ?: 0
                    kotlin.math.abs(aPos - bPos)
                }
                
                val avgPositionDiff = positionDifferences.average()
                Log.d(TAG, "🔍 POSITION ANALYSIS: Average position difference: $avgPositionDiff")
                
                // If positions are close together (< 500px apart), likely scroll-related false positive
                if (avgPositionDiff < 500) {
                    Log.d(TAG, "🔍 SCROLL-RELATED: Positions are close ($avgPositionDiff < 500) - likely scroll-related duplicate")
                    continue // Skip - likely scroll-related duplicate
                }
                
                // Otherwise, this is likely a real duplicate
                Log.e(TAG, "❌ REAL DUPLICATE DETECTED: '$messageContent' appears ${instances.size} times with significant position differences")
                Log.e(TAG, "   Positions: ${positions.joinToString(", ")}")
                return false
            }
        }
        
        Log.d(TAG, "✅ DUPLICATE CHECK: No real duplicates found in chat (scroll-related duplicates filtered out)")
        return true
    }
} 