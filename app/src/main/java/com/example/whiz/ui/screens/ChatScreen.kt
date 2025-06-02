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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.example.whiz.permissions.MicrophonePermissionHandler
import com.example.whiz.permissions.PermissionHandler
import androidx.navigation.NavController
import com.example.whiz.ui.navigation.Screen // Update this import
import com.example.whiz.ui.viewmodels.AuthViewModel
import android.util.Log


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: Long,
    onChatsListClick: () -> Unit,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
    navController: NavController
) {
    // Handle permission state - moved to top
    var showPermissionDialog by remember { mutableStateOf(false) }

    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    android.util.Log.d("ChatScreen", "Composed with isAuthenticated=$isAuthenticated")

    // Check for voice mode flag
    val enableVoiceMode = navController.currentBackStackEntry?.savedStateHandle?.get<Boolean>("ENABLE_VOICE_MODE") ?: false
    val initialTranscription = navController.currentBackStackEntry?.savedStateHandle?.get<String>("INITIAL_TRANSCRIPTION")
    Log.d("ChatScreen", "Composed with enableVoiceMode=$enableVoiceMode, initialTranscription=$initialTranscription, hasPermission=$hasPermission")
    
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
    val connectionError by viewModel.connectionError.collectAsState() // General connection errors
    val authErrorMessage by viewModel.showAuthErrorDialog.collectAsState() // For API key/specific auth dialogs
    val navigateToLogin by viewModel.navigateToLogin.collectAsState() // For forced login navigation
    val showAsanaSetupDialog by viewModel.showAsanaSetupDialog.collectAsState() // Collect new state
    val isContinuousListeningEnabled by viewModel.isContinuousListeningEnabled.collectAsState() // Track continuous listening mode

    // UI State
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Update viewModel with current permission state
    LaunchedEffect(hasPermission) {
        Log.d("ChatScreen", "Permission state changed: hasPermission=$hasPermission")
        if (hasPermission) {
            viewModel.onMicrophonePermissionGranted()
        } else {
            viewModel.onMicrophonePermissionDenied()
        }
    }
    
    // If mic is clicked without permission, show permission dialog
    fun handleMicClick() {
        if (!hasPermission) {
            Log.d("ChatScreen", "[LOG] Mic clicked without permission, showing dialog")
            showPermissionDialog = true
        } else {
            // The ViewModel will handle when microphone can be turned on/off
            Log.d("ChatScreen", "[LOG] Mic clicked, calling toggleSpeechRecognition")
            viewModel.toggleSpeechRecognition()
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
        // Use voice-mode-aware loading if voice mode is enabled
        if (enableVoiceMode) {
            Log.d("ChatScreen", "[LOG] Loading chat with voice mode awareness")
            viewModel.loadChatWithVoiceMode(chatId, true)
        } else {
            viewModel.loadChat(chatId)
        }
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

    // Handle connection errors (now only for generic errors shown in Snackbar)
    LaunchedEffect(connectionError) {
        if (connectionError != null) {
            // Show a snackbar for generic connection errors
            snackbarHostState.showSnackbar(
                message = connectionError ?: "A connection error occurred",
                duration = SnackbarDuration.Short
            )
            // The ViewModel should clear this error once shown, or it might reappear on recomposition.
            // Consider adding a viewModel.clearConnectionError() method if needed.
        }
    }

    // Function to navigate to Settings
    fun navigateToSettings(navController: NavController, focusSection: String? = null) {
        navController.navigate(Screen.Settings.createRoute(focusSection))
    }

    // Navigate to Login if required
    LaunchedEffect(navigateToLogin) {
        if (navigateToLogin) {
            navController.navigate(Screen.Login.route) {
                popUpTo(Screen.Home.route) { inclusive = true } // Or your preferred popUpTo logic
            }
            viewModel.onLoginNavigationComplete() // Reset the state
        }
    }

    // Enable voice mode if flag is set
    LaunchedEffect(enableVoiceMode, isContinuousListeningEnabled) {
        Log.d("ChatScreen", "[LOG] LaunchedEffect(enableVoiceMode) triggered: enableVoiceMode=$enableVoiceMode, hasPermission=$hasPermission, isContinuousListeningEnabled=$isContinuousListeningEnabled")
        
        // Only proceed if enableVoiceMode is explicitly true (not just any truthy value)
        if (enableVoiceMode == true) {
            Log.d("ChatScreen", "[LOG] Voice mode explicitly enabled - proceeding with voice setup")
            if (hasPermission) {
                Log.d("ChatScreen", "[LOG] Delaying voice mode setup to ensure UI is ready")
                kotlinx.coroutines.delay(500) // Wait for UI to be fully composed and activity to be foregrounded
                
                // Enable voice responses since user opened app via voice
                Log.d("ChatScreen", "[LOG] Enabling voice responses for voice-triggered app launch (current state: $isVoiceResponseEnabled)")
                if (!isVoiceResponseEnabled) {
                    Log.d("ChatScreen", "[LOG] Toggling voice response from OFF to ON")
                    viewModel.toggleVoiceResponse()
                } else {
                    Log.d("ChatScreen", "[LOG] Voice response already enabled, skipping toggle")
                }
                
                // Ensure continuous listening is enabled (don't use toggle to avoid accidentally disabling it)
                Log.d("ChatScreen", "[LOG] Ensuring continuous listening is enabled for voice mode")
                viewModel.onMicrophonePermissionGranted()
                viewModel.ensureContinuousListeningEnabled()
                
                // Clear the voice mode flag after setup is complete to prevent persistence
                Log.d("ChatScreen", "[LOG] Voice mode setup complete - clearing ENABLE_VOICE_MODE flag")
                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", false)
            } else {
                Log.d("ChatScreen", "[LOG] Permission dialog for voice mode")
                showPermissionDialog = true
                // Clear flag even if permission not granted to prevent persistence
                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", false)
            }
        } else {
            Log.d("ChatScreen", "[LOG] Voice mode not enabled or false - skipping voice setup")
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
            // Disable text input when responding or speaking
            val isTextInputDisabled = isResponding || isSpeaking
            // Only disable mic when TTS is speaking, NOT when responding (allow mic control during response)
            val isMicDisabled = isSpeaking
            ChatInputBar(
                inputText = inputText,
                transcription = transcription,
                isListening = isListening,
                isInputDisabled = isTextInputDisabled,
                isMicDisabled = isMicDisabled,
                isResponding = isResponding,
                isContinuousListeningEnabled = isContinuousListeningEnabled,
                onInputChange = viewModel::updateInputText,
                onSendClick = { viewModel.sendUserInput(inputText) }, // Pass current input text explicitly
                onInterruptClick = { viewModel.interruptResponse() }, // Pass new callback for interrupts
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

    // Dialog for missing Asana Token
    if (showAsanaSetupDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onAsanaSetupDialogDismissed() },
            title = { Text("Asana Account Setup") },
            text = { Text("Your Asana access token is missing or invalid. Please set it up in Settings to use Asana features.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.onAsanaSetupDialogDismissed()
                    navigateToSettings(navController, focusSection = "asana")
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                Button(onClick = { viewModel.onAsanaSetupDialogDismissed() }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // Show Auth Error Dialog (handles API key issues and other non-login auth errors)
    if (authErrorMessage != null) {
        val actualAuthErrorMessage = authErrorMessage!! 
        
        // Determine dialog title and if it's a Claude specific API key issue
        var dialogTitle = "Authentication Error" // Default title
        var focusTarget: String? = null

        if (actualAuthErrorMessage.contains("API key", ignoreCase = true)) {
            dialogTitle = "API Key Issue"
            // Check if it specifically mentions Claude to enable focused navigation
            if (actualAuthErrorMessage.contains("Claude", ignoreCase = true)) {
                focusTarget = "claude"
            }
            // Potentially add Asana check here if its errors also use this dialog and need focus
            // else if (actualAuthErrorMessage.contains("Asana", ignoreCase = true)) {
            //     focusTarget = "asana" 
            // }
        }

        AlertDialog(
            onDismissRequest = { 
                            if (!dialogTitle.contains("API Key")) viewModel.clearAuthErrorDialog()
                           },
            title = { Text(dialogTitle) },
            text = { Text(actualAuthErrorMessage) }, // Removed elvis operator, already asserted not null
            confirmButton = {
                Button(onClick = {
                    viewModel.clearAuthErrorDialog()
                    navigateToSettings(navController, focusSection = focusTarget) // Pass focusTarget
                }) {
                    Text("Go to Settings")
                }
            },
            dismissButton = {
                // Optional: Provide a way to dismiss if appropriate, e.g., for general auth errors
                if (actualAuthErrorMessage.contains("log in again")) { // Check against the actual value
                    Button(onClick = {
                         viewModel.clearAuthErrorDialog()
                         // Optionally navigate to login
                         // navController.navigate("login") { ... }
                    }) {
                        Text("Dismiss") // Or "Login"
                    }
                }
            }
        )
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
    // State for animation
    var isAnimating by remember { mutableStateOf(true) }
    
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
                text = "Whiz is computing",
                style = MaterialTheme.typography.bodyMedium // Consistent style
            )
            Spacer(modifier = Modifier.width(8.dp)) // Space before dots

            // Animated dots with proper animation
            val dotCount = 3
            val dotSize = 6.dp
            val dotSpacing = 4.dp

            Row(horizontalArrangement = Arrangement.spacedBy(dotSpacing)) {
                for (i in 0 until dotCount) {
                    val delay = i * 200 // 200ms delay between each dot
                    
                    // Each dot has its own animation state
                    var dotVisible by remember { mutableStateOf(false) }
                    val alpha by animateFloatAsState(
                        targetValue = if (dotVisible) 1f else 0.3f,
                        animationSpec = tween(durationMillis = 600),
                        label = "dotAlpha$i"
                    )
                    
                    // LaunchedEffect to control the animation timing
                    LaunchedEffect(isAnimating) {
                        delay(delay.toLong()) // Initial delay for staggered effect
                        while (isAnimating) {
                            dotVisible = true
                            delay(600) // Stay visible
                            dotVisible = false  
                            delay(600) // Stay dim
                        }
                    }
                    
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = alpha)
                            )
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
    isInputDisabled: Boolean, // Text input disabled state
    isMicDisabled: Boolean = isInputDisabled, // Separate mic disabled state, defaults to same as text input
    isResponding: Boolean, // Bot is currently responding/thinking
    isContinuousListeningEnabled: Boolean, // Add continuous listening state
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onInterruptClick: () -> Unit = {}, // New callback for interrupts
    onMicClick: () -> Unit,
    surfaceColor: Color,
    shape: Shape = RectangleShape
) {
    val hasInputText = inputText.isNotBlank()
    
    // Check if we can interrupt (bot is responding and user has new input)
    val canInterrupt = isResponding && hasInputText
    
    // 🔧 Show actual input text if present (sent message), otherwise show transcription when listening
    val displayValue = when {
        inputText.isNotBlank() -> inputText // Always show sent message if present (grayed out when disabled)
        isListening -> transcription // Show live transcription only when no sent message
        else -> inputText // Default to input text
    }
    
    val placeholderText = if (isListening && inputText.isBlank()) "Listening..." else "Type or tap mic..."

    Surface(
        color = surfaceColor,
        //tonalElevation = 4.dp,
        shape = shape,
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
                    // Allow input change even during responses for interrupt functionality
                    if (!isListening) onInputChange(it)
                },
                modifier = Modifier.fillMaxWidth(), // TextField fills the Box
                placeholder = { Text(placeholderText) },
                readOnly = isListening, // Cannot edit via keyboard when listening
                enabled = true, // Always enable input field to allow interrupts
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp), // Rounded corners
                colors = OutlinedTextFieldDefaults.colors(
                    // Define colors for different states - no special interrupt colors
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor,
                    disabledContainerColor = surfaceColor.copy(alpha = 0.8f),
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
                trailingIcon = { // Place the icon back inside the TextField
                    // Button logic with seamless interrupt support
                    val (icon, description, action, tint) = when {
                        canInterrupt -> {
                            // When bot is responding and user has input, handle as interrupt but look like normal send
                            Tuple4(
                                Icons.Filled.Send,
                                "Send message", // Same description as normal send
                                onInterruptClick,
                                MaterialTheme.colorScheme.primary // Same color as normal send
                            )
                        }
                        isListening -> {
                            Tuple4(
                                Icons.Filled.MicOff,
                                "Stop listening",
                                onMicClick,
                                MaterialTheme.colorScheme.error
                            )
                        }
                        isResponding && isContinuousListeningEnabled -> {
                            Tuple4(
                                Icons.Filled.MicOff,
                                "Turn off continuous listening",
                                onMicClick,
                                MaterialTheme.colorScheme.error
                            )
                        }
                        isResponding -> {
                            Tuple4(
                                Icons.Filled.Mic,
                                "Turn on continuous listening",
                                onMicClick,
                                MaterialTheme.colorScheme.primary
                            )
                        }
                        hasInputText -> {
                            Tuple4(
                                Icons.Filled.Send,
                                "Send message",
                                onSendClick,
                                MaterialTheme.colorScheme.primary
                            )
                        }
                        else -> {
                            Tuple4(
                                Icons.Filled.Mic,
                                "Start listening",
                                onMicClick,
                                MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    val isButtonEnabled = when {
                        canInterrupt -> true // Always allow interrupts
                        isListening -> !isMicDisabled
                        isResponding -> !isMicDisabled
                        hasInputText -> !isInputDisabled
                        else -> !isMicDisabled
                    }
                    
                    IconButton(
                        onClick = action,
                        enabled = isButtonEnabled
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = description,
                            tint = if (isButtonEnabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        }
    }
}

// Helper data class for the tuple
private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)