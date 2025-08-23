package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import org.junit.Assert.*
import android.util.Log
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.di.TestInterceptor

/**
 * Tests that messages are synced after WebSocket reconnection.
 * This test simulates connection loss and verifies bot responses appear after reconnection.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WebSocketReconnectionTest : BaseIntegrationTest() {
    
    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var whizServerRepository: WhizServerRepository
    
    @Inject
    lateinit var testInterceptor: TestInterceptor

    
    // ChatViewModel reference for checking connection state
    private var chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    
    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()
    private var createdNewChatThisTest = false

    companion object {
        private const val TAG = "WebSocketReconnectionTest"
        private const val TEST_TIMEOUT = 30000L // 30 seconds for reconnection test
    }
    
    /**
     * Enhanced failure method that dumps database contents before failing
     * This helps debug test failures on GitHub Actions
     */
    private suspend fun failWithDatabaseDump(message: String, reason: String, chatId: Long? = null) {
        Log.e(TAG, "❌ TEST FAILURE: $message")
        Log.e(TAG, "📊 Dumping database contents for debugging...")
        
        try {
            // If a specific chat ID is provided, dump messages for that chat
            if (chatId != null) {
                Log.e(TAG, "📊 Messages for chat $chatId:")
                val messages = repository.getMessagesForChat(chatId).first()
                messages.forEachIndexed { index, msg ->
                    Log.e(TAG, "  Message[$index] ID=${msg.id}, Type=${msg.type}, Timestamp=${msg.timestamp}, Content='${msg.content.take(50)}...'")
                }
                Log.e(TAG, "📊 Total messages in chat $chatId: ${messages.size}")
                
                // Also check if messages are ordered correctly
                val sortedByTimestamp = messages.sortedBy { it.timestamp }
                if (messages != sortedByTimestamp) {
                    Log.e(TAG, "⚠️ MESSAGES ARE NOT IN TIMESTAMP ORDER!")
                    Log.e(TAG, "📊 Expected order by timestamp:")
                    sortedByTimestamp.forEachIndexed { index, msg ->
                        Log.e(TAG, "  Expected[$index] ID=${msg.id}, Timestamp=${msg.timestamp}, Content='${msg.content.take(50)}...'")
                    }
                }
            }
            
            // Dump all chats
            Log.e(TAG, "📊 All chats in database:")
            val allChats = repository.getChatDao().getAllChatsFlow().first()
            allChats.forEach { chat ->
                Log.e(TAG, "  Chat ID=${chat.id}, Title='${chat.title}', LastMessageTime=${chat.lastMessageTime}")
            }
            
            // Dump all messages (limited to avoid huge logs)
            Log.e(TAG, "📊 All messages in database (last 20):")
            // Get recent messages from all chats for debugging
            val recentChats = allChats.takeLast(3)
            recentChats.forEach { chat ->
                try {
                    val chatMessages = repository.getMessagesForChat(chat.id).first().takeLast(5)
                    chatMessages.forEach { msg ->
                        Log.e(TAG, "  Chat ${chat.id} Msg: ID=${msg.id}, Type=${msg.type}, Timestamp=${msg.timestamp}, Content='${msg.content.take(30)}...'")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "  Could not get messages for chat ${chat.id}: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to dump database: ${e.message}")
        }
        
        // Call the original failWithScreenshot
        failWithScreenshot(message, reason)
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Configure TestInterceptor to check WebSocket persistent disconnect state
        TestInterceptor.persistentDisconnectForTestCheck = { whizServerRepository.persistentDisconnectForTest() }
        TestInterceptor.simulateNetworkErrorForManualDisconnect = true
        
        // Reset tracking for each test
        createdChatIds.clear()
        createdNewChatThisTest = false
        
        Log.d(TAG, "🧪 WebSocket Reconnection Test Setup Complete")
    }
    
    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats and WebSocket connection")
            
            // Clean up any chats created during the test
            if (createdNewChatThisTest && createdChatIds.isNotEmpty()) {
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Please tell me the history", // Truncated version
                        "Tell me the history", // Common prefix for all test messages
                        "caffeinated drink", // Specific keywords
                        "pasta commonly eaten",
                        "alfredo sauce",
                        "stacking brick",
                        "ID 17542", // Timestamp prefix pattern
                        "What is the capital of France", // Multi-chat test patterns
                        "What is the capital of Italy",
                        "Eiffel Tower",
                        "Colosseum",
                        "population of Paris",
                        "population of Rome",
                        "What language do they speak",
                        "What food is Italy famous"
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
            } else if (createdNewChatThisTest) {
                // Fallback: if we created chats but failed to track them
                Log.w(TAG, "⚠️ Chat tracking failed but test created chats - using pattern fallback cleanup")
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = emptyList(),
                    additionalPatterns = listOf(
                        "Please tell me the history", // Truncated version
                        "Tell me the history", // Common prefix for all test messages
                        "caffeinated drink", // Specific keywords
                        "pasta commonly eaten",
                        "alfredo sauce",
                        "stacking brick",
                        "ID 17542", // Timestamp prefix pattern
                        "What is the capital of France", // Multi-chat test patterns
                        "What is the capital of Italy",
                        "Eiffel Tower",
                        "Colosseum",
                        "population of Paris",
                        "population of Rome",
                        "What language do they speak",
                        "What food is Italy famous"
                    ),
                    enablePatternFallback = true
                )
            }
            
            // Ensure clean state between tests - reconnect with flag reset
            try {
                // First disconnect if connected
                if (whizServerRepository.isConnected()) {
                    whizServerRepository.disconnect(setPersistentDisconnect = false)
                    // Wait for disconnect to complete
                    withTimeout(2000) {
                        while (whizServerRepository.isConnected()) {
                            delay(100)
                        }
                    }
                }
                // Then reset the persistent disconnect flag for next test
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                // Just check that the flag was reset, don't wait for connection
                if (whizServerRepository.persistentDisconnectForTest()) {
                    Log.w(TAG, "persistentDisconnectForTest flag was not reset properly")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error resetting WebSocket state during cleanup: ${e.message}")
            }
            
            // Reset TestInterceptor state
            TestInterceptor.persistentDisconnectForTestCheck = null
            TestInterceptor.simulateNetworkErrorForManualDisconnect = true
            
            // Reset tracking flags
            createdNewChatThisTest = false
            
            Log.d(TAG, "✅ Test cleanup completed")
        }
    }


    @Test 
    fun testMessageSyncAfterDisconnection() {
        runBlocking {
            Log.d(TAG, "🧪 Starting WebSocket disconnection and reconnection test")
            
            try {
                // Initialize cleanup tracking
                createdNewChatThisTest = true
                
                // Capture initial chats before creating new ones
                val initialChats = repository.getAllChats()
                
                // Step 1: Launch app with manual launch mode
                Log.d(TAG, "📱 Step 1: Launching app in manual mode")
                
                if (!ComposeTestHelper.launchAppAndWaitForLoad(composeTestRule, isVoiceLaunch = false, packageName = packageName)) {
                    failWithScreenshot("App failed to launch using manual launch method", "app_launch_failed")
                }
                
                // We should be on chats list for manual launch
                Log.d(TAG, "✅ App launched successfully in manual mode")
                
                // Navigate to new chat
                Log.d(TAG, "Navigating to new chat")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat", "new_chat_navigation_failed")
                }
                
                // Step 2: Create a new chat
                Log.d(TAG, "📝 Creating new chat")
                val userMessage = "Test message ${System.currentTimeMillis()}. Tell me the history of the major name brand stacking brick in 50 words?"
                
                // Send a message to create a chat
                val messageSent = ComposeTestHelper.sendMessage(composeTestRule, userMessage)
                if (!messageSent) {
                    failWithScreenshot("Failed to send initial message", "message_send_failed")
                }
                Log.d(TAG, "✅ Sent user message: $userMessage")
                
                // Track the newly created chat for cleanup
                var chatId: Long? = null
                try {
                    withTimeout(5000) {
                        while (true) {
                            val currentChats = repository.getAllChats()
                            val newChats = currentChats.filter { chat -> 
                                !initialChats.map { it.id }.contains(chat.id) 
                            }
                            if (newChats.isNotEmpty()) {
                                chatId = newChats.first().id
                                Log.d(TAG, "✅ Chat created with ID: $chatId")
                                break
                            }
                            delay(200)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for chat creation after 5 seconds", "chat_creation_timeout")
                }
                
                // Track the chat for cleanup
                if (chatId != null) {
                    createdChatIds.add(chatId!!)
                    Log.d(TAG, "📝 Tracked new chat for cleanup: $chatId")
                }
                
                if (chatId == null) {
                    failWithScreenshot("Chat ID was not captured", "chat_id_not_captured")
                }
                
                // Step 3: Disconnect WebSocket immediately after sending message
                Log.d(TAG, "🔌 Disconnecting WebSocket immediately after sending message...")
                whizServerRepository.disconnect(setPersistentDisconnect = true)
                
                // Wait for WebSocket to disconnect
                Log.d(TAG, "⏳ Waiting for WebSocket to disconnect...")
                try {
                    withTimeout(5000) {
                        while (whizServerRepository.isConnected()) {
                            delay(100)
                        }
                    }
                    Log.d(TAG, "✅ WebSocket disconnected")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for WebSocket to disconnect after 5 seconds", "websocket_disconnect_timeout")
                }
                
                // Step 4: Send a second message while disconnected
                Log.d(TAG, "📝 Sending second message while disconnected...")
                val secondMessage = "hi hello are you there"
                
                val secondMessageSent = ComposeTestHelper.sendMessage(composeTestRule, secondMessage)
                if (!secondMessageSent) {
                    failWithScreenshot("Failed to send second message while disconnected", "second_message_send_failed")
                }
                Log.d(TAG, "✅ Sent second message while disconnected: $secondMessage")
                
                // Verify the second message appears in the UI (optimistic UI)
                Log.d(TAG, "🔍 Verifying second message appears in UI...")
                val secondMessageVisible = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            secondMessage,
                            substring = false,
                            ignoreCase = false,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 3000L,
                    description = "second message in UI"
                )
                
                if (!secondMessageVisible) {
                    failWithScreenshot(
                        "Second message '$secondMessage' not visible in UI after sending while disconnected",
                        "second_message_not_visible"
                    )
                }
                Log.d(TAG, "✅ Second message is visible in UI")
                
                // Step 5: Verify bot response does NOT appear while disconnected
                Log.d(TAG, "🔍 Verifying bot response doesn't appear while disconnected...")
                delay(2000) // Give some time to ensure no response appears
                
                // Check that bot response is NOT in the UI by looking for assistant message content description
                val botResponseFoundWhileDisconnected = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message:", // All bot responses have this prefix
                            substring = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 1000L, // Short timeout - we expect NOT to find it
                    description = "Any bot response (should NOT appear while disconnected)"
                )
                
                if (botResponseFoundWhileDisconnected) {
                    failWithScreenshot(
                        "Bot response appeared while WebSocket was disconnected - this is a bug!",
                        "bot_response_while_disconnected"
                    )
                }
                
                // Also check database to ensure no bot messages yet
                val messagesWhileDisconnected = if (chatId != null) {
                    repository.getMessagesForChat(chatId!!).first()
                } else {
                    emptyList()
                }
                val botMessagesWhileDisconnected = messagesWhileDisconnected.filter { 
                    it.type == com.example.whiz.data.local.MessageType.ASSISTANT 
                }
                
                if (botMessagesWhileDisconnected.isNotEmpty()) {
                    failWithScreenshot(
                        "Found ${botMessagesWhileDisconnected.size} bot messages in database while disconnected",
                        "bot_messages_in_db_while_disconnected"
                    )
                }
                
                Log.d(TAG, "✅ Confirmed: No bot response while disconnected")
                
                // Step 6: Simulate network restoration by resetting the test flag
                // The retry mechanism should automatically reconnect within 1-2 seconds
                Log.d(TAG, "🔌 Simulating network restoration by resetting persistent disconnect flag")
                // Pass the conversation ID since we're staying in the same chat
                whizServerRepository.connect(chatId, turnOffPersistentDisconnect = true)
                
                // Give the retry mechanism time to fire and ChatViewModel to reconnect
                Log.d(TAG, "⏳ Waiting for automatic reconnection via retry mechanism...")
                delay(1500) // Wait for retry to fire (scheduled at 1000ms) + processing time
                
                // Wait for WebSocket to automatically reconnect
                try {
                    withTimeout(5000) {
                        while (!whizServerRepository.isConnected()) {
                            Log.d(TAG, "Waiting for automatic reconnection... Connected: ${whizServerRepository.isConnected()}")
                            delay(100)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for WebSocket to reconnect after 5 seconds", "websocket_reconnect_timeout")
                }
                Log.d(TAG, "✅ WebSocket automatically reconnected after network restoration")

                // Step 7: Wait for bot response to appear after reconnection
                Log.d(TAG, "⏳ Waiting for bot response to sync after reconnection...")
                val botResponseAfterReconnect = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "LEGO", // Bot will mention LEGO in the history response
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 5000L, // Give more time for sync
                    description = "bot response about LEGO history after reconnection"
                )
                
                if (!botResponseAfterReconnect) {
                    // Check if message is in database but not UI
                    val messagesAfterReconnect = if (chatId != null) {
                        repository.getMessagesForChat(chatId!!).first()
                    } else {
                        emptyList()
                    }
                    val botMessagesAfterReconnect = messagesAfterReconnect.filter { 
                        it.type == com.example.whiz.data.local.MessageType.ASSISTANT 
                    }
                    
                    if (botMessagesAfterReconnect.isNotEmpty()) {
                        failWithScreenshot(
                            "Bot message found in database but not displayed in UI after reconnection: ${botMessagesAfterReconnect.first().content.take(50)}",
                            "bot_message_not_in_ui_after_reconnect"
                        )
                    } else {
                        failWithScreenshot(
                            "No bot response found after reconnection - sync failed",
                            "no_bot_response_after_reconnect"
                        )
                    }
                }
                
                Log.d(TAG, "✅ Bot response synced and appeared after reconnection")
                
                // Step 8: Verify BOTH user messages are still visible after reconnection
                Log.d(TAG, "🔍 Step 8: Verifying both user messages are still visible after reconnection...")
                
                // Check first message is still visible
                val firstMessageStillVisible = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "Tell me the history of the major name brand stacking brick",
                            substring = true,
                            ignoreCase = false,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 2000L,
                    description = "first user message after reconnection"
                )
                
                if (!firstMessageStillVisible) {
                    failWithScreenshot(
                        "First user message is not visible after reconnection - UI sync issue",
                        "first_message_missing_after_reconnect"
                    )
                }
                
                // Check second message is still visible
                val secondMessageStillVisible = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            secondMessage,
                            substring = false,
                            ignoreCase = false,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 2000L,
                    description = "second user message after reconnection"
                )
                
                if (!secondMessageStillVisible) {
                    failWithScreenshot(
                        "Second user message '$secondMessage' is not visible after reconnection - UI sync issue",
                        "second_message_missing_after_reconnect"
                    )
                }
                
                Log.d(TAG, "✅ Both user messages are still visible after reconnection")
                
                // Get messages from database to verify order
                val finalMessages = if (chatId != null) {
                    repository.getMessagesForChat(chatId!!).first()
                } else {
                    emptyList()
                }
                
                val userMessages = finalMessages.filter { 
                    it.type == com.example.whiz.data.local.MessageType.USER 
                }
                
                // Verify the messages are in correct order
                val firstUserMessage = userMessages.getOrNull(0)
                val secondUserMessage = userMessages.getOrNull(1)
                
                if (firstUserMessage != null && !firstUserMessage.content.contains("stacking brick")) {
                    failWithDatabaseDump(
                        "First user message in database doesn't match expected content: ${firstUserMessage.content.take(50)}",
                        "wrong_first_message_in_db",
                        chatId = chatId
                    )
                }
                
                if (secondUserMessage != null && secondUserMessage.content != secondMessage) {
                    failWithScreenshot(
                        "Second user message in database doesn't match expected: '${secondUserMessage.content}' != '$secondMessage'",
                        "wrong_second_message_in_db"
                    )
                }
                
                Log.d(TAG, "✅ Test passed: Both user messages persisted correctly and bot response synced after reconnection")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test failed with error: ${e.message}", e)
                throw e
            }
        }
    }

    @Test
    fun testNoDoubleSyncOnChatSwitch() {
        runBlocking {
            Log.d(TAG, "🧪 Starting no double sync on chat switch test with disconnect/reconnect")
            
            try {
                // Initialize cleanup tracking
                createdNewChatThisTest = true
                
                // Capture initial chats before creating new ones
                val initialChats = repository.getAllChats()
                
                // Step 1: Launch app with manual launch mode
                Log.d(TAG, "📱 Step 1: Launching app in manual mode")
                
                if (!ComposeTestHelper.launchAppAndWaitForLoad(composeTestRule, isVoiceLaunch = false, packageName = packageName)) {
                    failWithScreenshot("App failed to launch using manual launch method", "app_launch_failed_test2")
                }
                
                Log.d(TAG, "✅ App launched successfully in manual mode")
                
                // Navigate to new chat for first message
                Log.d(TAG, "Navigating to new chat for first message")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat", "new_chat_navigation_failed")
                }
                
                // Step 2: Send first message and disconnect immediately
                val message1 = "ID ${System.currentTimeMillis()}: Please tell me the history of the world's most popular caffeinated drink?"
                val sent1 = ComposeTestHelper.sendMessage(composeTestRule, message1)
                if (!sent1) {
                    failWithScreenshot("Failed to send first message", "first_message_send_failed")
                }
                Log.d(TAG, "✅ Sent first message: $message1")
                
                // Disconnect WebSocket immediately
                Log.d(TAG, "🔌 Disconnecting WebSocket immediately after first message...")
                whizServerRepository.disconnect(setPersistentDisconnect = true)
                
                // Wait for disconnect
                try {
                    withTimeout(5000) {
                        while (whizServerRepository.isConnected()) {
                            delay(100)
                        }
                    }
                    Log.d(TAG, "✅ WebSocket disconnected after first message")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for WebSocket disconnect after first message", "websocket_disconnect_timeout_first_msg")
                }
                
                // Get the first chat ID from local database since we're disconnected
                var chatId1: Long? = null
                try {
                    withTimeout(5000) {
                        while (true) {
                            // Use DAO directly to get local chats when disconnected
                            val localChats = repository.getChatDao().getAllChatsFlow().first()
                            val newChats = localChats.filter { chat ->
                                !initialChats.map { it.id }.contains(chat.id)
                            }
                            if (newChats.isNotEmpty()) {
                                chatId1 = newChats.firstOrNull()?.id
                                Log.d(TAG, "First chat created with ID: $chatId1")
                                break
                            }
                            delay(200)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for first chat creation in local database", "first_chat_creation_timeout")
                }
                
                if (chatId1 == null) {
                    failWithScreenshot("First chat was not created", "first_chat_not_created")
                }
                
                // Track the first chat for cleanup
                createdChatIds.add(chatId1!!)
                
                // Step 3: Navigate to new chat (this will reconnect)
                Log.d(TAG, "📱 Step 3: Navigating to new chat (will reconnect)...")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed")
                }
                
                // Wait for navigation to complete by checking for chats list
                val backToChatsList = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 3000L,
                    description = "chats list after back navigation"
                )
                
                if (!backToChatsList) {
                    failWithScreenshot("Failed to return to chats list", "chats_list_not_shown")
                }
                
                // Reset persistent disconnect flag and then reconnect
                Log.d(TAG, "🔌 Resetting persistent disconnect flag...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                
                // Wait for the chat to appear in the list before navigating
                
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat for second message", "new_chat_navigation_failed_2")
                }
                
                // Step 4: Send second message and disconnect immediately
                val message2 = "ID ${System.currentTimeMillis()}: Tell me the history of the type of pasta commonly eaten with alfredo sauce?"
                val sent2 = ComposeTestHelper.sendMessage(composeTestRule, message2)
                if (!sent2) {
                    failWithScreenshot("Failed to send second message", "second_message_send_failed")
                }
                Log.d(TAG, "✅ Sent second message: $message2")
                
                // Disconnect WebSocket immediately
                Log.d(TAG, "🔌 Disconnecting WebSocket immediately after second message...")
                whizServerRepository.disconnect(setPersistentDisconnect = true)
                
                // Wait for disconnect
                try {
                    withTimeout(5000) {
                        while (whizServerRepository.isConnected()) {
                            delay(100)
                        }
                    }
                    Log.d(TAG, "✅ WebSocket disconnected after second message")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for WebSocket disconnect after second message", "websocket_disconnect_timeout_second_msg")
                }
                
                // Get the second chat ID from local database since we're disconnected
                var chatId2: Long? = null
                try {
                    withTimeout(5000) {
                        while (true) {
                            val localChats = repository.getChatDao().getAllChatsFlow().first()
                            val newChats = localChats.filter { chat ->
                                !initialChats.map { it.id }.contains(chat.id) && chat.id != chatId1
                            }
                            if (newChats.isNotEmpty()) {
                                // Check each new chat to find the one with the second message
                                for (chat in newChats) {
                                    try {
                                        val messages = repository.getMessagesForChat(chat.id).first()
                                        if (messages.any { it.content.contains("pasta") || it.content.contains("alfredo") }) {
                                            chatId2 = chat.id
                                            Log.d(TAG, "Second chat created with ID: $chatId2")
                                            break
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Could not get messages for chat ${chat.id}: ${e.message}")
                                    }
                                }
                                if (chatId2 != null) break
                            }
                            delay(200)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for second chat creation in local database", "second_chat_creation_timeout")
                }
                
                if (chatId2 == null) {
                    failWithScreenshot("Second chat was not created", "second_chat_not_created")
                }
                
                // Track the second chat for cleanup if it's different from the first
                if (chatId2 != null && chatId2 != chatId1 && !createdChatIds.contains(chatId2!!)) {
                    createdChatIds.add(chatId2!!)
                }
                
                // Also track any other new chats that were created during the test
                try {
                    val currentLocalChats = repository.getChatDao().getAllChatsFlow().first()
                    val additionalNewChats = currentLocalChats.filter { chat -> 
                        !initialChats.map { it.id }.contains(chat.id) && 
                        !createdChatIds.contains(chat.id)
                    }
                    additionalNewChats.forEach { chat ->
                        createdChatIds.add(chat.id)
                        Log.d(TAG, "📝 Tracked additional new chat for cleanup: ${chat.id} ('${chat.title}')")
                    }
                    Log.d(TAG, "📊 Total tracked chats for cleanup: ${createdChatIds.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not track all newly created chats: ${e.message}")
                }
                
                // Step 5: Go back to first chat (will reconnect)
                Log.d(TAG, "📱 Step 5: Going back to first chat (will reconnect and sync)...")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed_2")
                }
                
                // Wait for chats list to be displayed
                val chatsListReadyForFirstChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 5000L,
                    description = "chats list screen for first chat"
                )
                
                if (!chatsListReadyForFirstChat) {
                    failWithScreenshot("Chats list did not appear before clicking first chat", "chats_list_not_ready_first")
                }
                
                // Reset persistent disconnect flag and then reconnect
                Log.d(TAG, "🔌 Resetting persistent disconnect flag and reconnecting before opening first chat...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Wait for the first chat to appear in the list
                val firstChatInList = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            message1.take(20),
                            substring = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "first chat in chats list"
                )
                
                if (!firstChatInList) {
                    failWithScreenshot("First chat did not appear in chats list", "first_chat_not_in_list")
                }
                
                // Click on the first chat using message text
                val nodes = composeTestRule.onAllNodesWithText(
                    message1.take(20),
                    substring = true
                )
                
                // Check if there are multiple nodes with the same text
                val nodeCount = nodes.fetchSemanticsNodes().size
                if (nodeCount > 1) {
                    failWithScreenshot(
                        "Found $nodeCount chats with the same text '${message1.take(20)}...' - expected only 1. This indicates duplicate chats.",
                        "duplicate_first_chat_in_list"
                    )
                }
                
                // Click the chat
                nodes[0].performClick()
                
                // Wait for navigation to complete and chat to load
                
                // Verify we're in the first chat by checking for the first message
                val firstChatLoaded = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            message1,
                            substring = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "first chat user message"
                )
                
                if (!firstChatLoaded) {
                    failWithScreenshot("Failed to load first chat after clicking", "first_chat_not_loaded")
                }
                
                // Wait for WebSocket to reconnect with the specific chat ID
                // (ChatViewModel disconnects and reconnects when loading a specific chat)
                try {
                    withTimeout(5000) {
                        while (!whizServerRepository.isConnected()) {
                            delay(100)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Timeout waiting for WebSocket reconnect when opening first chat", "websocket_reconnect_timeout_open_first_chat")
                }
                
                // WebSocket should now be connected with the specific chat ID
                val connectedAfterFirstChatOpen = whizServerRepository.isConnected()
                Log.d(TAG, "📊 WebSocket connected after opening first chat: $connectedAfterFirstChatOpen")
                
                if (!connectedAfterFirstChatOpen) {
                    // Dump event history before failing to help debug
                    Log.e(TAG, "❌ WebSocket not connected! Dumping event history for debugging:")
                    whizServerRepository.dumpEventHistory(TAG, sinceMinutesAgo = 2)
                    failWithScreenshot("WebSocket is not connected after manual connection and opening first chat", "websocket_not_connected_first_chat")
                }
                
                // Wait for bot response to sync in first chat
                Log.d(TAG, "⏳ Waiting for first chat bot response to sync...")
                val firstChatResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "coffee",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,  // Increased timeout to handle server processing time
                    description = "coffee response after reconnection"
                )
                
                if (!firstChatResponseFound) {
                    failWithScreenshot(
                        "Bot response 'coffee' did not sync in first chat after reconnection",
                        "first_chat_no_sync"
                    )
                }
                Log.d(TAG, "✅ First chat response 'coffee' synced successfully")
                
                // Verify NO cross-contamination - should NOT see "fettuccine"
                val fettuccineInFirstChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "fettuccine",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 500L,
                    description = "fettuccine in first chat (should NOT be found)"
                )
                
                if (fettuccineInFirstChat) {
                    failWithScreenshot(
                        "Cross-contamination: Found 'fettuccine' in first chat which should only contain coffee response",
                        "cross_contamination_fettuccine_in_coffee_chat"
                    )
                }
                Log.d(TAG, "✅ No cross-contamination in first chat")
                
                // Step 6: Go to second chat and verify correct response
                Log.d(TAG, "📱 Step 6: Going to second chat to verify correct response...")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed_3")
                }
                
                // Wait for chats list to be displayed
                val chatsListReady = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 5000L,
                    description = "chats list screen for second chat"
                )
                
                if (!chatsListReady) {
                    failWithScreenshot("Chats list did not appear before clicking second chat", "chats_list_not_ready_second")
                }
                
                // Wait for the second chat to appear in the list
                val secondChatFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            message2.take(20),
                            substring = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "second chat in list"
                )
                
                if (!secondChatFound) {
                    // If not found, take a screenshot of the current chat list
                    failWithScreenshot("Second chat not found in chats list", "second_chat_not_in_list")
                }
                
                // Click on the second chat using message text
                val secondNodes = composeTestRule.onAllNodesWithText(
                    message2.take(20),
                    substring = true
                )
                
                // Check if there are multiple nodes with the same text
                val secondNodeCount = secondNodes.fetchSemanticsNodes().size
                if (secondNodeCount > 1) {
                    failWithScreenshot(
                        "Found $secondNodeCount chats with the same text '${message2.take(20)}...' - expected only 1. This indicates duplicate chats.",
                        "duplicate_second_chat_in_list"
                    )
                }
                
                // Click the chat
                secondNodes[0].performClick()
                
                // Wait for bot response to sync in second chat
                Log.d(TAG, "⏳ Waiting for second chat bot response to sync...")
                val secondChatResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "fettuccine",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,  // Increased timeout to handle server processing time
                    description = "fettuccine response after reconnection"
                )
                
                if (!secondChatResponseFound) {
                    failWithScreenshot(
                        "Bot response 'fettuccine' did not sync in second chat after reconnection",
                        "second_chat_no_sync"
                    )
                }
                Log.d(TAG, "✅ Second chat response 'fettuccine' synced successfully")
                
                // Verify NO cross-contamination - should NOT see "coffee"
                val coffeeInSecondChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            "coffee",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 500L,
                    description = "coffee in second chat (should NOT be found)"
                )
                
                if (coffeeInSecondChat) {
                    failWithScreenshot(
                        "Cross-contamination: Found 'coffee' in second chat which should only contain fettuccine response",
                        "cross_contamination_coffee_in_fettuccine_chat"
                    )
                }
                Log.d(TAG, "✅ No cross-contamination in second chat")
                
                Log.d(TAG, "✅ Test passed: Messages synced correctly with no cross-contamination")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test failed with error: ${e.message}", e)
                throw e
            }
        }
    }

    @Test
    fun testMultiChatOfflineMessaging() {
        runBlocking {
            Log.d(TAG, "🧪 Starting multi-chat offline messaging test")
            
            try {
                // Initialize cleanup tracking
                createdNewChatThisTest = true
                
                // Capture initial chats before creating new ones
                val initialChats = repository.getAllChats()
                
                // Step 1: Launch app with manual launch mode
                Log.d(TAG, "📱 Step 1: Launching app in manual mode")
                
                if (!ComposeTestHelper.launchAppAndWaitForLoad(composeTestRule, isVoiceLaunch = false, packageName = packageName)) {
                    failWithScreenshot("App failed to launch using manual launch method", "app_launch_failed_multi_chat")
                }
                
                Log.d(TAG, "✅ App launched successfully in manual mode, should be on chats list")
                
                // Step 2: Navigate to new chat and start first chat
                Log.d(TAG, "📝 Step 2: Creating first chat")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat", "new_chat_navigation_failed_first")
                }
                
                val firstMessage = "Test ${System.currentTimeMillis()}: Pls respond with 1 word for the following test questions. What is the capital of France?"
                val sent1 = ComposeTestHelper.sendMessage(composeTestRule, firstMessage)
                if (!sent1) {
                    failWithScreenshot("Failed to send first message", "first_message_send_failed")
                }
                Log.d(TAG, "✅ Sent first message: $firstMessage")
                
                // Track the first chat ID
                var chatId1: Long? = null
                withTimeout(2000) {
                    while (true) {
                        val currentChats = repository.getAllChats()
                        val newChats = currentChats.filter { chat ->
                            !initialChats.map { it.id }.contains(chat.id)
                        }
                        if (newChats.isNotEmpty()) {
                            chatId1 = newChats.first().id
                            Log.d(TAG, "✅ First chat created with ID: $chatId1")
                            break
                        }
                        delay(100)
                    }
                }
                
                if (chatId1 == null) {
                    failWithScreenshot("First chat was not created", "first_chat_not_created")
                }
                createdChatIds.add(chatId1!!)
                
                // Step 3: Disconnect WebSocket
                Log.d(TAG, "🔌 Step 3: Disconnecting WebSocket...")
                whizServerRepository.disconnect(setPersistentDisconnect = true)
                
                try {
                    withTimeout(1000) {
                        while (whizServerRepository.isConnected()) {
                            delay(50)
                        }
                    }
                    Log.d(TAG, "✅ WebSocket disconnected")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("WebSocket failed to disconnect within 1 second", "websocket_disconnect_timeout_step3")
                }
                
                // Step 4: Send 3 messages while disconnected
                Log.d(TAG, "📝 Step 4: Sending 3 messages while disconnected...")
                
                val offlineMessages1 = listOf(
                    "Message 2: How tall is the Eiffel Tower?",
                    "Message 3: What is the population of Paris?",
                    "Message 4: What language do they speak there?"
                )
                
                for ((index, message) in offlineMessages1.withIndex()) {
                    val sent = ComposeTestHelper.sendMessage(composeTestRule, message)
                    if (!sent) {
                        failWithScreenshot("Failed to send offline message ${index + 2}", "offline_message_${index + 2}_failed")
                    }
                    Log.d(TAG, "✅ Sent offline message ${index + 2}: $message")
                }
                
                // Step 5: Go back to main chats list
                Log.d(TAG, "📱 Step 5: Going back to chats list...")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed_step5")
                }
                // Step 6: Reconnect WebSocket
                Log.d(TAG, "🔌 Step 6: Reconnecting WebSocket...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Verify we're on chats list
                val chatsListShown = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 3000L,
                    description = "chats list screen"
                )
                
                if (!chatsListShown) {
                    failWithScreenshot("Chats list did not appear", "chats_list_not_shown_step5")
                }
                
                
                // Step 7: Start another new chat
                Log.d(TAG, "📝 Step 7: Creating second chat")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat for second chat", "new_chat_navigation_failed_second")
                }
                
                val secondChatFirstMessage = "Test ${System.currentTimeMillis()}: Pls keep responses to 1 word. What is the capital of Italy?"
                val sent2 = ComposeTestHelper.sendMessage(composeTestRule, secondChatFirstMessage, rapid = true)
                if (!sent2) {
                    failWithScreenshot("Failed to send second chat first message", "second_chat_first_message_failed")
                }
                Log.d(TAG, "✅ Sent second chat first message: $secondChatFirstMessage")

                // Step 8: Disconnect WebSocket immediately to work with optimistic ID
                Log.d(TAG, "🔌 Step 8: Disconnecting WebSocket immediately after sending...")
                whizServerRepository.disconnect(setPersistentDisconnect = true)
                try {
                    withTimeout(1000) {
                        while (whizServerRepository.isConnected()) {
                            delay(50)
                        }
                    }
                    Log.d(TAG, "✅ WebSocket disconnected")
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("WebSocket failed to disconnect within 1 second in step 8", "websocket_disconnect_timeout_step8")
                }

                // Track the second chat ID after disconnecting
                // The chat was created when we sent the message, even though we're disconnected
                // We need to get it from local database since we're disconnected
                var chatId2: Long? = null
                try {
                    withTimeout(3000) {
                        while (true) {
                            val currentChats = repository.getChatDao().getAllChatsFlow().first()
                            val newChats = currentChats.filter { chat ->
                                !initialChats.map { it.id }.contains(chat.id) && chat.id != chatId1
                            }
                            if (newChats.isNotEmpty()) {
                                chatId2 = newChats.first().id
                                Log.d(TAG, "✅ Second chat created with ID: $chatId2 (optimistic: ${chatId2 < 0})")
                                break
                            }
                            delay(100)
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    failWithScreenshot("Second chat was not found within 3 seconds", "second_chat_not_found_timeout")
                }
                
                if (chatId2 == null) {
                    failWithScreenshot("Second chat was not created", "second_chat_not_created")
                }
                createdChatIds.add(chatId2!!)
                
                Log.d(TAG, "📝 Sending 3 messages to second chat while disconnected...")
                val offlineMessages2 = listOf(
                    "Message 2: How tall is the Colosseum",
                    "Message 3: What is the population of Rome?",
                    "Message 4: What pie-like food is Italy famous for?"
                )
                
                for ((index, message) in offlineMessages2.withIndex()) {
                    val sent = ComposeTestHelper.sendMessage(composeTestRule, message)
                    if (!sent) {
                        failWithScreenshot("Failed to send second chat offline message ${index + 2}", "second_chat_offline_message_${index + 2}_failed")
                    }
                    Log.d(TAG, "✅ Sent second chat offline message ${index + 2}: $message")
                }
                
                // Step 9: Go back to main chats list and reconnect
                Log.d(TAG, "📱 Step 9: Going back to chats list...")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed_step9")
                }
                
                // Verify we're on chats list
                val chatsListShown2 = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 3000L,
                    description = "chats list screen step 9"
                )
                
                if (!chatsListShown2) {
                    failWithScreenshot("Chats list did not appear in step 9", "chats_list_not_shown_step9")
                }
                
                Log.d(TAG, "🔌 Reconnecting WebSocket...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Step 10: Verify first chat messages are sent and responded to
                Log.d(TAG, "🔍 Step 10: Verifying first chat messages...")
                
                // Click on first chat
                val firstChatInList = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            firstMessage.take(20),
                            substring = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "first chat in list"
                )
                
                if (!firstChatInList) {
                    failWithScreenshot("First chat not found in list", "first_chat_not_in_list_verification")
                }
                
                composeTestRule.onNodeWithText(firstMessage.take(20), substring = true).performClick()
                
                // Wait for chat to load by checking for the unique message using content description
                val chatLoaded = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNode(
                            ComposeTestHelper.hasContentDescriptionMatching(".*User message:.*${Regex.escape(firstMessage.substringBefore(":"))}.*")
                        )
                    },
                    timeoutMs = 2000L,
                    description = "first chat loaded with unique timestamp"
                )
                if (!chatLoaded) {
                    failWithScreenshot("First chat did not load after clicking", "first_chat_not_loaded_after_click")
                }
                
                // Verify all user messages are present using content descriptions
                for (message in listOf(firstMessage) + offlineMessages1) {
                    // Use content description which is more reliable
                    val contentDesc = "User message: $message"
                    
                    val messageFound = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onNodeWithContentDescription(
                                contentDesc
                            )
                        },
                        timeoutMs = 3000L,
                        description = "user message with content description"
                    )
                    
                    if (!messageFound) {
                        // Fallback: Try with substring matching on the content description
                        val fallbackFound = ComposeTestHelper.waitForElement(
                            composeTestRule = composeTestRule,
                            selector = { 
                                composeTestRule.onNode(
                                    ComposeTestHelper.hasContentDescriptionMatching(".*User message:.*${Regex.escape(message.take(30))}.*")
                                )
                            },
                            timeoutMs = 1000L,
                            description = "user message with regex content description"
                        )
                        
                        if (!fallbackFound) {
                            failWithScreenshot("User message not found in first chat: ${message.take(50)}", "first_chat_missing_user_message")
                        }
                    }
                }
                Log.d(TAG, "✅ All user messages found in first chat")
                
                // Verify bot responses are present and correct for France questions
                Log.d(TAG, "🔍 Verifying France-specific bot responses...")
                
                // Expected responses for France chat (bot should respond with 1 word as requested)
                val expectedFranceResponses = listOf(
                    "Paris",     // Capital of France
                    "324",       // Eiffel Tower height (might be "324m" or just "324")
                    "2",         // Paris population (might be "2million" or just "2")
                    "French"     // Language
                )
                
                // Trigger recomposition with simplest possible action
                Log.d(TAG, "🔄 Triggering recomposition...")
                composeTestRule.onRoot().performClick()
                
                // Check for Paris response (should NOT contain Rome/Italy)
                val parisResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Paris",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,
                    description = "Paris response in first chat"
                )
                
                if (!parisResponseFound) {
                    // Get the actual chat ID for database dump
                    val actualChatId = try {
                        val localChats = repository.getChatDao().getAllChatsFlow().first()
                        val firstChat = localChats.find { chat -> 
                            chat.title?.contains("capital of France") == true ||
                            chat.title?.contains("Test message") == true
                        }
                        firstChat?.id ?: chatId1
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not determine chat ID for database dump: ${e.message}")
                        chatId1
                    }
                    
                    failWithDatabaseDump(
                        "Bot response 'Paris' not found in first chat",
                        "first_chat_no_paris_response",
                        chatId = actualChatId
                    )
                }
                
                // Check for French response
                val frenchResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: French",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 3000L,
                    description = "French language response"
                )
                
                if (!frenchResponseFound) {
                    Log.w(TAG, "⚠️ 'French' response not found - bot may have given a different answer")
                }
                
                // IMPORTANT: Check that Italy-related responses are NOT in this chat
                val romeInFirstChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Rome",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 1000L, // Short timeout - we expect NOT to find it
                    description = "Rome (should NOT be in France chat)"
                )
                
                if (romeInFirstChat) {
                    failWithScreenshot("❌ CROSS-CONTAMINATION: Found 'Rome' in France chat!", "cross_contamination_rome_in_france")
                }
                
                val pizzaInFirstChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Pizza",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 1000L, // Short timeout - we expect NOT to find it
                    description = "Pizza (should NOT be in France chat)"
                )
                
                if (pizzaInFirstChat) {
                    failWithScreenshot("❌ CROSS-CONTAMINATION: Found 'Pizza' in France chat!", "cross_contamination_pizza_in_france")
                }
                
                Log.d(TAG, "✅ France chat has correct responses with no Italy contamination")
                
                // Step 11: Verify second chat messages are sent and responded to
                Log.d(TAG, "🔍 Step 11: Verifying second chat messages...")
                
                // Go back to chats list
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back from first chat", "navigate_back_from_first_chat_failed")
                }
                
                // Click on second chat
                val secondChatInList = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            secondChatFirstMessage.take(20),
                            substring = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "second chat in list"
                )
                
                if (!secondChatInList) {
                    failWithScreenshot("Second chat not found in list", "second_chat_not_in_list_verification")
                }
                
                composeTestRule.onNodeWithText(secondChatFirstMessage.take(20), substring = true).performClick()
                
                // Wait for chat to load by checking for the first message using content description
                val secondChatLoaded = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNode(
                            ComposeTestHelper.hasContentDescriptionMatching(".*User message:.*${Regex.escape(secondChatFirstMessage.substringBefore(":"))}.*")
                        )
                    },
                    timeoutMs = 2000L,
                    description = "second chat loaded with first message"
                )
                if (!secondChatLoaded) {
                    failWithScreenshot("Second chat did not load after clicking", "second_chat_not_loaded_after_click")
                }
                
                // Verify all user messages are present in second chat using content descriptions
                for (message in listOf(secondChatFirstMessage) + offlineMessages2) {
                    // Use content description which is more reliable
                    val contentDesc = "User message: $message"
                    
                    val messageFound = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onNodeWithContentDescription(
                                contentDesc
                            )
                        },
                        timeoutMs = 3000L,
                        description = "user message with content description"
                    )
                    
                    if (!messageFound) {
                        // Fallback: Try with substring matching on the content description
                        val fallbackFound = ComposeTestHelper.waitForElement(
                            composeTestRule = composeTestRule,
                            selector = { 
                                composeTestRule.onNode(
                                    ComposeTestHelper.hasContentDescriptionMatching(".*User message:.*${Regex.escape(message.take(30))}.*")
                                )
                            },
                            timeoutMs = 1000L,
                            description = "user message with regex content description"
                        )
                        
                        if (!fallbackFound) {
                            failWithScreenshot("User message not found in second chat: ${message.take(50)}", "second_chat_missing_user_message")
                        }
                    }
                }
                Log.d(TAG, "✅ All user messages found in second chat")
                
                // Verify bot responses are present and correct for Italy questions
                Log.d(TAG, "🔍 Verifying Italy-specific bot responses...")
                
                // Expected responses for Italy chat (bot should respond with 1 word as requested)
                val expectedItalyResponses = listOf(
                    "Rome",      // Capital of Italy
                    "48",        // Colosseum height (might be "48m" or just "48")
                    "2.8",       // Rome population (might be "2.8million" or just "3")
                    "Pizza"      // Pie-like food
                )
                
                // Check for Rome response (should NOT contain Paris/France)
                val romeResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Rome",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,
                    description = "Rome response in second chat"
                )
                
                if (!romeResponseFound) {
                    // Get the actual chat ID for database dump
                    val actualChatId = try {
                        val localChats = repository.getChatDao().getAllChatsFlow().first()
                        val secondChat = localChats.find { chat -> 
                            chat.title?.contains("capital of Italy") == true ||
                            chat.title?.contains("following test questions") == true
                        }
                        secondChat?.id ?: chatId2
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not determine chat ID for database dump: ${e.message}")
                        chatId2
                    }
                    
                    failWithDatabaseDump(
                        "Bot response 'Rome' not found in second chat",
                        "second_chat_no_rome_response",
                        chatId = actualChatId
                    )
                }
                
                // Check for Pizza response
                val pizzaResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Pizza",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 3000L,
                    description = "Pizza response"
                )
                
                if (!pizzaResponseFound) {
                    Log.w(TAG, "⚠️ 'Pizza' response not found - bot may have given a different answer")
                }
                
                // IMPORTANT: Check that France-related responses are NOT in this chat
                val parisInSecondChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: Paris",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 1000L, // Short timeout - we expect NOT to find it
                    description = "Paris (should NOT be in Italy chat)"
                )
                
                if (parisInSecondChat) {
                    failWithScreenshot("❌ CROSS-CONTAMINATION: Found 'Paris' in Italy chat!", "cross_contamination_paris_in_italy")
                }
                
                val frenchInSecondChat = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithContentDescription(
                            "Assistant message: French",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 1000L, // Short timeout - we expect NOT to find it
                    description = "French (should NOT be in Italy chat)"
                )
                
                if (frenchInSecondChat) {
                    failWithScreenshot("❌ CROSS-CONTAMINATION: Found 'French' in Italy chat!", "cross_contamination_french_in_italy")
                }
                
                Log.d(TAG, "✅ Italy chat has correct responses with no France contamination")
                
                Log.d(TAG, "✅✅✅ TEST PASSED: Multi-chat offline messaging works correctly with no cross-contamination")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test failed with error: ${e.message}", e)
                throw e
            }
        }
    }
}