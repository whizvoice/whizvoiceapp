package com.example.whiz.test_helpers

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.accessibility.WhizAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Test implementation of AccessibilityChecker that allows mocking the service state
 */
@Singleton
class TestAccessibilityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : AccessibilityChecker {
    
    companion object {
        private const val TAG = "TestAccessibilityChecker"
    }
    
    /**
     * Mock state for testing. When set, this overrides the real service check.
     * When null, falls back to checking the real service.
     */
    var mockServiceEnabled: Boolean? = null
    
    override fun isServiceEnabled(): Boolean {
        // Check mock state first
        mockServiceEnabled?.let { mockedState ->
            Log.d(TAG, "Using mocked accessibility state: $mockedState")
            return mockedState
        }
        
        // Fall back to real service check
        val realState = WhizAccessibilityService.isServiceEnabled()
        Log.d(TAG, "Using real accessibility state: $realState")
        return realState
    }
    
    override fun openAccessibilitySettings() {
        Log.d(TAG, "Opening accessibility settings")
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
    
    /**
     * Reset the mock state to use real service checking
     */
    fun resetMock() {
        Log.d(TAG, "Resetting mock state")
        mockServiceEnabled = null
    }
    
    /**
     * Set the mock state for testing
     */
    fun setMockServiceEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting mock accessibility state to: $enabled")
        mockServiceEnabled = enabled
    }
}