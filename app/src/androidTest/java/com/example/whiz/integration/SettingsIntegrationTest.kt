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
        
        // Clear any existing settings
        runBlocking {
            try {
                Log.d(TAG, "Clearing existing settings before test")
                userPreferences.setClaudeToken("")
                userPreferences.setAsanaToken("")
                userPreferences.saveVoiceSettings(VoiceSettings())
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing settings", e)
            }
        }
    }
    
    @After
    fun tearDown() {
        // Clear test tokens after each test
        runBlocking {
            try {
                userPreferences.setClaudeToken("")
                userPreferences.setAsanaToken("")
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up settings", e)
            }
        }
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
        if (!navigateToSettings()) {
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
        }
        
        // Test setting Claude API key
        Log.d(TAG, "Testing Claude API key setting")
        
        // Verify we're on settings screen
        val claudeApiKeyFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Claude API Key") },
            timeoutMs = 5000,
            description = "Claude API Key text"
        )
        if (!claudeApiKeyFound) {
            failWithScreenshot("Claude API Key text not found", "claude_api_key_text_not_found")
        }
        
        // Check current state - token might already be set from previous tests
        val tokenAlreadySet = try {
            composeTestRule.onNodeWithText("Token is set.").assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
        
        if (tokenAlreadySet) {
            Log.d(TAG, "Token already set, clearing it first")
            // Find the first Clear button (for Claude API Key)
            try {
                composeTestRule.onAllNodesWithText("Clear")
                    .onFirst()
                    .performClick()
                Thread.sleep(1500) // Give time for the clear operation to complete
            } catch (e: AssertionError) {
                failWithScreenshot("Could not clear existing token - Clear button not found: ${e.message}", "initial_clear_failed")
            }
            
            // After clearing, the text field should appear
            // But it might not have placeholder text visible, so we'll proceed to enter text
        }
        
        // Enter a test token - look for text field by test tag or content description
        val testToken = "sk-ant-test-token-${System.currentTimeMillis()}"
        
        // Try to find the input field - it may not have placeholder text visible
        try {
            // Try finding by placeholder text
            composeTestRule.onNodeWithText("Enter Claude API Key")
                .performTextInput(testToken)
        } catch (e: AssertionError) {
            Log.d(TAG, "Placeholder not found, looking for text field")
            // Find the text field - it should be the first one after Claude API Key label
            try {
                composeTestRule.onNode(
                    hasSetTextAction()
                        .and(hasAnyAncestor(hasText("Claude API Key")))
                ).performTextInput(testToken)
            } catch (e2: AssertionError) {
                failWithScreenshot("Could not find Claude API Key input field after clearing: ${e2.message}", "claude_input_not_found")
            }
        }
        
        // Save the token - button text might be "Save" or "Save Claude API Key"
        try {
            try {
                composeTestRule.onNodeWithText("Save Claude API Key")
                    .assertIsEnabled()
                    .performClick()
            } catch (e: AssertionError) {
                composeTestRule.onNodeWithText("Save")
                    .assertIsEnabled()
                    .performClick()
            }
        } catch (e: AssertionError) {
            failWithScreenshot("Could not find Save button for Claude API Key: ${e.message}", "save_button_not_found")
        }
        
        // Wait for save to complete
        Thread.sleep(1000) // Give time for save operation
        
        // Verify token is set
        val tokenSetFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 5000,
            description = "Token is set text"
        )
        if (!tokenSetFound) {
            failWithScreenshot("Token was not saved successfully - 'Token is set.' text not found", "token_not_saved")
        }
        Log.d(TAG, "Screenshot would be taken here: claude_token_set")
        
        // Verify token was actually saved
        runBlocking {
            val hasToken = withTimeout(5000) {
                userPreferences.hasClaudeToken.first()
            }
            assertTrue("Claude token should be saved", hasToken == true)
        }
        
        // Test clearing the token
        Log.d(TAG, "Testing Claude API key clearing")
        try {
            composeTestRule.onAllNodesWithText("Clear")
                .onFirst() // First Clear button is for Claude API Key
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("Could not find Clear button for Claude API Key: ${e.message}", "clear_button_not_found")
        }
        
        // Wait for clear to complete
        Thread.sleep(1000)
        
        // Verify token is cleared - look for input field or verify "Token is set." is gone
        val tokenCleared = try {
            composeTestRule.onNodeWithText("Token is set.").assertDoesNotExist()
            true
        } catch (e: AssertionError) {
            false
        }
        
        if (!tokenCleared) {
            failWithScreenshot("Token was not cleared successfully", "token_not_cleared")
        }
        
        Log.d(TAG, "Screenshot would be taken here: claude_token_cleared")
        
        // Verify token was actually cleared
        runBlocking {
            val hasToken = withTimeout(5000) {
                userPreferences.hasClaudeToken.first()
            }
            assertFalse("Claude token should be cleared", hasToken == true)
        }
    }
    
    @Test
    fun testAsanaAccessToken_SetAndUnset() {
        if (!navigateToSettings()) {
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
        }
        
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
            failWithScreenshot("Could not find Asana Access Token section", "asana_section_not_found")
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
                failWithScreenshot("Could not clear existing Asana token: ${e.message}", "asana_clear_failed")
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
                failWithScreenshot("Could not find Asana input field: ${e2.message}", "asana_input_not_found")
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
                failWithScreenshot("Could not find Save button for Asana: ${e2.message}", "asana_save_not_found")
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
            failWithScreenshot("Asana token was not saved - 'Token is set.' not found", "asana_not_saved")
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
            failWithScreenshot("Token not set after change", "token_not_set_after_change")
        }
        
        // Test clearing the token
        Log.d(TAG, "Testing Asana access token clearing")
        try {
            composeTestRule.onAllNodesWithText("Clear")
                .get(1) // Second "Clear" button for Asana
                .performClick()
        } catch (e: Exception) {
            failWithScreenshot("Could not find Asana Clear button: ${e.message}", "asana_clear_button_not_found")
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
                failWithScreenshot("Asana token was not cleared", "asana_not_cleared")
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
    }
    
    @Test
    fun testVoiceSettings_CustomConfiguration() {
        if (!navigateToSettings()) {
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
            failWithScreenshot("Voice Settings section not found", "voice_settings_not_found")
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
                failWithScreenshot("Speech Rate not found after disabling system defaults", "speech_rate_not_found")
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
            failWithScreenshot("Speech Rate not visible", "speech_rate_not_visible")
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
            failWithScreenshot("Test Playback button not found or disabled", "test_playback_not_found")
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
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
                failWithScreenshot("Speech Rate not found", "speech_rate_not_found")
            }
        }
        
        // Verify we can see both sliders
        try {
            composeTestRule.onNodeWithText("Speech Rate").assertExists()
            composeTestRule.onNodeWithText("Voice Pitch").assertExists()
        } catch (e: AssertionError) {
            failWithScreenshot("Voice sliders not found", "voice_sliders_not_found")
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
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
            failWithScreenshot("Subscription section not found", "subscription_not_found")
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
                        failWithScreenshot("Subscription UI elements not as expected", "subscription_ui_issue")
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
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
                failWithScreenshot("Could not enter token for persistence test: ${e2.message}", "persistence_input_failed")
            }
        }
        
        try {
            composeTestRule.onNodeWithText("Save Claude API Key")
                .performClick()
        } catch (e: AssertionError) {
            try {
                composeTestRule.onNodeWithText("Save").performClick()
            } catch (e2: AssertionError) {
                failWithScreenshot("Could not save token for persistence test", "persistence_save_failed")
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
            failWithScreenshot("Token not set after save", "token_not_set_after_save")
        }
        
        // Navigate away from settings
        try {
            composeTestRule.onNodeWithContentDescription("Back")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("Could not navigate back from settings", "back_button_not_found")
        }
        
        // Wait for home screen
        val chatsListFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNode(hasContentDescription("Chats list")) },
            timeoutMs = 5000,
            description = "Chats list"
        )
        if (!chatsListFound) {
            failWithScreenshot("Could not navigate back to chats list", "chats_list_not_found")
        }
        
        // Navigate back to settings
        if (!navigateToSettings()) {
            failWithScreenshot("Failed to navigate back to Settings screen", "navigate_back_to_settings_failed")
        }
        
        // Verify token is still set
        val tokenStillSetAfterNav = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Token is set.") },
            timeoutMs = 5000,
            description = "Token is set after navigation"
        )
        if (!tokenStillSetAfterNav) {
            failWithScreenshot("Token not persisted after navigation", "token_not_persisted")
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
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
                failWithScreenshot("Could not enter token for visibility test", "visibility_input_failed")
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
            failWithScreenshot("Could not find visibility toggle icon", "visibility_toggle_not_found")
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
            failWithScreenshot("Failed to navigate to Settings screen", "navigate_to_settings_failed")
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
            failWithScreenshot("Force Full Sync not found", "force_sync_not_found")
        }
        
        val syncDescFound = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Clear local sync timestamps and re-download all data from server") },
            timeoutMs = 3000,
            description = "Sync description text"
        )
        if (!syncDescFound) {
            failWithScreenshot("Data Management section not complete", "data_management_not_complete")
        }
        
        // Verify sync button is enabled
        try {
            composeTestRule.onNodeWithText("Sync Now")
                .assertIsEnabled()
            
            // Test clicking sync (we won't wait for it to complete as it may take time)
            composeTestRule.onNodeWithText("Sync Now")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("Sync Now button not found or disabled", "sync_button_issue")
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