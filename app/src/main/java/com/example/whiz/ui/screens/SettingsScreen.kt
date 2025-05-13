package com.example.whiz.ui.screens

import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.launch
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()

    // States from ViewModel
    val hasClaudeToken by viewModel.hasClaudeToken.collectAsState()
    val hasAsanaToken by viewModel.hasAsanaToken.collectAsState()
    val isSavingClaude by viewModel.isSavingClaude.collectAsState()
    val isSavingAsana by viewModel.isSavingAsana.collectAsState()

    // Input states
    var claudeTokenInput by rememberSaveable { mutableStateOf("") }
    var asanaTokenInput by rememberSaveable { mutableStateOf("") }

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
                .padding(16.dp),
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
                onClearClick = { viewModel.saveClaudeToken("") }
            )

            // Asana Access Token Section
            TokenInputSection(
                title = "Asana Access Token",
                hasToken = hasAsanaToken,
                isBusy = isSavingAsana,
                inputValue = asanaTokenInput,
                onInputChange = { asanaTokenInput = it },
                onSaveClick = { viewModel.saveAsanaToken(asanaTokenInput) },
                onClearClick = { viewModel.saveAsanaToken("") }
            )

            Spacer(modifier = Modifier.weight(1f)) // Push logout to bottom

            // Logout Section
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
    onClearClick: () -> Unit // New parameter for clear action
) {
    var editMode by rememberSaveable(hasToken) {
        val initialEditMode = hasToken == false
        Log.d("TokenInputSection", "[$title] Initializing editMode. hasToken: $hasToken, initialEditMode: $initialEditMode")
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