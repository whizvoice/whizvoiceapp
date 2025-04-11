package com.example.wiz.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.wiz.data.local.DateFormatter
import com.example.wiz.data.local.MessageEntity
import com.example.wiz.data.local.MessageType
import com.example.wiz.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.delay

import androidx.compose.runtime.State
import com.google.accompanist.systemuicontroller.rememberSystemUiController

// States for transition animation
enum class TransitionState {
    Entering, Entered, Exiting, Exited
}

// Helper to track transition states
@Composable
fun rememberTransitionState(): State<TransitionState> {
    val state = remember { mutableStateOf(TransitionState.Entering) }

    LaunchedEffect(Unit) {
        // Simulate transition completion after a delay
        delay(300) // Matches our animation duration
        state.value = TransitionState.Entered
    }

    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: Long,
    onBackClick: () -> Unit,
    onChatsListClick: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val transcription by viewModel.transcriptionState.collectAsState()
    val chatTitle by viewModel.chatTitle.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState()
    val speechError by viewModel.speechError.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Define the color for the input area with elevation
    val inputSurfaceColor = MaterialTheme.colorScheme.surface.copy(
        alpha = 0.9f
    ).compositeOver(MaterialTheme.colorScheme.surfaceTint.copy(
        alpha = 0.1f
    ))

    // Simple animation visibility state - no need for complex transition state
    var isContentVisible by remember { mutableStateOf(false) }

    // Delay loading and showing content after transition animation
    LaunchedEffect(Unit) {
        // Wait for entry animation to complete
        delay(300)

        // Load chat data
        viewModel.loadChat(chatId)

        // Show content with slight delay for smoothness
        delay(50)
        isContentVisible = true
    }

    // Scroll to bottom when messages change
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Show speech recognition errors
    LaunchedEffect(speechError) {
        speechError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(chatTitle) },
                navigationIcon = {
                    IconButton(onClick = onChatsListClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open Chats List"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatInputBar(
                inputText = inputText,
                transcription = transcription,
                isListening = isListening,
                isResponding = isResponding,
                onInputChange = viewModel::updateInputText,
                onSendClick = { viewModel.sendUserInput() },
                onMicClick = viewModel::toggleSpeechRecognition,
                surfaceColor = inputSurfaceColor
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Fade in content after animation completes
            AnimatedVisibility(
                visible = isContentVisible || messages.isEmpty(),
                enter = fadeIn(animationSpec = tween(300))
            ) {
                if (messages.isEmpty()) {
                    EmptyChatPlaceholder()
                } else {
                    MessagesList(
                        messages = messages,
                        listState = listState
                    )
                }
            }

            // Show typing indicator when assistant is responding
            AnimatedVisibility(
                visible = isResponding,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                TypingIndicator()
            }
        }
    }
}

@Composable
fun MessagesList(
    messages: List<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    // Debug print for troubleshooting
    if (messages.isEmpty()) {
        println("Debug: MessagesList received empty messages list")
    } else {
        println("Debug: MessagesList received ${messages.size} messages")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages) { message ->
            // Debug print for each message
            println("Debug: Displaying message: ${message.content} (${message.type})")
            MessageItem(message = message)
        }
    }
}

@Composable
fun MessageItem(message: MessageEntity) {
    val isUserMessage = message.type == MessageType.USER

    // More distinctive colors for the bubbles
    val backgroundColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.primary // Use actual primary color for user
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
    }

    val textColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.onPrimary // Ensure text is readable on primary
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUserMessage) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(vertical = 4.dp), // Add some vertical spacing between messages
            shape = RoundedCornerShape(
                topStart = if (isUserMessage) 12.dp else 4.dp,
                topEnd = if (isUserMessage) 4.dp else 12.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = backgroundColor,
                contentColor = textColor
            ),
            // Add elevation for more visual distinction
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isUserMessage) 2.dp else 1.dp
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Add a small label to identify the speaker
                Text(
                    text = if (isUserMessage) "You" else "Wiz",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.8f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = DateFormatter.formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                    color = textColor.copy(alpha = 0.7f) // Slightly dimmed timestamp
                )
            }
        }
    }
}

@Composable
fun EmptyChatPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Send a message to Wiz...",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Wiz is thinking",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.bodyMedium
        )

        // Three animated dots
        for (i in 0 until 3) {
            val delay = i * 200
            var isVisible by remember { mutableStateOf(true) }

            LaunchedEffect(key1 = true) {
                kotlinx.coroutines.delay(delay.toLong())
                while (true) {
                    isVisible = true
                    kotlinx.coroutines.delay(600)
                    isVisible = false
                    kotlinx.coroutines.delay(400)
                }
            }

            val alpha by animateFloatAsState(targetValue = if (isVisible) 1f else 0.2f)

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    transcription: String,
    isListening: Boolean,
    isResponding: Boolean,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    surfaceColor: Color
) {
    // Determine if there's manually typed text (ignoring transcription)
    val hasInputText = inputText.isNotEmpty()

    // Determine what to display in the field
    val displayText = if (isListening) transcription else inputText

    // Determine the placeholder text
    val placeholderText = if (isListening) {
        if (transcription.isNotEmpty()) "" // Show transcription if available
        else "Listening..." // Otherwise show "Listening..."
    } else {
        "Type a message"
    }

    Surface(
        color = surfaceColor,
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = displayText,
                    onValueChange = {
                        // Only allow typing when not listening
                        if (!isListening) onInputChange(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholderText) }, // Use dynamic placeholder
                    singleLine = false,
                    maxLines = 4,
                    // Field should be readable when listening, but not editable via keyboard
                    enabled = !isResponding,
                    readOnly = isListening, // Make field read-only (non-focusable) when listening
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        // Adjust disabled/readonly appearance if needed
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isListening) 0.5f else 1f),
                        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if(isListening) 0.7f else 1f), // Slightly dimmer when listening
                        focusedContainerColor = surfaceColor,
                        unfocusedContainerColor = surfaceColor,
                        disabledContainerColor = surfaceColor,
                    ),
                    trailingIcon = {
                        // Button action depends ONLY on whether we are listening or not
                        IconButton(
                            onClick = if (isListening) onMicClick else { if (hasInputText) onSendClick else onMicClick },
                            enabled = !isResponding
                        ) {
                            // Icon depends on listening state primarily
                            if (isListening) {
                                // Always show MicOff when listening, tapping stops it
                                Icon(
                                    imageVector = Icons.Default.MicOff,
                                    contentDescription = "Stop listening",
                                    tint = MaterialTheme.colorScheme.error // Use error color to indicate active listening/stop action
                                )
                            } else {
                                // If not listening, show Send if there's text, otherwise Mic
                                if (hasInputText) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Start listening",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}