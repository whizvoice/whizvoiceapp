package com.example.whiz.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign

@Composable
fun AccessibilityPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { 
            contentDescription = "Accessibility permission dialog"
        },
        title = { 
            Text(
                "Enable Accessibility Service",
                modifier = Modifier.semantics { 
                    contentDescription = "Enable accessibility service title"
                }
            ) 
        },
        text = {
            Text(
                text = "WhizVoice needs accessibility access to:\n\n" +
                      "• Open apps like WhatsApp on your command\n" +
                      "• Navigate your phone with voice\n" +
                      "• Interact with other apps hands-free\n\n" +
                      "In Settings:\n" +
                      "1. Look for 'Downloaded apps' or 'Installed services'\n" +
                      "2. Find 'WhizVoice' in the list\n" +
                      "3. Toggle it ON and confirm",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics { 
                    contentDescription = "Accessibility permission explanation"
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onOpenSettings()
                    onDismiss()
                },
                modifier = Modifier.semantics { 
                    contentDescription = "Open accessibility settings button"
                }
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { 
                    contentDescription = "Dismiss accessibility permission dialog button"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}