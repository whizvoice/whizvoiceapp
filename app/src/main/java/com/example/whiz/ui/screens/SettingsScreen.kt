package com.example.whiz.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.whiz.R
import com.example.whiz.ui.viewmodels.SettingsViewModel
import com.example.whiz.ui.navigation.Screen
import com.example.whiz.data.preferences.VoiceSettings
import com.example.whiz.ui.components.CancelButton
import com.example.whiz.ui.components.ClearButton
import com.example.whiz.ui.components.LoadingButton
import com.example.whiz.ui.components.SaveButton
import kotlinx.coroutines.launch
import android.util.Log
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
    focusSection: String? = null
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    val navigateToLogin by viewModel.navigateToLogin.collectAsState()

    // States from ViewModel
    val hasClaudeToken by viewModel.hasClaudeToken.collectAsState()
    val hasAsanaToken by viewModel.hasAsanaToken.collectAsState()
    val voiceSettings by viewModel.voiceSettings.collectAsState()
    val isSavingClaude by viewModel.isSavingClaude.collectAsState()
    val isSavingAsana by viewModel.isSavingAsana.collectAsState()
    val isSavingVoiceSettings by viewModel.isSavingVoiceSettings.collectAsState()
    val isHardSyncing by viewModel.isHardSyncing.collectAsState()

    // Input states
    var claudeTokenInput by rememberSaveable { mutableStateOf("") }
    var asanaTokenInput by rememberSaveable { mutableStateOf("") }
    
    // Voice settings local state
    var localVoiceSettings by remember(voiceSettings) { mutableStateOf(voiceSettings) }
    
    // Auto-save voice settings when they change, but with debouncing for slider interactions
    LaunchedEffect(localVoiceSettings) {
        // Only save if the settings have actually changed from the server state
        // The sliders will handle their own saving via onValueChangeFinished
        if (localVoiceSettings != voiceSettings && localVoiceSettings.useSystemDefaults != voiceSettings.useSystemDefaults) {
            // Only auto-save for the toggle switch, not sliders
            Log.d("SettingsScreen", "Auto-saving voice settings (toggle change): $localVoiceSettings")
            viewModel.saveVoiceSettings(localVoiceSettings)
        }
    }
    
    // Debug logging and sync local state with ViewModel state
    LaunchedEffect(voiceSettings) {
        Log.d("SettingsScreen", "Voice settings from ViewModel changed: $voiceSettings")
        localVoiceSettings = voiceSettings
    }
    
    // Debug log current local state
    Log.d("SettingsScreen", "Current localVoiceSettings: $localVoiceSettings, ViewModel voiceSettings: $voiceSettings")

    // Refresh voice settings when screen opens
    LaunchedEffect(Unit) {
        Log.d("SettingsScreen", "SettingsScreen opened, refreshing voice settings")
        viewModel.refreshVoiceSettings()
    }

    // Navigate to Login if required
    LaunchedEffect(navigateToLogin) {
        if (navigateToLogin) {
            Log.d("SettingsScreen", "navigateToLogin triggered, navigating to Login screen.")
            navController.navigate(Screen.Login.route) {
                // Clear back stack up to Home or another appropriate root to prevent going back to Settings
                popUpTo(Screen.Home.route) { inclusive = true }
                launchSingleTop = true
            }
            viewModel.onLoginNavigationComplete() // Reset the state after navigation
        }
    }

    // Effect to show Snackbar messages as one-time events
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            launch { // Launch coroutine for Snackbar
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Long // Use Long for important messages
                )
                viewModel.clearErrorMessage() // Consume the message
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.semantics { contentDescription = "Back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // Claude API Key Section
            Text("API Keys", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(thickness = Dp.Hairline)

            TokenInputSection(
                title = "Claude API Key",
                hasToken = hasClaudeToken,
                isBusy = isSavingClaude,
                inputValue = claudeTokenInput,
                onInputChange = { claudeTokenInput = it },
                onSaveClick = { viewModel.saveClaudeToken(claudeTokenInput) },
                onClearClick = { viewModel.saveClaudeToken("") },
                startInEditMode = focusSection == "claude"
            )

            // Asana Access Token Section
            TokenInputSection(
                title = "Asana Access Token",
                hasToken = hasAsanaToken,
                isBusy = isSavingAsana,
                inputValue = asanaTokenInput,
                onInputChange = { asanaTokenInput = it },
                onSaveClick = { viewModel.saveAsanaToken(asanaTokenInput) },
                onClearClick = { viewModel.saveAsanaToken("") },
                startInEditMode = focusSection == "asana"
            )

            // Voice Settings Section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Voice Settings", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(thickness = Dp.Hairline)
            
            VoiceSettingsSection(
                settings = localVoiceSettings,
                onSettingsChange = { localVoiceSettings = it },
                onTestPlayback = { viewModel.testVoiceSettings(localVoiceSettings) },
                isSaving = isSavingVoiceSettings,
                onSaveSettings = { viewModel.saveVoiceSettings(localVoiceSettings) }
            )

            // Subscription Section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Subscription", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(thickness = Dp.Hairline)
            
            SubscriptionSection(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth()
            )
            
            // Data Management Section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Data Management", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(thickness = Dp.Hairline)

            // Hard Sync Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Force Full Sync",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Clear local sync timestamps and re-download all data from server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = { viewModel.performHardSync() },
                    enabled = !isHardSyncing,
                    modifier = Modifier.wrapContentWidth()
                ) {
                    if (isHardSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing...")
                    } else {
                        Text("Sync Now")
                    }
                }
            }

            // Logout Section
            Spacer(modifier = Modifier.height(32.dp))
            HorizontalDivider(thickness = Dp.Hairline)
            Button(
                onClick = { viewModel.logout() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Logout")
            }
        }
    }
}

