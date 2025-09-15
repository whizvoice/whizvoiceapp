package com.example.whiz.test_helpers

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Helper class to automatically grant accessibility and overlay permissions during tests
 * using UI Automator to navigate Android settings screens.
 */
class PermissionAutomator {
    
    companion object {
        private const val TAG = "PermissionAutomator"
        private const val MAX_SCROLL_ATTEMPTS = 10
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val APP_PACKAGE = "com.example.whiz.debug"
        private const val APP_NAME = "WhizVoice"
        private const val DEBUG_APP_NAME = "WhizVoice DEBUG"  // The debug version shown in settings (may or may not have emoji)
        private const val DEBUG_APP_NAME_WITH_EMOJI = "🧪 WhizVoice DEBUG"  // With test tube emoji
        private const val TIMEOUT_MS = 5000L
        private const val SHORT_TIMEOUT_MS = 2000L
    }
    
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    
    /**
     * Automatically handles any permission dialogs that appear.
     * Returns true if permissions were granted, false if no dialogs were found.
     */
    fun handlePermissionDialogs(): Boolean = runBlocking {
        Log.d(TAG, "🔍 Checking for permission dialogs...")
        
        var permissionsHandled = false
        
        // Check for accessibility permission dialog
        if (handleAccessibilityPermissionDialog()) {
            permissionsHandled = true
        }
        
        // Check for overlay permission dialog  
        if (handleOverlayPermissionDialog()) {
            permissionsHandled = true
        }
        
        // Check for microphone permission dialog
        if (handleMicrophonePermissionDialog()) {
            permissionsHandled = true
        }
        
        if (permissionsHandled) {
            Log.d(TAG, "✅ Permissions granted successfully")
            // Wait for app to return to foreground
            delay(2000)
            returnToApp()
        } else {
            Log.d(TAG, "ℹ️ No permission dialogs found")
        }
        
        return@runBlocking permissionsHandled
    }
    
    /**
     * Handles accessibility permission dialog and navigates settings to grant it.
     */
    private suspend fun handleAccessibilityPermissionDialog(): Boolean {
        Log.d(TAG, "Checking for accessibility permission dialog...")
        
        // First check for the app's own permission dialog by content description
        val accessibilityDialog = device.findObject(By.desc("Accessibility permission dialog"))
        if (accessibilityDialog != null) {
            Log.d(TAG, "📍 Found accessibility permission dialog by content description")
            
            // Click the "Open Settings" button
            val openSettingsButton = device.findObject(By.desc("Open accessibility settings button"))
                ?: device.findObject(By.text("Open Settings"))
                ?: device.findObject(By.text("Settings"))
            
            if (openSettingsButton != null) {
                Log.d(TAG, "Clicking Open Settings button...")
                openSettingsButton.click()
                device.waitForIdle()
                delay(1500) // Wait for Settings to open
                
                // Now we should be in Android Settings - Accessibility
                if (device.currentPackageName == SETTINGS_PACKAGE) {
                    // Find and enable WhizVoice accessibility service
                    return enableAccessibilityService()
                } else {
                    Log.w(TAG, "Not in settings after clicking button, trying to navigate manually")
                    navigateToAccessibilitySettings()
                    return enableAccessibilityService()
                }
            }
        }
        
        // Also check for text patterns in case content description isn't set
        val dialogPatterns = listOf(
            "Enable Accessibility Service",
            "Accessibility permission",
            "needs accessibility",
            "Enable accessibility",
            "Accessibility service"
        )
        
        var dialogFound = false
        for (pattern in dialogPatterns) {
            if (device.wait(Until.hasObject(By.textContains(pattern)), SHORT_TIMEOUT_MS)) {
                Log.d(TAG, "📍 Found accessibility dialog with pattern: $pattern")
                dialogFound = true
                break
            }
        }
        
        if (!dialogFound) {
            return false
        }
        
        Log.d(TAG, "🔧 Handling accessibility permission...")
        
        // Click on Settings or OK button to go to accessibility settings
        val actionButton = device.findObject(By.text("Settings"))
            ?: device.findObject(By.text("Open Settings"))
            ?: device.findObject(By.text("Go to Settings"))
            ?: device.findObject(By.text("OK"))
        
        if (actionButton != null) {
            actionButton.click()
            device.waitForIdle()
            delay(1000)
        }
        
        // Now we should be in Android Settings - Accessibility
        if (device.currentPackageName != SETTINGS_PACKAGE) {
            Log.w(TAG, "Not in settings after clicking button, trying to navigate manually")
            navigateToAccessibilitySettings()
        }
        
        // Find and enable WhizVoice accessibility service
        return enableAccessibilityService()
    }
    
