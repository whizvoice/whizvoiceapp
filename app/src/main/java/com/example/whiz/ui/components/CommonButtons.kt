package com.example.whiz.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Reusable button with loading state to eliminate UI duplication
 */
@Composable
fun LoadingButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentDescription: String? = null
) {
    val buttonColors = if (containerColor != null) {
        ButtonDefaults.buttonColors(containerColor = containerColor)
    } else {
        ButtonDefaults.buttonColors()
    }
    
    val buttonModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = buttonModifier,
        colors = buttonColors
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(text)
        }
    }
}

/**
 * Outlined button with loading state
 */
@Composable
fun LoadingOutlinedButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors()
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(text)
        }
    }
}

/**
 * Destructive action button with loading state (used for delete/clear actions)
 */
@Composable
fun LoadingDestructiveButton(
    text: String,
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            width = 1.dp,
            brush = SolidColor(
                if (enabled && !isLoading) MaterialTheme.colorScheme.error 
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            )
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(text)
        }
    }
}

/**
 * Standard save button with loading state
 */
@Composable
fun SaveButton(
    text: String = "Save",
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    LoadingButton(
        text = text,
        onClick = onClick,
        isLoading = isLoading,
        enabled = enabled,
        modifier = modifier
    )
}

/**
 * Standard cancel button
 */
@Composable
fun CancelButton(
    text: String = "Cancel",
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    LoadingOutlinedButton(
        text = text,
        onClick = onClick,
        isLoading = false,
        enabled = enabled,
        modifier = modifier
    )
}

/**
 * Standard clear/delete button with loading state
 */
@Composable
fun ClearButton(
    text: String = "Clear",
    onClick: () -> Unit,
    isLoading: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    LoadingDestructiveButton(
        text = text,
        onClick = onClick,
        isLoading = isLoading,
        enabled = enabled,
        modifier = modifier
    )
} 