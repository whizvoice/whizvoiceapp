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
import androidx.compose.runtime.remember

@Composable
fun AssistantOverlayUi(
    viewModel: ChatViewModel = hiltViewModel(),
    onDismiss: () -> Unit // Callback to close the activity
) {
    val TAG = "AssistantOverlayUi" // Tag for logging

    // Collect necessary state from ViewModel
    val inputText by viewModel.inputText.collectAsState()
    val transcription by viewModel.transcriptionState.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState() // Agent thinking/fetching
    val isSpeaking by viewModel.isSpeaking.collectAsState() // TTS actively speaking
    val micPermissionGranted by viewModel.micPermissionGranted.collectAsState()

    // 1. Collect the state containing the full list of messages
    val messagesState by viewModel.messages.collectAsState()

    // 2. Derive the last assistant message from the current value of the state
    // Use remember so this calculation only runs when messagesState changes.
    val lastAssistantMessage = remember(messagesState) {
        messagesState.lastOrNull { it.type == MessageType.ASSISTANT }
    }

    // *** Restore LaunchedEffect to load chat and attempt auto-listening ***
    LaunchedEffect(Unit) {
        Log.d(TAG, "LaunchedEffect: Initializing Assistant State. Permission: $micPermissionGranted")
        // Ensure ViewModel loads a new chat state for the assistant session
        // Note: loadChat also resets many other states like inputText, isResponding etc.
        viewModel.loadChat(-1L)
        // Attempt to start listening if permission is granted
        /*if (micPermissionGranted) {
            Log.d(TAG, "Permission granted. Calling startListeningFromAssistant.")
            viewModel.startListeningFromAssistant()
        } else {
            Log.w(TAG, "Launched but no mic permission for auto-listening.")
            // Inform user? Or just wait for manual mic tap.
        }*/
    }

    // The main Box fills the screen but is transparent
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent) // Make the Box transparent
            // Apply padding horizontally, ensure no fixed bottom padding to allow imePadding to work
            .padding(start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomCenter // Align content (Column) to bottom
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(), // Column takes full width
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Display Last Assistant Response Conditionally ---
            AnimatedVisibility(
                // Show if message is not null
                visible = lastAssistantMessage != null,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                // Smart cast should work here because of visible check
                lastAssistantMessage?.let { message ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f) // Responsive width
                            .padding(bottom = 16.dp), // Space between response and input bar
                        shape = MaterialTheme.shapes.medium, // Rounded corners for response bubble
                        color = MaterialTheme.colorScheme.surfaceVariant, // Background color for response
                        tonalElevation = 4.dp // Add elevation
                    ) {
                        Text(
                            text = message.content,
                            modifier = Modifier.padding(16.dp), // Padding inside the bubble
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } // End AnimatedVisibility

            // --- Input Bar ---
            ChatInputBar(
                inputText = inputText,
                transcription = transcription,
                isListening = isListening,
                isInputDisabled = isResponding || isSpeaking, // Disable if thinking or speaking
                onInputChange = viewModel::updateInputText,
                onSendClick = { viewModel.sendUserInput(inputText) },
                onMicClick = {
                    // Check permission before allowing mic toggle from UI tap
                    if (micPermissionGranted) {
                        viewModel.toggleSpeechRecognition()
                    } else {
                        Log.w(TAG,"Mic Tapped but no permission.")
                        // Ideally, trigger the permission request flow here if possible
                        // This might involve calling back to the Activity.
                        // For now, it just won't work if permission is missing.
                    }
                },
                // Let the Surface within ChatInputBar handle its color/elevation
                // based on Material 3 guidelines (using tonalElevation)
                surfaceColor = Color.Unspecified
            )
        }
    }
}

// Remove the surfaceColorAtElevation helper if relying on Surface's tonalElevation
// @Composable
// fun MaterialTheme.surfaceColorAtElevation(elevation: Dp): Color { ... }