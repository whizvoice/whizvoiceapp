package com.example.whiz.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Inline dialog that renders within the activity's window instead of creating
 * a separate window. This is required for lock screen compatibility —
 * AlertDialog creates a separate window that doesn't inherit
 * setShowWhenLocked(true), making buttons untappable on the lock screen.
 */
@Composable
internal fun InlineDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val maxDialogHeight = (configuration.screenHeightDp * 0.9f).dp
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
                .heightIn(max = maxDialogHeight)
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
                    // Body is scrollable so tall content at large display scales doesn't
                    // push the action buttons off-screen. weight(fill = false) lets the
                    // dialog wrap content when it fits and only consume remaining height
                    // when content overflows. Top/bottom dividers appear when there's
                    // hidden content in that direction — the M3 signal for "scrollable".
                    val scrollState = rememberScrollState()
                    val canScrollUp by remember {
                        derivedStateOf { scrollState.value > 0 }
                    }
                    val canScrollDown by remember {
                        derivedStateOf { scrollState.value < scrollState.maxValue }
                    }
                    if (canScrollUp) {
                        HorizontalDivider()
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(scrollState)
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )) {
                            text()
                        }
                    }
                    if (canScrollDown) {
                        HorizontalDivider()
                    }
                    Spacer(Modifier.height(24.dp))
                }
                DialogButtonRow(modifier = Modifier.fillMaxWidth()) {
                    if (dismissButton != null) {
                        dismissButton()
                    }
                    confirmButton()
                }
            }
        }
    }
}

/**
 * Material 3 dialog action layout. Lays the buttons out as a single end-aligned
 * row when they fit, and falls back to a vertical stack — with the LAST child
 * (the confirm button) on top — when they don't. Mirrors what the framework's
 * AlertDialogFlowRow does, but as a custom Layout so it works inside our
 * activity-local InlineDialog.
 *
 * Spacing per M3 spec: 8dp between buttons in a row, 12dp between stacked rows.
 */
@Composable
private fun DialogButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val mainAxisSpacingPx = 8.dp.roundToPx()
        val crossAxisSpacingPx = 12.dp.roundToPx()
        // Measure with unbounded width so children take their natural size.
        // Material 3 Button, given a bounded maxWidth via fillMaxWidth on this
        // layout, expands to fill (centering its pill inside); an infinite
        // maxWidth forces it to wrap to its content instead, so end-alignment
        // hugs the actual visible button edge.
        val childConstraints = Constraints(minWidth = 0, maxWidth = Constraints.Infinity)
        val placeables = measurables.map { it.measure(childConstraints) }
        val rowWidth = placeables.sumOf { it.width } +
            mainAxisSpacingPx * (placeables.size - 1)
        if (rowWidth <= constraints.maxWidth) {
            val rowHeight = placeables.maxOf { it.height }
            layout(constraints.maxWidth, rowHeight) {
                var x = constraints.maxWidth - rowWidth
                placeables.forEach { p ->
                    p.placeRelative(x, (rowHeight - p.height) / 2)
                    x += p.width + mainAxisSpacingPx
                }
            }
        } else {
            val width = constraints.maxWidth
            val totalHeight = placeables.sumOf { it.height } +
                crossAxisSpacingPx * (placeables.size - 1)
            layout(width, totalHeight) {
                var y = 0
                placeables.asReversed().forEach { p ->
                    p.placeRelative(width - p.width, y)
                    y += p.height + crossAxisSpacingPx
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
fun HealthConnectPermissionDialog(
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit
) {
    InlineDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics {
            contentDescription = "Health Connect permission dialog"
        },
        title = {
            Text(
                "Health Connect Permission Required",
                modifier = Modifier.semantics {
                    contentDescription = "Health Connect permission required title"
                }
            )
        },
        text = {
            Text(
                "Whiz needs permission to write to Health Connect so it can log calories and weight. Would you like to grant permission now?",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics {
                    contentDescription = "Health Connect permission explanation"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onGrantPermission()
                },
                modifier = Modifier.semantics {
                    contentDescription = "Grant Health Connect permission button"
                }
            ) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Dismiss Health Connect permission dialog button"
                }
            ) {
                Text("Not Now")
            }
        }
    )
}

@Composable
fun ConnectHealthAppDialog(
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    InlineDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics {
            contentDescription = "Open Health Connect settings dialog"
        },
        title = {
            Text(
                "Open Health Connect settings?",
                modifier = Modifier.semantics {
                    contentDescription = "Open Health Connect settings title"
                }
            )
        },
        text = {
            Text(
                "Open Health Connect settings to connect an app so you can see your data.",
                textAlign = TextAlign.Start,
                modifier = Modifier.semantics {
                    contentDescription = "Open Health Connect settings explanation"
                }
            )
        },
        confirmButton = {
            Button(
                onClick = { onOpen() },
                modifier = Modifier.semantics {
                    contentDescription = "Open Health Connect settings button"
                }
            ) {
                Text("Open Health Connect")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = "Dismiss Health Connect settings dialog button"
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
