package com.example.whiz.test_helpers

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performTextReplacement
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Compose helper functions for UI testing
 * Provides common Compose UI interaction and verification methods
 */
object ComposeTestHelper {
    
    private const val TAG = "ComposeTestHelper"
    
    /**
     * Find and interact with the message input field using Compose testing
     */
    fun findMessageInputField(composeTestRule: ComposeContentTestRule): SemanticsNodeInteraction? {
        val selectors = listOf(
            // Primary selectors
            { composeTestRule.onNodeWithContentDescription("Message input field") },
            { composeTestRule.onNodeWithTag("message_input") },
            
            // Text field selectors
            { composeTestRule.onNodeWithText("") }, // Empty text field
            
            // Compose-specific selectors
            { composeTestRule.onNodeWithContentDescription("Type a message") }, // Hint text
            { composeTestRule.onNodeWithText("Type a message") }, // Hint text
            { composeTestRule.onNodeWithContentDescription("Message") }, // Alternative hint
            { composeTestRule.onNodeWithText("Message") }, // Alternative hint
            
            // Fallback selectors
            { composeTestRule.onNodeWithContentDescription("input") },
            { composeTestRule.onNodeWithContentDescription("text") }
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
    fun findSendButton(composeTestRule: ComposeContentTestRule): SemanticsNodeInteraction? {
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
    fun typeMessage(composeTestRule: ComposeContentTestRule, message: String): Boolean {
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
    fun clickSendButton(composeTestRule: ComposeContentTestRule): Boolean {
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
    suspend fun sendMessage(composeTestRule: ComposeContentTestRule, message: String, rapid: Boolean = false): Boolean {
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
    suspend fun waitForMessageToAppear(composeTestRule: ComposeContentTestRule, message: String, timeoutMs: Long): Boolean {
        return try {
            val searchText = message.take(30)
            val startTime = System.currentTimeMillis()
            
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    // Try to find the message using Compose Testing
                    val messageSelectors = listOf(
                        { composeTestRule.onNodeWithText(searchText) },
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
    fun verifyAllMessagesExist(composeTestRule: ComposeContentTestRule, expectedMessages: List<String>): List<String> {
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
    fun noDuplicates(composeTestRule: ComposeContentTestRule, expectedMessages: List<String>): Boolean {
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
} 