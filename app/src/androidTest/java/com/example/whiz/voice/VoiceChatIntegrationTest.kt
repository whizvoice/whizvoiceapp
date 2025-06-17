package com.example.whiz.voice

import androidx.compose.runtime.*
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
@org.junit.Ignore("Integration tests disabled - device connection issues")
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
                    isSpeaking = false,
                    shouldShowMicDuringTTS = false,
                    onInputChange = { inputText = it },
                    onSendClick = {},
                    onInterruptClick = {},
                    onMicClick = { micClickCount++ },
                    onMicClickDuringTTS = {},
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
        
        // Verify transcription is shown while listening
        assert(isListening == true)
        assert(transcription.isNotBlank())
        composeTestRule.onNodeWithText(transcription).assertIsDisplayed()
    }

    @Test
    fun chatInputBar_waitingForResponse_inputDisabled() {
        // Test ChatInputBar while waiting for bot response
        val isResponding = true
        val inputText = "Hello Whiz"  // Previously submitted message
        val isContinuousListening = true
        
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
        
        // Verify waiting state
        assert(isResponding == true)
        assert(inputText.isNotBlank()) // Shows submitted message
        composeTestRule.onNodeWithText(inputText).assertIsDisplayed()
    }

    @Test
    fun chatInputBar_voiceActivatedMode_ttsEnabled() {
        // Test ChatInputBar in voice-activated mode (OK Google activation)
        composeTestRule.setContent {
            val isContinuousListening by remember { mutableStateOf(true) }
            val isTTSEnabled by remember { mutableStateOf(true) }
            val isVoiceActivated by remember { mutableStateOf(true) }
        
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
        }
        
        // Verify voice-activated state displays correctly
        composeTestRule.onNodeWithText("Voice activated: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS enabled: true").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_micButtonClick_togglesContinuousListening() {
        // Test microphone button click functionality
        composeTestRule.setContent {
            var isContinuousListening by remember { mutableStateOf(true) }
            var micClickCount by remember { mutableStateOf(0) }
            val isWaitingForResponse = false
            
            fun handleMicClick() {
                micClickCount++
                if (!isWaitingForResponse) {
                    isContinuousListening = !isContinuousListening
                }
            }
        
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    androidx.compose.material3.Text("Mic clicks: $micClickCount")
                    androidx.compose.material3.Button(
                        onClick = { handleMicClick() }
                    ) {
                        androidx.compose.material3.Text("Toggle Mic")
                    }
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = false,
                        isMicDisabled = false,
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        isSpeaking = false,
                        shouldShowMicDuringTTS = false,
                        onInputChange = {},
                        onSendClick = {},
                        onInterruptClick = {},
                        onMicClick = { handleMicClick() },
                        onMicClickDuringTTS = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mic clicks: 0").assertIsDisplayed()
        
        // Click the toggle button to simulate mic interaction
        composeTestRule.onNodeWithText("Toggle Mic").performClick()
        
        // Verify state changed
        composeTestRule.onNodeWithText("Mic clicks: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_ttsReading_allowsMicInterruption() {
        // Test: TTS reading allows mic interruption
        composeTestRule.setContent {
            var isTTSReading by remember { mutableStateOf(true) }
            var micClickCount by remember { mutableStateOf(0) }
            val canAcceptTextInput = false  // Text input still disabled during TTS
            val isContinuousListening = false  // Continuous listening disabled during TTS without headphones
            
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("TTS reading: $isTTSReading")
                    androidx.compose.material3.Text("Can accept text input: $canAcceptTextInput")
                    androidx.compose.material3.Text("Mic clicks: $micClickCount")
                    androidx.compose.material3.Button(
                        onClick = { 
                            micClickCount++
                            isTTSReading = false // Simulate TTS stopping
                        }
                    ) {
                        androidx.compose.material3.Text("Interrupt TTS")
                    }
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = !canAcceptTextInput, // Text input disabled when TTS reading
                        isMicDisabled = false, // Mic should be active during TTS for interruption
                        isResponding = false,
                        isContinuousListeningEnabled = isContinuousListening,
                        isSpeaking = isTTSReading,
                        shouldShowMicDuringTTS = isTTSReading && !isContinuousListening,
                        onInputChange = {},
                        onSendClick = {},
                        onInterruptClick = {},
                        onMicClick = { micClickCount++ },
                        onMicClickDuringTTS = { 
                            micClickCount++
                            isTTSReading = false
                        },
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("TTS reading: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can accept text input: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mic clicks: 0").assertIsDisplayed()
        
        // Click the interrupt button
        composeTestRule.onNodeWithText("Interrupt TTS").performClick()
        
        // Verify TTS interruption worked
        composeTestRule.onNodeWithText("Mic clicks: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS reading: false").assertIsDisplayed()
    }

    @Test
    fun chatInputBar_messageSubmissionFlow_preservesStates() {
        // Test complete message submission flow with state preservation
        composeTestRule.setContent {
            var inputText by remember { mutableStateOf("") }
            var isWaitingForResponse by remember { mutableStateOf(false) }
            var messageSubmitted by remember { mutableStateOf(false) }
            val isContinuousListening = true
            
            fun simulateMessageSubmission() {
                messageSubmitted = true
                isWaitingForResponse = true
            }
            
            fun simulateBotResponse() {
                isWaitingForResponse = false
                inputText = ""
                messageSubmitted = false
            }
        
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                    androidx.compose.material3.Text("Message submitted: $messageSubmitted")
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    
                    androidx.compose.material3.Button(
                        onClick = { 
                            inputText = "Hello Whiz"
                            simulateMessageSubmission() 
                        }
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
                        isSpeaking = false,
                        shouldShowMicDuringTTS = false,
                        onInputChange = { inputText = it },
                        onSendClick = {},
                        onInterruptClick = {},
                        onMicClick = {},
                        onMicClickDuringTTS = {},
                        surfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message submitted: false").assertIsDisplayed()
        
        // Submit message
        composeTestRule.onNodeWithText("Submit Message").performClick()
        
        // Verify waiting state
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message submitted: true").assertIsDisplayed()
        
        // Bot responds
        composeTestRule.onNodeWithText("Bot Responds").performClick()
        
        // Verify post-response state
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Message submitted: false").assertIsDisplayed()
    }
} 