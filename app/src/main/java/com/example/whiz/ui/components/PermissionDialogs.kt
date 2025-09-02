package com.example.whiz.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

@Composable
fun MicrophonePermissionDialog(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Microphone Permission Required") },
        text = { 
            Text(
                "Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?",
                textAlign = TextAlign.Start
            ) 
        },
        confirmButton = {
            Button(onClick = {
                onRequestPermission()
                onDismiss()
            }) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}