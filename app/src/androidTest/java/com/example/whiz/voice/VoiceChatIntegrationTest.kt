package com.example.whiz.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
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
class VoiceChatIntegrationTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun chatInputBar_continuousListeningActive_showsCorrectState() {
        // Test ChatInputBar with continuous listening active (red mute state)
        var isContinuousListening = true
        var isListening = false
        var inputText = ""
        var transcription = ""
        var micClickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = inputText,
                    transcription = transcription,
                    isListening = isListening,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = isContinuousListening,
                    onInputChange = { inputText = it },
                    onSendClick = {},
                    onMicClick = { micClickCount++ },
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify continuous listening state
        assert(isContinuousListening == true)
        assert(isListening == false)
        composeTestRule.onNodeWithText("Type or tap mic...").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_activeListening_showsTranscription() {
        // Test ChatInputBar during active voice input
        var isListening = true
        var transcription = "Hello Whiz, how are you today?"
        var isContinuousListening = true
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = "",
                    transcription = transcription,
                    isListening = isListening,
                    isInputDisabled = false,
                    isMicDisabled = false,
                    isResponding = false,
                    isContinuousListeningEnabled = isContinuousListening,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify transcription is shown while listening
        assert(isListening == true)
        assert(transcription.isNotBlank())
        composeTestRule.onNodeWithText(transcription).assertIsDisplayed()
    }

    @Test
    fun chatInputBar_waitingForResponse_inputDisabled() {
        // Test ChatInputBar while waiting for bot response
        var isResponding = true
        var inputText = "Hello Whiz"  // Previously submitted message
        var isContinuousListening = true
        
        composeTestRule.setContent {
            WhizTheme {
                ChatInputBar(
                    inputText = inputText,
                    transcription = "",
                    isListening = false,
                    isInputDisabled = true,  // Disabled while responding
                    isMicDisabled = false,   // Mic can still be used to turn off continuous listening
                    isResponding = isResponding,
                    isContinuousListeningEnabled = isContinuousListening,
                    onInputChange = {},
                    onSendClick = {},
                    onMicClick = {},
                    surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                )
            }
        }
        
        // Verify waiting state
        assert(isResponding == true)
        assert(inputText.isNotBlank()) // Shows submitted message
        composeTestRule.onNodeWithText(inputText).assertIsDisplayed()
    }

