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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Use wildcard import for brevity
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.* // Use wildcard import for brevity
import androidx.compose.material3.SnackbarDuration
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
import com.example.whiz.ui.components.MarkdownText
import com.example.whiz.ui.viewmodels.ChatViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import com.example.whiz.services.BubbleOverlayService

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.material.icons.filled.Refresh
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.round
import androidx.compose.ui.res.painterResource
import com.example.whiz.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.whiz.ui.components.OverlayPermissionDialog
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

// Helper data class for the tuple
private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

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
    
    // 🔧 DEBUG: Track animation timing for test vs production comparison
    val animationStartTime = remember { System.currentTimeMillis() }
    
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
                        delay(delay.toLong()) // Initial stagger delay (0ms, 200ms, 400ms)
                        while (isAnimating) {
                            val cycleStart = System.currentTimeMillis()
                            dotVisible = true
                            
                            // 🔧 NON-BLOCKING WAIT: Use system time + yielding (immune to test framework acceleration)
                            val visibleStart = System.currentTimeMillis()
                            while (System.currentTimeMillis() - visibleStart < 600L && isAnimating) {
                                delay(16L) // Yield every frame (~60fps) - allows other coroutines to run
                            }
                            
                            // Early exit if animation stopped during visible phase
                            if (!isAnimating) break
                            
                            dotVisible = false
                            val dimStart = System.currentTimeMillis()
                            while (System.currentTimeMillis() - dimStart < 600L && isAnimating) {
                                delay(16L) // Yield every frame
                            }
                            
                            val cycleEnd = System.currentTimeMillis()
                            val actualCycleDuration = cycleEnd - cycleStart
                            val expectedDuration = 1200L // 600ms visible + 600ms dim
                            
                            Log.d("TypingIndicator", "🎬 Dot $i animation cycle: ${actualCycleDuration}ms (expected: ${expectedDuration}ms)")
                            
                            // Early exit if animation stopped during dim phase
                            if (!isAnimating) break
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
    
    // 🔧 DEBUG: Log total animation duration when component is disposed
    DisposableEffect(Unit) {
        onDispose {
            val totalDuration = System.currentTimeMillis() - animationStartTime
            Log.d("TypingIndicator", "🎬 TypingIndicator total duration: ${totalDuration}ms")
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
    shouldShowMuteButton: Boolean = false, // Computed state for when to show mute button
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
    
    // 🔧 PRODUCTION BUG FIX: Ensure displayValue recomposes correctly
    // The issue was complex conditional logic that wasn't recomposing properly
    val displayValue = when {
        inputText.isNotBlank() -> inputText // Always show actual input text if present
        isListening && transcription.isNotBlank() -> transcription // Show live transcription when listening
        else -> inputText // Default to input text (could be empty)
    }
    
    val placeholderText = if ((isListening || shouldShowMuteButton) && inputText.isBlank()) "Listening..." else if (isSpeaking && inputText.isBlank()) "Speaking..." else "Type or tap mic..."
    


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
                    // Always allow input change - this enables manual typing to disable continuous listening
                    // The updateInputText method will handle stopping voice recognition when user types
                    onInputChange(newValue)
                },
                modifier = Modifier
                    .fillMaxWidth() // TextField fills the Box
                    .semantics {
                        contentDescription = "Message input field"
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
                    // Button logic with seamless interrupt support and TTS interrupt behavior
                    val (icon, description, action, tint) = when {
                        hasTypedText -> {
                            // PRIORITY 1: Show send button for typed text (always needs manual send)
                            // This must come first to override listening/responding states
                            Tuple4(
                                Icons.Filled.Send,
                                "Send typed message",
                                onSendClick,
                                MaterialTheme.colorScheme.onSurface
                            )
                        }
                        isSpeaking && isListening -> {
                            // PRIORITY 2a: Full-duplex mode - both TTS and mic active
                            // Show MicOff to let user stop listening (mic IS on, tap to mute)
                            Tuple4(
                                Icons.Filled.MicOff,
                                "Listening while speaking",
                                onMicClick,
                                MaterialTheme.colorScheme.error
                            )
                        }
                        isSpeaking -> {
                            // PRIORITY 2b: TTS only, mic off - show mic to start listening/interrupt
                            Tuple4(
                                Icons.Filled.Mic,
                                "Interrupt and speak",
                                onMicClickDuringTTS,
                                MaterialTheme.colorScheme.onSurface
                            )
                        }
                        shouldShowMuteButton -> {
                            Tuple4(
                                Icons.Filled.MicOff,
                                "Stop listening",
                                onMicClick,
                                MaterialTheme.colorScheme.error
                            )
                        }
                        isResponding && shouldShowMuteButton -> {
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
                                MaterialTheme.colorScheme.onSurface
                            )
                        }
                        hasVoiceText && !isContinuousListeningEnabled -> {
                            // Show send button for voice text when continuous listening is OFF
                            Tuple4(
                                Icons.Filled.Send,
                                "Send voice message",
                                onSendClick,
                                MaterialTheme.colorScheme.onSurface
                            )
                        }
                        shouldShowMuteButton && !hasInputText -> {
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
                                MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    // Debug logging for button decision
                    val buttonInstanceId = "BTN_${System.currentTimeMillis()}_${hashCode()}"
                    Log.d("ChatInputBar", "🎯 Button decision: description='$description', icon=${icon.name}")
                    Log.d("ChatInputBar", "🔍 BUTTON INSTANCE: Creating button ID='$buttonInstanceId' with description='$description'")
                    Log.d("ChatInputBar", "🔍 BUTTON CONTEXT: hasTypedText=$hasTypedText, hasVoiceText=$hasVoiceText, isResponding=$isResponding")
                    Log.d("ChatInputBar", "🎤 VOICE_STATE_DEBUG: isListening=$isListening, isContinuousListeningEnabled=$isContinuousListeningEnabled, isSpeaking=$isSpeaking, shouldShowMuteButton=$shouldShowMuteButton")

                    val isButtonEnabled = when {
                        hasTypedText -> true  // Send button for typed text is ALWAYS enabled
                        hasVoiceText -> true  // Send button for voice text is ALWAYS enabled
                        isSpeaking -> true    // Mic button during TTS is ALWAYS enabled (allows interrupt)
                        isListening -> !isMicDisabled
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
                            contentDescription = null, // Remove duplicate accessibility description
                            tint = if (isButtonEnabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun ChatLoadErrorView(
    onRetry: () -> Unit,
    onGoBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Error icon
        Icon(
            painter = painterResource(id = R.drawable.robot_error),
            contentDescription = null,
            modifier = Modifier
                .size(128.dp)
                .padding(bottom = 16.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        // Error message
        Text(
            text = "Couldn't load this chat",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // Retry button (prominent)
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("Retry")
        }
        
        // Go back link (secondary)
        TextButton(
            onClick = onGoBack,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text("Go back", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    chatId: Long,
    onChatsListClick: () -> Unit,
    permissionManager: com.example.whiz.permissions.PermissionManager,
    voiceManager: VoiceManager,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(
        viewModelStoreOwner = LocalContext.current as androidx.activity.ComponentActivity
    ),
    navController: NavController,
    onViewModelReady: ((ChatViewModel) -> Unit)? = null // Test hook for accessing navigation-scoped ViewModel
) {
    // Handle permission state - moved to top
    var showPermissionDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // Call the test hook once when ViewModel is ready
    LaunchedEffect(viewModel) {
        onViewModelReady?.invoke(viewModel)
        // Set up the permission request callback
        viewModel.onRequestMicrophonePermission = onRequestPermission
    }

    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    android.util.Log.d("ChatScreen", "Composed with isAuthenticated=$isAuthenticated")

    // Navigate to login immediately if not authenticated
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            android.util.Log.d("ChatScreen", "User not authenticated, navigating to login")
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true } // Clear entire back stack
                launchSingleTop = true
            }
        }
    }

    // Check for TTS mode flag (full voice experience: speech recognition + TTS responses)
    val enableTTSMode = navController.currentBackStackEntry?.savedStateHandle?.get<Boolean>("ENABLE_VOICE_MODE") ?: false
    val initialTranscription = navController.currentBackStackEntry?.savedStateHandle?.get<String>("INITIAL_TRANSCRIPTION")
    Log.d("ChatScreen", "Composed with enableTTSMode=$enableTTSMode, initialTranscription=$initialTranscription, hasPermission=$hasPermission")

    // Observe FORCE_NEW_CHAT signal from power button long-press
    // Using getStateFlow to reactively observe savedStateHandle changes
    val forceNewChatTimestamp by navController.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow("FORCE_NEW_CHAT", 0L)
        ?.collectAsState() ?: remember { mutableStateOf(0L) }
    
    // ViewModel state collections
    val viewModelChatId by viewModel.chatId.collectAsState()

    LaunchedEffect(viewModelChatId) {
        Log.d("ChatScreen", "🔥 UI_DEBUG: ViewModel chat ID changed to $viewModelChatId (provided chatId=$chatId)")
    }
    
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    
    LaunchedEffect(messages) {
        Log.d("ChatScreen", "🔥 UI_DEBUG: Messages collection changed! Now have ${messages.size} messages for chatId=$chatId (vm chatId=$viewModelChatId)")
        messages.forEachIndexed { index, msg ->
            Log.d("ChatScreen", "🔥 UI_DEBUG: Message[$index]: ${msg.type} - ${msg.content.take(50)}...")
        }
    }
    Log.d("ChatScreen", "🔥 UI_DEBUG: ChatScreen recomposed with ${messages.size} messages, chatId=$chatId, viewModelChatId=$viewModelChatId, viewModel=${viewModel.hashCode()}")
    val inputText by viewModel.inputText.collectAsState()
    val isInputFromVoice by viewModel.isInputFromVoice.collectAsState()
    val chatTitle by viewModel.chatTitle.collectAsState()
    val isResponding by viewModel.isResponding.collectAsState() // Agent thinking/fetching
    val connectionError by viewModel.connectionError.collectAsState() // General connection errors
    val authErrorMessage by viewModel.showAuthErrorDialog.collectAsState() // For API key/specific auth dialogs
    val navigateToLogin by viewModel.navigateToLogin.collectAsState() // For forced login navigation
    val showAsanaSetupDialog by viewModel.showAsanaSetupDialog.collectAsState() // Collect new state
    val showOverlayPermissionDialog by viewModel.showOverlayPermissionDialog.collectAsState()
    val isVoiceResponseEnabled by viewModel.isVoiceResponseEnabled.collectAsState()
    val chatLoadError by viewModel.chatLoadError.collectAsState() // Collect chat load error state
    val isRefreshing by viewModel.isRefreshing.collectAsState() // For pull-to-refresh
    


    // Voice state from VoiceManager (clean separation)
    val isListening by voiceManager.isListening.collectAsState()
    val transcription by voiceManager.transcriptionState.collectAsState()
    val speechError by voiceManager.speechError.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState() // TTS actively speaking
    val isContinuousListeningEnabled by voiceManager.isContinuousListeningEnabled.collectAsState() // Track continuous listening mode

    // NOTE: FLAG_KEEP_SCREEN_ON is now managed by MainActivity lifecycle observer
    // to prevent the flag from being cleared during activity/composable transitions

    // UI State
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val scrollTrigger by viewModel.scrollTrigger.collectAsState()

    // 🔧 AUTO-SCROLL: stickToBottom approach.
    // Tracks user scroll intent independently of layout changes.
    // isProgrammaticScroll guards against our own scrollToItem calls flipping stickToBottom off.
    var stickToBottom by remember { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Reset stickToBottom when switching chats
    LaunchedEffect(viewModelChatId) {
        stickToBottom = true
    }

    // Monitor user scroll to update stickToBottom.
    // Check when scroll ends (not during) to avoid jitter. Only care about user gestures.
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (wasScrolling && !scrolling && !isProgrammaticScroll) {
                    // Scroll just ended — check if user is at the bottom
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                    val totalItems = layoutInfo.totalItemsCount
                    if (lastVisibleItem != null && totalItems > 0) {
                        // At bottom = the very last item in the list is visible on screen
                        val atBottom = lastVisibleItem.index >= totalItems - 1
                        val prev = stickToBottom
                        stickToBottom = atBottom
                        if (prev != atBottom) {
                            android.util.Log.d("ChatScreen", "📜 STICK-TO-BOTTOM changed: $prev -> $atBottom (lastVisible=${lastVisibleItem.index}, totalItems=$totalItems)")
                        }
                    }
                }
                wasScrolling = scrolling
            }
    }

    // Scroll to bottom when new content arrives and stickToBottom is true
    LaunchedEffect(scrollTrigger, messages.size) {
        if (scrollTrigger > 0 && messages.isNotEmpty() && stickToBottom) {
            // Wait for LazyColumn to lay out all items including spacer (or timeout after 500ms)
            withTimeoutOrNull(500L) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it >= messages.size + 1 }
            }
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                isProgrammaticScroll = true
                listState.animateScrollToItem(lastIndex)
                isProgrammaticScroll = false
                android.util.Log.d("ChatScreen", "📜 AUTO-SCROLL: Scrolled to index $lastIndex (trigger=$scrollTrigger, stickToBottom=$stickToBottom)")
            }
        }
    }

    // Observe permission state directly from PermissionManager for reactive UI updates
    val hasPermissionReactive by permissionManager.microphonePermissionGranted.collectAsState()
    
    // Use reactive permission state for UI decisions (dialog, mic button, etc.)
    val effectiveHasPermission = hasPermissionReactive
    
    // Compute microphone button state based on all conditions
    val shouldShowMuteButton = isListening || isContinuousListeningEnabled
    
    // Compute TTS state - should be enabled for voice launches
    val shouldEnableTTS = enableTTSMode && effectiveHasPermission
    
    // Debug log UI state changes
    Log.d("ChatScreen", "🎤 UI_STATE_DEBUG: isListening=$isListening, isContinuousListeningEnabled=$isContinuousListeningEnabled, isSpeaking=$isSpeaking, shouldShowMuteButton=$shouldShowMuteButton, shouldEnableTTS=$shouldEnableTTS")
    
    // Enable TTS immediately for voice launches (without delay that can be interrupted)
    LaunchedEffect(shouldEnableTTS) {
        if (shouldEnableTTS && !isVoiceResponseEnabled) {
            viewModel.toggleVoiceResponse()
            voiceManager.setVoiceResponseEnabled(true)
        }
    }
    
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
        if (BubbleOverlayService.isActive) {
            return
        }
        if (!effectiveHasPermission) {
            showPermissionDialog = true
        } else {
            // Use VoiceManager for clean voice coordination
            voiceManager.toggleContinuousListening()
        }
    }
    
    // Function to copy message to clipboard
    fun copyMessageToClipboard(message: MessageEntity) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("Chat Message", message.content)
        clipboardManager.setPrimaryClip(clipData)
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


    // Load initial chat data
    LaunchedEffect(chatId) {
        Log.d("ChatScreen", "🔥 UI_DEBUG: Initial load - navigation chatId=$chatId")
        // Use TTS-mode-aware loading if TTS mode is enabled
        if (enableTTSMode) {
            viewModel.loadChatWithVoiceMode(chatId, true)
        } else {
            viewModel.loadChat(chatId)
        }
    }

    // React to force new chat signal from power button long-press while app is open
    LaunchedEffect(forceNewChatTimestamp) {
        if (forceNewChatTimestamp > 0L) {
            Log.d("ChatScreen", "🔄 FORCE_NEW_CHAT signal received (timestamp=$forceNewChatTimestamp), resetting to new chat")
            viewModel.loadChatWithVoiceMode(-1L, true)
            // Clear the signal after handling
            navController.currentBackStackEntry?.savedStateHandle?.remove<Long>("FORCE_NEW_CHAT")
        }
    }

    // Sync messages when returning to the screen (e.g., from chats list)
    // This ensures we get any messages that arrived while away
    // Track if this is the initial load to avoid double-syncing
    var isInitialLoad by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, chatId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && chatId != -1L && chatId != 0L) {
                // Only sync on resume if this is NOT the initial load
                // Initial load already syncs via loadChat
                if (!isInitialLoad) {
                    Log.d("ChatScreen", "🔄 Screen resumed after navigation - syncing messages for chat $chatId")
                    viewModel.syncMessagesIfNeeded(chatId)
                } else {
                    Log.d("ChatScreen", "🔄 Screen resumed for initial load - skipping sync (loadChat handles it)")
                    isInitialLoad = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Handle when ViewModel's chat ID changes (e.g., after new chat creation)
    // Track previous viewModelChatId to distinguish real migrations (new chat → server ID)
    // from stale state (screen re-composed while ViewModel still has old chat ID)
    var previousViewModelChatId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(viewModelChatId) {
        val prev = previousViewModelChatId
        previousViewModelChatId = viewModelChatId
        // Only migrate if the PREVIOUS value was -1 (we were in new chat state)
        // and the current value is a real chat ID. This prevents stale migration when
        // the screen first composes with an old viewModelChatId from a previous chat.
        if (prev == -1L && chatId == -1L && viewModelChatId != -1L && viewModelChatId != chatId) {
            Log.d("ChatScreen", "🔥 UI_DEBUG: Chat ID migrated in ViewModel. Old: $chatId, New: $viewModelChatId")
            // This is a migration (new chat creation), not a chat switch
            // Use migrateChatId to avoid disconnecting WebSocket
            viewModel.migrateChatId(chatId, viewModelChatId)
        }
    }

    // 🔧 AUTO-SCROLL: Moved to MessagesList composable to use deduplicatedMessages.size
    // (Scrolling based on messages.size can fail if UI deduplication changes the count)

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

    // Track whether we've already initialized voice settings for this screen instance
    // This prevents re-initialization during chat migration (chatId changes)
    val voiceInitialized = remember { mutableStateOf(false) }

    // Voice app behavior: enable microphone for all chats on FIRST launch only
    // Don't override user's preference if they've manually toggled it
    // IMPORTANT: Don't re-initialize during chat migration (when chatId changes from -1 to optimistic ID)
    LaunchedEffect(enableTTSMode, effectiveHasPermission) {
        // Only initialize voice settings ONCE per screen instance
        // Skip if already initialized (prevents override during chat migration)
        if (voiceInitialized.value) {
            return@LaunchedEffect
        }

        // Only enable continuous listening automatically for NEW chats (optimistic negative IDs)
        // For existing chats, respect the user's current continuous listening state
        if (effectiveHasPermission && viewModelChatId < 0) {
            voiceManager.updateContinuousListeningEnabled(true)
            viewModel.ensureContinuousListeningEnabled()
            voiceInitialized.value = true
        } else if (!effectiveHasPermission) {
            // No microphone permission - voice setup skipped
        } else {
            voiceInitialized.value = true // Mark as initialized so we don't override later
        }

        // Note: TTS enabling is now handled by the separate LaunchedEffect above
        // using computed state to avoid coroutine cancellation issues
    }

    // Re-enable voice when FORCE_NEW_CHAT signal is received (power button long-press while app is open)
    // This must be after voiceInitialized is defined
    LaunchedEffect(forceNewChatTimestamp) {
        if (forceNewChatTimestamp > 0L) {
            Log.d("ChatScreen", "🔄 FORCE_NEW_CHAT: Re-enabling voice for new chat")

            // Reset voice initialization flag so voice can be re-enabled
            voiceInitialized.value = false

            // Re-enable continuous listening for the new chat (same as initial voice launch)
            if (effectiveHasPermission) {
                Log.d("ChatScreen", "🔄 FORCE_NEW_CHAT: Re-enabling continuous listening")
                voiceManager.updateContinuousListeningEnabled(true)
                viewModel.ensureContinuousListeningEnabled()
                voiceInitialized.value = true
            }
        }
    }

    // Collect transcriptions from VoiceManager flow
    // Only process when bubble is NOT active (bubble has its own transcription handling)
    LaunchedEffect(Unit) {
        voiceManager.transcriptionFlow.distinctUntilChanged().collect { transcription ->
            if (transcription.isNotBlank() && !BubbleOverlayService.isActive) {
                viewModel.updateInputText(transcription, fromVoice = true)
                viewModel.sendUserInput(transcription)
            } else if (transcription.isNotBlank()) {
                // Ignoring transcription - bubble is active
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
                IconButton(
                    onClick = onChatsListClick,
                    modifier = Modifier.semantics { contentDescription = "Open Chats List" }
                ) {
                    Icon(Icons.Default.Menu, contentDescription = null)
                }
            },
            actions = {
                IconButton(
                    onClick = viewModel::toggleVoiceResponse,
                    modifier = Modifier.semantics { 
                        contentDescription = if (isVoiceResponseEnabled) "Disable Voice Response" else "Enable Voice Response"
                    }
                ) {
                    Icon(
                        imageVector = if (isVoiceResponseEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = null,
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
        // Use key to force recomposition when effective chat ID changes
        Box(
            modifier = Modifier.weight(1f)
        ) {
            key(chatId) {
                when {
                    // Show error state if there's a chat load error
                    chatLoadError != null -> {
                        Log.d("ChatScreen", "🔥 UI_DEBUG: Showing ChatLoadErrorView - error: $chatLoadError")
                        ChatLoadErrorView(
                            onRetry = {
                                viewModel.retryChatLoad()
                            },
                            onGoBack = {
                                onChatsListClick()
                            }
                        )
                    }
                    // Show empty placeholder when no messages
                    messages.isEmpty() && !isResponding && !isSpeaking -> {
                        Log.d("ChatScreen", "🔥 UI_DEBUG: Showing EmptyChatPlaceholder - messages.isEmpty()=${messages.isEmpty()}, isResponding=$isResponding, isSpeaking=$isSpeaking, chatId=$chatId")
                        EmptyChatPlaceholder()
                    }
                    // Show messages list normally
                    else -> {
                        Log.d("ChatScreen", "🔥 UI_DEBUG: Showing MessagesList with ${messages.size} messages for chatId=$chatId")
                        
                        // Wrap MessagesList with SwipeRefresh
                        val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = isRefreshing)
                        SwipeRefresh(
                            state = swipeRefreshState,
                            onRefresh = { viewModel.refreshMessages() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            MessagesList(
                                messages = messages,
                                listState = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isSpeaking) Color.Black.copy(alpha = 0.03f) else Color.Transparent),
                                showTypingIndicator = isResponding && !isSpeaking,
                                onLongPressMessage = ::copyMessageToClipboard
                            )
                        }
                    }
                }
            }
        }
        
        // Input bar - directly placed at bottom, no Scaffold bottomBar wrapper
        // Hide input bar when there's a chat load error
        if (chatLoadError == null) {
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
                shouldShowMuteButton = shouldShowMuteButton,
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

    // Dialog for overlay permission
    if (showOverlayPermissionDialog) {
        OverlayPermissionDialog(
            onDismiss = { viewModel.dismissOverlayPermissionDialog() },
            onRequestPermission = {
                // Open system settings for overlay permission
                val appPackageName = context.packageName
                Log.d("ChatScreen", "Requesting overlay permission for package: $appPackageName")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$appPackageName")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.d("ChatScreen", "Starting activity with URI: ${intent.data}")
                context.startActivity(intent)
                viewModel.requestOverlayPermission()
            }
        )
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
    showTypingIndicator: Boolean = false,
    onLongPressMessage: ((MessageEntity) -> Unit)? = null
) {
    android.util.Log.d("MessagesList", "🔥 MESSAGES_LIST_RECOMPOSE: Received ${messages.size} messages, listState.firstVisibleItemIndex=${listState.firstVisibleItemIndex}")

    // NOTE: Deduplication is handled by ChatViewModel - no UI-level deduplication needed
    // NOTE: Auto-scroll is now handled in ChatScreen via viewModel.scrollTrigger

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth() // Only fill width, not height - prevents overlay
            .semantics {
                contentDescription = "Chat messages list"
            },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(messages, key = { message ->
            // Use message ID as key for stable identity
            message.id
        }) { message ->
            MessageItem(
                message = message,
                onLongPress = onLongPressMessage
            )
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
fun MessageItem(
    message: MessageEntity,
    onLongPress: ((MessageEntity) -> Unit)? = null
) {
    val isUserMessage = message.type == MessageType.USER
    
    val backgroundColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.surface  // White background for user messages
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer  // Light yellow from theme
    }
    val textColor = when (message.type) {
        MessageType.USER -> MaterialTheme.colorScheme.onSurface  // Black text for user messages
        MessageType.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer  // Black text from theme
    }
    val alignment = if (isUserMessage) Alignment.CenterEnd else Alignment.CenterStart
    
    // Custom selection colors for user messages to ensure visibility
    val customSelectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,  // Yellow handle
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)  // Semi-transparent yellow
    )

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
                CompositionLocalProvider(LocalTextSelectionColors provides customSelectionColors) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            modifier = Modifier.semantics {
                                contentDescription = if (message.type == MessageType.USER) {
                                    "User message: ${message.content}"
                                } else {
                                    "Assistant message: ${message.content}"
                                }
                            }
                        )
                    }
                }
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

