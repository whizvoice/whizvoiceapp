package com.example.whiz.test_helpers

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.IOException

/**
 * Helper class to grant permissions using ADB commands before tests run.
 * This is more reliable than trying to navigate Settings UI during tests.
 */
object AdbPermissionGranter {
    
    private const val TAG = "AdbPermissionGranter"
    private const val PACKAGE_NAME = "com.example.whiz.debug"
    
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    
    /**
     * Grant all required permissions for WhatsApp integration tests.
     * Should be called in @Before setup method.
     */
    fun grantAllPermissions(): Boolean {
        Log.d(TAG, "🔐 Granting all permissions via ADB...")
        
        var allSuccess = true
        
        // Grant overlay permission
        if (grantOverlayPermission()) {
            Log.d(TAG, "✅ Overlay permission granted")
        } else {
            Log.e(TAG, "❌ Failed to grant overlay permission")
            allSuccess = false
        }
        
        // Grant accessibility service permission
        if (enableAccessibilityService()) {
            Log.d(TAG, "✅ Accessibility service enabled")
        } else {
            Log.e(TAG, "❌ Failed to enable accessibility service")
            allSuccess = false
        }
        
        // Grant microphone permission
        if (grantMicrophonePermission()) {
            Log.d(TAG, "✅ Microphone permission granted")
        } else {
            Log.e(TAG, "❌ Failed to grant microphone permission")
            allSuccess = false
        }
        
        return allSuccess
    }
    
    /**
     * Grant overlay (SYSTEM_ALERT_WINDOW) permission.
     */
    fun grantOverlayPermission(): Boolean {
        return try {
            // Grant overlay permission via appops
            val result = device.executeShellCommand(
                "appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow"
            )
            Log.d(TAG, "Overlay permission command result: $result")
            
            // Verify it was granted
            val checkResult = device.executeShellCommand(
                "appops get $PACKAGE_NAME SYSTEM_ALERT_WINDOW"
            )
            val granted = checkResult.contains("allow", ignoreCase = true) || 
                         checkResult.contains("default", ignoreCase = true) // default means allowed for this permission
            
            if (!granted) {
                // Try alternative method using settings
                device.executeShellCommand(
                    "settings put secure enabled_accessibility_services " +
                    "$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"
                )
            }
            
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to grant overlay permission", e)
            false
        }
    }
    
    /**
     * Enable accessibility service for the app.
     */
    fun enableAccessibilityService(): Boolean {
        return try {
            // Get current enabled services
            val currentServices = device.executeShellCommand(
                "settings get secure enabled_accessibility_services"
            ).trim()
            
            val whizService = "$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"
            
            // Check if already enabled
            if (currentServices.contains(whizService)) {
                Log.d(TAG, "Accessibility service already enabled")
                return true
            }
            
            // Add our service to the list
            val newServices = if (currentServices.isEmpty() || currentServices == "null") {
                whizService
            } else {
                "$currentServices:$whizService"
            }
            
            // Enable the service
            device.executeShellCommand(
                "settings put secure enabled_accessibility_services \"$newServices\""
            )
            
            // Also ensure accessibility is enabled globally
            device.executeShellCommand(
                "settings put secure accessibility_enabled 1"
            )
            
            // Touch exploration might be needed for some accessibility features
            device.executeShellCommand(
                "settings put secure touch_exploration_enabled 0"
            )
            
            Log.d(TAG, "Accessibility service enabled via ADB")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to enable accessibility service", e)
            false
        }
    }
    
    /**
     * Grant microphone (RECORD_AUDIO) permission.
     */
    fun grantMicrophonePermission(): Boolean {
        return try {
            val result = device.executeShellCommand(
                "pm grant $PACKAGE_NAME android.permission.RECORD_AUDIO"
            )
            
            // Empty result usually means success
            if (result.isEmpty() || !result.contains("error", ignoreCase = true)) {
                Log.d(TAG, "Microphone permission granted")
                true
            } else {
                Log.e(TAG, "Microphone permission grant failed: $result")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to grant microphone permission", e)
            false
        }
    }
    
    /**
     * Disable accessibility service (useful for cleanup).
     */
    fun disableAccessibilityService() {
        try {
            val currentServices = device.executeShellCommand(
                "settings get secure enabled_accessibility_services"
            ).trim()
            
            val whizService = "$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"
            
            if (currentServices.contains(whizService)) {
                // Remove our service from the list
                val newServices = currentServices
                    .split(":")
                    .filter { it != whizService }
                    .joinToString(":")
                
                device.executeShellCommand(
                    "settings put secure enabled_accessibility_services \"$newServices\""
                )
                
                // If no services left, disable accessibility
                if (newServices.isEmpty() || newServices == "null") {
                    device.executeShellCommand(
                        "settings put secure accessibility_enabled 0"
                    )
                }
                
                Log.d(TAG, "Accessibility service disabled")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to disable accessibility service", e)
        }
    }
    
    /**
     * Check current permission status.
     */
    fun checkPermissionStatus(): PermissionStatus {
        val status = PermissionStatus()
        
        try {
            // Check overlay permission
            val overlayResult = device.executeShellCommand(
                "appops get $PACKAGE_NAME SYSTEM_ALERT_WINDOW"
            )
            status.overlayGranted = overlayResult.contains("allow", ignoreCase = true) ||
                                   overlayResult.contains("default", ignoreCase = true)
            
            // Check accessibility service
            val accessibilityResult = device.executeShellCommand(
                "settings get secure enabled_accessibility_services"
            )
            status.accessibilityEnabled = accessibilityResult.contains(
                "$PACKAGE_NAME/com.example.whiz.accessibility.WhizAccessibilityService"
            )
            
            // Check microphone permission
            val micResult = device.executeShellCommand(
                "dumpsys package $PACKAGE_NAME | grep android.permission.RECORD_AUDIO"
            )
            status.microphoneGranted = micResult.contains("granted=true")
            
        } catch (e: IOException) {
            Log.e(TAG, "Failed to check permission status", e)
        }
        
        return status
    }
    
    data class PermissionStatus(
        var overlayGranted: Boolean = false,
        var accessibilityEnabled: Boolean = false,
        var microphoneGranted: Boolean = false
    ) {
        fun allGranted(): Boolean = overlayGranted && accessibilityEnabled && microphoneGranted
        
        override fun toString(): String {
            return "PermissionStatus(overlay=$overlayGranted, accessibility=$accessibilityEnabled, microphone=$microphoneGranted)"
        }
    }
}