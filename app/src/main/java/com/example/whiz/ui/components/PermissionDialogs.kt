package com.example.whiz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Inline dialog that renders within the activity's window instead of creating
 * a separate window. This is required for lock screen compatibility —
 * AlertDialog creates a separate window that doesn't inherit
 * setShowWhenLocked(true), making buttons untappable on the lock screen.
 */
@Composable
private fun InlineDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismissRequest() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = 340.dp)
                .padding(horizontal = 24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume click so scrim handler doesn't fire */ },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                if (title != null) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        title()
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (text != null) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )) {
                        text()
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                    }
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun MicrophonePermissionDialog(
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit
) {
    InlineDialog(
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
    InlineDialog(
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
    InlineDialog(
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
    InlineDialog(
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
    InlineDialog(
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
            Button(
                onClick = {
                    onOpenSettings()
                    // Don't dismiss here - let onResume handle it when returning from Settings
                },
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
