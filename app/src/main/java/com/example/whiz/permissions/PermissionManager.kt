package com.example.whiz.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.accessibility.WhizAccessibilityService
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
    companion object {
        private const val TAG = "PermissionManager"
    }
    
    enum class PermissionType {
        MICROPHONE,
        ACCESSIBILITY,
        OVERLAY
    }

    // StateFlow for microphone permission status
    private val _microphonePermissionGranted = MutableStateFlow(false)
    val microphonePermissionGranted: StateFlow<Boolean> = _microphonePermissionGranted

    // StateFlow for accessibility permission status
    private val _accessibilityPermissionGranted = MutableStateFlow(false)
    val accessibilityPermissionGranted: StateFlow<Boolean> = _accessibilityPermissionGranted
    
    // StateFlow for overlay permission status
    private val _overlayPermissionGranted = MutableStateFlow(false)
    val overlayPermissionGranted: StateFlow<Boolean> = _overlayPermissionGranted

    // StateFlow for tracking if we're waiting for accessibility service to start
    private val _isWaitingForAccessibilityService = MutableStateFlow(false)
    val isWaitingForAccessibilityService: StateFlow<Boolean> = _isWaitingForAccessibilityService

    // Combined state to track which permission is needed next
    private val _nextRequiredPermission = MutableStateFlow<PermissionType?>(null)
    val nextRequiredPermission: StateFlow<PermissionType?> = _nextRequiredPermission

    init {
        // Don't check permissions here - MainActivity will call checkAllPermissions()
        // after determining authentication status. This prevents the app from being
        // killed when permissions are revoked at runtime before login.
    }

    /**
     * Check all permissions
     */
    fun checkAllPermissions() {
        checkMicrophonePermission()
        checkAccessibilityPermission()
        checkOverlayPermission()
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
        Log.d(TAG, "checkAccessibilityPermission: isEnabled=$isEnabled")
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
    
    /**
     * Check if the app has overlay permission
     */
    open fun checkOverlayPermission() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Always true for older Android versions
        }
        _overlayPermissionGranted.value = hasPermission
        updateNextRequiredPermission()
    }
    
    /**
     * Update the overlay permission status
     */
    fun updateOverlayPermission(granted: Boolean) {
        _overlayPermissionGranted.value = granted
        updateNextRequiredPermission()
    }

    private fun updateNextRequiredPermission() {
        val nextPermission = when {
            !_microphonePermissionGranted.value -> PermissionType.MICROPHONE
            !_accessibilityPermissionGranted.value -> PermissionType.ACCESSIBILITY
            !_overlayPermissionGranted.value -> PermissionType.OVERLAY
            else -> null
        }
        Log.d(TAG, "updateNextRequiredPermission: nextPermission=$nextPermission (mic=${_microphonePermissionGranted.value}, acc=${_accessibilityPermissionGranted.value}, overlay=${_overlayPermissionGranted.value})")
        _nextRequiredPermission.value = nextPermission
    }
    
    /**
     * Clear permission dialogs temporarily (used when returning from settings)
     * This prevents stale dialogs from persisting while permissions are being rechecked
     */
    fun clearPermissionDialogs() {
        Log.d(TAG, "clearPermissionDialogs called - clearing nextRequiredPermission")
        _nextRequiredPermission.value = null
    }
    
    /**
     * Set whether we're waiting for the accessibility service to start
     */
    fun setWaitingForAccessibilityService(waiting: Boolean) {
        Log.d(TAG, "setWaitingForAccessibilityService: $waiting")
        _isWaitingForAccessibilityService.value = waiting
    }
    
    /**
     * Check if accessibility permission is granted but service is not yet running
     */
    fun checkAccessibilityServiceStartupState() {
        val permissionGranted = accessibilityChecker.isServiceEnabled()
        val serviceRunning = WhizAccessibilityService.getInstance() != null
        
        if (permissionGranted && !serviceRunning) {
            Log.d(TAG, "Accessibility permission granted but service not running yet")
            setWaitingForAccessibilityService(true)
        } else {
            setWaitingForAccessibilityService(false)
        }
    }
} 