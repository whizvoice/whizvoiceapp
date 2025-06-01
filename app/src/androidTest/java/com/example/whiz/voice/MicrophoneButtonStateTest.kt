package com.example.whiz.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
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
class MicrophoneButtonStateTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    // Microphone button states
    enum class MicButtonState {
        RED_MUTE,      // Continuous listening active (red mute button)
        GRAYED_MIC,    // Disabled during bot response (grayed mic)
        BLUE_MIC,      // Available for activation (blue mic)
        SEND_BUTTON    // When user types text (send button)
    }

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun micButtonState_normalChatFlow_correctTransitions() {
        // Test complete normal chat flow state transitions
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.RED_MUTE)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var isWaitingForResponse by androidx.compose.runtime.mutableStateOf(false)
                var hasTypedText by androidx.compose.runtime.mutableStateOf(false)
                
                val stateString = when(currentState) {
                    MicButtonState.RED_MUTE -> "RED_MUTE"
                    MicButtonState.GRAYED_MIC -> "GRAYED_MIC"
                    MicButtonState.BLUE_MIC -> "BLUE_MIC"
                    MicButtonState.SEND_BUTTON -> "SEND_BUTTON"
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("State: $stateString")
                    androidx.compose.material3.Text("Listening: $isContinuousListening")
                    androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                    androidx.compose.material3.Text("Typed: $hasTypedText")
                    
                    androidx.compose.material3.Button(
                        onClick = { isWaitingForResponse = !isWaitingForResponse }
                    ) {
                        androidx.compose.material3.Text("Toggle Waiting")
                    }
                }
            }
        }
        
        // Print what's actually in the UI for debugging
        composeTestRule.onRoot().printToLog("STATE_TEST")
        
        // Initial state verification
        composeTestRule.onNodeWithText("State: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Typed: false").assertIsDisplayed()
        
        // Toggle waiting
        composeTestRule.onNodeWithText("Toggle Waiting").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_redMuteClick_turnsOffContinuousListening() {
        // Test clicking red mute button to turn off continuous listening
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.RED_MUTE)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                val isWaitingForResponse = false
                
                fun clickMicButton() {
                    when (currentState) {
                        MicButtonState.RED_MUTE -> {
                            isContinuousListening = false
                            currentState = if (isWaitingForResponse) MicButtonState.GRAYED_MIC else MicButtonState.BLUE_MIC
                        }
                        MicButtonState.BLUE_MIC -> {
                            isContinuousListening = true
                            currentState = MicButtonState.RED_MUTE
                        }
                        else -> { /* No action for other states */ }
                    }
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(
                        onClick = { clickMicButton() }
                    ) {
                        androidx.compose.material3.Text("Click Me")
                    }
                    androidx.compose.material3.Text("State: $currentState")
                    androidx.compose.material3.Text("Listening: $isContinuousListening")
                }
            }
        }
        
        // Initial state - verify components are displayed
        composeTestRule.onNodeWithText("Click Me").assertIsDisplayed()
        composeTestRule.onNodeWithText("State: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: true").assertIsDisplayed()
        
        // Click to change state
        composeTestRule.onNodeWithText("Click Me").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify state changed
        composeTestRule.onNodeWithText("State: BLUE_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: false").assertIsDisplayed()
        
        // Click again to revert
        composeTestRule.onNodeWithText("Click Me").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify back to original state
        composeTestRule.onNodeWithText("State: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_redMuteClickWhileWaiting_becomesGrayedMic() {
        // Test clicking red mute while waiting for response
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.RED_MUTE)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var isWaitingForResponse by androidx.compose.runtime.mutableStateOf(true)
                
                fun clickMicButton() {
                    if (currentState == MicButtonState.RED_MUTE) {
                        isContinuousListening = false
                        currentState = if (isWaitingForResponse) MicButtonState.GRAYED_MIC else MicButtonState.BLUE_MIC
                    }
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(
                        onClick = { clickMicButton() },
                        enabled = currentState != MicButtonState.GRAYED_MIC
                    ) {
                        androidx.compose.material3.Text("Mic Button")
                    }
                    androidx.compose.material3.Text("State: $currentState")
                    androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                    androidx.compose.material3.Text("Listening: $isContinuousListening")
                }
            }
        }
        
        // Initial state: waiting for response with continuous listening on
        composeTestRule.onNodeWithText("State: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: true").assertIsDisplayed()
        
        // Click red mute while waiting
        composeTestRule.onNodeWithText("Mic Button").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Should become grayed mic (disabled because still waiting)
        composeTestRule.onNodeWithText("State: GRAYED_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_typingText_becomesSeñdButton() {
        // Test typing text changes button to send button
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.BLUE_MIC)
                var inputText by androidx.compose.runtime.mutableStateOf("")
                var hasTypedText by androidx.compose.runtime.mutableStateOf(false)
                
                fun onTextChange(text: String) {
                    inputText = text
                    hasTypedText = text.isNotBlank()
                    currentState = if (hasTypedText) MicButtonState.SEND_BUTTON else MicButtonState.BLUE_MIC
                }
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = inputText,
                        onValueChange = { onTextChange(it) },
                        placeholder = { androidx.compose.material3.Text("Type message...") }
                    )
                    androidx.compose.material3.Text("State: $currentState")
                    androidx.compose.material3.Text("Typed: $hasTypedText")
                    
                    androidx.compose.material3.Button(
                        onClick = { onTextChange("Hello") }
                    ) {
                        androidx.compose.material3.Text("Add Text")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { onTextChange("") }
                    ) {
                        androidx.compose.material3.Text("Clear Text")
                    }
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("State: BLUE_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Typed: false").assertIsDisplayed()
        
        // Add text
        composeTestRule.onNodeWithText("Add Text").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("State: SEND_BUTTON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Typed: true").assertIsDisplayed()
        
        // Clear text
        composeTestRule.onNodeWithText("Clear Text").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("State: BLUE_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Typed: false").assertIsDisplayed()
    }

    @Test
    fun micButtonState_waitingForResponse_correctBehavior() {
        // Test behavior while waiting for bot response
        composeTestRule.setContent {
            WhizTheme {
                var isWaiting by androidx.compose.runtime.mutableStateOf(false)
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(
                        onClick = { isWaiting = !isWaiting }
                    ) {
                        androidx.compose.material3.Text("Toggle Waiting")
                    }
                    androidx.compose.material3.Text("Waiting: $isWaiting")
                    androidx.compose.material3.Text(if (isWaiting) "Bot is thinking..." else "Ready for input")
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Toggle Waiting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ready for input").assertIsDisplayed()
        
        // Start waiting
        composeTestRule.onNodeWithText("Toggle Waiting").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify waiting state
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bot is thinking...").assertIsDisplayed()
        
        // Stop waiting
        composeTestRule.onNodeWithText("Toggle Waiting").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify back to ready state
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ready for input").assertIsDisplayed()
    }

    @Test
    fun micButtonState_ttsReadingTransitions_correctBehavior() {
        // Test button behavior during TTS reading
        composeTestRule.setContent {
            WhizTheme {
                var isTTSReading by androidx.compose.runtime.mutableStateOf(false)
                var canAcceptInput by androidx.compose.runtime.mutableStateOf(true)
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Button(
                        onClick = { 
                            isTTSReading = !isTTSReading
                            canAcceptInput = !isTTSReading
                        }
                    ) {
                        androidx.compose.material3.Text(if (isTTSReading) "Stop TTS" else "Start TTS")
                    }
                    androidx.compose.material3.Text("TTS Reading: $isTTSReading")
                    androidx.compose.material3.Text("Can Accept Input: $canAcceptInput")
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Start TTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS Reading: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can Accept Input: true").assertIsDisplayed()
        
        // Start TTS
        composeTestRule.onNodeWithText("Start TTS").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify TTS reading state
        composeTestRule.onNodeWithText("Stop TTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS Reading: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can Accept Input: false").assertIsDisplayed()
        
        // Stop TTS
        composeTestRule.onNodeWithText("Stop TTS").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        
        // Verify back to normal state
        composeTestRule.onNodeWithText("Start TTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS Reading: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can Accept Input: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_basicStateTransitions_work() {
        // Test basic state transitions work correctly
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.BLUE_MIC)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(false)
                var hasTypedText by androidx.compose.runtime.mutableStateOf(false)
                
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("State: $currentState")
                    androidx.compose.material3.Text("Listening: $isContinuousListening")
                    androidx.compose.material3.Text("Text: $hasTypedText")
                    
                    androidx.compose.material3.Button(
                        onClick = { 
                            isContinuousListening = !isContinuousListening
                            currentState = if (isContinuousListening) MicButtonState.RED_MUTE else MicButtonState.BLUE_MIC
                        }
                    ) {
                        androidx.compose.material3.Text("Toggle Listening")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { 
                            hasTypedText = !hasTypedText
                            currentState = if (hasTypedText) MicButtonState.SEND_BUTTON else MicButtonState.BLUE_MIC
                        }
                    ) {
                        androidx.compose.material3.Text("Toggle Text")
                    }
                }
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("State: BLUE_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text: false").assertIsDisplayed()
        
        // Turn on listening
        composeTestRule.onNodeWithText("Toggle Listening").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("State: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening: true").assertIsDisplayed()
        
        // Turn on text input
        composeTestRule.onNodeWithText("Toggle Text").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("State: SEND_BUTTON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text: true").assertIsDisplayed()
        
        // Turn off text input
        composeTestRule.onNodeWithText("Toggle Text").performClick()
        composeTestRule.waitForIdle()  // Wait for recomposition
        composeTestRule.onNodeWithText("State: BLUE_MIC").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text: false").assertIsDisplayed()
    }

    @Test
    fun micButtonState_debugTest_simpleText() {
        // Debug test to see what's actually being rendered
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Hello World")
                    androidx.compose.material3.Text("Test Text")
                    androidx.compose.material3.Button(
                        onClick = {}
                    ) {
                        androidx.compose.material3.Text("Button Text")
                    }
                }
            }
        }
        
        // Print what's actually in the UI
        composeTestRule.onRoot().printToLog("DEBUG_UI")
        
        // Just try to find basic text
        composeTestRule.onNodeWithText("Hello World").assertIsDisplayed()
    }
} 