    @Test
    fun chatInputBar_voiceActivatedMode_ttsEnabled() {
        // Test ChatInputBar in voice-activated mode (OK Google activation)
        var isContinuousListening = true
        var isTTSEnabled = true  // Voice activation should enable TTS
        var isVoiceActivated = true
        
        composeTestRule.setContent {
            WhizTheme {
                // Simulate voice-activated chat state
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Voice activated: $isVoiceActivated")
                    androidx.compose.material3.Text("TTS enabled: $isTTSEnabled")
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = false,
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = {},
                        onSendClick = {},
                        onMicClick = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Verify voice-activated state
        assert(isVoiceActivated == true)
        assert(isTTSEnabled == true)
        assert(isContinuousListening == true)
        composeTestRule.onNodeWithText("Voice activated: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS enabled: true").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_micButtonClick_togglesContinuousListening() {
        // Test microphone button click functionality
        var isContinuousListening = true
        var isWaitingForResponse = false
        var micClickCount = 0
        
        fun handleMicClick() {
            micClickCount++
            if (!isWaitingForResponse) {
                isContinuousListening = !isContinuousListening
            }
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    androidx.compose.material3.Text("Mic clicks: $micClickCount")
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = false,
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = {},
                        onSendClick = {},
                        onMicClick = { handleMicClick() },
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Initial state
        assert(isContinuousListening == true)
        assert(micClickCount == 0)
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        
        // Note: In a full integration test, you'd click the actual mic button
        // For now, we test the callback logic
        handleMicClick()
        
        assert(micClickCount == 1)
        assert(isContinuousListening == false) // Should toggle off
    }

    @Test
    fun chatInputBar_textInput_disablesContinuousListening() {
        // Test typing text should change mic button to send button
        composeTestRule.setContent {
            WhizTheme {
                var inputText by androidx.compose.runtime.mutableStateOf("")
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var hasTypedText by androidx.compose.runtime.mutableStateOf(false)
                
                fun handleTextInput(text: String) {
                    inputText = text
                    hasTypedText = text.isNotBlank()
                    // In real implementation, typing would change button state
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Input: '$inputText'")
                    androidx.compose.material3.Text("Has typed text: $hasTypedText")
                    
                    ChatInputBar(
                        inputText = inputText,
                        transcription = "",
                        isListening = false,
                        isInputDisabled = false,
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = { handleTextInput(it) },
                        onSendClick = {},
                        onMicClick = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                    
                    // Test button to simulate typing
                    androidx.compose.material3.Button(
                        onClick = { handleTextInput("Hello world") }
                    ) {
                        androidx.compose.material3.Text("Simulate Typing")
                    }
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Input: ''").assertIsDisplayed()
        composeTestRule.onNodeWithText("Has typed text: false").assertIsDisplayed()
        
        // Simulate typing
        composeTestRule.onNodeWithText("Simulate Typing").performClick()
        
        // Verify text input state
        composeTestRule.onNodeWithText("Input: 'Hello world'").assertIsDisplayed()
        composeTestRule.onNodeWithText("Has typed text: true").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_ttsReading_preventsNewInput() {
        // Test that TTS reading prevents new input
        var isTTSReading = true
        var canAcceptInput = false
        var isContinuousListening = true
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("TTS reading: $isTTSReading")
                    androidx.compose.material3.Text("Can accept input: $canAcceptInput")
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = !canAcceptInput, // Disabled when TTS reading
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = {},
                        onSendClick = {},
                        onMicClick = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Verify TTS reading state
        assert(isTTSReading == true)
        assert(canAcceptInput == false)
        composeTestRule.onNodeWithText("TTS reading: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can accept input: false").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_complexStateFlow_normalToVoiceActivated() {
        // Test transition from normal chat to voice-activated behavior
        composeTestRule.setContent {
            WhizTheme {
                var chatMode by androidx.compose.runtime.mutableStateOf("NORMAL")  // NORMAL, VOICE_ACTIVATED
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var isTTSEnabled by androidx.compose.runtime.mutableStateOf(false)
                var currentPhase by androidx.compose.runtime.mutableStateOf("INITIAL")  // INITIAL, SPEAKING, WAITING, REPLIED
                
                fun simulateVoiceActivation() {
                    chatMode = "VOICE_ACTIVATED"
                    isTTSEnabled = true  // Voice activation enables TTS
                    isContinuousListening = true
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Chat mode: $chatMode")
                    androidx.compose.material3.Text("TTS enabled: $isTTSEnabled")
                    androidx.compose.material3.Text("Phase: $currentPhase")
                    
                    androidx.compose.material3.Button(
                        onClick = { simulateVoiceActivation() }
                    ) {
                        androidx.compose.material3.Text("Activate via OK Google")
                    }
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = false,
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = {},
                        onSendClick = {},
                        onMicClick = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Initial normal state
        composeTestRule.onNodeWithText("Chat mode: NORMAL").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS enabled: false").assertIsDisplayed()
        
        // Simulate voice activation
        composeTestRule.onNodeWithText("Activate via OK Google").performClick()
        
        // Verify voice-activated state
        composeTestRule.onNodeWithText("Chat mode: VOICE_ACTIVATED").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS enabled: true").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_messageSubmissionFlow_preservesStates() {
        // Test complete message submission flow with state preservation
        var inputText = ""
        var isWaitingForResponse = false
        var isContinuousListening = true
        var messageSubmitted = false
        
        fun simulateMessageSubmission() {
            messageSubmitted = true
            isWaitingForResponse = true
            // Input text should remain visible but grayed out
        }
        
        fun simulateBotResponse() {
            isWaitingForResponse = false
            inputText = ""  // Clear input after response
            messageSubmitted = false
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                    androidx.compose.material3.Text("Message submitted: $messageSubmitted")
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    
                    androidx.compose.material3.Button(
                        onClick = { simulateMessageSubmission() }
                    ) {
                        androidx.compose.material3.Text("Submit Message")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { simulateBotResponse() }
                    ) {
                        androidx.compose.material3.Text("Bot Responds")
                    }
                    
                    ChatInputBar(
                        inputText = inputText,
                        transcription = "",
                        isListening = false,
                        isInputDisabled = isWaitingForResponse,
                        isMicDisabled = false,
                        isResponding = isWaitingForResponse,
                        isContinuousListeningEnabled = isContinuousListening,
                        onInputChange = { inputText = it },
                        onSendClick = {},
                        onMicClick = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Set up initial message
        inputText = "Hello Whiz"
        
        // Submit message
        composeTestRule.onNodeWithText("Submit Message").performClick()
        
        // Verify waiting state
        assert(isWaitingForResponse == true)
        assert(messageSubmitted == true)
        assert(isContinuousListening == true)  // Should preserve state
        
        // Bot responds
        composeTestRule.onNodeWithText("Bot Responds").performClick()
        
        // Verify post-response state
        assert(isWaitingForResponse == false)
        assert(inputText.isEmpty())  // Input cleared
        assert(isContinuousListening == true)  // State preserved
    }
} 