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
        onDismissRequest = {}, // Make dialog non-dismissible
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
        dismissButton = null // Remove the dismiss button entirely
    )
}

@Composable
fun OverlayPermissionDialog(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {}, // Make dialog non-dismissible
        modifier = Modifier.semantics {
            contentDescription = "Overlay permission dialog"
        },
        title = {
            Text(
                "Display Over Other Apps Permission Required",
                modifier = Modifier.semantics {
                    contentDescription = "Overlay permission required title"
                }
            )
        },
        text = {
            Text(
                "Whiz needs permission to display over other apps, so you can use voice commands on other apps.",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics {
                    contentDescription = "Overlay permission explanation"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onRequestPermission()
                    // Don't dismiss here - let onResume handle it when returning from Settings
                },
                modifier = Modifier.semantics {
                    contentDescription = "Grant overlay permission button"
                }
            ) {
                Text("Grant Permission")
            }
        },
        dismissButton = null // Remove the dismiss button entirely
    )
}

@Composable
fun ContactsPermissionDialog(
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics {
            contentDescription = "Contacts permission dialog"
        },
        title = {
            Text(
                "Contacts Permission Required",
                modifier = Modifier.semantics {
                    contentDescription = "Contacts permission required title"
                }
            )
        },
        text = {
            Text(
                "Whiz needs access to your contacts to look up phone numbers, emails, and addresses. Would you like to grant contacts permission now?",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics {
                    contentDescription = "Contacts permission explanation"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onGrantPermission()
                },
                modifier = Modifier.semantics {
                    contentDescription = "Grant contacts permission button"
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
                    contentDescription = "Dismiss contacts permission dialog button"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}

@Composable
fun CalendarPermissionDialog(
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics {
            contentDescription = "Calendar permission dialog"
        },
        title = {
            Text(
                "Calendar Permission Required",
                modifier = Modifier.semantics {
                    contentDescription = "Calendar permission required title"
                }
            )
        },
        text = {
            Text(
                "Whiz needs access to your calendar to save events. Would you like to grant calendar permission now?",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics {
                    contentDescription = "Calendar permission explanation"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onGrantPermission()
                },
                modifier = Modifier.semantics {
                    contentDescription = "Grant calendar permission button"
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
                    contentDescription = "Dismiss calendar permission dialog button"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}

@Composable
fun AccessibilityPermissionDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {}, // Make dialog non-dismissible
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
                text = "WhizVoice requires accessibility access to function properly.\n\n" +
                      "This allows WhizVoice to:\n" +
                      "• Open apps on your command\n" +
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
                    // Don't dismiss here - let onResume handle it when returning from Settings
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Black
                ),
                modifier = Modifier.semantics { 
                    contentDescription = "Open accessibility settings button"
                }
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = null // Remove the dismiss button entirely
    )
}