package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.After
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

    companion object {
        private const val TAG = "WebSocketReconnectionTest"
        private const val TEST_TIMEOUT = 30000L // 30 seconds for reconnection test
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        Log.d(TAG, "🧪 WebSocket Reconnection Test Setup Complete")
    }

    @After
    fun cleanup() {
        // Kill the app to ensure clean state for next test
        Log.d(TAG, "🔄 Killing app to ensure clean state")
        device.executeShellCommand("am force-stop $packageName")
        Thread.sleep(1000) // Give time for app to fully stop
    }

    @Test 
    fun testMessageSyncAfterDisconnection() {
        runBlocking {
            Log.d(TAG, "🧪 Starting WebSocket disconnection and reconnection test")
            
            try {
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
                
                // Get the created chat ID
                val chatId = repository.conversations.first().firstOrNull()?.id
                if (chatId == null) {
                    failWithScreenshot("Chat was not created after sending message", "chat_not_created")
                }
                Log.d(TAG, "✅ Chat created with ID: $chatId")
                
                // Step 3: Disconnect WebSocket immediately after sending message
                Log.d(TAG, "🔌 Disconnecting WebSocket immediately after sending message...")
                whizServerRepository.disconnect()
                
                // Wait for WebSocket to disconnect
                Log.d(TAG, "⏳ Waiting for WebSocket to disconnect...")
                withTimeout(5000) {
                    while (whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket disconnected")
                
                // Step 4: Verify bot response does NOT appear while disconnected
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
                        "Bot response about LEGO appeared while WebSocket was disconnected - this is a bug!",
                        "bot_response_while_disconnected"
                    )
                }
                
                // Also check database to ensure no bot messages yet
                val messagesWhileDisconnected = repository.getMessagesForChat(chatId!!).first()
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
                
                // Step 5: Reconnect WebSocket
                Log.d(TAG, "🔌 Reconnecting WebSocket...")
                whizServerRepository.connect()
                
                // Wait for WebSocket to reconnect
                Log.d(TAG, "⏳ Waiting for WebSocket to reconnect...")
                withTimeout(10000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
                Log.d(TAG, "✅ WebSocket reconnected")
                
                // Step 6: Wait for bot response to appear after reconnection
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
                    val messagesAfterReconnect = repository.getMessagesForChat(chatId).first()
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
            Log.d(TAG, "🧪 Starting no double sync on chat switch test")
            
            try {
                // Step 1: Launch app with manual launch mode
                Log.d(TAG, "📱 Step 1: Launching app in manual mode")
                
                if (!ComposeTestHelper.launchAppAndWaitForLoad(composeTestRule, isVoiceLaunch = false, packageName = packageName)) {
                    failWithScreenshot("App failed to launch using manual launch method", "app_launch_failed_test2")
                }
                
                // We should be on chats list for manual launch
                Log.d(TAG, "✅ App launched successfully in manual mode")
                
                // Navigate to new chat for first message
                Log.d(TAG, "Navigating to new chat for first message")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat", "new_chat_navigation_failed")
                }
                
                // Create first chat
                val message1 = "Please keep responses to 1 word for test. Most popular caffeinated drink? Test ID ${System.currentTimeMillis()}"
                val sent1 = ComposeTestHelper.sendMessage(composeTestRule, message1)
                if (!sent1) {
                    failWithScreenshot("Failed to send first message", "first_message_send_failed")
                }
                
                // Wait for bot response to appear in the first chat
                Log.d(TAG, "⏳ Waiting for bot response in first chat...")
                val firstChatResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        // Look for "coffee" response
                        composeTestRule.onNodeWithText(
                            "coffee",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,
                    description = "bot response in first chat"
                )
                
                if (!firstChatResponseFound) {
                    failWithScreenshot(
                        "Bot response did not appear in UI for first chat within 10 seconds",
                        "first_chat_no_bot_response"
                    )
                }
                
                // Give a moment for the messages to be saved to the database
                delay(1000)
                
                // Wait for the chat to be created in the repository
                var chatId1: Long? = null
                val chatCreated = withTimeout(5000) {
                    while (true) {
                        // Use getAllChats() to force a fresh fetch from the database
                        val chats = repository.getAllChats()
                        if (chats.isNotEmpty()) {
                            chatId1 = chats.firstOrNull()?.id
                            Log.d(TAG, "Chat created with ID: $chatId1, title: ${chats.firstOrNull()?.title}")
                            true
                            break
                        }
                        Log.d(TAG, "Waiting for chat to be created... current count: ${chats.size}")
                        delay(200)
                    }
                }
                
                if (chatId1 == null) {
                    failWithScreenshot("First chat was not created", "first_chat_not_created")
                }
                
                // Verify messages were saved
                val firstChatMessages = repository.getMessagesForChat(chatId1!!).first()
                Log.d(TAG, "First chat has ${firstChatMessages.size} messages after bot response")
                if (firstChatMessages.size < 2) { // Should have user message + bot response
                    failWithScreenshot(
                        "First chat messages not properly saved. Found ${firstChatMessages.size} messages",
                        "first_chat_messages_not_saved"
                    )
                }
                
                // Create second chat by going home and sending another message
                // Navigate back to chats list
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list for second chat", "navigate_back_failed_2")
                }
                
                // Wait for chats list to be displayed
                val chatsListReady = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 5000L,
                    description = "chats list screen"
                )
                
                if (!chatsListReady) {
                    failWithScreenshot("Chats list did not appear after navigation", "chats_list_not_ready")
                }
                
                // Navigate to new chat for second message
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    failWithScreenshot("Failed to navigate to new chat for second message", "new_chat_navigation_failed_2")
                }
                
                val message2 = "Second test chat with ID ${System.currentTimeMillis()}. Please keep responses to one word. What's the type of pasta commonly eaten with alfredo sauce?"
                val sent2 = ComposeTestHelper.sendMessage(composeTestRule, message2)
                if (!sent2) {
                    failWithScreenshot("Failed to send second message", "second_message_send_failed")
                }
                
                // Wait for bot response in second chat
                Log.d(TAG, "⏳ Waiting for bot response in second chat...")
                val secondChatResponseFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        // Look for "fettuccine" response
                        composeTestRule.onNodeWithText(
                            "fettuccine",
                            substring = true,
                            ignoreCase = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 10000L,
                    description = "bot response in second chat"
                )
                
                if (!secondChatResponseFound) {
                    failWithScreenshot(
                        "Bot response did not appear in UI for second chat within 10 seconds",
                        "second_chat_no_bot_response"
                    )
                }
                
                // Give a moment for the messages to be saved to the database
                delay(1000)
                
                // Get the second chat ID (should be the newest one)
                val chats = repository.getAllChats()
                if (chats.size < 2) {
                    failWithScreenshot("Expected at least 2 chats but found ${chats.size}", "insufficient_chats")
                }
                val chatId2 = chats.first().id // Most recent chat
                Log.d(TAG, "Second chat created with ID: $chatId2")
                
                // Verify second chat messages were saved
                val secondChatMessages = repository.getMessagesForChat(chatId2).first()
                Log.d(TAG, "Second chat has ${secondChatMessages.size} messages after bot response")
                if (secondChatMessages.size < 2) { // Should have user message + bot response
                    failWithScreenshot(
                        "Second chat messages not properly saved. Found ${secondChatMessages.size} messages",
                        "second_chat_messages_not_saved"
                    )
                }
                
                // Step 2: Count messages before switching
                val messagesBeforeSwitch = repository.getMessagesForChat(chatId1!!).first().size
                Log.d(TAG, "Chat 1 has $messagesBeforeSwitch messages before switch")
                
                // Step 3: Navigate back to first chat
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list", "navigate_back_failed_3")
                }
                
                // Wait for chats list to appear
                val chatsListReady2 = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("My Chats") },
                    timeoutMs = 5000L,
                    description = "chats list screen (2nd time)"
                )
                
                if (!chatsListReady2) {
                    failWithScreenshot("Chats list did not appear after second navigation", "chats_list_not_ready_2")
                }
                
                // Click on the first chat (it should be the second item in the list now)
                val firstChatTitle = chats.find { it.id == chatId1 }?.title ?: message1.take(30)
                composeTestRule.onNodeWithText(
                    firstChatTitle,
                    substring = true,
                    useUnmergedTree = true
                ).performClick()
                
                // Step 4: Wait for chat to load and verify no duplicate messages
                // Wait for the first chat's content to be displayed
                val chatLoaded = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            message1,
                            substring = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "first chat content"
                )
                
                if (!chatLoaded) {
                    failWithScreenshot("First chat content did not load after switching back", "chat_content_not_loaded")
                }
                
                // Give a small amount of time for any potential double sync to occur
                delay(1000)
                
                val messagesAfterSwitch = repository.getMessagesForChat(chatId1).first().size
                Log.d(TAG, "Chat 1 has $messagesAfterSwitch messages after switch")
                
                // Messages should not have duplicated
                if (messagesAfterSwitch != messagesBeforeSwitch) {
                    failWithScreenshot(
                        "Message count changed after chat switch. Before: $messagesBeforeSwitch, After: $messagesAfterSwitch",
                        "double_sync_detected"
                    )
                }
                
                // Step 5: Check for cross-contamination
                Log.d(TAG, "🔍 Checking for cross-contamination between chats...")
                
                // Check that first chat (coffee) doesn't contain "fettuccine"
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
                    timeoutMs = 500L, // Short timeout - we expect NOT to find it
                    description = "fettuccine in first chat (should NOT be found)"
                )
                
                if (fettuccineInFirstChat) {
                    failWithScreenshot(
                        "Cross-contamination detected: Found 'fettuccine' in first chat which should only contain coffee response",
                        "cross_contamination_fettuccine_in_coffee_chat"
                    )
                }
                
                // Now navigate to second chat to check the reverse
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chats list for cross-contamination check", "navigate_back_failed_4")
                }
                
                // Click on the second chat (should be the first item in the list)
                val secondChatTitle = chats.find { it.id == chatId2 }?.title ?: message2.take(30)
                composeTestRule.onNodeWithText(
                    secondChatTitle,
                    substring = true,
                    useUnmergedTree = true
                ).performClick()
                
                // Wait for second chat to load
                val secondChatLoaded = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText(
                            message2,
                            substring = true,
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 5000L,
                    description = "second chat content"
                )
                
                if (!secondChatLoaded) {
                    failWithScreenshot("Second chat content did not load for cross-contamination check", "second_chat_not_loaded")
                }
                
                // Check that second chat (fettuccine) doesn't contain "coffee"
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
                    timeoutMs = 500L, // Short timeout - we expect NOT to find it
                    description = "coffee in second chat (should NOT be found)"
                )
                
                if (coffeeInSecondChat) {
                    failWithScreenshot(
                        "Cross-contamination detected: Found 'coffee' in second chat which should only contain fettuccine response",
                        "cross_contamination_coffee_in_fettuccine_chat"
                    )
                }
                
                Log.d(TAG, "✅ No cross-contamination detected between chats")
                
                Log.d(TAG, "✅ Test passed: No double sync on chat switch")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test failed with error: ${e.message}", e)
                throw e
            }
        }
    }
}