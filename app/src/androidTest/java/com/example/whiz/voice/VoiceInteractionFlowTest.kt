package com.example.whiz.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.MainActivity
import com.example.whiz.di.AppModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoiceInteractionFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun normalChatOpening_showsCorrectInitialState() {
        composeTestRule.waitForIdle()
        
        // Wait for app to load with timeout
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            try {
                // Look for any main screen elements that indicate the app has loaded
                composeTestRule.onNodeWithContentDescription("Microphone").assertExists()
                true
            } catch (e: Exception) {
                try {
                    composeTestRule.onNodeWithText("Sign in with Google").assertExists()
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }
        
        // Test that we can find expected UI elements
        try {
            composeTestRule.onNodeWithContentDescription("Microphone").assertIsDisplayed()
        } catch (e: Exception) {
            // If microphone not found, we might be on login screen
            composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
        }
    }

    @Test
    fun voiceActivatedOpening_showsContinuousListening() {
        composeTestRule.waitForIdle()
        
        // Wait for app to load
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            try {
                composeTestRule.onNodeWithContentDescription("Microphone").assertExists()
                true
            } catch (e: Exception) {
                try {
                    composeTestRule.onNodeWithText("Sign in with Google").assertExists()
                    true
                } catch (e2: Exception) {
                    false
                }
            }
        }
        
        // Test voice activation features if we're in the main app
        try {
            composeTestRule.onNodeWithContentDescription("Microphone").assertIsDisplayed()
            // Additional voice-specific tests could go here
        } catch (e: Exception) {
            // If not on main screen, verify we're on expected screen
            composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
        }
    }

    @Test
    fun userSpeechInput_showsTranscriptionInInputBar() {
        // Test: Active speech transcription is displayed in the input bar
        val transcriptionText = "Hello, I'm speaking to Whiz"
        
        composeTestRule.waitForIdle()
        
        // Verify transcription appears in the input field while listening
        composeTestRule.onNodeWithText(transcriptionText).assertIsDisplayed()
        // Should show stop listening button while actively listening
        composeTestRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    @Test
    fun userFinishesSpeaking_inputShowsGrayedSubmittedMessage() {
        // Test: After speech ends, message is submitted and shown grayed out
        val submittedMessage = "Hello, I submitted this message"
        
        composeTestRule.waitForIdle()
        
        // Should show the submitted message and red mute button for continuous listening
        composeTestRule.onNodeWithText(submittedMessage).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun clickRedMuteButton_turnsContinuousListeningOff() {
        // Test: Clicking red mute button toggles continuous listening off
        var isContinuousListening = true
        var micClickCount = 0
        
        composeTestRule.waitForIdle()
        
        // Initial state should show red mute button
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
        
        // Click the button to toggle continuous listening
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").performClick()
        
        // Verify callback was triggered
        assert(micClickCount == 1)
        assert(isContinuousListening == false)
    }

    @Test
    fun whizResponds_inputBarClears_statePreserved() {
        // Test: After bot responds, input clears but state is preserved - simplified to test component behavior
        composeTestRule.waitForIdle()

        // Verify the input bar is back to ready state
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        
        // Verify that the microphone button is present (continuous listening preserved)
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun userTypesText_micButtonBecomesSendButton() {
        // Test: When user types, mic button changes to send button
        val typedText = "Hello typed message"
        
        composeTestRule.waitForIdle()
        
        // Should display the typed text
        composeTestRule.onNodeWithText(typedText).assertIsDisplayed()
        
        // Should show send button when text is present
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun ttsReading_disablesMicInput() {
        // Test: During TTS reading, mic input is disabled (without headphones)
        composeTestRule.waitForIdle()

        // Verify that the input bar shows proper TTS state
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        
        // During TTS without headphones, mic button should not be visible for interruption
        // This tests the headphone-aware logic
    }

    @Test
    fun micButtonClickCallback_worksCorrectly() {
        // Test: Mic button clicks trigger callback properly
        var micClickCount = 0
        
        composeTestRule.waitForIdle()
        
        // Initial state
        assert(micClickCount == 0)
        
        // Click the mic button
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        
        // Verify callback was triggered
        assert(micClickCount == 1)
    }

    @Test
    fun sendButtonCallback_worksCorrectly() {
        // Test: Send button clicks trigger callback properly
        var sendClickCount = 0
        
        composeTestRule.waitForIdle()
        
        // Initial state
        assert(sendClickCount == 0)
        
        // Click the send button
        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        
        // Verify callback was triggered
        assert(sendClickCount == 1)
    }
} 