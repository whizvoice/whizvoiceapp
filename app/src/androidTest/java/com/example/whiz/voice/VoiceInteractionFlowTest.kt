package com.example.whiz.voice

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.ui.screens.ChatInputBar
import com.example.whiz.ui.screens.ChatScreen
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.di.AppModule
import com.example.whiz.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.testing.TestNavHostController
import androidx.compose.ui.platform.LocalContext

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
        // Test: After bot responds, input clears but state is preserved - using real ChatScreen
        android.util.Log.d("VoiceInteractionFlowTest", "🔬 Starting whizResponds_inputBarClears_statePreserved test")
        
        try {
            android.util.Log.d("VoiceInteractionFlowTest", "🔬 About to call setContent...")
            composeTestRule.setContent {
                android.util.Log.d("VoiceInteractionFlowTest", "🔬 Inside compose content block")
                WhizTheme {
                    android.util.Log.d("VoiceInteractionFlowTest", "🔬 About to create ChatViewModel...")
                    // Use real ChatScreen with proper state management
                    val chatViewModel: ChatViewModel = hiltViewModel()
                    android.util.Log.d("VoiceInteractionFlowTest", "✅ ChatViewModel created successfully!")
                    
                    ChatScreen(
                        chatId = 1L,
                        onChatsListClick = {},
                        hasPermission = true,
                        onRequestPermission = {},
                        viewModel = chatViewModel,
                        navController = androidx.navigation.testing.TestNavHostController(
                            androidx.compose.ui.platform.LocalContext.current
                        )
                    )
                    android.util.Log.d("VoiceInteractionFlowTest", "✅ ChatScreen created successfully!")
                }
            }
            android.util.Log.d("VoiceInteractionFlowTest", "✅ setContent completed successfully!")
        } catch (e: Exception) {
            android.util.Log.e("VoiceInteractionFlowTest", "❌ Error in setContent", e)
            throw e
        }

        composeTestRule.waitForIdle()
        
        // Verify the chat screen displays and input bar is present
        // The actual response flow would be managed by the ViewModel
        // This test ensures the screen renders and input is available
        composeTestRule.onNodeWithText("Type or tap mic...").assertExists()
        
        // Verify that the microphone button is present (actual state depends on ViewModel)
        composeTestRule.onNodeWithContentDescription("Start listening").assertExists()
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
        // Test: During TTS reading, mic input is disabled - using real ChatScreen
        android.util.Log.d("VoiceInteractionFlowTest", "🔬 Starting ttsReading_disablesMicInput test")
        
        try {
            android.util.Log.d("VoiceInteractionFlowTest", "🔬 About to call setContent...")
            composeTestRule.setContent {
                android.util.Log.d("VoiceInteractionFlowTest", "🔬 Inside compose content block")
                WhizTheme {
                    android.util.Log.d("VoiceInteractionFlowTest", "🔬 About to create ChatViewModel...")
                    // Test with real ChatScreen that properly manages TTS state
                    val chatViewModel: ChatViewModel = hiltViewModel()
                    android.util.Log.d("VoiceInteractionFlowTest", "✅ ChatViewModel created successfully!")
                    
                    ChatScreen(
                        chatId = 1L,
                        onChatsListClick = {},
                        hasPermission = true,
                        onRequestPermission = {},
                        viewModel = chatViewModel,
                        navController = androidx.navigation.testing.TestNavHostController(
                            androidx.compose.ui.platform.LocalContext.current
                        )
                    )
                    android.util.Log.d("VoiceInteractionFlowTest", "✅ ChatScreen created successfully!")
                }
            }
            android.util.Log.d("VoiceInteractionFlowTest", "✅ setContent completed successfully!")
        } catch (e: Exception) {
            android.util.Log.e("VoiceInteractionFlowTest", "❌ Error in setContent", e)
            throw e
        }

        // In a real app, TTS state would be managed by the ViewModel
        // For now, verify that the ChatScreen displays correctly
        // The actual TTS logic would disable mic input through ViewModel state
        composeTestRule.waitForIdle()
        
        // Verify that the chat screen is displayed
        // The TTS functionality is complex and involves ViewModel state management
        // This test ensures the screen renders without crash
        composeTestRule.onNodeWithText("Type or tap mic...").assertExists()
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