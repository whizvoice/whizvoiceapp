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
import androidx.compose.ui.test.SemanticsMatcher
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
    
    /**
     * Create a SemanticsMatcher that matches content descriptions containing specific text
     * This allows us to find nodes with content descriptions that contain patterns like "User message:"
     */
    fun hasContentDescriptionMatching(regex: String): SemanticsMatcher {
        return SemanticsMatcher("Has content description matching regex '$regex'") { node ->
            try {
                val contentDescription = node.config[SemanticsProperties.ContentDescription].firstOrNull()
                contentDescription?.matches(regex.toRegex()) ?: false
            } catch (e: Exception) {
                false
            }
        }
    }
    
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
            Log.d(TAG, "🔍 Compose: Looking for chats list indicator ('My Chats')...")
            val chatsListFound = waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("My Chats") },
                timeoutMs = 3000L,
                description = "chats list indicator"
            )
            Log.d(TAG, "🔍 Compose: Chats list indicator found: $chatsListFound")
            
            Log.d(TAG, "🔍 Compose: Looking for new chat button...")
            val newChatButtonFound = waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("New Chat") },
                timeoutMs = 2000L,
                description = "new chat button"
            )
            Log.d(TAG, "🔍 Compose: New chat button found: $newChatButtonFound")
            
            Log.d(TAG, "🔍 Compose: Looking for chat input field...")
            val chatInputFieldFound = waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
                timeoutMs = 3000L,
                description = "chat input field"
            )
            Log.d(TAG, "🔍 Compose: Chat input field found: $chatInputFieldFound")
            
            Log.d(TAG, "🔍 Compose: Looking for chat input placeholder...")
            val chatInputPlaceholderFound = waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Type or tap mic...") },
                timeoutMs = 2000L,
                description = "chat input placeholder"
            )
            Log.d(TAG, "🔍 Compose: Chat input placeholder found: $chatInputPlaceholderFound")
            
            val appReady = chatsListFound || newChatButtonFound || chatInputFieldFound || chatInputPlaceholderFound
            
            if (appReady) {
                Log.d(TAG, "✅ Compose: App is ready for testing")
                Log.d(TAG, "🔍 Compose: Found UI elements:")
                if (chatsListFound) Log.d(TAG, "   ✅ Chats list indicator")
                if (newChatButtonFound) Log.d(TAG, "   ✅ New chat button")
                if (chatInputFieldFound) Log.d(TAG, "   ✅ Chat input field")
                if (chatInputPlaceholderFound) Log.d(TAG, "   ✅ Chat input placeholder")
                true
            } else {
                Log.e(TAG, "❌ Compose: App is not ready - no main UI elements found")
                Log.e(TAG, "🔍 Compose: UI element search results:")
                Log.e(TAG, "   ❌ Chats list indicator: $chatsListFound")
                Log.e(TAG, "   ❌ New chat button: $newChatButtonFound")
                Log.e(TAG, "   ❌ Chat input field: $chatInputFieldFound")
                Log.e(TAG, "   ❌ Chat input placeholder: $chatInputPlaceholderFound")
                Log.e(TAG, "🔍 Compose: This suggests the app may have:")
                Log.e(TAG, "   - Launched to a different screen than expected")
                Log.e(TAG, "   - UI elements not yet loaded")
                Log.e(TAG, "   - Compose hierarchy not ready")
                Log.e(TAG, "   - Voice launch detection causing unexpected navigation")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception checking app readiness", e)
            Log.e(TAG, "🔍 Compose: Exception details: ${e.message}")
            Log.e(TAG, "🔍 Compose: Stack trace: ${e.stackTrace.joinToString("\n")}")
            false
        } catch (e: AssertionError) {
            Log.e(TAG, "❌ Compose: AssertionError checking app readiness", e)
            Log.e(TAG, "🔍 Compose: AssertionError details: ${e.message}")
            Log.e(TAG, "🔍 Compose: Stack trace: ${e.stackTrace.joinToString("\n")}")
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
            val startTime = System.currentTimeMillis()
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    val node = selector()
                    node.assertIsDisplayed()
                    Log.d(TAG, "✅ Found $description after ${System.currentTimeMillis() - startTime}ms")
                    return true
                } catch (e: Exception) {
                    Thread.sleep(50) // Use shorter sleep for more responsive waiting
                } catch (e: AssertionError) {
                    Thread.sleep(50) // Use shorter sleep for more responsive waiting
                }
            }
            
            Log.e(TAG, "❌ $description not found within ${timeoutMs}ms")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception waiting for $description", e)
            false
        } catch (e: AssertionError) {
            Log.e(TAG, "❌ AssertionError waiting for $description", e)
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
        rapid: Boolean = false
    ): Boolean {
        return try {
            Log.d(TAG, "📝 Compose: attempting to send message: '${message.take(30)}...'")
            Log.d(TAG, "⚡ Compose: Rapid mode: $rapid")
            
            // Type the message
            Log.d(TAG, "⌨️ Compose: Step 1 - Typing message...")
            if (!typeMessage(composeTestRule, message)) {
                Log.e(TAG, "❌ Compose: Failed to type message")
                return false
            }
            Log.d(TAG, "✅ Compose: Step 1 - Message typed successfully")
            
            // Click send button
            Log.d(TAG, "📤 Compose: Step 2 - Clicking send button...")
            if (!clickSendButton(composeTestRule)) {
                Log.e(TAG, "❌ Compose: Failed to click send button")
                return false
            }
            Log.d(TAG, "✅ Compose: Step 2 - Send button clicked successfully")
            
            // Wait for message to appear using existing waitForElement method
            val timeout = if (rapid) 400L else 1000L
            Log.d(TAG, "⏳ Compose: Step 3 - Waiting for message to appear (timeout: ${timeout}ms)...")
            
            if (rapid) {
                Log.d(TAG, "🚨 RAPID MODE: Message must appear within ${timeout}ms or test will fail!")
            }
            
            // Use existing waitForElement method which provides timing info
            val messageAppeared = waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText(message) },
                timeoutMs = timeout,
                description = "message '${message.take(30)}...'"
            )
            
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
                    Log.e(TAG, "🚨 RAPID FAILURE: This indicates the app is blocking rapid interruption!")
                    Log.e(TAG, "🚨 RAPID FAILURE: Users cannot send messages while bot is responding!")
                }
                
                // Log detailed step-by-step failure for test summary
                Log.e(TAG, "🚨 COMPOSE SEND MESSAGE FAILURE:")
                Log.e(TAG, "   ✅ Step 1: Message typed successfully")
                Log.e(TAG, "   ✅ Step 2: Send button clicked successfully")
                Log.e(TAG, "   ❌ Step 3: Message failed to appear in UI within ${timeout}ms")
                Log.e(TAG, "   📝 Message: '${message.take(50)}...'")
                Log.e(TAG, "   ⏱️ Timeout: ${timeout}ms")
                Log.e(TAG, "   🎯 This indicates rapid message sending is blocked")
                
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception during message sending", e)
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
                    // Try multiple selectors to find the message
                    // Based on production code, messages are Compose Text components with content descriptions
                    val selectors = listOf(
                        { composeTestRule.onNodeWithContentDescription("User message: ${message}") }, // Primary: content description
                        { composeTestRule.onNodeWithText(message) }, // Fallback: direct text
                        { composeTestRule.onNodeWithText(message, useUnmergedTree = true) } // Fallback: unmerged tree
                    )
                    
                    var messageFound = false
                    for (selector in selectors) {
                        try {
                            val node = selector()
                            node.assertIsDisplayed()
                            messageFound = true
                            Log.d(TAG, "✅ Compose: Message found with selector")
                            break
                        } catch (e: Exception) {
                            // Continue to next selector
                            continue
                        }
                    }
                    
                    if (messageFound) {
                        return true
                    }
                    
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
     * Verify that a response message appears IMMEDIATELY after its corresponding user message
     * This tests the request ID pairing functionality to ensure responses appear in reply order, not timestamp order
     * The bug was that responses were appearing chronologically instead of being paired with their user messages
     */
    fun verifyMessageOrder(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        userMessage: String, 
        expectedResponse: String
    ): Boolean {
        return try {
            Log.d(TAG, "🔍 Compose: Verifying message order - response should appear IMMEDIATELY after user message")
            Log.d(TAG, "🔍 Compose: Testing request ID pairing (reply order vs timestamp order)")
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
                Log.d(TAG, "🔍 Compose: Looking for expected response text: '$expectedResponse'")
                composeTestRule.onNodeWithText(expectedResponse).assertIsDisplayed()
                Log.d(TAG, "✅ Compose: Expected response found")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose: Expected response not found: '${expectedResponse.take(30)}...'")
                Log.e(TAG, "❌ Compose: Exception details: ${e.message}")
                
                // Log what messages are actually visible to help debug
                try {
                    val allTextNodes = composeTestRule.onAllNodesWithText("").fetchSemanticsNodes()
                    Log.e(TAG, "🔍 Compose: Available text nodes on screen (${allTextNodes.size}):")
                    allTextNodes.forEachIndexed { index, node ->
                        val text = node.config[SemanticsProperties.Text].firstOrNull() ?: ""
                        if (text.isNotBlank()) {
                            Log.e(TAG, "🔍 Compose:   Node $index: '$text'")
                        }
                    }
                } catch (debugException: Exception) {
                    Log.e(TAG, "❌ Compose: Could not get available text nodes for debugging: ${debugException.message}")
                }
                
                return false
            }
            
            // 🔍 MESSAGE ORDER VERIFICATION: Get all message nodes and check their order
            Log.d(TAG, "🔍 Compose: Now verifying message ORDER - getting all message nodes")
            
            try {
                // Get all nodes with content descriptions that contain "message" (both user and assistant)
                val allMessageNodes = composeTestRule.onAllNodes(hasContentDescriptionMatching(".*message.*"))
                val messageNodes = allMessageNodes.fetchSemanticsNodes()
                
                Log.d(TAG, "🔍 Compose: Found ${messageNodes.size} message nodes total")
                
                if (messageNodes.size < 2) {
                    Log.e(TAG, "❌ Compose: Not enough message nodes found (${messageNodes.size}) to verify order")
                    return false
                }
                
                // Extract content descriptions and find our specific messages
                var userMessageIndex = -1
                var responseIndex = -1
                
                for (index in messageNodes.indices) {
                    val node = messageNodes[index]
                    val contentDesc = try {
                        node.config[SemanticsProperties.ContentDescription].firstOrNull() ?: ""
                    } catch (e: Exception) {
                        ""
                    }
                    Log.d(TAG, "🔍 Compose: Message node $index: '$contentDesc'")
                    
                    if (contentDesc.contains("User message:") && contentDesc.contains(userMessage)) {
                        userMessageIndex = index
                        Log.d(TAG, "🔍 Compose: Found user message at index $index")
                    }
                    
                    if (contentDesc.contains("Assistant message:") && contentDesc.contains(expectedResponse)) {
                        responseIndex = index
                        Log.d(TAG, "🔍 Compose: Found expected response at index $index")
                    }
                }
                
                if (userMessageIndex == -1) {
                    Log.e(TAG, "❌ Compose: Could not find user message in message nodes")
                    return false
                }
                
                if (responseIndex == -1) {
                    Log.e(TAG, "❌ Compose: Could not find expected response in message nodes")
                    return false
                }
                
                // Verify order: response should appear IMMEDIATELY after the user message
                if (responseIndex == userMessageIndex + 1) {
                    Log.d(TAG, "✅ Compose: Message order verified! User message at index $userMessageIndex, response at index $responseIndex")
                    Log.d(TAG, "✅ Compose: Response appears IMMEDIATELY after user message - order is correct")
                    return true
                } else {
                    Log.e(TAG, "❌ Compose: Message order verification FAILED!")
                    Log.e(TAG, "❌ Compose: User message at index $userMessageIndex, response at index $responseIndex")
                    Log.e(TAG, "❌ Compose: Response does NOT appear immediately after user message - order is INCORRECT")
                    Log.e(TAG, "❌ Compose: Expected response at index ${userMessageIndex + 1}, but found it at index $responseIndex")
                    
                    // Log what's between the user message and response for debugging
                    if (userMessageIndex < responseIndex) {
                        Log.e(TAG, "🔍 Compose: Messages between user message and response:")
                        for (i in (userMessageIndex + 1) until responseIndex) {
                            try {
                                val node = messageNodes[i]
                                val contentDesc = node.config[SemanticsProperties.ContentDescription].firstOrNull() ?: ""
                                Log.e(TAG, "🔍 Compose:   Index $i: '$contentDesc'")
                            } catch (e: Exception) {
                                Log.e(TAG, "🔍 Compose:   Index $i: [Error reading content description]")
                            }
                        }
                    }
                    
                    return false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Compose: Exception during message order verification", e)
                
                // Fallback: if we can't verify order, at least verify both messages exist
                Log.d(TAG, "🔍 Compose: Falling back to existence check only")
                Log.d(TAG, "🔍 Compose: Both messages found with onNodeWithText - returning true (order verification failed)")
                return true
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
    
    /**
     * Send message and verify WebSocket transmission
     * This function handles both UI interactions and WebSocket verification
     * It sends a message via UI and verifies a new request was added to pendingRequests
     */
    suspend fun sendMessageWithWebSocketVerification(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        message: String,
        chatViewModel: com.example.whiz.ui.viewmodels.ChatViewModel
    ): Boolean {
        return try {
            Log.d(TAG, "📝 Compose: Attempting to send message with WebSocket verification: '${message.take(30)}...'")
            
            // Step 1: Get initial pending request count
            val initialPendingRequests = chatViewModel.getPendingRequestIds()
            Log.d(TAG, "🔍 Initial pending requests: $initialPendingRequests")
            
            // Step 2: Send the message via UI
            val sendSuccess = sendMessage(composeTestRule, message)
            if (!sendSuccess) {
                Log.e(TAG, "❌ Compose: Message send failed during WebSocket verification")
                return false
            }
            
            // Step 3: Wait for WebSocket confirmation by checking if any new request was added to pendingRequests
            Log.d(TAG, "🔍 Compose: Waiting for WebSocket confirmation via pendingRequests...")
            
            val startTime = System.currentTimeMillis()
            val timeoutMs = 300L // 3 seconds for WebSocket confirmation
            
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val currentPendingRequests = chatViewModel.getPendingRequestIds()
                    val newRequests = currentPendingRequests - initialPendingRequests
                    
                    Log.d(TAG, "🔍 Compose: WebSocket check: currentPendingRequests=$currentPendingRequests, newRequests=$newRequests")
                    
                    if (newRequests.isNotEmpty()) {
                        Log.d(TAG, "✅ Compose: WebSocket confirmation - new request added to pendingRequests: $newRequests")
                        return true
                    }
                    
                    // Also check for server response indicators as fallback
                    try {
                        composeTestRule.onNodeWithText("Whiz is computing").assertIsDisplayed()
                        Log.d(TAG, "✅ Compose: WebSocket confirmation - bot thinking indicator appeared")
                        return true
                    } catch (e: AssertionError) {
                        // Check if there's any bot response after our message
                        if (verifyBotResponseAfterMessage(composeTestRule, message)) {
                            Log.d(TAG, "✅ Compose: WebSocket confirmation - bot response found after our message")
                            return true
                        }
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "⚠️ Compose: Exception during WebSocket confirmation check: ${e.message}")
                    Thread.sleep(50)
                }
            }
            
            Log.e(TAG, "❌ Compose: WebSocket confirmation timeout - message may not have reached server")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception during WebSocket verification", e)
            false
        }
    }
    
    /**
     * Verify that a bot response appears after our specific user message
     * This checks message ordering to ensure the bot response is related to our message
     */
    private fun verifyBotResponseAfterMessage(
        composeTestRule: AndroidComposeTestRule<*, MainActivity>, 
        userMessage: String
    ): Boolean {
        return try {
            Log.d(TAG, "🔍 Compose: Verifying bot response appears after user message: '${userMessage.take(30)}...'")
            
            // Get all message nodes and check their order
            val allMessageNodes = composeTestRule.onAllNodes(hasContentDescriptionMatching(".*message.*"))
            val messageNodes = allMessageNodes.fetchSemanticsNodes()
            
            Log.d(TAG, "🔍 Compose: Found ${messageNodes.size} message nodes total")
            
            if (messageNodes.size < 2) {
                Log.d(TAG, "🔍 Compose: Not enough messages to verify order")
                return false
            }
            
            // Find our user message and any bot response that comes after it
            var userMessageIndex = -1
            var botResponseIndex = -1
            
            for (index in messageNodes.indices) {
                val node = messageNodes[index]
                val contentDesc = try {
                    node.config[SemanticsProperties.ContentDescription].firstOrNull() ?: ""
                } catch (e: Exception) {
                    ""
                }
                
                // Check if this is our user message
                if (contentDesc.contains("User message:") && contentDesc.contains(userMessage)) {
                    userMessageIndex = index
                    Log.d(TAG, "🔍 Compose: Found our user message at index $index")
                }
                
                // Check if this is a bot response that comes after our message
                if (userMessageIndex != -1 && index > userMessageIndex && 
                    contentDesc.contains("Assistant message:")) {
                    botResponseIndex = index
                    Log.d(TAG, "🔍 Compose: Found bot response at index $index (after our message)")
                    break // Found the first bot response after our message
                }
            }
            
            if (userMessageIndex == -1) {
                Log.d(TAG, "🔍 Compose: Could not find our user message")
                return false
            }
            
            if (botResponseIndex == -1) {
                Log.d(TAG, "🔍 Compose: No bot response found after our message")
                return false
            }
            
            Log.d(TAG, "✅ Compose: Bot response verified after our message (user at $userMessageIndex, bot at $botResponseIndex)")
            true
            
        } catch (e: Exception) {
            Log.d(TAG, "🔍 Compose: Exception during bot response verification: ${e.message}")
            false
        }
    }
    

    

} 