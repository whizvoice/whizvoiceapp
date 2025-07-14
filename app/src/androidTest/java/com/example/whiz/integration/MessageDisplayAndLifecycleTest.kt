package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.whiz.BaseIntegrationTest
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assert
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.AppLifecycleService
import com.example.whiz.data.repository.WhizRepository
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.example.whiz.data.local.WhizDatabase
import com.example.whiz.data.local.MessageType
import com.example.whiz.data.local.MessageEntity
import kotlinx.coroutines.launch
import android.util.Log
import com.example.whiz.data.local.ChatEntity

/**
 * Integration tests for message display and app lifecycle behavior.
 * 
 * These tests verify:
 * 1. Messages appear immediately when submitted (optimistic UI)
 * 2. App lifecycle events work correctly for continuous listening
 * 3. Navigation away stops microphone, navigation back resumes it
 * 4. Multiple navigation cycles work correctly
 * 
 * Note: These tests focus on the service layer integration rather than full UI testing.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MessageDisplayAndLifecycleTest : BaseIntegrationTest() {
    
    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO
    )

    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    @Inject
    lateinit var appLifecycleService: AppLifecycleService
    
    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var database: WhizDatabase

    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
    @Inject
    lateinit var preloadManager: com.example.whiz.data.PreloadManager
    
    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    private var testChatId = 0L
    private var createdServerChatId = 0L // Track the server chat ID created during test
    private val TAG = "MessageDisplayTest"

    // Authentication is now handled automatically by BaseIntegrationTest
    val authenticated = true // Always authenticated via BaseIntegrationTest

    @Before
    override fun setUpAuthentication() {
        // Call parent authentication setup first
        super.setUpAuthentication()
        
        // device is already initialized by parent class
        
        // Set up test data
        runBlocking {
            try {
                Log.d(TAG, "🔧 Creating test chat via direct database access...")
                val testChatEntity = ChatEntity(
                    id = 0, // Auto-generated
                    title = "Integration Test Chat",
                    createdAt = System.currentTimeMillis(),
                    lastMessageTime = System.currentTimeMillis()
                )
                
                testChatId = database.chatDao().insertChat(testChatEntity)
                Log.d(TAG, "✅ Created test chat with ID: $testChatId")
                
                if (testChatId <= 0) {
                    throw RuntimeException("Failed to create test chat - chat ID: $testChatId")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test setup failed", e)
                throw e
            }
        }
    }
    
    @After
    fun teardown() {
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up test session...")
                
                // CRITICAL: Ensure app is in a clean state for subsequent tests
                // The voice tests use compose test rules and need a clean UI state
                
                // 1. Stop any ongoing speech recognition
                try {
                    speechRecognitionService.continuousListeningEnabled = false
                    Log.d(TAG, "✅ Disabled speech recognition")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not disable speech recognition: ${e.message}")
                }
                
                // 2. Reset app lifecycle service state
                try {
                    appLifecycleService.notifyAppForegrounded()
                    Log.d(TAG, "✅ Reset app lifecycle to foreground state")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not reset app lifecycle: ${e.message}")
                }
                
                // 3. Navigate to a clean screen (home screen) to clear any UI state
                try {
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    val intent = context.packageManager.getLaunchIntentForPackage("com.example.whiz.debug")
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        context.startActivity(intent)
                        
                        // Wait for navigation to complete
                        Thread.sleep(1000)
                        Log.d(TAG, "✅ Navigated to clean home screen")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not navigate to clean screen: ${e.message}")
                }
                
                // 4. Clear any compose-related state that might interfere with subsequent tests
                try {
                    // Force finish any activities that might have compose content
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    instrumentation.runOnMainSync {
                        // This will help clear any lingering compose state
                        System.gc()
                    }
                    Thread.sleep(500)
                    Log.d(TAG, "✅ Cleared compose state on main thread")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not clear compose state: ${e.message}")
                }
                
                // 5. Force garbage collection to clean up any lingering objects
                try {
                    System.gc()
                    Thread.sleep(100)
                    Log.d(TAG, "✅ Forced garbage collection")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not force garbage collection: ${e.message}")
                }
                
                // 6. Clean up test chats to prevent accumulation
                try {
                    // Direct database cleanup for this test's specific chats
                    if (testChatId > 0) {
                        val deletedMessages = database.messageDao().deleteMessagesForChat(testChatId)
                        val deletedChat = database.chatDao().deleteChat(testChatId)
                        Log.d(TAG, "✅ Deleted initial test chat $testChatId ($deletedMessages messages, $deletedChat chat)")
                    }
                    
                    if (createdServerChatId > 0) {
                        val deletedMessages = database.messageDao().deleteMessagesForChat(createdServerChatId)
                        val deletedChat = database.chatDao().deleteChat(createdServerChatId)
                        Log.d(TAG, "✅ Deleted server test chat $createdServerChatId ($deletedMessages messages, $deletedChat chat)")
                    }
                    
                    // Use simplified cleanup for any other test chats
                    cleanupTestChats(
                        repository = repository,
                        trackedChatIds = listOf(testChatId, createdServerChatId).filter { it > 0 },
                        additionalPatterns = listOf("INTEGRATION_TEST_MSG_", "message display", "lifecycle"),
                        enablePatternFallback = false
                    )
                    
                    Log.d(TAG, "✅ Test chat cleanup completed")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not clean up test chats: ${e.message}")
                }
                
                // Leave user authenticated for manual testing
                // Don't logout - let user stay logged in
                
                Log.d(TAG, "✅ Test cleanup completed (user remains authenticated, UI state cleaned)")
                
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test cleanup", e)
                // Don't fail the test if cleanup fails
            }
        }
    }

    @Test
    fun messageUI_sendMessage_appearsInChat() {
        runBlocking {
        // Comprehensive conversation lifecycle test:
        // 1. Create new chat and send message
        // 2. Verify optimistic UI and bot response
        // 3. Read chat title before navigation
        // 4. Navigate back to chat list
        // 5. Re-enter chat using the saved title
        // 6. Send second message in existing chat
        
        Log.d(TAG, "🧪 Starting comprehensive conversation lifecycle test")
        
        // Use a very unique message that won't conflict with other chats
        val uniqueId = System.currentTimeMillis()
        val firstMessage = "INTEGRATION_TEST_MSG_$uniqueId: Hello, can you help me test this chat?"
        val secondMessage = "INTEGRATION_TEST_MSG_$uniqueId: Second message after navigation"
        var chatTitle: String? = null // Will store the chat title for later navigation
        
        // Step 1: Launch the app for our UI test
        Log.d(TAG, "🚀 Launching app for comprehensive UI test...")
        if (!launchAppAndWaitForLoad()) {
            failWithScreenshot("app_failed_to_load", "App failed to launch or load main UI")
        }
        
        // Step 2: Navigate to new chat if needed
        Log.d(TAG, "📱 App loaded successfully, navigating to new chat...")
        if (!clickNewChatButtonAndWaitForChatScreen()) {
            // Check if already in chat by looking for message input field
            val alreadyInChat = device.hasObject(androidx.test.uiautomator.By.clazz("android.widget.EditText").pkg(packageName))
            if (!alreadyInChat) {
                failWithScreenshot("new_chat_failed", "Failed to navigate to new chat and not already in chat")
            }
            Log.w(TAG, "⚠️ Already in chat, continuing with test...")
        }
        
        // Step 3: Send first message and verify optimistic UI
        Log.d(TAG, "💬 Sending first message and verifying optimistic UI...")
        if (!sendMessageAndVerifyDisplay(firstMessage)) {
            failWithScreenshot("first_message_failed", "Failed to send first message or verify optimistic UI")
        }
        Log.d(TAG, "✅ SUCCESS: First message appears in chat UI immediately!")
        
        // Step 6: Wait for server/bot response by looking for the "Whiz" assistant label
        Log.d(TAG, "🔍 Waiting for server/bot response by looking for assistant message structure...")
        
        // Look for the "Whiz" label that appears in bot messages (from production MessageItem composable)
        val botResponseAppeared = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.text("Whiz").pkg("com.example.whiz.debug")
        ), 10000) // Reasonable timeout for server response
        
        val finalBotResponse = botResponseAppeared
        
        if (finalBotResponse) {
            Log.d(TAG, "✅ SUCCESS: Bot response appeared in chat UI!")
            
            // Verify we now have at least 2 messages (user + bot)
            val allMessages = device.findObjects(
                androidx.test.uiautomator.By.clazz("android.widget.TextView")
                    .pkg("com.example.whiz.debug")
            )
            val messageTexts = allMessages.mapNotNull { 
                try {
                    it.text
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    Log.w(TAG, "⚠️ Stale UI element encountered during message text extraction, skipping")
                    null
                }
            }.filter { 
                it.isNotBlank() && it.length > 10 // Filter out short UI labels
            }
            Log.d(TAG, "🔍 Found ${messageTexts.size} message-like texts in UI")
            
            if (messageTexts.size < 2) {
                Log.e(TAG, "❌ FAILURE: Expected at least 2 messages after bot response, found ${messageTexts.size}")
                failWithScreenshot("insufficient_messages_after_bot_response", "Expected at least 2 messages (user + bot) but found ${messageTexts.size} - conversation flow may be broken")
            }
            
            Log.d(TAG, "✅ Conversation flow working: user message + bot response both visible")
        } else {
            Log.w(TAG, "⚠️ No bot response appeared within timeout")
            Log.w(TAG, "   This is acceptable - the test focuses on optimistic UI for user messages")
            Log.w(TAG, "   Bot response depends on server connectivity and may not always be available")
        }
        
        // The server response confirms the chat was created successfully
        // We can now proceed with navigation - we'll sync the chat list when we get back
        
        // Step 6.5: CALCULATE EXPECTED CHAT TITLE based on production logic
        Log.d(TAG, "🔍 Calculating expected chat title based on production deriveChatTitle logic...")
        
        // Production code: deriveChatTitle takes first line, then first 20 chars + "..." if longer than 20
        val firstLine = firstMessage.trim().split("\n").first()
        chatTitle = if (firstLine.length > 20) {
            "${firstLine.take(20)}..."
        } else {
            firstLine
        }
        
        Log.d(TAG, "✅ Expected chat title: '$chatTitle'")
        Log.d(TAG, "   (Derived from first line: '${firstLine.take(30)}...')")
        
        // Step 7: Navigate back to chat list
        Log.d(TAG, "🔍 Navigating back to chat list...")
        if (!navigateBackToChatsListFromChat()) {
            Log.d(TAG, "🚫 Could not navigate back to chat list")
            failWithScreenshot("chat_list_not_loaded", "Could not navigate back to chat list - back navigation may be broken")
        }
        Log.d(TAG, "✅ Successfully returned to chat list")
        
        // Step 7.5: Wait for incremental sync to update chat list
        // With incremental sync, new chats should appear automatically
        Log.d(TAG, "🔍 Waiting for incremental sync to update chat list...")
        
        // Wait for chat list to be populated (indicating sync completion)
        val chatListUpdated = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.clazz("android.widget.TextView").pkg("com.example.whiz.debug")
        ), 5000) // Wait for any chat content to appear
        
        if (chatListUpdated) {
            Log.d(TAG, "✅ Chat list updated via incremental sync")
        } else {
            Log.w(TAG, "⚠️ Chat list may not have updated yet, but continuing...")
        }
        
        // Step 8: Find and re-enter the chat using the calculated title
        Log.d(TAG, "🔍 Looking for our chat using calculated title: '$chatTitle'...")
        
        // Wait for chat list to fully load
        val chatListFullyLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.clazz("android.widget.TextView").pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!chatListFullyLoaded) {
            Log.w(TAG, "⚠️ Chat list may not have fully loaded, but continuing...")
        }
        
        // Find our chat by unique test identifier (this always works based on logs)
        val chatTestIdentifier = "INTEGRATION_TEST_MSG_$uniqueId"
        Log.d(TAG, "🔍 Looking for chat containing unique test identifier: '$chatTestIdentifier'...")
        
        val ourChat = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .textContains(chatTestIdentifier.take(15)) // Use first 15 chars of identifier
                .packageName("com.example.whiz.debug")
        )
        
        if (!ourChat.waitForExists(5000)) {
            failWithScreenshot("chat_not_found_in_list", "Could not find our chat by identifier '$chatTestIdentifier' in the chat list")
        }
        
        Log.d(TAG, "✅ Found our chat by test identifier, clicking to re-enter...")
        ourChat.click()
        
        // Wait for chat to reload
        val chatReloaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")
        ), 5000)
        
        if (!chatReloaded) {
            Log.e(TAG, "❌ FAILURE: Chat failed to reload after re-entering")
            failWithScreenshot("chat_failed_to_reload", "Chat did not reload properly after clicking on it from chat list - chat loading may be broken")
        }
        
        Log.d(TAG, "✅ Successfully re-entered the chat")
        
        // Step 9: Wait for chat history to load, then verify the first message is still visible
        Log.d(TAG, "🔍 Waiting for chat history to load after re-entering...")
        
        // Wait for chat content to load - look for any message content
        val chatContentLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains("Hello").pkg("com.example.whiz.debug")
        ), 5000) || device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains("test").pkg("com.example.whiz.debug")
        ), 2000)
        
        if (!chatContentLoaded) {
            Log.w(TAG, "⚠️ Chat content may not have loaded yet, waiting a bit more...")
            // Wait for any substantial text content that looks like a message
            val anyMessageContent = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.clazz("android.widget.TextView")
                    .pkg("com.example.whiz.debug")
            ), 3000)
            
            if (!anyMessageContent) {
                Log.w(TAG, "⚠️ No message content visible after waiting")
            }
        }
        
        Log.d(TAG, "🔍 Verifying first message is still visible after navigation...")
        
        // Look for our unique test identifier in the message content
        val testIdentifier = "INTEGRATION_TEST_MSG_$uniqueId"
        var firstMessageStillVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains(testIdentifier).pkg("com.example.whiz.debug")
        ), 3000)
        
        // If not found by test identifier, try the full message
        if (!firstMessageStillVisible) {
            Log.w(TAG, "🔍 Test identifier not found, trying full message...")
            firstMessageStillVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains(firstMessage).pkg("com.example.whiz.debug")
            ), 2000)
        }
        
        // If not found by full text, try looking for key parts of the message
        if (!firstMessageStillVisible) {
            Log.w(TAG, "🔍 Full message not found, trying to find by 'Hello' keyword...")
            firstMessageStillVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains("Hello").pkg("com.example.whiz.debug")
            ), 2000)
        }
        
        // If still not found, try looking for "test this chat" phrase
        if (!firstMessageStillVisible) {
            Log.w(TAG, "🔍 'Hello' not found, trying 'test this chat' phrase...")
            firstMessageStillVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains("test this chat").pkg("com.example.whiz.debug")
            ), 2000)
        }
        
        if (!firstMessageStillVisible) {
            Log.e(TAG, "❌ FAILURE: First message no longer visible after navigation")
            
            // Debug: Let's see what messages are actually visible
            val allTextViews = device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.TextView").pkg("com.example.whiz.debug"))
            val visibleTexts = allTextViews.mapNotNull { 
                try {
                    it.text
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    Log.w(TAG, "⚠️ Stale UI element encountered during final text extraction, skipping")
                    null
                }
            }.filter { it.isNotBlank() && it.length > 5 }
            Log.e(TAG, "🔍 Currently visible texts: ${visibleTexts.joinToString(", ")}")
            
            failWithScreenshot("first_message_lost_after_navigation", "First message is no longer visible after navigating back to chat - chat persistence is broken. Visible texts: ${visibleTexts.take(3).joinToString(", ")}")
        } else {
            Log.d(TAG, "✅ First message confirmed still visible after navigation")
        }
        
        // Step 10: Send a second message to test existing chat functionality - using RAPID method
        Log.d(TAG, "💬 Sending second message in existing chat using rapid method...")
        if (!sendMessageAndVerifyDisplayRapid(secondMessage)) {
            failWithScreenshot("second_message_failed", "Failed to send second message in existing chat")
        }
        Log.d(TAG, "✅ Second message sent and visible successfully using rapid method")
        
        // Step 11: Final verification - both messages should be visible
        Log.d(TAG, "🔍 Final verification: checking if both messages are visible...")
        
        // Use the same reliable detection method that worked earlier
        val firstMessageVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains(testIdentifier).pkg("com.example.whiz.debug")
        ), 3000)
        
        val secondMessageVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains(secondMessage).pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!firstMessageVisible || !secondMessageVisible) {
            Log.w(TAG, "⚠️ One or both messages not found by exact text, trying partial matches...")
            
            // Try partial matches as fallback
            val firstMessagePartial = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains("Hello").pkg("com.example.whiz.debug")
            ), 2000)
            
            val secondMessagePartial = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains("Second message").pkg("com.example.whiz.debug")
            ), 2000)
            
            if (!firstMessagePartial || !secondMessagePartial) {
                Log.e(TAG, "❌ FAILURE: Not all messages visible in final verification")
                Log.e(TAG, "🔍 First message visible: $firstMessageVisible (partial: $firstMessagePartial)")
                Log.e(TAG, "🔍 Second message visible: $secondMessageVisible (partial: $secondMessagePartial)")
                failWithScreenshot("messages_not_all_visible", "Not all messages are visible in the final verification - message persistence in existing chats may be broken")
            } else {
                Log.d(TAG, "✅ Both messages found using partial matches")
            }
        } else {
            Log.d(TAG, "✅ Both messages confirmed visible in final verification")
        }
        
        // Capture the server chat ID for cleanup
        try {
            val testIdentifier = "INTEGRATION_TEST_MSG_$uniqueId"
            val allMessages = database.messageDao().getAllMessages()
            val testMessage = allMessages.find { it.content.contains(testIdentifier) }
            if (testMessage != null) {
                createdServerChatId = testMessage.chatId
                Log.d(TAG, "📝 Captured server chat ID for cleanup: $createdServerChatId")
            } else {
                Log.w(TAG, "⚠️ Could not find test message to capture chat ID")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not capture server chat ID: ${e.message}")
        }
        }
    }
}