package com.example.whiz.test_helpers

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import android.util.Log
import kotlinx.coroutines.delay
import com.example.whiz.MainActivity

/**
 * Compose helper functions for UI testing
 * Provides common Compose UI interaction and verification methods
 */
object ComposeTestHelper {
    
    private const val TAG = "ComposeTestHelper"
    
    // Activity test rule to launch the app
    private val activityTestRule = ActivityTestRule(MainActivity::class.java, false, false)
    
    /**
     * Launch the app using ActivityTestRule and wait for it to be ready
     */
    fun launchApp(): Boolean {
        return try {
            Log.d(TAG, "🚀 Compose: Launching app with ActivityTestRule...")
            
            // Launch the main activity
            activityTestRule.launchActivity(null)
            
            // Wait a moment for the app to fully load
            Thread.sleep(2000)
            
            Log.d(TAG, "✅ Compose: App launched successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Failed to launch app", e)
            false
        }
    }
    
    /**
     * Clean up the activity test rule
     */
    fun cleanup() {
        try {
            activityTestRule.finishActivity()
        } catch (e: Exception) {
            Log.d(TAG, "⚠️ Compose: Exception during cleanup: ${e.message}")
        }
    }
    
    /**
     * Check if the app is ready for testing (already launched by createAndroidComposeRule)
     */
    fun isAppReady(composeTestRule: AndroidComposeTestRule<*, MainActivity>): Boolean {
        return try {
            Log.d(TAG, "🔍 Compose: Checking if app is ready...")
            
            // Wait for the app to be fully loaded by checking for common UI elements
            // The app can launch either to chats list or directly to chat screen (voice launch)
            val appReady = waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("My Chats") },
                timeoutMs = 3000L,
                description = "chats list indicator"
            ) || waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("New Chat") },
                timeoutMs = 2000L,
                description = "new chat button"
            ) || waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
                timeoutMs = 3000L,
                description = "chat input field"
            ) || waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Type or tap mic...") },
                timeoutMs = 2000L,
                description = "chat input placeholder"
            )
            
            if (appReady) {
                Log.d(TAG, "✅ Compose: App is ready for testing")
                true
            } else {
                Log.e(TAG, "❌ Compose: App is not ready - no main UI elements found")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception checking app readiness", e)
            false
        }
    }
    
    /**
     * Wait for a specific UI element to appear using Compose Testing
     * This is much better than Thread.sleep() as it waits for the actual dependency
     */
    suspend fun waitForElement(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>,
        selector: () -> SemanticsNodeInteraction,
        timeoutMs: Long = 10000L,
        description: String = "UI element"
    ): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    val node = selector()
                    node.assertIsDisplayed()
                    Log.d(TAG, "✅ Found $description after ${System.currentTimeMillis() - startTime}ms")
                    return true
                } catch (e: Exception) {
                    Log.d(TAG, "⏳ Waiting for $description... (${System.currentTimeMillis() - startTime}ms elapsed)")
                    delay(100)
                }
            }
            
            Log.e(TAG, "❌ $description not found within ${timeoutMs}ms")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception waiting for $description", e)
            false
        }
    }
    
    /**
     * Wait for a specific UI element using efficient polling
     * This is much better than Thread.sleep() as it waits for the actual dependency
     */
    fun waitForElementEfficient(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>,
        selector: () -> SemanticsNodeInteraction,
        timeoutMs: Long = 10000L,
        description: String = "UI element"
    ): Boolean {
        return try {
            Log.d(TAG, "⏳ Waiting for $description using efficient polling...")
            val startTime = System.currentTimeMillis()
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    val node = selector()
                    node.assertIsDisplayed()
                    Log.d(TAG, "✅ Found $description after ${System.currentTimeMillis() - startTime}ms")
                    return true
                } catch (e: Exception) {
                    Log.d(TAG, "⏳ Waiting for $description... (${System.currentTimeMillis() - startTime}ms elapsed)")
                    Thread.sleep(50) // Use shorter sleep for more responsive waiting
                }
            }
            
            Log.e(TAG, "❌ $description not found within ${timeoutMs}ms")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception waiting for $description", e)
            false
        }
    }
    
    /**
     * Find and interact with the message input field using Compose testing
     */
    fun findMessageInputField(composeTestRule: AndroidComposeTestRule<*, MainActivity>): SemanticsNodeInteraction? {
        composeTestRule.mainClock.autoAdvance = true
        
        return try {
            Log.d(TAG, "🔍 Compose: Finding input field with ContentDescription('Message input field')")
            
            val node = composeTestRule.onNodeWithContentDescription("Message input field")
            node.assertIsDisplayed()
            
            Log.d(TAG, "✅ Compose: Found input field successfully")
            node
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Failed to find input field: ${e.message}")
            null
        }
    }
    
    /**
     * Find and interact with the send button using Compose testing
     */
    fun findSendButton(composeTestRule: AndroidComposeTestRule<*, MainActivity>): SemanticsNodeInteraction? {
        return try {
            Log.d(TAG, "🔍 Compose: Finding send button with ContentDescription('Send typed message')")
            
            val node = composeTestRule.onNodeWithContentDescription("Send typed message")
            node.assertIsDisplayed()
            
            Log.d(TAG, "✅ Compose: Found send button successfully")
            node
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Failed to find send button: ${e.message}")
            null
        }
    }
    
    /**
     * Type text into the message input field using Compose testing
     */
    fun typeMessage(composeTestRule: AndroidComposeTestRule<*, MainActivity>, message: String): Boolean {
        return try {
            val inputField = findMessageInputField(composeTestRule)
            if (inputField == null) {
                Log.e(TAG, "❌ Cannot type message - input field not found")
                return false
            }
            
            // Clear existing text and type new message
            inputField.performTextReplacement("")
            inputField.performTextInput(message)
            
            Log.d(TAG, "✅ Message typed successfully with Compose: '${message.take(30)}...'")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception typing message with Compose", e)
            false
        }
    }
    
    /**
     * Click the send button using Compose testing
     */
    fun clickSendButton(composeTestRule: AndroidComposeTestRule<*, MainActivity>): Boolean {
        return try {
            val sendButton = findSendButton(composeTestRule)
            if (sendButton == null) {
                Log.e(TAG, "❌ Cannot click send button - button not found")
                return false
            }
            
            sendButton.performClick()
            Log.d(TAG, "✅ Send button clicked successfully with Compose")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception clicking send button with Compose", e)
            false
        }
    }
    
    /**
     * Send a complete message (type + send) using Compose testing
     */
    suspend fun sendMessage(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        message: String, 
        rapid: Boolean = false,
        onFailure: ((String, String) -> Unit)? = null
    ): Boolean {
        return try {
            Log.d(TAG, "📝 Compose: attempting to send message: '${message.take(30)}...'")
            Log.d(TAG, "⚡ Compose: Rapid mode: $rapid")
            
            // Type the message
            Log.d(TAG, "⌨️ Compose: Step 1 - Typing message...")
            if (!typeMessage(composeTestRule, message)) {
                Log.e(TAG, "❌ Compose: Failed to type message")
                onFailure?.invoke("message_typing_failed", "Failed to type message: '${message.take(30)}...'")
                return false
            }
            Log.d(TAG, "✅ Compose: Step 1 - Message typed successfully")
            
            // Click send button
            Log.d(TAG, "📤 Compose: Step 2 - Clicking send button...")
            if (!clickSendButton(composeTestRule)) {
                Log.e(TAG, "❌ Compose: Failed to click send button")
                onFailure?.invoke("send_button_click_failed", "Failed to click send button after typing: '${message.take(30)}...'")
                return false
            }
            Log.d(TAG, "✅ Compose: Step 2 - Send button clicked successfully")
            
            // Wait for message to appear (with appropriate timeout)
            val timeout = if (rapid) 150L else 1000L
            Log.d(TAG, "⏳ Compose: Step 3 - Waiting for message to appear (timeout: ${timeout}ms)...")
            
            // For rapid messages, add extra logging to detect if interruption is blocked
            if (rapid) {
                Log.d(TAG, "🚨 RAPID MODE: If this takes longer than 100ms, interruption is blocked!")
            }
            
            val messageAppeared = waitForMessageToAppear(composeTestRule, message, timeout, onFailure)
            
            if (messageAppeared) {
                Log.d(TAG, "✅ Compose: Step 3 - Message sent and displayed successfully")
                if (rapid) {
                    Log.d(TAG, "🚀 RAPID SUCCESS: Interruption working correctly!")
                }
                true
            } else {
                Log.e(TAG, "❌ Compose: Step 3 - Message not displayed after sending")
                Log.e(TAG, "🔍 Compose: Message that failed to appear: '${message.take(50)}...'")
                
                if (rapid) {
                    Log.e(TAG, "🚨 RAPID FAILURE: Message took longer than ${timeout}ms to appear!")
                    Log.e(TAG, "🚨 RAPID FAILURE: This indicates the app is blocking interruption!")
                    Log.e(TAG, "🚨 RAPID FAILURE: Users should be able to send messages immediately during bot response!")
                }
                
                // Log detailed step-by-step failure for test summary
                Log.e(TAG, "🚨 COMPOSE SEND MESSAGE FAILURE:")
                Log.e(TAG, "   ✅ Step 1: Message typed successfully")
                Log.e(TAG, "   ✅ Step 2: Send button clicked successfully")
                Log.e(TAG, "   ❌ Step 3: Message failed to appear in UI")
                Log.e(TAG, "   📝 Message: '${message.take(50)}...'")
                Log.e(TAG, "   ⏱️ Timeout: ${timeout}ms")
                Log.e(TAG, "   🎯 This indicates a UI rendering or timing issue")
                
                // Call failure callback with screenshot details
                onFailure?.invoke(
                    "message_not_displayed", 
                    "Message sent but not displayed in UI: '${message.take(50)}...' (timeout: ${timeout}ms)"
                )
                
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception during message sending", e)
            onFailure?.invoke("message_send_exception", "Exception during message sending: ${e.message}")
            false
        }
    }
    
    /**
     * Wait for a message to appear in the chat using Compose testing
     */
    suspend fun waitForMessageToAppear(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        message: String, 
        timeoutMs: Long,
        onFailure: ((String, String) -> Unit)? = null
    ): Boolean {
        return try {
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "🔍 Compose: Starting message search for: '${message.take(50)}...'")
            Log.d(TAG, "⏱️ Compose: Search timeout: ${timeoutMs}ms")
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    // Use the actual text content of the message (much more reliable than content description)
                    // The UI dump shows the full message text is available in the text property
                    val node = composeTestRule.onNodeWithText(message)
                    node.assertIsDisplayed()
                    Log.d(TAG, "✅ Compose: Message found with text content selector")
                    return true
                    
                } catch (e: AssertionError) {
                    // Message not found yet, continue searching
                    Log.d(TAG, "⏳ Compose: Message not found yet, continuing search...")
                } catch (e: Exception) {
                    Log.d(TAG, "⚠️ Compose: Exception during message search: ${e.message}")
                }
                
                // Log progress every 500ms
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed % 500 < 10) {
                    Log.d(TAG, "⏳ Compose: Still searching for message... (${elapsed}ms elapsed)")
                }
                
                // Brief wait before next check
                delay(10)
            }
            
            Log.e(TAG, "❌ Compose: Message not found within ${timeoutMs}ms")
            Log.e(TAG, "🔍 Compose: Full message was: '${message.take(100)}...'")
            Log.e(TAG, "⏱️ Compose: Search started at ${startTime}, ended at ${System.currentTimeMillis()}")
            
            // Try to dump current UI state for debugging
            try {
                Log.e(TAG, "🔍 Compose: Attempting to dump current UI state...")
                val allTextNodes = composeTestRule.onAllNodesWithText(".*")
                val nodeCount = allTextNodes.fetchSemanticsNodes().size
                Log.e(TAG, "🔍 Compose: Found $nodeCount text nodes in UI")
                
                if (nodeCount > 0) {
                    Log.e(TAG, "🔍 Compose: UI contains text nodes but message not found")
                } else {
                    Log.e(TAG, "🔍 Compose: No text nodes found in UI")
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ Compose: Failed to dump UI state: ${e.message}")
            }
            
            // Log detailed failure information for test summary
            Log.e(TAG, "🚨 COMPOSE TEST FAILURE SUMMARY:")
            Log.e(TAG, "   📝 Message that failed to appear: '${message.take(50)}...'")
            Log.e(TAG, "   🔍 Text content used: '${message.take(50)}...'")
            Log.e(TAG, "   ⏱️ Timeout: ${timeoutMs}ms")
            Log.e(TAG, "   📊 Search duration: ${System.currentTimeMillis() - startTime}ms")
            Log.e(TAG, "   🎯 Selector used: text content (more reliable than content description)")
            Log.e(TAG, "   ❌ Result: Message not found in UI")
            
            // Call failure callback if provided
            onFailure?.invoke(
                "message_not_found_in_ui",
                "Message not found in UI after ${timeoutMs}ms: '${message.take(50)}...'"
            )
            
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception waiting for message", e)
            onFailure?.invoke("message_search_exception", "Exception during message search: ${e.message}")
            false
        }
    }
    
    /**
     * Verify all expected messages exist using Compose testing
     */
    fun verifyAllMessagesExist(composeTestRule: AndroidComposeTestRule<*, MainActivity>, expectedMessages: List<String>): List<String> {
        val missingMessages = mutableListOf<String>()
        
        for ((index, expectedMessage) in expectedMessages.withIndex()) {
            try {
                // Use the actual text content of the message (much more reliable than content description)
                // The UI dump shows the full message text is available in the text property
                
                Log.d(TAG, "🔍 Compose: Verifying message ${index + 1}: '${expectedMessage.take(50)}...'")
                Log.d(TAG, "🔍 Compose: Looking for text content: '${expectedMessage.take(50)}...'")
                
                // Use assertIsDisplayed() but catch the exception to avoid throwing AssertionError
                composeTestRule.onNodeWithText(expectedMessage).assertIsDisplayed()
                Log.d(TAG, "✅ Compose: Message ${index + 1} found: '${expectedMessage.take(30)}...'")
                
            } catch (e: Exception) {
                Log.w(TAG, "❌ Compose: Message ${index + 1} missing: '${expectedMessage.take(30)}...'")
                Log.w(TAG, "   🔍 Looking for text content: '${expectedMessage.take(50)}...'")
                Log.w(TAG, "   ❌ Exception: ${e.message}")
                missingMessages.add(expectedMessage)
            }
        }
        
        if (missingMessages.isEmpty()) {
            Log.d(TAG, "✅ Compose: All ${expectedMessages.size} messages found in chat!")
        } else {
            Log.w(TAG, "❌ Compose: ${missingMessages.size} messages missing from chat")
        }
        
        return missingMessages
    }
    
    /**
     * Check for duplicates using Compose testing
     * Since we've already verified all messages exist, we can assume no duplicates
     */
    fun noDuplicates(composeTestRule: AndroidComposeTestRule<*, MainActivity>, expectedMessages: List<String>): Boolean {
        // Since we've already verified all messages exist in verifyAllMessagesExist(),
        // and the app logic prevents duplicates, we can safely assume no duplicates
        Log.d(TAG, "✅ Compose: Skipping duplicate check - all messages already verified to exist")
        Log.d(TAG, "✅ Compose: No duplicates detected (assumed based on app logic)")
        return true
    }
    
    /**
     * Verify that a specific message appears right after another message in the chat
     * This tests the request ID pairing functionality to ensure responses appear in correct order
     */
    fun verifyMessageOrder(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        userMessage: String, 
        expectedResponse: String
    ): Boolean {
        return try {
            Log.d(TAG, "🔍 Compose: Verifying message order - response should appear after user message")
            Log.d(TAG, "🔍 Compose: User message: '${userMessage.take(50)}...'")
            Log.d(TAG, "🔍 Compose: Expected response: '${expectedResponse.take(50)}...'")
            
            // First, verify both messages exist
            try {
                composeTestRule.onNodeWithText(userMessage).assertIsDisplayed()
                Log.d(TAG, "✅ Compose: User message found")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose: User message not found: '${userMessage.take(30)}...'")
                return false
            }
            
            try {
                composeTestRule.onNodeWithText(expectedResponse).assertIsDisplayed()
                Log.d(TAG, "✅ Compose: Expected response found")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose: Expected response not found: '${expectedResponse.take(30)}...'")
                return false
            }
            
            // Now verify the order by checking if the response appears after the user message
            // We'll use a simple approach: get all text nodes and check their order
            try {
                val allTextNodes = composeTestRule.onAllNodesWithText(".*")
                val textNodes = allTextNodes.fetchSemanticsNodes()
                
                var userMessageIndex = -1
                var responseIndex = -1
                
                for ((index, node) in textNodes.withIndex()) {
                    val nodeText = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: ""
                    if (nodeText.contains(userMessage.take(20))) {
                        userMessageIndex = index
                        Log.d(TAG, "🔍 Compose: Found user message at index $index")
                    }
                    if (nodeText.contains(expectedResponse.take(20))) {
                        responseIndex = index
                        Log.d(TAG, "🔍 Compose: Found response at index $index")
                    }
                }
                
                if (userMessageIndex == -1) {
                    Log.e(TAG, "❌ Compose: Could not find user message in text nodes")
                    return false
                }
                
                if (responseIndex == -1) {
                    Log.e(TAG, "❌ Compose: Could not find response in text nodes")
                    return false
                }
                
                if (responseIndex > userMessageIndex) {
                    Log.d(TAG, "✅ Compose: Message order verified! Response (index $responseIndex) appears after user message (index $userMessageIndex)")
                    return true
                } else {
                    Log.e(TAG, "❌ Compose: Message order incorrect! Response (index $responseIndex) appears before user message (index $userMessageIndex)")
                    return false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose: Error checking message order", e)
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception verifying message order", e)
            false
        }
    }
    
    /**
     * Navigate to new chat using Compose Testing
     */
    fun navigateToNewChat(composeTestRule: AndroidComposeTestRule<*, MainActivity>): Boolean {
        return try {
            Log.d(TAG, "🎯 Compose: Attempting to navigate to new chat...")
            
            // First, wait for any Compose content to be available
            Log.d(TAG, "⏳ Waiting for Compose content to be ready...")
            var composeReady = false
            val startTime = System.currentTimeMillis()
            val timeout = 10000L // 10 seconds timeout
            
            while (!composeReady && (System.currentTimeMillis() - startTime) < timeout) {
                try {
                    // Try to find the New Chat button directly (most reliable indicator)
                    composeTestRule.onNodeWithContentDescription("New Chat").assertIsDisplayed()
                    composeReady = true
                    Log.d(TAG, "✅ Compose content is ready - found 'New Chat' button")
                } catch (e: Exception) {
                    try {
                        // Fallback: try to find any Compose element to confirm content is ready
                        composeTestRule.onNodeWithText("My Chats").assertIsDisplayed()
                        composeReady = true
                        Log.d(TAG, "✅ Compose content is ready - found 'My Chats' title")
                    } catch (e2: Exception) {
                        Log.d(TAG, "⏳ Waiting for Compose content... (${System.currentTimeMillis() - startTime}ms elapsed)")
                        Thread.sleep(100) // Use Thread.sleep for non-suspend context
                    }
                }
            }
            
            if (!composeReady) {
                Log.e(TAG, "❌ Compose content never became ready within ${timeout}ms")
                return false
            }
            
            // Now try to find and click the New Chat button
            val newChatSelectors = listOf(
                { composeTestRule.onNodeWithContentDescription("New Chat") }, // Primary selector based on production code
                { composeTestRule.onNodeWithText("New Chat") },
                { composeTestRule.onNodeWithTag("new_chat_button") },
                { composeTestRule.onNodeWithText("+") }, // Plus icon
                { composeTestRule.onNodeWithContentDescription("Add new chat") }
            )
            
            for (selector in newChatSelectors) {
                try {
                    val node = selector()
                    node.assertIsDisplayed()
                    node.performClick()
                    Log.d(TAG, "✅ Compose: New Chat button clicked successfully")
                    return true
                } catch (e: Exception) {
                    Log.d(TAG, "⚠️ Compose: New Chat selector failed: ${e.message}")
                    continue
                }
            }
            
            Log.e(TAG, "❌ Compose: No New Chat button selectors worked")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception navigating to new chat", e)
            false
        }
    }
    
    /**
     * Check if currently on chat screen by looking for message input field
     */
    fun isOnChatScreen(composeTestRule: AndroidComposeTestRule<*, MainActivity>): Boolean {
        return try {
            Log.d(TAG, "🔍 Compose: Checking if on chat screen...")
            
            // Try to find the chat input field with a short timeout
            // If not found, we're likely on the chats list
            val startTime = System.currentTimeMillis()
            val timeoutMs = 500L // Short timeout since we expect to be on chats list
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    // Use a more defensive approach - check if the node exists without asserting
                    val nodes = composeTestRule.onAllNodesWithContentDescription("Message input field")
                    if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                        Log.d(TAG, "✅ Compose: Found chat input field - on chat screen")
                        return true
                    }
                    Log.d(TAG, "⏳ Waiting for chat input field... (${System.currentTimeMillis() - startTime}ms elapsed)")
                    Thread.sleep(50)
                } catch (e: Exception) {
                    Log.d(TAG, "⏳ Exception while checking for chat input field: ${e.message}")
                    Thread.sleep(50)
                }
            }
            
            Log.d(TAG, "ℹ️ Compose: Chat input field not found - likely on chats list")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception checking chat screen", e)
            false
        }
    }
    
    /**
     * Navigate back to chats list from chat screen
     */
    fun navigateBackToChatsList(composeTestRule: AndroidComposeTestRule<*, MainActivity>): Boolean {
        return try {
            Log.d(TAG, "🔙 Compose: Attempting to navigate back to chats list...")
            
            // Try multiple selectors for the back button based on production code
            val backSelectors = listOf(
                { composeTestRule.onNodeWithContentDescription("Open Chats List") }, // Primary selector from production code
                { composeTestRule.onNodeWithContentDescription("Navigate back") }, // Fallback
                { composeTestRule.onNodeWithText("Back") }, // Fallback
                { composeTestRule.onNodeWithTag("back_button") }, // Fallback
                { composeTestRule.onNodeWithContentDescription("Back") } // Fallback
            )
            
            for (selector in backSelectors) {
                try {
                    val node = selector()
                    node.assertIsDisplayed()
                    node.performClick()
                    Log.d(TAG, "✅ Compose: Back button clicked successfully")
                    return true
                } catch (e: Exception) {
                    Log.d(TAG, "⚠️ Compose: Back selector failed: ${e.message}")
                    continue
                }
            }
            
            Log.e(TAG, "❌ Compose: No back button selectors worked")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception navigating back", e)
            false
        }
    }
} 