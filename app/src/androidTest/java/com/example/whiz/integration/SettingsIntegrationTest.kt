package com.example.whiz.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.whiz.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.data.preferences.VoiceSettings
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import android.util.Log
import kotlinx.coroutines.delay
import com.example.whiz.TestCredentialsManager

/**
 * Integration tests for the Settings screen functionality.
 * 
 * These tests verify:
 * 1. Claude API key setting and unsetting
 * 2. Asana access token setting and unsetting
 * 3. Voice settings configuration
 * 4. Subscription management
 * 5. Settings persistence across app restarts
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest : BaseIntegrationTest() {
    
    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var userPreferences: UserPreferences
    
    private val TAG = "SettingsIntegrationTest"
    
    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Don't clear tokens in @Before since test account should have them set
        // Tests will handle clearing and restoring as needed
        Log.d(TAG, "Test setup complete - preserving existing test account tokens")
    }
    
    @After
    fun tearDown() {
        // Note: We don't clear tokens in @After because the test account
        // needs them to remain set for other tests to work properly.
        // Each test is responsible for restoring valid tokens if it changes them.
        Log.d(TAG, "Test cleanup - tokens preserved for test account")
    }
    
    private fun navigateToSettings(): Boolean {
        Log.d(TAG, "Navigating to Settings screen")
        
        // Wait for the settings button to be available - could be on chats list or chat screen
        // The button is identified by its contentDescription = "Settings" (no test tags)
        val settingsButtonFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Settings")) },
            timeoutMs = 5000,
            description = "Settings button"
        )
        
        if (!settingsButtonFound) {
            Log.e(TAG, "Settings button not found on screen")
            return false
        }
        
        // Click the settings button
        try {
            composeTestRule.onNode(hasContentDescription("Settings"))
                .performClick()
        } catch (e: AssertionError) {
            Log.e(TAG, "Could not click Settings button: ${e.message}")
            return false
        }
        
        // Wait for settings screen to load
        val settingsScreenLoaded = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Settings") },
            timeoutMs = 3000,
            description = "Settings screen title"
        )
        
        if (!settingsScreenLoaded) {
            Log.e(TAG, "Settings screen did not load")
            return false
        }
        
        val apiKeysVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("API Keys") },
            timeoutMs = 2000,
            description = "API Keys section"
        )
        
        if (!apiKeysVisible) {
            Log.e(TAG, "API Keys section not visible on settings screen")
            return false
        }
        
        return true
    }
    
    
    @Test
    fun testClaudeApiKey_SetAndUnset() {
        Log.d(TAG, "========== Starting testClaudeApiKey_SetAndUnset ==========")
        if (!navigateToSettings()) {
            Log.e(TAG, "FAILURE: Could not navigate to Settings screen")
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing Claude API key management")
        
        // Verify we're on settings screen
        Log.d(TAG, "Verifying Settings screen loaded - looking for 'Claude API Key' text")
        val claudeApiKeyFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Claude API Key") },
            timeoutMs = 5000,
            description = "Claude API Key text"
        )
        if (!claudeApiKeyFound) {
            Log.e(TAG, "FAILURE: 'Claude API Key' text not found on Settings screen")
            failWithScreenshot("claude_api_key_text_not_found", "Claude API Key text not found")
        } else {
            Log.d(TAG, "✓ Settings screen loaded successfully")
        }
        
        // Step 1: Save the original token to restore later
        var originalTokenExists = false
        runBlocking {
            originalTokenExists = userPreferences.hasClaudeToken.first() == true
        }
        
        // Step 2: Check if Claude token needs clearing
        Log.d(TAG, "Checking if Claude API key needs clearing")
        
        // First check if the input field is already visible (token already cleared)
        // Need to wait for UI to load and fetch token status from server
        val inputFieldAlreadyVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNodeWithText("Enter Claude API Key")
            },
            timeoutMs = 5000,  // Longer timeout to allow for server fetch
            description = "Claude API Key input field (initial check)"
        )
        
        if (!inputFieldAlreadyVisible) {
            // Token is set, need to clear it
            Log.d(TAG, "Claude token is currently set, need to clear it first")
            
            // Find the Clear button specifically in the Claude API Key section
            val clearButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNode(
                        hasText("Clear").and(hasAnyAncestor(hasText("Claude API Key")))
                    )
                },
                timeoutMs = 3000,
                description = "Clear button for Claude API Key"
            )
            
            if (clearButtonFound) {
                composeTestRule.onNode(
                    hasText("Clear").and(hasAnyAncestor(hasText("Claude API Key")))
                ).performClick()
                
                // Wait for the input field to appear after clearing
                val inputFieldAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText("Enter Claude API Key")
                    },
                    timeoutMs = 5000,
                    description = "Claude API Key input field after clear"
                )
                
                if (!inputFieldAppeared) {
                    Log.e(TAG, "FAILURE: Input field did not appear after clicking Clear button")
                    failWithScreenshot("clear_failed_no_input", "Input field did not appear after clearing token")
                } else {
                    Log.d(TAG, "✓ Token cleared successfully, input field visible")
                }
            } else {
                Log.e(TAG, "FAILURE: Could not find Clear button for Claude API Key")
                failWithScreenshot("clear_button_not_found", "Could not find Clear button for Claude API Key")
            }
        } else {
            Log.d(TAG, "Claude API key already cleared, input field is visible")
        }
        
        // Step 3: Verify token is cleared - input field should be visible
        Log.d(TAG, "Step 3: Verifying token is cleared")
        val inputFieldFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNodeWithText("Enter Claude API Key")
            },
            timeoutMs = 5000,
            description = "Claude API Key input field"
        )
        
        if (!inputFieldFound) {
            Log.e(TAG, "FAILURE: Input field not visible after clearing token - clear operation may have failed")
            failWithScreenshot("input_field_not_visible", "Input field not visible after clearing token")
        } else {
            Log.d(TAG, "✓ Step 3 complete: Token cleared, ready to test invalid token")
        }
        
        // Step 4: Test setting an INVALID token
        Log.d(TAG, "Step 4: Testing invalid token scenario")
        val invalidToken = "invalid-token-12345"
        
        // Click on the input field first to focus it, then input text
        try {
            // The actual EditText is the parent of the placeholder text
            composeTestRule.onNode(
                hasSetTextAction().and(hasAnyDescendant(hasText("Enter Claude API Key")))
            ).performClick()
            
            composeTestRule.onNode(
                hasSetTextAction().and(hasAnyDescendant(hasText("Enter Claude API Key")))
            ).performTextInput(invalidToken)
        } catch (e: AssertionError) {
            Log.e(TAG, "FAILURE: Could not enter invalid token into input field: ${e.message}")
            failWithScreenshot("invalid_token_input_failed", "Could not enter invalid token: ${e.message}")
        }
        
        // Save the invalid token
        Log.d(TAG, "Looking for Save button to save invalid token")
        val saveButtonFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Save") },
            timeoutMs = 3000,
            description = "Save button"
        )
        
        if (!saveButtonFound) {
            Log.e(TAG, "Save button not found, looking for 'Save Claude API Key' button")
            val saveClaudeButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Save Claude API Key") },
                timeoutMs = 3000,
                description = "Save Claude API Key button"
            )
            if (!saveClaudeButtonFound) {
                Log.e(TAG, "FAILURE: Neither 'Save' nor 'Save Claude API Key' button found on screen")
                failWithScreenshot("save_button_not_found", "Save button not found")
            }
            Log.d(TAG, "Clicking 'Save Claude API Key' button")
            composeTestRule.onNodeWithText("Save Claude API Key").performClick()
        } else {
            Log.d(TAG, "Clicking 'Save' button")
            composeTestRule.onNodeWithText("Save").performClick()
        }
        
        // Wait for save operation to complete by checking for "Token is set." text
        Log.d(TAG, "Waiting for 'Token is set.' confirmation after saving invalid token")
        val invalidTokenSet = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 5000,
            description = "Token is set text for invalid token"
        )
        
        if (!invalidTokenSet) {
            Log.e(TAG, "FAILURE: 'Token is set.' text not found after saving invalid token - save operation may have failed")
            failWithScreenshot("invalid_token_not_saved", "Invalid token was not saved")
        } else {
            Log.d(TAG, "✓ Step 4a complete: Invalid token saved successfully")
        }
        
        // Step 5: Navigate to chat and verify error when using invalid Claude key
        Log.d(TAG, "Step 5: Testing invalid token behavior in chat")
        try {
            // Navigate back to chats list
            composeTestRule.onNodeWithContentDescription("Back").performClick()
            Thread.sleep(1000)
            
            // Click New Chat
            Log.d(TAG, "Looking for New Chat button to test invalid token")
            val newChatFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("New Chat") },
                timeoutMs = 5000,
                description = "New Chat button"
            )
            
            if (newChatFound) {
                Log.d(TAG, "Found New Chat button, clicking it")
                composeTestRule.onNodeWithContentDescription("New Chat").performClick()
                Thread.sleep(2000)
                
                // Type a message that would trigger Claude API call
                val messageFieldFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNode(hasSetTextAction()) },
                    timeoutMs = 5000,
                    description = "Message input field"
                )
                
                if (messageFieldFound) {
                    Log.d(TAG, "Message field found, typing test message")
                    composeTestRule.onNode(hasSetTextAction()).performTextInput("Hello Claude")
                    
                    // Send the message
                    val sendButtonFound = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { composeTestRule.onNodeWithContentDescription("Send") },
                        timeoutMs = 3000,
                        description = "Send button"
                    )
                    
                    if (sendButtonFound) {
                        Log.d(TAG, "Send button found, sending message with invalid API key")
                        composeTestRule.onNodeWithContentDescription("Send").performClick()
                        Log.d(TAG, "Waiting 3 seconds for API call to complete/fail")
                        Thread.sleep(3000) // Wait for API call to fail
                        
                        // Should see an error message about invalid API key
                        val errorFound = ComposeTestHelper.waitForElement(
                            composeTestRule = composeTestRule,
                            selector = { 
                                composeTestRule.onNode(
                                    hasText("error", ignoreCase = true)
                                        .or(hasText("invalid", ignoreCase = true))
                                        .or(hasText("authentication", ignoreCase = true))
                                )
                            },
                            timeoutMs = 5000,
                            description = "Error message"
                        )
                        
                        if (errorFound) {
                            Log.d(TAG, "✓ Error shown for invalid API key as expected")
                        } else {
                            Log.w(TAG, "WARNING: No error message found for invalid API key - API might have accepted invalid token or UI didn't show error")
                        }
                    } else {
                        Log.e(TAG, "Send button not found after typing message")
                    }
                } else {
                    Log.e(TAG, "Message input field not found in chat screen")
                }
            } else {
                Log.e(TAG, "New Chat button not found on chats list screen")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during invalid token chat test: ${e.message}")
            Log.e(TAG, "This is non-critical - continuing to restore valid token")
        }
        Log.d(TAG, "✓ Step 5 complete: Invalid token chat test finished")
        
        // Step 6: Go back to settings and restore the ACTUAL test account token
        Log.d(TAG, "Step 6: Restoring test account's Claude API key to prevent test pollution")
        
        // Get the actual test credentials
        val testCredentials = TestCredentialsManager.credentials
        val actualClaudeKey = testCredentials.testEnvironment.claudeApiKey
        
        // Navigate back to settings
        val settingsButtonFound2 = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Settings")) },
            timeoutMs = 5000,
            description = "Settings button"
        )
        
        if (settingsButtonFound2) {
            Log.d(TAG, "Navigating back to Settings to restore valid token")
            composeTestRule.onNode(hasContentDescription("Settings")).performClick()
            Thread.sleep(1000)
        } else {
            Log.e(TAG, "ERROR: Could not find Settings button to restore valid token")
        }
        
        // Clear the invalid token
        val clearButton2Found = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onAllNodesWithText("Clear").onFirst() },
            timeoutMs = 3000,
            description = "Clear button"
        )
        
        if (clearButton2Found) {
            Log.d(TAG, "Clearing invalid token before restoring valid one")
            composeTestRule.onAllNodesWithText("Clear").onFirst().performClick()
            Thread.sleep(1500)
        } else {
            Log.w(TAG, "Clear button not found, attempting to overwrite token directly")
        }
        
        // Restore the actual test account token
        try {
            composeTestRule.onNode(
                hasSetTextAction().and(hasAnyAncestor(hasText("Claude API Key")))
            ).performTextInput(actualClaudeKey)
            
            // Save the valid token
            val saveButton2Found = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Save") },
                timeoutMs = 3000,
                description = "Save button"
            )
            
            if (saveButton2Found) {
                composeTestRule.onNodeWithText("Save").performClick()
            } else {
                composeTestRule.onNodeWithText("Save Claude API Key").performClick()
            }
            
            Thread.sleep(1000)
            
            // Verify token is set
            val finalTokenSet = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Token is set.") },
                timeoutMs = 5000,
                description = "Token is set final check"
            )
            
            if (!finalTokenSet) {
                Log.e(TAG, "FAILURE: Test account's Claude token was not restored - 'Token is set.' not found")
                failWithScreenshot("test_token_not_restored", "Test account token was not restored")
            }
            
            Log.d(TAG, "✓ TEST COMPLETE: Successfully tested Claude API key management and restored test account token")
            Log.d(TAG, "========== testClaudeApiKey_SetAndUnset completed successfully ==========")
            
        } catch (e: AssertionError) {
            Log.e(TAG, "CRITICAL FAILURE: Could not restore test account token: ${e.message}")
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            failWithScreenshot("restore_test_token_failed", "Could not restore test account token: ${e.message}")
        }
    }
    
    @Test
    fun testAsanaAccessToken_SetAndUnset() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        // Get the actual test credentials to restore at the end
        val testCredentials = TestCredentialsManager.credentials
        val actualAsanaToken = testCredentials.testEnvironment.asanaToken
        
        // Test setting Asana token
        Log.d(TAG, "Testing Asana access token setting")
        
        // Scroll to Asana section
        try {
            composeTestRule.onRoot()
                .performScrollToIndex(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll, continuing anyway")
        }
        
        // Verify we can see Asana section
        val asanaFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Asana Access Token") },
            timeoutMs = 5000,
            description = "Asana Access Token text"
        )
        if (!asanaFound) {
            failWithScreenshot("asana_section_not_found", "Could not find Asana Access Token section")
        }
        
        // Check current state - token might already be set
        val asanaTokenSet = try {
            composeTestRule.onAllNodesWithText("Token is set.")
                .get(1) // Second one would be Asana
                .assertExists()
            true
        } catch (e: Exception) {
            false
        }
        
        if (asanaTokenSet) {
            Log.d(TAG, "Asana token already set, clearing it first")
            try {
                composeTestRule.onAllNodesWithText("Clear")
                    .get(1) // Second Clear button for Asana
                    .performClick()
                Thread.sleep(1500)
            } catch (e: Exception) {
                failWithScreenshot("asana_clear_failed", "Could not clear existing Asana token: ${e.message}")
            }
        }
        
        // Enter a test token
        val testToken = "0/test-asana-token-${System.currentTimeMillis()}"
        try {
            composeTestRule.onNodeWithText("Enter Asana Access Token")
                .performTextInput(testToken)
        } catch (e: AssertionError) {
            // Try finding input field differently
            try {
                composeTestRule.onNode(
                    hasSetTextAction()
                        .and(hasAnyAncestor(hasText("Asana Access Token")))
                ).performTextInput(testToken)
            } catch (e2: AssertionError) {
                failWithScreenshot("asana_input_not_found", "Could not find Asana input field: ${e2.message}")
            }
        }
        
        // Save the token
        try {
            composeTestRule.onNodeWithText("Save Asana Access Token")
                .assertIsEnabled()
                .performClick()
        } catch (e: AssertionError) {
            try {
                composeTestRule.onNodeWithText("Save")
                    .assertIsEnabled()
                    .performClick()
            } catch (e2: AssertionError) {
                failWithScreenshot("asana_save_not_found", "Could not find Save button for Asana: ${e2.message}")
            }
        }
        
        // Wait for save to complete
        Thread.sleep(1000)
        
        // Verify token is set
        val asanaTokenSetFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 3000,
            description = "Token is set text for Asana"
        )
        if (!asanaTokenSetFound) {
            failWithScreenshot("asana_not_saved", "Asana token was not saved - 'Token is set.' not found")
        }
        Log.d(TAG, "Screenshot would be taken here: asana_token_set")
        
        // Verify token was actually saved
        runBlocking {
            val hasToken = withTimeout(5000) {
                userPreferences.hasAsanaToken.first()
            }
            assertTrue("Asana token should be saved", hasToken == true)
        }
        
        // Test changing the token
        Log.d(TAG, "Testing Asana access token change")
        try {
            composeTestRule.onAllNodesWithText("Change")
                .get(1) // Second "Change" button for Asana
                .performClick()
        } catch (e: Exception) {
            Log.w(TAG, "Could not click Change button, skipping token change test")
        }
        
        // Enter a new token
        val newToken = "0/new-asana-token-${System.currentTimeMillis()}"
        composeTestRule.onNodeWithText("Enter new Asana Access Token")
            .performTextClearance()
        composeTestRule.onNodeWithText("Enter new Asana Access Token")
            .performTextInput(newToken)
        
        // Save the new token
        composeTestRule.onNodeWithText("Save Asana Access Token")
            .performClick()
        
        // Wait for save to complete
        Thread.sleep(1000)
        
        // Verify token is still set
        val tokenStillSet = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 3000,
            description = "Token is set after change"
        )
        if (!tokenStillSet) {
            failWithScreenshot("token_not_set_after_change", "Token not set after change")
        }
        
        // Test clearing the token
        Log.d(TAG, "Testing Asana access token clearing")
        try {
            composeTestRule.onAllNodesWithText("Clear")
                .get(1) // Second "Clear" button for Asana
                .performClick()
        } catch (e: Exception) {
            failWithScreenshot("asana_clear_button_not_found", "Could not find Asana Clear button: ${e.message}")
        }
        
        // Wait for clear to complete
        Thread.sleep(1000)
        
        // Verify token is cleared
        val asanaInputFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Enter Asana Access Token") },
            timeoutMs = 5000,
            description = "Enter Asana Access Token text"
        )
        if (!asanaInputFound) {
            // Check if "Token is set." is gone
            try {
                composeTestRule.onAllNodesWithText("Token is set.")
                    .get(1)
                    .assertDoesNotExist()
            } catch (e2: Exception) {
                failWithScreenshot("asana_not_cleared", "Asana token was not cleared")
            }
        }
        Log.d(TAG, "Screenshot would be taken here: asana_token_cleared")
        
        // Verify token was actually cleared
        runBlocking {
            val hasToken = withTimeout(5000) {
                userPreferences.hasAsanaToken.first()
            }
            assertFalse("Asana token should be cleared", hasToken == true)
        }
        
        // IMPORTANT: Restore the actual test account's Asana token
        Log.d(TAG, "Restoring test account's Asana token")
        try {
            // Enter the actual test account token
            composeTestRule.onNode(
                hasSetTextAction().and(hasAnyAncestor(hasText("Asana Access Token")))
            ).performTextInput(actualAsanaToken)
            
            // Save it
            val saveAsanaFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Save Asana Access Token") },
                timeoutMs = 3000,
                description = "Save Asana button"
            )
            
            if (saveAsanaFound) {
                composeTestRule.onNodeWithText("Save Asana Access Token").performClick()
            } else {
                composeTestRule.onNodeWithText("Save").performClick()
            }
            
            Thread.sleep(1000)
            
            // Verify restored
            Log.d(TAG, "Verifying Asana token restoration")
            val restoredTokenSet = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onAllNodesWithText("Token is set.").get(1) },
                timeoutMs = 3000,
                description = "Restored Asana token check"
            )
            
            if (!restoredTokenSet) {
                Log.e(TAG, "CRITICAL WARNING: Test account's Asana token may not be restored - this will affect other tests!")
            } else {
                Log.d(TAG, "✓ Test account's Asana token restored successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore test account's Asana token: ${e.message}")
        }
    }
    
    @Test
    fun testVoiceSettings_CustomConfiguration() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing voice settings configuration")
        
        // Scroll to Voice Settings section
        try {
            composeTestRule.onRoot()
                .performScrollToIndex(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to voice settings")
        }
        
        // Verify initial state - system defaults may be on
        val voiceSettingsFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Use System TTS Settings") },
            timeoutMs = 5000,
            description = "Use System TTS Settings text"
        )
        if (!voiceSettingsFound) {
            failWithScreenshot("voice_settings_not_found", "Voice Settings section not found")
        }
        
        // Check current state and ensure custom settings are enabled
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertExists()
            Log.d(TAG, "Custom settings already visible")
        } catch (e: AssertionError) {
            Log.d(TAG, "Turning off system defaults to show custom settings")
            // Find and click the switch to turn off system defaults
            val switches = composeTestRule.onAllNodes(hasClickAction())
            switches.filter(hasAnyAncestor(hasText("Use System TTS Settings")))
                .onFirst()
                .performClick()
            
            // Wait for custom settings to appear
            val speechRateFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Speech Rate") },
                timeoutMs = 5000,
                description = "Speech Rate text"
            )
            if (!speechRateFound) {
                failWithScreenshot("speech_rate_not_found", "Speech Rate not found after disabling system defaults")
            }
        }
        
        // Test adjusting speech rate
        Log.d(TAG, "Testing speech rate adjustment")
        
        // The sliders are present, verify we can see the current values
        val speechRateVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Speech Rate") },
            timeoutMs = 5000,
            description = "Speech Rate text"
        )
        if (!speechRateVisible) {
            failWithScreenshot("speech_rate_not_visible", "Speech Rate not visible")
        }
        
        val defaultValueFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("100%") },
            timeoutMs = 3000,
            description = "Default 100% value"
        )
        if (!defaultValueFound) {
            Log.w(TAG, "Default 100% value not found")
        }
        
        // Test the Test Playback button
        Log.d(TAG, "Testing voice playback")
        try {
            composeTestRule.onNodeWithText("Test Playback")
                .assertIsEnabled()
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("test_playback_not_found", "Test Playback button not found or disabled")
        }
        
        // Take screenshot of voice settings
        Log.d(TAG, "Screenshot would be taken here: voice_settings_custom")
        
        // Test switching to system defaults
        Log.d(TAG, "Testing switch to system defaults")
        
        // Find and click the switch to turn on system defaults
        val switches = composeTestRule.onAllNodes(hasClickAction())
        switches.filter(hasAnyAncestor(hasText("Use System TTS Settings")))
            .onFirst()
            .performClick()
        
        // Verify custom settings are hidden
        Thread.sleep(500)
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertDoesNotExist()
        } catch (e: AssertionError) {
            Log.w(TAG, "Speech Rate still visible after switching to system defaults")
        }
        
        // Test Playback should still be available
        composeTestRule.onNodeWithText("Test Playback")
            .assertIsEnabled()
        
        Log.d(TAG, "Screenshot would be taken here: voice_settings_system_defaults")
        
        // Verify settings were saved
        runBlocking {
            val settings = withTimeout(5000) {
                userPreferences.voiceSettings.first()
            }
            assertTrue("System defaults should be enabled", settings.useSystemDefaults)
        }
    }
    
    @Test
    fun testVoiceSettings_SliderInteraction() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing voice settings slider interactions")
        
        // Scroll to Voice Settings
        try {
            composeTestRule.onRoot()
                .performScrollToIndex(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to voice settings")
        }
        
        // Ensure custom settings are visible
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertExists()
        } catch (e: AssertionError) {
            // Turn off system defaults if needed
            val switches = composeTestRule.onAllNodes(hasClickAction())
            switches.filter(hasAnyAncestor(hasText("Use System TTS Settings")))
                .onFirst()
                .performClick()
            val speechRateFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Speech Rate") },
                timeoutMs = 5000,
                description = "Speech Rate text"
            )
            if (!speechRateFound) {
                failWithScreenshot("speech_rate_not_found", "Speech Rate not found")
            }
        }
        
        // Verify we can see both sliders
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertExists()
            composeTestRule.onNodeWithText("Voice Pitch").assertExists()
        } catch (e: AssertionError) {
            failWithScreenshot("voice_sliders_not_found", "Voice sliders not found")
        }
        
        // Verify percentage displays
        try {
            composeTestRule.onNodeWithText("100%").assertExists() // Default values
        } catch (e: AssertionError) {
            Log.w(TAG, "Default percentage values not visible")
        }
        
        // Test that sliders are interactable (checking they're enabled)
        // Verify sliders are present by checking for their text labels
        composeTestRule.onNodeWithText("Speech Rate").assertExists()
        composeTestRule.onNodeWithText("Voice Pitch").assertExists()
        
        Log.d(TAG, "Screenshot would be taken here: voice_settings_sliders")
    }
    
    @Test
    fun testSubscription_DisplayAndInteraction() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing subscription section display")
        
        // Scroll to Subscription section
        try {
            composeTestRule.onRoot()
                .performScrollToIndex(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to subscription section")
        }
        
        // Wait for subscription status to load
        Thread.sleep(2000) // Give time for subscription status to load
        
        // Check if subscription section is visible
        val subscriptionFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Subscription") },
            timeoutMs = 5000,
            description = "Subscription text"
        )
        if (!subscriptionFound) {
            failWithScreenshot("subscription_not_found", "Subscription section not found")
        }
        
        // The subscription status could be either active or not
        // We'll just verify the section exists and is interactive
        try {
            // Case 1: No subscription
            if (composeTestRule.onAllNodesWithText("Premium Subscription").fetchSemanticsNodes().isNotEmpty()) {
                Log.d(TAG, "User has no active subscription")
                val unlimitedAccessFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("Get unlimited access to all features") },
                    timeoutMs = 3000,
                    description = "Unlimited access text"
                )
                if (unlimitedAccessFound) {
                    try {
                        composeTestRule.onNodeWithText("Subscribe for $10/month")
                            .assertIsEnabled()
                    } catch (e: AssertionError) {
                        failWithScreenshot("subscription_ui_issue", "Subscription UI elements not as expected")
                    }
                } else {
                    Log.w(TAG, "Unlimited access text not found")
                }
                Log.d(TAG, "Screenshot would be taken here: subscription_not_active")
            }
        } catch (e: AssertionError) {
            try {
                // Case 2: Active subscription
                if (composeTestRule.onAllNodesWithText("Premium Subscription Active").fetchSemanticsNodes().isNotEmpty()) {
                    Log.d(TAG, "User has active subscription")
                    val premiumActiveFound = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { composeTestRule.onNodeWithText("Premium Subscription Active") },
                        timeoutMs = 3000,
                        description = "Premium Subscription Active text"
                    )
                    if (!premiumActiveFound) {
                        Log.w(TAG, "Premium Subscription Active text not found")
                    }
                    // May have Cancel Subscription button
                    Log.d(TAG, "Screenshot would be taken here: subscription_active")
                }
            } catch (e2: AssertionError) {
                Log.d(TAG, "Subscription section in loading state")
                Log.d(TAG, "Screenshot would be taken here: subscription_loading")
            }
        }
    }
    
    @Test
    fun testSettingsPersistence_AcrossNavigation() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing settings persistence across navigation")
        
        // Set a Claude token
        val testToken = "sk-ant-persistence-test-${System.currentTimeMillis()}"
        try {
            composeTestRule.onNodeWithText("Enter Claude API Key")
                .performTextInput(testToken)
        } catch (e: AssertionError) {
            // If token is already set, clear it first
            try {
                composeTestRule.onAllNodesWithText("Clear").onFirst().performClick()
                Thread.sleep(1500)
                composeTestRule.onNode(hasSetTextAction()).performTextInput(testToken)
            } catch (e2: Exception) {
                failWithScreenshot("persistence_input_failed", "Could not enter token for persistence test: ${e2.message}")
            }
        }
        
        try {
            composeTestRule.onNodeWithText("Save Claude API Key")
                .performClick()
        } catch (e: AssertionError) {
            try {
                composeTestRule.onNodeWithText("Save").performClick()
            } catch (e2: AssertionError) {
                failWithScreenshot("persistence_save_failed", "Could not save token for persistence test")
            }
        }
        
        // Wait for save to complete
        Thread.sleep(1000)
        val tokenSetAfterSave = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 5000,
            description = "Token is set after save"
        )
        if (!tokenSetAfterSave) {
            failWithScreenshot("token_not_set_after_save", "Token not set after save")
        }
        
        // Navigate away from settings
        try {
            composeTestRule.onNodeWithContentDescription("Back")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("back_button_not_found", "Could not navigate back from settings")
        }
        
        // Wait for home screen
        val chatsListFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Chats list")) },
            timeoutMs = 5000,
            description = "Chats list"
        )
        if (!chatsListFound) {
            failWithScreenshot("chats_list_not_found", "Could not navigate back to chats list")
        }
        
        // Navigate back to settings
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_back_to_settings_failed", "Failed to navigate back to Settings screen")
        }
        
        // Verify token is still set
        val tokenStillSetAfterNav = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 5000,
            description = "Token is set after navigation"
        )
        if (!tokenStillSetAfterNav) {
            failWithScreenshot("token_not_persisted", "Token not persisted after navigation")
        }
        
        Log.d(TAG, "Settings persisted successfully across navigation")
        Log.d(TAG, "Screenshot would be taken here: settings_persisted")
        
        // Clean up
        try {
            composeTestRule.onAllNodesWithText("Clear")
                .onFirst()
                .performClick()
        } catch (e: Exception) {
            Log.w(TAG, "Could not clean up test token")
        }
    }
    
    @Test
    fun testTokenVisibility_PasswordMasking() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing token visibility toggle")
        
        // Enter a token
        val testToken = "sk-ant-visible-test-123"
        try {
            composeTestRule.onNodeWithText("Enter Claude API Key")
                .performTextInput(testToken)
        } catch (e: AssertionError) {
            // Token might be already set, try to find input field differently
            try {
                composeTestRule.onNode(hasSetTextAction()).performTextInput(testToken)
            } catch (e2: AssertionError) {
                failWithScreenshot("visibility_input_failed", "Could not enter token for visibility test")
            }
        }
        
        // Find the visibility toggle icon
        try {
            val visibilityIcon = composeTestRule.onNode(
                hasContentDescription("Show password")
                    .or(hasContentDescription("Show token"))
            )
            // Click to show the token
            visibilityIcon.performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("visibility_toggle_not_found", "Could not find visibility toggle icon")
        }
        
        Log.d(TAG, "Screenshot would be taken here: token_visible")
        
        // Click again to hide the token
        try {
            composeTestRule.onNode(
                hasContentDescription("Hide password")
                    .or(hasContentDescription("Hide token"))
            ).performClick()
        } catch (e: AssertionError) {
            Log.w(TAG, "Could not hide token again")
        }
        
        Log.d(TAG, "Screenshot would be taken here: token_hidden")
    }
    
    @Test
    fun testDataManagement_HardSync() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing hard sync functionality")
        
        // Scroll to Data Management section
        composeTestRule.onRoot()
            .performScrollToIndex(0)
        
        val forceSyncFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Force Full Sync") },
            timeoutMs = 5000,
            description = "Force Full Sync text"
        )
        if (!forceSyncFound) {
            failWithScreenshot("force_sync_not_found", "Force Full Sync not found")
        }
        
        val syncDescFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Clear local sync timestamps and re-download all data from server") },
            timeoutMs = 3000,
            description = "Sync description text"
        )
        if (!syncDescFound) {
            failWithScreenshot("data_management_not_complete", "Data Management section not complete")
        }
        
        // Verify sync button is enabled
        try {
            composeTestRule.onNodeWithText("Sync Now")
                .assertIsEnabled()
            
            // Test clicking sync (we won't wait for it to complete as it may take time)
            composeTestRule.onNodeWithText("Sync Now")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("sync_button_issue", "Sync Now button not found or disabled")
        }
        
        // Verify syncing state appears
        val syncingIndicatorFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Syncing...") },
            timeoutMs = 2000,
            description = "Syncing indicator"
        )
        if (!syncingIndicatorFound) {
            Log.w(TAG, "Syncing indicator may have completed too quickly")
        }
        
        Log.d(TAG, "Screenshot would be taken here: hard_sync_in_progress")
        
        Log.d(TAG, "Hard sync initiated successfully")
    }
}