@Composable
fun TokenInputSection(
    title: String,
    hasToken: Boolean?, // Nullable for loading state
    isBusy: Boolean,    // Renamed from isSaving, true if VM is working on this token
    inputValue: String,
    onInputChange: (String) -> Unit,
    onSaveClick: () -> Unit,
    onClearClick: () -> Unit, // New parameter for clear action
    startInEditMode: Boolean = false // Add new parameter with default
) {
    var editMode by rememberSaveable(hasToken, startInEditMode) { // Add startInEditMode to key
        // Prioritize startInEditMode. If true, always start in edit mode.
        // Otherwise, start in edit mode if there's no token.
        val initialEditMode = startInEditMode || (hasToken == false)
        Log.d("TokenInputSection", "[$title] Initializing editMode. hasToken: $hasToken, startInEditMode: $startInEditMode, initialEditMode: $initialEditMode")
        mutableStateOf(initialEditMode)
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // Local states to track which button click initiated the busy state
    var saveOperationInitiated by remember { mutableStateOf(false) }
    var clearOperationInitiated by remember { mutableStateOf(false) }

    Log.d("TokenInputSection", "[$title] Recomposing. hasToken: $hasToken, isBusy: $isBusy, editMode: $editMode, inputValue: '$inputValue', saveOpInit: $saveOperationInitiated, clearOpInit: $clearOperationInitiated")
    
    // Additional debug logging for Clear button issue
    if (hasToken == true && !editMode) {
        Log.d("TokenInputSection", "[$title] Should show Clear button. Token is set and not in edit mode. isBusy=$isBusy")
    }

    var previousIsBusy by remember { mutableStateOf(isBusy) } // Track previous busy state

    // State for optimistic updates
    var optimisticSaveAttempted by remember { mutableStateOf(false) }
    var saveSucceeded by remember { mutableStateOf(false) }
    
    // Reset local operation trackers when isBusy becomes false
    // Also, handle UI changes post-operation
    LaunchedEffect(isBusy, previousIsBusy, saveOperationInitiated, clearOperationInitiated, inputValue, hasToken) {
        Log.d("TokenInputSection", "[$title] LaunchedEffect(isBusy Triggered). isBusy: $isBusy, previousIsBusy: $previousIsBusy, saveOpInit: $saveOperationInitiated, clearOpInit: $clearOperationInitiated, hasToken: $hasToken")
        if (previousIsBusy && !isBusy) { // Operation just finished
            if (saveOperationInitiated) {
                Log.d("TokenInputSection", "[$title] Save operation finished. inputValue: '$inputValue'")
                // Check if the save actually succeeded by looking at hasToken
                saveSucceeded = (inputValue.isNotBlank() && hasToken == true)
                if (!saveSucceeded && optimisticSaveAttempted) {
                    // Save failed, revert the optimistic UI update
                    Log.d("TokenInputSection", "[$title] Save failed, reverting optimistic update. Setting editMode = true.")
                    editMode = true
                }
                saveOperationInitiated = false
                optimisticSaveAttempted = false
            }
            if (clearOperationInitiated) {
                Log.d("TokenInputSection", "[$title] Clear operation finished.")
                // If a clear operation results in hasToken being false, editMode will be set by rememberSaveable.
                // If hasToken is still true (e.g. clear failed), editMode remains as is (likely true if user clicked 'Clear' from 'Token is set' view's editMode).
                // However, 'Clear' button is only available when !editMode and hasToken == true.
                // Clicking 'Clear' sets clearOperationInitiated=true, calls onClearClick (which saves blank token).
                // Then hasToken should become false, and rememberSaveable(hasToken) will set editMode=true.
                clearOperationInitiated = false
            }
        }
        if (previousIsBusy != isBusy) { // Update previousIsBusy only when isBusy actually changes
            previousIsBusy = isBusy
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 56.dp)
        ) { // Ensure consistent height
            when (hasToken) {
                null -> { // Loading state
                    Log.d("TokenInputSection", "[$title] State: hasToken is null (Loading)")
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking status...", style = MaterialTheme.typography.bodyMedium)
                }
                true -> { // Token is set
                    Log.d("TokenInputSection", "[$title] State: hasToken is true. editMode: $editMode")
                    if (!editMode) {
                        Log.d("TokenInputSection", "[$title] Displaying: Token is set (not in editMode)")
                        // Use title-specific content description for better test targeting and accessibility
                        val tokenSetDescription = when(title) {
                            "Claude API Key" -> "Claude token set"
                            "Asana Access Token" -> "Asana token set"
                            else -> "$title token set"
                        }
                        Icon(Icons.Default.CheckCircle, contentDescription = tokenSetDescription, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Token is set.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        val changeEnabled = !isBusy
                        Log.d("TokenInputSection", "[$title] Change button state - enabled: $changeEnabled, isBusy: $isBusy")
                        LoadingButton(
                            text = "Change",
                            onClick = { onInputChange(""); editMode = true },
                            isLoading = false,
                            enabled = changeEnabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // Clear should always be enabled when token exists - user should always be able to remove a token
                        val clearEnabled = true // Always allow clearing when token is set
                        Log.d("TokenInputSection", "[$title] Clear button state - enabled: $clearEnabled, isBusy: $isBusy, clearOpInitiated: $clearOperationInitiated")
                        // Use title-specific content description for Clear button
                        val clearButtonDescription = when(title) {
                            "Claude API Key" -> "Clear Claude token"
                            "Asana Access Token" -> "Clear Asana token"
                            else -> "Clear $title"
                        }
                        ClearButton(
                            onClick = { clearOperationInitiated = true; onClearClick() },
                            isLoading = isBusy && clearOperationInitiated,
                            enabled = clearEnabled,
                            modifier = Modifier.semantics { contentDescription = clearButtonDescription }
                        )
                    } else {
                        Log.d("TokenInputSection", "[$title] Displaying: Edit mode for existing token")
                        // In edit mode for an existing token (user clicked "Change")
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = "$title input field" },
                                value = inputValue,
                                onValueChange = onInputChange,
                                label = { Text("Enter new $title") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                enabled = !isBusy,
                                trailingIcon = {
                                    val image = if (passwordVisible)
                                        Icons.Filled.Visibility
                                    else Icons.Filled.VisibilityOff
                                    val description = if (passwordVisible) "Hide password" else "Show password"
                                    IconButton(onClick = { passwordVisible = !passwordVisible }){
                                        Icon(imageVector  = image, description)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                CancelButton(
                                    onClick = { editMode = false },
                                    enabled = !isBusy
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                SaveButton(
                                    text = "Save $title",
                                    onClick = { 
                                        saveOperationInitiated = true
                                        optimisticSaveAttempted = true
                                        // Optimistic update: exit edit mode immediately
                                        if (inputValue.isNotBlank()) {
                                            Log.d("TokenInputSection", "[$title] Optimistically exiting edit mode")
                                            editMode = false
                                        }
                                        onSaveClick() 
                                    },
                                    isLoading = isBusy && saveOperationInitiated,
                                    enabled = !isBusy && inputValue.isNotBlank(),
                                    modifier = Modifier.semantics { contentDescription = "Save $title button" }
                                )
                            }
                        }
                    }
                }
                false -> { // Token not set - Show input form (editMode will be true here)
                    Log.d("TokenInputSection", "[$title] State: hasToken is false. editMode: $editMode (should be true)")
                    Column(modifier = Modifier.fillMaxWidth()) { // Make column take full width for alignment
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "$title input field" },
                            value = inputValue,
                            onValueChange = onInputChange,
                            label = { Text("Enter $title") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            enabled = !isBusy,
                            trailingIcon = {
                                val image = if (passwordVisible)
                                    Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff
                                val description = if (passwordVisible) "Hide token" else "Show token"
                                IconButton(onClick = { passwordVisible = !passwordVisible }){
                                    Icon(imageVector  = image, description)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SaveButton(
                            text = "Save $title",
                            onClick = { 
                                saveOperationInitiated = true
                                optimisticSaveAttempted = true
                                // Optimistic update: exit edit mode immediately
                                if (inputValue.isNotBlank()) {
                                    Log.d("TokenInputSection", "[$title] Optimistically exiting edit mode")
                                    editMode = false
                                }
                                onSaveClick() 
                            },
                            isLoading = isBusy && saveOperationInitiated,
                            enabled = !isBusy && inputValue.isNotBlank(),
                            modifier = Modifier
                                .align(Alignment.End)
                                .semantics { contentDescription = "Save $title button" }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceSettingsSection(
    settings: VoiceSettings,
    onSettingsChange: (VoiceSettings) -> Unit,
    onTestPlayback: () -> Unit,
    isSaving: Boolean,
    onSaveSettings: (VoiceSettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Use System Defaults Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Use System TTS Settings",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Use your device's default text-to-speech settings instead of custom app settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.useSystemDefaults,
                onCheckedChange = { useSystem ->
                    onSettingsChange(settings.copy(useSystemDefaults = useSystem))
                },
                enabled = !isSaving
            )
        }

        // Custom Settings (only shown when not using system defaults)
        if (!settings.useSystemDefaults) {
            HorizontalDivider(thickness = Dp.Hairline)
            
            // Speech Rate Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Speech Rate",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${(settings.speechRate * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = settings.speechRate,
                    onValueChange = { rate ->
                        onSettingsChange(settings.copy(speechRate = rate))
                    },
                    onValueChangeFinished = {
                        // Auto-save when user finishes adjusting the slider
                        Log.d("VoiceSettingsSection", "Speech rate slider interaction finished, auto-saving")
                        onSaveSettings(settings)
                    },
                    valueRange = 0.5f..3.0f,
                    steps = 49, // 0.5 to 3.0 in 0.05 increments
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Slower ← → Faster",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Pitch Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voice Pitch",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${(settings.pitch * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = settings.pitch,
                    onValueChange = { pitch ->
                        onSettingsChange(settings.copy(pitch = pitch))
                    },
                    onValueChangeFinished = {
                        // Auto-save when user finishes adjusting the slider
                        Log.d("VoiceSettingsSection", "Pitch slider interaction finished, auto-saving")
                        onSaveSettings(settings)
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 29, // 0.5 to 2.0 in 0.05 increments
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Lower ← → Higher",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Test Playback Button (always visible)
        HorizontalDivider(thickness = Dp.Hairline)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Test Voice Settings",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Play a sample to hear how your current settings sound",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onTestPlayback,
                enabled = !isSaving,
                colors = ButtonDefaults.outlinedButtonColors()
            ) {
                Text("Test Playback")
            }
        }
        
        // Subtle saving indicator
        if (isSaving) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Saving settings...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SubscriptionSection(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsState()
    val isLoadingSubscription by viewModel.isLoadingSubscription.collectAsState()
    val isProcessingSubscription by viewModel.isProcessingSubscription.collectAsState()
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            isLoadingSubscription -> {
                // Loading state
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading subscription status...")
                }
            }
            subscriptionStatus?.has_subscription == true -> {
                // User has an active subscription
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Active subscription",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Premium Subscription Active",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        if (subscriptionStatus?.cancel_at_period_end == true) {
                            Text(
                                "Subscription will end on ${formatTimestamp(subscriptionStatus?.current_period_end ?: 0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "Renews on ${formatTimestamp(subscriptionStatus?.current_period_end ?: 0)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        
                        if (subscriptionStatus?.cancel_at_period_end != true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.cancelSubscription() },
                                enabled = !isProcessingSubscription,
                                colors = ButtonDefaults.outlinedButtonColors(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isProcessingSubscription) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Processing...")
                                } else {
                                    Text("Cancel Subscription")
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                // User doesn't have a subscription
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Premium Subscription",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Get unlimited access to all features for $10/month",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        
                        // Benefits list
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BenefitRow("Unlimited conversations")
                            BenefitRow("Priority support")
                            BenefitRow("Advanced features")
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.startSubscription() },
                            enabled = !isProcessingSubscription,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isProcessingSubscription) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Processing...")
                            } else {
                                Text("Subscribe for $10/month")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BenefitRow(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown"
    val date = java.util.Date(timestamp * 1000)
    val format = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return format.format(date)
}