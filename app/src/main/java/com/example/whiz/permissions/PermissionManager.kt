package com.example.whiz.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.whiz.accessibility.AccessibilityChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized permission manager to track app-wide permission states
 */
@Singleton
open class PermissionManager @Inject constructor(
    @ApplicationContext protected val context: Context,
    protected val accessibilityChecker: AccessibilityChecker
) {
    enum class PermissionType {
        MICROPHONE,
        ACCESSIBILITY
    }

    // StateFlow for microphone permission status
    private val _microphonePermissionGranted = MutableStateFlow(false)
    val microphonePermissionGranted: StateFlow<Boolean> = _microphonePermissionGranted

    // StateFlow for accessibility permission status
    private val _accessibilityPermissionGranted = MutableStateFlow(false)
    val accessibilityPermissionGranted: StateFlow<Boolean> = _accessibilityPermissionGranted

    // Combined state to track which permission is needed next
    private val _nextRequiredPermission = MutableStateFlow<PermissionType?>(null)
    val nextRequiredPermission: StateFlow<PermissionType?> = _nextRequiredPermission

    init {
        // Check initial permission states
        checkAllPermissions()
    }

    /**
     * Check all permissions
     */
    fun checkAllPermissions() {
        checkMicrophonePermission()
        checkAccessibilityPermission()
        updateNextRequiredPermission()
    }

    /**
     * Check if the app has microphone permission
     */
    open fun checkMicrophonePermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        _microphonePermissionGranted.value = hasPermission
        updateNextRequiredPermission()
    }

    /**
     * Check if accessibility service is enabled
     */
    fun checkAccessibilityPermission() {
        val isEnabled = accessibilityChecker.isServiceEnabled()
        _accessibilityPermissionGranted.value = isEnabled
        updateNextRequiredPermission()
    }

    /**
     * Update the microphone permission status
     */
    fun updateMicrophonePermission(granted: Boolean) {
        _microphonePermissionGranted.value = granted
        updateNextRequiredPermission()
    }

    /**
     * Update the accessibility permission status
     */
    fun updateAccessibilityPermission(granted: Boolean) {
        _accessibilityPermissionGranted.value = granted
        updateNextRequiredPermission()
    }

    private fun updateNextRequiredPermission() {
        _nextRequiredPermission.value = when {
            !_microphonePermissionGranted.value -> PermissionType.MICROPHONE
            !_accessibilityPermissionGranted.value -> PermissionType.ACCESSIBILITY
            else -> null
        }
    }
} 