    /**
     * Handles overlay permission dialog and navigates settings to grant it.
     */
    private suspend fun handleOverlayPermissionDialog(): Boolean {
        Log.d(TAG, "Checking for overlay permission dialog...")
        
        // First check for the app's own permission dialog by content description
        val overlayDialog = device.findObject(By.desc("Overlay permission dialog"))
        if (overlayDialog != null) {
            Log.d(TAG, "📍 Found overlay permission dialog by content description")
            
            // Click the "Grant Permission" button
            val grantButton = device.findObject(By.desc("Grant overlay permission button"))
                ?: device.findObject(By.text("Grant Permission"))
            
            if (grantButton != null) {
                Log.d(TAG, "Clicking Grant Permission button...")
                grantButton.click()
                device.waitForIdle()
                delay(1500) // Wait for Settings to open
                
                // Now we should be in Settings
                return handleOverlaySettingsPage()
            }
        }
        
        // Also check for text patterns in case content description isn't set
        val dialogPatterns = listOf(
            "Display Over Other Apps Permission Required",
            "Display over other apps",
            "overlay permission",
            "appear on top",
            "draw over",
            "system alert window"
        )
        
        var dialogFound = false
        for (pattern in dialogPatterns) {
            if (device.wait(Until.hasObject(By.textContains(pattern)), SHORT_TIMEOUT_MS)) {
                Log.d(TAG, "📍 Found overlay dialog with pattern: $pattern")
                dialogFound = true
                break
            }
        }
        
        if (!dialogFound) {
            return false
        }
        
        Log.d(TAG, "🔧 Handling overlay permission...")
        
        // Click on Settings or OK button to go to overlay settings
        val actionButton = device.findObject(By.text("Settings"))
            ?: device.findObject(By.text("Open Settings"))
            ?: device.findObject(By.text("Go to Settings"))
            ?: device.findObject(By.text("OK"))
        
        if (actionButton != null) {
            actionButton.click()
            device.waitForIdle()
            delay(1000)
        }
        
        // Wait for settings to load
        device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), TIMEOUT_MS)
        
        // Now we should be in Android Settings - but we might be on a list of apps
        // or directly on the WhizVoice overlay permission page
        if (device.currentPackageName == SETTINGS_PACKAGE) {
            // Check if we're already on the WhizVoice overlay page
            val whizPageIndicator = device.findObject(By.text("WhizVoice"))
                ?: device.findObject(By.text(APP_NAME))
            
            if (whizPageIndicator != null) {
                // We're already on the WhizVoice page, just enable the toggle
                return enableOverlayToggle()
            } else {
                // We're on the app list, need to find and click WhizVoice
                return enableOverlayPermission()
            }
        }
        
        return false
    }
    
    /**
     * Handles the overlay settings page after clicking Grant Permission.
     * This is called after the app's dialog opens Settings.
     */
    private suspend fun handleOverlaySettingsPage(): Boolean {
        Log.d(TAG, "Handling overlay settings page...")
        
        // Wait for Settings to fully load
        device.wait(Until.hasObject(By.pkg(SETTINGS_PACKAGE)), TIMEOUT_MS)
        delay(1000)
        
        // Check if we're in Settings
        if (device.currentPackageName != SETTINGS_PACKAGE) {
            Log.w(TAG, "Not in Settings app, current package: ${device.currentPackageName}")
            return false
        }
        
        // Since the package name wasn't passed, we're on the app list
        // Need to find and click WhizVoice
        return enableOverlayPermission()
    }
    
    /**
     * Handles microphone permission dialog.
     */
    private suspend fun handleMicrophonePermissionDialog(): Boolean {
        Log.d(TAG, "Checking for microphone permission dialog...")
        
        // Look for standard Android permission dialog
        val permissionDialog = device.findObject(By.textContains("record audio"))
            ?: device.findObject(By.textContains("microphone"))
        
        if (permissionDialog == null) {
            return false
        }
        
        Log.d(TAG, "🎤 Granting microphone permission...")
        
        // Click Allow button
        val allowButton = device.findObject(By.text("Allow"))
            ?: device.findObject(By.text("While using the app"))
            ?: device.findObject(By.text("Only this time"))
        
        if (allowButton != null) {
            allowButton.click()
            delay(500)
            return true
        }
        
        return false
    }
    
    /**
     * Navigate to accessibility settings manually.
     */
    private suspend fun navigateToAccessibilitySettings() {
        Log.d(TAG, "Navigating to accessibility settings...")
        
        // Open settings app
        val intent = InstrumentationRegistry.getInstrumentation().context.packageManager
            .getLaunchIntentForPackage(SETTINGS_PACKAGE)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
            device.waitForIdle()
            delay(1000)
        }
        
        // Click on Accessibility
        val accessibilityOption = findAndClickSetting("Accessibility")
        if (!accessibilityOption) {
            Log.e(TAG, "Could not find Accessibility option in settings")
        }
    }
    
    /**
     * Enable WhizVoice accessibility service in settings.
     */
    private suspend fun enableAccessibilityService(): Boolean {
        Log.d(TAG, "Enabling WhizVoice accessibility service...")
        
        // Wait for the accessibility list to load
        delay(1500)
        device.waitForIdle()
        
        // Look for WhizVoice DEBUG first (for debug builds)
        var serviceFound = false
        
        // Method 1: Look for the debug version first (use contains to handle emoji variations)
        Log.d(TAG, "Looking for WhizVoice DEBUG in accessibility services...")
        var whizDebugService = device.findObject(By.textContains("WhizVoice DEBUG"))
            ?: device.findObject(By.text(DEBUG_APP_NAME_WITH_EMOJI))
            ?: device.findObject(By.text(DEBUG_APP_NAME))
        
        if (whizDebugService != null) {
            val serviceText = whizDebugService.text
            Log.d(TAG, "Found debug service: '$serviceText', clicking...")
            whizDebugService.click()
            serviceFound = true
        }
        
        // Method 2: If not found, check if we need to go into Downloaded apps section
        if (!serviceFound) {
            val downloadedApps = device.findObject(By.text("Downloaded apps"))
                ?: device.findObject(By.text("Downloaded services"))
                ?: device.findObject(By.text("Installed services"))
            
            if (downloadedApps != null) {
                Log.d(TAG, "Found Downloaded apps section, clicking...")
                downloadedApps.click()
                device.waitForIdle()
                delay(1000)
                
                // Now look for WhizVoice DEBUG again
                whizDebugService = device.findObject(By.textContains("WhizVoice DEBUG"))
                    ?: device.findObject(By.text(DEBUG_APP_NAME_WITH_EMOJI))
                    ?: device.findObject(By.text(DEBUG_APP_NAME))
                
                if (whizDebugService != null) {
                    Log.d(TAG, "Found WhizVoice DEBUG in Downloaded apps, clicking...")
                    whizDebugService.click()
                    serviceFound = true
                }
            }
        }
        
        // Method 3: Try scrolling to find it
        if (!serviceFound) {
            Log.d(TAG, "Scrolling to find WhizVoice DEBUG...")
            val scrollable = UiScrollable(UiSelector().scrollable(true))
            
            if (scrollable.exists()) {
                scrollable.maxSearchSwipes = MAX_SCROLL_ATTEMPTS
                
                try {
                    // Try to scroll to WhizVoice DEBUG
                    val found = scrollable.scrollTextIntoView(DEBUG_APP_NAME)
                    if (found) {
                        whizDebugService = device.findObject(By.text(DEBUG_APP_NAME))
                        if (whizDebugService != null) {
                            whizDebugService.click()
                            serviceFound = true
                            Log.d(TAG, "Found and clicked WhizVoice DEBUG after scrolling")
                        }
                    }
                } catch (e: UiObjectNotFoundException) {
                    Log.w(TAG, "Could not find WhizVoice DEBUG by scrolling")
                }
            }
        }
        
        // Method 4: Fall back to regular WhizVoice if debug not found
        if (!serviceFound) {
            Log.d(TAG, "Looking for regular WhizVoice as fallback...")
            val whizService = device.findObject(By.text(APP_NAME))
                ?: device.findObject(By.textContains("WhizVoice"))
                ?: device.findObject(By.textContains("Whiz"))
            
            if (whizService != null) {
                // Make sure we're not clicking the production version by mistake
                val serviceText = whizService.text
                if (serviceText != null && !serviceText.contains("DEBUG") && serviceText == "Whiz Voice") {
                    Log.w(TAG, "Found production Whiz Voice, but looking for debug version")
                } else {
                    whizService.click()
                    serviceFound = true
                    Log.d(TAG, "Clicked on WhizVoice service")
                }
            }
        }
        
        if (!serviceFound) {
            Log.e(TAG, "Could not find WhizVoice DEBUG in accessibility services")
            return false
        }
        
        device.waitForIdle()
        delay(1000)
        
        // Now we should be on the WhizVoice accessibility page
        // Look for the toggle switch
        val toggleSwitch = device.findObject(By.clazz("android.widget.Switch"))
            ?: device.findObject(By.checkable(true))
        
        if (toggleSwitch != null && !toggleSwitch.isChecked) {
            toggleSwitch.click()
            device.waitForIdle()
            delay(500)
            
            // Handle confirmation dialog if it appears
            val allowButton = device.findObject(By.text("Allow"))
                ?: device.findObject(By.text("OK"))
                ?: device.findObject(By.text("Turn on"))
            
            if (allowButton != null) {
                allowButton.click()
                delay(500)
            }
            
            Log.d(TAG, "✅ Accessibility service enabled")
            return true
        } else if (toggleSwitch?.isChecked == true) {
            Log.d(TAG, "✅ Accessibility service already enabled")
            return true
        }
        
        return false
    }
    
    /**
     * Enable overlay permission in settings.
     */
    private suspend fun enableOverlayPermission(): Boolean {
        Log.d(TAG, "Enabling overlay permission...")
        
        // We should be on the "Display over other apps" page with a list of apps
        // Since package name wasn't passed, we need to scroll through all apps to find WhizVoice
        
        // Wait for the list to load
        delay(1500)
        device.waitForIdle()
        
        // Look for WhizVoice in the list - try multiple approaches
        var appFound = false
        
        // Method 1: Try to find directly if visible (look for DEBUG version first)
        Log.d(TAG, "Looking for WhizVoice DEBUG in app list...")
        var whizApp = device.findObject(By.textContains("WhizVoice DEBUG"))  // Most flexible - catches with or without emoji
            ?: device.findObject(By.text(DEBUG_APP_NAME_WITH_EMOJI))
            ?: device.findObject(By.text(DEBUG_APP_NAME))
            ?: device.findObject(By.textContains("WhizVoice"))  // Fallback to any WhizVoice
            ?: device.findObject(By.text("Whiz"))
        
        if (whizApp != null) {
            val appText = whizApp.text
            Log.d(TAG, "Found app: $appText, clicking...")
            whizApp.click()
            appFound = true
        }
        
        // Method 2: Scroll through the list to find it
        if (!appFound) {
            Log.d(TAG, "Scrolling to find WhizVoice...")
            val scrollable = UiScrollable(UiSelector().scrollable(true))
            
            if (scrollable.exists()) {
                scrollable.maxSearchSwipes = MAX_SCROLL_ATTEMPTS
                
                // First scroll to top
                scrollable.scrollToBeginning(3)
                delay(500)
                
                // Now scroll down looking for WhizVoice DEBUG
                try {
                    // Try DEBUG version first
                    var found = scrollable.scrollTextIntoView(DEBUG_APP_NAME)
                    if (found) {
                        // Click on WhizVoice DEBUG
                        val whizItem = device.findObject(By.text(DEBUG_APP_NAME))
                            ?: device.findObject(By.textContains("WhizVoice DEBUG"))
                        
                        if (whizItem != null) {
                            whizItem.click()
                            appFound = true
                            Log.d(TAG, "Found and clicked WhizVoice DEBUG after scrolling")
                        }
                    }
                    
                    // If not found, try regular WhizVoice
                    if (!appFound) {
                        found = scrollable.scrollTextIntoView("WhizVoice")
                        if (found) {
                            val whizItem = device.findObject(By.text("WhizVoice"))
                                ?: device.findObject(By.textContains("WhizVoice"))
                            
                            if (whizItem != null) {
                                whizItem.click()
                                appFound = true
                                Log.d(TAG, "Found and clicked WhizVoice after scrolling")
                            }
                        }
                    }
                } catch (e: UiObjectNotFoundException) {
                    Log.w(TAG, "Could not find WhizVoice/WhizVoice DEBUG by scrolling")
                }
            }
        }
        
        // Method 3: Try searching by package name pattern
        if (!appFound) {
            Log.d(TAG, "Looking for app by package name pattern...")
            
            // Look for any text containing "whiz" (case insensitive)
            val whizPattern = device.findObject(By.text(java.util.regex.Pattern.compile(".*[Ww]hiz.*")))
            if (whizPattern != null) {
                whizPattern.click()
                appFound = true
                Log.d(TAG, "Found app by pattern matching")
            }
        }
        
        if (!appFound) {
            Log.e(TAG, "Could not find WhizVoice in overlay permissions list")
            return false
        }
        
        device.waitForIdle()
        delay(1000)
        
        // Now we should be on the WhizVoice overlay permission page
        return enableOverlayToggle()
    }
    
    /**
     * Enable the overlay permission toggle on the app's permission page.
     */
    private suspend fun enableOverlayToggle(): Boolean {
        Log.d(TAG, "Looking for overlay permission toggle...")
        
        // Look for the toggle switch
        val toggleSwitch = device.findObject(By.clazz("android.widget.Switch"))
            ?: device.findObject(By.checkable(true))
            ?: device.findObject(By.res("android:id/switch_widget"))
        
        if (toggleSwitch != null) {
            if (!toggleSwitch.isChecked) {
                Log.d(TAG, "Enabling overlay permission toggle...")
                toggleSwitch.click()
                delay(500)
                
                // Check if any confirmation dialog appears
                val confirmButton = device.findObject(By.text("Allow"))
                    ?: device.findObject(By.text("Yes"))
                    ?: device.findObject(By.text("OK"))
                
                if (confirmButton != null) {
                    confirmButton.click()
                    delay(500)
                }
                
                Log.d(TAG, "✅ Overlay permission enabled")
                return true
            } else {
                Log.d(TAG, "✅ Overlay permission already enabled")
                return true
            }
        } else {
            Log.e(TAG, "Could not find overlay permission toggle")
            return false
        }
    }
    
    /**
     * Find and click a setting option by text.
     */
    private fun findAndClickSetting(settingName: String): Boolean {
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        scrollable.maxSearchSwipes = MAX_SCROLL_ATTEMPTS
        
        return try {
            val setting = scrollable.getChildByText(
                UiSelector().className("android.widget.TextView"),
                settingName,
                true
            )
            
            if (setting != null && setting.exists()) {
                setting.click()
                device.waitForIdle()
                true
            } else {
                false
            }
        } catch (e: UiObjectNotFoundException) {
            Log.w(TAG, "Setting not found: $settingName")
            false
        }
    }
    
    /**
     * Return to the app after granting permissions.
     */
    private fun returnToApp() {
        Log.d(TAG, "Returning to app...")
        
        // Try to click back multiple times
        repeat(5) {
            if (device.currentPackageName == APP_PACKAGE) {
                Log.d(TAG, "✅ Returned to app")
                return
            }
            device.pressBack()
            device.waitForIdle()
        }
        
        // If back doesn't work, try to relaunch the app
        if (device.currentPackageName != APP_PACKAGE) {
            val intent = InstrumentationRegistry.getInstrumentation().context.packageManager
                .getLaunchIntentForPackage(APP_PACKAGE)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                InstrumentationRegistry.getInstrumentation().context.startActivity(intent)
                device.waitForIdle()
            }
        }
    }
    
    /**
     * Check if all required permissions are granted.
     * This can be called before running tests to verify setup.
     */
    fun verifyPermissions(): PermissionStatus {
        Log.d(TAG, "Verifying permissions...")
        
        val status = PermissionStatus()
        
        // Check accessibility service
        status.accessibilityEnabled = isAccessibilityServiceEnabled()
        
        // Check overlay permission
        status.overlayEnabled = isOverlayPermissionGranted()
        
        // Check microphone permission
        status.microphoneEnabled = isMicrophonePermissionGranted()
        
        Log.d(TAG, "Permission status: $status")
        return status
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        // This would need to be implemented based on your accessibility service
        // For now, return a placeholder
        return try {
            val serviceClass = Class.forName("com.example.whiz.accessibility.WhizAccessibilityService")
            val method = serviceClass.getMethod("isServiceEnabled")
            method.invoke(null) as Boolean
        } catch (e: Exception) {
            Log.w(TAG, "Could not check accessibility service status: ${e.message}")
            false
        }
    }
    
    private fun isOverlayPermissionGranted(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(
                InstrumentationRegistry.getInstrumentation().targetContext
            )
        } else {
            true
        }
    }
    
    private fun isMicrophonePermissionGranted(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            InstrumentationRegistry.getInstrumentation().targetContext,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    data class PermissionStatus(
        var accessibilityEnabled: Boolean = false,
        var overlayEnabled: Boolean = false,
        var microphoneEnabled: Boolean = false
    ) {
        fun allGranted(): Boolean = accessibilityEnabled && overlayEnabled && microphoneEnabled
        
        override fun toString(): String {
            return "PermissionStatus(accessibility=$accessibilityEnabled, overlay=$overlayEnabled, microphone=$microphoneEnabled)"
        }
    }
}