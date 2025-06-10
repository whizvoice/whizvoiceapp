package com.example.whiz.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.ui.screens.ChatInputBar
import com.example.whiz.ui.theme.WhizTheme
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
    fun microphoneButton_basicStates() {
        // Test normal and active listening states
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
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = {},
                    onSendClick = {},
                    onInterruptClick = {},
                    onMicClick = {},
                    onMicClickDuringTTS = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Start listening").assertIsDisplayed()
    }

    @Test
    fun microphoneButton_activeListeningState() {
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "I'm speaking right now...",
                    isListening = true,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = false,
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = {},
                    onSendClick = {},
                    onInterruptClick = {},
                    onMicClick = {},
                    onMicClickDuringTTS = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        composeTestRule.onNodeWithText("I'm speaking right now...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Stop listening").assertIsDisplayed()
    }

    @Test
    fun microphoneButton_continuousListeningStates() {
        // Test both normal and responding states with continuous listening
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = "",
                    isListening = false,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = true,
                    isContinuousListeningEnabled = true,
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = {},
                    onSendClick = {},
                    onInterruptClick = {},
                    onMicClick = {},
                    onMicClickDuringTTS = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").assertIsDisplayed()
    }

    @Test
    fun microphoneButton_textAndSendButton() {
        // Test send button functionality
        var sendClicked = false
        
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
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = {},
                    onSendClick = { sendClicked = true },
                    onInterruptClick = {},
                    onMicClick = {},
                    onMicClickDuringTTS = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        composeTestRule.onNodeWithText("Hello Whiz").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        
        assert(sendClicked) { "Send button click should trigger callback" }
    }

    @Test
    fun microphoneButton_callbackFunctionality() {
        // Test microphone button callback
        var micClicked = false
        
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
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = {},
                    onSendClick = {},
                    onInterruptClick = {},
                    onMicClick = { micClicked = true },
                    onMicClickDuringTTS = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        
        assert(micClicked) { "Microphone button click should trigger callback" }
    }
} 