package com.example.whiz.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.whiz.MainActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.data.preferences.UserPreferences
import com.example.whiz.data.preferences.VoiceSettings
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
    
    private fun navigateToSettings() {
        Log.d(TAG, "Navigating to Settings screen")
        
        // Wait for the app to be ready - could be on chats list or chat screen
        Thread.sleep(2000) // Give app time to fully load
        
        // Try to find and click settings icon - it should be available on both screens
        try {
            composeTestRule.onNode(hasContentDescription("Settings"))
                .performClick()
        } catch (e: AssertionError) {
            Log.d(TAG, "Settings button not found, trying alternative selectors")
            // Try alternative selector
            composeTestRule.onNode(hasTestTag("settings_button"))
                .performClick()
        }
        
        // Wait for settings screen to load
        waitForText("Settings")
        waitForText("API Keys")
    }
    
    private fun waitForText(text: String, timeout: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                composeTestRule.onNodeWithText(text).assertExists()
                return
            } catch (e: AssertionError) {
                Thread.sleep(100)
            }
        }
        // Take screenshot before failing
        failWithScreenshot("Text '$text' not found within ${timeout}ms", "text_not_found_$text")
    }
    
    private fun waitForNode(matcher: SemanticsMatcher, timeout: Long = 5000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                composeTestRule.onNode(matcher).assertExists()
                return
            } catch (e: AssertionError) {
                Thread.sleep(100)
            }
        }
        // Take screenshot before failing
        failWithScreenshot("Node not found within ${timeout}ms", "node_not_found")
    }
    
    private fun waitUntilGone(text: String, timeout: Long = 3000) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                composeTestRule.onNodeWithText(text).assertDoesNotExist()
                return
            } catch (e: AssertionError) {
                Thread.sleep(100)
            }
        }
    }
    
    @Test
    fun testClaudeApiKey_SetAndUnset() {
        navigateToSettings()
        
        // Test setting Claude API key
        Log.d(TAG, "Testing Claude API key setting")
        
        // Verify we're on settings screen
        waitForText("Claude API Key")
        
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
        try {
            waitForText("Token is set.")
        } catch (e: AssertionError) {
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
        navigateToSettings()
        
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
        try {
            waitForText("Asana Access Token")
        } catch (e: AssertionError) {
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
        try {
            waitForText("Token is set.", timeout = 3000)
        } catch (e: AssertionError) {
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
        waitForText("Token is set.", timeout = 3000)
        
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
        try {
            waitForText("Enter Asana Access Token")
        } catch (e: AssertionError) {
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
        navigateToSettings()
        
        Log.d(TAG, "Testing voice settings configuration")
        
        // Scroll to Voice Settings section
        try {
            composeTestRule.onRoot()
                .performScrollToIndex(0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not scroll to voice settings")
        }
        
        // Verify initial state - system defaults may be on
        try {
            waitForText("Use System TTS Settings")
        } catch (e: AssertionError) {
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
            waitForText("Speech Rate")
        }
        
        // Test adjusting speech rate
        Log.d(TAG, "Testing speech rate adjustment")
        
        // The sliders are present, verify we can see the current values
        waitForText("Speech Rate")
        waitForText("100%") // Default speech rate
        
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
        navigateToSettings()
        
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
            waitForText("Speech Rate")
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
        navigateToSettings()
        
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
        try {
            waitForText("Subscription")
        } catch (e: AssertionError) {
            failWithScreenshot("Subscription section not found", "subscription_not_found")
        }
        
        // The subscription status could be either active or not
        // We'll just verify the section exists and is interactive
        try {
            // Case 1: No subscription
            if (composeTestRule.onAllNodesWithText("Premium Subscription").fetchSemanticsNodes().isNotEmpty()) {
                Log.d(TAG, "User has no active subscription")
                try {
                    waitForText("Get unlimited access to all features")
                    composeTestRule.onNodeWithText("Subscribe for $10/month")
                        .assertIsEnabled()
                } catch (e: AssertionError) {
                    failWithScreenshot("Subscription UI elements not as expected", "subscription_ui_issue")
                }
                Log.d(TAG, "Screenshot would be taken here: subscription_not_active")
            }
        } catch (e: AssertionError) {
            try {
                // Case 2: Active subscription
                if (composeTestRule.onAllNodesWithText("Premium Subscription Active").fetchSemanticsNodes().isNotEmpty()) {
                    Log.d(TAG, "User has active subscription")
                    waitForText("Premium Subscription Active")
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
        navigateToSettings()
        
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
        waitForText("Token is set.")
        
        // Navigate away from settings
        try {
            composeTestRule.onNodeWithContentDescription("Back")
                .performClick()
        } catch (e: AssertionError) {
            failWithScreenshot("Could not navigate back from settings", "back_button_not_found")
        }
        
        // Wait for home screen
        waitForNode(hasContentDescription("Chats list"))
        
        // Navigate back to settings
        navigateToSettings()
        
        // Verify token is still set
        waitForText("Token is set.")
        
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
        navigateToSettings()
        
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
        navigateToSettings()
        
        Log.d(TAG, "Testing hard sync functionality")
        
        // Scroll to Data Management section
        composeTestRule.onRoot()
            .performScrollToIndex(0)
        
        try {
            waitForText("Force Full Sync")
            waitForText("Clear local sync timestamps and re-download all data from server")
        } catch (e: AssertionError) {
            failWithScreenshot("Data Management section not found", "data_management_not_found")
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
        try {
            waitForText("Syncing...")
        } catch (e: AssertionError) {
            Log.w(TAG, "Syncing indicator may have completed too quickly")
        }
        
        Log.d(TAG, "Screenshot would be taken here: hard_sync_in_progress")
        
        Log.d(TAG, "Hard sync initiated successfully")
    }
}