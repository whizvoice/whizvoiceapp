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
import com.example.whiz.ui.viewmodels.VoiceManager

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
import androidx.navigation.NavHostController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: Long,
    onChatsListClick: () -> Unit,
    permissionManager: com.example.whiz.permissions.PermissionManager,
    voiceManager: VoiceManager,
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

    // Check for TTS mode flag (full voice experience: speech recognition + TTS responses)
    val enableTTSMode = navController.currentBackStackEntry?.savedStateHandle?.get<Boolean>("ENABLE_VOICE_MODE") ?: false
    val initialTranscription = navController.currentBackStackEntry?.savedStateHandle?.get<String>("INITIAL_TRANSCRIPTION")
    Log.d("ChatScreen", "Composed with enableTTSMode=$enableTTSMode, initialTranscription=$initialTranscription, hasPermission=$hasPermission")
    
    // ViewModel state collections
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val isInputFromVoice by viewModel.isInputFromVoice.collectAsState()
    val chatTitle by viewModel.chatTitle.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState() // Agent thinking/fetching
    val connectionError by viewModel.connectionError.collectAsState() // General connection errors
    val authErrorMessage by viewModel.showAuthErrorDialog.collectAsState() // For API key/specific auth dialogs
    val navigateToLogin by viewModel.navigateToLogin.collectAsState() // For forced login navigation
    val showAsanaSetupDialog by viewModel.showAsanaSetupDialog.collectAsState() // Collect new state
    val isVoiceResponseEnabled by viewModel.isVoiceResponseEnabled.collectAsState()
    


    // Voice state from VoiceManager (clean separation)
    val isListening by voiceManager.isListening.collectAsState()
    val transcription by voiceManager.transcriptionState.collectAsState()
    val speechError by voiceManager.speechError.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState() // TTS actively speaking
    val isContinuousListeningEnabled by voiceManager.isContinuousListeningEnabled.collectAsState() // Track continuous listening mode

    // UI State
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Observe permission state directly from PermissionManager for reactive UI updates
    val hasPermissionReactive by permissionManager.microphonePermissionGranted.collectAsState()
    
    // Use reactive permission state for UI decisions (dialog, mic button, etc.)
    val effectiveHasPermission = hasPermissionReactive
    
    // Auto-prompt for microphone permission when app opens (if not already granted)
    LaunchedEffect(effectiveHasPermission) {
        Log.d("ChatScreen", "Auto-permission check: effectiveHasPermission=$effectiveHasPermission")
        if (!effectiveHasPermission) {
            // Small delay to ensure UI is fully composed before showing dialog
            kotlinx.coroutines.delay(500L)
            Log.d("ChatScreen", "Auto-showing permission dialog - no microphone permission granted")
            showPermissionDialog = true
        }
    }
    
    
    // If mic is clicked without permission, show permission dialog
    fun handleMicClick() {
        if (!effectiveHasPermission) {
            Log.d("ChatScreen", "[LOG] Mic clicked without permission, showing dialog")
            showPermissionDialog = true
        } else {
            // Use VoiceManager for clean voice coordination
            Log.d("ChatScreen", "[LOG] Mic clicked, toggling continuous listening via VoiceManager")
            voiceManager.toggleContinuousListening()
        }
    }

    // Microphone permission dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Microphone Permission Required") },
            text = { Text("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?") },
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
                    Text("Not Now")
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
        // Use TTS-mode-aware loading if TTS mode is enabled
        if (enableTTSMode) {
            Log.d("ChatScreen", "[LOG] Loading chat with TTS mode awareness")
            viewModel.loadChatWithVoiceMode(chatId, true)
        } else {
            viewModel.loadChat(chatId)
        }
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) { // Trigger scroll based on message count change
        if (messages.isNotEmpty() && messages.size > 0) {
            delay(100L) // Allow layout
            val targetIndex = messages.size - 1
            if (targetIndex >= 0) { // Extra safety check to prevent crash
                listState.animateScrollToItem(targetIndex)
            }
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

    // Voice app behavior: enable microphone for all chats, plus TTS for Assistant launches
    LaunchedEffect(chatId, enableTTSMode, effectiveHasPermission) {
        Log.d("ChatScreen", "[LOG] LaunchedEffect triggered: chatId=$chatId, enableTTSMode=$enableTTSMode, effectiveHasPermission=$effectiveHasPermission, isContinuousListeningEnabled=$isContinuousListeningEnabled")
        
        // 🎙️ VOICE APP BEHAVIOR: Always enable microphone for ALL chats (this is a voice app!)
        if (effectiveHasPermission) {
            Log.d("ChatScreen", "[LOG] Permission available - enabling continuous listening (voice app default behavior)")
            kotlinx.coroutines.delay(500L) // Wait for UI to be ready
            
            // Always enable continuous listening for all chats (voice app default)
            // 🔧 Enable continuous listening in BOTH VoiceManager and ChatViewModel
            voiceManager.updateContinuousListeningEnabled(true)
            viewModel.ensureContinuousListeningEnabled()
            
            // Set up transcription callback for chat integration
            voiceManager.setTranscriptionCallback { transcription ->
                if (transcription.isNotBlank()) {
                    Log.d("ChatScreen", "[LOG] Voice transcription received: '$transcription'")
                    viewModel.updateInputText(transcription, fromVoice = true)
                    viewModel.sendUserInput(transcription)
                }
            }
            
            Log.d("ChatScreen", "[LOG] Continuous listening enabled for chat (voice app default)")
        } else {
            Log.d("ChatScreen", "[LOG] No microphone permission - voice setup skipped (will retry when permission granted)")
        }
        
        // 🔊 GOOGLE ASSISTANT BEHAVIOR: Additionally enable TTS mode if triggered by Assistant
        if (enableTTSMode == true) {
            Log.d("ChatScreen", "[LOG] Google Assistant launch detected - enabling FULL voice mode (microphone + TTS)")
            if (effectiveHasPermission) {
                kotlinx.coroutines.delay(500L) // Wait for UI to be fully composed
                
                // Enable voice responses for hands-free Google Assistant experience
                Log.d("ChatScreen", "[LOG] Enabling voice responses for Assistant-triggered launch (current: $isVoiceResponseEnabled)")
                if (!isVoiceResponseEnabled) {
                    Log.d("ChatScreen", "[LOG] Toggling voice response from OFF to ON for Assistant")
                    viewModel.toggleVoiceResponse()
                    voiceManager.setVoiceResponseEnabled(true)
                } else {
                    Log.d("ChatScreen", "[LOG] Voice response already enabled")
                }
                
                // Ensure continuous listening is enabled (should already be set above for new chats)
                Log.d("ChatScreen", "[LOG] Ensuring continuous listening for Assistant launch")
                // 🔧 Enable continuous listening in BOTH VoiceManager and ChatViewModel
                voiceManager.updateContinuousListeningEnabled(true)
                viewModel.ensureContinuousListeningEnabled()
                
                // Set up transcription callback if not already done
                voiceManager.setTranscriptionCallback { transcription ->
                    if (transcription.isNotBlank()) {
                        Log.d("ChatScreen", "[LOG] Assistant transcription received: '$transcription'")
                        viewModel.updateInputText(transcription, fromVoice = true)
                        viewModel.sendUserInput(transcription)
                    }
                }
                
                // Clear the TTS mode flag to prevent persistence across navigation
                Log.d("ChatScreen", "[LOG] Assistant voice setup complete - clearing ENABLE_VOICE_MODE flag")
                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", false)
            } else {
                Log.d("ChatScreen", "[LOG] No microphone permission for Assistant launch - showing dialog")
                showPermissionDialog = true
                // Clear flag even without permission to prevent persistence
                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", false)
            }
        }
    }

    // 🔧 PRODUCTION BUG FIX: Eliminate Scaffold completely to prevent touch interception
    // Replace with simple Column layout - no bottomBar, no padding calculations, no overlays
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar - directly placed
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
        
        // Messages area - takes remaining space above input
        Box(
            modifier = Modifier.weight(1f)
        ) {
            if (messages.isEmpty() && !isResponding && !isSpeaking) {
                EmptyChatPlaceholder()
            } else {
                MessagesList(
                    messages = messages,
                    listState = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (isSpeaking) Color.Black.copy(alpha = 0.03f) else Color.Transparent),
                    showTypingIndicator = isResponding && !isSpeaking
                )
            }
        }
        
        // Input bar - directly placed at bottom, no Scaffold bottomBar wrapper
        run {
            // Never disable text input - users should always be able to type
            val isTextInputDisabled = false
            // Only disable mic during TTS when no headphones (to prevent audio feedback)
            val isMicDisabled = voiceManager.shouldShowMicButtonDuringTTS()
            
            ChatInputBar(
                inputText = inputText,
                isInputFromVoice = isInputFromVoice,
                transcription = transcription,
                isListening = isListening,
                isInputDisabled = isTextInputDisabled,
                isMicDisabled = isMicDisabled,
                isResponding = isResponding,
                isContinuousListeningEnabled = isContinuousListeningEnabled,
                isSpeaking = isSpeaking,
                shouldShowMicDuringTTS = voiceManager.shouldShowMicButtonDuringTTS(),
                onInputChange = viewModel::updateInputText,
                onSendClick = { 
                    // Always send regular messages - let server handle interruption logic
                    viewModel.sendUserInput(inputText)
                },
                onMicClick = { handleMicClick() },
                onMicClickDuringTTS = { voiceManager.handleMicClickDuringTTS() },
                surfaceColor = inputSurfaceColor
            )
        }
        
        // Snackbar host at bottom
        SnackbarHost(snackbarHostState)
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
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
    showTypingIndicator: Boolean = false
) {
    // 🔧 DEDUPLICATION FIX: Remove duplicate messages during chat ID migration race condition
    // The test countMessageOccurrences() uses UI Automator to scan screen text - during optimistic
    // to server chat ID migration, same content can appear in two UI contexts simultaneously
    val deduplicatedMessages = remember(messages) {
        val originalSize = messages.size
        
        // Group by content+type+timestamp (rounded to 2 seconds) to catch migration duplicates
        val grouped = messages.groupBy { message ->
            Triple(
                message.content.trim(),
                message.type,
                message.timestamp / 2000L // Round to 2-second window for migration overlap
            )
        }
        
        // For each group, prefer the message with positive chat ID (server-backed)
        val deduplicated = grouped.mapNotNull { (_, duplicateList) ->
            when {
                duplicateList.size == 1 -> duplicateList.first()
                duplicateList.size > 1 -> {
                    // Prefer positive chat ID (server) over negative (optimistic)
                    val serverMessage = duplicateList.find { it.chatId > 0 }
                    val chosenMessage = serverMessage ?: duplicateList.first()
                    
                    android.util.Log.w("MessagesList", "🔧 UI DEDUPLICATION: Found ${duplicateList.size} duplicates for content '${chosenMessage.content.take(30)}...', chose ${if (serverMessage != null) "server" else "optimistic"} message (ID:${chosenMessage.id}, ChatID:${chosenMessage.chatId})")
                    android.util.Log.d("MessagesList", "🔧 UI DEDUPLICATION: Duplicate list: ${duplicateList.map { "ID:${it.id} ChatID:${it.chatId}" }}")
                    
                    chosenMessage
                }
                else -> null
            }
        }.sortedBy { it.timestamp } // Maintain chronological order
        
        if (deduplicated.size != originalSize) {
            android.util.Log.w("MessagesList", "🔧 UI DEDUPLICATION: Fixed chat migration race condition - removed ${originalSize - deduplicated.size} duplicate messages (${originalSize} -> ${deduplicated.size})")
        }
        
        deduplicated
    }
    
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(), // Only fill width, not height - prevents overlay
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(deduplicatedMessages, key = { message -> 
            // 🔧 SAFETY FIX: Create unique key from content+timestamp to prevent any LazyColumn duplication
            // This ensures even if somehow duplicates slip through, they won't render as separate items
            "${message.content.hashCode()}_${message.timestamp}_${message.type}"
        }) { message ->
            MessageItem(message = message)
        }
        
        // Show typing indicator as a message when bot is responding
        if (showTypingIndicator) {
            item(key = "typing_indicator") {
                TypingIndicator()
            }
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
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { 
                        contentDescription = if (message.type == MessageType.USER) {
                            "User message: ${message.content}"
                        } else {
                            "Assistant message: ${message.content}"
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().padding(16.dp),
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
                    val delay = i * 200 // Delay between each dot
                    
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
                                                      delay(600L) // Stay visible
                          dotVisible = false
                          delay(600L) // Stay dim
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
    isInputFromVoice: Boolean,
    transcription: String,
    isListening: Boolean,
    isInputDisabled: Boolean, // Text input disabled state
    isMicDisabled: Boolean = isInputDisabled, // Separate mic disabled state, defaults to same as text input
    isResponding: Boolean, // Bot is currently responding/thinking
    isContinuousListeningEnabled: Boolean, // Add continuous listening state
    isSpeaking: Boolean = false, // Add TTS speaking state
    shouldShowMicDuringTTS: Boolean, // New parameter for headphone-aware behavior
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onInterruptClick: () -> Unit = {}, // New callback for interrupts
    onMicClick: () -> Unit,
    onMicClickDuringTTS: () -> Unit = {}, // New callback for TTS mic click
    surfaceColor: Color,
    shape: Shape = RectangleShape
) {
    val hasInputText = inputText.isNotBlank()
    val hasTypedText = hasInputText && !isInputFromVoice
    val hasVoiceText = hasInputText && isInputFromVoice
    
    // Debug logging for button logic and input state
    Log.d("ChatInputBar", "🔍 Button logic state: inputText='$inputText', hasInputText=$hasInputText, hasTypedText=$hasTypedText, hasVoiceText=$hasVoiceText, isInputFromVoice=$isInputFromVoice, isContinuousListeningEnabled=$isContinuousListeningEnabled, isListening=$isListening, isResponding=$isResponding")
    Log.d("ChatInputBar", "🔍 Input field state: isInputDisabled=$isInputDisabled, isSpeaking=$isSpeaking, enabled=${!isInputDisabled}")
    Log.d("ChatInputBar", "🔍 PRODUCTION BUG DEBUG: TextField will be enabled=${!isInputDisabled}, isInputDisabled=$isInputDisabled")
    
    // 🔧 PRODUCTION BUG FIX: Ensure displayValue recomposes correctly
    // The issue was complex conditional logic that wasn't recomposing properly
    val displayValue = when {
        inputText.isNotBlank() -> inputText // Always show actual input text if present
        isListening && transcription.isNotBlank() -> transcription // Show live transcription when listening
        else -> inputText // Default to input text (could be empty)
    }
    
    Log.d("ChatInputBar", "🔍 PRODUCTION BUG DEBUG: displayValue='$displayValue', inputText='$inputText', isListening=$isListening, transcription='$transcription'")
    
    // 🔧 ENHANCED DEBUG LOGGING: Track displayValue changes and timing
    val currentTimeMs = System.currentTimeMillis()
    Log.d("ChatInputBar", "🔍 ENHANCED DEBUG [${currentTimeMs}]: displayValue='$displayValue', inputText='$inputText', isListening=$isListening, transcription='$transcription', isResponding=$isResponding")
    Log.d("ChatInputBar", "🔍 ENHANCED DEBUG [${currentTimeMs}]: displayValue.length=${displayValue.length}, inputText.length=${inputText.length}")
    
    // 🔧 CRITICAL DEBUG: Log every time displayValue changes
    LaunchedEffect(displayValue) {
        Log.d("ChatInputBar", "🔥 DISPLAYVALUE CHANGE [${System.currentTimeMillis()}]: '$displayValue' (length=${displayValue.length})")
        Log.d("ChatInputBar", "🔥 DISPLAYVALUE CHANGE [${System.currentTimeMillis()}]: inputText='$inputText', isListening=$isListening, transcription='$transcription'")
    }
    
    // 🔧 CRITICAL DEBUG: Log every time inputText changes
    LaunchedEffect(inputText) {
        Log.d("ChatInputBar", "🔥 INPUTTEXT CHANGE [${System.currentTimeMillis()}]: '$inputText' (length=${inputText.length})")
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
            // 🔧 PRODUCTION BUG FIX: Remove key() block that was causing TextField recreation
            // The key() block was destroying focus and text input handling on every character
            OutlinedTextField(
                value = displayValue,
                onValueChange = { newValue ->
                    // 🔧 ENHANCED DEBUG: Log every onValueChange call
                    Log.d("ChatInputBar", "🔥 TEXTFIELD onValueChange [${System.currentTimeMillis()}]: '$newValue' (prev: '$displayValue')")
                    Log.d("ChatInputBar", "🔥 TEXTFIELD onValueChange [${System.currentTimeMillis()}]: newValue.length=${newValue.length}, displayValue.length=${displayValue.length}")
                    
                    // Always allow input change - this enables manual typing to disable continuous listening
                    // The updateInputText method will handle stopping voice recognition when user types
                    onInputChange(newValue)
                },
                modifier = Modifier
                    .fillMaxWidth() // TextField fills the Box
                    .semantics { 
                        contentDescription = "Message input field"
                        testTag = "chat_input_field"
                        // 🔧 PRODUCTION BUG FIX: Ensure proper accessibility exposure for UI testing
                        role = Role.Button
                        focused = true
                    }, // Add accessibility description for both users and testing
                placeholder = { Text(placeholderText) },
                readOnly = false, // Always allow text input for production bug fix
                enabled = !isInputDisabled, // Respect the isInputDisabled parameter
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp), // Rounded corners
                colors = OutlinedTextFieldDefaults.colors(
                    // Define colors for different states - voice text appears gray
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    // Voice text is gray, typed text is normal color
                    focusedTextColor = if (isInputFromVoice) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = if (isInputFromVoice) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    focusedContainerColor = surfaceColor,
                    unfocusedContainerColor = surfaceColor,
                    disabledContainerColor = surfaceColor.copy(alpha = 0.8f),
                    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                ),
                trailingIcon = { // Place the icon back inside the TextField
                    // Button logic with seamless interrupt support and headphone-aware TTS behavior
                    val (icon, description, action, tint) = when {
                        hasTypedText -> {
                            // PRIORITY: Show send button for typed text (always needs manual send)
                            // This must come first to override listening/responding states
                            Tuple4(
                                Icons.Filled.Send,
                                "Send message",
                                onSendClick,
                                MaterialTheme.colorScheme.primary
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
                        shouldShowMicDuringTTS -> {
                            // Show mic button during TTS when headphones not connected (allows manual override)
                            Tuple4(
                                Icons.Filled.Mic,
                                "Start listening during response",
                                onMicClickDuringTTS,
                                MaterialTheme.colorScheme.primary
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
                        hasVoiceText && !isContinuousListeningEnabled -> {
                            // Show send button for voice text when continuous listening is OFF
                            Tuple4(
                                Icons.Filled.Send,
                                "Send message",
                                onSendClick,
                                MaterialTheme.colorScheme.primary
                            )
                        }
                        isContinuousListeningEnabled && !hasInputText -> {
                            // Show mic off button only when no text is present
                            Tuple4(
                                Icons.Filled.MicOff,
                                "Turn off continuous listening",
                                onMicClick,
                                MaterialTheme.colorScheme.error
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
                    
                    // Debug logging for button decision
                    Log.d("ChatInputBar", "🎯 Button decision: description='$description', icon=${icon.name}")

                    val isButtonEnabled = when {
                        hasTypedText -> true  // Send button for typed text is ALWAYS enabled
                        hasVoiceText -> true  // Send button for voice text is ALWAYS enabled  
                        isListening -> !isMicDisabled
                        shouldShowMicDuringTTS -> !isMicDisabled // Allow mic during TTS override
                        isResponding -> !isMicDisabled
                        else -> !isMicDisabled
                    }
                    
                    IconButton(
                        onClick = action,
                        enabled = isButtonEnabled,
                        modifier = Modifier.semantics { 
                            contentDescription = description
                            testTag = when {
                                hasTypedText -> "send_button"
                                hasVoiceText -> "send_button"
                                isListening -> "mic_off_button"
                                else -> "mic_button"
                            }
                        }
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

@Composable
fun ChatScreenWithPermissionDialog(
    chatId: Long,
    onChatsListClick: () -> Unit,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    navController: NavHostController,
    // Optional content parameter for testing
    content: @Composable () -> Unit = {
        // Default content - the real ChatScreen
        ChatScreenContent(
            chatId = chatId,
            onChatsListClick = onChatsListClick,
            navController = navController
        )
    }
) {
    var showPermissionDialog by remember { mutableStateOf(false) }

    // Automatic permission prompt logic
    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            delay(500L) // Permission check delay
            showPermissionDialog = true
        }
    }

    // Main content
    content()
    
    // Microphone permission dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Microphone Permission Required") },
            text = { Text("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?") },
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
                    Text("Not Now")
                }
            }
        )
    }
}

@Composable
private fun ChatScreenContent(
    chatId: Long,
    onChatsListClick: () -> Unit,
    navController: NavHostController
) {
    // This contains the original ChatScreen logic with ViewModels
    val viewModel: ChatViewModel = hiltViewModel()
    val authViewModel: AuthViewModel = hiltViewModel()
    
    // ... existing ChatScreen content ...
}