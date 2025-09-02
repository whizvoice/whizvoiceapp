package com.example.whiz.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of AccessibilityChecker
 */
@Singleton
class AccessibilityCheckerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AccessibilityChecker {
    
    override fun isServiceEnabled(): Boolean {
        return WhizAccessibilityService.isServiceEnabled()
    }
    
    override fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}