package com.example.whiz.test_helpers

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.matcher.BoundedMatcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.allOf
import androidx.test.espresso.matcher.ViewMatchers.withHint
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Helper class for Espresso-based UI testing.
 * Provides utilities for common UI operations with better reliability than UI Automator.
 */
object EspressoTestHelper {
    
    private const val TAG = "EspressoTestHelper"
    
    /**
     * Find and interact with the message input field using multiple fallback strategies
     */
    fun findMessageInputField(): ViewInteraction? {
        val selectors = listOf(
            // Primary selectors
            onView(withContentDescription("Message input field")),
            onView(withClassName(containsString("EditText"))),
            
            // Compose-specific selectors
            onView(withClassName(containsString("OutlinedTextField"))),
            onView(withClassName(containsString("TextField"))),
            onView(withClassName(containsString("BasicTextField"))),
            onView(withClassName(containsString("TextInput"))),
            
            // Compose view selectors
            onView(withClassName(containsString("ComposeView"))),
            onView(withClassName(containsString("AndroidComposeView"))),
            
            // Fallback selectors
            onView(withId(android.R.id.text1)),
            onView(withText("")), // Empty text field
            onView(withHint("Type a message")), // Common hint text
            onView(withHint("Message")) // Alternative hint
        )
        
        for (selector in selectors) {
            try {
                selector.check(matches(allOf(isDisplayed(), isEnabled())))
                Log.d(TAG, "✅ Found input field with selector: ${selector}")
                return selector
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Selector failed: ${e.message}")
                continue
            }
        }
        
        Log.e(TAG, "❌ No input field selectors worked")
        return null
    }
    
    /**
     * Find and interact with the send button using multiple fallback strategies
     */
    fun findSendButton(): ViewInteraction? {
        val selectors = listOf(
            // Primary selectors
            onView(withContentDescription("Send typed message")),
            onView(withText("Send")),
            
            // Button-specific selectors
            onView(withClassName(containsString("Button"))),
            onView(allOf(withClassName(containsString("Button")), isClickable())),
            
            // Fallback selectors
            onView(withId(android.R.id.button1)),
            onView(withContentDescription(containsString("send")))
        )
        
        for (selector in selectors) {
            try {
                selector.check(matches(allOf(isDisplayed(), isClickable(), isEnabled())))
                Log.d(TAG, "✅ Found send button with selector: ${selector}")
                return selector
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Send button selector failed: ${e.message}")
                continue
            }
        }
        
        Log.e(TAG, "❌ No send button selectors worked")
        return null
    }
    
    /**
     * Type text into the message input field with automatic retry
     */
    fun typeMessage(message: String): Boolean {
        return try {
            val inputField = findMessageInputField()
            if (inputField == null) {
                Log.e(TAG, "❌ Cannot type message - input field not found")
                return false
            }
            
            // Clear existing text and type new message
            inputField.perform(clearText())
            inputField.perform(typeText(message))
            
            Log.d(TAG, "✅ Message typed successfully: '${message.take(30)}...'")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception typing message", e)
            false
        }
    }
    
    /**
     * Click the send button
     */
    fun clickSendButton(): Boolean {
        return try {
            val sendButton = findSendButton()
            if (sendButton == null) {
                Log.e(TAG, "❌ Cannot click send button - button not found")
                return false
            }
            
            sendButton.perform(click())
            Log.d(TAG, "✅ Send button clicked successfully")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception clicking send button", e)
            false
        }
    }
    
