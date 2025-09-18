package com.example.whiz.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun AccessibilityServiceLoadingDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {}, // Make dialog non-dismissible by tapping outside
        modifier = Modifier.semantics { 
            contentDescription = "Accessibility service starting dialog"
        },
        title = { 
            Text(
                "Starting Accessibility Service",
                modifier = Modifier.semantics { 
                    contentDescription = "Starting accessibility service title"
                }
            ) 
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp)
                )
                
                Text(
                    text = "Please wait while WhizVoice accessibility service starts...\n\n" +
                          "This usually takes a few seconds. The dialog will close automatically when the service is ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { 
                        contentDescription = "Accessibility service loading explanation"
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.Black
                ),
                modifier = Modifier.semantics { 
                    contentDescription = "OK button"
                }
            ) {
                Text("OK")
            }
        },
        dismissButton = null
    )
}