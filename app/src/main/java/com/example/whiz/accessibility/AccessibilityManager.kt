package com.example.whiz.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibilityChecker: AccessibilityChecker
) {
    private val TAG = "AccessibilityManager"
    
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled
    
    init {
        updateAccessibilityStatus()
    }
    
    fun updateAccessibilityStatus() {
        _isAccessibilityEnabled.value = accessibilityChecker.isServiceEnabled()
        Log.d(TAG, "Accessibility service enabled: ${_isAccessibilityEnabled.value}")
    }
    
    fun openAccessibilitySettings() {
        accessibilityChecker.openAccessibilitySettings()
    }
    
    fun openWhatsApp(): Boolean {
        val service = WhizAccessibilityService.getInstance()
        return if (service != null) {
            service.openWhatsApp()
        } else {
            Log.w(TAG, "Accessibility service not available")
            false
        }
    }
    
    fun openApp(packageName: String): Boolean {
        val service = WhizAccessibilityService.getInstance()
        return if (service != null) {
            service.openApp(packageName)
        } else {
            Log.w(TAG, "Accessibility service not available")
            false
        }
    }
    
    fun getInstalledApps(): List<WhizAccessibilityService.AppInfo> {
        val service = WhizAccessibilityService.getInstance()
        return service?.getInstalledApps() ?: emptyList()
    }
    
    fun performBackAction(): Boolean {
        val service = WhizAccessibilityService.getInstance()
        return service?.performGlobalActionSafely(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }
    
    fun performHomeAction(): Boolean {
        val service = WhizAccessibilityService.getInstance()
        return service?.performGlobalActionSafely(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }
    
    fun performRecentAppsAction(): Boolean {
        val service = WhizAccessibilityService.getInstance()
        return service?.performGlobalActionSafely(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false
    }
}