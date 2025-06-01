package com.example.whiz.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.ui.screens.ChatInputBar
import com.example.whiz.ui.theme.WhizTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MicrophoneButtonStateTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun chatInputBar_continuousListening_showsRedMuteButton() {
        // Test: Continuous listening enabled shows red mute button (mic off icon)
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = true, // This should show red mute
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify the input bar is displayed
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        
        // When continuous listening is enabled during response, should show "Turn off continuous listening"
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_normalState_showsBlueMicButton() {
        // Test: Normal state shows blue mic button
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false, // Normal state
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify the input bar is displayed
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        
        // Normal state should show "Start listening"
        composeTestRule.onNodeWithContentDescription("Start listening").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_activeListening_showsRedStopButton() {
        // Test: Active listening shows red stop button
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "I'm speaking right now...",
                    isListening = true, // Currently listening
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show listening placeholder
        composeTestRule.onNodeWithText("Listening...").assertIsDisplayed()
        
        // Active listening should show "Stop listening"
        composeTestRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
        
        // Should display transcription
        composeTestRule.onNodeWithText("I'm speaking right now...").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_hasText_showsSendButton() {
        // Test: When user has typed text, shows send button
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "Hello Whiz",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should display the typed text
        composeTestRule.onNodeWithText("Hello Whiz").assertIsDisplayed()
        
        // Should show send button when text is present
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_respondingWithContinuousListening_showsRedMute() {
        // Test: During response with continuous listening enabled, shows red mute
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "My previous message",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true, // Text input disabled during response
                    isMicDisabled = false, // Mic still works to control continuous listening
                    isResponding = true, // Bot is responding
                    isContinuousListeningEnabled = true, // Continuous listening is on
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show the previous message (grayed out)
        composeTestRule.onNodeWithText("My previous message").assertIsDisplayed()
        
        // During response with continuous listening, should show option to turn it off
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_respondingWithoutContinuousListening_showsBlueMic() {
        // Test: During response without continuous listening, shows blue mic
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "My previous message",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true, // Text input disabled during response
                    isMicDisabled = false,
                    isResponding = true, // Bot is responding
                    isContinuousListeningEnabled = false, // Continuous listening is off
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show the previous message
        composeTestRule.onNodeWithText("My previous message").assertIsDisplayed()
        
        // During response without continuous listening, should show option to turn it on
        composeTestRule.onNodeWithContentDescription("Turn on continuous listening").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_micButtonClick_triggersCallback() {
        // Test: Microphone button click triggers the callback
        var micClickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = { micClickCount++ },
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Initial state
        assert(micClickCount == 0)
        
        // Click the microphone button
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        
        // Verify callback was triggered
        assert(micClickCount == 1)
    }

    @Test
    fun chatInputBar_sendButtonClick_triggersCallback() {
        // Test: Send button click triggers the callback
        var sendClickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "Test message",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    onInputChange = {},
                    onSendClick = { sendClickCount++ },
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Initial state
        assert(sendClickCount == 0)
        
        // Click the send button
        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        
        // Verify callback was triggered
        assert(sendClickCount == 1)
    }

    @Test
    fun chatInputBar_disabledState_buttonsNotClickable() {
        // Test: When disabled, buttons should not be clickable
        var clickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = true, // Mic is disabled (e.g., during TTS)
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    onInputChange = {},
                    onSendClick = { clickCount++ },
                    onMicClick = { clickCount++ },
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // The button should be displayed but not enabled
        composeTestRule.onNodeWithContentDescription("Start listening").assertIsDisplayed()
        
        // Initial state
        assert(clickCount == 0)
        
        // Try to click the disabled button
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        
        // Callback should not be triggered because button is disabled
        assert(clickCount == 0)
    }
} 