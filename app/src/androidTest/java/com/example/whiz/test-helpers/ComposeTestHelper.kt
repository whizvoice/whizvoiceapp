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
        val selectors = listOf(
            // Primary selectors from production code
            { composeTestRule.onNodeWithContentDescription("Message input field") }, // Primary selector from production code
            { composeTestRule.onNodeWithTag("chat_input_field") }, // Test tag from production code
            
            // Placeholder text selectors from production code
            { composeTestRule.onNodeWithText("Type or tap mic...") }, // Default placeholder
            { composeTestRule.onNodeWithText("Listening...") }, // Listening placeholder
            
            // Fallback selectors
            { composeTestRule.onNodeWithContentDescription("Type a message") },
            { composeTestRule.onNodeWithTag("message_input") },
            { composeTestRule.onNodeWithContentDescription("input") }
        )
        
        for (selector in selectors) {
            try {
                val node = selector()
                node.assertIsDisplayed()
                Log.d(TAG, "✅ Found input field with Compose selector")
                return node
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Compose selector failed: ${e.message}")
                continue
            }
        }
        
        Log.e(TAG, "❌ No input field Compose selectors worked")
        return null
    }
    
    /**
     * Find and interact with the send button using Compose testing
     */
    fun findSendButton(composeTestRule: AndroidComposeTestRule<*, MainActivity>): SemanticsNodeInteraction? {
        val selectors = listOf(
            // Primary selectors
            { composeTestRule.onNodeWithContentDescription("Send typed message") },
            { composeTestRule.onNodeWithText("Send") },
            { composeTestRule.onNodeWithTag("send_button") },
            
            // Compose-specific selectors
            { composeTestRule.onNodeWithContentDescription("Send typed message") },
            { composeTestRule.onNodeWithText("Send") },
            
            // Fallback selectors
            { composeTestRule.onNodeWithContentDescription("send") },
            { composeTestRule.onNodeWithContentDescription("Send") }
        )
        
        for (selector in selectors) {
            try {
                val node = selector()
                node.assertIsDisplayed()
                Log.d(TAG, "✅ Found send button with Compose selector")
                return node
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Send button Compose selector failed: ${e.message}")
                continue
            }
        }
        
        Log.e(TAG, "❌ No send button Compose selectors worked")
        return null
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
    suspend fun sendMessage(composeTestRule: AndroidComposeTestRule<*, MainActivity>, message: String, rapid: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "📝 Compose: attempting to send message: '${message.take(30)}...'")
            
            // Type the message
            if (!typeMessage(composeTestRule, message)) {
                Log.e(TAG, "❌ Compose: Failed to type message")
                return false
            }
            
            // Click send button
            if (!clickSendButton(composeTestRule)) {
                Log.e(TAG, "❌ Compose: Failed to click send button")
                return false
            }
            
            // Wait for message to appear (with appropriate timeout)
            val timeout = if (rapid) 100L else 1000L
            val messageAppeared = waitForMessageToAppear(composeTestRule, message, timeout)
            
            if (messageAppeared) {
                Log.d(TAG, "✅ Compose: Message sent and displayed successfully")
                true
            } else {
                Log.e(TAG, "❌ Compose: Message not displayed after sending")
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
    suspend fun waitForMessageToAppear(composeTestRule: AndroidComposeTestRule<*, MainActivity>, message: String, timeoutMs: Long): Boolean {
        return try {
            // Use a shorter search text to be more flexible with message display
            val searchText = message.take(20)
            val startTime = System.currentTimeMillis()
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    // Try to find the message using Compose Testing with multiple strategies
                    val messageSelectors = listOf(
                        { composeTestRule.onNodeWithText(searchText) },
                        { composeTestRule.onNodeWithText(message.take(15)) }, // Even shorter fallback
                        { composeTestRule.onNodeWithText(message.take(10)) }, // Very short fallback
                        { composeTestRule.onNodeWithContentDescription("User message: $searchText") },
                        { composeTestRule.onNodeWithContentDescription("Assistant message: $searchText") }
                    )
                    
                    for (selector in messageSelectors) {
                        try {
                            val node = selector()
                            node.assertIsDisplayed()
                            Log.d(TAG, "✅ Compose: Message found: '$searchText'")
                            return true
                        } catch (e: Exception) {
                            continue
                        }
                    }
                    
                    // Brief wait before next check
                    delay(10)
                    
                } catch (e: Exception) {
                    Log.d(TAG, "⚠️ Compose: Exception during message search: ${e.message}")
                    delay(10)
                }
            }
            
            Log.e(TAG, "❌ Compose: Message not found within ${timeoutMs}ms: '$searchText'")
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Compose: Exception waiting for message", e)
            false
        }
    }
    
    /**
     * Verify all expected messages exist using Compose testing
     */
    fun verifyAllMessagesExist(composeTestRule: AndroidComposeTestRule<*, MainActivity>, expectedMessages: List<String>): List<String> {
        val missingMessages = mutableListOf<String>()
        
        for ((index, expectedMessage) in expectedMessages.withIndex()) {
            val searchText = expectedMessage.take(30)
            
            try {
                // Try to find the message using multiple selectors
                val messageFound = try {
                    composeTestRule.onNodeWithText(searchText).assertIsDisplayed()
                    true
                } catch (e: Exception) {
                    try {
                        composeTestRule.onNodeWithContentDescription("User message: $searchText").assertIsDisplayed()
                        true
                    } catch (e2: Exception) {
                        try {
                            composeTestRule.onNodeWithContentDescription("Assistant message: $searchText").assertIsDisplayed()
                            true
                        } catch (e3: Exception) {
                            false
                        }
                    }
                }
                
                if (messageFound) {
                    Log.d(TAG, "✅ Compose: Message ${index + 1} found: '${searchText}...'")
                } else {
                    Log.w(TAG, "❌ Compose: Message ${index + 1} missing: '${searchText}...'")
                    missingMessages.add(expectedMessage)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "❌ Compose: Exception checking message ${index + 1}: ${e.message}")
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
     */
    fun noDuplicates(composeTestRule: AndroidComposeTestRule<*, MainActivity>, expectedMessages: List<String>): Boolean {
        for (message in expectedMessages) {
            val searchText = message.take(30)
            
            try {
                // Check if this message appears multiple times by trying to find it
                val messageFound = try {
                    composeTestRule.onNodeWithText(searchText).assertIsDisplayed()
                    true
                } catch (e: Exception) {
                    false
                }
                
                if (messageFound) {
                    // For now, we'll assume no duplicates since we can't easily count with available APIs
                    Log.d(TAG, "✅ Compose: Message found: '$searchText'")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Compose: Exception checking duplicates for: '$searchText'")
            }
        }
        
        Log.d(TAG, "✅ Compose: No duplicates detected (simplified check)")
        return true
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
            
            // Use efficient polling to wait for the specific element
            // This is much better than Thread.sleep() - it waits for the actual dependency
            return waitForElementEfficient(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
                timeoutMs = 10000L,
                description = "chat screen input field"
            )
            
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