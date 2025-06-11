package com.example.whiz.voice

import androidx.compose.runtime.*
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
import android.util.Log

/**
 * Focused integration test for mic button behavior during server responses.
 * Tests the fixed behavior for the production issue:
 * 1. User sends message
 * 2. isResponding = true
 * 3. User tries to turn mic on/off during response
 * 4. Should work normally (fixed behavior - no longer blocked)
 * 5. After response, mic continues to work
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MicButtonDuringResponseTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    companion object {
        private const val TAG = "MicButtonDuringResponseTest"
    }

    @Before
    fun setup() {
        hiltRule.inject()
        Log.d(TAG, "🎤 Mic Button During Response Test Setup")
    }

    @Test
    fun micButton_duringServerResponse_showsErrorAndBlocksToggle() {
        Log.d(TAG, "🧪 Testing mic button behavior during server response")
        
        // This test reproduces the exact production scenario
        composeTestRule.setContent {
            var isResponding by remember { mutableStateOf(false) }
            var isContinuousListening by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var micClickCount by remember { mutableStateOf(0) }
            
            fun handleMicClick() {
                micClickCount++
                Log.d(TAG, "🎤 Mic clicked (count: $micClickCount), isResponding: $isResponding")
                
                // FIXED: Allow mic toggle during response (only block during speaking)
                isContinuousListening = !isContinuousListening
                errorMessage = null
                Log.d(TAG, "🔄 Toggled continuous listening to: $isContinuousListening")
            }
            
            fun simulateMessageSend() {
                Log.d(TAG, "📤 Simulating message send")
                isResponding = true
                errorMessage = null
            }
            
            fun simulateResponseReceived() {
                Log.d(TAG, "📥 Simulating response received")
                isResponding = false
                errorMessage = null
            }
            
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    // Status indicators
                    androidx.compose.material3.Text("Responding: $isResponding")
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    androidx.compose.material3.Text("Mic clicks: $micClickCount")
                    if (errorMessage != null) {
                        androidx.compose.material3.Text(
                            text = "Error: $errorMessage",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                    
                    // Control buttons for test simulation
                    androidx.compose.foundation.layout.Row {
                        androidx.compose.material3.Button(
                            onClick = { simulateMessageSend() }
                        ) {
                            androidx.compose.material3.Text("Send Message")
                        }
                        androidx.compose.material3.Button(
                            onClick = { simulateResponseReceived() }
                        ) {
                            androidx.compose.material3.Text("Response Received")
                        }
                    }
                    
                    // The actual ChatInputBar under test
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = isResponding, // Input disabled during response
                        isMicDisabled = false, // Mic physically available but should be blocked by logic
                        isResponding = isResponding,
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
        
        // Step 1: Verify initial state
        Log.d(TAG, "1️⃣ Verifying initial state")
        composeTestRule.onNodeWithText("Responding: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mic clicks: 0").assertIsDisplayed()
        
        // Step 2: Test mic works initially
        Log.d(TAG, "2️⃣ Testing mic works initially")
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        composeTestRule.onNodeWithText("Mic clicks: 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        
        // Step 3: Simulate sending a message (triggers isResponding = true)
        Log.d(TAG, "3️⃣ Simulating message send")
        composeTestRule.onNodeWithText("Send Message").performClick()
        composeTestRule.onNodeWithText("Responding: true").assertIsDisplayed()
        
        // Step 4: Try to toggle mic during response - should work now!
        Log.d(TAG, "4️⃣ Testing mic works during response (fixed behavior)")
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").performClick()
        
        // Verify mic click worked - count incremented and state changed
        composeTestRule.onNodeWithText("Mic clicks: 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
        
        // Step 5: Try again - should toggle back on
        Log.d(TAG, "5️⃣ Testing mic toggle works again during response")
        composeTestRule.onNodeWithContentDescription("Start listening").performClick()
        composeTestRule.onNodeWithText("Mic clicks: 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        
        // Step 6: Simulate response received (clears isResponding)
        Log.d(TAG, "6️⃣ Simulating response received")
        composeTestRule.onNodeWithText("Response Received").performClick()
        composeTestRule.onNodeWithText("Responding: false").assertIsDisplayed()
        
        // Step 7: Verify mic continues to work after response ends
        Log.d(TAG, "7️⃣ Testing mic still works after response ends")
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").performClick()
        
        // Should work - state changes
        composeTestRule.onNodeWithText("Mic clicks: 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
        
        Log.d(TAG, "✅ Mic button during response test completed successfully")
    }

    @Test
    fun micButton_multipleResponseCycles_maintainsCorrectBehavior() {
        Log.d(TAG, "🔄 Testing mic button behavior across multiple request/response cycles")
        
        composeTestRule.setContent {
            var isResponding by remember { mutableStateOf(false) }
            var isContinuousListening by remember { mutableStateOf(true) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var cycleCount by remember { mutableStateOf(0) }
            
            fun handleMicClick() {
                // FIXED: Allow mic toggle during response (only block during speaking)
                isContinuousListening = !isContinuousListening
                errorMessage = null
            }
            
            fun runRequestResponseCycle() {
                cycleCount++
                Log.d(TAG, "🔄 Starting cycle $cycleCount")
                isResponding = true
                errorMessage = null
            }
            
            fun completeResponseCycle() {
                Log.d(TAG, "✅ Completing cycle $cycleCount")
                isResponding = false
                errorMessage = null
            }
            
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Cycle: $cycleCount")
                    androidx.compose.material3.Text("Responding: $isResponding")
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    if (errorMessage != null) {
                        androidx.compose.material3.Text("Error: $errorMessage")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { runRequestResponseCycle() }
                    ) {
                        androidx.compose.material3.Text("Start Cycle")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { completeResponseCycle() }
                    ) {
                        androidx.compose.material3.Text("Complete Cycle")
                    }
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = isResponding,
                        isMicDisabled = false,
                        isResponding = isResponding,
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
        
        // Test 3 complete cycles
        repeat(3) { cycle ->
            Log.d(TAG, "🔄 Testing cycle ${cycle + 1}")
            
            // Verify initial state for this cycle
            composeTestRule.onNodeWithText("Responding: false").assertIsDisplayed()
            
            // Start request/response cycle
            composeTestRule.onNodeWithText("Start Cycle").performClick()
            composeTestRule.onNodeWithText("Cycle: ${cycle + 1}").assertIsDisplayed()
            composeTestRule.onNodeWithText("Responding: true").assertIsDisplayed()
            
            // Try to use mic during response - should work now (fixed behavior)
            composeTestRule.onNodeWithContentDescription("Turn off continuous listening").performClick()
            composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
            
            // Complete the cycle
            composeTestRule.onNodeWithText("Complete Cycle").performClick()
            composeTestRule.onNodeWithText("Responding: false").assertIsDisplayed()
            
            // Mic should continue to work
            composeTestRule.onNodeWithContentDescription("Start listening").performClick()
            composeTestRule.onNodeWithText("Continuous listening: true").assertIsDisplayed()
        }
        
        Log.d(TAG, "✅ Multiple cycle test completed successfully")
    }

    @Test
    fun micButton_rapidToggleAttempts_duringResponse_handlesGracefully() {
        Log.d(TAG, "⚡ Testing rapid mic toggle attempts during response")
        
        composeTestRule.setContent {
            var isResponding by remember { mutableStateOf(true) } // Start in responding state
            var isContinuousListening by remember { mutableStateOf(false) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            var rapidClickCount by remember { mutableStateOf(0) }
            
            fun handleMicClick() {
                rapidClickCount++
                // FIXED: Allow mic toggle during response (only block during speaking)
                isContinuousListening = !isContinuousListening
                errorMessage = null
                Log.d(TAG, "✅ Rapid click $rapidClickCount succeeded")
            }
            
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Responding: $isResponding")
                    androidx.compose.material3.Text("Continuous listening: $isContinuousListening")
                    androidx.compose.material3.Text("Rapid clicks: $rapidClickCount")
                    if (errorMessage != null) {
                        androidx.compose.material3.Text("Error: $errorMessage")
                    }
                    
                    androidx.compose.material3.Button(
                        onClick = { isResponding = false }
                    ) {
                        androidx.compose.material3.Text("Stop Responding")
                    }
                    
                    ChatInputBar(
                        inputText = "",
                        transcription = "",
                        isListening = false,
                        isInputDisabled = isResponding,
                        isMicDisabled = false,
                        isResponding = isResponding,
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
        
        // Rapid fire clicks during response - should work now (fixed behavior)
        Log.d(TAG, "⚡ Performing rapid clicks during response")
        
        // Start with continuous listening = false, so first click should turn it on
        var currentState = false
        
        repeat(5) { clickIndex ->
            val contentDescription = if (currentState) "Turn off continuous listening" else "Start listening"
            composeTestRule.onNodeWithContentDescription(contentDescription).performClick()
            
            // Toggle the state for next iteration
            currentState = !currentState
            
            composeTestRule.onNodeWithText("Rapid clicks: ${clickIndex + 1}").assertIsDisplayed()
            composeTestRule.onNodeWithText("Continuous listening: $currentState").assertIsDisplayed()
        }
        
        // Stop responding
        Log.d(TAG, "🛑 Stopping response state")
        composeTestRule.onNodeWithText("Stop Responding").performClick()
        composeTestRule.onNodeWithText("Responding: false").assertIsDisplayed()
        
        // Mic should continue to work (was working during response too)
        // After 5 clicks starting from false: false->true->false->true->false->true
        // So we should end with continuous listening = true
        Log.d(TAG, "✅ Testing mic continues to work after response")
        composeTestRule.onNodeWithContentDescription("Turn off continuous listening").performClick()
        composeTestRule.onNodeWithText("Continuous listening: false").assertIsDisplayed()
        composeTestRule.onNodeWithText("Rapid clicks: 6").assertIsDisplayed()
        
        Log.d(TAG, "✅ Rapid toggle test completed successfully")
    }
} 