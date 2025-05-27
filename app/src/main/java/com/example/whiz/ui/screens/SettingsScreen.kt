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
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                onSaveClick = { viewModel.saveVoiceSettings(localVoiceSettings) },
                onTestPlayback = { viewModel.testVoiceSettings(localVoiceSettings) },
                isSaving = isSavingVoiceSettings
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

    var previousIsBusy by remember { mutableStateOf(isBusy) } // Track previous busy state

    // Reset local operation trackers when isBusy becomes false
    // Also, handle UI changes post-operation
    LaunchedEffect(isBusy, previousIsBusy, saveOperationInitiated, clearOperationInitiated, inputValue, hasToken) {
        Log.d("TokenInputSection", "[$title] LaunchedEffect(isBusy Triggered). isBusy: $isBusy, previousIsBusy: $previousIsBusy, saveOpInit: $saveOperationInitiated, clearOpInit: $clearOperationInitiated, hasToken: $hasToken")
        if (previousIsBusy && !isBusy) { // Operation just finished
            if (saveOperationInitiated) {
                Log.d("TokenInputSection", "[$title] Save operation finished. inputValue: '$inputValue'")
                // If a non-blank token was saved, we want to exit edit mode.
                // The hasToken check ensures we don't prematurely exit editMode if the token status is still false/null after a failed save.
                if (inputValue.isNotBlank() && hasToken == true) {
                    Log.d("TokenInputSection", "[$title] Save successful for non-blank token and hasToken is true. Setting editMode = false.")
                    editMode = false
                }
                saveOperationInitiated = false
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
                        Icon(Icons.Default.CheckCircle, contentDescription = "Token set", tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Token is set.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onInputChange(""); editMode = true },
                            enabled = !isBusy
                        ) {
                            Text("Change")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { clearOperationInitiated = true; onClearClick() },
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            if (isBusy && clearOperationInitiated) {
                                Log.d("TokenInputSection", "[$title] Displaying: Clear button (busy)")
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Log.d("TokenInputSection", "[$title] Displaying: Clear button (idle)")
                                Text("Clear")
                            }
                        }
                    } else {
                        Log.d("TokenInputSection", "[$title] Displaying: Edit mode for existing token")
                        // In edit mode for an existing token (user clicked "Change")
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
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
                                Button(
                                    onClick = { editMode = false }, // Cancel edit mode
                                    enabled = !isBusy,
                                    colors = ButtonDefaults.outlinedButtonColors()

                                ) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { saveOperationInitiated = true; onSaveClick() },
                                    enabled = !isBusy && inputValue.isNotBlank()
                                ) {
                                    if (isBusy && saveOperationInitiated) {
                                        Log.d("TokenInputSection", "[$title] Displaying: Save button (busy, in editMode for existing token)")
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else {
                                        Log.d("TokenInputSection", "[$title] Displaying: Save button (idle, in editMode for existing token)")
                                        Text("Save $title")
                                    }
                                }
                            }
                        }
                    }
                }
                false -> { // Token not set - Show input form (editMode will be true here)
                    Log.d("TokenInputSection", "[$title] State: hasToken is false. editMode: $editMode (should be true)")
                    Column(modifier = Modifier.fillMaxWidth()) { // Make column take full width for alignment
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
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
                        Button(
                            onClick = { saveOperationInitiated = true; onSaveClick() },
                            enabled = !isBusy && inputValue.isNotBlank(),
                            modifier = Modifier.align(Alignment.End) // Align button to the right
                        ) {
                            if (isBusy && saveOperationInitiated) {
                                Log.d("TokenInputSection", "[$title] Displaying: Save button (busy, token not set)")
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Log.d("TokenInputSection", "[$title] Displaying: Save button (idle, token not set)")
                                Text("Save $title")
                            }
                        }
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
    onSaveClick: () -> Unit,
    onTestPlayback: () -> Unit,
    isSaving: Boolean
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
                    valueRange = 0.5f..2.0f,
                    steps = 29, // 0.5 to 2.0 in 0.05 increments
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

        // Save Button
        Button(
            onClick = onSaveClick,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Saving...")
            } else {
                Text("Save Voice Settings")
            }
        }
    }
}