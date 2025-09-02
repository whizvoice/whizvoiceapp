package com.example.whiz.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.accessibility.AccessibilityManager
import com.example.whiz.accessibility.WhizAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccessibilityViewModel @Inject constructor(
    private val accessibilityManager: AccessibilityManager
) : ViewModel() {
    
    val isAccessibilityEnabled: StateFlow<Boolean> = accessibilityManager.isAccessibilityEnabled
    
    private val _installedApps = MutableStateFlow<List<WhizAccessibilityService.AppInfo>>(emptyList())
    val installedApps: StateFlow<List<WhizAccessibilityService.AppInfo>> = _installedApps
    
    fun refreshAccessibilityStatus() {
        accessibilityManager.updateAccessibilityStatus()
    }
    
    fun openAccessibilitySettings() {
        accessibilityManager.openAccessibilitySettings()
    }
    
    fun openWhatsApp(): Boolean {
        return accessibilityManager.openWhatsApp()
    }
    
    fun openApp(packageName: String): Boolean {
        return accessibilityManager.openApp(packageName)
    }
    
    fun performBackAction(): Boolean {
        return accessibilityManager.performBackAction()
    }
    
    fun performHomeAction(): Boolean {
        return accessibilityManager.performHomeAction()
    }
    
    fun performRecentAppsAction(): Boolean {
        return accessibilityManager.performRecentAppsAction()
    }
    
    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = accessibilityManager.getInstalledApps()
        }
    }
}