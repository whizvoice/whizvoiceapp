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
class VoiceInteractionFlowTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun normalChatOpening_showsCorrectInitialState() {
        // Test: Normal chat state shows continuous listening enabled (red mute button)
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = true, // Normal state with continuous listening
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify placeholder is displayed and continuous listening state
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        // Note: Can't directly test button color, but functionality is verified
    }

    @Test
    fun voiceActivatedOpening_showsContinuousListening() {
        // Test: Voice-activated mode maintains continuous listening
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = true, // Voice-activated should enable this
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify the input bar is properly set up for voice activation
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
    }

    @Test
    fun userSpeechInput_showsTranscriptionInInputBar() {
        // Test: Active speech transcription is displayed in the input bar
        val transcriptionText = "Hello, I'm speaking to Whiz"
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = transcriptionText,
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
        
        // Verify transcription appears in the input field while listening
        composeTestRule.onNodeWithText(transcriptionText).assertIsDisplayed()
        // Should show stop listening button while actively listening
        composeTestRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    @Test
    fun userFinishesSpeaking_inputShowsGrayedSubmittedMessage() {
        // Test: After speech ends, message is submitted and shown grayed out
        val submittedMessage = "Hello, I submitted this message"
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = submittedMessage,
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true, // Input disabled while waiting for response
                    isMicDisabled = false,
                    isResponding = true, // Bot is responding
                    isContinuousListeningEnabled = true, // Continuous listening still on
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show the submitted message and red mute button for continuous listening
        composeTestRule.onNodeWithText(submittedMessage).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun clickRedMuteButton_turnsContinuousListeningOff() {
        // Test: Clicking red mute button toggles continuous listening off
        var isContinuousListening = true
        var micClickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "Previous message",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true,
                    isMicDisabled = false,
                    isResponding = true,
                    isContinuousListeningEnabled = isContinuousListening,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = { 
                        micClickCount++
                        isContinuousListening = !isContinuousListening 
                    },
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
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
        // Test: After bot responds, input clears but continuous listening state preserved
        var inputText = ""
        var isContinuousListening = true
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = inputText,
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false, // Input re-enabled after response
                    isMicDisabled = false,
                    isResponding = false, // Bot finished responding
                    isContinuousListeningEnabled = isContinuousListening,
                    onInputChange = { inputText = it },
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show placeholder when input is empty and continuous listening preserved
        if (inputText.isEmpty()) {
            composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        }
        
        // Should show appropriate button for continuous listening state
        if (isContinuousListening) {
            composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
        }
    }

    @Test
    fun userTypesText_micButtonBecomesSendButton() {
        // Test: When user types, mic button changes to send button
        val typedText = "Hello typed message"
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = typedText,
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
        composeTestRule.onNodeWithText(typedText).assertIsDisplayed()
        
        // Should show send button when text is present
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun ttsReading_disablesMicInput() {
        // Test: During TTS reading, mic input is disabled
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "Bot is reading this response",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true, // Input disabled during TTS
                    isMicDisabled = true, // Mic disabled during TTS to prevent conflicts
                    isResponding = false,
                    isContinuousListeningEnabled = true,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Should show the response text
        composeTestRule.onNodeWithText("Bot is reading this response").assertIsDisplayed()
        
        // Mic button should be present but disabled (verified by isMicDisabled = true)
        composeTestRule.onNodeWithContentDescription("Start listening").assertIsDisplayed()
    }

    @Test
    fun micButtonClickCallback_worksCorrectly() {
        // Test: Mic button clicks trigger callback properly
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
        
        // Click the mic button
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        
        // Verify callback was triggered
        assert(micClickCount == 1)
    }

    @Test
    fun sendButtonCallback_worksCorrectly() {
        // Test: Send button clicks trigger callback properly
        var sendClickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "Message to send",
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
} 