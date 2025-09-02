package com.example.whiz.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

@Composable
fun AccessibilityPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Accessibility Service") },
        text = {
            Text(
                text = "WhizVoice needs accessibility access to:\n\n" +
                      "• Open apps like WhatsApp on your command\n" +
                      "• Navigate your phone with voice\n" +
                      "• Interact with other apps hands-free\n\n" +
                      "You'll be taken to Settings where you can enable WhizVoice under Accessibility.",
                textAlign = TextAlign.Start
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onOpenSettings()
                onDismiss()
            }) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        }
    )
}