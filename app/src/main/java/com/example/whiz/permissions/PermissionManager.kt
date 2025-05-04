package com.example.whiz.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized permission manager to track app-wide permission states
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // StateFlow for microphone permission status
    private val _microphonePermissionGranted = MutableStateFlow(false)
    val microphonePermissionGranted: StateFlow<Boolean> = _microphonePermissionGranted

    init {
        // Check initial permission state
        checkMicrophonePermission()
    }

    /**
     * Check if the app has microphone permission
     */
    fun checkMicrophonePermission() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        _microphonePermissionGranted.value = hasPermission
    }

    /**
     * Update the microphone permission status
     */
    fun updateMicrophonePermission(granted: Boolean) {
        _microphonePermissionGranted.value = granted
    }
} 