package com.example.whiz.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.data.local.DateFormatter
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    onChatSelected: (Long) -> Unit,
    onNewChatClick: () -> Unit,
    onSettingsClick: () -> Unit,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    viewModel: ChatsListViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isShowingCachedData by viewModel.isShowingCachedData.collectAsState()
    
    // Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Auto-refresh when returning to chat list to sync new chats created via WebSocket
    // This ensures users see chats created in other parts of the app (like voice assistant)
    LaunchedEffect(Unit) {
        viewModel.refreshChats()
    }
    
    // Show snackbar when showing cached data (offline)
    LaunchedEffect(isShowingCachedData) {
        if (isShowingCachedData) {
            snackbarHostState.showSnackbar(
                message = "No connection. Showing offline data",
                duration = androidx.compose.material3.SnackbarDuration.Long
            )
        }
    }
    
    // SwipeRefresh state
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing = isRefreshing)

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("My Chats") },
                actions = {
                    if (!hasPermission) {
                        IconButton(
                            onClick = onRequestPermission,
                            modifier = Modifier.semantics { contentDescription = "Enable Microphone" }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null
                            )
                        }
                    }
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.semantics { contentDescription = "Settings" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    // Explicit accessibility properties
                    role = Role.Button
                    contentDescription = "New Chat"
                }
            ) {
                Icon(
                    Icons.Default.Add, 
                    contentDescription = null // Icon description handled by parent FAB
                )
            }
        }
    ) { paddingValues ->
        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = { viewModel.refreshChats() },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (chats.isEmpty()) {
                EmptyChatsList(onNewChatClick = onNewChatClick)
            } else {
                ChatsList(
                    chats = chats,
                    onChatClick = onChatSelected,
                    onChatLongPress = { chatId ->
                        viewModel.deleteChat(chatId)
                    }
                )
            }
        }
    }
}

@Composable
fun ChatsList(
    chats: List<ChatEntity>,
    onChatClick: (Long) -> Unit,
    onChatLongPress: (Long) -> Unit
) {
    var chatToDelete by remember { mutableStateOf<ChatEntity?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(chats) { chat ->
            ChatItem(
                chat = chat,
                onClick = { onChatClick(chat.id) },
                onLongPress = { chatToDelete = chat }
            )
            HorizontalDivider()
        }
    }

    // Delete confirmation dialog
    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Delete Chat") },
            text = { Text("Are you sure you want to delete \"${chat.title}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onChatLongPress(chat.id)
                        chatToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatItem(
    chat: ChatEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chat icon or placeholder
        /*Icon(
            imageVector = Icons.Default.Add, // We'll use a placeholder icon
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))
        */
        // Chat title and preview
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Last message time
        Text(
            text = DateFormatter.formatMessageTime(chat.lastMessageTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyChatsList(onNewChatClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No chats yet",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Start a conversation with Whiz",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            IconButton(
                onClick = onNewChatClick,
                modifier = Modifier
                    .size(56.dp)
                    .padding(8.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "Start your first chat"
                    }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null, // Remove duplicate description
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(32.dp)
                        .clearAndSetSemantics { }
                )
            }
        }
    }
}

