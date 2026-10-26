package com.example.whiz.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RageShakeReportDialog(
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onDisableForever: () -> Unit,
    onSubmit: (description: String) -> Unit
) {
    var description by remember { mutableStateOf("") }

    InlineDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text(
                "Report a Problem",
                modifier = Modifier.semantics {
                    contentDescription = "Report a problem title"
                }
            )
        },
        text = {
            Column {
                Text(
                    "Describe what went wrong (optional):",
                    textAlign = TextAlign.Start,
                    modifier = Modifier.semantics {
                        contentDescription = "Bug report description prompt"
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Bug description input" },
                    placeholder = { Text("e.g. The app froze when I tried to...") },
                    maxLines = 4,
                    enabled = !isSubmitting
                )
            }
        },
        confirmButton = {
            // Stacked full-width buttons: easy to hit one-handed while moving.
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSubmit(description) },
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Submit bug report button" }
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Submit")
                    }
                }
                TextButton(
                    onClick = onSnooze,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Snooze shaking button" }
                ) {
                    Text("Not now")
                }
                TextButton(
                    onClick = onDisableForever,
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Turn off shaking forever button" }
                ) {
                    Text("Never shake again")
                }
            }
        },
        dismissButton = null
    )
}
