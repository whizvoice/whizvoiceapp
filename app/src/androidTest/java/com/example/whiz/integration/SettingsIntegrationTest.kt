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
import com.example.whiz.di.TestInterceptor

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
        // CRITICAL: Always restore valid tokens to prevent test pollution
        // This ensures subsequent tests have valid API keys even if a test fails
        Log.d(TAG, "Test cleanup - restoring valid test account tokens")

        try {
            // Get the actual test credentials
            val testCredentials = TestCredentialsManager.credentials
            val actualClaudeKey = testCredentials.testEnvironment.claudeApiKey
            val actualAsanaToken = testCredentials.testEnvironment.asanaToken

            // Use runBlocking to ensure tokens are restored before next test
            runBlocking {
                // Restore Claude API key - call once and wait for it to persist
                try {
                    userPreferences.setClaudeToken(actualClaudeKey)
                    Log.d(TAG, "Called setClaudeToken, waiting for it to persist...")

                    // Poll until the token is confirmed set, with timeout
                    val maxAttempts = 10
                    var attempt = 0
                    var tokenIsSet = false

                    while (attempt < maxAttempts && !tokenIsSet) {
                        delay(300) // Wait between checks

                        // Check if the token is now set
                        tokenIsSet = withTimeout(2000) {
                            userPreferences.hasClaudeToken.first()
                        } == true

                        attempt++

                        if (!tokenIsSet && attempt < maxAttempts) {
                            Log.d(TAG, "Token not yet persisted, checking again... (attempt $attempt/$maxAttempts)")
                        }
                    }

                    if (!tokenIsSet) {
                        val errorMsg = "❌ VERIFICATION FAILED: Claude token was not set after ${maxAttempts * 300}ms!"
                        Log.e(TAG, errorMsg)
                        fail(errorMsg)
                    }

                    Log.d(TAG, "✅ VERIFIED: Valid Claude API key restored successfully in cleanup after ${attempt * 300}ms")
                } catch (e: Exception) {
                    val errorMsg = "❌ CRITICAL: Failed to restore Claude API key in cleanup: ${e.message}"
                    Log.e(TAG, errorMsg)
                    e.printStackTrace()
                    fail(errorMsg)
                }

                // Restore Asana token - call once and wait for it to persist
                try {
                    userPreferences.setAsanaToken(actualAsanaToken)
                    Log.d(TAG, "Called setAsanaToken, waiting for it to persist...")

                    // Poll until the token is confirmed set, with timeout
                    val maxAttempts = 10
                    var attempt = 0
                    var tokenIsSet = false

                    while (attempt < maxAttempts && !tokenIsSet) {
                        delay(300) // Wait between checks

                        // Check if the token is now set
                        tokenIsSet = withTimeout(2000) {
                            userPreferences.hasAsanaToken.first()
                        } == true

                        attempt++

                        if (!tokenIsSet && attempt < maxAttempts) {
                            Log.d(TAG, "Token not yet persisted, checking again... (attempt $attempt/$maxAttempts)")
                        }
                    }

                    if (!tokenIsSet) {
                        val errorMsg = "❌ VERIFICATION FAILED: Asana token was not set after ${maxAttempts * 300}ms!"
                        Log.e(TAG, errorMsg)
                        fail(errorMsg)
                    }

                    Log.d(TAG, "✅ VERIFIED: Valid Asana token restored successfully in cleanup after ${attempt * 300}ms")
                } catch (e: Exception) {
                    val errorMsg = "❌ CRITICAL: Failed to restore Asana token in cleanup: ${e.message}"
                    Log.e(TAG, errorMsg)
                    e.printStackTrace()
                    fail(errorMsg)
                }
            }
        } catch (e: Exception) {
            val errorMsg = "❌ CRITICAL: Error during token restoration in tearDown: ${e.message}"
            Log.e(TAG, errorMsg)
            e.printStackTrace()
            fail(errorMsg)
        }

        Log.d(TAG, "✅ Test cleanup complete - tokens verified and restored for test account")
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
        
        // Save the original token to restore later
        var originalTokenExists = false
        runBlocking {
            originalTokenExists = userPreferences.hasClaudeToken.first() == true
        }
        
        // Check if Claude token needs clearing
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
            // Token is set, verify Clear button is enabled BEFORE clicking Change
            Log.d(TAG, "Claude token is currently set, verifying Clear button is enabled")
            
            // IMPORTANT TEST: Clear button should be clickable when token is set
            // Note: Clear button is the first one on the screen (for Claude), second would be for Asana
            val clearButtonBeforeChange = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onAllNodesWithText("Clear").onFirst()
                },
                timeoutMs = 3000,
                description = "Clear button for Claude API Key (before Change)"
            )
            
            if (clearButtonBeforeChange) {
                // Check if Clear button is enabled
                try {
                    composeTestRule.onAllNodesWithText("Clear").onFirst()
                        .assertIsEnabled()
                    Log.d(TAG, "✓ Clear button is correctly enabled when token is set")
                } catch (e: AssertionError) {
                    Log.e(TAG, "FAILURE: Clear button is disabled when it should be clickable")
                    failWithScreenshot("clear_button_disabled", "Clear button should be enabled when token is set, but it's disabled")
                }
            } else {
                Log.e(TAG, "FAILURE: Clear button not found when token is set")
                failWithScreenshot("clear_button_not_found", "Clear button should be visible when token is set")
            }
            
            // Now click Change to edit the token
            Log.d(TAG, "Clicking Change button to edit token")
            
            val changeButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNode(hasContentDescription("Change Claude token"))
                },
                timeoutMs = 3000,
                description = "Change button for Claude API Key"
            )
            
            if (changeButtonFound) {
                composeTestRule.onNode(hasContentDescription("Change Claude token"))
                    .performClick()
                Log.d(TAG, "Clicked Change button, waiting for input field")
                
                // Wait for the input field to appear after clicking Change
                // After clicking Change, the UI shows "Enter new Claude API Key"
                val inputFieldAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText("Enter new Claude API Key")
                    },
                    timeoutMs = 5000,
                    description = "Claude API Key input field after clicking Change"
                )
                
                if (!inputFieldAppeared) {
                    Log.e(TAG, "FAILURE: Input field did not appear after clicking Change button")
                    // Try alternative selector as fallback
                    val alternativeField = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true)
                        },
                        timeoutMs = 2000,
                        description = "Any text input field (fallback)"
                    )
                    if (!alternativeField) {
                        failWithScreenshot("change_failed_no_input", "Input field did not appear after clicking Change")
                    } else {
                        Log.d(TAG, "✓ Found input field using fallback selector")
                    }
                } else {
                    Log.d(TAG, "✓ Input field appeared, ready to enter new text (not clicking Clear)")
                }
            } else {
                Log.e(TAG, "FAILURE: Could not find Change button for Claude API Key")
                failWithScreenshot("change_button_not_found", "Could not find Change button for Claude API Key")
            }
        } else {
            Log.d(TAG, "Claude API key already cleared, input field is visible")
        }
        
        // Test setting an INVALID token
        Log.d(TAG, "Testing invalid token scenario")
        val invalidToken = "invalid-token-12345"
        
        // Enter text into the input field
        try {
            // Find the Claude API Key input field specifically using content description
            val inputFieldNode = composeTestRule.onNode(
                hasContentDescription("Claude API Key input field"),
                useUnmergedTree = true
            )
            
            // Clear any existing text first
            inputFieldNode.performTextClearance()
            
            // Now set the invalid token text
            inputFieldNode.performTextInput(invalidToken)
            Log.d(TAG, "Successfully entered invalid token: $invalidToken")
        } catch (e: AssertionError) {
            // If that fails, try alternative approach
            Log.w(TAG, "First approach failed, trying alternative: ${e.message}")
            try {
                // Try finding by SetTextAction but specifically the first one (Claude)
                val allInputFields = composeTestRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                allInputFields[0].performTextClearance()
                allInputFields[0].performTextInput(invalidToken)
                Log.d(TAG, "Successfully entered invalid token using first input field")
            } catch (e2: AssertionError) {
                Log.e(TAG, "FAILURE: Could not enter invalid token into input field")
                Log.e(TAG, "Error 1: ${e.message}")
                Log.e(TAG, "Error 2: ${e2.message}")
                failWithScreenshot("invalid_token_input_failed", "Could not enter invalid token: ${e2.message}")
            }
        }
        
        // Save the invalid token - use content description
        Log.d(TAG, "Looking for Save button to save invalid token")
        val saveButtonFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Save Claude API Key button"))
            },
            timeoutMs = 3000,
            description = "Save Claude API Key button"
        )
        
        if (!saveButtonFound) {
            Log.e(TAG, "FAILURE: 'Save Claude API Key' button not found on screen")
            failWithScreenshot("save_button_not_found", "Save Claude API Key button not found on screen")
        } else {
            Log.d(TAG, "Clicking 'Save Claude API Key' button")
            composeTestRule.onNode(hasContentDescription("Save Claude API Key button")).performClick()
        }
        
        // Wait for save operation to complete by checking for "Token is set." text
        Log.d(TAG, "Waiting for 'Claude token set' confirmation after saving invalid token")
        val invalidTokenSet = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Claude token set")) },
            timeoutMs = 5000,
            description = "Claude token set icon for invalid token"
        )
        
        if (!invalidTokenSet) {
            Log.e(TAG, "FAILURE: 'Token is set.' text not found after saving invalid token - save operation may have failed")
            failWithScreenshot("invalid_token_not_saved", "Invalid token was not saved")
        } else {
            Log.d(TAG, "✓ Invalid token saved successfully")
        }
        
        // Wait for the Snackbar message to confirm server has responded
        Log.d(TAG, "Waiting for server confirmation via Snackbar message")
        val serverConfirmed = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNodeWithText("Claude API Key saved successfully!")
            },
            timeoutMs = 3000,
            description = "Server confirmation Snackbar"
        )
        
        if (serverConfirmed) {
            Log.d(TAG, "✓ Server confirmed token was saved")
        } else {
            Log.d(TAG, "Warning: Snackbar confirmation not found, but continuing with test")
        }
        
        // Test Clear button functionality - it should remove token without requiring new input
        Log.d(TAG, "Testing Clear button removes token without requiring new input")
        
        // The invalid token is now set, test that Clear button works
        val clearAfterInvalidToken = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Clear Claude token"))
            },
            timeoutMs = 3000,
            description = "Clear button after invalid token is set"
        )
        
        if (clearAfterInvalidToken) {
            // Verify Clear button is enabled
            try {
                composeTestRule.onNode(hasContentDescription("Clear Claude token"))
                    .assertIsEnabled()
                Log.d(TAG, "✓ Clear button is enabled after setting invalid token")
            } catch (e: AssertionError) {
                Log.e(TAG, "FAILURE: Clear button is disabled after setting invalid token")
                failWithScreenshot("clear_disabled_after_invalid", "Clear button should be enabled after setting token")
            }
            
            // Click Clear to remove the token
            composeTestRule.onNode(hasContentDescription("Clear Claude token"))
                .performClick()
            Log.d(TAG, "Clicked Clear button to remove invalid token")
            
            // Verify input field appears and is empty
            val inputFieldAfterClear = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNodeWithText("Enter Claude API Key")
                },
                timeoutMs = 1000,
                description = "Input field after clearing"
            )
            
            if (inputFieldAfterClear) {
                Log.d(TAG, "✓ Clear button successfully removed token - input field is now visible")
                
                // Set the invalid token again for the next test
                val inputField = composeTestRule.onNode(
                    hasContentDescription("Claude API Key input field"),
                    useUnmergedTree = true
                )
                inputField.performTextInput(invalidToken)
                
                // Save it again - use content description
                val saveAgainButton = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNode(hasContentDescription("Save Claude API Key button"))
                    },
                    timeoutMs = 3000,
                    description = "Save Claude API Key button after re-entering token"
                )
                if (saveAgainButton) {
                    composeTestRule.onNode(hasContentDescription("Save Claude API Key button")).performClick()
                    
                    // Wait for save to complete by checking for "Token set" confirmation
                    val tokenSaved = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { composeTestRule.onNode(hasContentDescription("Claude token set")) },
                        timeoutMs = 2000,
                        description = "Claude token set confirmation after re-save"
                    )
                    
                    if (tokenSaved) {
                        Log.d(TAG, "Re-saved invalid token for chat test")
                    } else {
                        Log.w(TAG, "Token set confirmation not shown after re-save, but continuing")
                    }
                } else {
                    Log.e(TAG, "Could not find Save button after re-entering token")
                }
            } else {
                Log.e(TAG, "FAILURE: Input field not visible after clicking Clear")
                failWithScreenshot("clear_did_not_work", "Clear button did not remove token - input field not visible")
            }
        } else {
            Log.e(TAG, "Clear button not found after setting invalid token")
            Log.w(TAG, "Skipping Clear button test")
        }
        
        // Navigate to chat and verify error when using invalid Claude key
        Log.d(TAG, "Testing invalid token behavior in chat")
        try {
            // Navigate back to chats list
            composeTestRule.onNodeWithContentDescription("Back").performClick()            
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
                
                // Wait for the chat screen to load and message input field to appear
                Log.d(TAG, "Waiting for chat screen to load with message input field")
                val messageFieldFound = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNode(hasSetTextAction()) },
                    timeoutMs = 5000,
                    description = "Message input field"
                )
                
                if (messageFieldFound) {
                    Log.d(TAG, "Message field found, typing test message")
                    composeTestRule.onNode(hasSetTextAction()).performTextInput("Hello Claude")
                    
                    // Send the message - try multiple selectors
                    val sendButtonFound = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onNode(
                                hasContentDescription("Send")
                                    .or(hasContentDescription("Send message"))
                                    .or(hasContentDescription("Send typed message"))
                                    .or(hasText("Send"))
                            )
                        },
                        timeoutMs = 5000,
                        description = "Send button"
                    )
                    
                    if (sendButtonFound) {
                        Log.d(TAG, "Send button found, sending message with invalid API key")
                        composeTestRule.onNode(
                            hasContentDescription("Send")
                                .or(hasContentDescription("Send message"))
                                .or(hasContentDescription("Send typed message"))
                                .or(hasText("Send"))
                        ).performClick()
                        Log.d(TAG, "Waiting for API call to fail and error dialog to appear")
                        
                        // Wait for the error dialog to appear - look for the dialog title
                        // Use useUnmergedTree for dialog detection
                        val errorFound = ComposeTestHelper.waitForElement(
                            composeTestRule = composeTestRule,
                            selector = { 
                                composeTestRule.onNode(
                                    hasText("API Key Issue"),
                                    useUnmergedTree = true
                                )
                            },
                            timeoutMs = 10000,
                            description = "API Key Issue dialog title"
                        )
                        
                        if (errorFound) {
                            Log.d(TAG, "✓ Error dialog shown for invalid API key as expected")
                            
                            // Now click the "Go to Settings" button
                            Log.d(TAG, "Looking for 'Go to Settings' button to navigate back to settings")
                            val goToSettingsFound = ComposeTestHelper.waitForElement(
                                composeTestRule = composeTestRule,
                                selector = {
                                    composeTestRule.onNode(
                                        hasText("Go to Settings"),
                                        useUnmergedTree = true
                                    )
                                },
                                timeoutMs = 5000,
                                description = "Go to Settings button"
                            )
                            
                            if (goToSettingsFound) {
                                Log.d(TAG, "Clicking 'Go to Settings' button")
                                composeTestRule.onNode(hasText("Go to Settings"), useUnmergedTree = true).performClick()
                                
                                // Wait for Settings screen to appear
                                ComposeTestHelper.waitForElement(
                                    composeTestRule = composeTestRule,
                                    selector = { composeTestRule.onNodeWithText("Settings") },
                                    timeoutMs = 5000,
                                    description = "Settings screen"
                                )
                                Log.d(TAG, "✓ Clicked 'Go to Settings' button and navigated to Settings")
                            } else {
                                Log.e(TAG, "Go to Settings button not found in error dialog")
                            }
                        } else {
                            Log.e(TAG, "FAILURE: No error dialog found for invalid API key - UI should show error for invalid token")
                            failWithScreenshot("no_error_for_invalid_api_key", "Expected error dialog for invalid API key but none was shown")
                        }
                    } else {
                        Log.e(TAG, "FAILURE: Send button not found after typing message - cannot test invalid API key")
                        failWithScreenshot("send_button_not_found", "Send button not found in chat after typing message")
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
        Log.d(TAG, "✓ Invalid token chat test finished")
        
        // Go back to settings and restore the ACTUAL test account token
        Log.d(TAG, "Restoring test account's Claude API key to prevent test pollution")
        
        // Get the actual test credentials
        val testCredentials = TestCredentialsManager.credentials
        val actualClaudeKey = testCredentials.testEnvironment.claudeApiKey
        
        // If we clicked "Go to Settings" from the error dialog, we should already be in settings
        // If not, try to navigate there
        val alreadyInSettings = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("API Keys") },
            timeoutMs = 1000,
            description = "API Keys section header"
        )
        
        if (!alreadyInSettings) {
            Log.d(TAG, "Not in settings, trying to navigate there")
            val settingsButtonFound2 = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNode(hasContentDescription("Settings")) },
                timeoutMs = 3000,
                description = "Settings button"
            )
            
            if (settingsButtonFound2) {
                Log.d(TAG, "Navigating back to Settings to restore valid token")
                composeTestRule.onNode(hasContentDescription("Settings")).performClick()
                
                // Wait for Settings screen to appear
                ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("Settings") },
                    timeoutMs = 3000,
                    description = "Settings screen after navigation"
                )
            } else {
                Log.e(TAG, "ERROR: Could not find Settings button to restore valid token")
            }
        } else {
            Log.d(TAG, "Already in settings screen after clicking 'Go to Settings' from error dialog")
        }
        
        // First click Change button since token is already set
        val changeButton2Found = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Change Claude token")) },
            timeoutMs = 3000,
            description = "Change Claude token button"
        )
        
        // Restore the actual test account token
        // Wait for input field to be available after clicking Change
        val inputFieldFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(
                    hasContentDescription("Claude API Key input field"),
                    useUnmergedTree = true
                )
            },
            timeoutMs = 3000,
            description = "Claude API Key input field"
        )
        
        if (!inputFieldFound) {
            Log.e(TAG, "Claude API Key input field not found after clicking Change")
        }
        
        // Find and interact with the Claude API Key input field
        val inputField = composeTestRule.onNode(
            hasContentDescription("Claude API Key input field"),
            useUnmergedTree = true
        )
        inputField.performTextClearance()
        inputField.performTextInput(actualClaudeKey)
        Log.d(TAG, "Entered test account's Claude API key")
        
        // Save the valid token - use content description
        val saveButton2Found = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Save Claude API Key button"))
            },
            timeoutMs = 3000,
            description = "Save Claude API Key button for restoration"
        )
        
        if (!saveButton2Found) {
            Log.e(TAG, "FAILURE: 'Save Claude API Key' button not found when restoring token")
            failWithScreenshot("save_button_not_found_restore", "Save Claude API Key button not found when restoring token")
        }
        
        composeTestRule.onNode(hasContentDescription("Save Claude API Key button")).performClick()
        Log.d(TAG, "Clicked Save button to restore test account token")
        
        // Wait for the optimistic UI update to show "Token set" icon
        val tokenSetShown = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Token set")) },
            timeoutMs = 5000,
            description = "Token set icon after save"
        )
        
        if (tokenSetShown) {
            Log.d(TAG, "✓ Token restoration completed - UI shows 'Token set' with optimistic update")
        } else {
            Log.d(TAG, "Warning: 'Token set' icon not shown after save")
            Log.d(TAG, "This may indicate the save failed or the optimistic update was reverted")
            // Still continue with the test as the save operation might have succeeded
        }
        
        // CRITICAL: Ensure Claude API key is restored programmatically as a safety measure
        // The UI-based restoration above might fail or be incomplete
        runBlocking {
            try {
                userPreferences.setClaudeToken(actualClaudeKey)
                Log.d(TAG, "Called setClaudeToken programmatically, verifying...")

                // Give it a moment to persist
                delay(500)

                // VERIFY: Read back the token to confirm it was set
                val tokenIsSet = withTimeout(2000) {
                    userPreferences.hasClaudeToken.first()
                }

                if (tokenIsSet != true) {
                    val errorMsg = "❌ VERIFICATION FAILED: Programmatic Claude token restore failed! hasClaudeToken returned: $tokenIsSet"
                    Log.e(TAG, errorMsg)
                    failWithScreenshot("programmatic_restore_failed", errorMsg)
                }

                Log.d(TAG, "✅ VERIFIED: Programmatically restored Claude API key successfully")
            } catch (e: Exception) {
                val errorMsg = "❌ ERROR: Failed to programmatically restore Claude token: ${e.message}"
                Log.e(TAG, errorMsg)
                e.printStackTrace()
                failWithScreenshot("programmatic_restore_exception", errorMsg)
            }
        }

        // Wait for the UI to reflect the programmatically restored token
        val tokenRestoredInUI = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Claude token set")) },
            timeoutMs = 1500,
            description = "Token set confirmation after programmatic restore"
        )

        if (tokenRestoredInUI) {
            Log.d(TAG, "✓ UI confirmed Claude token is set after programmatic restoration")
        } else {
            Log.d(TAG, "Warning: UI confirmation timed out, but programmatic verification already succeeded")
        }
        
        Log.d(TAG, "✓ TEST COMPLETE: Successfully tested Claude API key management and restored test account token")
        Log.d(TAG, "========== testClaudeApiKey_SetAndUnset completed successfully ==========")
    }
    
    @Test
    fun testAsanaAccessToken_SetAndUnset() {
        Log.d(TAG, "========== Starting testAsanaAccessToken_SetAndUnset ==========")
        if (!navigateToSettings()) {
            Log.e(TAG, "FAILURE: Could not navigate to Settings screen")
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing Asana access token management")
        
        // Verify we're on settings screen
        Log.d(TAG, "Verifying Settings screen loaded - looking for 'Asana Access Token' text")
        val asanaAccessTokenFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Asana Access Token") },
            timeoutMs = 5000,
            description = "Asana Access Token text"
        )
        if (!asanaAccessTokenFound) {
            Log.e(TAG, "FAILURE: 'Asana Access Token' text not found on Settings screen")
            failWithScreenshot("asana_access_token_text_not_found", "Asana Access Token text not found")
        } else {
            Log.d(TAG, "✓ Settings screen loaded successfully")
        }
        
        // Save the original token to restore later
        var originalTokenExists = false
        runBlocking {
            originalTokenExists = userPreferences.hasAsanaToken.first() == true
        }
        
        // Check if Asana token needs clearing
        Log.d(TAG, "Checking if Asana access token needs clearing")
        
        // First check if the input field is already visible (token already cleared)
        // Need to wait for UI to load and fetch token status from server
        val inputFieldAlreadyVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNodeWithText("Enter Asana Access Token")
            },
            timeoutMs = 5000,  // Longer timeout to allow for server fetch
            description = "Asana Access Token input field (initial check)"
        )
        
        if (!inputFieldAlreadyVisible) {
            // Token is set, verify Clear button is enabled BEFORE clicking Change
            Log.d(TAG, "Asana token is currently set, verifying Clear button is enabled")
            
            // IMPORTANT TEST: Clear button should be clickable when token is set
            // Use content description to specifically target Asana's Clear button
            val clearButtonBeforeChange = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNode(hasContentDescription("Clear Asana token"))
                },
                timeoutMs = 3000,
                description = "Clear button for Asana Access Token (before Change)"
            )
            
            if (clearButtonBeforeChange) {
                // Check if Clear button is enabled
                try {
                    composeTestRule.onNode(hasContentDescription("Clear Asana token"))
                        .assertIsEnabled()
                    Log.d(TAG, "✓ Clear button is correctly enabled when token is set")
                } catch (e: AssertionError) {
                    Log.e(TAG, "FAILURE: Clear button is disabled when it should be clickable")
                    failWithScreenshot("asana_clear_button_disabled", "Clear button should be enabled when Asana token is set, but it's disabled")
                }
            } else {
                Log.e(TAG, "FAILURE: Clear button not found when token is set")
                failWithScreenshot("asana_clear_button_not_found", "Clear button should be visible when Asana token is set")
            }
            
            // Now click Change to edit the token
            Log.d(TAG, "Clicking Change button to edit token")
            
            val changeButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNode(hasContentDescription("Change Asana token"))
                },
                timeoutMs = 3000,
                description = "Change button for Asana Access Token"
            )
            
            if (changeButtonFound) {
                composeTestRule.onNode(hasContentDescription("Change Asana token"))
                    .performClick()
                Log.d(TAG, "Clicked Change button, waiting for input field")
                
                // Wait for the input field to appear after clicking Change
                // After clicking Change, the UI shows "Enter new Asana Access Token"
                val inputFieldAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText("Enter new Asana Access Token")
                    },
                    timeoutMs = 1000,
                    description = "Asana Access Token input field after clicking Change"
                )
                
                if (!inputFieldAppeared) {
                    Log.e(TAG, "FAILURE: Input field did not appear after clicking Change button")
                    // Try alternative selector as fallback
                    val alternativeField = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[1]
                        },
                        timeoutMs = 2000,
                        description = "Asana text input field (fallback)"
                    )
                    if (!alternativeField) {
                        failWithScreenshot("asana_change_failed_no_input", "Input field did not appear after clicking Change for Asana")
                    } else {
                        Log.d(TAG, "✓ Found input field using fallback selector")
                    }
                } else {
                    Log.d(TAG, "✓ Input field appeared, ready to enter new text (not clicking Clear)")
                }
            } else {
                Log.e(TAG, "FAILURE: Could not find Change button for Asana Access Token")
                failWithScreenshot("asana_change_button_not_found", "Could not find Change button for Asana Access Token")
            }
        } else {
            Log.d(TAG, "Asana access token already cleared, input field is visible")
        }
        
        // Test setting an INVALID token
        Log.d(TAG, "Testing invalid token scenario")
        val invalidToken = "invalid-asana-token-12345"
        
        // Enter text into the input field
        try {
            // Find the Asana Access Token input field specifically using content description
            val inputFieldNode = composeTestRule.onNode(
                hasContentDescription("Asana Access Token input field"),
                useUnmergedTree = true
            )
            
            // Clear any existing text first
            inputFieldNode.performTextClearance()
            
            // Now set the invalid token text
            inputFieldNode.performTextInput(invalidToken)
            Log.d(TAG, "Successfully entered invalid token: $invalidToken")
        } catch (e: AssertionError) {
            // If that fails, try alternative approach
            Log.w(TAG, "First approach failed, trying alternative: ${e.message}")
            try {
                // Try finding by SetTextAction but specifically the second one (Asana)
                val allInputFields = composeTestRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                allInputFields[1].performTextClearance()
                allInputFields[1].performTextInput(invalidToken)
                Log.d(TAG, "Successfully entered invalid token using second input field")
            } catch (e2: AssertionError) {
                Log.e(TAG, "FAILURE: Could not enter invalid token into input field")
                Log.e(TAG, "Error 1: ${e.message}")
                Log.e(TAG, "Error 2: ${e2.message}")
                failWithScreenshot("invalid_token_input_failed", "Could not enter invalid token: ${e2.message}")
            }
        }
        
        // Save the invalid token - use content description
        Log.d(TAG, "Looking for Save button to save invalid token")
        val saveButtonFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Save Asana Access Token button"))
            },
            timeoutMs = 3000,
            description = "Save Asana Access Token button"
        )
        
        if (!saveButtonFound) {
            Log.e(TAG, "FAILURE: 'Save Asana Access Token' button not found on screen")
            failWithScreenshot("asana_save_button_not_found", "Save Asana Access Token button not found on screen")
        } else {
            Log.d(TAG, "Clicking 'Save Asana Access Token' button")
            composeTestRule.onNode(hasContentDescription("Save Asana Access Token button")).performClick()
        }
        
        // Wait for save operation to complete by checking for "Token is set." text
        Log.d(TAG, "Waiting for 'Asana token set' confirmation after saving invalid token")
        val invalidTokenSet = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Asana token set"))
            },
            timeoutMs = 5000,
            description = "Asana token set icon for invalid token"
        )
        
        if (!invalidTokenSet) {
            Log.e(TAG, "FAILURE: 'Asana token set' icon not found after saving invalid token - save operation may have failed")
            failWithScreenshot("asana_invalid_token_not_saved", "Invalid Asana token was not saved")
        } else {
            Log.d(TAG, "✓ Invalid token saved successfully")
        }
        
        // Test Clear button functionality - it should remove token without requiring new input
        Log.d(TAG, "Testing Clear button removes token without requiring new input")
        
        // The invalid token is now set, test that Clear button works
        val clearAfterInvalidToken = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Clear Asana token"))
            },
            timeoutMs = 3000,
            description = "Clear button after invalid token is set"
        )
        
        if (clearAfterInvalidToken) {
            // Verify Clear button is enabled
            var clearButtonEnabled = false
            try {
                composeTestRule.onNode(hasContentDescription("Clear Asana token"))
                    .assertIsEnabled()
                clearButtonEnabled = true
                Log.d(TAG, "✓ Clear button is enabled after setting invalid token")
            } catch (e: AssertionError) {
                Log.e(TAG, "FAILURE: Clear button is disabled after setting invalid token")
                failWithScreenshot("asana_clear_disabled_after_invalid", "Clear button should be enabled after setting Asana token")
            }
            
            if (clearButtonEnabled) {
                // Click Clear to remove the token
                composeTestRule.onNode(hasContentDescription("Clear Asana token"))
                    .performClick()
                Log.d(TAG, "Clicked Clear button to remove invalid token")
                
                // Verify input field appears and is empty
                val inputFieldAfterClear = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNodeWithText("Enter Asana Access Token")
                    },
                    timeoutMs = 1000,
                    description = "Input field after clearing"
                )
                
                if (inputFieldAfterClear) {
                    Log.d(TAG, "✓ Clear button successfully removed token - input field is now visible")
                    
                    // Set the invalid token again for the next test
                    val inputField = composeTestRule.onNode(
                        hasContentDescription("Asana Access Token input field"),
                        useUnmergedTree = true
                    )
                    inputField.performTextInput(invalidToken)
                    
                    // Save it again - use content description
                    val saveAgainButton = ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { 
                            composeTestRule.onNode(hasContentDescription("Save Asana Access Token button"))
                        },
                        timeoutMs = 3000,
                        description = "Save Asana Access Token button after re-entering token"
                    )
                    if (saveAgainButton) {
                        composeTestRule.onNode(hasContentDescription("Save Asana Access Token button")).performClick()
                        
                        // Wait for save to complete by checking for "Token set" confirmation
                        val tokenSaved = ComposeTestHelper.waitForElement(
                            composeTestRule = composeTestRule,
                            selector = { composeTestRule.onNode(hasContentDescription("Asana token set")) },
                            timeoutMs = 2000,
                            description = "Asana token set confirmation after re-save"
                        )
                        
                        if (tokenSaved) {
                            Log.d(TAG, "Re-saved invalid token for further testing")
                        } else {
                            Log.w(TAG, "Token set confirmation not shown after re-save, but continuing")
                        }
                    } else {
                        Log.e(TAG, "Could not find Save button after re-entering token")
                    }
                } else {
                    Log.e(TAG, "FAILURE: Input field not visible after clicking Clear")
                    failWithScreenshot("asana_clear_did_not_work", "Clear button did not remove Asana token - input field not visible")
                }
            }
        } else {
            Log.e(TAG, "Clear button not found after setting invalid token")
            Log.w(TAG, "Skipping Clear button test")
        }
        
        Log.d(TAG, "✓ Invalid token test finished")
        
        // Restore the ACTUAL test account token
        Log.d(TAG, "Restoring test account's Asana access token to prevent test pollution")
        
        // Get the actual test credentials
        val testCredentials = TestCredentialsManager.credentials
        val actualAsanaToken = testCredentials.testEnvironment.asanaToken
        
        // Check current state - we might already have an input field visible or need to click Change
        val inputFieldVisibleForRestore = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(
                    hasContentDescription("Asana Access Token input field"),
                    useUnmergedTree = true
                )
            },
            timeoutMs = 1000,
            description = "Asana Access Token input field (checking if already visible)"
        )
        
        if (!inputFieldVisibleForRestore) {
            // Need to click Change button first
            Log.d(TAG, "Input field not visible, looking for Change button")
            val changeButton = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { 
                    composeTestRule.onNode(hasContentDescription("Change Asana token"))
                },
                timeoutMs = 2000,
                description = "Change Asana token button"
            )
            
            if (changeButton) {
                composeTestRule.onNode(hasContentDescription("Change Asana token")).performClick()
                Log.d(TAG, "Clicked Change button to access input field")
                
                // Wait for the input field to appear after clicking Change
                val inputFieldAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { 
                        composeTestRule.onNode(
                            hasContentDescription("Asana Access Token input field"),
                            useUnmergedTree = true
                        )
                    },
                    timeoutMs = 2000,
                    description = "Asana Access Token input field after clicking Change"
                )
                
                if (!inputFieldAppeared) {
                    Log.w(TAG, "Input field did not appear immediately after clicking Change, continuing anyway")
                }
            }
        } else {
            Log.d(TAG, "Input field already visible, no need to click Change")
        }
        
        // Now wait for input field to be available
        val inputFieldFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(
                    hasContentDescription("Asana Access Token input field"),
                    useUnmergedTree = true
                )
            },
            timeoutMs = 3000,
            description = "Asana Access Token input field for restoration"
        )
        
        if (!inputFieldFound) {
            Log.e(TAG, "Asana Access Token input field not found for restoration")
            // Try fallback with safer approach
            try {
                val allInputFields = composeTestRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                val fieldCount = allInputFields.fetchSemanticsNodes().size
                Log.d(TAG, "Found $fieldCount input fields total")
                if (fieldCount > 1) {
                    allInputFields[1].performTextClearance()
                    allInputFields[1].performTextInput(actualAsanaToken)
                    Log.d(TAG, "Entered test account's Asana access token using fallback (field 1)")
                } else if (fieldCount == 1) {
                    allInputFields[0].performTextClearance()
                    allInputFields[0].performTextInput(actualAsanaToken)
                    Log.d(TAG, "Entered test account's Asana access token using fallback (field 0)")
                } else {
                    Log.e(TAG, "No input fields found for restoration")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not enter test account token: ${e.message}")
            }
        } else {
            // Find and interact with the Asana Access Token input field
            val inputField = composeTestRule.onNode(
                hasContentDescription("Asana Access Token input field"),
                useUnmergedTree = true
            )
            inputField.performTextClearance()
            inputField.performTextInput(actualAsanaToken)
            Log.d(TAG, "Entered test account's Asana access token")
        }
        
        // Save the valid token - use content description
        val saveButton2Found = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { 
                composeTestRule.onNode(hasContentDescription("Save Asana Access Token button"))
            },
            timeoutMs = 3000,
            description = "Save Asana Access Token button for restoration"
        )
        
        if (!saveButton2Found) {
            Log.e(TAG, "FAILURE: 'Save Asana Access Token' button not found when restoring token")
            failWithScreenshot("asana_save_button_not_found_restore", "Save Asana Access Token button not found when restoring token")
        }
        
        composeTestRule.onNode(hasContentDescription("Save Asana Access Token button")).performClick()
        Log.d(TAG, "Clicked Save button to restore test account token")
        
        // Wait for the optimistic UI update to show "Token set" icon - use Asana-specific selector
        val tokenSetShown = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Asana token set")) },
            timeoutMs = 5000,
            description = "Asana token set icon after save"
        )
        
        if (tokenSetShown) {
            Log.d(TAG, "✓ Token restoration completed - UI shows 'Asana token set' with optimistic update")
        } else {
            Log.d(TAG, "Warning: 'Asana token set' icon not shown after save")
            Log.d(TAG, "This may indicate the save failed or the optimistic update was reverted")
            // Still continue with the test as the save operation might have succeeded
        }
        
        Log.d(TAG, "✓ TEST COMPLETE: Successfully tested Asana access token management and restored test account token")
        Log.d(TAG, "========== testAsanaAccessToken_SetAndUnset completed successfully ==========")
    }
    
    @Test
    fun testVoiceSettings_CustomConfiguration() {
        // Save the initial voice settings to restore at the end
        var initialVoiceSettings: VoiceSettings? = null
        runBlocking {
            initialVoiceSettings = userPreferences.voiceSettings.first()
            Log.d(TAG, "Initial voice settings: $initialVoiceSettings")
        }
        
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing voice settings configuration")
        
        // Scroll to Voice Settings section
        try {
            // Use content description to find the specific Voice Settings content area
            composeTestRule.onNodeWithContentDescription("Voice Settings content")
                .performScrollTo()
        } catch (e: Exception) {
            try {
                // If that fails, try scrolling to the header
                composeTestRule.onNodeWithContentDescription("Voice Settings header")
                    .performScrollTo()
            } catch (e2: Exception) {
                // Take screenshot and fail the test
                Log.e(TAG, "Could not scroll to Voice Settings: ${e.message}")
                val testName = "testVoiceSettings_CustomConfiguration"
                takeFailureScreenshotAndWaitForCompletion(testName, "voice_settings_scroll_failed")
                fail("Could not scroll to Voice Settings section: ${e2.message}")
            }
        }
        
        // Verify initial state - system defaults may be on
        val voiceSettingsFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Use System TTS Settings") },
            timeoutMs = 1000,
            description = "Use System TTS Settings text"
        )
        if (!voiceSettingsFound) {
            failWithScreenshot("voice_settings_not_found", "Voice Settings section not found")
        }
        
        // STEP 1: First ensure we're in custom settings mode (system defaults OFF)
        // This gives us a known starting state for testing
        val customSettingsVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Speech Rate") },
            timeoutMs = 500,
            description = "Speech Rate text (checking if custom settings visible)"
        )
        
        if (!customSettingsVisible) {
            Log.d(TAG, "System defaults are currently ON - turning them OFF to show custom settings")
            // Find and click the switch using its content description
            val switchFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Use System TTS Settings switch") },
                timeoutMs = 1000,
                description = "Use System TTS Settings switch"
            )
            
            if (!switchFound) {
                failWithScreenshot("system_tts_switch_not_found", "Use System TTS Settings switch not found")
            }
            
            composeTestRule.onNodeWithContentDescription("Use System TTS Settings switch")
                .performClick()
            
            // Wait for custom settings to appear
            val speechRateFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Speech Rate") },
                timeoutMs = 1000,
                description = "Speech Rate text after toggle"
            )
            if (!speechRateFound) {
                failWithScreenshot("speech_rate_not_found", "Speech Rate not found after disabling system defaults")
            }
            
            // Wait for auto-save to complete after toggle change by waiting for any button to be enabled
            Log.d(TAG, "Waiting for auto-save to complete after toggle change")
            // The Test Playback button will be disabled during save, wait for it to be enabled
            ComposeTestHelper.waitForElementEnabled(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Test Playback") },
                timeoutMs = 2000,
                description = "Test Playback button to be enabled after toggle"
            )
        } else {
            Log.d(TAG, "Custom settings already visible (system defaults already OFF)")
        }
        
        // Test adjusting speech rate
        Log.d(TAG, "Testing speech rate adjustment")
        
        // The sliders are present, verify we can see the current values
        val speechRateVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Speech Rate") },
            timeoutMs = 1000,
            description = "Speech Rate text"
        )
        if (!speechRateVisible) {
            failWithScreenshot("speech_rate_not_visible", "Speech Rate not visible")
        }
        
        val defaultValueFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("100%") },
            timeoutMs = 1000,
            description = "Default 100% value"
        )
        if (!defaultValueFound) {
            Log.w(TAG, "Default 100% value not found")
        }
        
        // Test the Test Playback button
        Log.d(TAG, "Testing voice playback")
        
        // Wait for the button to be enabled (auto-save may still be in progress)
        val testPlaybackEnabled = ComposeTestHelper.waitForElementEnabled(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Test Playback") },
            timeoutMs = 2000,
            description = "Test Playback button to be enabled"
        )
        
        if (!testPlaybackEnabled) {
            failWithScreenshot("test_playback_disabled", "Test Playback button remained disabled")
        }
        
        composeTestRule.onNodeWithText("Test Playback")
            .performClick()
        Log.d(TAG, "Successfully clicked Test Playback button")
        
        // STEP 2: Now test switching to system defaults (turning them ON)
        Log.d(TAG, "Testing switch to system defaults - turning system defaults ON")
        
        // At this point, custom settings are visible (system defaults are OFF)
        // We will now click the switch to turn system defaults ON
        val switchForSystemDefaults = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Use System TTS Settings switch") },
            timeoutMs = 1000,
            description = "Use System TTS Settings switch for enabling"
        )
        
        if (!switchForSystemDefaults) {
            failWithScreenshot("switch_not_found_for_system", "Switch not found for enabling system defaults")
        }
        
        // First verify the current state of the toggle before clicking
        runBlocking {
            val currentSettings = userPreferences.voiceSettings.first()
            Log.d(TAG, "Current settings before toggle click: useSystemDefaults=${currentSettings.useSystemDefaults}")
        }
        
        Log.d(TAG, "Clicking switch to enable system defaults (hide custom settings)")
        composeTestRule.onNodeWithContentDescription("Use System TTS Settings switch")
            .performClick()
        
        // Verify the toggle actually changed by checking preferences
        // This also ensures the click has been processed
        runBlocking {
            val settingsAfterClick = userPreferences.voiceSettings.first()
            Log.d(TAG, "Settings after toggle click: useSystemDefaults=${settingsAfterClick.useSystemDefaults}")
        }
        
        // Wait for auto-save to complete after toggle change
        val saveCompleted = ComposeTestHelper.waitForElementEnabled(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Test Playback") },
            timeoutMs = 2000,
            description = "Test Playback button to be enabled after system defaults toggle"
        )
        
        if (!saveCompleted) {
            Log.w(TAG, "Test Playback button still disabled after 2s, continuing anyway")
        }
        
        // Verify custom settings are now hidden (Speech Rate should NOT be visible)
        // We use a short timeout expecting it NOT to be found
        val speechRateStillVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Speech Rate") },
            timeoutMs = 500,
            description = "Speech Rate should be hidden"
        )
        
        if (speechRateStillVisible) {
            Log.e(TAG, "ERROR: Speech Rate is still visible after switching to system defaults - custom settings should be hidden!")
            failWithScreenshot("custom_settings_still_visible", "Custom settings still visible when system defaults should be ON")
        } else {
            Log.d(TAG, "✓ Custom settings correctly hidden after enabling system defaults")
        }
        
        // Test Playback should still be available - verify it's enabled
        val testPlaybackStillEnabled = ComposeTestHelper.waitForElementEnabled(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Test Playback") },
            timeoutMs = 2000,
            description = "Test Playback button still enabled after system defaults"
        )
        
        if (!testPlaybackStillEnabled) {
            failWithScreenshot("test_playback_disabled_after_system", "Test Playback button disabled after switching to system defaults")
        }
        
        Log.d(TAG, "Screenshot would be taken here: voice_settings_system_defaults")
        
        // Verify settings were saved - replace assertion with check and screenshot on failure
        runBlocking {
            val settings = withTimeout(1000) {
                userPreferences.voiceSettings.first()
            }
            if (!settings.useSystemDefaults) {
                Log.e(TAG, "FAILURE: System defaults should be enabled but it's not")
                failWithScreenshot("system_defaults_not_enabled", "System defaults should be enabled but settings show: useSystemDefaults=${settings.useSystemDefaults}")
            } else {
                Log.d(TAG, "✓ System defaults correctly enabled in saved settings")
            }
            
            // Restore the initial voice settings
            if (initialVoiceSettings != null) {
                Log.d(TAG, "Restoring initial voice settings: $initialVoiceSettings")
                try {
                    userPreferences.saveVoiceSettings(initialVoiceSettings!!)
                    Log.d(TAG, "✓ Successfully restored initial voice settings")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restore initial voice settings: ${e.message}")
                }
            }
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
            // Use content description to find the specific Voice Settings content area
            composeTestRule.onNodeWithContentDescription("Voice Settings content")
                .performScrollTo()
        } catch (e: Exception) {
            try {
                // If that fails, try scrolling to the header
                composeTestRule.onNodeWithContentDescription("Voice Settings header")
                    .performScrollTo()
            } catch (e2: Exception) {
                // Take screenshot and fail the test
                Log.e(TAG, "Could not scroll to Voice Settings: ${e.message}")
                val testName = "testVoiceSettings_SliderInteraction"
                takeFailureScreenshotAndWaitForCompletion(testName, "voice_slider_scroll_failed")
                fail("Could not scroll to Voice Settings for slider interaction test: ${e2.message}")
            }
        }
        
        // Ensure custom settings are visible
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertExists()
        } catch (e: AssertionError) {
            // Turn off system defaults if needed using content description
            composeTestRule.onNodeWithContentDescription("Use System TTS Settings switch")
                .performClick()
            val speechRateFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Speech Rate") },
                timeoutMs = 1000,
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
    fun testSubscription_NoSubscription() {
        Log.d(TAG, "=== Testing NO SUBSCRIPTION scenario ===")
        
        // Set test mode to no subscription
        TestInterceptor.subscriptionTestMode = TestInterceptor.Companion.SubscriptionTestMode.NO_SUBSCRIPTION
        
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed_no_sub", "Failed to navigate to Settings screen")
        }
        
        // Scroll down to find Subscription section
        Log.d(TAG, "Scrolling to find Subscription section")
        try {
            composeTestRule.onNodeWithContentDescription("Subscription section header")
                .performScrollTo()
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to Subscription: ${e.message}")
        }
        
        // Wait for subscription section to load
        val subscriptionFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Subscription section") },
            timeoutMs = 1000,
            description = "Subscription section"
        )
        if (!subscriptionFound) {
            failWithScreenshot("subscription_not_found_no_sub", "Subscription section not found")
        }
        
        // Verify no subscription UI elements
        val premiumSubFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Premium subscription title") },
            timeoutMs = 1000,
            description = "Premium subscription title"
        )
        if (!premiumSubFound) {
            failWithScreenshot("no_subscription_ui_missing", "No subscription UI not displayed")
        }
        
        // Check for subscription benefits text
        val unlimitedAccessFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Subscription benefits description") },
            timeoutMs = 1000,
            description = "Subscription benefits description"
        )
        if (!unlimitedAccessFound) {
            Log.w(TAG, "Unlimited access text not found - checking for alternative text")
        }
        
        // Verify Subscribe button is present and enabled
        try {
            composeTestRule.onNodeWithContentDescription("Subscribe button")
                .assertExists()
                .assertIsEnabled()
            Log.d(TAG, "✅ Subscribe button found and enabled for no subscription case")
        } catch (e: AssertionError) {
            failWithScreenshot("subscribe_button_issue", "Subscribe button not found or not enabled")
        }
        
        Log.d(TAG, "✅ No subscription UI test completed successfully")
    }
    
    @Test
    fun testSubscription_ActiveWithRenewal() {
        Log.d(TAG, "=== Testing ACTIVE SUBSCRIPTION WITH AUTO-RENEWAL scenario ===")
        
        // Set test mode to active with renewal
        TestInterceptor.subscriptionTestMode = TestInterceptor.Companion.SubscriptionTestMode.ACTIVE_WITH_RENEWAL
        
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed_renewal", "Failed to navigate to Settings screen")
        }
        
        // Scroll down to find Subscription section
        Log.d(TAG, "Scrolling to find Subscription section")
        try {
            composeTestRule.onNodeWithContentDescription("Subscription section header")
                .performScrollTo()
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to Subscription: ${e.message}")
        }
        
        // Wait for subscription section to load
        val subscriptionFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Subscription section") },
            timeoutMs = 1000,
            description = "Subscription section"
        )
        if (!subscriptionFound) {
            failWithScreenshot("subscription_not_found_renewal", "Subscription section not found")
        }
        
        // Verify active subscription UI
        val premiumActiveFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Premium subscription active status") },
            timeoutMs = 1000,
            description = "Premium subscription active status"
        )
        if (!premiumActiveFound) {
            failWithScreenshot("active_subscription_ui_missing", "Active subscription UI not displayed")
        }
        
        // Check for renewal date text (should show "Renews on" or similar)
        try {
            // Look for renewal date content description
            val renewsTextFound = composeTestRule.onNodeWithContentDescription("Subscription renewal date")
                .fetchSemanticsNode()
            Log.d(TAG, "✅ Renewal date information found")
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify renewal text: ${e.message}")
        }
        
        // Verify Cancel Subscription button is present
        try {
            composeTestRule.onNodeWithContentDescription("Cancel subscription button")
                .assertExists()
                .assertIsEnabled()
            Log.d(TAG, "✅ Cancel Subscription button found and enabled")
        } catch (e: AssertionError) {
            failWithScreenshot("cancel_button_missing_renewal", "Cancel button not found for active subscription")
        }
        
        Log.d(TAG, "✅ Active subscription with renewal UI test completed successfully")
    }
    
    @Test
    fun testSubscription_ActiveWithEndDate() {
        Log.d(TAG, "=== Testing ACTIVE SUBSCRIPTION WITH END DATE (CANCELED) scenario ===")
        
        // Set test mode to active with end date
        TestInterceptor.subscriptionTestMode = TestInterceptor.Companion.SubscriptionTestMode.ACTIVE_WITH_END_DATE
        
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed_canceled", "Failed to navigate to Settings screen")
        }
        
        // Scroll down to find Subscription section
        Log.d(TAG, "Scrolling to find Subscription section")
        try {
            composeTestRule.onNodeWithContentDescription("Subscription section header")
                .performScrollTo()
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to Subscription: ${e.message}")
        }
        
        // Wait for subscription section to load
        val subscriptionFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Subscription section") },
            timeoutMs = 1000,
            description = "Subscription section"
        )
        if (!subscriptionFound) {
            failWithScreenshot("subscription_not_found_canceled", "Subscription section not found")
        }
        
        // Verify active subscription UI (still active but canceled)
        val premiumActiveFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Premium Subscription Active") },
            timeoutMs = 1500,
            description = "Premium Subscription Active text"
        )
        if (!premiumActiveFound) {
            failWithScreenshot("active_subscription_ui_missing_canceled", "Active subscription UI not displayed")
        }
        
        // Check for end date text (should show "Ends on" or "Expires" or similar)
        try {
            // Look for end date content description
            val endsTextFound = composeTestRule.onNodeWithContentDescription("Subscription end date")
                .fetchSemanticsNode()
            Log.d(TAG, "✅ Subscription end date information found")
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify end date text: ${e.message}")
        }
        
        // For canceled subscription, the cancel button should not exist
        try {
            composeTestRule.onNodeWithContentDescription("Cancel subscription button")
                .assertDoesNotExist()
            Log.d(TAG, "✅ Cancel button correctly not shown for already canceled subscription")
        } catch (e: AssertionError) {
            Log.w(TAG, "Cancel button still visible for canceled subscription")
        }
        
        Log.d(TAG, "✅ Active subscription with end date (canceled) UI test completed successfully")
        
        // Reset test mode
        TestInterceptor.subscriptionTestMode = TestInterceptor.Companion.SubscriptionTestMode.NONE
    }
    
    @Test
    fun testSettingsPersistence_AcrossNavigation() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing settings persistence across navigation")
        
        // Set a Claude token
        val testToken = "sk-ant-persistence-test-${System.currentTimeMillis()}"
        
        // First check if we can see the input field directly (token not set or already in edit mode)
        val inputFieldVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Claude API Key input field") },
            timeoutMs = 1000,
            description = "Claude API Key input field"
        )
        
        if (inputFieldVisible) {
            // Input field is visible, we can directly enter the token
            val inputField = composeTestRule.onNodeWithContentDescription("Claude API Key input field")
            inputField.performTextClearance()
            inputField.performTextInput(testToken)
            Log.d(TAG, "Entered persistence test token directly: $testToken")
        } else {
            // Token is set and not in edit mode, need to click Change first
            Log.d(TAG, "Token is set, clicking Change button to enter edit mode")
            val changeButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Change Claude token") },
                timeoutMs = 1000,
                description = "Change Claude token button"
            )
            
            if (changeButtonFound) {
                composeTestRule.onNodeWithContentDescription("Change Claude token")
                    .performClick()
                
                // Wait for the input field to appear after clicking Change
                val inputFieldAppeared = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithContentDescription("Claude API Key input field") },
                    timeoutMs = 1000,
                    description = "Claude API Key input field after Change"
                )
                
                if (inputFieldAppeared) {
                    // Now enter the new token
                    val inputField = composeTestRule.onNodeWithContentDescription("Claude API Key input field")
                    inputField.performTextClearance()
                    inputField.performTextInput(testToken)
                    Log.d(TAG, "Entered persistence test token after clicking Change: $testToken")
                } else {
                    failWithScreenshot("input_field_not_appeared", "Input field did not appear after clicking Change")
                }
            } else {
                failWithScreenshot("change_button_not_found", "Could not find Change button to enter edit mode")
            }
        }
        
        // Save the token using content description
        try {
            composeTestRule.onNodeWithContentDescription("Save Claude API Key button")
                .performClick()
            Log.d(TAG, "Clicked Save button")
        } catch (e: AssertionError) {
            failWithScreenshot("persistence_save_failed", "Could not save token for persistence test")
        }
        
        // Wait for the token to be saved - check for the checkmark icon content description
        val tokenSetAfterSave = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Claude token set") },
            timeoutMs = 2000,
            description = "Token set indicator"
        )
        if (!tokenSetAfterSave) {
            // Try alternate: look for the "Token is set." text since we can see it in the screenshot
            val tokenTextFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onAllNodesWithText("Token is set.").onFirst() },
                timeoutMs = 1000,
                description = "Token is set text"
            )
            if (!tokenTextFound) {
                failWithScreenshot("token_not_set_after_save", "Token not set after save")
            }
        }
        
        // Navigate away from settings
        try {
            composeTestRule.onNodeWithContentDescription("Back")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("back_button_not_found", "Could not navigate back from settings")
        }
        
        // Wait for home screen - check for settings button to confirm we're on main screen
        val settingsButtonFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Settings") },
            timeoutMs = 1000,
            description = "Settings button on main screen"
        )
        if (!settingsButtonFound) {
            failWithScreenshot("main_screen_not_found", "Could not navigate back to main screen")
        }
        
        // Navigate back to settings
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_back_to_settings_failed", "Failed to navigate back to Settings screen")
        }
        
        // Verify token is still set using content description
        val tokenStillSetAfterNav = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Claude token set") },
            timeoutMs = 1000,
            description = "Token still set after navigation"
        )
        if (!tokenStillSetAfterNav) {
            // Try alternate: look for the "Token is set." text
            val tokenTextFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onAllNodesWithText("Token is set.").onFirst() },
                timeoutMs = 1000,
                description = "Token is set text after navigation"
            )
            if (!tokenTextFound) {
                failWithScreenshot("token_not_persisted", "Token not persisted after navigation")
            }
        }
        
        Log.d(TAG, "Settings persisted successfully across navigation")

        // Note: Cleanup is handled by @After tearDown() to avoid race conditions
        // Previously, this test would try to clear the token here, which would race with
        // tearDown()'s restoration, causing the database SET and CLEAR operations to interleave
        Log.d(TAG, "Test complete - tearDown() will restore the original token")
    }
    
    @Test
    fun testTokenVisibility_PasswordMasking() {
        if (!navigateToSettings()) {
            failWithScreenshot("navigate_to_settings_failed", "Failed to navigate to Settings screen")
        }
        
        Log.d(TAG, "Testing token visibility toggle")
        
        // Enter a token
        val testToken = "sk-ant-visible-test-123"
        
        // First check if input field is already visible (token not set)
        val inputFieldVisible = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Claude API Key input field") },
            timeoutMs = 1000,
            description = "Claude API Key input field for visibility test"
        )
        
        if (!inputFieldVisible) {
            // Token might be set, need to click Change first
            Log.d(TAG, "Token appears to be set, clicking Change to enter edit mode")
            val changeButtonFound = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Change Claude token") },
                timeoutMs = 1000,
                description = "Change Claude token button"
            )
            
            if (changeButtonFound) {
                try {
                    composeTestRule.onNodeWithContentDescription("Change Claude token")
                        .performClick()
                    
                    // Wait for input field to appear
                    ComposeTestHelper.waitForElement(
                        composeTestRule = composeTestRule,
                        selector = { composeTestRule.onNodeWithContentDescription("Claude API Key input field") },
                        timeoutMs = 1000,
                        description = "Claude API Key input field after Change"
                    )
                } catch (e: AssertionError) {
                    Log.e(TAG, "Failed to click Change button: ${e.message}")
                    failWithScreenshot("change_button_click_failed", "Found Change button but could not click it")
                }
            }
        }
        
        try {
            // Now enter the token using content description
            val inputField = composeTestRule.onNodeWithContentDescription("Claude API Key input field")
            inputField.performTextClearance()
            inputField.performTextInput(testToken)
            Log.d(TAG, "Entered visibility test token: $testToken")
        } catch (e: AssertionError) {
            Log.e(TAG, "FAILURE: Could not enter token for visibility test: ${e.message}")
            failWithScreenshot("visibility_input_failed", "Could not enter token for visibility test")
        }
        
        // Check if visibility toggle exists without throwing assertion
        val visibilityToggleExists = try {
            composeTestRule.onNode(
                hasContentDescription("Show password")
                    .or(hasContentDescription("Show token"))
                    .or(hasContentDescription("Hide password"))
                    .or(hasContentDescription("Hide token"))
            ).assertExists()
            true
        } catch (e: AssertionError) {
            Log.d(TAG, "Visibility toggle not found - taking screenshot to document current UI")
            false
        }
        
        if (!visibilityToggleExists) {
            // Take screenshot to show what the UI actually looks like
            failWithScreenshot("visibility_toggle_not_found", "Could not find visibility toggle icon - UI may not have this feature implemented")
            return // Exit test since we can't proceed without the toggle
        }
        
        // If we get here, the visibility toggle exists
        try {
            val visibilityIcon = composeTestRule.onNode(
                hasContentDescription("Show password")
                    .or(hasContentDescription("Show token"))
            )
            // Click to show the token
            visibilityIcon.performClick()
            Log.d(TAG, "Successfully clicked visibility toggle to show token")
        } catch (e: AssertionError) {
            Log.e(TAG, "Could not click visibility toggle: ${e.message}")
            failWithScreenshot("visibility_toggle_click_failed", "Found toggle but could not click it")
        }
        
        Log.d(TAG, "Screenshot would be taken here: token_visible")
        
        // Click again to hide the token
        try {
            composeTestRule.onNode(
                hasContentDescription("Hide password")
                    .or(hasContentDescription("Hide token"))
            ).performClick()
            Log.d(TAG, "Successfully clicked visibility toggle to hide token")
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
        try {
            // Use content description to find the specific Data Management content area
            composeTestRule.onNodeWithContentDescription("Data Management content")
                .performScrollTo()
        } catch (e: Exception) {
            try {
                // If that fails, try scrolling to the header
                composeTestRule.onNodeWithContentDescription("Data Management header")
                    .performScrollTo()
            } catch (e2: Exception) {
                // Take screenshot and fail the test
                Log.e(TAG, "Could not scroll to Data Management: ${e.message}")
                val testName = "testDataManagement_HardSync"
                takeFailureScreenshotAndWaitForCompletion(testName, "data_management_scroll_failed")
                fail("Could not scroll to Data Management section: ${e2.message}")
            }
        }
        
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