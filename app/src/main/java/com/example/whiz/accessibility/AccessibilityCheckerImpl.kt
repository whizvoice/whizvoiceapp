package com.example.whiz.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
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
        // Check system settings directly - this gives immediate feedback
        // when the user grants permission, even if service hasn't started yet
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        if (!TextUtils.isEmpty(enabledServices)) {
            val serviceName = "${context.packageName}/com.example.whiz.accessibility.WhizAccessibilityService"
            return enabledServices.contains(serviceName)
        }
        
        return false
    }
    
    override fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}