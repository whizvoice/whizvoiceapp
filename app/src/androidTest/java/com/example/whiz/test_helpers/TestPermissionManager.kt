package com.example.whiz.test_helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.permissions.PermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Test implementation of PermissionManager that allows mocking permission states
 * for testing without actually revoking system permissions (which kills the app)
 */
@Singleton
class TestPermissionManager @Inject constructor(
    @ApplicationContext context: Context,
    accessibilityChecker: AccessibilityChecker
) : PermissionManager(context, accessibilityChecker) {
    
    companion object {
        private const val TAG = "TestPermissionManager"
    }
    
    /**
     * Mock state for microphone permission. When set, this overrides the real permission check.
     * When null, falls back to checking the real permission.
     */
    var mockMicrophonePermission: Boolean? = null
    
    /**
     * Override checkMicrophonePermission to use mock state when available
     */
    override fun checkMicrophonePermission() {
        // Check mock state first
        val hasPermission = mockMicrophonePermission?.let { mockedState ->
            Log.d(TAG, "Using mocked microphone permission state: $mockedState")
            mockedState
        } ?: run {
            // Fall back to real permission check
            val realPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Using real microphone permission state: $realPermission")
            realPermission
        }
        
        // Update the permission state
        updateMicrophonePermission(hasPermission)
    }
    
    /**
     * Set the mock microphone permission state for testing
     */
    fun setMockMicrophonePermission(granted: Boolean) {
        Log.d(TAG, "Setting mock microphone permission to: $granted")
        mockMicrophonePermission = granted
        // Immediately update the state
        checkMicrophonePermission()
    }
    
    /**
     * Reset the mock state to use real permission checking
     */
    fun resetMockMicrophonePermission() {
        Log.d(TAG, "Resetting mock microphone permission state")
        mockMicrophonePermission = null
        // Re-check with real permission
        checkMicrophonePermission()
    }
    
    /**
     * Helper method to simulate revoking microphone permission without actually
     * revoking it (which would kill the app)
     */
    fun simulateMicrophoneRevoke() {
        Log.d(TAG, "Simulating microphone permission revocation (without killing app)")
        setMockMicrophonePermission(false)
    }
    
    /**
     * Helper method to simulate granting microphone permission
     */
    fun simulateMicrophoneGrant() {
        Log.d(TAG, "Simulating microphone permission grant")
        setMockMicrophonePermission(true)
    }
}