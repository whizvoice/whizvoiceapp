package com.example.whiz.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessibilityChecker: AccessibilityChecker
) {
    private val TAG = "AccessibilityManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled

    init {
        updateAccessibilityStatus()
        observeServiceState()
    }

    private fun observeServiceState() {
        // Observe the WhizAccessibilityService state changes
        scope.launch {
            WhizAccessibilityService.serviceState.collect { state ->
                val isEnabled = state != WhizAccessibilityService.ServiceState.DISCONNECTED
                _isAccessibilityEnabled.value = isEnabled
                Log.d(TAG, "Accessibility service state changed: $state, enabled: $isEnabled")
            }
        }
    }

    fun updateAccessibilityStatus() {
        // This now checks the actual service state instead of system settings
        _isAccessibilityEnabled.value = WhizAccessibilityService.isServiceEnabled()
        Log.d(TAG, "Accessibility service enabled: ${_isAccessibilityEnabled.value}")
    }
    
    fun openAccessibilitySettings() {
        accessibilityChecker.openAccessibilitySettings()
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