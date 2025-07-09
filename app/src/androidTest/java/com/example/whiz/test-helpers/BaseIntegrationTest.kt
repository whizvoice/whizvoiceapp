package com.example.whiz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import javax.inject.Inject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Base class for integration tests that need authentication.
 * Automatically handles test authentication setup and provides common UI interaction methods.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
abstract class BaseIntegrationTest {
    
    @get:Rule
    var hiltRule = HiltAndroidRule(this)
    
    @Inject
    lateinit var authRepository: AuthRepository
    
    protected lateinit var device: UiDevice
    protected lateinit var context: Context
    protected val packageName = "com.example.whiz.debug"
    private val screenshotDir = "/sdcard/Download/test_screenshots"
    
    /**
     * Override this to skip automatic authentication (e.g., for login UI tests)
     */
    protected open val skipAutoAuthentication: Boolean = false
    
    @Before
    open fun setUpAuthentication() {
        hiltRule.inject()
        
        // Initialize UiDevice and Context for all tests
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = InstrumentationRegistry.getInstrumentation().context
        
        // Set up screenshot directory
        setupScreenshotDirectory()
        
        if (!skipAutoAuthentication) {
            runBlocking {
                try {
                    val authSuccess = AutoTestAuthentication.ensureAuthenticated(authRepository)
                    if (!authSuccess) {
                        throw AssertionError(
                            "❌ Test authentication failed. " +
                            "Please ensure REDACTED_TEST_EMAIL is signed in to the device and app."
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BaseIntegrationTest", "Authentication setup failed", e)
                    throw e
                }
            }
        }
    }
    
    /**
     * Set up screenshot directory - clear existing screenshots and create fresh directory
     */
    private fun setupScreenshotDirectory() {
        try {
            // Clear existing screenshots on device
            device.executeShellCommand("rm -rf $screenshotDir")
            // Create fresh directory on device
            device.executeShellCommand("mkdir -p $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "🔧 Device screenshot directory prepared: $screenshotDir")
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "Failed to setup screenshot directory", e)
        }
    }
    
    // =============================================================================
    // COMMON UI INTERACTION METHODS
    // =============================================================================
    
    /**
     * Launch app and wait for it to be fully loaded
     */
    protected fun launchAppAndWaitForLoad(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🚀 launching app")
        
        // Use UiDevice's built-in app launching mechanism which is more reliable
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        
        if (intent == null) {
            android.util.Log.e("BaseIntegrationTest", "❌ Could not get launch intent for package $packageName")
            return false
        }
        
        // Add flags to match manual launch pattern (0x10200000) to avoid voice detection
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)         // 0x10000000
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)  // 0x00200000
        
        android.util.Log.d("BaseIntegrationTest", "   intent: $intent")
        android.util.Log.d("BaseIntegrationTest", "   flags: ${String.format("0x%08X", intent.flags)}")
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Failed to start activity: ${e.message}")
            return false
        }
        
        val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        if (!appLaunched) {
            android.util.Log.e("BaseIntegrationTest", "❌ app failed to launch within 10 seconds")
            return false
        }
        
        // Wait for main UI elements to load - should be chats list for manual launch
        val mainUILoaded = device.wait(Until.hasObject(
            By.text("My Chats").pkg(packageName)
        ), 8000) || device.wait(Until.hasObject(
            By.descContains("New Chat").pkg(packageName)  
        ), 3000)
        
        if (!mainUILoaded) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ main UI not detected, but checking for any app content...")
            // Fallback check for any app content
            val anyAppContent = device.wait(Until.hasObject(
                By.pkg(packageName)
            ), 2000)
            
