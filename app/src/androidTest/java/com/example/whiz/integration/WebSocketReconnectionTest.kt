package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
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
                        "ID 17542" // Timestamp prefix pattern
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
                        "ID 17542" // Timestamp prefix pattern
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
                // Then reconnect with flag reset to ensure clean state for next test
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                // Wait for connection
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
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
                val userMessage = "Test message ${System.currentTimeMillis()}. Tell me the history of the major name brand stacking brick?"
                
                // Send a message to create a chat
                val messageSent = ComposeTestHelper.sendMessage(composeTestRule, userMessage)
                if (!messageSent) {
                    failWithScreenshot("Failed to send initial message", "message_send_failed")
                }
                Log.d(TAG, "✅ Sent user message: $userMessage")
                
                // Track the newly created chat for cleanup
                var chatId: Long? = null
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
                withTimeout(5000) {
                    while (whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket disconnected")
                
                // Step 4: Send a second message while disconnected
                Log.d(TAG, "📝 Sending second message while disconnected...")
                val secondMessage = "hi hello are you there"
                
                val secondMessageSent = ComposeTestHelper.sendMessage(composeTestRule, secondMessage)
                if (!secondMessageSent) {
                    failWithScreenshot("Failed to send second message while disconnected", "second_message_send_failed")
                }
                Log.d(TAG, "✅ Sent second message while disconnected: $secondMessage")
                
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
                    repository.getMessagesForChat(chatId).first()
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
                
                // Step 6: Reconnect WebSocket
                Log.d(TAG, "🔌 Reconnecting WebSocket...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Wait for WebSocket to reconnect
                Log.d(TAG, "⏳ Waiting for WebSocket to reconnect...")
                withTimeout(10000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket reconnected")
                
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
                    timeoutMs = 15000L, // Give more time for sync
                    description = "bot response about LEGO history after reconnection"
                )
                
                if (!botResponseAfterReconnect) {
                    // Check if message is in database but not UI
                    val messagesAfterReconnect = if (chatId != null) {
                        repository.getMessagesForChat(chatId).first()
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
                
                Log.d(TAG, "✅ Test passed: Bot response synced and appeared after reconnection")
                
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
                withTimeout(5000) {
                    while (whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket disconnected after first message")
                
                // Get the first chat ID from local database since we're disconnected
                var chatId1: Long? = null
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
                
                // Manually reconnect WebSocket since we disconnected it
                Log.d(TAG, "🔌 Manually reconnecting WebSocket...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Wait for WebSocket to connect
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket reconnected")
                
                // Wait for the chat to appear in the list before navigating
                
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat for second message", "new_chat_navigation_failed_2")
                }
                
                // WebSocket should already be connected from our manual connection
                val connectedAfterNav = whizServerRepository.isConnected()
                Log.d(TAG, "📊 WebSocket connected after navigation: $connectedAfterNav")
                
                if (!connectedAfterNav) {
                    failWithScreenshot("WebSocket is not connected after manual connection and navigation", "websocket_not_connected")
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
                withTimeout(5000) {
                    while (whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket disconnected after second message")
                
                // Get the second chat ID from local database since we're disconnected
                var chatId2: Long? = null
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
                
                if (chatId2 == null) {
                    failWithScreenshot("Second chat was not created", "second_chat_not_created")
                }
                
                // Track the second chat for cleanup if it's different from the first
                if (chatId2 != chatId1 && !createdChatIds.contains(chatId2)) {
                    createdChatIds.add(chatId2)
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
                
                // Manually reconnect WebSocket since we disconnected it after second message
                Log.d(TAG, "🔌 Manually reconnecting WebSocket before opening first chat...")
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                
                // Wait for WebSocket to connect
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket reconnected")
                
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
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
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
}