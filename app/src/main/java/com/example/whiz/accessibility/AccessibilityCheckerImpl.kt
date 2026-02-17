package com.example.whiz.accessibility

import android.content.ComponentName
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
            val componentName = ComponentName(context.packageName, WhizAccessibilityService::class.java.name)
            // Check both forms since Android may store either the full or short component name
            val fullName = componentName.flattenToString()        // "pkg/pkg.class"
            val shortName = componentName.flattenToShortString()  // "pkg/.class"
            return enabledServices.contains(fullName) || enabledServices.contains(shortName)
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