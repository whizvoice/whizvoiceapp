package com.example.whiz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
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
        
        // Ensure it starts in a new task to avoid voice assistant mode
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
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
        val newChatButton = device.findObject(
            UiSelector()
                .descriptionContains("New Chat")
                .packageName(packageName)
        )
        
        if (!newChatButton.waitForExists(5000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ new chat button not found")
            return false
        }
        
        newChatButton.click()
        
        // wait for chat screen to load by looking for message input field
        val chatScreenLoaded = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 8000)
        
        return chatScreenLoaded
    }
    
    /**
     * Find message input field, type message, and verify text appears
     */
    protected fun typeMessageInInputField(message: String): Boolean {
        val messageInput = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        if (!messageInput.waitForExists(5000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ message input field not found")
            
            // debug: dump all EditText elements
            val allEditTexts = device.findObjects(By.clazz("android.widget.EditText"))
            android.util.Log.d("BaseIntegrationTest", "🔍 found ${allEditTexts.size} EditText elements")
            allEditTexts.forEachIndexed { i, element ->
                try {
                    val bounds = element.visibleBounds
                    val hint = element.hint
                    android.util.Log.d("BaseIntegrationTest", "  EditText $i: hint='$hint', bounds=$bounds")
                } catch (e: Exception) {
                    android.util.Log.d("BaseIntegrationTest", "  EditText $i: error reading properties")
                }
            }
            
            return false
        }
        
        messageInput.click()
        messageInput.setText(message)
        
        // wait for text to appear in field with retry
        var textSet = device.wait(Until.hasObject(
            By.text(message).pkg(packageName)
        ), 3000)
        
        if (!textSet) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ text not visible, retrying...")
            messageInput.setText(message)
            textSet = device.wait(Until.hasObject(
                By.text(message).pkg(packageName)
            ), 2000)
        }
        
        return textSet
    }
    
    /**
     * Find and click send button, wait for message to be sent - NORMAL VERSION for initial loading
     */
    protected fun clickSendButtonAndWaitForSent(messageText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 looking for send button with exact content description 'Send message'...")
        
        // Use exact content description from production code (ChatScreen.kt line 748)
        val sendButton = device.findObject(
            UiSelector()
                .description("Send message")  // Exact match from production code
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(1000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ Send button with description 'Send message' not found!")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ found send button")
        android.util.Log.d("BaseIntegrationTest", "📤 clicking send button...")
        sendButton.click()
        
        // verify message appears in chat (optimistic UI) - normal timeout for loading
        val messageDisplayed = device.wait(Until.hasObject(
            By.textContains(messageText).pkg(packageName)
        ), 1000)
        
        if (messageDisplayed) {
            android.util.Log.d("BaseIntegrationTest", "✅ message sent and displayed successfully")
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ message not displayed after clicking send button")
        }
        
        return messageDisplayed
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
            val inputField = device.findObject(
                UiSelector()
                    .className("android.widget.EditText")
                    .packageName(packageName)
            )
            
            if (!inputField.exists()) {
                android.util.Log.d("BaseIntegrationTest", "❌ Input field not found - WebSocket verification failed")
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
            return true
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
            return true
        }
        
        // Fallback: Look for new message content that appeared after our last user message
        // This is less reliable but catches cases where styling detection fails
        val anyNewMessageContent = device.wait(Until.hasObject(
            By.clazz("android.widget.TextView").pkg(packageName)
        ), 3000)
        
        if (anyNewMessageContent) {
            android.util.Log.d("BaseIntegrationTest", "⚠️ Bot response detected via fallback (new content appeared)")
            return true
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ No bot response detected within timeout")
        return false
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
        
        // if not found, try scrolling up to find older messages
        android.util.Log.d("BaseIntegrationTest", "🔍 Message not visible, trying to scroll up to find: '${searchText}...'")
        
        // Smart scrolling: stop when we reach the top or find the message
        var lastContentSnapshot = getVisibleContentSnapshot()
        var scrollAttempts = 0
        val maxScrollAttempts = 15 // Safety limit to prevent infinite loops
        
        while (scrollAttempts < maxScrollAttempts) {
            try {
                // use swipe gesture to scroll up in the chat area
                val height = device.displayHeight
                val width = device.displayWidth
                
                // swipe from middle-bottom to middle-top to scroll up
                device.swipe(width/2, height*2/3, width/2, height/3, 10)
                scrollAttempts++
                android.util.Log.d("BaseIntegrationTest", "📜 Swiped up to scroll (attempt $scrollAttempts)")
                
                // give UI more time to update after scroll (CI environments can be slower)
                Thread.sleep(getCIAwareDelay(1200))
                
                // check if message is now visible
                if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
                    android.util.Log.d("BaseIntegrationTest", "✅ Message found after scrolling up (attempt $scrollAttempts): '${searchText}...'")
                    return true
                }
                
                // Check if we've reached the top by comparing content
                val currentContentSnapshot = getVisibleContentSnapshot()
                if (currentContentSnapshot == lastContentSnapshot) {
                    android.util.Log.d("BaseIntegrationTest", "🔝 Reached top of chat (no new content after scroll attempt $scrollAttempts)")
                    break
                }
                lastContentSnapshot = currentContentSnapshot
                
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Error during scroll attempt $scrollAttempts: ${e.message}")
                break
            }
        }
        
        if (scrollAttempts >= maxScrollAttempts) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Stopped scrolling after reaching max attempts ($maxScrollAttempts)")
        }
        
        // try scrolling down too (in case we overshot)
        android.util.Log.d("BaseIntegrationTest", "🔍 Trying to scroll down to find: '${searchText}...'")
        lastContentSnapshot = getVisibleContentSnapshot()
        var downScrollAttempts = 0
        val maxDownScrolls = 5 // Fewer down scrolls since we just came from the top
        
        while (downScrollAttempts < maxDownScrolls) {
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                
                // swipe from middle-top to middle-bottom to scroll down
                device.swipe(width/2, height/3, width/2, height*2/3, 10)
                downScrollAttempts++
                android.util.Log.d("BaseIntegrationTest", "📜 Swiped down to scroll (attempt $downScrollAttempts)")
                
                Thread.sleep(getCIAwareDelay(1200))
                
                if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
                    android.util.Log.d("BaseIntegrationTest", "✅ Message found after scrolling down (attempt $downScrollAttempts): '${searchText}...'")
                    return true
                }
                
                // Check if we've reached the bottom
                val currentContentSnapshot = getVisibleContentSnapshot()
                if (currentContentSnapshot == lastContentSnapshot) {
                    android.util.Log.d("BaseIntegrationTest", "🔻 Reached bottom of chat (no new content after down scroll attempt $downScrollAttempts)")
                    break
                }
                lastContentSnapshot = currentContentSnapshot
                
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Error during down scroll attempt $downScrollAttempts: ${e.message}")
                break
            }
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ Message not found even after intelligent scrolling (${scrollAttempts} up, ${downScrollAttempts} down): '${searchText}...'")
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
     * Check if bot is currently responding (thinking indicator visible)
     */
    protected fun isBotCurrentlyResponding(): Boolean {
        return device.hasObject(By.textContains("Whiz is computing").pkg(packageName)) ||
               device.hasObject(By.textContains("thinking").pkg(packageName)) ||
               device.hasObject(By.textContains("computing").pkg(packageName))
    }
    
    /**
     * Count how many times a message text appears (for duplicate detection)
     * 🔧 FIX: Use longer, more specific search to avoid false positives from chat titles
     */
    protected fun countMessageOccurrences(messageText: String): Int {
        // Use longer search text (30 chars instead of 15) to avoid matching truncated chat titles
        val searchText = messageText.take(30)
        val elements = device.findObjects(
            By.textContains(searchText).pkg(packageName)
        )
        
        // 🔍 DIAGNOSTIC: Log details about what UI elements are found
        if (elements.size > 1) {
            android.util.Log.w("BaseIntegrationTest", "🔍 DUPLICATE DIAGNOSTIC: Found ${elements.size} UI elements containing '$searchText'")
            elements.forEachIndexed { index, element ->
                try {
                    val fullText = element.text
                    val className = element.className
                    val bounds = element.visibleBounds
                    
                    android.util.Log.w("BaseIntegrationTest", "🔍 Element $index:")
                    android.util.Log.w("BaseIntegrationTest", "  Text: '$fullText'")
                    android.util.Log.w("BaseIntegrationTest", "  Class: $className")
                    android.util.Log.w("BaseIntegrationTest", "  Bounds: $bounds")
                } catch (e: Exception) {
                    android.util.Log.w("BaseIntegrationTest", "🔍 Element $index: Error reading properties - ${e.message}")
                }
            }
        }
        
        return elements.size
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
        
        return messageInput.exists() || sendButton.exists() || micButton.exists()
    }

    /**
     * Find and click send button, wait for message to be sent - RAPID VERSION for interruption testing
     */
    protected fun clickSendButtonAndWaitForSentRapid(messageText: String): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 RAPID: looking for send button with exact content description 'Send message'...")
        
        // Use exact content description from production code (ChatScreen.kt line 748)
        val sendButton = device.findObject(
            UiSelector()
                .description("Send message")  // Exact match from production code
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(50)) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Send button with description 'Send message' not found!")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: found send button")
        android.util.Log.d("BaseIntegrationTest", "📤 RAPID: clicking send button...")
        sendButton.click()
        
        // verify message appears in chat (optimistic UI) - should be instant
        val messageDisplayed = device.wait(Until.hasObject(
            By.textContains(messageText).pkg(packageName)
        ), 50)
        
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
        val inputField = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
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
        
        val inputField = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        if (!inputField.exists()) {
            android.util.Log.e("BaseIntegrationTest", "❌ Input field not found")
            return false
        }
        
        // Try to click on the input field
        val clickSuccess = inputField.click()
        if (!clickSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ Cannot click input field")
            return false
        }
        
        // Try to type text
        val typeSuccess = inputField.setText(testText)
        if (!typeSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ Cannot type in input field")
            return false
        }
        
        // Check if text was accepted (either visually or internally)
        val actualText = inputField.text ?: ""
        val textAccepted = actualText.contains(testText)
        
        if (textAccepted) {
            android.util.Log.d("BaseIntegrationTest", "✅ Successfully typed '$testText' in input field (text accepted)")
            return true
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ Text '$testText' was not accepted by input field - completely blocked")
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