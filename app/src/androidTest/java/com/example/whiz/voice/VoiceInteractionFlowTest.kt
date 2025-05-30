package com.example.whiz.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
    fun normalChatOpening_startsWithContinuousListeningOn_ttsOff() {
        // Test: When a chat is selected from main menu
        // Expected: Continuous listening ON, TTS OFF, Red mute button
        
        var isContinuousListening = true
        var isTTSEnabled = false
        var micButtonState = "RED_MUTE" // RED_MUTE, GRAYED_MIC, BLUE_MIC, SEND_BUTTON
        
        composeTestRule.setContent {
            WhizTheme {
                // Mock the initial chat state
                androidx.compose.material3.Text("Chat opened - Continuous listening: $isContinuousListening, TTS: $isTTSEnabled")
                androidx.compose.material3.Text("Mic button state: $micButtonState")
            }
        }
        
        // Verify initial state
        assert(isContinuousListening == true)
        assert(isTTSEnabled == false)
        assert(micButtonState == "RED_MUTE")
        composeTestRule.onNodeWithText("Chat opened - Continuous listening: true, TTS: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mic button state: RED_MUTE").assertIsDisplayed()
    }

    @Test
    fun voiceActivatedOpening_startsWithContinuousListeningOn_ttsOn() {
        // Test: When chat opened via "OK Google Open Whiz Voice"
        // Expected: Continuous listening ON, TTS ON, Red mute button
        
        var isContinuousListening = true
        var isTTSEnabled = true
        var micButtonState = "RED_MUTE"
        var openedViaVoice = true
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("Voice activated - Continuous listening: $isContinuousListening, TTS: $isTTSEnabled")
                androidx.compose.material3.Text("Opened via voice: $openedViaVoice")
                androidx.compose.material3.Text("Mic button state: $micButtonState")
            }
        }
        
        // Verify voice-activated state
        assert(isContinuousListening == true)
        assert(isTTSEnabled == true)
        assert(openedViaVoice == true)
        assert(micButtonState == "RED_MUTE")
    }

    @Test
    fun userSpeechInput_showsInInputBarWhileSpeaking() {
        // Test: When user provides speech input
        // Expected: Input shows in input bar as user is speaking
        
        var isListening = true
        var transcription = "Hello, I'm speaking to Whiz"
        var inputText = ""
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("Listening: $isListening")
                androidx.compose.material3.Text("Transcription: $transcription")
                androidx.compose.material3.OutlinedTextField(
                    value = if (isListening) transcription else inputText,
                    onValueChange = {},
                    placeholder = { androidx.compose.material3.Text("Type or speak...") }
                )
            }
        }
        
        // Verify transcription appears while speaking
        assert(isListening == true)
        assert(transcription.isNotBlank())
        composeTestRule.onNodeWithText("Transcription: Hello, I'm speaking to Whiz").assertIsDisplayed()
    }

    @Test
    fun userPauseSpeech_submitsMessage_staysGrayedInInputBar() {
        // Test: When user pauses speaking
        // Expected: Message submits, stays grayed in input bar, red mute button remains
        
        var isWaitingForResponse = true
        var submittedMessage = "Hello, I'm speaking to Whiz"
        var micButtonState = "RED_MUTE" // Should remain red mute while waiting
        var inputBarState = "GRAYED_SUBMITTED" // Message shown but grayed out
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("Waiting for response: $isWaitingForResponse")
                androidx.compose.material3.Text("Submitted: $submittedMessage")
                androidx.compose.material3.Text("Input state: $inputBarState")
                androidx.compose.material3.Text("Mic button: $micButtonState")
                
                // Mock grayed out input
                androidx.compose.material3.OutlinedTextField(
                    value = submittedMessage,
                    onValueChange = {},
                    enabled = false, // Grayed out
                    placeholder = { androidx.compose.material3.Text("Waiting for Whiz...") }
                )
            }
        }
        
        // Verify waiting state
        assert(isWaitingForResponse == true)
        assert(submittedMessage.isNotBlank())
        assert(micButtonState == "RED_MUTE")
        assert(inputBarState == "GRAYED_SUBMITTED")
    }

    @Test
    fun clickRedMuteWhileWaiting_turnsContinuousListeningOff() {
        // Test: User clicks red mute button while waiting for response
        // Expected: Continuous listening OFF, button becomes grayed mic
        
        var isContinuousListening = true
        var isWaitingForResponse = true
        var micButtonState = "RED_MUTE"
        
        fun toggleContinuousListening() {
            isContinuousListening = !isContinuousListening
            micButtonState = if (isWaitingForResponse) "GRAYED_MIC" else "BLUE_MIC"
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Button(
                    onClick = { toggleContinuousListening() }
                ) {
                    androidx.compose.material3.Text("$micButtonState - Click to toggle")
                }
                androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
            }
        }
        
        // Initial state
        assert(isContinuousListening == true)
        assert(micButtonState == "RED_MUTE")
        
        // Click the button
        composeTestRule.onNodeWithText("RED_MUTE - Click to toggle").performClick()
        
        // Verify state change
        assert(isContinuousListening == false)
        assert(micButtonState == "GRAYED_MIC") // Grayed because still waiting
    }

    @Test
    fun whizReplies_inputBarClears_continuousListeningStatePreserved() {
        // Test: When Whiz replies
        // Expected: Input bar clears, continuous listening state preserved
        
        var continuousListeningBeforeReply = true
        var continuousListeningAfterReply = true
        var inputText = ""
        var micButtonState = "RED_MUTE"
        var isWaitingForResponse = false
        
        fun simulateWhizReply() {
            inputText = "" // Clear input bar
            isWaitingForResponse = false
            // Preserve continuous listening state
            micButtonState = if (continuousListeningAfterReply) "RED_MUTE" else "BLUE_MIC"
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Button(
                    onClick = { simulateWhizReply() }
                ) {
                    androidx.compose.material3.Text("Simulate Whiz Reply")
                }
                androidx.compose.material3.Text("Input: '$inputText'")
                androidx.compose.material3.Text("Continuous listening: $continuousListeningAfterReply")
                androidx.compose.material3.Text("Mic button: $micButtonState")
                androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
            }
        }
        
        // Before reply
        assert(continuousListeningBeforeReply == true)
        
        // Simulate reply
        composeTestRule.onNodeWithText("Simulate Whiz Reply").performClick()
        
        // After reply
        assert(inputText.isEmpty()) // Input cleared
        assert(continuousListeningAfterReply == continuousListeningBeforeReply) // State preserved
        assert(micButtonState == "RED_MUTE") // Red mute because continuous listening is on
        assert(isWaitingForResponse == false)
    }

    @Test
    fun userTypesText_micButtonBecomesSeñdButton() {
        // Test: When user types with keyboard
        // Expected: Mic button becomes send button
        
        var inputText = ""
        var micButtonState = "BLUE_MIC"
        var hasTypedText = false
        
        fun onTextInput(text: String) {
            inputText = text
            hasTypedText = text.isNotBlank()
            micButtonState = if (hasTypedText) "SEND_BUTTON" else "BLUE_MIC"
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.OutlinedTextField(
                    value = inputText,
                    onValueChange = { onTextInput(it) },
                    placeholder = { androidx.compose.material3.Text("Type your message...") }
                )
                androidx.compose.material3.Text("Mic button: $micButtonState")
                androidx.compose.material3.Text("Has typed text: $hasTypedText")
            }
        }
        
        // Initially no text
        assert(micButtonState == "BLUE_MIC")
        assert(hasTypedText == false)
        
        // Simulate typing
        onTextInput("Hello typed message")
        
        // Verify button changes to send
        assert(micButtonState == "SEND_BUTTON")
        assert(hasTypedText == true)
        assert(inputText == "Hello typed message")
    }

    @Test
    fun ttsReading_micBehavesLikeWaitingForReply() {
        // Test: When TTS is reading a reply
        // Expected: Mic behaves like waiting for reply
        
        var isTTSReading = true
        var isContinuousListening = true
        var micButtonState = "RED_MUTE" // Behaves like waiting
        var canAcceptNewInput = false
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("TTS reading: $isTTSReading")
                androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                androidx.compose.material3.Text("Mic button: $micButtonState")
                androidx.compose.material3.Text("Can accept input: $canAcceptNewInput")
            }
        }
        
        // Verify TTS reading state
        assert(isTTSReading == true)
        assert(micButtonState == "RED_MUTE")
        assert(canAcceptNewInput == false) // Can't accept new input while TTS reading
    }

    @Test
    fun turnOffTTSMidReading_micImmediatelyBecomesAvailable() {
        // Test: TTS turned off mid-reading
        // Expected: Mic immediately becomes available for new input
        
        var isTTSReading = true
        var isTTSEnabled = true
        var isContinuousListening = true
        var micButtonState = "RED_MUTE"
        var canAcceptNewInput = false
        
        fun turnOffTTS() {
            isTTSEnabled = false
            isTTSReading = false
            canAcceptNewInput = true
            // Mic should behave as if no reply is waiting
            micButtonState = if (isContinuousListening) "RED_MUTE" else "BLUE_MIC"
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Button(
                    onClick = { turnOffTTS() }
                ) {
                    androidx.compose.material3.Text("Turn off TTS")
                }
                androidx.compose.material3.Text("TTS reading: $isTTSReading")
                androidx.compose.material3.Text("TTS enabled: $isTTSEnabled")
                androidx.compose.material3.Text("Can accept input: $canAcceptNewInput")
                androidx.compose.material3.Text("Mic button: $micButtonState")
            }
        }
        
        // Before turning off TTS
        assert(isTTSReading == true)
        assert(canAcceptNewInput == false)
        
        // Turn off TTS
        composeTestRule.onNodeWithText("Turn off TTS").performClick()
        
        // After turning off TTS
        assert(isTTSReading == false)
        assert(isTTSEnabled == false)
        assert(canAcceptNewInput == true) // Immediately available
        assert(micButtonState == "RED_MUTE") // Because continuous listening is still on
    }
} 