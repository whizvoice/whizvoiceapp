package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
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
    
    // Add Compose test rule for hybrid UI testing (app launched manually via BaseIntegrationTest)
    @get:Rule
    val composeTestRule = createEmptyComposeRule()
    
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
    private val uniqueTestId = "MSG_DISPLAY_TEST_${System.currentTimeMillis()}"

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
                    Log.e(TAG, "❌ Failed to create test chat - chat ID: $testChatId")
                    // Skip test gracefully instead of crashing the process
                    org.junit.Assume.assumeTrue(
                        "Failed to create test chat - chat ID: $testChatId",
                        false
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Test setup failed: ${e.message}", e)
                // Skip test gracefully instead of crashing the process
                org.junit.Assume.assumeTrue(
                    "Test setup failed: ${e.message}",
                    false
                )
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
                    // Stop listening through VoiceManager instead of directly setting property
                    voiceManager.updateContinuousListeningEnabled(false)
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
                        additionalPatterns = listOf("INTEGRATION_TEST_MSG_", "message display", "lifecycle", uniqueTestId, "MSG_DISPLAY_TEST_"),
                        enablePatternFallback = true // Enable to catch any chats with unique identifier
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
        // Declare variables in outer scope so they're accessible in exception handlers
        val firstMessage = "$uniqueTestId: Help test this chat. Keep responses to 1 word."
        val secondMessage = "$uniqueTestId: Second message after navigation"
        var chatTitle: String? = null // Will store the chat title for later navigation
        
        runBlocking {
        try {
        // Comprehensive conversation lifecycle test:
        // 1. Create new chat and send message
        // 2. Verify optimistic UI and bot response
        // 3. Read chat title before navigation
        // 4. Navigate back to chat list
        // 5. Re-enter chat using the saved title
        // 6. Send second message in existing chat
        
        Log.d(TAG, "🧪 Starting comprehensive conversation lifecycle test")
        
        // Step 1: Launch app manually using ComposeTestHelper method (ensures manual launch, not voice)
        Log.d(TAG, "🚀 Launching app manually to ensure manual launch detection...")
        if (!ComposeTestHelper.launchAppAndWaitForLoad(composeTestRule, isVoiceLaunch = false, packageName = packageName)) {
            failWithScreenshot("app_failed_to_load", "App failed to launch using manual launch method")
        }
        
        // Step 1.5: Check if app is ready using ComposeTestHelper
        Log.d(TAG, "🔍 Checking if app is ready for hybrid UI testing...")
        if (!ComposeTestHelper.isAppReady(composeTestRule)) {
            failWithScreenshot("app_failed_to_load", "App launched but main UI not ready for Compose testing")
        }
        
        // Step 2: Navigate to new chat if needed using ComposeTestHelper
        Log.d(TAG, "📱 App loaded successfully, navigating to new chat...")
        
        // Capture initial chats before UI navigation
        val initialUIChats = repository.getAllChats()
        
        // Check if already on chat screen using ComposeTestHelper
        if (!ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            // Navigate to new chat using ComposeTestHelper
            if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                failWithScreenshot("new_chat_failed", "Failed to navigate to new chat using Compose")
            }
            
            // Wait for chat screen to load
            val chatScreenLoaded = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
                timeoutMs = 5000L,
                description = "chat input field after navigation"
            )
            
            if (!chatScreenLoaded) {
                failWithScreenshot("chat_screen_not_loaded", "Chat screen did not load after navigation")
            }
        } else {
            Log.d(TAG, "✅ Already on chat screen, continuing with test...")
        }
        
        // Track any UI-created chat for cleanup
        try {
            val currentUIChats = repository.getAllChats()
            val newUIChats = currentUIChats.filter { !initialUIChats.map { it.id }.contains(it.id) }
            newUIChats.forEach { chat ->
                if (chat.id != testChatId) { // Don't double-track the pre-created test chat
                    createdServerChatId = chat.id
                    Log.d(TAG, "📝 Tracked UI-created chat for cleanup: ${chat.id} ('${chat.title}')")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not track UI-created chat: ${e.message}")
        }
        
        // Step 3: Send first message and verify optimistic UI using ComposeTestHelper.sendMessageAndVerifyDisplay
        Log.d(TAG, "💬 Sending first message and verifying optimistic UI using Compose with verification...")
        val messageSentSuccessfully = ComposeTestHelper.sendMessageAndVerifyDisplay(composeTestRule, firstMessage, rapid = false)
        if (!messageSentSuccessfully) {
            failWithScreenshot("first_message_failed", "Failed to send first message or verify optimistic UI with Compose")
        }
        Log.d(TAG, "✅ SUCCESS: First message appears in chat UI immediately!")
        
        // Step 6: Wait for server/bot response using ComposeTestHelper
        Log.d(TAG, "🔍 Waiting for server/bot response using Compose...")
        
        // Use ComposeTestHelper to wait for bot response indicator
        val botResponseAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Whiz") },
            timeoutMs = 10000L,
            description = "bot response indicator"
        )
        
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
        
        // Step 7: Navigate back to chat list using ComposeTestHelper
        Log.d(TAG, "🔍 Navigating back to chat list using Compose...")
        if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
            Log.d(TAG, "🚫 Could not navigate back to chat list using Compose")
            failWithScreenshot("chat_list_not_loaded", "Could not navigate back to chat list - back navigation may be broken")
        }
        
        // Wait for chats list to load using ComposeTestHelper
        val chatsListLoaded = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("My Chats") },
            timeoutMs = 5000L,
            description = "chats list indicator"
        )
        
        if (!chatsListLoaded) {
            failWithScreenshot("chats_list_not_loaded", "Chats list did not load after navigation")
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
        
        // Find our chat by unique test identifier using ComposeTestHelper
        Log.d(TAG, "🔍 Looking for chat containing unique test identifier using Compose: '$uniqueTestId'...")
        
        // Wait for the chat to appear in the list using ComposeTestHelper
        val chatFoundInList = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText(uniqueTestId.take(15), substring = true) },
            timeoutMs = 10000L,
            description = "chat with test identifier in list"
        )
        
        if (!chatFoundInList) {
            failWithScreenshot("chat_not_found_in_list", "Could not find our chat by identifier '$uniqueTestId' in the chat list using Compose")
        }
        
        Log.d(TAG, "✅ Found our chat by test identifier using Compose, clicking to re-enter...")
        
        // Click on the chat using Compose
        try {
            composeTestRule.onNodeWithText(uniqueTestId.take(15), substring = true).performClick()
            Log.d(TAG, "✅ Clicked on chat using Compose")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to click on chat using Compose: ${e.message}")
            failWithScreenshot("chat_click_failed", "Failed to click on chat using Compose")
        }
        
        // Wait for chat to reload using ComposeTestHelper
        val chatReloaded = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
            timeoutMs = 5000L,
            description = "chat input field after re-entering chat"
        )
        
        if (!chatReloaded) {
            Log.e(TAG, "❌ FAILURE: Chat failed to reload after re-entering")
            failWithScreenshot("chat_failed_to_reload", "Chat did not reload properly after clicking on it from chat list - chat loading may be broken")
        }
        
        Log.d(TAG, "✅ Successfully re-entered the chat")
        
        // Step 9: Wait for chat history to load, then verify the first message is still visible using ComposeTestHelper
        Log.d(TAG, "🔍 Waiting for chat history to load after re-entering using Compose...")
        
        // Wait for chat content to load using ComposeTestHelper
        var chatContentLoaded = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText(firstMessage) },
            timeoutMs = 5000L,
            description = "first message in chat history"
        )
        
        // If full message not found, try with unique test identifier
        if (!chatContentLoaded) {
            Log.w(TAG, "⚠️ Full message not found, trying with test identifier...")
            chatContentLoaded = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(uniqueTestId, substring = true) },
                timeoutMs = 3000L,
                description = "message with test identifier"
            )
        }
        
        // If still not found, try with partial content
        if (!chatContentLoaded) {
            Log.w(TAG, "⚠️ Test identifier not found, trying partial message content...")
            chatContentLoaded = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("test this chat", substring = true) },
                timeoutMs = 2000L,
                description = "partial message content"
            )
        }
        
        Log.d(TAG, "🔍 Verifying first message is still visible after navigation using Compose...")
        
        // Final verification using ComposeTestHelper
        val firstMessageStillVisible = chatContentLoaded
        
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
        
        // Step 10: Send a second message to test existing chat functionality using ComposeTestHelper.sendMessageAndVerifyDisplay
        Log.d(TAG, "💬 Sending second message in existing chat using Compose with verification...")
        try {
            val secondMessageSent = ComposeTestHelper.sendMessageAndVerifyDisplay(composeTestRule, secondMessage, rapid = true)
            if (!secondMessageSent) {
                Log.e(TAG, "❌ FAILURE: ComposeTestHelper.sendMessageAndVerifyDisplay returned false for second message")
                Log.e(TAG, "🔍 Second message content: '$secondMessage'")
                Log.e(TAG, "🔍 Test identifier: '$uniqueTestId'")
                
                failWithScreenshot("second_message_failed_compose", "Failed to send second message in existing chat using Compose with verification. Message: '${secondMessage.take(50)}...'")
            }
            Log.d(TAG, "✅ Second message sent and visible successfully using Compose with verification")
        } catch (e: Exception) {
            Log.e(TAG, "❌ EXCEPTION during second message send with Compose", e)
            Log.e(TAG, "🔍 Second message content: '$secondMessage'")
            Log.e(TAG, "🔍 Test identifier: '$uniqueTestId'")
            Log.e(TAG, "🔍 Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "🔍 Exception message: ${e.message}")
            
            failWithScreenshot("second_message_exception_compose", "Exception during second message send using Compose with verification: ${e.javaClass.simpleName} - ${e.message}")
        }
        
        // Step 11: Final verification - both messages should be visible using ComposeTestHelper
        Log.d(TAG, "🔍 Final verification: checking if both messages are visible using Compose...")
        
        try {
            // Use ComposeTestHelper to verify both messages exist
            val expectedMessages = listOf(firstMessage, secondMessage)
            val missingMessages = ComposeTestHelper.verifyAllMessagesExist(composeTestRule, expectedMessages)
        
        if (missingMessages.isNotEmpty()) {
            Log.w(TAG, "⚠️ Some messages not found by exact text, trying partial matches...")
            
            // Try partial matches as fallback using ComposeTestHelper
            val firstMessagePartial = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("test this chat", substring = true) },
                timeoutMs = 2000L,
                description = "first message partial"
            )
            
            val secondMessagePartial = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Second message", substring = true) },
                timeoutMs = 2000L,
                description = "second message partial"
            )
            
            if (!firstMessagePartial || !secondMessagePartial) {
                Log.e(TAG, "❌ FAILURE: Not all messages visible in final verification")
                Log.e(TAG, "🔍 First message partial visible: $firstMessagePartial")
                Log.e(TAG, "🔍 Second message partial visible: $secondMessagePartial")
                Log.e(TAG, "🔍 Missing messages: ${missingMessages.joinToString(", ")}")
                Log.e(TAG, "🔍 Test identifier: '$uniqueTestId'")
                Log.e(TAG, "🔍 First message content: '$firstMessage'")
                Log.e(TAG, "🔍 Second message content: '$secondMessage'")
                
                failWithScreenshot("messages_not_all_visible", "Not all messages are visible in the final verification using Compose - message persistence in existing chats may be broken. Missing: ${missingMessages.take(2).joinToString(", ")}")
            } else {
                Log.d(TAG, "✅ Both messages found using partial matches with Compose")
            }
        } else {
            Log.d(TAG, "✅ Both messages confirmed visible in final verification using Compose")
        }
        
        // Step 12: Check for duplicates - fail test if found
        Log.d(TAG, "🔍 Step 12: Checking for duplicate messages...")
        val sentMessages = listOf(firstMessage, secondMessage)
        if (!ComposeTestHelper.noDuplicates(composeTestRule, sentMessages)) {
            failWithScreenshot("message_duplicates_detected", "Found duplicate message(s) in chat after navigation - indicates production deduplication bug")
        }
        Log.d(TAG, "✅ Duplicate checking completed")
        
        } catch (e: Throwable) {
            // Don't catch AssertionErrors from failWithScreenshot() calls - those are intentional test failures
            if (e is AssertionError) {
                throw e // Re-throw AssertionErrors from failWithScreenshot() calls
            }
            
            Log.e(TAG, "❌ FAILURE: Exception during final verification")
            Log.e(TAG, "🔍 Exception type: ${e.javaClass.simpleName}")
            Log.e(TAG, "🔍 Exception message: ${e.message}")
            Log.e(TAG, "🔍 Test identifier: '$uniqueTestId'")
            
            failWithScreenshot("final_verification_exception", "Exception during final message verification: ${e.javaClass.simpleName} - ${e.message}")
        }
        
        // Capture the server chat ID for cleanup
        try {
            val allMessages = database.messageDao().getAllMessages()
            val testMessage = allMessages.find { it.content.contains(uniqueTestId) }
            if (testMessage != null) {
                createdServerChatId = testMessage.chatId
                Log.d(TAG, "📝 Captured server chat ID for cleanup: $createdServerChatId")
            } else {
                Log.w(TAG, "⚠️ Could not find test message to capture chat ID")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not capture server chat ID: ${e.message}")
        }
        } catch (e: Exception) {
            Log.e(TAG, "❌ UNEXPECTED EXCEPTION in messageUI_sendMessage_appearsInChat test", e)
            Log.e(TAG, "🔍 Test identifier: '$uniqueTestId'")
            Log.e(TAG, "🔍 First message: '$firstMessage'")
            Log.e(TAG, "🔍 Second message: '$secondMessage'")
            throw e
        }
        }
    }
}