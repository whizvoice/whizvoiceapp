package com.example.whiz.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whiz.ui.viewmodels.AccessibilityViewModel
import kotlinx.coroutines.launch
import com.example.whiz.ui.components.AccessibilityPermissionDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilityScreen(
    viewModel: AccessibilityViewModel = hiltViewModel()
) {
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPermissionDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        viewModel.refreshAccessibilityStatus()
        viewModel.loadInstalledApps()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility Controls") },
                navigationIcon = {
                    IconButton(onClick = { /* Handle back navigation */ }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAccessibilityEnabled) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                if (isAccessibilityEnabled) Icons.Default.CheckCircle 
                                else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (isAccessibilityEnabled) 
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = if (isAccessibilityEnabled) 
                                    "Accessibility Service Enabled" 
                                else "Accessibility Service Disabled",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isAccessibilityEnabled) 
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        
                        if (!isAccessibilityEnabled) {
                            Text(
                                text = "Enable the accessibility service to control other apps",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            
                            Button(
                                onClick = { showPermissionDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Enable Accessibility Service")
                            }
                        }
                    }
                }
            }
            
            if (isAccessibilityEnabled) {
                item {
                    Text(
                        text = "Quick Actions",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (viewModel.openWhatsApp()) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Opening WhatsApp")
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("WhatsApp not installed or cannot be opened")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("WhatsApp")
                        }
                        
                        OutlinedButton(
                            onClick = { viewModel.performHomeAction() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Home")
                        }
                    }
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.performBackAction() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Back")
                        }
                        
                        OutlinedButton(
                            onClick = { viewModel.performRecentAppsAction() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Apps, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Recent")
                        }
                    }
                }
                
                item {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                }
                
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Installed Apps",
                            style = MaterialTheme.typography.titleLarge
                        )
                        
                        IconButton(onClick = { viewModel.loadInstalledApps() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
                
                items(installedApps) { app ->
                    Card(
                        onClick = {
                            if (viewModel.openApp(app.packageName)) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Opening ${app.appName}")
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("Cannot open ${app.appName}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Open",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showPermissionDialog) {
        AccessibilityPermissionDialog(
            onDismiss = { showPermissionDialog = false },
            onOpenSettings = { viewModel.openAccessibilitySettings() }
        )
    }
}