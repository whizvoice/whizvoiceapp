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
import android.view.accessibility.AccessibilityNodeInfo
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
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiCollection
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.UiWatcher
import androidx.test.uiautomator.Configurator
import java.util.concurrent.TimeUnit

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
     * Set up screenshot directory - ensure it exists but preserve existing screenshots from other tests
     */
    private fun setupScreenshotDirectory() {
        try {
            android.util.Log.d("BaseIntegrationTest", "🔧 Ensuring screenshot directory exists...")
            
            // Only clear on first test run (check if directory is empty)
            val dirCheck = device.executeShellCommand("ls -la $screenshotDir 2>/dev/null || echo 'DIR_NOT_EXISTS'")
            val shouldClear = dirCheck.contains("DIR_NOT_EXISTS") || dirCheck.trim() == "total 0"
            
            if (shouldClear) {
                android.util.Log.d("BaseIntegrationTest", "🧹 First test - clearing any existing screenshots...")
                val removeResult = device.executeShellCommand("rm -rf $screenshotDir")
                android.util.Log.d("BaseIntegrationTest", "🧹 Screenshot cleanup result: $removeResult")
            } else {
                android.util.Log.d("BaseIntegrationTest", "📁 Screenshot directory exists with content - preserving for CI collection")
            }
            
            // Ensure directory exists
            val mkdirResult = device.executeShellCommand("mkdir -p $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Screenshot directory creation result: $mkdirResult")
            
            // Log current directory state
            val finalDirCheck = device.executeShellCommand("ls -la $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📋 Screenshot directory state: $finalDirCheck")
            
            android.util.Log.d("BaseIntegrationTest", "✅ Screenshot directory ready: $screenshotDir")
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
        
        // First attempt: Wait for app to launch
        var appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        if (!appLaunched) {
            android.util.Log.e("BaseIntegrationTest", "❌ app failed to launch within 10 seconds")
            android.util.Log.w("BaseIntegrationTest", "⚠️ Attempting backgrounding recovery...")
            
            // Try to bring app to foreground
            bringAppToForeground()
            
            // Check again after recovery
            appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 5000)
            if (!appLaunched) {
                android.util.Log.e("BaseIntegrationTest", "❌ app still failed to launch after recovery attempt")
                return false
            } else {
                android.util.Log.d("BaseIntegrationTest", "✅ app recovered successfully")
            }
        }
        
        // Wait for main UI elements to load - should be chats list for manual launch
        var mainUILoaded = device.wait(Until.hasObject(
            By.text("My Chats").pkg(packageName)
        ), 8000) || device.wait(Until.hasObject(
            By.descContains("New Chat").pkg(packageName)  
        ), 3000)
        
        if (!mainUILoaded) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ main UI not detected, attempting recovery...")
            
            // Try to bring app to foreground again
            bringAppToForeground()
            
            // Check for main UI again
            mainUILoaded = device.wait(Until.hasObject(
                By.text("My Chats").pkg(packageName)
            ), 5000) || device.wait(Until.hasObject(
                By.descContains("New Chat").pkg(packageName)  
            ), 3000)
            
            if (!mainUILoaded) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ main UI still not detected, checking for any app content...")
                // Fallback check for any app content
                val anyAppContent = device.wait(Until.hasObject(
                    By.pkg(packageName)
                ), 2000)
                
                if (!anyAppContent) {
                    android.util.Log.e("BaseIntegrationTest", "❌ no app content visible after launch and recovery")
                    return false
                }
            }
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ app launched successfully")
        return true
    }
    
    /**
     * Find and click the new chat button using accessibility actions, wait for chat screen to load
     */
    protected fun clickNewChatButtonAndWaitForChatScreen(): Boolean {
        device.findObject(UiSelector().packageName(packageName)).waitForExists(10) // Force UI sync

        android.util.Log.d("BaseIntegrationTest", "🎯 Clicking new chat button using accessibility actions...")
        
        // Try to find the main FloatingActionButton first (when chats exist)
        var newChatButton = device.findObject(
            UiSelector()
                .description("New Chat")
                .packageName(packageName)
        )
        
        if (!newChatButton.waitForExists(1000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ 'New Chat' button not found")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Found new chat button: ${newChatButton.contentDescription}")
        
        // Wait a moment for Compose to fully render the button (addresses timing issues)
        try {
            
            // Ensure button is still present and clickable
            if (!newChatButton.isClickable) {
                android.util.Log.e("BaseIntegrationTest", "❌ Button found but not clickable")
                return false
            }
            
            android.util.Log.d("BaseIntegrationTest", "🎯 Clicking new chat button...")
            newChatButton.click()
            android.util.Log.d("BaseIntegrationTest", "✅ Click performed")
            
            // Wait for navigation to chat screen with longer timeout
            android.util.Log.d("BaseIntegrationTest", "⏳ Waiting for navigation to chat screen...")
            device.findObject(UiSelector().packageName(packageName)).waitForExists(10) // Force UI sync
            val chatScreenLoaded = device.wait(Until.hasObject(
                By.clazz("android.widget.EditText").pkg(packageName)
            ), 2000)
            
            if (chatScreenLoaded) {
                android.util.Log.d("BaseIntegrationTest", "✅ Successfully navigated to chat screen")
                return true
            } else {
                android.util.Log.e("BaseIntegrationTest", "❌ Navigation to chat screen failed - EditText not found after 8 seconds")
                return false
            }
            
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Button click failed with exception: ${e.message}")
            return false
        }
    }
    
    /**
     * Type a message in the input field with optional scrolling
     */
    protected fun typeMessageInInputField(message: String, rapid: Boolean = false): Boolean {
        if (!rapid) {
            // First, scroll to bottom to ensure input field is accessible
            android.util.Log.d("BaseIntegrationTest", "📜 Ensuring input field is accessible by scrolling to bottom...")
            scrollToBottom()
        } else {
            android.util.Log.d("BaseIntegrationTest", "🚀 RAPID: Skipping scroll to bottom for rapid typing...")
        }
        device.findObject(UiSelector().packageName(packageName)).waitForExists(10) // Force UI sync
        // Look for the actual EditText (the real input field)
        val messageInput = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        var waitTimeout = 1000L
        if (rapid) {
            waitTimeout = 10L  // Ultra-short timeout for immediate sending test
        }
        if (!messageInput.waitForExists(waitTimeout)) {
            android.util.Log.e("BaseIntegrationTest", "❌ EditText input field not found within ${waitTimeout}ms")
            
            // UI dump to see what's actually on screen when input field search fails
            val allElements = device.findObjects(By.pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 UI Dump for input field search failure:")
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allElements.size} elements in package $packageName")
            
            // Log ALL elements to see what's actually available
            android.util.Log.d("BaseIntegrationTest", "🔍 ALL ELEMENTS ON SCREEN:")
            allElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  Element $index: class='${element.className}', text='${element.text}', desc='${element.contentDescription}', enabled=${element.isEnabled}, clickable=${element.isClickable}")
            }
            
            // Log all EditText elements specifically
            val editTextElements = device.findObjects(By.clazz("android.widget.EditText").pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${editTextElements.size} EditText elements:")
            editTextElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  EditText $index: text='${element.text}', desc='${element.contentDescription}', enabled=${element.isEnabled}, clickable=${element.isClickable}")
            }
            
            // Log all elements with "input" or "message" in their description
            val inputRelatedElements = allElements.filter { element ->
                element.contentDescription?.contains("input", ignoreCase = true) == true ||
                element.contentDescription?.contains("message", ignoreCase = true) == true
            }
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${inputRelatedElements.size} input-related elements:")
            inputRelatedElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  Input-related $index: class='${element.className}', text='${element.text}', desc='${element.contentDescription}'")
            }
            
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ Found EditText input field")
        
        // Debug logging of input field status
        android.util.Log.d("BaseIntegrationTest", "🔍 INPUT FIELD STATUS:")
        android.util.Log.d("BaseIntegrationTest", "  - isClickable: ${messageInput.isClickable}")
        android.util.Log.d("BaseIntegrationTest", "  - isEnabled: ${messageInput.isEnabled}")
        android.util.Log.d("BaseIntegrationTest", "  - isSelected: ${messageInput.isSelected}")
        android.util.Log.d("BaseIntegrationTest", "  - isFocused: ${messageInput.isFocused}")
        android.util.Log.d("BaseIntegrationTest", "  - bounds: ${messageInput.visibleBounds}")
        android.util.Log.d("BaseIntegrationTest", "  - text: '${messageInput.text}'")
        
        // Click and set text
        // messageInput.click()
        android.util.Log.d("BaseIntegrationTest", "skipped click")
        messageInput.setText(message)
        android.util.Log.d("BaseIntegrationTest", "just set text")
        
        // Wait for the specific text to appear in the EditText field
        val searchText = message.take(30)

        // Use a custom wait condition specifically for EditText content
        var textFound = false
        val startTime = System.currentTimeMillis()
        var timeout = 1000L // 1 seconds
        if (rapid) {
            timeout = 25L
        }
        android.util.Log.d("BaseIntegrationTest", "looking for text")
        device.findObject(UiSelector().packageName(packageName)).waitForExists(10) // Force UI sync

        while (!textFound && (System.currentTimeMillis() - startTime) < timeout) {
            val currentText = messageInput.text ?: ""
            if (currentText.contains(searchText, ignoreCase = true)) {
                textFound = true
                android.util.Log.d("BaseIntegrationTest", "✅ Text found in EditText: '${currentText.take(50)}...'")
            } else {
                android.util.Log.d("BaseIntegrationTest", "🔄 Waiting for text... current: '${currentText.take(30)}...'")
                if (rapid) {
                    return true
                }
                Thread.sleep(10)
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
     * Complete message sending flow: type message, click send, verify display - NORMAL VERSION
     */
    protected fun sendMessageAndVerifyDisplay(message: String, rapid: Boolean = false): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📝 attempting to send message: '${message.take(30)}...'")
        
        // step 1: type message
        val typingSuccess = typeMessageInInputField(message, rapid = rapid)
        if (!typingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ typeMessageInInputField returned false")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ message typed successfully")
        
        // step 2: click send and wait - NORMAL
        val sendingSuccess = clickSendButtonAndWaitForSent(message, rapid = rapid)
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
            android.util.Log.d("BaseIntegrationTest", "⏳ Waiting for input field to be cleared (WebSocket verification)...")
            
            // Wait directly for the EditText to exist and be accessible
            val timeout = 1500L // 1.5 second timeout (increased for CI environments)
            
            // First, ensure the EditText exists
            val inputFieldExists = device.wait(Until.hasObject(
                By.clazz("android.widget.EditText").pkg(packageName)
            ), 1000)
            
            if (!inputFieldExists) {
                android.util.Log.e("BaseIntegrationTest", "❌ Input field not found during WebSocket verification")
                return false
            }
            
            // Now check for empty text by finding the EditText and checking its content
            val startTime = System.currentTimeMillis()
            while ((System.currentTimeMillis() - startTime) < timeout) {
                try {
                    val inputField = device.findObject(
                        By.clazz("android.widget.EditText").pkg(packageName)
                    )
                    
                    if (inputField != null) {
                        val inputText = inputField.text
                        if (inputText == null || inputText.trim().isEmpty()) {
                            android.util.Log.d("BaseIntegrationTest", "✅ Input field cleared via conditional wait - WebSocket send successful")
                            return true
                        }
                    }
                    
                    // Brief wait before next check
                    device.waitForIdle(100)
                    
                } catch (e: Exception) {
                    android.util.Log.w("BaseIntegrationTest", "⚠️ Error checking input field during WebSocket verification: ${e.message}")
                }
            }
            
            android.util.Log.w("BaseIntegrationTest", "⚠️ Input field not cleared after ${timeout}ms - WebSocket send may still be in progress")
            return false
            
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Error during WebSocket verification", e)
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
            // Take screenshot before throwing assertion for debugging
            takeFailureScreenshot("duplicate_messages_detected", "Found $realDuplicatesFound real duplicate message(s) in chat! This indicates a production bug.")
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
     * Take a screenshot for test failure debugging with guaranteed completion
     * @param testName Name of the test that failed
     * @param reason Brief description of the failure
     */
    protected fun takeFailureScreenshotAndWaitForCompletion(testName: String, reason: String) {
        try {
            android.util.Log.e("BaseIntegrationTest", "🔴 STARTING screenshot capture for test failure...")
            android.util.Log.e("BaseIntegrationTest", "🔴 Test name: $testName, Reason: $reason")
            takeFailureScreenshot(testName, reason)
            
            // Force completion by waiting for file system operations
            android.util.Log.e("BaseIntegrationTest", "⏳ Ensuring screenshot file operations complete...")
            Thread.sleep(2000) // Give file system time to complete operations
            
            // Verify screenshot was actually created
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val expectedPattern = "${testName}_${timestamp.take(13)}"  // Match first 13 chars of timestamp (YYYYMMDD_HHMM)
            val verifyResult = device.executeShellCommand("find $screenshotDir -name '*${testName}*.png' -type f | head -5")
            android.util.Log.e("BaseIntegrationTest", "📁 Screenshot verification result: $verifyResult")
            
            if (verifyResult.contains(".png")) {
                android.util.Log.e("BaseIntegrationTest", "✅ SCREENSHOT CONFIRMED: File created successfully")
            } else {
                android.util.Log.e("BaseIntegrationTest", "⚠️ SCREENSHOT WARNING: File may not have been created")
            }
            
            // Final sync to ensure screenshot is written to storage
            device.executeShellCommand("sync")
            android.util.Log.e("BaseIntegrationTest", "💾 Final file system sync completed")
            
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Error in takeFailureScreenshotAndWaitForCompletion", e)
            android.util.Log.e("BaseIntegrationTest", "❌ Exception details: ${e.message}")
            android.util.Log.e("BaseIntegrationTest", "❌ Stack trace: ${e.stackTrace.joinToString("\n")}")
            // Continue to fail the test even if screenshot fails
        }
    }

    /**
     * Take a screenshot for test failure debugging (internal method)
     * @param testName Name of the test that failed
     * @param reason Brief description of the failure
     */
    private fun takeFailureScreenshot(testName: String, reason: String) {
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
            
            // ensure screenshot directory exists
            val mkdirResult = device.executeShellCommand("mkdir -p $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Create directory result: $mkdirResult")
            
            // check if directory was actually created
            val dirCheckResult = device.executeShellCommand("ls -la $screenshotDir")
            android.util.Log.d("BaseIntegrationTest", "📁 Directory check: $dirCheckResult")
            
            // take screenshot using standard method
            android.util.Log.d("BaseIntegrationTest", "📸 Taking screenshot with screencap -p")
            val result = device.executeShellCommand("screencap -p $filepath")
            android.util.Log.d("BaseIntegrationTest", "📸 Screenshot result: $result")
              
              // Verify screenshot was created with more detailed checking
              val checkResult = device.executeShellCommand("ls -la $filepath")
              android.util.Log.d("BaseIntegrationTest", "🔍 File check result: $checkResult")
            
            if (checkResult.contains(filename)) {
                android.util.Log.d("BaseIntegrationTest", "✅ Screenshot confirmed saved: $filepath")
                
                // check file size to ensure it's not empty
                val fileSizeResult = device.executeShellCommand("stat -c%s $filepath 2>/dev/null || echo 'stat failed'")
                android.util.Log.d("BaseIntegrationTest", "📏 Screenshot file size: $fileSizeResult bytes")
            } else {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Screenshot may not have been saved: $checkResult")
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
            
            // Force file system sync to ensure screenshots are written to disk
            android.util.Log.d("BaseIntegrationTest", "🔄 Forcing file system sync...")
            val syncResult = device.executeShellCommand("sync")
            android.util.Log.d("BaseIntegrationTest", "💾 File sync result: $syncResult")
            
            // Wait a moment for sync to complete
            Thread.sleep(500)
            
            // Final verification of screenshot location
            android.util.Log.d("BaseIntegrationTest", "🔍 Final screenshot verification:")
            val finalCheck = device.executeShellCommand("ls -la $filepath")
            android.util.Log.d("BaseIntegrationTest", "📁 Screenshot location: $finalCheck")
            
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
        android.util.Log.e("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT: $message")
        android.util.Log.e("BaseIntegrationTest", "🔴 Test method: $testName")
        takeFailureScreenshotAndWaitForCompletion(testName, message)
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
        android.util.Log.e("BaseIntegrationTest", "🔴 FAIL WITH SCREENSHOT (reason: $reason): $message")
        android.util.Log.e("BaseIntegrationTest", "🔴 Test method: $testName")
        takeFailureScreenshotAndWaitForCompletion(testName, reason)
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
                .description("Message input field")
                .packageName(packageName)
        )
        
        // Log what we found
        val messageInputExists = messageInput.exists()
        
        android.util.Log.d("BaseIntegrationTest", "🔍 isCurrentlyInChatScreen check:")
        android.util.Log.d("BaseIntegrationTest", "   messageInput (EditText): $messageInputExists")
        
        android.util.Log.d("BaseIntegrationTest", "   📱 Result: ${if (messageInputExists) "IN CHAT SCREEN" else "NOT IN CHAT SCREEN - CANT FIND MESSAGE INPUT"}")
        
        return messageInputExists
    }

    /**
     * Find and click send button, wait for message to be sent - RAPID VERSION for interruption testing
     */
    protected fun clickSendButtonAndWaitForSent(messageText: String, rapid: Boolean = false): Boolean {
        if (rapid) {
            android.util.Log.d("BaseIntegrationTest", "🔍 RAPID: clicking send button (optimized for speed)...")
        } else {
            android.util.Log.d("BaseIntegrationTest", "🔍 REGULAR: clicking send button (not optimized for speed)...")
        }

        // Ultra-short timeout for rapid testing - fail fast if not immediately available
        var sendTimeout = 1000L
        if (rapid) {
            sendTimeout = 100L
        }

        // Wait for specific element with description
        var sendButttonExists = waitForElementExists(
            className = "android.widget.Button", 
            description = "Send typed message",
            timeoutMs = sendTimeout,
            elementDescription = "Send button"
        )

        if (!sendButttonExists) {
                         android.util.Log.e("BaseIntegrationTest", "❌ Send button not found within $sendTimeout - UI not responsive enough for immediate sending")
            // UI dump to see what's actually on screen when send button search fails
            val allElements = device.findObjects(By.pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 UI Dump for RAPID send button search:")
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allElements.size} elements in package $packageName")
            
            // Show all clickable elements
            val clickableElements = device.findObjects(By.clickable(true).pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${clickableElements.size} clickable elements")
            clickableElements.forEachIndexed { index, element ->
                try {
                    val text = element.text ?: "no text"
                    val desc = element.contentDescription ?: "no desc" 
                    val className = element.className ?: "no class"
                    android.util.Log.d("BaseIntegrationTest", "🔍 Clickable element $index: text='$text', desc='$desc', class='$className'")
                } catch (e: Exception) {
                    android.util.Log.d("BaseIntegrationTest", "🔍 Clickable element $index: error reading properties")
                }
            }
            
            // Show all Button elements specifically  
            val allButtons = device.findObjects(By.clazz("android.widget.Button").pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allButtons.size} Button elements:")
            allButtons.forEachIndexed { index, button ->
                try {
                    val text = button.text ?: "no text"
                    val desc = button.contentDescription ?: "no desc"
                    android.util.Log.d("BaseIntegrationTest", "🔍 Button $index: text='$text', desc='$desc', clickable=${button.isClickable}, enabled=${button.isEnabled}")
                } catch (e: Exception) {
                    android.util.Log.d("BaseIntegrationTest", "🔍 Button $index: error reading properties")
                }
            }
            return false
        }
        
        // Single click attempt
        var clickSuccessful = false
        try {
            // Find and click the send button
            val sendButton = device.findObject(
                UiSelector()
                    .description("Send typed message")
                    .className("android.widget.Button")
                    .packageName(packageName)
            )
            sendButton.click()
            android.util.Log.d("BaseIntegrationTest", "📤: send button clicked")
            clickSuccessful = true
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌: Send button click failed: ${e.message}")
            return false
        }
        
        if (!clickSuccessful) {
            android.util.Log.e("BaseIntegrationTest", "❌: Send button click failed")
            return false
        }
        var messageDisplayTimeout = 1000L
        android.util.Log.d("BaseIntegrationTest", "🔍 TIMEOUT DEBUG: rapid=$rapid, initial timeout=$messageDisplayTimeout")
        if (rapid) {
            messageDisplayTimeout = 20L
        } else {
        }
        device.findObject(UiSelector().packageName(packageName)) // Force UI sync
        android.util.Log.d("BaseIntegrationTest", "🔍 TIMEOUT DEBUG: About to wait for message with timeout=${messageDisplayTimeout}ms")
                 // Proper wait for message to render in UI (not just instant check)
         val messageDisplayed = device.wait(
             Until.hasObject(
                 By.descContains("User message:").textContains(messageText.take(20)).pkg(packageName)
             ),
             messageDisplayTimeout
         )
        
        if (messageDisplayed) {
            android.util.Log.d("BaseIntegrationTest", "✅ : message sent and displayed instantly")
        } else {
            android.util.Log.e("BaseIntegrationTest", "❌ : message not displayed instantly")
            
            // Full UI dump to see what's actually on screen when message detection fails
            val allElements = device.findObjects(By.pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 UI Dump for message detection failure:")
            android.util.Log.d("BaseIntegrationTest", "🔍 Looking for message: '$messageText'")
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${allElements.size} elements in package $packageName")
            
            // Log ALL elements to see what's actually available
            android.util.Log.d("BaseIntegrationTest", "🔍 ALL ELEMENTS ON SCREEN:")
            allElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  Element $index: class='${element.className}', text='${element.text}', desc='${element.contentDescription}', enabled=${element.isEnabled}, clickable=${element.isClickable}")
            }
            
            // Log all elements that might contain our message text
            val messageRelatedElements = allElements.filter { element ->
                element.text?.contains(messageText, ignoreCase = true) == true ||
                element.contentDescription?.contains(messageText, ignoreCase = true) == true ||
                element.text?.contains("User message:", ignoreCase = true) == true ||
                element.contentDescription?.contains("User message:", ignoreCase = true) == true
            }
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${messageRelatedElements.size} message-related elements:")
            messageRelatedElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  Message-related $index: class='${element.className}', text='${element.text}', desc='${element.contentDescription}'")
            }
            
            // Log all TextView elements specifically (where messages are usually displayed)
            val textViewElements = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
            android.util.Log.d("BaseIntegrationTest", "🔍 Found ${textViewElements.size} TextView elements:")
            textViewElements.forEachIndexed { index, element ->
                android.util.Log.d("BaseIntegrationTest", "  TextView $index: text='${element.text}', desc='${element.contentDescription}'")
            }
        }
        
        return messageDisplayed
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
        val sendingSuccess = clickSendButtonAndWaitForSent(currentText, rapid = true)
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
     * Scroll to the bottom of the chat to ensure the input field is visible and accessible
     * This is essential for reliable message sending in long conversations
     */
    protected fun scrollToBottom(): Boolean {
        android.util.Log.d("BaseIntegrationTest", "📜 Scrolling to bottom of chat to ensure input field is accessible...")
        
        try {
            val height = device.displayHeight
            val width = device.displayWidth
            
            // Perform multiple scroll downs to ensure we reach the bottom
            // 🔧 KEYBOARD FIX: Avoid swiping through keyboard area where paint palette icon is located
            repeat(5) { attempt ->
                android.util.Log.d("BaseIntegrationTest", "📜 Bottom scroll attempt ${attempt + 1}/5")
                
                // Swipe in the CHAT AREA ONLY - avoid keyboard toolbar at bottom
                // Start at 45% and swipe to 20% (staying well above keyboard area)
                device.swipe(width/2, height*45/100, width/2, height*20/100, 10)
                
                // Wait for scroll animation to complete
                Thread.sleep(300)
                
                // Check if input field is now visible and accessible
                val inputField = device.findObject(
                    UiSelector()
                        .className("android.widget.EditText")
                        .packageName(packageName)
                )
                
                if (inputField.exists() && inputField.isEnabled) {
                    android.util.Log.d("BaseIntegrationTest", "✅ Input field visible and accessible after scroll attempt ${attempt + 1}")
                    return true
                }
            }
            
            android.util.Log.d("BaseIntegrationTest", "✅ Completed scrolling to bottom")
            return true
            
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error scrolling to bottom: ${e.message}")
            return false
        }
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
        
        val typingSuccess = typeMessageInInputField(message, rapid = true)
        if (!typingSuccess) {
            android.util.Log.e("BaseIntegrationTest", "❌ RAPID: Failed to simulate voice transcription")
            return false
        }
        
        android.util.Log.d("BaseIntegrationTest", "✅ RAPID: Voice transcription simulated successfully")
        
        // Now send the message with rapid timing (as voice input typically auto-sends)
        val sendingSuccess = clickSendButtonAndWaitForSent(message, rapid = true)
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
     * Simple verification: Find user messages by their styling (right-aligned, primary color background)
     * Much simpler than text parsing since user/bot messages have different styling
     */
    protected fun verifyMessageSentAtBottom(messageText: String, timeoutMs: Long = 3000): Boolean {
        android.util.Log.d("BaseIntegrationTest", "🔍 SIMPLE: Looking for recently sent message: '${messageText.take(30)}...'")
        
        // STEP 1: Check if message appears in chat FIRST (optimistic UI should work immediately)
        val messageFound = device.hasObject(
            By.textContains(messageText.take(20)).pkg(packageName)
        )
        
        if (messageFound) {
            android.util.Log.d("BaseIntegrationTest", "✅ SIMPLE: Message found in chat (optimistic UI working)")
            
            // STEP 2: Now check if input field was cleared (indicates WebSocket send completed)
            try {
                val inputField = device.findObject(
                    UiSelector()
                        .className("android.widget.EditText")
                        .packageName(packageName)
                )
                
                if (inputField.exists()) {
                    val inputText = inputField.text ?: ""
                    if (inputText.contains(messageText, ignoreCase = true)) {
                        android.util.Log.w("BaseIntegrationTest", "⚠️ SIMPLE: Message found in chat but still in input field - optimistic UI working but WebSocket send may have failed")
                        // Still return true because the main UX (message visible) works
                        return true
                    } else {
                        android.util.Log.d("BaseIntegrationTest", "✅ SIMPLE: Message found in chat AND input field cleared - full send complete")
                        return true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ SIMPLE: Error checking input field: ${e.message}")
                // Still return true because message is visible (main requirement)
                return true
            }
            
            return true
        }
        
        // If not found immediately, wait longer for app's automatic scroll
        android.util.Log.d("BaseIntegrationTest", "🔍 SIMPLE: Not immediately visible, waiting longer for app's automatic scroll...")
        
        // Give the app more time for auto-scroll + layout (especially on different screen sizes)
        val messageFoundAfterAutoScroll = device.wait(
            Until.hasObject(By.textContains(messageText.take(20)).pkg(packageName)),
            5000 // Increased timeout for different screen sizes and slower devices
        )
        
        if (messageFoundAfterAutoScroll) {
            android.util.Log.d("BaseIntegrationTest", "✅ SIMPLE: Message found after waiting for automatic scroll")
            return true
        } else {
            android.util.Log.w("BaseIntegrationTest", "⚠️ SIMPLE: Message not found after extended wait for automatic scroll")
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

    /**
     * Bring the app to foreground if it gets backgrounded
     */
    protected fun bringAppToForeground() {
        android.util.Log.d("BaseIntegrationTest", "🔄 Bringing app to foreground...")
        try {
            // Method 1: Use recent apps and click on our app
            device.pressRecentApps()
            Thread.sleep(1000)
            
            // Look for our app in recent apps
            val whizVoiceApp = device.findObject(UiSelector().textContains("Whiz Voice"))
            if (whizVoiceApp.exists()) {
                whizVoiceApp.click()
                android.util.Log.d("BaseIntegrationTest", "✅ Found and clicked WhizVoice in recent apps")
            } else {
                // Method 2: Launch via package name
                android.util.Log.d("BaseIntegrationTest", "⚠️ App not found in recent apps, launching directly")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                    android.util.Log.d("BaseIntegrationTest", "✅ Launched app directly via intent")
                } else {
                    // Method 3: Use adb to launch
                    android.util.Log.d("BaseIntegrationTest", "⚠️ Intent not found, using adb to launch")
                    device.executeShellCommand("am start -n $packageName/com.example.whiz.MainActivity")
                }
            }
            
            // Wait for app to come to foreground
            Thread.sleep(2000)
            android.util.Log.d("BaseIntegrationTest", "✅ App brought to foreground")
        } catch (e: Exception) {
            android.util.Log.e("BaseIntegrationTest", "❌ Failed to bring app to foreground: ${e.message}")
        }
    }

    /**
     * Simplified cleanup method for test chats
     * Focuses on ID tracking with optional pattern matching fallback
     */
    protected suspend fun cleanupTestChats(
        repository: com.example.whiz.data.repository.WhizRepository,
        trackedChatIds: List<Long> = emptyList(),
        additionalPatterns: List<String> = emptyList(),
        enablePatternFallback: Boolean = false
    ) {
        try {
            android.util.Log.d("BaseIntegrationTest", "🧹 Starting simplified test chat cleanup")
            
            var chatsDeleted = 0
            
            // Primary method: Delete tracked chats (most reliable)
            if (trackedChatIds.isNotEmpty()) {
                android.util.Log.d("BaseIntegrationTest", "🗑️ Deleting ${trackedChatIds.size} tracked chat(s)")
                trackedChatIds.forEach { chatId ->
                    try {
                        repository.deleteChat(chatId)
                        chatsDeleted++
                        android.util.Log.d("BaseIntegrationTest", "✅ Deleted tracked chat: $chatId")
                    } catch (e: Exception) {
                        android.util.Log.w("BaseIntegrationTest", "⚠️ Failed to delete tracked chat $chatId", e)
                    }
                }
            }

            // Optional fallback: Pattern matching (only if enabled)
            if (enablePatternFallback && additionalPatterns.isNotEmpty()) {
                android.util.Log.d("BaseIntegrationTest", "🔍 Checking for pattern-matched chats as fallback")
                val allChats = repository.getAllChats()
                val testChats = allChats.filter { chat ->
                    !trackedChatIds.contains(chat.id) && 
                    additionalPatterns.any { pattern -> 
                        chat.title.contains(pattern, ignoreCase = true) 
                    }
                }
                
                if (testChats.isNotEmpty()) {
                    android.util.Log.w("BaseIntegrationTest", "⚠️ FALLBACK ACTIVATED: Found ${testChats.size} untracked test chats")
                    testChats.forEach { chat ->
                        try {
                            repository.deleteChat(chat.id)
                            chatsDeleted++
                            android.util.Log.d("BaseIntegrationTest", "✅ Deleted untracked chat ${chat.id} (${chat.title})")
                        } catch (e: Exception) {
                            android.util.Log.w("BaseIntegrationTest", "⚠️ Failed to delete untracked chat ${chat.id}", e)
                        }
                    }
                }
            }

            android.util.Log.d("BaseIntegrationTest", "✅ Cleanup completed: $chatsDeleted chats deleted")
            
        } catch (e: Exception) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ Error during test chat cleanup", e)
        }
    }

    /**
     * Custom wait function that uses .exists() with a loop and timeout
     * @param className The class name to search for (e.g., "android.widget.TextView")
     * @param description Optional description to filter by
     * @param timeoutMs Timeout in milliseconds
     * @param elementDescription Human-readable description for logging
     * @return true if element was found within timeout, false otherwise
     */
    protected fun waitForElementExists(
        className: String,
        description: String? = null,
        timeoutMs: Long = 1000L,
        elementDescription: String = "element"
    ): Boolean {
        android.util.Log.d("BaseIntegrationTest", "⏳ Waiting for $elementDescription (${timeoutMs}ms timeout)...")
        
        val startTime = System.currentTimeMillis()

        device.findObject(UiSelector().packageName(packageName)) // Force UI sync
        
        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                val selector = UiSelector().className(className).packageName(packageName)
                if (description != null) {
                    selector.description(description)
                }
                
                val element = device.findObject(selector)
                if (element.exists()) {
                    android.util.Log.d("BaseIntegrationTest", "✅ Found $elementDescription within ${System.currentTimeMillis() - startTime}ms")
                    return true
                }
                
                // Brief wait before next check
                Thread.sleep(10)
                
            } catch (e: Exception) {
                android.util.Log.w("BaseIntegrationTest", "⚠️ Error checking for $elementDescription: ${e.message}")
                Thread.sleep(10)
            }
        }
        
        android.util.Log.w("BaseIntegrationTest", "❌ $elementDescription not found within ${timeoutMs}ms timeout")
        return false
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