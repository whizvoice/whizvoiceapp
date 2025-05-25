package com.example.whiz.ui.screens

// Add necessary animation imports
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
// Remove this if using Surface tonalElevation directly
// import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.data.local.MessageType // Import MessageType
import android.util.Log // Import Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whiz.permissions.PermissionHandler
import kotlinx.coroutines.delay

@Composable
fun AssistantOverlayUi(
    viewModel: ChatViewModel = hiltViewModel(), // Use hiltViewModel for proper DI
    onDismiss: () -> Unit // Callback to close the activity
) {
    val TAG = "AssistantOverlayUi" // Tag for logging
    val context = LocalContext.current

    // Collect necessary state from ViewModel
    val inputText by viewModel.inputText.collectAsState()
    val transcription by viewModel.transcriptionState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState() // Agent thinking/fetching
    val isSpeaking by viewModel.isSpeaking.collectAsState() // TTS actively speaking
    val micPermissionGranted by viewModel.micPermissionGranted.collectAsState()
    val isContinuousListeningEnabled by viewModel.isContinuousListeningEnabled.collectAsState() // Track continuous listening mode

    // 1. Collect the state containing the full list of messages
    val messagesState by viewModel.messages.collectAsState()

    // Check microphone permission
    val hasPermission = remember {
        PermissionHandler.hasMicrophonePermission(context)
    }

    // *** Step 1: Comment out the derived state ***
    /*
    // 2. Derive the last assistant message from the current value of the state
    // Use remember so this calculation only runs when messagesState changes.
    val lastAssistantMessage = remember(messagesState) {
        messagesState.lastOrNull { it.type == MessageType.ASSISTANT }
    }
    */

    // *** Restore LaunchedEffect to load chat and attempt auto-listening ***
    LaunchedEffect(Unit) {
        Log.d(TAG, "LaunchedEffect: Initializing Assistant State. Permission: $micPermissionGranted, hasPermission: $hasPermission")
        // Set permission state first
        if (hasPermission) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
        // Small delay to ensure permission state is set
        delay(100)
        // Load new chat (this will reset isResponding state for fresh chat)
        viewModel.loadChat(-1L)
        
        // Additional delay to ensure chat is loaded and responding state is reset
        delay(200)
        Log.d(TAG, "LaunchedEffect: Chat loaded. Responding: $isResponding, Speaking: $isSpeaking, Listening: $isListening")
    }

    // The main Box fills the screen but is transparent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // Make the Box transparent
            .padding(start = 16.dp, end = 16.dp)
            .imePadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter // Align content (Column) to bottom
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(), // Column takes full width
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // *** Step 3: Add Logging ***
            val lastMessage = messagesState.lastOrNull() // Get the actual last message
            Log.d("AssistantOverlayUi", "Recomposing: lastMessage content = ${lastMessage?.content}")

            // *** Step 4: Add Simplified Display ***
            if (lastMessage != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f) // Responsive width
                        .padding(bottom = 16.dp), // Space between response and input bar
                    // Use simple shapes/colors for debugging
                    shape = RoundedCornerShape(8.dp),
                    color = if (lastMessage.type == MessageType.ASSISTANT) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Text(
                        // Display type and content clearly
                        text = "DBG Last (${lastMessage.type}): ${lastMessage.content}",
                        modifier = Modifier.padding(16.dp),
                        color = if (lastMessage.type == MessageType.ASSISTANT) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium // Use a standard style
                    )
                }
            }
            // *** End of Simplified Display ***


            // --- Input Bar ---
            val isTextInputDisabled = isResponding || isSpeaking
            // Disable mic when responding/speaking, but allow turning OFF if currently listening
            val isMicDisabled = (isResponding || isSpeaking) && !isListening
            ChatInputBar(
                inputText = inputText,
                transcription = transcription,
                isListening = isListening,
                isInputDisabled = isTextInputDisabled,
                isMicDisabled = isMicDisabled,
                isResponding = isResponding,
                isContinuousListeningEnabled = isContinuousListeningEnabled,
                onInputChange = viewModel::updateInputText,
                onSendClick = { viewModel.sendUserInput(inputText) },
                onMicClick = {
                    if (micPermissionGranted) {
                        viewModel.toggleSpeechRecognition()
                    } else {
                        Log.w(TAG,"Mic Tapped but no permission.")
                    }
                },
                // Pass explicit color and shape as decided before
                surfaceColor = MaterialTheme.colorScheme.surface, // Use the non-transparent color
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) // Use the desired overlay shape
            )
        }
    }
}