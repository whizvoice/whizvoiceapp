package com.example.whiz.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DebugIndicator(
    modifier: Modifier = Modifier,
    showBuildInfo: Boolean = false
) {
    // Empty in release builds - no debug indicator shown
}

@Composable
fun DebugBanner(
    modifier: Modifier = Modifier
) {
    // Empty in release builds - no debug banner shown
} 