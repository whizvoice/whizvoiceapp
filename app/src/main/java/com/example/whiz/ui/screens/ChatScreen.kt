package com.example.whiz.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.* // Use wildcard import for brevity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Use wildcard import for brevity
import androidx.compose.material3.* // Use wildcard import for brevity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whiz.data.local.DateFormatter
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import com.example.whiz.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.delay
import androidx.compose.animation.core.RepeatMode // Import RepeatMode
import androidx.compose.animation.core.StartOffset // Import StartOffset
import androidx.compose.material3.MaterialTheme // Ensure MaterialTheme is imported if not covered by wildcard
import com.example.whiz.permissions.MicrophonePermissionHandler
import com.example.whiz.permissions.PermissionHandler


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: Long,
    onChatsListClick: () -> Unit,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel()
) {
    // ViewModel state collections
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val transcription by viewModel.transcriptionState.collectAsState()
    val chatTitle by viewModel.chatTitle.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState() // Agent thinking/fetching
    val speechError by viewModel.speechError.collectAsState()
    val isVoiceResponseEnabled by viewModel.isVoiceResponseEnabled.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState() // TTS actively speaking

    // UI State
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle permission state
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    // Update viewModel with current permission state
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }
    
    // If mic is clicked without permission, show permission dialog
    fun handleMicClick() {
        if (!hasPermission) {
            showPermissionDialog = true
        } else {
            // Only allow mic toggle if not speaking or responding
            val isInputDisabled = isResponding || isSpeaking
            if (!isInputDisabled) {
                viewModel.toggleSpeechRecognition()
            }
        }
    }

    // Microphone permission dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Microphone Permission Required") },
            text = { Text("Whiz requires continuous microphone access to function as a voice assistant. Without this permission, voice features will not work. Would you like to grant this permission?") },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    onRequestPermission()
                }) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                Button(onClick = { showPermissionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Define the color for the input area with elevation
    val inputSurfaceColor = MaterialTheme.colorScheme.background // Keep it simple or use previous calculation
    // .copy(alpha = 0.9f) // Example customization
    // .compositeOver(MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.1f))


    // Load chat data when chatId changes
    LaunchedEffect(chatId) {
        viewModel.loadChat(chatId)
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) { // Trigger scroll based on message count change
        if (messages.isNotEmpty()) {
            delay(100) // Allow layout
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Show speech recognition errors
    LaunchedEffect(speechError) {
        speechError?.let {
            snackbarHostState.showSnackbar(message = it, duration = SnackbarDuration.Short)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(chatTitle, maxLines = 1) }, // Prevent title wrapping issues
                navigationIcon = {
                    IconButton(onClick = onChatsListClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Open Chats List")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleVoiceResponse) {
                        Icon(
                            imageVector = if (isVoiceResponseEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = if (isVoiceResponseEnabled) "Disable Voice Response" else "Enable Voice Response",
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Pass the combined state to disable input correctly
            val isInputDisabled = isResponding || isSpeaking
            ChatInputBar(
                inputText = inputText,
                transcription = transcription,
                isListening = isListening,
                isInputDisabled = isInputDisabled, // Use a combined state
                onInputChange = viewModel::updateInputText,
                onSendClick = { viewModel.sendUserInput(inputText) }, // Pass current input text explicitly
                onMicClick = { handleMicClick() },
                surfaceColor = inputSurfaceColor
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Apply padding from Scaffold
                .background(if (isSpeaking) Color.Black.copy(alpha = 0.03f) else Color.Transparent) // Subtle speaking indicator
        ) {
            // Display messages or placeholder
            if (messages.isEmpty() && !isResponding && !isSpeaking) { // Show placeholder only if truly empty and idle
                EmptyChatPlaceholder()
            } else {
                MessagesList(
                    messages = messages,
                    listState = listState
                )
            }

            // Typing Indicator - Show only when responding (agent thinking), not when speaking
            AnimatedVisibility(
                visible = isResponding && !isSpeaking,
                enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp) // Position above input bar area
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
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
        // reverseLayout = true // Keep false for messages appearing at the bottom
    ) {
        items(messages, key = { it.id }) { message ->
            MessageItem(message = message)
        }
        // Add a spacer at the bottom for breathing room above input bar
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}


@Composable
fun MessageItem(message: MessageEntity) {
    val isUserMessage = message.type == MessageType.USER

    val backgroundColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.primary
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.onPrimary
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val alignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(
                    start = if (isUserMessage) 56.dp else 0.dp, // More indentation for user messages
                    end = if (isUserMessage) 0.dp else 56.dp   // More indentation for assistant messages
                ),
            shape = RoundedCornerShape( // Slightly different shapes
                topStart = if (!isUserMessage) 4.dp else 16.dp,
                topEnd = if (isUserMessage) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = textColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isUserMessage) { // Optional speaker label for assistant
                    Text(
                        text = "Whiz", // Assistant name
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.9f),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = DateFormatter.formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                    color = textColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun EmptyChatPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Start chatting with Whiz!\nType or tap the mic.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TypingIndicator() {
    // Using Card for consistency
    Card(
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp), // Match assistant bubble
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp) // Match message padding
        ) {
            Text(
                text = "Whiz is thinking",
                style = MaterialTheme.typography.bodyMedium // Consistent style
            )
            Spacer(modifier = Modifier.width(8.dp)) // Space before dots

            // Animated dots
            val dotCount = 3
            val animationDelay = 1000 // Slightly longer cycle
            val dotSize = 6.dp
            val dotSpacing = 4.dp

            Row(horizontalArrangement = Arrangement.spacedBy(dotSpacing)) {
                for (i in 0 until dotCount) {
                    val delay = (i * animationDelay / dotCount).toLong()
                    val alpha by animateFloatAsState(
                        targetValue = 1f, // Animate alpha from low to high and back
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = animationDelay / 2), // Faster fade in/out
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(offsetMillis = delay.toInt())
                        )
                    )
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha.coerceIn(0.4f, 1f))) // Adjusted alpha range
                    )
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    inputText: String,
    transcription: String,
    isListening: Boolean,
    isInputDisabled: Boolean, // Combined disabled state (responding OR speaking)
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    surfaceColor: Color
) {
    val hasInputText = inputText.isNotBlank()
    // Display transcription if listening, otherwise input text. Show placeholder within TextField.
    val displayValue = if (isListening) transcription else inputText
    val placeholderText = if (isListening) "Listening..." else "Type or tap mic..."

    Surface(
        color = surfaceColor,
        //tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding() // Handles navigation bar insets
            .imePadding() // Handles keyboard insets
    ) {
        Box( // Use Box to contain TextField and allow padding
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = displayValue,
                onValueChange = {
                    // Only allow input change if not listening
                    if (!isListening) onInputChange(it)
                },
                modifier = Modifier.fillMaxWidth(), // TextField fills the Box
                placeholder = { Text(placeholderText) },
                readOnly = isListening, // Cannot edit via keyboard when listening
                enabled = !isInputDisabled, // Disable field if responding or speaking
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp), // Rounded corners
                colors = OutlinedTextFieldDefaults.colors(
                    // Define colors for different states
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), // Dim text when disabled
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor,
                    disabledContainerColor = surfaceColor.copy(alpha = 0.8f), // Dim container when disabled
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
                trailingIcon = { // Place the icon back inside the TextField
                    val canSend = hasInputText && !isListening
                    val icon = when {
                        isListening -> Icons.Filled.MicOff
                        canSend -> Icons.Filled.Send
                        else -> Icons.Filled.Mic
                    }
                    val description = when {
                        isListening -> "Stop listening"
                        canSend -> "Send message"
                        else -> "Start listening"
                    }
                    val tint = when {
                        isListening -> MaterialTheme.colorScheme.error // Red when listening/stoppable
                        canSend -> MaterialTheme.colorScheme.primary // Primary color for send
                        else -> MaterialTheme.colorScheme.primary // Primary color for mic start
                    }

                    IconButton(
                        onClick = if (isListening) onMicClick else { if (canSend) onSendClick else onMicClick },
                        enabled = !isInputDisabled // Use the combined disabled state
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = description,
                            // Apply tint based on state, dim if disabled
                            tint = if (!isInputDisabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        }
    }
}