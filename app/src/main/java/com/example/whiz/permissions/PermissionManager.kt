package com.example.whiz.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.accessibility.AccessibilityManager
import com.example.whiz.accessibility.WhizAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized permission manager to track app-wide permission states
 */
@Singleton
open class PermissionManager @Inject constructor(
    @ApplicationContext protected val context: Context,
    protected val accessibilityChecker: AccessibilityChecker,
    private val accessibilityManager: AccessibilityManager
) {
    companion object {
        private const val TAG = "PermissionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class RequiredStep {
        MICROPHONE,
        ACCESSIBILITY,
        OVERLAY,
        ACCESSIBILITY_SERVICE_STARTING  // Lowest priority - can wait in background
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

    // Combined state to track which step is needed next
    private val _nextRequiredStep = MutableStateFlow<RequiredStep?>(null)
    val nextRequiredStep: StateFlow<RequiredStep?> = _nextRequiredStep

    init {
        // Don't check permissions here - MainActivity will call checkAllPermissions()
        // after determining authentication status. This prevents the app from being
        // killed when permissions are revoked at runtime before login.

        // Observe accessibility service state changes to automatically update permission status
        observeAccessibilityServiceState()
    }

    /**
     * Check all permissions
     */
    fun checkAllPermissions() {
        checkMicrophonePermission()
        checkAccessibilityPermission()
        checkOverlayPermission()
        updateNextRequiredStep()
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
        updateNextRequiredStep()
    }

    /**
     * Check if accessibility service is enabled
     */
    fun checkAccessibilityPermission() {
        val isEnabled = accessibilityChecker.isServiceEnabled()
        Log.d(TAG, "checkAccessibilityPermission: isEnabled=$isEnabled")
        _accessibilityPermissionGranted.value = isEnabled
        updateNextRequiredStep()
    }

    /**
     * Update the microphone permission status
     */
    fun updateMicrophonePermission(granted: Boolean) {
        _microphonePermissionGranted.value = granted
        updateNextRequiredStep()
    }

    /**
     * Update the accessibility permission status
     */
    fun updateAccessibilityPermission(granted: Boolean) {
        _accessibilityPermissionGranted.value = granted
        updateNextRequiredStep()
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
        updateNextRequiredStep()
    }
    
    /**
     * Update the overlay permission status
     */
    fun updateOverlayPermission(granted: Boolean) {
        _overlayPermissionGranted.value = granted
        updateNextRequiredStep()
    }

    private fun updateNextRequiredStep() {
        val nextStep = when {
            !_microphonePermissionGranted.value -> RequiredStep.MICROPHONE
            !_accessibilityPermissionGranted.value -> RequiredStep.ACCESSIBILITY
            !_overlayPermissionGranted.value -> RequiredStep.OVERLAY
            // Only show service starting dialog if ALL permissions are granted but service isn't running
            _accessibilityPermissionGranted.value &&
                WhizAccessibilityService.getInstance() == null -> RequiredStep.ACCESSIBILITY_SERVICE_STARTING
            else -> null
        }
        val threadName = Thread.currentThread().name
        val threadId = Thread.currentThread().id
        Log.d(TAG, "updateNextRequiredStep on thread: $threadName (id=$threadId): nextStep=$nextStep (mic=${_microphonePermissionGranted.value}, acc=${_accessibilityPermissionGranted.value}, serviceRunning=${WhizAccessibilityService.getInstance() != null}, overlay=${_overlayPermissionGranted.value})")
        _nextRequiredStep.value = nextStep
    }
    
    /**
     * Clear permission dialogs temporarily (used when returning from settings)
     * This prevents stale dialogs from persisting while permissions are being rechecked
     */
    fun clearPermissionDialogs() {
        Log.d(TAG, "clearPermissionDialogs called - clearing nextRequiredStep")
        _nextRequiredStep.value = null
    }

    /**
     * Observe accessibility service state changes to automatically update permission status
     * This ensures the dialog dismisses immediately when the service connects
     */
    private fun observeAccessibilityServiceState() {
        scope.launch {
            // Observe the WhizAccessibilityService state directly
            WhizAccessibilityService.serviceState.collect { state ->
                val wasGranted = _accessibilityPermissionGranted.value
                val isNowEnabled = state != WhizAccessibilityService.ServiceState.DISCONNECTED

                val threadName = Thread.currentThread().name
                val threadId = Thread.currentThread().id
                Log.d(TAG, "Accessibility service state changed on thread: $threadName (id=$threadId): $state, wasGranted=$wasGranted, isNowEnabled=$isNowEnabled")

                // Update the accessibility permission status
                if (isNowEnabled != wasGranted) {
                    _accessibilityPermissionGranted.value = isNowEnabled
                    Log.d(TAG, "Accessibility permission status updated to: $isNowEnabled")

                    // Update the next required step immediately
                    updateNextRequiredStep()
                }
            }
        }

        // Also observe the AccessibilityManager's state for redundancy
        scope.launch {
            accessibilityManager.isAccessibilityEnabled.collect { isEnabled ->
                val wasGranted = _accessibilityPermissionGranted.value

                Log.d(TAG, "AccessibilityManager state changed: isEnabled=$isEnabled, wasGranted=$wasGranted")

                if (isEnabled != wasGranted) {
                    _accessibilityPermissionGranted.value = isEnabled
                    Log.d(TAG, "Accessibility permission updated from AccessibilityManager: $isEnabled")

                    // Update the next required step immediately
                    updateNextRequiredStep()
                }
            }
        }
    }
} 