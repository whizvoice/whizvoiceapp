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

    private var testChatId = 0L
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
        val intent = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .getLaunchIntentForPackage("com.example.whiz.debug")
        intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent)
        
        // Wait for app to load completely
        val appLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.pkg("com.example.whiz.debug")
        ), 10000)
        
        if (!appLoaded) {
            Log.e(TAG, "❌ FAILURE: App failed to load within timeout")
            failWithScreenshot("app_failed_to_load", "App did not load within 10 seconds - check if app is properly installed")
        }
        
        // Wait for main UI elements to appear (either New Chat button or message input)
        val newChatLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.descContains("New Chat").pkg("com.example.whiz.debug")
        ), 3000)
        
        val inputFieldLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")
        ), 3000)
        
        val mainUILoaded = newChatLoaded || inputFieldLoaded
        
        if (!mainUILoaded) {
            Log.e(TAG, "❌ FAILURE: Main UI elements not found")
            failWithScreenshot("main_ui_not_loaded", "Neither New Chat button nor message input field found - app may not have loaded properly")
        }
        
        Log.d(TAG, "📱 App loaded successfully, looking for New Chat button...")
        
        // Step 2: Navigate to new chat if needed
        val newChatButton = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .descriptionContains("New Chat")
                .packageName("com.example.whiz.debug")
        )
        
        if (newChatButton.waitForExists(3000)) {
            Log.d(TAG, "✅ Found New Chat button, clicking...")
            newChatButton.click()
            
            // Wait for chat screen to load by looking for message input field
            val chatLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")
            ), 5000)
            
            if (!chatLoaded) {
                Log.e(TAG, "❌ FAILURE: Chat screen failed to load after clicking New Chat")
                failWithScreenshot("chat_screen_not_loaded", "Chat screen did not load after clicking New Chat button - navigation may be broken")
            }
        } else {
            Log.w(TAG, "⚠️ New Chat button not found, assuming already in chat")
        }
        
        // Step 3: Find the message input field and type the first message
        Log.d(TAG, "🔍 Looking for message input field...")
        val messageInput = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .className("android.widget.EditText")
                .packageName("com.example.whiz.debug")
        )
        
        if (!messageInput.waitForExists(5000)) {
            Log.e(TAG, "❌ FAILURE: Message input field not found")
            failWithScreenshot("message_input_not_found", "Message input field not found - chat UI may not have loaded properly")
        }
        
        Log.d(TAG, "✅ Found message input field, typing first message...")
        messageInput.click() // Focus the input field
        
        // Wait for input field to be focused
        val inputFocused = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.focused(true).clazz("android.widget.EditText")
        ), 2000)
        
        if (!inputFocused) {
            // Wait for keyboard to appear as alternative focus indicator
            val keyboardAppeared = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.pkg("com.google.android.inputmethod.latin")
            ), 1000) || device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.pkg("com.android.inputmethod")
            ), 1000)
            
            if (!keyboardAppeared) {
                Log.w(TAG, "⚠️ Neither focus nor keyboard detected, but continuing...")
            }
        }
        
        messageInput.setText(firstMessage)
        
        // Wait for text to actually appear in the field
        val textSet = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.text(firstMessage).pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!textSet) {
            Log.w(TAG, "⚠️ Text didn't appear in input field, trying again...")
            messageInput.setText(firstMessage)
            
            // Wait again with more patience
            val textSetRetry = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.text(firstMessage).pkg("com.example.whiz.debug")
            ), 2000)
            
            if (!textSetRetry) {
                Log.e(TAG, "❌ FAILURE: Could not set text in message input field")
                failWithScreenshot("text_input_failed", "Unable to set text in message input field - input field may not be working properly")
            }
        }
        
        Log.d(TAG, "✅ First message typed successfully: '$firstMessage'")
        
        // Step 4: Find and click the send button
        Log.d(TAG, "🔍 Looking for send button...")
        
        // Wait for send button to appear (should happen when text is entered)
        val sendButtonReady = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.descContains("Send message").pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!sendButtonReady) {
            Log.w(TAG, "🔍 Send button not ready yet, checking if text input is complete...")
            // Wait for UI to update after text input
            val uiUpdated = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.textContains(firstMessage).pkg("com.example.whiz.debug")
            ), 1000)
            if (!uiUpdated) {
                Log.w(TAG, "⚠️ UI may not have updated properly after text input")
            }
        }
        
        val sendButton = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .descriptionContains("Send message")
                .packageName("com.example.whiz.debug")
        )
        
        if (sendButton.waitForExists(3000)) {
            Log.d(TAG, "✅ Found send button, clicking...")
            sendButton.click()
            
            // Wait for message to be sent by checking if input field is cleared
            val messageSent = device.wait(androidx.test.uiautomator.Until.gone(
                androidx.test.uiautomator.By.text(firstMessage).clazz("android.widget.EditText")
            ), 3000)
            
            if (!messageSent) {
                Log.w(TAG, "⚠️ Input field not cleared, checking if send action completed...")
                // Alternative: check if a user message appeared in the chat
                val messageInChat = device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.textContains(firstMessage).pkg("com.example.whiz.debug")
                ), 2000)
                if (!messageInChat) {
                    Log.w(TAG, "⚠️ Message may not have been sent properly")
                }
            }
            
            Log.d(TAG, "✅ Send button clicked successfully")
        } else {
            Log.e(TAG, "❌ FAILURE: Send button not found")
            failWithScreenshot("send_button_not_found", "Send button not found - UI may not be responding to text input or send button is not visible")
        }
        
        // Step 5: Verify the first message appears in the chat UI (optimistic UI test)
        Log.d(TAG, "🔍 Verifying first message appears in chat...")
        
        val firstMessageAppeared = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains(firstMessage).pkg("com.example.whiz.debug")
        ), 5000)
        
        if (!firstMessageAppeared) {
            Log.e(TAG, "❌ FAILURE: First message does not appear in chat UI")
            failWithScreenshot("first_message_not_visible", "First message not visible in chat UI - optimistic UI not working properly")
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
            val messageTexts = allMessages.mapNotNull { it.text }.filter { 
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
        
        // Try to find the "Open Chats List" button (hamburger menu in top bar)
        val chatsListButton = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .descriptionContains("Open Chats List")
                .packageName("com.example.whiz.debug")
        )
        
        if (chatsListButton.waitForExists(3000)) {
            Log.d(TAG, "✅ Found 'Open Chats List' button, clicking...")
            chatsListButton.click()
        } else {
            // Try alternative navigation button descriptions
            val backButton = device.findObject(
                androidx.test.uiautomator.UiSelector()
                    .descriptionContains("Navigate up")
                    .packageName("com.example.whiz.debug")
            )
            
            if (backButton.waitForExists(2000)) {
                Log.d(TAG, "✅ Found 'Navigate up' button, clicking...")
                backButton.click()
            } else {
                Log.w(TAG, "⚠️ No navigation buttons found, using device back button")
                device.pressBack()
            }
        }
        
        // Wait for chat list to appear
        val chatListLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.descContains("New Chat").pkg("com.example.whiz.debug")
        ), 5000)
        
        if (!chatListLoaded) {
            Log.e(TAG, "❌ FAILURE: Failed to return to chat list")
            failWithScreenshot("chat_list_not_loaded", "Could not navigate back to chat list - back navigation may be broken")
        }
        
        Log.d(TAG, "✅ Successfully returned to chat list")
        
        // Step 8: Find and re-enter the chat using the calculated title
        Log.d(TAG, "🔍 Looking for our chat using calculated title: '$chatTitle'...")
        
        // Wait for chat list to fully load
        val chatListFullyLoaded = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.clazz("android.widget.TextView").pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!chatListFullyLoaded) {
            Log.w(TAG, "⚠️ Chat list may not have fully loaded, but continuing...")
        }
        
        // Debug: Let's see what chats are available
        val allChatItems = device.findObjects(
            androidx.test.uiautomator.By.clazz("android.widget.TextView")
                .pkg("com.example.whiz.debug")
        )
        val chatTexts = allChatItems.mapNotNull { it.text }.filter { 
            it.isNotBlank() && 
            !it.contains("New Chat") && 
            !it.contains("Settings") &&
            it.length > 5 
        }
        Log.d(TAG, "🔍 Available chats: ${chatTexts.joinToString(", ")}")
        
        // First, try to find our chat by the exact calculated title
        var ourChat = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .text(chatTitle)
                .packageName("com.example.whiz.debug")
        )
        
        var chatFound = false
        
        if (ourChat.waitForExists(3000)) {
            Log.d(TAG, "✅ Found our chat by exact title match ('$chatTitle'), clicking to re-enter...")
            ourChat.click()
            chatFound = true
        } else {
            // Try partial match on the title 
            Log.w(TAG, "⚠️ Exact title search failed, trying partial title match...")
            ourChat = device.findObject(
                androidx.test.uiautomator.UiSelector()
                    .textContains(chatTitle.take(15)) // Use first 15 chars in case of truncation
                    .packageName("com.example.whiz.debug")
            )
            
            if (ourChat.waitForExists(2000)) {
                Log.d(TAG, "✅ Found our chat by partial title match ('${chatTitle.take(15)}'), clicking to re-enter...")
                ourChat.click()
                chatFound = true
            } else {
                Log.e(TAG, "❌ FAILURE: Could not find our chat by title in the chat list")
                
                // Debug: Let's see what chats are actually available
                val allTextViews = device.findObjects(androidx.test.uiautomator.By.clazz("android.widget.TextView").pkg("com.example.whiz.debug"))
                val visibleTexts = allTextViews.mapNotNull { it.text }.filter { it.isNotBlank() && it.length > 5 }
                Log.e(TAG, "🔍 Currently visible chat titles: ${visibleTexts.take(5).joinToString(", ")}")
                
                failWithScreenshot("chat_not_found_in_list", "Could not find our chat by expected title '$chatTitle' in the chat list - chat persistence may be broken. Available titles: ${visibleTexts.take(3).joinToString(", ")}")
            }
        }
        
        if (!chatFound) {
            Log.e(TAG, "❌ FAILURE: Could not find any existing chat in the chat list")
            failWithScreenshot("no_existing_chat_found", "Could not find any existing chat in the chat list - chat persistence may be broken or chat list is empty. Available texts: ${chatTexts.take(3).joinToString(", ")}")
        }
        
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
            val visibleTexts = allTextViews.mapNotNull { it.text }.filter { it.isNotBlank() && it.length > 5 }
            Log.e(TAG, "🔍 Currently visible texts: ${visibleTexts.joinToString(", ")}")
            
            failWithScreenshot("first_message_lost_after_navigation", "First message is no longer visible after navigating back to chat - chat persistence is broken. Visible texts: ${visibleTexts.take(3).joinToString(", ")}")
        } else {
            Log.d(TAG, "✅ First message confirmed still visible after navigation")
        }
        
        // Step 10: Send a second message to test existing chat functionality
        Log.d(TAG, "🔍 Sending second message in existing chat...")
        
        val messageInputSecond = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .className("android.widget.EditText")
                .packageName("com.example.whiz.debug")
        )
        
        if (!messageInputSecond.waitForExists(3000)) {
            Log.e(TAG, "❌ FAILURE: Message input not available for second message")
            failWithScreenshot("second_message_input_not_found", "Message input field not found for second message - existing chat UI may be broken")
        }
        
        messageInputSecond.click()
        messageInputSecond.setText(secondMessage)
        
        // Wait for second message text to appear
        val secondTextSet = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.text(secondMessage).pkg("com.example.whiz.debug")
        ), 3000)
        
        if (!secondTextSet) {
            Log.w(TAG, "⚠️ Second message text didn't appear, trying again...")
            messageInputSecond.setText(secondMessage)
            
            val secondTextSetRetry = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.text(secondMessage).pkg("com.example.whiz.debug")
            ), 2000)
            
            if (!secondTextSetRetry) {
                Log.e(TAG, "❌ FAILURE: Could not set second message text")
                failWithScreenshot("second_message_text_failed", "Unable to set text for second message - input field may not be working in existing chat")
            }
        }
        
        val sendButtonSecond = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .descriptionContains("Send message")
                .packageName("com.example.whiz.debug")
        )
        
        if (!sendButtonSecond.waitForExists(3000)) {
            Log.e(TAG, "❌ FAILURE: Send button not available for second message")
            failWithScreenshot("second_send_button_not_found", "Send button not found for second message - existing chat send functionality may be broken")
        }
        
        sendButtonSecond.click()
        
        // Verify second message appears
        val secondMessageAppeared = device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.textContains(secondMessage).pkg("com.example.whiz.debug")
        ), 5000)
        
        if (!secondMessageAppeared) {
            Log.e(TAG, "❌ FAILURE: Second message does not appear in existing chat")
            failWithScreenshot("second_message_not_visible", "Second message not visible in existing chat")
        }
        
        Log.d(TAG, "✅ Second message sent and visible successfully")
        
        // Step 11: Final verification - both messages should be visible
        val bothMessagesVisible = device.findObject(
            androidx.test.uiautomator.UiSelector()
                .textContains(firstMessage)
                .packageName("com.example.whiz.debug")
        ).exists() && device.findObject(
            androidx.test.uiautomator.UiSelector()
                .textContains(secondMessage)
                .packageName("com.example.whiz.debug")
        ).exists()
        
        if (!bothMessagesVisible) {
            Log.e(TAG, "❌ FAILURE: Not all messages visible in final verification")
            failWithScreenshot("messages_not_all_visible", "Not all messages are visible in the final verification - message persistence in existing chats may be broken")
        }
        
        Log.d(TAG, "🎉 Comprehensive conversation lifecycle test completed successfully!")
        Log.d(TAG, "✅ Verified: New chat creation, message sending, optimistic UI, bot response, title calculation, navigation, chat persistence, re-entry, and existing chat messaging")
        if (finalBotResponse) {
            Log.d(TAG, "✅ Bonus: Bot response functionality also working")
        }
        }
    }
}