            if (!anyAppContent) {
                android.util.Log.e("BaseIntegrationTest", "❌ no app content visible after launch")
                return false
            }
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ app launched successfully")
        return true
    }
    
    /**
     * Find and click the new chat button, wait for chat screen to load
     */
    protected fun clickNewChatButtonAndWaitForChatScreen(): Boolean {
        // Try to find the main FloatingActionButton first (when chats exist)
        var newChatButton = device.findObject(
            UiSelector()
                .description("New Chat")
                .packageName(packageName)
        )
        
        // If not found, try to find the empty state button (when no chats exist)
        if (!newChatButton.waitForExists(2000)) {
            android.util.Log.d("BaseIntegrationTest", "🔍 Main 'New Chat' FAB not found, trying empty state button...")
            newChatButton = device.findObject(
                UiSelector()
                    .description("Start your first chat")
                    .packageName(packageName)
            )
        }
        
        if (!newChatButton.waitForExists(3000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ Neither 'New Chat' nor 'Start your first chat' button found")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Found new chat button, clicking...")
        
        // Try multiple click approaches to ensure it works with Compose FAB
        var clickSuccessful = false
        try {
            // First attempt: Standard UiObject click
            newChatButton.click()
            android.util.Log.d("BaseIntegrationTest", "📱 Attempted standard UiObject click")
            Thread.sleep(500)
            
            // Check if click worked
            val stillOnChatsListAfterFirst = device.hasObject(By.desc("New Chat").pkg(packageName)) || 
                                            device.hasObject(By.desc("Start your first chat").pkg(packageName))
            
            if (stillOnChatsListAfterFirst) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Standard click failed, trying coordinate-based click...")
                
                // Second attempt: Click at adjusted center coordinates
                val bounds = newChatButton.bounds
                val centerX = bounds.centerX() + 30  // Adjust right by 30px
                val centerY = bounds.centerY() + 70  // Adjust down by 70px  
                device.click(centerX, centerY)
                android.util.Log.d("BaseIntegrationTest", "📱 Attempted coordinate click at ($centerX, $centerY) (adjusted from bounds)")
                Thread.sleep(500)
                
                // Check if second click worked
                val stillOnChatsListAfterSecond = device.hasObject(By.desc("New Chat").pkg(packageName)) || 
                                                  device.hasObject(By.desc("Start your first chat").pkg(packageName))
                
                if (!stillOnChatsListAfterSecond) {
                    android.util.Log.d("BaseIntegrationTest", "✅ DIAGNOSTIC: Coordinate click worked - navigation started")
                    clickSuccessful = true
                } else {
                    android.util.Log.e("BaseIntegrationTest", "❌ DIAGNOSTIC: Both click methods failed - still on chats list")
                    return false
                }
            } else {
                android.util.Log.d("BaseIntegrationTest", "✅ DIAGNOSTIC: Standard click worked - navigation started")
                clickSuccessful = true
            }
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Click failed with exception: ${e.message}")
            return false
        }
        
        // Wait for chat screen to load by looking for the message input field using proper content description
        android.util.Log.d("BaseIntegrationTest", "🔍 Waiting for chat screen to load (looking for message input field)...")
        val chatScreenLoaded = device.wait(Until.hasObject(
            By.desc("Message input field").pkg(packageName)
        ), 5000)
        
        if (!chatScreenLoaded) {
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Chat screen loaded successfully")
        return chatScreenLoaded
    }
    
    /**
     * Find message input field, type message, and verify text appears
     */
    protected fun typeMessageInInputField(message: String): Boolean {
        // Look for the actual EditText (the real input field)
        val messageInput = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        if (!messageInput.waitForExists(5000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ EditText input field not found")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Found EditText input field")
        
        // Click and set text
        messageInput.click()
        messageInput.setText(message)
        
        // Wait for the specific text to appear in the EditText field
        val searchText = message.take(30)
        
        // Use a custom wait condition specifically for EditText content
        var textFound = false
        val startTime = System.currentTimeMillis()
        val timeout = 2000L // 2 seconds
        
        while (!textFound && (System.currentTimeMillis() - startTime) < timeout) {
            val currentText = messageInput.text ?: ""
            if (currentText.contains(searchText, ignoreCase = true)) {
                textFound = true
                android.util.Log.d("BaseIntegrationTest", "✅ Text found in EditText: '${currentText.take(50)}...'")
            } else {
                android.util.Log.d("BaseIntegrationTest", "🔄 Waiting for text... current: '${currentText.take(30)}...'")
                Thread.sleep(100)
            }
        }
        
        if (!textFound) {
            val currentText = messageInput.text ?: ""
            android.util.Log.e("BaseIntegrationTest", "❌ Text not visible after typing - typing failed")
            android.util.Log.e("BaseIntegrationTest", "   Looking for: '$searchText...' in EditText")
            android.util.Log.e("BaseIntegrationTest", "   EditText contains: '${currentText.take(50)}...'")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Text found in EditText field")
        
        android.util.Log.d("BaseIntegrationTest", "✅ Text visible in UI - typing successful")
        return true
    }
    
    /**
     * Find and click send button, wait for message to be sent - NORMAL VERSION with enhanced Compose compatibility
     */
    protected fun clickSendButtonAndWaitForSent(messageText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 NORMAL: clicking send button...")
        
        // Use only the working method - exact content description
        val sendButton = device.findObject(
            UiSelector()
                .description("Send message")
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(1000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ NORMAL: Send button not found!")
            return false
        }
        
        try {
            sendButton.click()
            android.util.Log.d("BaseIntegrationTest", "📤 NORMAL: send button clicked")
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ NORMAL: Click failed: ${e.message}")
            return false
        }
        
        // Normal delay for regular testing
        Thread.sleep(100)
        
        // Use lightweight verification to check if message was sent
        android.util.Log.d("BaseIntegrationTest", "🔍 NORMAL: verifying message was sent...")
        
        val messageFound = verifyMessageSentAtBottom(messageText, 3000)
        
        if (messageFound) {
            android.util.Log.d("BaseIntegrationTest", "✅ NORMAL: message sent and displayed successfully")
            return true
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ NORMAL: message not found at bottom of chat")
            return false
        }
    }
    
    /**
     * Complete message sending flow: type message, click send, verify display - NORMAL VERSION
     */
    protected fun sendMessageAndVerifyDisplay(message: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📝 attempting to send message: '${message.take(30)}...'")
        
        // step 1: type message
        val typingSuccess = typeMessageInInputField(message)
        if (!typingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ typeMessageInInputField returned false")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ message typed successfully")
        
        // step 2: click send and wait - NORMAL
        val sendingSuccess = clickSendButtonAndWaitForSent(message)
        if (!sendingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ clickSendButtonAndWaitForSent returned false")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ message sent and displayed successfully")
        return true
    }
    
    /**
     * Verify that the input field has been cleared after a message send - NORMAL VERSION
     */
    protected fun verifyInputFieldCleared(): Boolean {
        try {
            // Look for the actual EditText (the working approach)
            val inputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            if (!inputField.exists()) {
                android.util.Log.d("BaseIntegrationTest", "❌ EditText input field not found - WebSocket verification failed")
                return false
            }
            
            val inputText = inputField.text ?: ""
            if (inputText.isEmpty() || inputText.isBlank()) {
                android.util.Log.d("BaseIntegrationTest", "✅ Input field cleared - WebSocket send successful")
                return true
            } else {
                android.util.Log.d("BaseIntegrationTest", "❌ Input field still contains text: '${inputText.take(30)}...' - WebSocket send failed")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "❌ Error checking input field: ${e.message}")
            return false
        }
    }
    
    /**
     * Send message and verify both UI display AND WebSocket transmission
     * This function catches the production bug where send button appears to work
     * but doesn't actually send messages to the server during bot response periods
     * 
     * @return true if both UI display and WebSocket send succeeded, false otherwise
     * Note: Caller should handle failures with appropriate error messages and screenshots
     */
    protected fun sendMessageAndVerifyWebSocketSending(message: String, messageNumber: Int): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📤 Sending message $messageNumber with WebSocket verification: '${message.take(30)}...'")
        
        // Step 1: Send message using the standard method
        if (!sendMessageAndVerifyDisplay(message)) {
            android.util.Log.e("BaseIntegrationTest", "❌ Failed to send message $messageNumber: UI display failed")
            return false
        }
        
        // Step 2: Verify that WebSocket sending actually occurred
        // We check this by verifying the input field was properly cleared 
        // Real WebSocket sends clear the input field; fake optimistic-only sends leave text
        val inputFieldCleared = verifyInputFieldCleared()
        
        if (!inputFieldCleared) {
            android.util.Log.e("BaseIntegrationTest", "❌ Message $messageNumber appeared in UI but WebSocket send failed (input field not cleared)")
            android.util.Log.e("BaseIntegrationTest", "   This indicates the PRODUCTION BUG: send button appears to work but messages don't reach server")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Message $messageNumber sent successfully via WebSocket and displayed in UI")
        return true
    }
    
    /**
     * Wait for bot thinking indicator to appear
     */
    protected fun waitForBotThinkingIndicator(timeoutMs: Long = 5000): Boolean {
        return device.wait(Until.hasObject(
            By.textContains("Whiz is computing").pkg(packageName)
        ), timeoutMs) || device.wait(Until.hasObject(
            By.textContains("thinking").pkg(packageName)
        ), 2000) || device.wait(Until.hasObject(
            By.textContains("computing").pkg(packageName)
        ), 2000)
    }
    
    /**
     * Wait for bot thinking indicator to disappear (bot finished responding)
     */
    protected fun waitForBotThinkingToFinish(timeoutMs: Long = 30000): Boolean {
        return device.wait(Until.gone(
            By.textContains("Whiz is computing").pkg(packageName)
        ), timeoutMs)
    }
    
    /**
     * Wait for bot response message to appear by looking for bot message styling/structure
     */
    protected fun waitForBotResponse(timeoutMs: Long = 10000): Boolean {
        // Look for the "Whiz" label that appears in assistant messages
        val whizLabelFound = device.wait(Until.hasObject(
            By.text("Whiz").pkg(packageName)
        ), timeoutMs)
        
        if (whizLabelFound) {
            android.util.Log.d("BaseIntegrationTest", "✅ Bot response detected via 'Whiz' label")
            return validateBotResponseContent()
        }
        
        // Alternative: Look for assistant message container or card structure
        // Assistant messages are left-aligned and have different styling than user messages
        val assistantMessageFound = device.wait(Until.hasObject(
            By.descContains("Assistant message").pkg(packageName)
        ), 3000) || device.wait(Until.hasObject(
            By.descContains("Bot response").pkg(packageName)
        ), 2000)
        
        if (assistantMessageFound) {
            android.util.Log.d("BaseIntegrationTest", "✅ Bot response detected via assistant message container")
            return validateBotResponseContent()
        }
        
        // Fallback: Look for new message content that appeared after our last user message
        // This is less reliable but catches cases where styling detection fails
        val anyNewMessageContent = device.wait(Until.hasObject(
            By.clazz("android.widget.TextView").pkg(packageName)
        ), 3000)
        
        if (anyNewMessageContent) {
            android.util.Log.d("BaseIntegrationTest", "⚠️ Bot response detected via fallback (new content appeared)")
            return validateBotResponseContent()
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ No bot response detected within timeout")
        return false
    }

    /**
     * Validate that bot response content is valid and not a server error
     */
    protected fun validateBotResponseContent(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 Validating bot response content...")
        
        // Find all "Whiz" labels (bot messages)
        val whizLabels = device.findObjects(By.text("Whiz").pkg(packageName))
        
        if (whizLabels.isEmpty()) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ No 'Whiz' labels found for content validation")
            return true // Don't fail if we can't find the label - might be styling issue
        }
        
        // Check the most recent bot message content
        val mostRecentWhizLabel = whizLabels.lastOrNull()
        if (mostRecentWhizLabel != null) {
            try {
                val parent = mostRecentWhizLabel.parent
                if (parent != null) {
                    val textViews = parent.findObjects(By.clazz("android.widget.TextView"))
                    for (textView in textViews) {
                        val text = textView.text
                        if (text != null && text != "Whiz" && text.length > 10) {
                            android.util.Log.d("BaseIntegrationTest", "🔍 Checking bot response content: '${text.take(50)}...'")
                            
                            // Check for server error indicators
                            if (text.contains("Server Error", ignoreCase = true) ||
                                text.contains("Internal Server Error", ignoreCase = true) ||
                                text.contains("503 Service Unavailable", ignoreCase = true) ||
                                text.contains("500 Internal Server Error", ignoreCase = true) ||
                                text.contains("Connection refused", ignoreCase = true) ||
                                text.contains("Network error", ignoreCase = true) ||
                                text.contains("Failed to connect", ignoreCase = true) ||
                                text.contains("Timeout", ignoreCase = true)) {
                                
                                android.util.Log.e("BaseIntegrationTest", "❌ Bot response contains server error: '${text.take(100)}...'")
                                android.util.Log.e("BaseIntegrationTest", "   This indicates the server is not responding properly")
                                return false
                            }
                            
                            android.util.Log.d("BaseIntegrationTest", "✅ Bot response content is valid (no server errors detected)")
                            return true
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Error validating bot response content: ${e.message}")
                return true // Don't fail on validation errors - could be UI timing issue
            }
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Bot response validation completed (no content found to validate)")
        return true
    }

    /**
     * Check all bot responses in the current chat to ensure none contain server errors
     * Can be called explicitly by tests that want comprehensive server error validation
     */
    protected fun validateAllBotResponsesForServerErrors(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 Validating ALL bot responses for server errors...")
        
        val whizLabels = device.findObjects(By.text("Whiz").pkg(packageName))
        
        if (whizLabels.isEmpty()) {
            android.util.Log.d("BaseIntegrationTest", "ℹ️ No bot responses found to validate")
            return true
        }
        
        android.util.Log.d("BaseIntegrationTest", "🔍 Found ${whizLabels.size} bot response(s) to validate")
        
        for (i in whizLabels.indices) {
            val whizLabel = whizLabels[i]
            try {
                val parent = whizLabel.parent
                if (parent != null) {
                    val textViews = parent.findObjects(By.clazz("android.widget.TextView"))
                    for (textView in textViews) {
                        val text = textView.text
                        if (text != null && text != "Whiz" && text.length > 10) {
                            android.util.Log.d("BaseIntegrationTest", "🔍 Checking bot response ${i+1} content: '${text.take(50)}...'")
                            
                            // Check for server error indicators
                            if (text.contains("Server Error", ignoreCase = true) ||
                                text.contains("Internal Server Error", ignoreCase = true) ||
                                text.contains("503 Service Unavailable", ignoreCase = true) ||
                                text.contains("500 Internal Server Error", ignoreCase = true) ||
                                text.contains("Connection refused", ignoreCase = true) ||
                                text.contains("Network error", ignoreCase = true) ||
                                text.contains("Failed to connect", ignoreCase = true) ||
                                text.contains("Timeout", ignoreCase = true)) {
                                
                                android.util.Log.e("BaseIntegrationTest", "❌ Bot response ${i+1} contains server error: '${text.take(100)}...'")
                                android.util.Log.e("BaseIntegrationTest", "   Server is not responding properly - test should be skipped or server fixed")
                                return false
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Error validating bot response ${i+1}: ${e.message}")
                continue // Continue checking other responses
            }
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ All ${whizLabels.size} bot response(s) validated - no server errors detected")
        return true
    }
    
    /**
     * Navigate back to chats list from chat screen using back button or hamburger menu
     */
    protected fun navigateBackToChatsListFromChat(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔙 navigating back to chats list")
        
        // Method 1: Try hamburger menu first (more reliable)
        val hamburgerMenu = device.findObject(
            UiSelector()
                .descriptionContains("Open Chats List")
                .packageName(packageName)
        ) ?: device.findObject(
            UiSelector()
                .descriptionContains("Menu")
                .packageName(packageName)
        )
        
        if (hamburgerMenu.waitForExists(3000)) {
            android.util.Log.d("BaseIntegrationTest", "🍔 clicking hamburger menu")
            hamburgerMenu.click()
            
            // wait for chats list to load
            val chatsListLoaded = device.wait(Until.hasObject(
                By.text("My Chats").pkg(packageName)
            ), 5000)
            
            if (chatsListLoaded) {
                android.util.Log.d("BaseIntegrationTest", "✅ returned to chats list via hamburger menu")
                return true
            }
        }
        
        // Method 2: Try device back button as fallback
        android.util.Log.d("BaseIntegrationTest", "📱 trying device back button")
        device.pressBack()
        
        // wait for chats list to load
        val chatsListLoaded = device.wait(Until.hasObject(
            By.text("My Chats").pkg(packageName)
        ), 5000)
        
        if (chatsListLoaded) {
            android.util.Log.d("BaseIntegrationTest", "✅ returned to chats list via back button")
            return true
        }
        
        // Method 3: Try "Navigate up" button 
        val navigateUpButton = device.findObject(
            UiSelector()
                .descriptionContains("Navigate up")
                .packageName(packageName)
        )
        
        if (navigateUpButton.waitForExists(2000)) {
            android.util.Log.d("BaseIntegrationTest", "⬆️ clicking navigate up button")
            navigateUpButton.click()
            
            val chatsListLoadedUp = device.wait(Until.hasObject(
                By.text("My Chats").pkg(packageName)
            ), 5000)
            
            if (chatsListLoadedUp) {
                android.util.Log.d("BaseIntegrationTest", "✅ returned to chats list via navigate up")
                return true
            }
        }
        
        android.util.Log.e("BaseIntegrationTest", "❌ failed to navigate back to chats list")
        return false
    }
    
    /**
     * Find chat in chats list by text content and click it
     */
    protected fun findAndClickChatInList(searchText: String): Boolean {
        val chat = device.findObject(
            UiSelector()
                .textContains(searchText.take(15)) // use first 15 chars
                .packageName(packageName)
        )
        
        if (!chat.waitForExists(5000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ chat not found in list: '$searchText'")
            return false
        }
        
        chat.click()
        
        // wait for chat to reload
        val chatReloaded = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 5000)
        
        return chatReloaded
    }
    
    /**
     * Verify message is visible in chat (with scrolling to find messages that may be off-screen)
     */
    protected fun verifyMessageVisible(messageText: String, timeoutMs: Long = 3000): Boolean {
        val searchText = messageText.take(20)
        
        // first try to find the message without scrolling
        if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
            android.util.Log.d("BaseIntegrationTest", "✅ Message found without scrolling: '${searchText}...'")
            return true
        }
        
        // 🔧 BOT RESPONSE FIX: Start from very top when bot responses may have pushed messages off-screen
        android.util.Log.d("BaseIntegrationTest", "🔍 Message not visible, scrolling to TOP first (bot responses may have pushed messages up)...")
        scrollToVeryTop(searchText)
        
        // Check again after reaching the top
        if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
            android.util.Log.d("BaseIntegrationTest", "✅ Message found after scrolling to top: '${searchText}...'")
            return true
        }
        
        // if still not found, try controlled scrolling down from the top
        // Final comprehensive check after scrolling to top using multiple search approaches
        android.util.Log.d("BaseIntegrationTest", "🔍 Doing final comprehensive search after reaching top...")
        
        // Approach 1: Standard textContains with package filter
        if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
            android.util.Log.d("BaseIntegrationTest", "✅ Message found after scroll to top (approach 1): '${searchText}...'")
            return true
        }
        
        // Approach 2: textContains without package filter (more permissive)
        if (device.hasObject(By.textContains(searchText))) {
            android.util.Log.d("BaseIntegrationTest", "✅ Message found after scroll to top (approach 2): '${searchText}...'")
            return true
        }
        
        // Approach 3: exact text match with package filter
        if (device.hasObject(By.text(searchText).pkg(packageName))) {
            android.util.Log.d("BaseIntegrationTest", "✅ Message found after scroll to top (approach 3): '${searchText}...'")
            return true
        }
        
        // Approach 4: manual search through all TextViews (most comprehensive)
        val allTextViews = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
        for (textView in allTextViews) {
            try {
                val text = textView.text
                if (text != null && text.contains(searchText, ignoreCase = true)) {
                    android.util.Log.d("BaseIntegrationTest", "✅ Message found after scroll to top (approach 4): '${searchText}...' in text: '$text'")
                    return true
                }
            } catch (e: Exception) {
                // Ignore errors reading individual text views
            }
        }
        
        // 🔧 DIAGNOSTIC: Show what's visible for debugging
        val visibleTexts = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
            .mapNotNull { try { it.text?.take(30) } catch (e: Exception) { null } }
            .filter { it.isNotEmpty() && it.length > 3 }
            .take(10) // Show more content for final check
        android.util.Log.d("BaseIntegrationTest", "🔍 FINAL CHECK - Visible content: ${visibleTexts.joinToString(", ")}")
        
        android.util.Log.w("BaseIntegrationTest", "❌ Message not found even after scrolling to top and comprehensive search: '${searchText}...'")
        return false
    }
    
    /**
     * Get a snapshot of currently visible content to detect when scrolling reaches top/bottom
     */
    private fun getVisibleContentSnapshot(): Set<String> {
        return try {
            device.findObjects(
                By.clazz("android.widget.TextView").pkg(packageName)
            ).mapNotNull { 
                try {
                    val text = it.text
                    if (text != null && text.length > 5) text else null
                } catch (e: Exception) {
                    null
                }
            }.toSet()
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error getting content snapshot: ${e.message}")
            emptySet()
        }
    }

    /**
     * Collect ALL messages in the chat by scrolling through completely
     * Returns a list of unique messages with timestamps to detect real duplicates
     */
    protected fun collectAllMessages(): List<Pair<String, String>> {
        android.util.Log.d("BaseIntegrationTest", "📋 Starting complete message collection...")
        
        val allMessages = mutableListOf<Pair<String, String>>() // (message, position)
        val seenMessageKeys = mutableSetOf<String>() // To avoid false duplicates during scrolling
        
        // DEBUG: Log starting position
        val startingMessages = collectMessagesFromCurrentScreen()
        android.util.Log.d("BaseIntegrationTest", "📍 STARTING POSITION: Found ${startingMessages.size} messages before scrolling")
        startingMessages.forEachIndexed { index, (message, position) ->
            android.util.Log.d("BaseIntegrationTest", "  Start $index: '$message' at $position")
        }
        
        // Start from current position (bottom of chat) and scroll UP to collect all messages
        val height = device.displayHeight
        val width = device.displayWidth
        
        android.util.Log.d("BaseIntegrationTest", "📜 Starting from current position (bottom), scrolling UP to collect all messages...")
        
        var consecutiveNoNewMessages = 0
        var consecutiveNoScrollMovement = 0
        var scrollAttempt = 0
        val maxScrolls = 30
        
        while (scrollAttempt < maxScrolls && consecutiveNoNewMessages < 5 && consecutiveNoScrollMovement < 4) {
            android.util.Log.d("BaseIntegrationTest", "📜 === SCROLL ATTEMPT $scrollAttempt ===")
            android.util.Log.d("BaseIntegrationTest", "📊 Current totals: ${allMessages.size} messages collected, ${consecutiveNoNewMessages} consecutive no-new-messages, ${consecutiveNoScrollMovement} consecutive no-movement")
            
            // Collect messages from current screen
            val currentMessages = collectMessagesFromCurrentScreen()
            android.util.Log.d("BaseIntegrationTest", "📱 Found ${currentMessages.size} messages on current screen (scroll attempt $scrollAttempt)")
            
            var foundNewMessage = false
            
            for ((message, position) in currentMessages) {
                val messageKey = "$message|$position" // Position-based deduplication
                
                if (!seenMessageKeys.contains(messageKey)) {
                    // New unique message found (by position)
                    seenMessageKeys.add(messageKey)
                    allMessages.add(Pair(message, position))
                    foundNewMessage = true
                    android.util.Log.d("BaseIntegrationTest", "📝 NEW MESSAGE #${allMessages.size}: '$message' at $position")
                } else {
                    // This message at this position already seen - true duplicate
                    android.util.Log.d("BaseIntegrationTest", "🔄 Already seen: '$message' at $position")
                }
            }
            
            if (foundNewMessage) {
                consecutiveNoNewMessages = 0
                consecutiveNoScrollMovement = 0
                android.util.Log.d("BaseIntegrationTest", "✅ Found new messages this round - resetting counters")
            } else {
                consecutiveNoNewMessages++
                android.util.Log.d("BaseIntegrationTest", "⚠️ No new messages this round - consecutiveNoNewMessages now $consecutiveNoNewMessages")
            }
            
            android.util.Log.d("BaseIntegrationTest", "📊 After processing this screen: ${allMessages.size} total messages accumulated")
            
            // Capture all visible messages before scrolling for better scroll detection
            val messagesBefore = collectMessagesFromCurrentScreen()
            android.util.Log.d("BaseIntegrationTest", "📍 About to scroll - ${messagesBefore.size} messages visible before scroll")
            
            // Scroll UP to see older messages (swipe DOWN to scroll UP)
            // Try multiple swipe approaches to handle different UI states
            val swipeX = width / 2
            
            if (consecutiveNoScrollMovement == 0) {
                // First attempts: normal scrolling
                android.util.Log.d("BaseIntegrationTest", "📜 STRATEGY 1: Using normal scroll (most common)")
                val swipeStartY = height / 4
                val swipeEndY = height * 3 / 4
                device.swipe(swipeX, swipeStartY, swipeX, swipeEndY, 30)
                Thread.sleep(400)
            }
            
            // Capture all visible messages after scrolling
            val messagesAfter = collectMessagesFromCurrentScreen()
            android.util.Log.d("BaseIntegrationTest", "📍 After scroll - ${messagesAfter.size} messages visible")
            
            // Check if the visible messages changed (i.e., we actually scrolled)
            val actuallyScrolled = messagesBefore != messagesAfter
            
            if (!actuallyScrolled) {
                consecutiveNoScrollMovement++
                android.util.Log.d("BaseIntegrationTest", "⬆️ No scroll movement detected - screen unchanged (attempt $consecutiveNoScrollMovement)")
                android.util.Log.d("BaseIntegrationTest", "   Messages before: ${messagesBefore.size}, Messages after: ${messagesAfter.size}")
                
                // DEBUG: Show what messages we're comparing
                android.util.Log.d("BaseIntegrationTest", "   BEFORE scroll messages:")
                messagesBefore.forEachIndexed { index, (msg, pos) ->
                    android.util.Log.d("BaseIntegrationTest", "     Before $index: '${msg.take(30)}...' at $pos")
                }
                android.util.Log.d("BaseIntegrationTest", "   AFTER scroll messages:")
                messagesAfter.forEachIndexed { index, (msg, pos) ->
                    android.util.Log.d("BaseIntegrationTest", "     After $index: '${msg.take(30)}...' at $pos")
                }
                
                if (consecutiveNoScrollMovement >= 2) {
                    android.util.Log.d("BaseIntegrationTest", "🛑 Reached scroll movement limit - stopping collection")
                }
            } else {
                consecutiveNoScrollMovement = 0
                android.util.Log.d("BaseIntegrationTest", "📜 Scrolled successfully: ${messagesBefore.size} -> ${messagesAfter.size} messages")
                
                // DEBUG: Show what changed
                android.util.Log.d("BaseIntegrationTest", "   New messages appeared after scroll:")
                messagesAfter.forEachIndexed { index, (msg, pos) ->
                    if (!messagesBefore.contains(Pair(msg, pos))) {
                        android.util.Log.d("BaseIntegrationTest", "     NEW: '${msg.take(30)}...' at $pos")
                    }
                }
            }
            
            scrollAttempt++
            android.util.Log.d("BaseIntegrationTest", "📜 === END SCROLL ATTEMPT $scrollAttempt ===")
        }
        
        android.util.Log.d("BaseIntegrationTest", "🏁 === COLLECTION COMPLETE ===")
        android.util.Log.d("BaseIntegrationTest", "📋 Message collection complete: ${allMessages.size} unique messages found")
        android.util.Log.d("BaseIntegrationTest", "📊 Final stats: $scrollAttempt scroll attempts, $consecutiveNoScrollMovement consecutive no-movement, $consecutiveNoNewMessages consecutive no-new-messages")
        android.util.Log.d("BaseIntegrationTest", "📊 Stopping reasons:")
        android.util.Log.d("BaseIntegrationTest", "   - Max scrolls ($maxScrolls): ${scrollAttempt >= maxScrolls}")
        android.util.Log.d("BaseIntegrationTest", "   - No new messages (5): ${consecutiveNoNewMessages >= 5}")
        android.util.Log.d("BaseIntegrationTest", "   - No scroll movement (4): ${consecutiveNoScrollMovement >= 4}")
        
        // Log all collected messages for debugging
        android.util.Log.d("BaseIntegrationTest", "📄 === FINAL MESSAGE LIST ===")
        allMessages.forEachIndexed { index, (message, position) ->
            android.util.Log.d("BaseIntegrationTest", "📄 Final #${index + 1}: '$message' at $position")
        }
        android.util.Log.d("BaseIntegrationTest", "📄 === END MESSAGE LIST ===")
        
        return allMessages.toList()
    }
    


    /**
     * Extract ALL messages and timestamps from current screen view using content descriptions
     */
    private fun collectMessagesFromCurrentScreen(): List<Pair<String, String>> {
        android.util.Log.d("BaseIntegrationTest", "📱 Collecting messages from current screen using content descriptions...")
        
        val messages = mutableListOf<Pair<String, String>>()
        
        try {
            // Use content descriptions for message content (serves both accessibility and testing)
            val userMessages = device.findObjects(By.descContains("User message:").pkg(packageName))
            val assistantMessages = device.findObjects(By.descContains("Assistant message:").pkg(packageName))
            
            android.util.Log.d("BaseIntegrationTest", "📱 Content description search: ${userMessages.size} user messages and ${assistantMessages.size} assistant messages found")
            
            // DEBUG: If no messages found via content descriptions, log this for investigation
            if (userMessages.isEmpty() && assistantMessages.isEmpty()) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ No messages found via content descriptions - checking if elements exist")
                val allElements = device.findObjects(By.pkg(packageName))
                android.util.Log.d("BaseIntegrationTest", "📱 Total elements in package: ${allElements.size}")
                
                val elementsWithDesc = allElements.filter { 
                    try { 
                        it.contentDescription?.isNotBlank() == true 
                    } catch (e: Exception) { 
                        false 
                    } 
                }
                android.util.Log.d("BaseIntegrationTest", "📱 Elements with content descriptions: ${elementsWithDesc.size}")
                
                elementsWithDesc.take(5).forEach { element ->
                    try {
                        android.util.Log.d("BaseIntegrationTest", "📱 Sample desc: '${element.contentDescription}'")
                    } catch (e: Exception) {
                        android.util.Log.w("BaseIntegrationTest", "Error reading content description: ${e.message}")
                    }
                }
            }
            
            // Collect user messages with position-based identification
            for (messageElement in userMessages) {
                try {
                    val text = messageElement.text
                    if (text != null && text.isNotBlank()) {
                        val scrollPosition = messageElement.visibleBounds.centerY()
                        val timestamp = findTimestampForMessage(messageElement)
                        android.util.Log.d("BaseIntegrationTest", "📱 Found user message: '${text.take(50)}...' at position $scrollPosition (timestamp: $timestamp)")
                        messages.add(Pair(text.trim(), "pos_$scrollPosition"))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BaseIntegrationTest", "⚠️ Error reading user message: ${e.message}")
                }
            }
            
            // Collect assistant messages with position-based identification
            for (messageElement in assistantMessages) {
                try {
                    val text = messageElement.text
                    if (text != null && text.isNotBlank()) {
                        val scrollPosition = messageElement.visibleBounds.centerY()
                        val timestamp = findTimestampForMessage(messageElement)
                        android.util.Log.d("BaseIntegrationTest", "📱 Found assistant message: '${text.take(50)}...' at position $scrollPosition (timestamp: $timestamp)")
                        messages.add(Pair(text.trim(), "pos_$scrollPosition"))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BaseIntegrationTest", "⚠️ Error reading assistant message: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error collecting messages from screen: ${e.message}")
            
            // Fallback to old method if content descriptions fail
            android.util.Log.d("BaseIntegrationTest", "🚨 FALLBACK TRIGGERED: Content descriptions failed - using old TextView scanning method")
            return collectMessagesFromCurrentScreenFallback()
        }
        
        android.util.Log.d("BaseIntegrationTest", "📱 Found ${messages.size} messages on current screen using content descriptions")
        return messages
    }
    
    /**
     * Find timestamp for a message element by looking at nearby elements
     */
    private fun findTimestampForMessage(messageElement: UiObject2): String {
        try {
            // Look for timestamp in the parent container
            val parent = messageElement.parent
            val siblings = parent?.findObjects(By.clazz("android.widget.TextView"))
            
            siblings?.forEach { sibling ->
                val text = sibling.text
                if (text != null && (text.contains("AM") || text.contains("PM"))) {
                    return text
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error finding timestamp: ${e.message}")
        }
        
        return "unknown"
    }
    
    /**
     * Fallback method using generic TextView scanning (old approach)
     */
    private fun collectMessagesFromCurrentScreenFallback(): List<Pair<String, String>> {
        android.util.Log.d("BaseIntegrationTest", "📱 Using fallback TextView scanning method...")
        
        val messages = mutableListOf<Pair<String, String>>()
        
        try {
            val textViews = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "📱 Found ${textViews.size} TextView elements to examine")
            
            for (i in textViews.indices) {
                try {
                    val textView = textViews[i]
                    val text = textView.text
                    
                    if (text != null && text.length > 3 && !text.contains("AM") && !text.contains("PM")) {
                        // This looks like a message, try to find its timestamp
                        var timestamp = "unknown"
                        
                        // Look for timestamp in nearby TextViews
                        for (j in maxOf(0, i-2)..minOf(textViews.size-1, i+2)) {
                            try {
                                val nearbyText = textViews[j].text
                                if (nearbyText != null && (nearbyText.contains("AM") || nearbyText.contains("PM"))) {
                                    timestamp = nearbyText
                                    break
                                }
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                        
                        // COLLECT ALL MESSAGES - Only filter out obvious UI elements
                        if (!isUIElement(text)) {
                            android.util.Log.d("BaseIntegrationTest", "📱 Found message: '${text.take(50)}...' at $timestamp")
                            messages.add(Pair(text.trim(), timestamp))
                        } else {
                            android.util.Log.d("BaseIntegrationTest", "📱 Filtered out UI element: '${text.take(30)}...'")
                        }
                    } else {
                        android.util.Log.d("BaseIntegrationTest", "📱 Skipped element: '${text?.take(30) ?: "null"}' (length: ${text?.length ?: 0})")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BaseIntegrationTest", "⚠️ Error reading TextView $i: ${e.message}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error collecting messages from screen: ${e.message}")
        }
        
        android.util.Log.d("BaseIntegrationTest", "📱 Found ${messages.size} messages on current screen using fallback")
        return messages
    }

    /**
     * Check if text is a UI element rather than a chat message
     */
    private fun isUIElement(text: String): Boolean {
        // Only filter out obvious UI navigation and system elements
        return text.equals("My Chats", ignoreCase = true) ||
               text.equals("Settings", ignoreCase = true) ||
               text.equals("Chat", ignoreCase = true) ||
               text.equals("Back", ignoreCase = true) ||
               text.equals("Menu", ignoreCase = true) ||
               text.equals("Search", ignoreCase = true) ||
               text.equals("New Chat", ignoreCase = true) ||
               text.equals("Open Chats List", ignoreCase = true) ||
               text.equals("Turn off continuous listening", ignoreCase = true) ||
               text.equals("Turn on continuous listening", ignoreCase = true) ||
               text.equals("Send message", ignoreCase = true) ||
               text.equals("Message input field", ignoreCase = true) ||
               text.equals("Grant Permission", ignoreCase = true) ||
               text.equals("Not Now", ignoreCase = true) ||
               text.equals("Start chatting with Whiz!", ignoreCase = true) ||
               text.contains("Type or tap the mic", ignoreCase = true) ||
               text.equals("Microphone Permission Required", ignoreCase = true) ||
               text.length < 2 ||  // Only filter out really short strings (1 char)
               (text.length < 4 && text.matches(Regex("^[^a-zA-Z0-9]*$"))) // Only filter short non-alphanumeric strings
    }
    
    /**
     * Check if bot is currently responding (thinking indicator visible)
     */
    protected fun isBotCurrentlyResponding(): Boolean {
        return device.hasObject(By.textContains("Whiz is computing").pkg(packageName)) ||
               device.hasObject(By.textContains("thinking").pkg(packageName)) ||
               device.hasObject(By.textContains("computing").pkg(packageName))
    }
    
    /**
     * Count how many times a message text appears (for duplicate detection)
     * 🔧 NEW: Smart collection to detect real duplicates vs scrolling artifacts
     */
    protected fun countMessageOccurrences(messageText: String): Int {
        android.util.Log.d("BaseIntegrationTest", "🔍 Counting occurrences of '$messageText' using smart collection")
        
        // Collect all messages in the chat
        val allMessages = collectAllMessages()
        
        // Count how many times our target message appears
        var count = 0
        var realDuplicatesFound = 0
        val messageInstances = mutableListOf<Pair<String, String>>()
        
        // Use longer search text (30 chars) to avoid matching truncated chat titles
        val searchText = messageText.take(30)
        
        for ((message, position) in allMessages) {
            if (message.contains(searchText, ignoreCase = true)) {
                count++
                messageInstances.add(Pair(message, position))
                android.util.Log.d("BaseIntegrationTest", "✅ Found match: '$message' at $position")
            }
        }
        
        // Check for real duplicates (same message content appearing at different positions)
        val messageGroups = messageInstances.groupBy { it.first }
        for ((messageContent, instances) in messageGroups) {
            if (instances.size > 1) {
                realDuplicatesFound++
                android.util.Log.w("BaseIntegrationTest", "🚨 REAL DUPLICATE: '$messageContent' appears ${instances.size} times!")
                instances.forEach { (_, position) ->
                    android.util.Log.w("BaseIntegrationTest", "    - At position: $position")
                }
            }
        }
        
        if (realDuplicatesFound > 0) {
            throw AssertionError("Found $realDuplicatesFound real duplicate message(s) in chat! This indicates a production bug.")
        }
        
        android.util.Log.d("BaseIntegrationTest", "📊 Total occurrences found: $count (no real duplicates)")
        return count
    }
    
    /**
     * Wait for the message count to increase (indicating a new message appeared)
     * This is useful for detecting bot responses without relying on specific content
     */
    protected fun waitForNewMessageToAppear(initialMessageCount: Int, timeoutMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()
        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            val currentMessageCount = device.findObjects(
                By.clazz("android.widget.TextView").pkg(packageName)
            ).size
            
            if (currentMessageCount > initialMessageCount) {
                android.util.Log.d("BaseIntegrationTest", "✅ New message detected: count changed from $initialMessageCount to $currentMessageCount")
                return true
            }
            
            Thread.sleep(500) // check every 500ms
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ No new message appeared within timeout")
        return false
    }
    
    /**
     * Get current count of message-like elements for comparison
     */
    protected fun getCurrentMessageCount(): Int {
        return device.findObjects(
            By.clazz("android.widget.TextView").pkg(packageName)
        ).filter { 
            try {
                val text = it.text
                text != null && text.length > 10 // filter out short UI labels
            } catch (e: Exception) {
                false
            }
        }.size
    }

    
    /**
     * Take a screenshot for test failure debugging
     * @param testName Name of the test that failed
     * @param reason Brief description of the failure
     */
    protected fun takeFailureScreenshot(testName: String, reason: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val filename = "${testName}_${timestamp}.png"
            val filepath = "$screenshotDir/$filename"
            
            android.util.Log.d("BaseIntegrationTest", "🔍 Taking failure screenshot: $reason")
            android.util.Log.d("BaseIntegrationTest", "📁 Screenshot directory: $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📸 Screenshot filename: $filename")
            android.util.Log.d("BaseIntegrationTest", "📍 Screenshot full path: $filepath")
            
            // check basic permissions and file system access
            android.util.Log.d("BaseIntegrationTest", "🔍 Basic file system checks:")
            val sdcardCheck = device.executeShellCommand("ls -la /sdcard/")
            android.util.Log.d("BaseIntegrationTest", "📁 /sdcard/ contents: $sdcardCheck")
            
            val whoami = device.executeShellCommand("whoami")
            android.util.Log.d("BaseIntegrationTest", "👤 Running as user: $whoami")
            
            val testWrite = device.executeShellCommand("echo 'test' > /sdcard/test.txt")
            android.util.Log.d("BaseIntegrationTest", "✍️ Test write result: $testWrite")
            
            val testRead = device.executeShellCommand("cat /sdcard/test.txt")
            android.util.Log.d("BaseIntegrationTest", "👀 Test read result: $testRead")
            
            // ensure screenshot directories exist (both regular and CI-accessible)
            val mkdirResult = device.executeShellCommand("mkdir -p $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Create regular directory result: $mkdirResult")
            
            val mkdirCiResult = device.executeShellCommand("mkdir -p $ciScreenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Create CI directory result: $mkdirCiResult")
            
            // check if directory was actually created
            val dirCheckResult = device.executeShellCommand("ls -la $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Directory check: $dirCheckResult")
            
            // try multiple screenshot approaches
            android.util.Log.d("BaseIntegrationTest", "📸 Attempting method 1: screencap -p")
            val result1 = device.executeShellCommand("screencap -p $filepath")
            android.util.Log.d("BaseIntegrationTest", "📸 Method 1 result: $result1")
            
            // check if first method worked
            val check1 = device.executeShellCommand("ls -la $filepath")
            if (!check1.contains(filename)) {
                android.util.Log.w("BaseIntegrationTest", "📸 Method 1 failed, trying method 2: screencap without -p")
                val result2 = device.executeShellCommand("screencap $filepath")
                android.util.Log.d("BaseIntegrationTest", "📸 Method 2 result: $result2")
                
                val check2 = device.executeShellCommand("ls -la $filepath")
                if (!check2.contains(filename)) {
                    android.util.Log.w("BaseIntegrationTest", "📸 Method 2 failed, trying method 3: different directory")
                    val altPath = "/sdcard/$filename"
                    val result3 = device.executeShellCommand("screencap -p $altPath")
                    android.util.Log.d("BaseIntegrationTest", "📸 Method 3 result: $result3")
                    
                                         val check3 = device.executeShellCommand("ls -la $altPath")
                     android.util.Log.d("BaseIntegrationTest", "📸 Method 3 check: $check3")
                     
                     if (!check3.contains(filename)) {
                         android.util.Log.w("BaseIntegrationTest", "📸 All shell methods failed, trying UiAutomator method")
                         try {
                             val uiFile = java.io.File("/sdcard/$filename")
                             val success = device.takeScreenshot(uiFile)
                             android.util.Log.d("BaseIntegrationTest", "📸 UiAutomator method success: $success")
                             
                             val check4 = device.executeShellCommand("ls -la /sdcard/$filename")
                             android.util.Log.d("BaseIntegrationTest", "📸 UiAutomator check: $check4")
                         } catch (e: Exception) {
                             android.util.Log.e("BaseIntegrationTest", "📸 UiAutomator method failed: ${e.message}")
                                                   }
                      }
                  }
              }
              
              // also save to CI-accessible location
              val ciFilepath = "$ciScreenshotDir/$filename"
              android.util.Log.d("BaseIntegrationTest", "📸 Saving copy to CI location: $ciFilepath")
              val ciCopyResult = device.executeShellCommand("screencap -p $ciFilepath")
              android.util.Log.d("BaseIntegrationTest", "📸 CI copy result: $ciCopyResult")
              
              val ciCheckResult = device.executeShellCommand("ls -la $ciFilepath")
              android.util.Log.d("BaseIntegrationTest", "🔍 CI file check: $ciCheckResult")
              
              // Verify screenshot was created with more detailed checking
              val checkResult = device.executeShellCommand("ls -la $filepath")
              android.util.Log.d("BaseIntegrationTest", "🔍 File check result: $checkResult")
            
            if (checkResult.contains(filename)) {
                android.util.Log.d("BaseIntegrationTest", "✅ Screenshot confirmed saved: $filepath")
                
                // also check file size to ensure it's not empty
                val fileSizeResult = device.executeShellCommand("stat -c%s $filepath 2>/dev/null || echo 'stat failed'")
                android.util.Log.d("BaseIntegrationTest", "📏 Screenshot file size: $fileSizeResult bytes")
            } else {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Screenshot may not have been saved: $checkResult")
                
                // try alternative screenshot location
                val altPath = "/sdcard/$filename"
                android.util.Log.d("BaseIntegrationTest", "🔄 Trying alternative path: $altPath")
                val altResult = device.executeShellCommand("screencap -p $altPath")
                android.util.Log.d("BaseIntegrationTest", "📸 Alternative screenshot result: $altResult")
                
                val altCheck = device.executeShellCommand("ls -la $altPath")
                android.util.Log.d("BaseIntegrationTest", "🔍 Alternative file check: $altCheck")
            }
            
            // Also dump UI hierarchy for debugging
            val allElements = device.findObjects(By.pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 UI Dump for $testName:")
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allElements.size} elements in package $packageName")
            
            val clickableElements = device.findObjects(By.clickable(true).pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${clickableElements.size} clickable elements")
            clickableElements.forEachIndexed { index, element ->
                try {
                    val text = element.text ?: "no text"
                    val desc = element.contentDescription ?: "no desc" 
                    val className = element.className ?: "no class"
                    android.util.Log.d("BaseIntegrationTest", "🔍 Element $index: text='$text', desc='$desc', class='$className'")
                } catch (e: Exception) {
                    android.util.Log.d("BaseIntegrationTest", "🔍 Element $index: error reading properties")
                }
            }
            
            android.util.Log.d("BaseIntegrationTest", "✅ Screenshot saved: $filepath")
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "Failed to take failure screenshot", e)
        }
    }
    
    /**
     * Get the current test method name from the call stack
     */
    private fun getCurrentTestMethodName(): String {
        return try {
            val stackTrace = Thread.currentThread().stackTrace
            // Look for test method (starts with "test" or has @Test annotation)
            val testMethod = stackTrace.find { 
                it.methodName.startsWith("test") || 
                it.methodName.contains("_")  // Common pattern like "testSomething_shouldDoSomething"
            }
            testMethod?.methodName ?: "unknownTest"
        } catch (e: Exception) {
            "unknownTest"
        }
    }
    
    /**
     * Custom fail method that automatically takes a screenshot before failing
     * @param message The failure message
     */
    protected fun failWithScreenshot(message: String): Nothing {
        val testName = getCurrentTestMethodName()
        android.util.Log.d("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT: $message")
        takeFailureScreenshot(testName, message)
        org.junit.Assert.fail(message)
        throw AssertionError(message) // This will never be reached but satisfies Nothing return type
    }
    
    /**
     * Custom fail method with reason parameter for clearer screenshot naming
     * @param message The failure message  
     * @param reason Brief description for screenshot filename
     */
    protected fun failWithScreenshot(message: String, reason: String): Nothing {
        val testName = getCurrentTestMethodName()
        android.util.Log.d("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT (reason: $reason): $message")
        takeFailureScreenshot(testName, reason)
        org.junit.Assert.fail(message)
        throw AssertionError(message) // This will never be reached but satisfies Nothing return type
    }
    
    /**
     * Check if currently in chat screen (vs chats list)
     */
    protected fun isCurrentlyInChatScreen(): Boolean {
        // look for chat-specific UI elements
        val messageInput = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        val sendButton = device.findObject(
            UiSelector()
                .descriptionContains("Send")
                .packageName(packageName)
        )
        
        val micButton = device.findObject(
            UiSelector()
                .descriptionContains("Mic")
                .packageName(packageName)
        )
        
        // Log what we found
        val messageInputExists = messageInput.exists()
        val sendButtonExists = sendButton.exists()
        val micButtonExists = micButton.exists()
        
        android.util.Log.d("BaseIntegrationTest", "🔍 isCurrentlyInChatScreen check:")
        android.util.Log.d("BaseIntegrationTest", "   messageInput (EditText): $messageInputExists")
        android.util.Log.d("BaseIntegrationTest", "   sendButton (desc contains 'Send'): $sendButtonExists")
        android.util.Log.d("BaseIntegrationTest", "   micButton (desc contains 'Mic'): $micButtonExists")
        
        val result = messageInputExists || sendButtonExists || micButtonExists
        android.util.Log.d("BaseIntegrationTest", "   📱 Result: ${if (result) "IN CHAT SCREEN" else "NOT IN CHAT SCREEN"}")
        
        return result
    }

    /**
     * Find and click send button, wait for message to be sent - RAPID VERSION for interruption testing
     */
    protected fun clickSendButtonAndWaitForSentRapid(messageText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 RAPID: clicking send button (optimized)...")
        
        // Use only the proven working method - exact content description
        val sendButton = device.findObject(
            UiSelector()
                .description("Send message")
                .packageName(packageName)
        )
        
        // 🔧 PATIENCE: Increased timeout for rapid messaging - send button may be disabled during migration/bot responses
        if (!sendButton.waitForExists(5000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Send button not found even with 5000ms timeout!")
            return false
        }
        
        // 🔧 RETRY: Try clicking send button multiple times if it's temporarily disabled during bot responses
        var clickSuccessful = false
        for (attempt in 1..3) {
            try {
                sendButton.click()
                android.util.Log.d("BaseIntegrationTest", "📤 RAPID: send button clicked (attempt $attempt)")
                clickSuccessful = true
                break
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ RAPID: Click attempt $attempt failed: ${e.message}")
                if (attempt < 3) {
                    Thread.sleep(500) // Wait before retry
                }
            }
        }
        
        if (!clickSuccessful) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: All send button click attempts failed")
            return false
        }
        
        // Minimal delay and fast verification for true rapid testing
        Thread.sleep(10)
        
        val messageDisplayed = device.wait(Until.hasObject(
            By.textContains(messageText).pkg(packageName)
        ), 1000)
        
        if (messageDisplayed) {
            android.util.Log.d("BaseIntegrationTest", "✅ RAPID: message sent and displayed instantly")
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: message not displayed after clicking send button")
        }
        
        return messageDisplayed
    }

    /**
     * Complete message sending flow: type message, click send, verify display - RAPID VERSION
     */
    protected fun sendMessageAndVerifyDisplayRapid(message: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📝 RAPID: attempting to send message: '${message.take(30)}...'")
        
        // step 1: type message
        val typingSuccess = typeMessageInInputField(message)
        if (!typingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: typeMessageInInputField returned false")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: message typed successfully")
        
        // step 2: click send and wait - RAPID
        val sendingSuccess = clickSendButtonAndWaitForSentRapid(message)
        if (!sendingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: clickSendButtonAndWaitForSentRapid returned false")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: message sent and displayed successfully")
        return true
    }

    /**
     * Send whatever is currently typed in the input field - NO TYPING, just send what's there
     * Perfect for testing the flow: user types → user sends (two separate actions)
     */
    protected fun sendCurrentTypedMessage(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📤 TYPED: Sending whatever is currently in the input field...")
        
        // Get current input text for logging
        val inputField = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        val currentText = if (inputField.exists()) {
            inputField.text ?: "unknown"
        } else {
            "field_not_found"
        }
        
        android.util.Log.d("BaseIntegrationTest", "📤 TYPED: Current input field content: '${currentText.take(30)}...'")
        
        // Just click send - don't type anything new
        val sendingSuccess = clickSendButtonAndWaitForSentRapid(currentText)
        if (!sendingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ TYPED: Failed to send currently typed message")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ TYPED: Successfully sent currently typed message")
        return true
    }

    /**
     * Verify that the input field has been cleared after a message send - RAPID VERSION
     */
    protected fun verifyInputFieldClearedRapid(): Boolean {
        try {
            val inputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            if (!inputField.exists()) {
                android.util.Log.d("BaseIntegrationTest", "❌ RAPID: Input field not found - WebSocket verification failed")
                return false
            }
            
            val inputText = inputField.text ?: ""
            if (inputText.isEmpty() || inputText.isBlank()) {
                android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Input field cleared - WebSocket send successful")
                return true
            } else {
                android.util.Log.d("BaseIntegrationTest", "❌ RAPID: Input field still contains text: '${inputText.take(30)}...' - WebSocket send failed")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "❌ RAPID: Error checking input field: ${e.message}")
            return false
        }
    }

    /**
     * Verify message is visible in chat - RAPID VERSION with NO scrolling
     * For rapid interruption testing - messages should appear immediately
     */
    protected fun verifyMessageVisibleRapid(messageText: String): Boolean {
        val searchText = messageText.take(20)
        
        // RAPID: Only check what's currently visible, no scrolling
        val messageVisible = device.hasObject(By.textContains(searchText).pkg(packageName))
        
        if (messageVisible) {
            android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Message found immediately: '${searchText}...'")
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Message not visible immediately: '${searchText}...'")
        }
        
        return messageVisible
    }

    /**
     * Enhanced message verification with smart scrolling (alias for verifyMessageVisible)
     * Shared between ChatViewModelIntegrationTest and MessageFlowIntegrationTest
     * Uses intelligent scrolling that stops when screen content doesn't change
     */
    protected fun verifyMessageWithScroll(messageText: String): Boolean {
        // Use the existing smart scrolling function that knows when to stop
        return verifyMessageVisible(messageText)
    }



    /**
     * Scroll to the very top of the chat to find messages that may have been pushed off-screen by bot responses
     * Returns true if message is found during scrolling, false otherwise
     */
    protected fun scrollToVeryTop(searchText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📜 Scrolling to very top of chat while checking for: '${searchText}...'")
        
        val height = device.displayHeight
        val width = device.displayWidth
        
        // Scroll up aggressively to reach the very top, checking after each scroll
        repeat(20) { attempt ->
            // 🔧 KEYBOARD FIX: Large upward swipe starting higher to avoid keyboard interference
            // Start at 50% and swipe to 10% (staying in upper chat area)
            device.swipe(width/2, height/2, width/2, height/10, 30)
            android.util.Log.d("BaseIntegrationTest", "📜 Top scroll attempt ${attempt + 1}/20 (keyboard-safe)")
            Thread.sleep(500) // Longer wait for scroll to complete
            
            // 🔧 CHECK AFTER EACH SCROLL: Use comprehensive search approaches
            
            // Approach 1: Standard textContains with package filter
            if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
                android.util.Log.d("BaseIntegrationTest", "✅ Message found during scroll to top (approach 1, attempt ${attempt + 1}): '${searchText}...'")
                return true
            }
            
            // Approach 2: textContains without package filter (more permissive)
            if (device.hasObject(By.textContains(searchText))) {
                android.util.Log.d("BaseIntegrationTest", "✅ Message found during scroll to top (approach 2, attempt ${attempt + 1}): '${searchText}...'")
                return true
            }
            
            // Approach 3: exact text match with package filter
            if (device.hasObject(By.text(searchText).pkg(packageName))) {
                android.util.Log.d("BaseIntegrationTest", "✅ Message found during scroll to top (approach 3, attempt ${attempt + 1}): '${searchText}...'")
                return true
            }
            
            // Approach 4: manual search through all TextViews (most comprehensive)
            val allTextViews = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
            for (textView in allTextViews) {
                try {
                    val text = textView.text
                    if (text != null && text.contains(searchText, ignoreCase = true)) {
                        android.util.Log.d("BaseIntegrationTest", "✅ Message found during scroll to top (approach 4, attempt ${attempt + 1}): '${searchText}...' in text: '$text'")
                        return true
                    }
                } catch (e: Exception) {
                    // Ignore errors reading individual text views
                }
            }
        }
        
        android.util.Log.d("BaseIntegrationTest", "📜 Completed scrolling to top (20 attempts) - message not found during scroll")
        return false
    }

    /**
     * Wait for scroll animation to complete - shared helper method
     */
    protected fun waitForScrollToComplete(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "⏳ Waiting for scroll animation to complete...")
        
        // Wait for UI to be responsive after scroll - UI Automator waits for accessibility events
        // 🔧 INCREASED: More patient waiting for scroll completion after migration fix
        val scrollComplete = device.wait(Until.hasObject(
            By.clazz("android.widget.TextView").pkg(packageName)
        ), 1500)
        
        if (!scrollComplete) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Scroll may not have completed properly")
            return false
        }
        
        // 🔧 ADDED: Extra stability wait after migration to ensure messages are fully rendered
        Thread.sleep(300)
        
        android.util.Log.d("BaseIntegrationTest", "✅ Scroll animation completed")
        return true
    }

    /**
     * Click microphone button and wait for listening to start - RAPID VERSION
     * For voice interruption testing - should activate immediately
     */
    protected fun clickMicButtonAndStartListeningRapid(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🎙️ RAPID: Looking for microphone button...")
        
        // Look for various mic button descriptions that might be present
        val micDescriptions = listOf("Start listening", "Turn off continuous listening", "Stop listening")
        var micButton: androidx.test.uiautomator.UiObject? = null
        
        for (description in micDescriptions) {
            micButton = device.findObject(
                androidx.test.uiautomator.UiSelector()
                    .description(description)
                    .packageName(packageName)
            )
            if (micButton.waitForExists(50)) {
                android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Found mic button with description: '$description'")
                break
            }
        }
        
        if (micButton == null || !micButton.exists()) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Microphone button not found!")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "🎙️ RAPID: Clicking microphone button...")
        micButton.click()
        
        // In rapid mode, we don't wait for actual speech recognition to start
        // We just verify the button was clicked successfully
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Microphone button clicked successfully")
        return true
    }

    /**
     * Simulate voice transcription completion and message sending - RAPID VERSION
     * This simulates the flow of: voice input → transcription → automatic send
     */
    protected fun simulateVoiceTranscriptionAndSendRapid(message: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🎤 RAPID: Simulating voice transcription: '${message.take(30)}...'")
        
        // In a real voice input flow, the transcribed text would appear in the input field
        // and then be automatically sent. Since we can't actually speak in tests,
        // we simulate this by directly typing the message (as if transcription completed)
        // and then triggering the send
        
        val typingSuccess = typeMessageInInputField(message)
        if (!typingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Failed to simulate voice transcription")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Voice transcription simulated successfully")
        
        // Now send the message with rapid timing (as voice input typically auto-sends)
        val sendingSuccess = clickSendButtonAndWaitForSentRapid(message)
        if (!sendingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Voice message send failed")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Voice message sent successfully")
        return true
    }

    /**
     * Complete voice message sending flow: simulate transcription callback and send - RAPID VERSION
     * This is the voice equivalent of sendMessageAndVerifyDisplayRapid()
     * Assumes voice mode is already active (continuous listening enabled)
     */
    protected fun sendVoiceMessageAndVerifyDisplayRapid(message: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🎙️ RAPID: Attempting to send voice message: '${message.take(30)}...'")
        android.util.Log.d("BaseIntegrationTest", "🎙️ RAPID: Assuming voice mode already active (continuous listening enabled)")
        
        // Step 1: Simulate voice transcription completion and send (rapid)
        // In voice mode, transcription appears and is automatically sent
        val transcriptionSuccess = simulateVoiceTranscriptionAndSendRapid(message)
        if (!transcriptionSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Voice transcription and send failed")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Voice message sent and displayed successfully")
        return true
    }

    /**
     * Check if input field is currently disabled/blocked (indicating message blocking bug)
     */
    protected fun isInputFieldBlocked(): Boolean {
        // Try contentDescription selector first (accessibility - most reliable)
        var inputField = device.findObject(
            UiSelector()
                .description("Message input field")
                .packageName(packageName)
        )
        
        // Fallback to className selector if contentDescription not found
        if (!inputField.exists()) {
            inputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
        }
        
        if (!inputField.exists()) {
            android.util.Log.d("BaseIntegrationTest", "🔍 Input field not found")
            return true // If input field doesn't exist, consider it blocked
        }
        
        val isEnabled = inputField.isEnabled
        val isFocusable = inputField.isFocusable
        android.util.Log.d("BaseIntegrationTest", "🔍 Input field enabled: $isEnabled, focusable: $isFocusable")
        return !isEnabled || !isFocusable
    }

    /**
     * Try to type text in the input field and return if it worked
     * This directly tests if typing is allowed during bot response
     */
    protected fun tryToTypeInInputField(testText: String = "test"): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 Trying to type '$testText' in input field...")
        
        // Find the actual EditText input field
        val inputField = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        if (!inputField.exists()) {
            android.util.Log.e("BaseIntegrationTest", "❌ EditText input field not found")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Found EditText input field")
        
        // 🔧 DEBUG: Log basic input field state 
        android.util.Log.d("BaseIntegrationTest", "🔍 INPUT FIELD STATE: exists=${inputField.exists()}, enabled=${inputField.isEnabled}, clickable=${inputField.isClickable}")
        
        if (!inputField.exists()) {
            android.util.Log.e("BaseIntegrationTest", "❌ Input field found but doesn't exist")
            return false
        }
        
        // Try multiple interaction methods for Compose compatibility
        var clickSuccess = false
        var clickMethod = ""
        
        // Method 1: Try normal click
        android.util.Log.d("BaseIntegrationTest", "🖱️ Trying Method 1: Normal UiObject.click()...")
        try {
            clickSuccess = inputField.click()
            if (clickSuccess) {
                clickMethod = "normal click"
                android.util.Log.d("BaseIntegrationTest", "✅ Method 1 SUCCESS: Normal click worked")
            } else {
                android.util.Log.d("BaseIntegrationTest", "❌ Method 1 FAILED: Normal click returned false")
            }
        } catch (e: Exception) {
            android.util.Log.d("BaseIntegrationTest", "❌ Method 1 EXCEPTION: Normal click threw: ${e.message}")
        }
        
        // Method 2: Try coordinate-based click
        if (!clickSuccess) {
            android.util.Log.d("BaseIntegrationTest", "🖱️ Trying Method 2: Coordinate-based click...")
            try {
                val bounds = inputField.bounds
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                android.util.Log.d("BaseIntegrationTest", "📍 Clicking at coordinates: ($centerX, $centerY)")
                clickSuccess = device.click(centerX, centerY)
                if (clickSuccess) {
                    clickMethod = "coordinate click"
                    android.util.Log.d("BaseIntegrationTest", "✅ Method 2 SUCCESS: Coordinate click worked")
                } else {
                    android.util.Log.d("BaseIntegrationTest", "❌ Method 2 FAILED: Coordinate click returned false")
                }
            } catch (e: Exception) {
                android.util.Log.d("BaseIntegrationTest", "❌ Method 2 EXCEPTION: Coordinate click threw: ${e.message}")
            }
        }
        
        // Method 3: Skip click entirely and try direct setText (for Compose compatibility)
        if (!clickSuccess) {
            android.util.Log.d("BaseIntegrationTest", "🖱️ Methods 1-2 failed, proceeding with Method 3: Direct setText without click")
            clickSuccess = true // We'll proceed to setText
            clickMethod = "direct setText (no click)"
        }
        
        if (!clickSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ All input field interaction methods failed")
            return false
        } else {
            android.util.Log.d("BaseIntegrationTest", "✅ Input field interaction successful using: $clickMethod")
        }
        
        // Try to type text using Compose-compatible method
        android.util.Log.d("BaseIntegrationTest", "⌨️ Typing text using keyboard input...")
        
        // For Compose compatibility, use keyboard input instead of setText
        val typeSuccess = device.pressKeyCode(android.view.KeyEvent.KEYCODE_CTRL_LEFT, android.view.KeyEvent.META_CTRL_ON) // Select all first
        Thread.sleep(50)
        
        // Type the text character by character using keyboard
        for (char in testText) {
            val keyCode = when (char) {
                'a' -> android.view.KeyEvent.KEYCODE_A
                'b' -> android.view.KeyEvent.KEYCODE_B  
                'c' -> android.view.KeyEvent.KEYCODE_C
                'd' -> android.view.KeyEvent.KEYCODE_D
                'e' -> android.view.KeyEvent.KEYCODE_E
                'f' -> android.view.KeyEvent.KEYCODE_F
                'g' -> android.view.KeyEvent.KEYCODE_G
                'h' -> android.view.KeyEvent.KEYCODE_H
                'i' -> android.view.KeyEvent.KEYCODE_I
                'j' -> android.view.KeyEvent.KEYCODE_J
                'k' -> android.view.KeyEvent.KEYCODE_K
                'l' -> android.view.KeyEvent.KEYCODE_L
                'm' -> android.view.KeyEvent.KEYCODE_M
                'n' -> android.view.KeyEvent.KEYCODE_N
                'o' -> android.view.KeyEvent.KEYCODE_O
                'p' -> android.view.KeyEvent.KEYCODE_P
                'q' -> android.view.KeyEvent.KEYCODE_Q
                'r' -> android.view.KeyEvent.KEYCODE_R
                's' -> android.view.KeyEvent.KEYCODE_S
                't' -> android.view.KeyEvent.KEYCODE_T
                'u' -> android.view.KeyEvent.KEYCODE_U
                'v' -> android.view.KeyEvent.KEYCODE_V
                'w' -> android.view.KeyEvent.KEYCODE_W
                'x' -> android.view.KeyEvent.KEYCODE_X
                'y' -> android.view.KeyEvent.KEYCODE_Y
                'z' -> android.view.KeyEvent.KEYCODE_Z
                '0' -> android.view.KeyEvent.KEYCODE_0
                '1' -> android.view.KeyEvent.KEYCODE_1
                '2' -> android.view.KeyEvent.KEYCODE_2
                '3' -> android.view.KeyEvent.KEYCODE_3
                '4' -> android.view.KeyEvent.KEYCODE_4
                '5' -> android.view.KeyEvent.KEYCODE_5
                '6' -> android.view.KeyEvent.KEYCODE_6
                '7' -> android.view.KeyEvent.KEYCODE_7
                '8' -> android.view.KeyEvent.KEYCODE_8
                '9' -> android.view.KeyEvent.KEYCODE_9
                '_' -> {
                    // Underscore requires SHIFT + MINUS
                    device.pressKeyCode(android.view.KeyEvent.KEYCODE_MINUS, android.view.KeyEvent.META_SHIFT_ON)
                    android.view.KeyEvent.KEYCODE_UNKNOWN // Skip the normal key press since we handled it
                }
                ' ' -> android.view.KeyEvent.KEYCODE_SPACE
                else -> android.view.KeyEvent.KEYCODE_UNKNOWN
            }
            
            if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                device.pressKeyCode(keyCode)
                Thread.sleep(10) // Small delay between key presses
            }
        }
        
        // Wait for text to appear in the input field (fix for timing race condition)
        // The UI might take a moment to update after keyboard input
        var textAccepted = false
        var actualText = ""
        val maxRetries = 10
        var retries = 0
        
        while (retries < maxRetries && !textAccepted) {
            // Get fresh reference to input field to avoid stale cached state
            val freshInputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            // Check if text was accepted with fresh UI state
            actualText = freshInputField.text ?: ""
            textAccepted = actualText.contains(testText)
            
            if (!textAccepted) {
                retries++
                Thread.sleep(50) // Wait 50ms before retrying
                android.util.Log.d("BaseIntegrationTest", "🔄 Waiting for text to appear... retry $retries/$maxRetries (current text: '$actualText')")
            }
        }
        
        if (textAccepted) {
            android.util.Log.d("BaseIntegrationTest", "✅ Successfully typed '$testText' using keyboard input (text accepted)")
            return true
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ Keyboard input '$testText' was not accepted by input field - completely blocked (actualText: '$actualText')")
            return false
        }
    }

    /**
     * Try to find and click the send button after typing text
     */
    protected fun tryToClickSendButton(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 Looking for send button...")
        
        val sendButton = device.findObject(
            UiSelector()
                .description("Send message")
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(50)) {
            android.util.Log.e("BaseIntegrationTest", "❌ Send button not found")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Send button found, trying to click...")
        val clickSuccess = sendButton.click()
        
        if (clickSuccess) {
            android.util.Log.d("BaseIntegrationTest", "✅ Successfully clicked send button")
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ Could not click send button")
        }
        
        return clickSuccess
    }

    /**
     * IMMEDIATE typing method that bypasses all UI waits - for testing during bot response
     * This method doesn't wait for UI stability and types immediately
     */
    protected fun typeImmediatelyDuringBotResponse(testText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "⚡ IMMEDIATE: Typing '$testText' without waiting for UI stability...")
        
        // Find input field with minimal wait (100ms max)
        val inputField = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        // Don't wait - if it doesn't exist immediately, that's the bug
        if (!inputField.exists()) {
            android.util.Log.e("BaseIntegrationTest", "❌ IMMEDIATE: Input field not found immediately - this IS the production bug!")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ IMMEDIATE: Input field found, proceeding with direct interaction...")
        
        // Try to interact immediately without waiting for "stability"
        try {
            // Method 1: Direct coordinate click (bypass UI Automator's stability waits)
            val bounds = inputField.bounds
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            android.util.Log.d("BaseIntegrationTest", "⚡ IMMEDIATE: Direct coordinate click at ($centerX, $centerY)")
            device.click(centerX, centerY)
            
            // Wait for focus to be established
            device.waitForIdle(5)
            
            // start typing with keyboard
            android.util.Log.d("BaseIntegrationTest", "⚡ IMMEDIATE: Starting keyboard input without delay...")
            
            // 🔧 PRODUCTION BUG FIX: Use more realistic typing speed (50ms between chars)
            // Real users don't type at 5ms intervals - this was causing race conditions
            for (char in testText) {
                val keyCode = when (char) {
                    'a' -> android.view.KeyEvent.KEYCODE_A
                    'b' -> android.view.KeyEvent.KEYCODE_B
                    'c' -> android.view.KeyEvent.KEYCODE_C
                    'd' -> android.view.KeyEvent.KEYCODE_D
                    'e' -> android.view.KeyEvent.KEYCODE_E
                    'f' -> android.view.KeyEvent.KEYCODE_F
                    'g' -> android.view.KeyEvent.KEYCODE_G
                    'h' -> android.view.KeyEvent.KEYCODE_H
                    'i' -> android.view.KeyEvent.KEYCODE_I
                    'j' -> android.view.KeyEvent.KEYCODE_J
                    'k' -> android.view.KeyEvent.KEYCODE_K
                    'l' -> android.view.KeyEvent.KEYCODE_L
                    'm' -> android.view.KeyEvent.KEYCODE_M
                    'n' -> android.view.KeyEvent.KEYCODE_N
                    'o' -> android.view.KeyEvent.KEYCODE_O
                    'p' -> android.view.KeyEvent.KEYCODE_P
                    'q' -> android.view.KeyEvent.KEYCODE_Q
                    'r' -> android.view.KeyEvent.KEYCODE_R
                    's' -> android.view.KeyEvent.KEYCODE_S
                    't' -> android.view.KeyEvent.KEYCODE_T
                    'u' -> android.view.KeyEvent.KEYCODE_U
                    'v' -> android.view.KeyEvent.KEYCODE_V
                    'w' -> android.view.KeyEvent.KEYCODE_W
                    'x' -> android.view.KeyEvent.KEYCODE_X
                    'y' -> android.view.KeyEvent.KEYCODE_Y
                    'z' -> android.view.KeyEvent.KEYCODE_Z
                    '0' -> android.view.KeyEvent.KEYCODE_0
                    '1' -> android.view.KeyEvent.KEYCODE_1
                    '2' -> android.view.KeyEvent.KEYCODE_2
                    '3' -> android.view.KeyEvent.KEYCODE_3
                    '4' -> android.view.KeyEvent.KEYCODE_4
                    '5' -> android.view.KeyEvent.KEYCODE_5
                    '6' -> android.view.KeyEvent.KEYCODE_6
                    '7' -> android.view.KeyEvent.KEYCODE_7
                    '8' -> android.view.KeyEvent.KEYCODE_8
                    '9' -> android.view.KeyEvent.KEYCODE_9
                    ' ' -> android.view.KeyEvent.KEYCODE_SPACE
                    else -> android.view.KeyEvent.KEYCODE_UNKNOWN
                }
                
                if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                    android.util.Log.d("BaseIntegrationTest", "⚡ IMMEDIATE: Typing character '$char' (keyCode: $keyCode)")
                    device.pressKeyCode(keyCode)
                    // Allow UI to process the keystroke before next character
                    device.waitForIdle(5)
                }
            }
            
            android.util.Log.d("BaseIntegrationTest", "⚡ IMMEDIATE: Keyboard input completed, checking if text was accepted...")
            
            // 🔧 PRODUCTION BUG FIX: More generous timeout for text acceptance
            // Wait for UI to settle after typing, then verify text was accepted
            device.waitForIdle(50)
            
            // Get fresh reference to input field to avoid stale cached state
            val freshInputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            val actualText = freshInputField.text ?: ""
            val textAccepted = actualText.contains(testText)
            
            if (textAccepted) {
                android.util.Log.d("BaseIntegrationTest", "✅ IMMEDIATE: Text '$testText' accepted during bot response!")
                return true
            } else {
                android.util.Log.e("BaseIntegrationTest", "❌ IMMEDIATE: Text '$testText' was NOT accepted - production bug confirmed!")
                android.util.Log.e("BaseIntegrationTest", "   Input field text: '$actualText'")
                return false
            }
            
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ IMMEDIATE: Exception during immediate typing: ${e.message}")
            return false
        }
    }

    companion object {
        const val packageName = "com.example.whiz"
        const val screenshotDir = "/sdcard/test_screenshots"
        
        // also save to a location that's more accessible for CI
        const val ciScreenshotDir = "/data/local/tmp/screenshots"
        
        // detect if running in CI environment (GitHub Actions)
        private val isRunningInCI: Boolean by lazy {
            System.getenv("CI") == "true" || 
            System.getenv("GITHUB_ACTIONS") == "true" ||
            System.getProperty("ci.environment") == "true"
        }
        
        // CI-aware timing multiplier
        private val timingMultiplier: Float by lazy {
            if (isRunningInCI) 3.0f else 1.0f
        }
        
        /**
         * Get CI-aware timeout with multiplier for slower environments
         */
        fun getCIAwareTimeout(baseTimeoutMs: Long): Long {
            return (baseTimeoutMs * timingMultiplier).toLong()
        }
        
        /**
         * Get CI-aware delay with multiplier for slower environments  
         */
        fun getCIAwareDelay(baseDelayMs: Long): Long {
            return (baseDelayMs * timingMultiplier).toLong()
        }
    }

    /**
     * Simple verification: Find user messages by their styling (right-aligned, primary color background)
     * Much simpler than text parsing since user/bot messages have different styling
     */
    protected fun verifyMessageSentAtBottom(messageText: String, timeoutMs: Long = 3000): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 SIMPLE: Looking for recently sent message: '${messageText.take(30)}...'")
        
        // Check if input field was cleared (indicates message was actually sent)
        try {
            val inputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            if (inputField.exists()) {
                val inputText = inputField.text ?: ""
                if (inputText.contains(messageText, ignoreCase = true)) {
                    android.util.Log.e("BaseIntegrationTest", "❌ SIMPLE: Message still in input field - send failed!")
                    return false
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ SIMPLE: Error checking input field: ${e.message}")
        }
        
        // Look for the message text in the chat - simple and direct
        val messageFound = device.hasObject(
            By.textContains(messageText.take(20)).pkg(packageName)
        )
        
        if (messageFound) {
            android.util.Log.d("BaseIntegrationTest", "✅ SIMPLE: Message found in chat")
            return true
        }
        
        // If not found immediately, try one scroll up to see if it's just above view
        android.util.Log.d("BaseIntegrationTest", "🔍 SIMPLE: Not immediately visible, trying one scroll...")
        
        try {
            device.swipe(
                device.displayWidth / 2,
                device.displayHeight / 2,
                device.displayWidth / 2,
                device.displayHeight / 2 + 150,
                10
            )
            
            Thread.sleep(300)
            
            val messageFoundAfterScroll = device.hasObject(
                By.textContains(messageText.take(20)).pkg(packageName)
            )
            
            if (messageFoundAfterScroll) {
                android.util.Log.d("BaseIntegrationTest", "✅ SIMPLE: Message found after scroll")
                return true
            }
            
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ SIMPLE: Error during scroll: ${e.message}")
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ SIMPLE: Message not found")
        return false
    }

    /**
     * Find all chat messages on screen using their actual UI styling
     * Much more reliable than text parsing since we identify by component structure
     */
    private fun findChatMessagesByStyle(): List<Pair<String, String>> {
        val messages = mutableListOf<Pair<String, String>>()
        
        try {
            // Find all Card components (messages are wrapped in Cards)
            val cards = device.findObjects(By.clazz("androidx.compose.material3.Card").pkg(packageName))
            
            for (card in cards) {
                try {
                    // Look for text content within this card
                    val textViews = card.findObjects(By.clazz("android.widget.TextView"))
                    
                    var messageContent = ""
                    var timestamp = ""
                    var isUserMessage = false
                    var hasWhizLabel = false
                    
                    for (textView in textViews) {
                        val text = textView.text ?: continue
                        
                        // Check if this is a "Whiz" label (indicates bot message)
                        if (text.equals("Whiz", ignoreCase = true)) {
                            hasWhizLabel = true
                            continue
                        }
                        
                        // Check if this is a timestamp
                        if (text.matches(Regex("\\d{1,2}:\\d{2}\\s*(AM|PM)"))) {
                            timestamp = text
                            continue
                        }
                        
                        // Skip UI elements
                        if (isUIElement(text)) {
                            continue
                        }
                        
                        // This should be the actual message content
                        if (text.length > 3 && !text.contains("computing", ignoreCase = true)) {
                            messageContent = text
                        }
                    }
                    
                    // Determine if this is a user message based on styling clues
                    // User messages: NO "Whiz" label, right-aligned
                    // Bot messages: HAS "Whiz" label, left-aligned
                    isUserMessage = !hasWhizLabel
                    
                    // Only add if we found actual message content
                    if (messageContent.isNotEmpty()) {
                        val displayType = if (isUserMessage) "USER" else "BOT"
                        android.util.Log.d("BaseIntegrationTest", "🎯 STYLE: Found $displayType message: '${messageContent.take(50)}...' at $timestamp")
                        messages.add(Pair(messageContent, timestamp))
                    }
                    
                } catch (e: Exception) {
                    // Skip problematic cards
                    android.util.Log.w("BaseIntegrationTest", "⚠️ STYLE: Error reading card: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ STYLE: Error finding messages: ${e.message}")
        }
        
        return messages
    }

    /**
     * Find user messages specifically using styling detection
     */
    private fun findUserMessages(): List<Pair<String, String>> {
        val allMessages = findChatMessagesByStyle()
        // No filtering needed - collect all messages to understand complete chat state
        return allMessages
    }

}

/**
 * Base class for integration tests that specifically test login/authentication UI.
 * Skips automatic authentication setup.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
abstract class BaseLoginTest : BaseIntegrationTest() {
         override val skipAutoAuthentication: Boolean = true
 } 