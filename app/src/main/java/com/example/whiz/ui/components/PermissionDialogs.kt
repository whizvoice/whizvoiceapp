package com.example.whiz.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

@Composable
fun MicrophonePermissionDialog(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { 
            contentDescription = "Microphone permission dialog"
        },
        title = { 
            Text(
                "Microphone Permission Required",
                modifier = Modifier.semantics { 
                    contentDescription = "Microphone permission required title"
                }
            ) 
        },
        text = { 
            Text(
                "Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics { 
                    contentDescription = "Microphone permission explanation"
                }
            ) 
        },
        confirmButton = {
            Button(
                onClick = {
                    onRequestPermission()
                    onDismiss()
                },
                modifier = Modifier.semantics { 
                    contentDescription = "Grant microphone permission button"
                }
            ) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Black
                ),
                modifier = Modifier.semantics { 
                    contentDescription = "Dismiss microphone permission dialog button"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}