    /**
     * Send a complete message (type + send) with automatic retry
     */
    suspend fun sendMessage(message: String, rapid: Boolean = false): Boolean {
        return try {
            Log.d(TAG, "📝 Attempting to send message: '${message.take(30)}...'")
            
            // Type the message
            if (!typeMessage(message)) {
                Log.e(TAG, "❌ Failed to type message")
                return false
            }
            
            // Click send button
            if (!clickSendButton()) {
                Log.e(TAG, "❌ Failed to click send button")
                return false
            }
            
            // Wait for message to appear (with appropriate timeout)
            val timeout = if (rapid) 100L else 1000L
            val messageAppeared = waitForMessageToAppear(message, timeout)
            
            if (messageAppeared) {
                Log.d(TAG, "✅ Message sent and displayed successfully")
                true
            } else {
                Log.e(TAG, "❌ Message not displayed after sending")
                false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception during message sending", e)
            false
        }
    }
    
    /**
     * Wait for a message to appear in the chat
     */
    suspend fun waitForMessageToAppear(message: String, timeoutMs: Long): Boolean {
        val searchText = message.take(30)
        val startTime = System.currentTimeMillis()
        
        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                // Try multiple selectors to find the message
                val messageSelectors = listOf(
                    onView(withText(containsString(searchText))),
                    onView(withContentDescription(containsString("User message: $searchText"))),
                    onView(withContentDescription(containsString("Assistant message: $searchText"))),
                    onView(withText(containsString(searchText.lowercase()))),
                    onView(withText(containsString(searchText.uppercase())))
                )
                
                for (selector in messageSelectors) {
                    try {
                        selector.check(matches(isDisplayed()))
                        Log.d(TAG, "✅ Message found: '$searchText'")
                        return true
                    } catch (e: Exception) {
                        continue
                    }
                }
                
                // Brief wait before next check
                delay(10)
                
            } catch (e: Exception) {
                Log.d(TAG, "⚠️ Exception during message search: ${e.message}")
                delay(10)
            }
        }
        
        Log.e(TAG, "❌ Message not found within ${timeoutMs}ms: '$searchText'")
        return false
    }
    
    /**
     * Verify all expected messages exist in the chat
     */
    fun verifyAllMessagesExist(expectedMessages: List<String>): List<String> {
        val missingMessages = mutableListOf<String>()
        
        for ((index, expectedMessage) in expectedMessages.withIndex()) {
            val searchText = expectedMessage.take(30)
            
            try {
                // Try multiple selectors to find the message
                val messageFound = try {
                    onView(withText(containsString(searchText))).check(matches(isDisplayed()))
                    true
                } catch (e: Exception) {
                    try {
                        onView(withContentDescription(containsString("User message: $searchText"))).check(matches(isDisplayed()))
                        true
                    } catch (e2: Exception) {
                        try {
                            onView(withContentDescription(containsString("Assistant message: $searchText"))).check(matches(isDisplayed()))
                            true
                        } catch (e3: Exception) {
                            false
                        }
                    }
                }
                
                if (messageFound) {
                    Log.d(TAG, "✅ Message ${index + 1} found: '${searchText}...'")
                } else {
                    Log.w(TAG, "❌ Message ${index + 1} missing: '${searchText}...'")
                    missingMessages.add(expectedMessage)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "❌ Exception checking message ${index + 1}: ${e.message}")
                missingMessages.add(expectedMessage)
            }
        }
        
        if (missingMessages.isEmpty()) {
            Log.d(TAG, "✅ All ${expectedMessages.size} messages found in chat!")
        } else {
            Log.w(TAG, "❌ ${missingMessages.size} messages missing from chat")
        }
        
        return missingMessages
    }
    
    /**
     * Check for duplicate messages
     */
    fun noDuplicates(expectedMessages: List<String>): Boolean {
        for (message in expectedMessages) {
            val searchText = message.take(30)
            
            try {
                // Count occurrences of this message
                var count = 0
                val maxChecks = 5 // Limit to avoid infinite loops
                
                for (i in 0 until maxChecks) {
                    try {
                        onView(withText(containsString(searchText))).check(matches(isDisplayed()))
                        count++
                    } catch (e: Exception) {
                        break
                    }
                }
                
                if (count > 1) {
                    Log.e(TAG, "❌ Found $count occurrences of message: '$searchText'")
                    return false
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Exception checking duplicates for: '$searchText'")
            }
        }
        
        Log.d(TAG, "✅ No duplicates found")
        return true
    }
    
    /**
     * Wait for an element to be displayed with timeout
     */
    suspend fun waitForElement(
        selector: ViewInteraction,
        timeoutMs: Long = 5000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                selector.check(matches(isDisplayed()))
                return true
            } catch (e: Exception) {
                delay(10)
            }
        }
        
        return false
    }
    
    /**
     * Wait for an element to be clickable with timeout
     */
    suspend fun waitForClickable(
        selector: ViewInteraction,
        timeoutMs: Long = 5000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        
        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                selector.check(matches(allOf(isDisplayed(), isClickable(), isEnabled())))
                return true
            } catch (e: Exception) {
                delay(10)
            }
        }
        
        return false
    }
} 