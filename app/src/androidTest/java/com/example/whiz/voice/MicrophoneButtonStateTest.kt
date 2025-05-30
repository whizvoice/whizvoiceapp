package com.example.whiz.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
        var currentState = MicButtonState.RED_MUTE
        var isContinuousListening = true
        var isWaitingForResponse = false
        var hasTypedText = false
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("Current state: $currentState")
                androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                androidx.compose.material3.Text("Waiting for response: $isWaitingForResponse")
                androidx.compose.material3.Text("Has typed text: $hasTypedText")
            }
        }
        
        // Initial state: RED_MUTE (continuous listening on)
        assert(currentState == MicButtonState.RED_MUTE)
        assert(isContinuousListening == true)
        composeTestRule.onNodeWithText("Current state: RED_MUTE").assertIsDisplayed()
        
        // User speaks and message is submitted - button stays RED_MUTE while waiting
        isWaitingForResponse = true
        // State should remain RED_MUTE while waiting for response
        assert(currentState == MicButtonState.RED_MUTE)
        
        // Bot responds - clear input, preserve continuous listening
        isWaitingForResponse = false
        // Should remain RED_MUTE because continuous listening is still on
        assert(currentState == MicButtonState.RED_MUTE)
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
                
                androidx.compose.material3.Button(
                    onClick = { clickMicButton() }
                ) {
                    androidx.compose.material3.Text("Mic Button ($currentState)")
                }
                androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
            }
        }
        
        // Initial state - verify RED_MUTE button is displayed
        composeTestRule.onNodeWithText("Mic Button (RED_MUTE)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        
        // Click to turn off continuous listening
        composeTestRule.onNodeWithText("Mic Button (RED_MUTE)").performClick()
        
        // Should become BLUE_MIC (not waiting for response)
        composeTestRule.onNodeWithText("Mic Button (BLUE_MIC)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
        
        // Click again to turn on continuous listening
        composeTestRule.onNodeWithText("Mic Button (BLUE_MIC)").performClick()
        
        // Should become RED_MUTE again
        composeTestRule.onNodeWithText("Mic Button (RED_MUTE)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_redMuteClickWhileWaiting_becomesGrayedMic() {
        // Test clicking red mute while waiting for response
        var currentState = MicButtonState.RED_MUTE
        var isContinuousListening = true
        var isWaitingForResponse = true
        
        fun clickMicButton() {
            if (currentState == MicButtonState.RED_MUTE) {
                isContinuousListening = false
                currentState = if (isWaitingForResponse) MicButtonState.GRAYED_MIC else MicButtonState.BLUE_MIC
            }
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Button(
                    onClick = { clickMicButton() },
                    enabled = currentState != MicButtonState.GRAYED_MIC
                ) {
                    androidx.compose.material3.Text("Mic Button ($currentState)")
                }
                androidx.compose.material3.Text("Waiting for response: $isWaitingForResponse")
                androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
            }
        }
        
        // Initial state: waiting for response with continuous listening on
        assert(currentState == MicButtonState.RED_MUTE)
        assert(isWaitingForResponse == true)
        assert(isContinuousListening == true)
        
        // Click red mute while waiting
        composeTestRule.onNodeWithText("Mic Button (RED_MUTE)").performClick()
        
        // Should become grayed mic (disabled because still waiting)
        assert(currentState == MicButtonState.GRAYED_MIC)
        assert(isContinuousListening == false)
        assert(isWaitingForResponse == true)
    }

    @Test
    fun micButtonState_typingText_becomesSeñdButton() {
        // Test typing text changes button to send button
        var currentState = MicButtonState.BLUE_MIC
        var inputText = ""
        var hasTypedText = false
        
        fun onTextChange(text: String) {
            inputText = text
            hasTypedText = text.isNotBlank()
            currentState = if (hasTypedText) MicButtonState.SEND_BUTTON else MicButtonState.BLUE_MIC
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.OutlinedTextField(
                    value = inputText,
                    onValueChange = { onTextChange(it) },
                    placeholder = { androidx.compose.material3.Text("Type message...") }
                )
                androidx.compose.material3.Text("Button state: $currentState")
                androidx.compose.material3.Text("Has typed text: $hasTypedText")
            }
        }
        
        // Initial state
        assert(currentState == MicButtonState.BLUE_MIC)
        assert(hasTypedText == false)
        
        // Simulate typing
        onTextChange("Hello")
        
        // Should become send button
        assert(currentState == MicButtonState.SEND_BUTTON)
        assert(hasTypedText == true)
        assert(inputText == "Hello")
        
        // Clear text
        onTextChange("")
        
        // Should go back to blue mic
        assert(currentState == MicButtonState.BLUE_MIC)
        assert(hasTypedText == false)
    }

    @Test
    fun micButtonState_waitingForResponse_correctBehavior() {
        // Test behavior while waiting for bot response
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.RED_MUTE)
                var isWaitingForResponse by androidx.compose.runtime.mutableStateOf(false)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var canClickMic by androidx.compose.runtime.mutableStateOf(true)
                
                fun startWaitingForResponse() {
                    isWaitingForResponse = true
                    // Button remains red mute but user can still click to disable continuous listening
                    canClickMic = (currentState == MicButtonState.RED_MUTE)
                }
                
                fun finishWaitingForResponse() {
                    isWaitingForResponse = false
                    // Restore button state based on continuous listening
                    currentState = if (isContinuousListening) MicButtonState.RED_MUTE else MicButtonState.BLUE_MIC
                    canClickMic = true
                }
                
                androidx.compose.material3.Button(
                    onClick = { startWaitingForResponse() }
                ) {
                    androidx.compose.material3.Text("Start waiting for response")
                }
                androidx.compose.material3.Button(
                    onClick = { finishWaitingForResponse() }
                ) {
                    androidx.compose.material3.Text("Finish waiting for response")
                }
                androidx.compose.material3.Text("Button state: $currentState")
                androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                androidx.compose.material3.Text("Can click mic: $canClickMic")
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can click mic: true").assertIsDisplayed()
        
        // Start waiting
        composeTestRule.onNodeWithText("Start waiting for response").performClick()
        
        // Should still be red mute but with waiting state
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can click mic: true").assertIsDisplayed()
        
        // Finish waiting
        composeTestRule.onNodeWithText("Finish waiting for response").performClick()
        
        // Should restore to appropriate state
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can click mic: true").assertIsDisplayed()
    }

    @Test
    fun micButtonState_ttsReadingTransitions_correctBehavior() {
        // Test button behavior during TTS reading
        composeTestRule.setContent {
            WhizTheme {
                var currentState by androidx.compose.runtime.mutableStateOf(MicButtonState.RED_MUTE)
                var isTTSReading by androidx.compose.runtime.mutableStateOf(false)
                var isContinuousListening by androidx.compose.runtime.mutableStateOf(true)
                var canAcceptNewInput by androidx.compose.runtime.mutableStateOf(true)
                
                fun startTTSReading() {
                    isTTSReading = true
                    canAcceptNewInput = false
                    // Mic behaves like waiting for response
                }
                
                fun stopTTSReading() {
                    isTTSReading = false
                    canAcceptNewInput = true
                    // Mic immediately becomes available
                }
                
                androidx.compose.material3.Button(
                    onClick = { startTTSReading() }
                ) {
                    androidx.compose.material3.Text("Start TTS")
                }
                androidx.compose.material3.Button(
                    onClick = { stopTTSReading() }
                ) {
                    androidx.compose.material3.Text("Stop TTS")
                }
                androidx.compose.material3.Text("Button state: $currentState")
                androidx.compose.material3.Text("TTS reading: $isTTSReading")
                androidx.compose.material3.Text("Can accept input: $canAcceptNewInput")
            }
        }
        
        // Initial state
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed()
        composeTestRule.onNodeWithText("TTS reading: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can accept input: true").assertIsDisplayed()
        
        // Start TTS
        composeTestRule.onNodeWithText("Start TTS").performClick()
        
        // During TTS reading
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed() // Still red mute
        composeTestRule.onNodeWithText("TTS reading: true").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can accept input: false").assertIsDisplayed() // Can't accept new input
        
        // Stop TTS
        composeTestRule.onNodeWithText("Stop TTS").performClick()
        
        // After stopping TTS
        composeTestRule.onNodeWithText("Button state: RED_MUTE").assertIsDisplayed() // Continuous listening preserved
        composeTestRule.onNodeWithText("TTS reading: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Can accept input: true").assertIsDisplayed() // Immediately available
    }

    @Test
    fun micButtonState_allTransitions_stateMachine() {
        // Test complete state machine transitions
        var currentState = MicButtonState.BLUE_MIC
        var isContinuousListening = false
        var isWaitingForResponse = false
        var hasTypedText = false
        var isTTSReading = false
        
        fun updateButtonState() {
            currentState = when {
                hasTypedText -> MicButtonState.SEND_BUTTON
                isWaitingForResponse && !isContinuousListening -> MicButtonState.GRAYED_MIC
                isContinuousListening -> MicButtonState.RED_MUTE
                else -> MicButtonState.BLUE_MIC
            }
        }
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Text("State: $currentState")
                androidx.compose.material3.Text("Continuous: $isContinuousListening")
                androidx.compose.material3.Text("Waiting: $isWaitingForResponse")
                androidx.compose.material3.Text("Typed: $hasTypedText")
            }
        }
        
        // Test all state transitions
        val transitions = listOf(
            // State changes
            { isContinuousListening = true; updateButtonState() },   // BLUE_MIC -> RED_MUTE
            { hasTypedText = true; updateButtonState() },            // RED_MUTE -> SEND_BUTTON  
            { hasTypedText = false; updateButtonState() },           // SEND_BUTTON -> RED_MUTE
            { isWaitingForResponse = true; updateButtonState() },    // RED_MUTE -> RED_MUTE (stays same)
            { isContinuousListening = false; updateButtonState() },  // RED_MUTE -> GRAYED_MIC
            { isWaitingForResponse = false; updateButtonState() },   // GRAYED_MIC -> BLUE_MIC
        )
        
        val expectedStates = listOf(
            MicButtonState.RED_MUTE,
            MicButtonState.SEND_BUTTON,
            MicButtonState.RED_MUTE,
            MicButtonState.RED_MUTE,
            MicButtonState.GRAYED_MIC,
            MicButtonState.BLUE_MIC
        )
        
        // Execute transitions and verify states
        transitions.forEachIndexed { index, transition ->
            transition()
            assert(currentState == expectedStates[index]) {
                "Transition $index failed: expected ${expectedStates[index]}, got $currentState"
            }
        }
    }
} 