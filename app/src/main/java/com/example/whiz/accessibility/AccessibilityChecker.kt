package com.example.whiz.accessibility

/**
 * Interface for checking accessibility service status.
 * This abstraction allows for testing with mock implementations.
 */
interface AccessibilityChecker {
    /**
     * Check if the accessibility service is enabled
     */
    fun isServiceEnabled(): Boolean
    
    /**
     * Open accessibility settings for the app
     */
    fun openAccessibilitySettings()
}