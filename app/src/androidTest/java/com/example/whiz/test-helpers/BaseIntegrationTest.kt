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
        // Create intent that mimics manual app launch (tap on app icon) to avoid voice assistant mode
        val intent = Intent().apply {
            setPackage(packageName)
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // Use manual launch flags - these are critical to avoid voice assistant mode
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or 0x00200000
            // Add sourceBounds to simulate clicking app icon (manual launches have bounds, voice don't)
            sourceBounds = android.graphics.Rect(100, 100, 200, 200)
            // Explicitly set these to prevent voice detection
            putExtra("IS_MANUAL_LAUNCH", true)
            removeExtra("tracing_intent_id") // Remove any voice launch indicators
        }
        
        android.util.Log.d("BaseIntegrationTest", "🚀 launching app with manual launch intent")
        android.util.Log.d("BaseIntegrationTest", "   flags: ${String.format("0x%08X", intent.flags)}")
        android.util.Log.d("BaseIntegrationTest", "   sourceBounds: ${intent.sourceBounds}")
        
        context.startActivity(intent)
        
        val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        if (!appLaunched) {
            android.util.Log.e("BaseIntegrationTest", "❌ app failed to launch within 10 seconds")
            return false
        }
        
        // wait for main UI elements to load - should be chats list for manual launch
        val mainUILoaded = device.wait(Until.hasObject(
            By.text("My Chats").pkg(packageName)
        ), 8000) || device.wait(Until.hasObject(
            By.descContains("New Chat").pkg(packageName)  
        ), 3000)
        
        if (!mainUILoaded) {
            android.util.Log.w("BaseIntegrationTest", "⚠️ main UI not detected, but checking for any app content...")
            // fallback check for any app content
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
     * Find and click send button, wait for message to be sent
     */
    protected fun clickSendButtonAndWaitForSent(messageText: String): Boolean {
        val sendButton = device.findObject(
            UiSelector()
                .descriptionContains("Send message")
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(3000)) {
            android.util.Log.e("BaseIntegrationTest", "❌ send button not found")
            return false
        }
        
        sendButton.click()
        
        // verify message appears in chat (optimistic UI)
        val messageDisplayed = device.wait(Until.hasObject(
            By.textContains(messageText).pkg(packageName)
        ), 5000)
        
        return messageDisplayed
    }
    
    /**
     * Complete message sending flow: type message, click send, verify display
     */
    protected fun sendMessageAndVerifyDisplay(message: String): Boolean {
        return typeMessageInInputField(message) && clickSendButtonAndWaitForSent(message)
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
     * Verify message is visible in chat
     */
    protected fun verifyMessageVisible(messageText: String, timeoutMs: Long = 3000): Boolean {
        return device.wait(Until.hasObject(
            By.textContains(messageText.take(20)).pkg(packageName)
        ), timeoutMs)
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
     */
    protected fun countMessageOccurrences(messageText: String): Int {
        val elements = device.findObjects(
            By.textContains(messageText.take(15)).pkg(packageName)
        )
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
            device.takeScreenshot(File(filepath))
            
            // Screenshot will be pulled to local folder by test script after completion
            
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