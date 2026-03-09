package com.example.whiz.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.util.Base64
import android.view.KeyEvent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whiz.BuildConfig
import com.example.whiz.MainActivity
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.data.api.ApiService
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.MessageDraftOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screen Agent Tools - Consolidated tools for screen interaction via Accessibility Service
 * Combines app launching and app-specific interactions (like WhatsApp chat selection)
 */
@Singleton
class ScreenAgentTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    private val TAG = "ScreenAgentTools"

    // Recent actions tracking for UI dump context (max 5 actions)
    private val recentActions = mutableListOf<String>()
    private val maxRecentActions = 5

    /**
     * Track an action for UI dump context.
     * Call this when performing significant screen agent actions.
     */
    fun trackAction(action: String) {
        synchronized(recentActions) {
            recentActions.add(action)
            while (recentActions.size > maxRecentActions) {
                recentActions.removeAt(0)
            }
        }
    }

    /**
     * Clear recent actions (call on successful operation completion).
     */
    fun clearRecentActions() {
        synchronized(recentActions) {
            recentActions.clear()
        }
    }

    /**
     * Get a copy of recent actions for UI dump.
     */
    private fun getRecentActionsCopy(): List<String> {
        synchronized(recentActions) {
            return recentActions.toList()
        }
    }
    
    // Result data classes
    data class LaunchResult(
        val success: Boolean,
        val appName: String,
        val packageName: String? = null,
        val error: String? = null,
        val overlayStarted: Boolean = false,
        val overlayPermissionRequired: Boolean = false
    )
    
    data class WhatsAppResult(
        val success: Boolean,
        val action: String,
        val chatName: String? = null,
        val error: String? = null
    )

    data class SMSResult(
        val success: Boolean,
        val action: String,
        val contactName: String? = null,
        val error: String? = null
    )

    data class DraftResult(
        val success: Boolean,
        val message: String? = null,
        val error: String? = null,
        val overlayShown: Boolean = false
    )

    data class MusicActionResult(
        val success: Boolean,
        val action: String,
        val query: String? = null,
        val error: String? = null,
        val nowPlaying: String? = null
    )

    data class MapsActionResult(
        val success: Boolean,
        val action: String,
        val location: String? = null,
        val mode: String? = null,
        val error: String? = null
    )

    data class CallButtonResult(
        val success: Boolean,
        val dialedNumber: String? = null,
        val error: String? = null,
        val speakerphoneEnabled: Boolean = false
    )

    data class FitbitResult(
        val success: Boolean,
        val action: String? = null,
        val calories: Int? = null,
        val error: String? = null
    )

    data class CloseOtherAppResult(
        val success: Boolean,
        val appName: String,
        val error: String? = null
    )

    // ========== Phone Call Functions ==========

    /**
     * Press the call button in the Google Dialer app via accessibility service.
     * Verifies the dialer is in the foreground and optionally checks the displayed number.
     */
    suspend fun pressCallButton(expectedNumber: String?, speakerphone: Boolean = true): CallButtonResult {
        Log.i(TAG, "pressCallButton called, expectedNumber=$expectedNumber, speakerphone=$speakerphone")

        val accessibilityService = WhizAccessibilityService.getInstance()
            ?: return CallButtonResult(
                success = false,
                error = "Accessibility service not enabled. Please enable it in settings."
            )

        val rootNode = accessibilityService.getCurrentRootNode()
            ?: return CallButtonResult(
                success = false,
                error = "Could not get current screen. Is the dialer open?"
            )

        val displayedNumber: String?
        try {
            // Verify the dialer app is in the foreground
            val currentPackage = rootNode.packageName?.toString() ?: ""
            if (currentPackage != "com.google.android.dialer") {
                return CallButtonResult(
                    success = false,
                    error = "Dialer is not in the foreground. Current app: $currentPackage"
                )
            }

            // Read the displayed number from the dialer
            val digitsNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.dialer:id/digits"
            )
            displayedNumber = digitsNodes?.firstOrNull()?.text?.toString()
            Log.i(TAG, "Displayed number in dialer: $displayedNumber")

            // If expectedNumber is provided, verify it matches
            if (expectedNumber != null && displayedNumber != null) {
                val normalizedExpected = expectedNumber.replace(Regex("[^0-9]"), "")
                val normalizedDisplayed = displayedNumber.replace(Regex("[^0-9]"), "")

                val longer = if (normalizedExpected.length >= normalizedDisplayed.length) normalizedExpected else normalizedDisplayed
                val shorter = if (normalizedExpected.length < normalizedDisplayed.length) normalizedExpected else normalizedDisplayed

                if (!longer.endsWith(shorter)) {
                    return CallButtonResult(
                        success = false,
                        dialedNumber = displayedNumber,
                        error = "Number mismatch: expected '$expectedNumber' but dialer shows '$displayedNumber'"
                    )
                }
            }

            // Find and click the call button
            val callButtonNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.dialer:id/dialpad_voice_call_button"
            )
            val callButton = callButtonNodes?.firstOrNull()
            if (callButton == null) {
                return CallButtonResult(
                    success = false,
                    dialedNumber = displayedNumber,
                    error = "Call button not found in dialer. Is the dialpad visible?"
                )
            }

            val clicked = callButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "Call button click result: $clicked")

            if (!clicked) {
                return CallButtonResult(
                    success = false,
                    dialedNumber = displayedNumber,
                    error = "Failed to click call button"
                )
            }
        } finally {
            rootNode.recycle()
        }

        // Enable speakerphone by clicking the Speaker button in the in-call UI
        var speakerphoneEnabled = false
        if (speakerphone) {
            speakerphoneEnabled = enableSpeakerphoneViaAccessibility(accessibilityService)
        }

        return CallButtonResult(
            success = true,
            dialedNumber = displayedNumber,
            speakerphoneEnabled = speakerphoneEnabled
        )
    }

    /**
     * Wait for the in-call UI to appear and click the Speaker button via accessibility.
     * Polls for up to 5 seconds for the Speaker button to become available.
     */
    private suspend fun enableSpeakerphoneViaAccessibility(
        accessibilityService: WhizAccessibilityService
    ): Boolean {
        // Wait for the in-call UI to load after placing the call
        for (attempt in 1..10) {
            delay(500)
            val root = accessibilityService.getCurrentRootNode() ?: continue
            try {
                val currentPackage = root.packageName?.toString() ?: ""
                if (currentPackage != "com.google.android.dialer") {
                    Log.d(TAG, "Speakerphone attempt $attempt: not in dialer ($currentPackage)")
                    continue
                }

                // Find the Speaker button by content description
                val speakerNode = findNodeByContentDesc(root, "Speaker")
                if (speakerNode != null) {
                    // The clickable parent is the checkable view wrapping the Speaker icon
                    val clickTarget = findClickableParent(speakerNode) ?: speakerNode
                    val clicked = clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Speaker button click result: $clicked (attempt $attempt)")
                    return clicked
                }
                Log.d(TAG, "Speakerphone attempt $attempt: Speaker button not found yet")
            } finally {
                root.recycle()
            }
        }
        Log.w(TAG, "Failed to find Speaker button after all attempts")
        return false
    }

    private fun findNodeByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString() == desc) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDesc(child, desc)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent ?: return null
        while (true) {
            if (current.isClickable) return current
            val parent = current.parent ?: return null
            current.recycle()
            current = parent
        }
    }

    // ========== App Launch Functions ==========
    
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    fun launchApp(appName: String, enableOverlay: Boolean = true): LaunchResult {
        Log.d(TAG, "Attempting to launch app: $appName")
        trackAction("launchApp: $appName")

        try {
            val packageManager = context.packageManager
            val normalizedAppName = appName.lowercase().trim()
            Log.d(TAG, "Normalized app name: $normalizedAppName")
            
            // Get all installed apps
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            
            // Try to find the app by label (display name)
            var bestMatch: Pair<String, Float>? = null
            
            for (appInfo in installedApps) {
                // Skip system apps that aren't launchable
                val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent == null) continue
                
                // Get the app label
                val appLabel = packageManager.getApplicationLabel(appInfo).toString()
                val normalizedLabel = appLabel.lowercase()
                
                // Calculate match score
                val matchScore = calculateMatchScore(normalizedAppName, normalizedLabel, appInfo.packageName)
                
                if (matchScore > 0 && (bestMatch == null || matchScore > bestMatch.second)) {
                    bestMatch = Pair(appInfo.packageName, matchScore)
                    Log.d(TAG, "Found potential match: $appLabel (${appInfo.packageName}) with score $matchScore")
                }
            }
            
            // Launch the best match if we found one
            if (bestMatch != null && bestMatch.second >= 0.5f) {
                val packageName = bestMatch.first
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    // Set pending flag BEFORE launching to prevent race with onAppBackgrounded
                    // This ensures VoiceManager doesn't stop listening during the transition
                    var overlayStarted = false
                    var overlayPermissionRequired = false
                    Log.d(TAG, "Checking overlay (fuzzy): enableOverlay=$enableOverlay, isWhizApp=${isWhizApp(packageName)}, hasPermission=${hasOverlayPermission()}")
                    if (enableOverlay && !isWhizApp(packageName) && hasOverlayPermission()) {
                        BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
                        Log.d(TAG, "Set pendingStartTimestamp before launching (fuzzy)")
                    }

                    context.startActivity(launchIntent)

                    val appLabel = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()

                    // Start bubble overlay if enabled and we have permission
                    if (enableOverlay && !isWhizApp(packageName)) {
                        if (hasOverlayPermission()) {
                            Log.d(TAG, "Starting bubble overlay service (fuzzy)")
                            overlayStarted = startBubbleOverlay()
                            Log.d(TAG, "Bubble overlay started (fuzzy): $overlayStarted")
                            // Clear pending flag if bubble failed to start
                            if (!overlayStarted) {
                                BubbleOverlayService.pendingStartTimestamp = 0L
                                Log.d(TAG, "Cleared pendingStartTimestamp because bubble failed to start (fuzzy)")
                            }
                        } else {
                            overlayPermissionRequired = true
                            Log.w(TAG, "Overlay permission required to show bubble")
                        }
                    } else {
                        Log.d(TAG, "Not starting overlay (fuzzy): enableOverlay=$enableOverlay, isWhizApp=${isWhizApp(packageName)}")
                    }
                    
                    Log.i(TAG, "Successfully launched app: $appLabel ($packageName)")
                    return LaunchResult(
                        success = true,
                        appName = appLabel,
                        packageName = packageName,
                        overlayStarted = overlayStarted,
                        overlayPermissionRequired = overlayPermissionRequired
                    )
                }
            }
            
            // Common app name mappings
            val commonMappings = mapOf(
                "chrome" to "com.android.chrome",
                "gmail" to "com.google.android.gm",
                "youtube" to "com.google.android.youtube",
                "youtube music" to "com.google.android.apps.youtube.music",
                "maps" to "com.google.android.apps.maps",
                "play store" to "com.android.vending",
                "camera" to "com.android.camera2",
                "photos" to "com.google.android.apps.photos",
                "calendar" to "com.google.android.calendar",
                "calculator" to "com.google.android.calculator2",
                "clock" to "com.google.android.deskclock",
                "messages" to "com.google.android.apps.messaging",
                "whatsapp" to "com.whatsapp",
                "instagram" to "com.instagram.android",
                "facebook" to "com.facebook.katana",
                "twitter" to "com.twitter.android",
                "x" to "com.twitter.android",
                "spotify" to "com.spotify.music",
                "netflix" to "com.netflix.mediaclient",
                "settings" to "com.android.settings",
                "asana" to "com.asana.app",
                "a sauna" to "com.asana.app"
            )
            
            // Try common mappings
            Log.d(TAG, "Checking common mappings for: $normalizedAppName")
            val mappedPackage = commonMappings[normalizedAppName]
            Log.d(TAG, "Mapped package for '$normalizedAppName': $mappedPackage")
            if (mappedPackage != null) {
                var launchIntent = packageManager.getLaunchIntentForPackage(mappedPackage)
                Log.d(TAG, "Launch intent for $mappedPackage: ${launchIntent != null}")
                
                // If getLaunchIntentForPackage returns null, try to manually create the intent
                if (launchIntent == null && mappedPackage == "com.whatsapp") {
                    Log.d(TAG, "Trying manual intent creation for WhatsApp")
                    launchIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(mappedPackage)
                        component = android.content.ComponentName(mappedPackage, "com.whatsapp.Main")
                    }
                }
                
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    // Set pending flag BEFORE launching to prevent race with onAppBackgrounded
                    // This ensures VoiceManager doesn't stop listening during the transition
                    var overlayStarted = false
                    var overlayPermissionRequired = false
                    Log.i(TAG, "🔵 BUBBLE CHECK: enableOverlay=$enableOverlay, packageName=$mappedPackage, isWhizApp=${isWhizApp(mappedPackage)}, hasPermission=${hasOverlayPermission()}")
                    if (enableOverlay && !isWhizApp(mappedPackage) && hasOverlayPermission()) {
                        BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
                        Log.i(TAG, "🔵 Set pendingStartTimestamp before launching (common mappings)")
                    }

                    try {
                        context.startActivity(launchIntent)

                        val appLabel = try {
                            packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(mappedPackage, 0)
                            ).toString()
                        } catch (e: Exception) {
                            appName
                        }

                        // Start bubble overlay if enabled and we have permission
                        if (enableOverlay && !isWhizApp(mappedPackage)) {
                            if (hasOverlayPermission()) {
                                Log.i(TAG, "🔵 STARTING BUBBLE OVERLAY SERVICE for $mappedPackage")
                                overlayStarted = startBubbleOverlay()
                                Log.i(TAG, "🔵 BUBBLE OVERLAY RESULT: $overlayStarted")
                                // Clear pending flag if bubble failed to start
                                if (!overlayStarted) {
                                    BubbleOverlayService.pendingStartTimestamp = 0L
                                    Log.i(TAG, "🔵 Cleared pendingStartTimestamp because bubble failed to start")
                                }
                            } else {
                                overlayPermissionRequired = true
                                Log.w(TAG, "🔵 OVERLAY PERMISSION REQUIRED to show bubble")
                            }
                        } else {
                            Log.i(TAG, "🔵 NOT STARTING BUBBLE: enableOverlay=$enableOverlay, isWhizApp=${isWhizApp(mappedPackage)}")
                        }
                        
                        Log.i(TAG, "Successfully launched mapped app: $appLabel ($mappedPackage)")
                        return LaunchResult(
                            success = true,
                            appName = appLabel,
                            packageName = mappedPackage,
                            overlayStarted = overlayStarted,
                            overlayPermissionRequired = overlayPermissionRequired
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch $mappedPackage: ${e.message}", e)
                        // Clear pending flag on launch failure
                        BubbleOverlayService.pendingStartTimestamp = 0L
                    }
                }
            }

            Log.w(TAG, "Could not find app matching: $appName")
            logScreenAgentError(
                reason = "app_not_found",
                errorMessage = "Could not find an app matching '$appName'",
                packageName = null
            )
            return LaunchResult(
                success = false,
                appName = appName,
                error = "Could not find an app matching '$appName'"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $appName", e)
            logScreenAgentError(
                reason = "app_launch_error",
                errorMessage = "Error launching app '$appName': ${e.message}",
                packageName = null
            )
            return LaunchResult(
                success = false,
                appName = appName,
                error = "Error launching app: ${e.message}"
            )
        }
    }

    /**
     * Launch Google Maps directly to search results using a geo: intent.
     * This bypasses the fragile search bar automation (find search box, click, type, submit).
     */
    private fun launchGoogleMapsSearch(query: String, enableOverlay: Boolean = true): LaunchResult {
        Log.d(TAG, "Launching Google Maps search with geo: intent for query: $query")
        trackAction("launchGoogleMapsSearch: $query")

        val mapsPackage = "com.google.android.apps.maps"
        var overlayStarted = false
        var overlayPermissionRequired = false

        try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(mapsPackage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // Set pending overlay flag BEFORE launching (same pattern as launchApp)
            if (enableOverlay && hasOverlayPermission()) {
                BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            }

            context.startActivity(intent)

            // Start bubble overlay if enabled
            if (enableOverlay) {
                if (hasOverlayPermission()) {
                    overlayStarted = startBubbleOverlay()
                    if (!overlayStarted) {
                        BubbleOverlayService.pendingStartTimestamp = 0L
                    }
                } else {
                    overlayPermissionRequired = true
                }
            }

            return LaunchResult(
                success = true,
                appName = "Maps",
                packageName = mapsPackage,
                overlayStarted = overlayStarted,
                overlayPermissionRequired = overlayPermissionRequired
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Google Maps search", e)
            BubbleOverlayService.pendingStartTimestamp = 0L
            return LaunchResult(
                success = false,
                appName = "Maps",
                packageName = mapsPackage,
                error = "Failed to launch Maps search: ${e.message}"
            )
        }
    }

    private fun isWhizApp(packageName: String): Boolean {
        // Only return true if the package name matches THIS app's package name exactly
        // This ensures production and debug apps don't interfere with each other
        return packageName == context.packageName
    }
    
    private fun startBubbleOverlay(): Boolean {
        Log.i(TAG, "🔵 startBubbleOverlay() called")
        return if (hasOverlayPermission()) {
            try {
                Log.i(TAG, "🔵 About to call BubbleOverlayService.start()")
                BubbleOverlayService.start(context)
                Log.i(TAG, "🔵 BubbleOverlayService.start() completed successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "🔵 FAILED to start bubble overlay", e)
                false
            }
        } else {
            Log.w(TAG, "🔵 NO OVERLAY PERMISSION to start bubble")
            false
        }
    }

    /**
     * Restore full-screen Whiz by launching MainActivity.
     * MainActivity.onResume() auto-stops the bubble overlay.
     * Fallback: explicitly stop bubble + press Home.
     */
    private fun restoreFullScreenWhiz() {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            context.startActivity(intent)
            Log.i(TAG, "restoreFullScreenWhiz: launched MainActivity")
        } catch (e: Exception) {
            Log.e(TAG, "restoreFullScreenWhiz: failed to launch MainActivity, falling back", e)
            try {
                BubbleOverlayService.stop(context)
            } catch (_: Exception) {}
            try {
                WhizAccessibilityService.getInstance()?.performGlobalActionSafely(
                    AccessibilityService.GLOBAL_ACTION_HOME
                )
            } catch (_: Exception) {}
        }
    }

    // ========== Close Other App Functions ==========

    /**
     * Common app name mappings shared between launchApp and closeOtherApp.
     */
    private val commonAppMappings = mapOf(
        "chrome" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "youtube music" to "com.google.android.apps.youtube.music",
        "maps" to "com.google.android.apps.maps",
        "play store" to "com.android.vending",
        "camera" to "com.android.camera2",
        "photos" to "com.google.android.apps.photos",
        "calendar" to "com.google.android.calendar",
        "calculator" to "com.google.android.calculator2",
        "clock" to "com.google.android.deskclock",
        "messages" to "com.google.android.apps.messaging",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "settings" to "com.android.settings",
        "asana" to "com.asana.app",
        "a sauna" to "com.asana.app"
    )

    /**
     * Resolve a user-provided app name to a (packageName, appLabel) pair.
     * Uses fuzzy matching against installed apps, then falls back to common mappings.
     * Returns null if the app cannot be found.
     */
    fun resolvePackageName(appName: String): Pair<String, String>? {
        val packageManager = context.packageManager
        val normalizedAppName = appName.lowercase().trim()

        // Try fuzzy matching against installed apps
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        var bestMatch: Triple<String, String, Float>? = null // (packageName, appLabel, score)

        for (appInfo in installedApps) {
            val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName) ?: continue
            val appLabel = packageManager.getApplicationLabel(appInfo).toString()
            val matchScore = calculateMatchScore(normalizedAppName, appLabel, appInfo.packageName)
            if (matchScore > 0 && (bestMatch == null || matchScore > bestMatch.third)) {
                bestMatch = Triple(appInfo.packageName, appLabel, matchScore)
            }
        }

        if (bestMatch != null && bestMatch.third >= 0.5f) {
            return Pair(bestMatch.first, bestMatch.second)
        }

        // Fall back to common mappings
        val mappedPackage = commonAppMappings[normalizedAppName]
        if (mappedPackage != null) {
            val appLabel = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(mappedPackage, 0)
                ).toString()
            } catch (e: Exception) {
                appName
            }
            return Pair(mappedPackage, appLabel)
        }

        return null
    }

    /**
     * Close another app by dismissing it from the Android recents screen.
     * Opens recents, finds the app card by label, swipes it up to dismiss, then goes Home.
     */
    suspend fun closeOtherApp(appName: String): CloseOtherAppResult {
        Log.i(TAG, "closeOtherApp called for: $appName")
        trackAction("closeOtherApp: $appName")

        // Track whether we started a temporary bubble for this operation
        var needToRestoreFullScreen = false

        try {
            // Resolve app name
            val resolved = resolvePackageName(appName)
            if (resolved == null) {
                Log.w(TAG, "Could not resolve app name: $appName")
                logScreenAgentError(
                    reason = "close_other_app_not_found",
                    errorMessage = "Could not find an app matching '$appName'",
                    packageName = null
                )
                return CloseOtherAppResult(
                    success = false,
                    appName = appName,
                    error = "Could not find an app matching '$appName'"
                )
            }

            val (packageName, appLabel) = resolved
            Log.i(TAG, "Resolved app: $appLabel ($packageName)")

            // Reject if target is Whiz itself
            if (isWhizApp(packageName)) {
                return CloseOtherAppResult(
                    success = false,
                    appName = appLabel,
                    error = "To close WhizVoice, use the agent_close_app tool instead."
                )
            }

            // Get accessibility service
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.e(TAG, "Accessibility service not available for closeOtherApp")
                logScreenAgentError(
                    reason = "close_other_app_no_accessibility",
                    errorMessage = "Accessibility service not available for closing '$appLabel'",
                    packageName = packageName
                )
                return CloseOtherAppResult(
                    success = false,
                    appName = appLabel,
                    error = "Accessibility service not enabled. Please enable it in settings."
                )
            }

            // Before opening recents, start bubble overlay if we're in full-screen mode.
            // Opening recents will background full-screen Whiz, causing VoiceManager to stop
            // listening. The bubble keeps the mic active so the user hears TTS confirmation.
            val wasBubbleAlreadyActive = BubbleOverlayService.isActive
            if (!wasBubbleAlreadyActive && hasOverlayPermission()) {
                Log.i(TAG, "closeOtherApp: starting temporary bubble overlay for recents operation")
                BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
                val bubbleStarted = startBubbleOverlay()
                if (bubbleStarted) {
                    needToRestoreFullScreen = true
                    // Wait for bubble service to become active
                    val bubbleReady = waitForCondition(maxWaitMs = 1000, initialDelayMs = 100) {
                        BubbleOverlayService.isActive
                    }
                    if (!bubbleReady) {
                        Log.w(TAG, "closeOtherApp: bubble started but not active in time, proceeding anyway")
                    }
                } else {
                    Log.w(TAG, "closeOtherApp: bubble failed to start, proceeding without it")
                    BubbleOverlayService.pendingStartTimestamp = 0L
                }
            }

            // Open recents screen
            Log.i(TAG, "Opening recents screen")
            val recentsOpened = accessibilityService.performGlobalActionSafely(
                AccessibilityService.GLOBAL_ACTION_RECENTS
            )
            if (!recentsOpened) {
                logScreenAgentError(
                    reason = "close_other_app_recents_failed",
                    errorMessage = "Failed to open recents screen",
                    packageName = packageName
                )
                // Clean up temporary bubble if we started one
                if (needToRestoreFullScreen) {
                    restoreFullScreenWhiz()
                }
                return CloseOtherAppResult(
                    success = false,
                    appName = appLabel,
                    error = "Failed to open recent apps screen"
                )
            }

            // Wait for recents screen to take focus (root node package changes away from Whiz)
            val whizPackage = context.packageName
            val recentsReady = waitForCondition(maxWaitMs = 1500, initialDelayMs = 100) {
                val currentPkg = WhizAccessibilityService.getCurrentPackageName()
                currentPkg != null && currentPkg != whizPackage
            }
            if (!recentsReady) {
                Log.w(TAG, "closeOtherApp: recents screen did not take focus in time, proceeding anyway")
            }

            // Try to find and dismiss the app from recents
            val dismissed = findAndDismissFromRecents(accessibilityService, appLabel)

            // Navigate back: restore full-screen Whiz or press Home
            delay(300)
            if (needToRestoreFullScreen) {
                Log.i(TAG, "closeOtherApp: restoring full-screen Whiz after recents operation")
                restoreFullScreenWhiz()
            } else {
                accessibilityService.performGlobalActionSafely(AccessibilityService.GLOBAL_ACTION_HOME)
            }

            if (dismissed) {
                Log.i(TAG, "Successfully dismissed $appLabel from recents")
                clearRecentActions()
                return CloseOtherAppResult(
                    success = true,
                    appName = appLabel
                )
            } else {
                Log.w(TAG, "Could not find $appLabel in recents")
                return CloseOtherAppResult(
                    success = false,
                    appName = appLabel,
                    error = "Could not find '$appLabel' in recent apps. It may not be running."
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error closing other app: $appName", e)
            logScreenAgentError(
                reason = "close_other_app_error",
                errorMessage = "Error closing '$appName': ${e.message}",
                packageName = null
            )
            // Recover: restore full-screen if we started a temporary bubble, otherwise press Home
            try {
                if (needToRestoreFullScreen || BubbleOverlayService.isActive) {
                    restoreFullScreenWhiz()
                } else {
                    WhizAccessibilityService.getInstance()?.performGlobalActionSafely(
                        AccessibilityService.GLOBAL_ACTION_HOME
                    )
                }
            } catch (_: Exception) {}
            return CloseOtherAppResult(
                success = false,
                appName = appName,
                error = "Error closing app: ${e.message}"
            )
        }
    }

    /**
     * Search the recents screen for an app card matching the given label and swipe it up to dismiss.
     * Scrolls through the recents carousel up to 7 times if the app isn't immediately visible.
     */
    private suspend fun findAndDismissFromRecents(
        accessibilityService: WhizAccessibilityService,
        appLabel: String
    ): Boolean {
        val normalizedLabel = appLabel.lowercase()
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()

        for (scrollAttempt in 0..7) {
            if (scrollAttempt > 0) {
                // Scroll left in the recents carousel (swipe right) to see older apps
                Log.d(TAG, "Scrolling recents carousel, attempt $scrollAttempt")
                accessibilityService.performScrollGesture(
                    startX = screenWidth * 0.2f,
                    startY = screenHeight * 0.5f,
                    endX = screenWidth * 0.8f,
                    endY = screenHeight * 0.5f,
                    duration = 300
                )
                delay(500)
            }

            val rootNode = accessibilityService.getCurrentRootNode() ?: continue

            try {
                // Recents cards use content descriptions on snapshot children, not text.
                // Search content descriptions recursively for the app label.
                val descNode = findNodeByContentDescContaining(rootNode, normalizedLabel)
                if (descNode != null) {
                    // Only dismiss if the card is actually on-screen (positive X bounds)
                    val rect = android.graphics.Rect()
                    descNode.getBoundsInScreen(rect)
                    if (rect.right > 0 && rect.left < screenWidth.toInt()) {
                        Log.i(TAG, "Found app card for '$appLabel' via content description, bounds=$rect")
                        val swipeResult = swipeUpToDismiss(accessibilityService, descNode, screenWidth, screenHeight)
                        if (swipeResult) return true
                    } else {
                        Log.d(TAG, "Found '$appLabel' but off-screen (bounds=$rect), scrolling more")
                    }
                }

                // Also try text-based search as fallback
                val textNodes = rootNode.findAccessibilityNodeInfosByText(appLabel)
                for (node in textNodes) {
                    val nodeText = node.text?.toString()?.lowercase() ?: ""
                    val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
                    if (nodeText.contains(normalizedLabel) || nodeDesc.contains(normalizedLabel)) {
                        // Walk up to find the card container — the text label is small and may
                        // not be positioned over the actual card. The card parent will have
                        // bounds that cover a large portion of the screen.
                        val cardNode = findCardParent(node, screenWidth, screenHeight)
                        if (cardNode != null) {
                            val cardRect = android.graphics.Rect()
                            cardNode.getBoundsInScreen(cardRect)
                            if (cardRect.right > 0 && cardRect.left < screenWidth.toInt()) {
                                Log.i(TAG, "Found app card for '$appLabel' via text match, card bounds=$cardRect")
                                val swipeResult = swipeUpToDismiss(accessibilityService, cardNode, screenWidth, screenHeight)
                                if (swipeResult) return true
                            }
                        } else {
                            // No card parent found — log the text node position for debugging
                            val textRect = android.graphics.Rect()
                            node.getBoundsInScreen(textRect)
                            Log.w(TAG, "Found text '$appLabel' at $textRect but no card parent — skipping swipe to avoid dismissing wrong app")
                        }
                    }
                }
            } finally {
                rootNode.recycle()
            }
        }

        // If we exhausted all scroll attempts, dump the UI for debugging
        val rootNode = accessibilityService.getCurrentRootNode()
        if (rootNode != null) {
            dumpUIHierarchy(rootNode, "recents_app_not_found", "Could not find '$appLabel' in recents after scrolling")
            rootNode.recycle()
        }

        return false
    }

    /**
     * Walk up from a text label node to find the parent recents card container.
     * The card container will have bounds that span a significant portion of the screen
     * (at least 40% width and 30% height) but NOT the full screen (which would be the
     * root container — swiping on that dismisses whichever card is in front, not the target).
     */
    private fun findCardParent(
        node: AccessibilityNodeInfo,
        screenWidth: Float,
        screenHeight: Float
    ): AccessibilityNodeInfo? {
        var current = node.parent ?: return null
        val minCardWidth = screenWidth * 0.4f
        val minCardHeight = screenHeight * 0.3f
        // Reject nodes that cover the full screen — those are root containers, not cards
        val maxCardWidth = screenWidth * 0.95f
        val maxCardHeight = screenHeight * 0.95f
        var depth = 0

        while (depth < 10) {
            val rect = android.graphics.Rect()
            current.getBoundsInScreen(rect)
            val nodeWidth = rect.width().toFloat()
            val nodeHeight = rect.height().toFloat()

            if (nodeWidth >= minCardWidth && nodeHeight >= minCardHeight &&
                nodeWidth <= maxCardWidth && nodeHeight <= maxCardHeight) {
                Log.d(TAG, "findCardParent: found card at depth=$depth, bounds=$rect")
                return current
            }

            if (nodeWidth > maxCardWidth && nodeHeight > maxCardHeight) {
                Log.d(TAG, "findCardParent: hit full-screen container at depth=$depth, bounds=$rect — stopping")
                break
            }

            val parent = current.parent ?: break
            current = parent
            depth++
        }

        Log.d(TAG, "findCardParent: no suitable card parent found")
        return null
    }

    /**
     * Swipe up on a node's bounding rect to dismiss it from recents.
     * After swiping, waits for the card to disappear from the accessibility tree
     * rather than using a fixed delay.
     */
    private suspend fun swipeUpToDismiss(
        accessibilityService: WhizAccessibilityService,
        node: AccessibilityNodeInfo,
        screenWidth: Float,
        screenHeight: Float
    ): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX().toFloat()
        val startY = rect.centerY().toFloat()
        // Swipe from card center to near top of screen (must stay on-screen for gesture to work)
        val endY = 10f

        // Capture identifying text before swiping so we can check for disappearance
        val nodeDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val searchLabel = nodeDesc.ifEmpty { nodeText }

        Log.d(TAG, "Swiping up to dismiss: centerX=$centerX, startY=$startY, endY=$endY")
        val result = accessibilityService.performScrollGesture(
            startX = centerX,
            startY = startY,
            endX = centerX,
            endY = endY,
            duration = 300
        )
        Log.d(TAG, "Swipe gesture result: $result")

        if (result && searchLabel.isNotEmpty()) {
            // Wait for the card to disappear from the accessibility tree
            val disappeared = waitForCondition(maxWaitMs = 1000, initialDelayMs = 100) {
                val root = accessibilityService.getCurrentRootNode() ?: return@waitForCondition true
                try {
                    // Check both content description and text-based search
                    val descGone = findNodeByContentDescContaining(root, searchLabel) == null
                    val textGone = root.findAccessibilityNodeInfosByText(searchLabel).isNullOrEmpty()
                    descGone && textGone
                } finally {
                    root.recycle()
                }
            }
            if (!disappeared) {
                Log.w(TAG, "Dismiss card did not disappear within timeout, proceeding anyway")
            }
        } else {
            // Fallback: fixed delay if we can't check for disappearance
            delay(600)
        }

        return result
    }

    /**
     * Recursively search for a node whose content description contains the target text.
     */
    private fun findNodeByContentDescContaining(
        root: AccessibilityNodeInfo,
        target: String,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains(target)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescContaining(child, target, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun calculateMatchScore(searchTerm: String, appLabel: String, packageName: String): Float {
        val normalizedLabel = appLabel.lowercase()
        val normalizedPackage = packageName.lowercase()
        
        // Exact match
        if (normalizedLabel == searchTerm) return 1.0f
        
        // Label starts with search term
        if (normalizedLabel.startsWith(searchTerm)) return 0.9f
        
        // Label contains search term as a word
        if (normalizedLabel.split(" ").contains(searchTerm)) return 0.8f
        
        // Label contains search term
        if (normalizedLabel.contains(searchTerm)) return 0.7f
        
        // Package name contains search term (less priority)
        if (normalizedPackage.contains(searchTerm)) return 0.5f
        
        // Check for partial word matches (e.g., "cal" for "calculator")
        if (searchTerm.length >= 3) {
            for (word in normalizedLabel.split(" ")) {
                if (word.startsWith(searchTerm)) return 0.6f
            }
        }
        
        return 0.0f
    }
    
    // ========== WhatsApp Specific Functions ==========
    
    suspend fun selectWhatsAppChat(chatName: String): WhatsAppResult {
        Log.i(TAG, "Attempting to select WhatsApp chat: $chatName")
        trackAction("selectWhatsAppChat: $chatName")

        try {
            // Auto-launch WhatsApp if not already open
            val launchResult = launchApp("WhatsApp", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch WhatsApp: ${launchResult.error}")
                return WhatsAppResult(
                    success = false,
                    action = "select_chat",
                    chatName = chatName,
                    error = "Failed to open WhatsApp: ${launchResult.error}"
                )
            }
            Log.i(TAG, "WhatsApp launched successfully")
            delay(1000) // Wait for WhatsApp to fully load

            // Get accessibility service instance
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.e(TAG, "Accessibility service not available")
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for selectWhatsAppChat",
                    packageName = "com.whatsapp"
                )
                return WhatsAppResult(
                    success = false,
                    action = "select_chat",
                    chatName = chatName,
                    error = "Accessibility service not enabled. Please enable it in settings."
                )
            }
            
            // Navigate to chat list by pressing back repeatedly
            // Try up to 6 times to reach the chat list
            var onChatList = false
            val maxBackAttempts = 6
            var uiDumped = false  // Only dump UI once for debugging

            for (backAttempt in 1..maxBackAttempts) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    val currentScreen = detectWhatsAppScreen(rootNode)
                    Log.i(TAG, "Back attempt $backAttempt: Current screen = $currentScreen")

                    // Dump UI on first UNKNOWN screen detection for debugging
                    if (currentScreen == WhatsAppScreen.UNKNOWN && !uiDumped) {
                        Log.w(TAG, "⚠️ WhatsApp screen not recognized, dumping UI for debugging...")
                        dumpUIHierarchy(rootNode, "whatsapp_unknown_screen", "WhatsApp screen not recognized during navigation")
                        uiDumped = true
                    }

                    if (currentScreen == WhatsAppScreen.CHAT_LIST) {
                        Log.i(TAG, "Reached chat list after $backAttempt back button(s)")
                        onChatList = true

                        // Check if search bar is visible, if not scroll to top
                        if (!isSearchBarVisible(rootNode)) {
                            Log.i(TAG, "Search bar not visible, scrolling to top")
                            rootNode.recycle()
                            scrollToTopOfChatList(accessibilityService)
                        } else {
                            Log.i(TAG, "Search bar is visible")
                            rootNode.recycle()
                        }
                        break
                    }

                    rootNode.recycle()

                    // Not on chat list yet, press back
                    Log.i(TAG, "Not on chat list, pressing back button")
                    accessibilityService.performGlobalActionSafely(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(500) // Wait for navigation to complete
                } else {
                    Log.w(TAG, "Could not get root node on back attempt $backAttempt")
                    delay(500)
                }
            }

            if (!onChatList) {
                Log.e(TAG, "Failed to navigate to chat list after $maxBackAttempts back button presses")
                // Try to get UI dump for debugging
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    dumpUIHierarchy(rootNode, "whatsapp_nav_to_chatlist_failed", "Failed to navigate to chat list after $maxBackAttempts attempts")
                    rootNode.recycle()
                } else {
                    logScreenAgentError(
                        reason = "whatsapp_nav_to_chatlist_failed",
                        errorMessage = "Failed to navigate to chat list after $maxBackAttempts attempts (no root node)",
                        packageName = "com.whatsapp"
                    )
                }
                return WhatsAppResult(
                    success = false,
                    action = "select_chat",
                    chatName = chatName,
                    error = "Could not navigate to WhatsApp chat list. Please ensure WhatsApp is open."
                )
            }
            
            // Try to find and click on the chat
            var success = false
            var attempts = 0
            val maxAttempts = 3
            var searchAttempted = false
            
            while (!success && attempts < maxAttempts) {
                attempts++
                Log.i(TAG, "Attempt $attempts to find chat: $chatName")
                
                // Get the root node
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode == null) {
                    Log.w(TAG, "Could not get root node")
                    // Wait a bit before retrying to get root node
                    delay(200)
                    continue
                }
                
                // Log the current activity to understand what screen we're on
                logCurrentScreen(rootNode)
                
                // First, try to find and click the chat in the main chat list
                val chatNodes = findChatNodes(rootNode, chatName)
                if (chatNodes.isNotEmpty()) {
                    Log.i(TAG, "Found ${chatNodes.size} nodes matching chat name")

                    // Try to click on each matching node until one succeeds
                    for (chatNode in chatNodes) {
                        // Use skipProfilePictures=true to avoid clicking on profile pictures
                        // which opens QuickContact popup instead of the chat
                        val clickableNode = findClickableParent(chatNode, skipProfilePictures = true)
                        if (clickableNode != null) {
                            Log.d(TAG, "Found clickable parent, attempting click...")
                            success = accessibilityService.clickNode(clickableNode)
                            Log.d(TAG, "Click result: $success")
                            
                            if (success) {
                                // Wait to verify we're in the chat
                                val inChat = waitForCondition(maxWaitMs = 2000) {
                                    val rootNode = accessibilityService.getCurrentRootNode()
                                    if (rootNode != null) {
                                        val screen = detectWhatsAppScreen(rootNode)
                                        val isInChat = screen == WhatsAppScreen.INSIDE_CHAT
                                        rootNode.recycle()
                                        isInChat
                                    } else {
                                        false
                                    }
                                }

                                if (inChat) {
                                    // Clean up
                                    chatNodes.forEach { it.recycle() }
                                    rootNode.recycle()

                                    return WhatsAppResult(
                                        success = true,
                                        action = "select_chat",
                                        chatName = chatName
                                    )
                                } else {
                                    // Click succeeded but we're not in the chat (e.g., profile popup opened)
                                    // Press back and try the next node
                                    Log.w(TAG, "Click succeeded but not in chat - pressing back to retry")
                                    accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                                    delay(300)
                                }
                            }
                            clickableNode.recycle()
                        }
                    }
                    
                    // Recycle nodes
                    chatNodes.forEach { it.recycle() }
                } else if (searchAttempted && chatNodes.isEmpty()) {
                    // Search was performed but text matching couldn't find a match.
                    // This happens for group chats where the search query differs from the display name.
                    // Fall back to clicking the first search result.
                    Log.i(TAG, "Text matching failed after search, trying to click first search result directly")
                    val firstResult = findFirstSearchResult(rootNode)
                    if (firstResult != null) {
                        val clicked = accessibilityService.clickNode(firstResult)
                        if (clicked) {
                            // Wait to verify we entered a chat
                            val inChat = waitForCondition(maxWaitMs = 2000) {
                                val checkRoot = accessibilityService.getCurrentRootNode()
                                if (checkRoot != null) {
                                    val screen = detectWhatsAppScreen(checkRoot)
                                    val isInChat = screen == WhatsAppScreen.INSIDE_CHAT
                                    checkRoot.recycle()
                                    isInChat
                                } else {
                                    false
                                }
                            }

                            if (inChat) {
                                firstResult.recycle()
                                rootNode.recycle()

                                return WhatsAppResult(
                                    success = true,
                                    action = "select_chat",
                                    chatName = chatName
                                )
                            } else {
                                Log.w(TAG, "Clicked first search result but not in chat - pressing back to retry")
                                accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                                delay(300)
                            }
                        }
                        firstResult.recycle()
                    }
                } else if (!searchAttempted && attempts == 1) {
                    // If we couldn't find the chat on the first attempt, try searching
                    Log.i(TAG, "Chat not visible, attempting to search for: $chatName")

                    // Check if search is already active
                    val currentScreen = detectWhatsAppScreen(rootNode)
                    val searchSuccess = if (currentScreen == WhatsAppScreen.SEARCH_ACTIVE) {
                        Log.d(TAG, "Search already active, updating search text")
                        // Clear and enter new search text
                        updateSearchText(rootNode, chatName)
                    } else {
                        // Open search and enter text
                        performWhatsAppSearch(rootNode, chatName, accessibilityService)
                    }

                    searchAttempted = true

                    if (searchSuccess) {
                        Log.d(TAG, "Search performed, waiting for results...")
                        // Wait for search results to appear
                        waitForSearchResults(accessibilityService, chatName, maxWaitMs = 2000)
                    }
                }
                
                // Recycle root node
                rootNode.recycle()
                
                // Wait before retrying with exponential backoff
                val retryDelay = minOf(200L * (1L shl attempts), 1000L)
                delay(retryDelay)
            }
            
            Log.e(TAG, "Could not find or click on chat: $chatName after $attempts attempts")

            // UI dump for debugging - try to get current screen state
            val finalRootNode = accessibilityService.getCurrentRootNode()
            if (finalRootNode != null) {
                dumpUIHierarchy(finalRootNode, "whatsapp_chat_not_found", "Could not find chat '$chatName' after $attempts attempts")
                finalRootNode.recycle()
            } else {
                logScreenAgentError(
                    reason = "whatsapp_chat_not_found",
                    errorMessage = "Could not find chat '$chatName' after $attempts attempts (no root node)",
                    packageName = "com.whatsapp"
                )
            }

            return WhatsAppResult(
                success = false,
                action = "select_chat",
                chatName = chatName,
                error = "Could not find contact or chat named '$chatName' in WhatsApp. Please verify the contact name and ask the user to confirm the exact spelling if needed."
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error selecting WhatsApp chat", e)
            logScreenAgentError(
                reason = "whatsapp_select_chat_error",
                errorMessage = "Exception selecting WhatsApp chat '$chatName': ${e.message}",
                packageName = "com.whatsapp"
            )
            return WhatsAppResult(
                success = false,
                action = "select_chat",
                chatName = chatName,
                error = "Error selecting chat: ${e.message}"
            )
        }
    }
    
    private fun findWhatsAppMessageInput(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        // Log node info for debugging
        if (depth == 0) {
            Log.d(TAG, "Starting search for WhatsApp input field...")
        }
        
        // Look for EditText nodes
        if (node.className == "android.widget.EditText") {
            val hintText = node.hintText?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""
            
            // Get bounds to check position
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val screenHeight = context.resources.displayMetrics.heightPixels
            
            Log.d(TAG, "Found EditText: hint='$hintText', text='$text', desc='$contentDesc', " +
                    "bounds=$rect (${rect.left},${rect.top},${rect.right},${rect.bottom})")
            
            // Just add any EditText we find for now to debug
            // We're only using this to get the position for the overlay
            if (rect.bottom > 0 && rect.right > 0) {  // Make sure it has valid bounds
                Log.d(TAG, "Adding EditText as potential input field for overlay positioning")
                results.add(node)
            }
        }
        
        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findWhatsAppMessageInput(child, results, depth + 1)
            }
        }
        
        if (depth == 0) {
            Log.d(TAG, "Finished search. Found ${results.size} EditText nodes")
        }
    }
    
    suspend fun draftWhatsAppMessage(message: String, previousText: String? = null, chatName: String? = null): DraftResult {
        Log.d(TAG, "Attempting to draft message in WhatsApp: $message, previousText: $previousText, chatName: $chatName")
        trackAction("draftWhatsAppMessage: ${message.take(30)}...")

        try {
            // Auto-launch WhatsApp if not already open
            val launchResult = launchApp("WhatsApp", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch WhatsApp: ${launchResult.error}")
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Failed to open WhatsApp: ${launchResult.error}"
                )
            }
            Log.i(TAG, "WhatsApp launched successfully")
            delay(1000) // Wait for WhatsApp to fully load

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for draftWhatsAppMessage",
                    packageName = "com.whatsapp"
                )
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Accessibility service not enabled"
                )
            }

            // If chatName is provided, check if we need to navigate to that chat
            if (chatName != null) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    val currentScreen = detectWhatsAppScreen(rootNode)
                    val currentChatName = if (currentScreen == WhatsAppScreen.INSIDE_CHAT) {
                        getCurrentWhatsAppChatName(rootNode)
                    } else {
                        null
                    }
                    rootNode.recycle()

                    // Check if we're in the correct chat using normalized comparison
                    // This handles different phone number formats like "+1(628)209-9005" vs "+1 (628) 209-9005 (You)"
                    val normalizedCurrent = normalizeChatName(currentChatName ?: "")
                    val normalizedRequested = normalizeChatName(chatName)
                    val isCorrectChat = currentChatName != null &&
                        (normalizedCurrent == normalizedRequested ||
                         normalizedCurrent.contains(normalizedRequested) ||
                         normalizedRequested.contains(normalizedCurrent))

                    if (!isCorrectChat) {
                        Log.d(TAG, "Not in correct chat (current: $currentChatName [$normalizedCurrent], requested: $chatName [$normalizedRequested]). Auto-selecting chat...")
                        val selectResult = selectWhatsAppChat(chatName)
                        if (!selectResult.success) {
                            return DraftResult(
                                success = false,
                                message = message,
                                error = "Could not open chat '$chatName': ${selectResult.error}"
                            )
                        }
                        Log.d(TAG, "Successfully navigated to chat: $chatName")
                    } else {
                        Log.d(TAG, "Already in correct chat: $currentChatName")
                    }
                }
            }

            // Wait to ensure we're in a chat
            waitForCondition(maxWaitMs = 1000) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    val isInChat = detectWhatsAppScreen(rootNode) == WhatsAppScreen.INSIDE_CHAT
                    rootNode.recycle()
                    isInChat
                } else {
                    false
                }
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Could not get root node"
                )
            }

            // If we're on a contact profile page (has message_btn), click it to open the chat
            val messageBtnNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_btn")
            if (messageBtnNodes != null && messageBtnNodes.isNotEmpty()) {
                Log.d(TAG, "On contact profile page, clicking message button to open chat")
                messageBtnNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                messageBtnNodes.forEach { it.recycle() }
                rootNode.recycle()
                delay(1500) // Wait for chat to open
                val chatRootNode = accessibilityService.getCurrentRootNode()
                if (chatRootNode == null) {
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Could not get root node after navigating from contact profile"
                    )
                }
                val inputNodesFromProfile = mutableListOf<AccessibilityNodeInfo>()
                findWhatsAppMessageInput(chatRootNode, inputNodesFromProfile, 0)
                if (inputNodesFromProfile.isEmpty()) {
                    dumpUIHierarchy(chatRootNode, "whatsapp_input_not_found", "Could not find message input field in WhatsApp after contact profile click")
                    chatRootNode.recycle()
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Could not find message input field after navigating from contact profile"
                    )
                }
                // Re-enter function logic with the new root; simplest approach: replace rootNode and inputNodes
                val screenHeight2 = context.resources.displayMetrics.heightPixels
                val inputNode2 = if (inputNodesFromProfile.size > 1) {
                    inputNodesFromProfile.minByOrNull { node ->
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        Math.abs(r.centerY() - screenHeight2 * 0.85)
                    } ?: inputNodesFromProfile[0]
                } else {
                    inputNodesFromProfile[0]
                }
                inputNode2.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(500)
                val appBounds2 = android.graphics.Rect()
                chatRootNode.getBoundsInScreen(appBounds2)
                val rect2 = android.graphics.Rect()
                inputNode2.getBoundsInScreen(rect2)
                val overlayBounds2 = android.graphics.Rect(appBounds2.left, rect2.top, appBounds2.right, rect2.bottom)
                val overlayStarted2 = MessageDraftOverlayService.show(context, overlayBounds2, message, previousText)
                inputNodesFromProfile.forEach { it.recycle() }
                chatRootNode.recycle()
                return DraftResult(
                    success = overlayStarted2,
                    message = message,
                    overlayShown = overlayStarted2,
                    error = if (!overlayStarted2) "Failed to show draft overlay" else null
                )
            }
            messageBtnNodes?.forEach { it.recycle() }

            // Find the WhatsApp message input field specifically
            val inputNodes = mutableListOf<AccessibilityNodeInfo>()
            findWhatsAppMessageInput(rootNode, inputNodes, 0)

            if (inputNodes.isNotEmpty()) {
                Log.d(TAG, "Found ${inputNodes.size} potential input field(s)")

                // Look for the best candidate - prefer one closer to bottom but not at very bottom (keyboard area)
                val screenHeight = context.resources.displayMetrics.heightPixels
                val inputNode = if (inputNodes.size > 1) {
                    // If multiple, pick the one that's likely the message input (not search bar)
                    inputNodes.minByOrNull { node ->
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        // Prefer nodes around 60-80% of screen height (above keyboard)
                        Math.abs(rect.top - (screenHeight * 0.7)).toInt()
                    } ?: inputNodes[0]
                } else {
                    inputNodes[0]
                }

                // Get the initial bounds of the input field
                val initialRect = android.graphics.Rect()
                inputNode.getBoundsInScreen(initialRect)

                Log.d(TAG, "Initial input field bounds before keyboard: $initialRect (top=${initialRect.top}, bottom=${initialRect.bottom})")
                Log.d(TAG, "Initial input field is at ${(initialRect.top.toFloat() / screenHeight * 100).toInt()}% of screen height")

                // Click on the input field to focus it and open the keyboard
                val clickSuccess = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                                   inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                if (clickSuccess) {
                    Log.d(TAG, "Clicked/focused input field to open keyboard")

                    // Wait for the keyboard to open and input field to move up
                    // The input field should move significantly upward when keyboard opens
                    val keyboardOpened = waitForCondition(maxWaitMs = 2000) {
                        val currentRootNode = accessibilityService.getCurrentRootNode()
                        if (currentRootNode != null) {
                            val currentInputNodes = mutableListOf<AccessibilityNodeInfo>()
                            findWhatsAppMessageInput(currentRootNode, currentInputNodes, 0)

                            if (currentInputNodes.isNotEmpty()) {
                                val currentRect = android.graphics.Rect()
                                currentInputNodes[0].getBoundsInScreen(currentRect)

                                // Check if input field has moved up significantly (at least 300px)
                                val movedUp = initialRect.top - currentRect.top > 300

                                currentInputNodes.forEach { it.recycle() }
                                currentRootNode.recycle()

                                movedUp
                            } else {
                                currentRootNode.recycle()
                                false
                            }
                        } else {
                            false
                        }
                    }

                    if (keyboardOpened) {
                        Log.d(TAG, "Keyboard opened successfully, input field moved up")
                    } else {
                        Log.w(TAG, "Keyboard may not have opened, input field didn't move 300px")
                    }
                } else {
                    Log.w(TAG, "Could not click/focus input field to open keyboard")
                }

                // Wait a bit more for keyboard animation to complete
                delay(300)

                // Get the updated root node and input field after keyboard opened
                val updatedRootNode = accessibilityService.getCurrentRootNode()
                if (updatedRootNode == null) {
                    inputNodes.forEach { it.recycle() }
                    rootNode.recycle()
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Could not get root node after keyboard opened"
                    )
                }

                // Find the input field again to get its new position
                val updatedInputNodes = mutableListOf<AccessibilityNodeInfo>()
                findWhatsAppMessageInput(updatedRootNode, updatedInputNodes, 0)

                if (updatedInputNodes.isEmpty()) {
                    dumpUIHierarchy(updatedRootNode, "whatsapp_input_not_found_after_keyboard", "Could not find input field after keyboard opened")
                    inputNode.recycle()
                    rootNode.recycle()
                    updatedRootNode.recycle()
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Could not find input field after keyboard opened"
                    )
                }

                val finalInputNode = if (updatedInputNodes.size > 1) {
                    updatedInputNodes.minByOrNull { node ->
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        Math.abs(rect.top - (screenHeight * 0.7)).toInt()
                    } ?: updatedInputNodes[0]
                } else {
                    updatedInputNodes[0]
                }

                // Get the final bounds of the input field (after keyboard is open)
                val rect = android.graphics.Rect()
                finalInputNode.getBoundsInScreen(rect)

                // Get the app window bounds (WhatsApp's actual width on screen)
                val appBounds = android.graphics.Rect()
                updatedRootNode.getBoundsInScreen(appBounds)

                Log.d(TAG, "Using input field at bounds: $rect (left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom})")
                Log.d(TAG, "WhatsApp window bounds: $appBounds (width=${appBounds.width()})")
                Log.d(TAG, "Screen dimensions: ${context.resources.displayMetrics.widthPixels} x ${context.resources.displayMetrics.heightPixels}")
                Log.d(TAG, "Input field is at ${(rect.top.toFloat() / context.resources.displayMetrics.heightPixels * 100).toInt()}% of screen height")

                // Create bounds for overlay that uses app width but input field's vertical position
                val overlayBounds = android.graphics.Rect(
                    appBounds.left,  // Use app's left edge
                    rect.top,        // Use input field's vertical position
                    appBounds.right, // Use app's right edge
                    rect.bottom      // Use input field's bottom
                )

                // Start the draft overlay service with the bounds, message, and previousText
                val overlayStarted = MessageDraftOverlayService.show(
                    context,
                    overlayBounds,
                    message,
                    previousText
                )

                // Clean up
                inputNode.recycle()
                updatedInputNodes.forEach { it.recycle() }
                rootNode.recycle()
                updatedRootNode.recycle()

                return DraftResult(
                    success = overlayStarted,
                    message = message,
                    overlayShown = overlayStarted,
                    error = if (!overlayStarted) "Failed to show draft overlay" else null
                )
            }

            // Clean up - no input field found
            inputNodes.forEach { it.recycle() }
            dumpUIHierarchy(rootNode, "whatsapp_input_not_found", "Could not find message input field in WhatsApp")
            rootNode.recycle()

            return DraftResult(
                success = false,
                message = message,
                error = "Could not find message input field"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error drafting WhatsApp message", e)
            logScreenAgentError(
                reason = "whatsapp_draft_error",
                errorMessage = "Exception drafting WhatsApp message: ${e.message}",
                packageName = "com.whatsapp"
            )
            return DraftResult(
                success = false,
                message = message,
                error = "Error drafting message: ${e.message}"
            )
        }
    }
    
    
    suspend fun sendWhatsAppMessage(message: String): WhatsAppResult {
        Log.d(TAG, "Attempting to send message in WhatsApp: $message")

        try {
            // Auto-launch WhatsApp if not already open
            val launchResult = launchApp("WhatsApp", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch WhatsApp: ${launchResult.error}")
                return WhatsAppResult(
                    success = false,
                    action = "send_message",
                    error = "Failed to open WhatsApp: ${launchResult.error}"
                )
            }
            Log.i(TAG, "WhatsApp launched successfully")
            delay(1000) // Wait for WhatsApp to fully load

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError("accessibility_unavailable", "Accessibility service not enabled", "com.whatsapp")
                return WhatsAppResult(
                    success = false,
                    action = "send_message",
                    error = "Accessibility service not enabled"
                )
            }

            // Wait to ensure we're in a chat and UI is ready
            if (!waitForWhatsAppReady(accessibilityService, WhatsAppScreen.INSIDE_CHAT, maxWaitMs = 1500)) {
                Log.w(TAG, "Not in a WhatsApp chat after waiting")
            }
            
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                logScreenAgentError("root_node_null", "Could not get root node", "com.whatsapp")
                return WhatsAppResult(
                    success = false,
                    action = "send_message",
                    error = "Could not get root node"
                )
            }

            // Find the message input field (usually has hint text "Message" or "Type a message")
            val inputNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, inputNodes)
            
            if (inputNodes.isNotEmpty()) {
                val inputNode = inputNodes[0]

                // Dismiss the draft overlay if it's active
                try {
                    if (com.example.whiz.services.MessageDraftOverlayService.isActive) {
                        Log.d(TAG, "Dismissing draft overlay before sending message")
                        com.example.whiz.services.MessageDraftOverlayService.stop(accessibilityService.applicationContext)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not dismiss draft overlay: ${e.message}")
                }

                // Clear the input field first
                val clearBundle = Bundle()
                clearBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
                Log.d(TAG, "Cleared input field")

                // Wait a bit for the clear to take effect
                delay(200)

                // Set the text
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    message
                )
                val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                if (textSet) {
                    Log.d(TAG, "Message text set successfully")
                    
                    // Wait for text to be set and then find send button
                    val sendButtonFound = waitForCondition(maxWaitMs = 1000) {
                        val currentRoot = accessibilityService.getCurrentRootNode()
                        if (currentRoot != null) {
                            // Check if send button is now enabled/visible
                            val sendButton = currentRoot.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                            val hasButton = sendButton != null && sendButton.isNotEmpty()
                            sendButton?.forEach { it.recycle() }
                            currentRoot.recycle()
                            hasButton
                        } else {
                            false
                        }
                    }
                    
                    val sendSuccess = if (sendButtonFound) {
                        val currentRoot = accessibilityService.getCurrentRootNode()
                        val success = if (currentRoot != null) {
                            clickSendButton(currentRoot)
                        } else {
                            false
                        }
                        currentRoot?.recycle()
                        success
                    } else {
                        false
                    }
                    
                    // Clean up
                    inputNodes.forEach { it.recycle() }
                    rootNode.recycle()
                    
                    if (sendSuccess) {
                        return WhatsAppResult(
                            success = true,
                            action = "send_message"
                        )
                    } else {
                        // UI dump for send button not found
                        val dumpRoot = accessibilityService.getCurrentRootNode()
                        if (dumpRoot != null) {
                            dumpUIHierarchy(dumpRoot, "whatsapp_send_button_not_found", "Could not find or click send button in WhatsApp")
                            dumpRoot.recycle()
                        }
                        return WhatsAppResult(
                            success = false,
                            action = "send_message",
                            error = "Could not find or click send button"
                        )
                    }
                }
            }

            // Clean up
            inputNodes.forEach { it.recycle() }
            dumpUIHierarchy(rootNode, "whatsapp_send_input_not_found", "Could not find message input field in sendWhatsAppMessage")
            rootNode.recycle()

            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Could not find message input field"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error sending WhatsApp message", e)
            logScreenAgentError(
                reason = "whatsapp_send_error",
                errorMessage = "Exception sending WhatsApp message: ${e.message}",
                packageName = "com.whatsapp"
            )
            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Error sending message: ${e.message}"
            )
        }
    }

    // ========== SMS Specific Functions ==========

    suspend fun selectSMSChat(contactName: String): SMSResult {
        Log.i(TAG, "Attempting to select SMS chat: $contactName")
        trackAction("selectSMSChat: $contactName")

        try {
            // Auto-launch Messages app if not already open
            val launchResult = launchApp("Messages", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch Messages: ${launchResult.error}")
                return SMSResult(
                    success = false,
                    action = "select_chat",
                    contactName = contactName,
                    error = "Failed to open Messages: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Messages app launched successfully")
            delay(1000) // Wait for Messages to fully load

            // Wait for accessibility service to become available (with retry logic)
            // This handles cases where the service is temporarily disconnected
            var accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.w(TAG, "Accessibility service not immediately available, waiting up to 2 seconds...")
                val maxRetries = 8
                var retryCount = 0
                while (accessibilityService == null && retryCount < maxRetries) {
                    delay(250) // Wait 250ms between retries
                    accessibilityService = WhizAccessibilityService.getInstance()
                    retryCount++
                    if (accessibilityService != null) {
                        Log.i(TAG, "Accessibility service became available after ${retryCount * 250}ms")
                    }
                }

                if (accessibilityService == null) {
                    Log.e(TAG, "Accessibility service not available after waiting 2 seconds")
                    logScreenAgentError(
                        reason = "accessibility_unavailable",
                        errorMessage = "Accessibility service not available for selectSMSChat after 2s retry",
                        packageName = "com.google.android.apps.messaging"
                    )
                    return SMSResult(
                        success = false,
                        action = "select_chat",
                        contactName = contactName,
                        error = "Accessibility service not enabled. Please enable it in settings."
                    )
                }
            }

            // Navigate to conversation list by pressing back repeatedly
            // Try up to 6 times to reach the list
            var onConversationList = false
            val maxBackAttempts = 6

            for (backAttempt in 1..maxBackAttempts) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    // Check if we're on the conversation list by looking for common indicators
                    // Most SMS apps have a search button or "New message" button on the main list
                    val searchButton = rootNode.findAccessibilityNodeInfosByText("Search")
                    val newMessageButton = rootNode.findAccessibilityNodeInfosByText("Start chat")
                    val hasListIndicators = (searchButton != null && searchButton.isNotEmpty()) ||
                                           (newMessageButton != null && newMessageButton.isNotEmpty())

                    searchButton?.forEach { it.recycle() }
                    newMessageButton?.forEach { it.recycle() }

                    if (hasListIndicators) {
                        Log.i(TAG, "Reached conversation list after $backAttempt back button(s)")
                        onConversationList = true
                        rootNode.recycle()
                        break
                    }

                    rootNode.recycle()

                    // Not on conversation list yet, press back
                    Log.i(TAG, "Not on conversation list, pressing back button")
                    accessibilityService.performGlobalActionSafely(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(500) // Wait for navigation to complete
                } else {
                    Log.w(TAG, "Could not get root node on back attempt $backAttempt")
                    delay(500)
                }
            }

            if (!onConversationList) {
                Log.e(TAG, "Failed to navigate to conversation list after $maxBackAttempts back button presses")
                // Try to get UI dump for debugging
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    dumpUIHierarchy(rootNode, "sms_nav_to_list_failed", "Failed to navigate to SMS conversation list after $maxBackAttempts attempts")
                    rootNode.recycle()
                } else {
                    logScreenAgentError(
                        reason = "sms_nav_to_list_failed",
                        errorMessage = "Failed to navigate to SMS conversation list after $maxBackAttempts attempts (no root node)",
                        packageName = "com.google.android.apps.messaging"
                    )
                }
                return SMSResult(
                    success = false,
                    action = "select_chat",
                    contactName = contactName,
                    error = "Could not navigate to SMS conversation list. Please ensure the SMS app is open."
                )
            }

            // Try to find and click on the conversation
            var success = false
            var attempts = 0
            val maxAttempts = 3
            var searchAttempted = false

            while (!success && attempts < maxAttempts) {
                attempts++
                Log.i(TAG, "Attempt $attempts to find conversation: $contactName")

                // Get the root node
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode == null) {
                    Log.w(TAG, "Could not get root node")
                    delay(200)
                    continue
                }

                // Try to find the conversation by contact name
                val contactNodes = rootNode.findAccessibilityNodeInfosByText(contactName)
                // Filter out EditText nodes (search fields) before checking if we found anything useful
                val nonEditTextNodes = contactNodes?.filter { it.className != "android.widget.EditText" } ?: emptyList()
                val skippedEditTextCount = (contactNodes?.size ?: 0) - nonEditTextNodes.size
                if (skippedEditTextCount > 0) {
                    Log.d(TAG, "Skipped $skippedEditTextCount EditText node(s) (likely search field)")
                }

                if (nonEditTextNodes.isNotEmpty()) {
                    Log.i(TAG, "Found ${nonEditTextNodes.size} non-EditText nodes matching contact name")

                    // Try to click on each matching node until one succeeds
                    for (contactNode in nonEditTextNodes) {
                        val clickableNode = findClickableParent(contactNode)
                        if (clickableNode != null) {
                            Log.d(TAG, "Found clickable parent, attempting click...")
                            success = accessibilityService.clickNode(clickableNode)
                            Log.d(TAG, "Click result: $success")

                            if (success) {
                                // Wait longer for the screen to update (conversations list may take time to load)
                                delay(1500)

                                // Check if we're on secondary search results screen
                                // (Google Messages shows this when clicking a contact shows their conversations)
                                val updatedRootNode = accessibilityService.getCurrentRootNode()
                                if (updatedRootNode != null) {
                                    // Look for "Conversations" header indicating secondary results
                                    val conversationsHeader = updatedRootNode.findAccessibilityNodeInfosByViewId(
                                        "com.google.android.apps.messaging:id/zero_state_search_chat_header"
                                    )

                                    if (conversationsHeader != null && conversationsHeader.isNotEmpty()) {
                                        Log.i(TAG, "✅ Found conversation header - on conversations list for contact")
                                        conversationsHeader.forEach { it.recycle() }

                                        // Find the conversations RecyclerView
                                        val conversationsResults = updatedRootNode.findAccessibilityNodeInfosByViewId(
                                            "com.google.android.apps.messaging:id/zero_state_search_chat_results"
                                        )

                                        if (conversationsResults != null && conversationsResults.isNotEmpty()) {
                                            Log.d(TAG, "Found conversations results container")

                                            // Find clickable conversation items
                                            val swipeableContainers = conversationsResults[0].findAccessibilityNodeInfosByViewId(
                                                "com.google.android.apps.messaging:id/swipeableContainer"
                                            )

                                            if (swipeableContainers != null && swipeableContainers.isNotEmpty()) {
                                                Log.d(TAG, "Found ${swipeableContainers.size} swipeable containers, clicking first...")
                                                val firstConversation = swipeableContainers[0]
                                                val clicked = accessibilityService.clickNode(firstConversation)

                                                swipeableContainers.forEach { it.recycle() }
                                                conversationsResults.forEach { it.recycle() }

                                                if (clicked) {
                                                    Log.i(TAG, "✅ Clicked first conversation in list")
                                                    delay(1200) // Wait for conversation to open
                                                } else {
                                                    Log.w(TAG, "Failed to click first conversation")
                                                }
                                            } else {
                                                Log.w(TAG, "No swipeableContainer found in conversations results")
                                                conversationsResults.forEach { it.recycle() }
                                            }
                                        } else {
                                            Log.w(TAG, "Conversation header found but no results container")
                                        }
                                    } else {
                                        Log.d(TAG, "No conversation header found - may have opened directly or still in search")
                                    }

                                    updatedRootNode.recycle()
                                }

                                // Validate we actually opened a conversation (not still in search)
                                delay(800) // Wait for conversation to load
                                val validationRootNode = accessibilityService.getCurrentRootNode()
                                if (validationRootNode != null) {
                                    // Check if we're still in search screen
                                    val searchBoxId = "com.google.android.apps.messaging:id/zero_state_search_box_auto_complete"
                                    val stillInSearch = validationRootNode.findAccessibilityNodeInfosByViewId(searchBoxId)

                                    if (stillInSearch != null && stillInSearch.isNotEmpty()) {
                                        Log.w(TAG, "Click succeeded but still in search screen - conversation not opened")
                                        stillInSearch.forEach { it.recycle() }
                                        validationRootNode.recycle()
                                        // Mark as not successful so outer loop retries
                                        success = false
                                    } else {
                                        // Successfully opened conversation
                                        Log.i(TAG, "Successfully opened conversation with $contactName")
                                        validationRootNode.recycle()

                                        // Clean up
                                        contactNodes.forEach { it.recycle() }
                                        rootNode.recycle()

                                        return SMSResult(
                                            success = true,
                                            action = "select_chat",
                                            contactName = contactName
                                        )
                                    }
                                } else {
                                    Log.w(TAG, "Could not validate conversation opened")
                                    success = false
                                }
                            }
                            clickableNode.recycle()
                        }
                    }

                    // Recycle nodes
                    contactNodes.forEach { it.recycle() }
                } else if (!searchAttempted && attempts == 1) {
                    // Recycle any EditText nodes we filtered out
                    contactNodes?.forEach { it.recycle() }

                    // If we couldn't find the contact on the first attempt, try searching
                    Log.i(TAG, "Contact not visible, attempting to search for: $contactName")

                    val searchSuccess = performSMSSearch(rootNode, contactName, accessibilityService)
                    searchAttempted = true

                    if (searchSuccess) {
                        Log.d(TAG, "Search performed, waiting for results...")
                        delay(1500) // Wait for search results to appear

                        // After search, click on the first search result
                        val searchResultRootNode = accessibilityService.getCurrentRootNode()
                        if (searchResultRootNode != null) {
                            Log.d(TAG, "Looking for search results to click...")

                            // Find the search results RecyclerView
                            // Try both IDs - zero_state_search_chat_results is used in modern Google Messages
                            val searchResultsIds = listOf(
                                "com.google.android.apps.messaging:id/zero_state_search_chat_results",
                                "com.google.android.apps.messaging:id/zero_state_search_results"
                            )
                            var searchResultsNodes: List<AccessibilityNodeInfo>? = null
                            for (searchResultsId in searchResultsIds) {
                                val nodes = searchResultRootNode.findAccessibilityNodeInfosByViewId(searchResultsId)
                                if (nodes != null && nodes.isNotEmpty()) {
                                    searchResultsNodes = nodes
                                    Log.d(TAG, "Found search results container with ID: $searchResultsId")
                                    break
                                }
                            }

                            if (searchResultsNodes != null && searchResultsNodes.isNotEmpty()) {
                                Log.d(TAG, "Found search results container")

                                // Find the first clickable item in search results
                                // Look for LinearLayout children that are clickable
                                val searchResultsContainer = searchResultsNodes[0]
                                val clickableItems = mutableListOf<AccessibilityNodeInfo>()
                                findClickableChildren(searchResultsContainer, clickableItems)

                                if (clickableItems.isNotEmpty()) {
                                    Log.d(TAG, "Found ${clickableItems.size} clickable search results, clicking first...")
                                    val firstResult = clickableItems[0]
                                    val clicked = accessibilityService.clickNode(firstResult)

                                    Log.d(TAG, "Click on first search result: $clicked")

                                    clickableItems.forEach { it.recycle() }

                                    if (clicked) {
                                        delay(1500) // Wait for conversation to open

                                        // Validate we opened a conversation
                                        val validationRootNode = accessibilityService.getCurrentRootNode()
                                        if (validationRootNode != null) {
                                            val searchBoxId = "com.google.android.apps.messaging:id/zero_state_search_box_auto_complete"
                                            val stillInSearch = validationRootNode.findAccessibilityNodeInfosByViewId(searchBoxId)

                                            if (stillInSearch == null || stillInSearch.isEmpty()) {
                                                // Successfully opened conversation
                                                Log.i(TAG, "Successfully opened conversation from search results")
                                                validationRootNode.recycle()
                                                searchResultsNodes.forEach { it.recycle() }
                                                searchResultRootNode.recycle()
                                                rootNode.recycle()

                                                return SMSResult(
                                                    success = true,
                                                    action = "select_chat",
                                                    contactName = contactName
                                                )
                                            } else {
                                                // Still in search - might be showing conversations with this contact
                                                // Try clicking the first result again
                                                Log.w(TAG, "Still in search screen after clicking first result")
                                                Log.d(TAG, "Checking if we're in conversations view...")

                                                stillInSearch.forEach { it.recycle() }

                                                // Find search results again and click
                                                // After first click, results may be in zero_state_search_chat_results instead
                                                var searchResultsAgain = validationRootNode.findAccessibilityNodeInfosByViewId(
                                                    "com.google.android.apps.messaging:id/zero_state_search_chat_results"
                                                )

                                                if (searchResultsAgain == null || searchResultsAgain.isEmpty()) {
                                                    // Try the original results container
                                                    searchResultsAgain = validationRootNode.findAccessibilityNodeInfosByViewId(
                                                        "com.google.android.apps.messaging:id/zero_state_search_results"
                                                    )
                                                }

                                                if (searchResultsAgain != null && searchResultsAgain.isNotEmpty()) {
                                                    val clickableItemsAgain = mutableListOf<AccessibilityNodeInfo>()
                                                    findClickableChildren(searchResultsAgain[0], clickableItemsAgain)

                                                    if (clickableItemsAgain.isNotEmpty()) {
                                                        Log.d(TAG, "Found ${clickableItemsAgain.size} results, clicking first again...")
                                                        val clickedAgain = accessibilityService.clickNode(clickableItemsAgain[0])
                                                        clickableItemsAgain.forEach { it.recycle() }

                                                        if (clickedAgain) {
                                                            Log.d(TAG, "Clicked result again, waiting for conversation...")
                                                            delay(1500)

                                                            // Final validation
                                                            val finalRootNode = accessibilityService.getCurrentRootNode()
                                                            if (finalRootNode != null) {
                                                                val finalSearchBox = finalRootNode.findAccessibilityNodeInfosByViewId(searchBoxId)

                                                                if (finalSearchBox == null || finalSearchBox.isEmpty()) {
                                                                    Log.i(TAG, "Successfully opened conversation after second click")
                                                                    finalRootNode.recycle()
                                                                    searchResultsAgain.forEach { it.recycle() }
                                                                    validationRootNode.recycle()
                                                                    searchResultsNodes.forEach { it.recycle() }
                                                                    searchResultRootNode.recycle()
                                                                    rootNode.recycle()

                                                                    return SMSResult(
                                                                        success = true,
                                                                        action = "select_chat",
                                                                        contactName = contactName
                                                                    )
                                                                } else {
                                                                    finalSearchBox.forEach { it.recycle() }
                                                                }
                                                                finalRootNode.recycle()
                                                            }
                                                        }
                                                    } else {
                                                        Log.w(TAG, "No clickable items found on second attempt")
                                                    }

                                                    searchResultsAgain.forEach { it.recycle() }
                                                }
                                            }
                                            validationRootNode.recycle()
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "No clickable items found in search results")
                                }

                                searchResultsNodes.forEach { it.recycle() }
                            } else {
                                Log.w(TAG, "Search results container not found")
                            }

                            searchResultRootNode.recycle()
                        }
                    }
                }

                // Recycle root node
                rootNode.recycle()

                // Wait before retrying
                delay(500)
            }

            Log.e(TAG, "Could not find or click on conversation: $contactName after $attempts attempts")

            // UI dump for debugging
            val finalRootNode = accessibilityService.getCurrentRootNode()
            if (finalRootNode != null) {
                dumpUIHierarchy(finalRootNode, "sms_chat_not_found", "Could not find SMS conversation '$contactName' after $attempts attempts")
                finalRootNode.recycle()
            } else {
                logScreenAgentError(
                    reason = "sms_chat_not_found",
                    errorMessage = "Could not find SMS conversation '$contactName' after $attempts attempts (no root node)",
                    packageName = "com.google.android.apps.messaging"
                )
            }

            return SMSResult(
                success = false,
                action = "select_chat",
                contactName = contactName,
                error = "Could not find contact or conversation named '$contactName' in SMS app. Please verify the contact name."
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error selecting SMS chat", e)
            logScreenAgentError(
                reason = "sms_select_chat_error",
                errorMessage = "Exception selecting SMS chat '$contactName': ${e.message}",
                packageName = "com.google.android.apps.messaging"
            )
            return SMSResult(
                success = false,
                action = "select_chat",
                contactName = contactName,
                error = "Error selecting chat: ${e.message}"
            )
        }
    }

    suspend fun draftSMSMessage(message: String, previousText: String? = null, contactName: String? = null): DraftResult {
        Log.d(TAG, "Attempting to draft SMS message: $message, previousText: $previousText, contactName: $contactName")
        trackAction("draftSMSMessage: ${message.take(30)}...")

        try {
            // Skip launching app when updating an existing draft - we're already in the correct conversation
            if (previousText != null) {
                Log.d(TAG, "Skipping app launch - updating existing draft, already in conversation")
            } else {
                // Auto-launch Messages app if not already open
                val launchResult = launchApp("Messages", enableOverlay = true)
                if (!launchResult.success) {
                    Log.e(TAG, "Failed to launch Messages: ${launchResult.error}")
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Failed to open Messages: ${launchResult.error}"
                    )
                }
                Log.i(TAG, "Messages app launched successfully")
                delay(1000) // Wait for Messages to fully load
            }

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for draftSMSMessage",
                    packageName = "com.google.android.apps.messaging"
                )
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Accessibility service not enabled"
                )
            }

            // If contactName is provided, check if we need to navigate to that conversation
            // SKIP this check if previousText is provided - that means we're updating an existing draft
            // and we're already in the correct conversation
            if (contactName != null && previousText == null) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    val currentContactName = getCurrentSMSContactName(rootNode)
                    rootNode.recycle()

                    // Check if we're in the correct conversation using normalized comparison
                    // This handles different phone number formats like "+1(628)209-9005" vs "+1 (628) 209-9005"
                    val normalizedCurrent = normalizeChatName(currentContactName ?: "")
                    val normalizedRequested = normalizeChatName(contactName)
                    val isCorrectConversation = currentContactName != null &&
                        (normalizedCurrent == normalizedRequested ||
                         normalizedCurrent.contains(normalizedRequested) ||
                         normalizedRequested.contains(normalizedCurrent))

                    if (!isCorrectConversation) {
                        Log.d(TAG, "Not in correct SMS conversation (current: $currentContactName [$normalizedCurrent], requested: $contactName [$normalizedRequested]). Auto-selecting contact...")
                        val selectResult = selectSMSChat(contactName)
                        if (!selectResult.success) {
                            return DraftResult(
                                success = false,
                                message = message,
                                error = "Could not open conversation with '$contactName': ${selectResult.error}"
                            )
                        }
                        Log.d(TAG, "Successfully navigated to SMS conversation: $contactName")
                    } else {
                        Log.d(TAG, "Already in correct SMS conversation: $currentContactName")
                    }
                }
            } else if (previousText != null) {
                Log.d(TAG, "Skipping contact navigation check - updating existing draft (previousText provided)")

                // Dismiss the existing overlay first so we can find the actual input field
                Log.d(TAG, "Dismissing existing draft overlay before updating")
                MessageDraftOverlayService.stop(context)
                delay(500) // Wait for overlay to fully dismiss
                Log.d(TAG, "Overlay dismissed, proceeding to find input field")
            }

            // Wait a bit to ensure we're in a conversation (shorter when updating since we're already there)
            delay(if (previousText != null) 300 else 800)

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Could not get root node"
                )
            }

            // Get screen height for later use
            val screenHeight = context.resources.displayMetrics.heightPixels

            // IMPORTANT: Check if we're still in the search screen
            val searchBoxId = "com.google.android.apps.messaging:id/zero_state_search_box_auto_complete"
            val searchBoxNodes = rootNode.findAccessibilityNodeInfosByViewId(searchBoxId)
            if (searchBoxNodes != null && searchBoxNodes.isNotEmpty()) {
                Log.e(TAG, "Still in search screen - not in a conversation!")
                searchBoxNodes.forEach { it.recycle() }
                dumpUIHierarchy(rootNode, "sms_stuck_in_search_screen", "Cannot draft message: still in search screen. Please select a contact first to open a conversation.")
                rootNode.recycle()
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Cannot draft message: still in search screen. Please select a contact first to open a conversation."
                )
            }

            // Find the SMS message input field
            // Try by resource ID first (Google Messages - traditional view)
            var inputNode: AccessibilityNodeInfo? = null
            var isComposeUI = false  // Track if we're using Compose UI (needs double-click) vs EditText (single click)
            val composeMessageId = "com.google.android.apps.messaging:id/compose_message_text"
            val composeMessageNodes = rootNode.findAccessibilityNodeInfosByViewId(composeMessageId)

            if (composeMessageNodes != null && composeMessageNodes.isNotEmpty()) {
                Log.d(TAG, "Found message input field by resource ID: $composeMessageId")
                inputNode = composeMessageNodes[0]
                isComposeUI = false  // Traditional EditText
                composeMessageNodes.drop(1).forEach { it.recycle() }
            } else {
                // Fallback 1: Find any EditText nodes, but exclude search boxes
                Log.d(TAG, "Resource ID not found, falling back to generic EditText search")
                val inputNodes = mutableListOf<AccessibilityNodeInfo>()
                findEditTextNodes(rootNode, inputNodes)

                // Filter out search box nodes
                val filteredNodes = inputNodes.filter { node ->
                    val viewId = node.viewIdResourceName
                    viewId != searchBoxId && !viewId.contains("search")
                }

                // Recycle nodes that were filtered out
                inputNodes.filter { it !in filteredNodes }.forEach { it.recycle() }

                if (filteredNodes.isNotEmpty()) {
                    Log.d(TAG, "Found ${filteredNodes.size} potential input field(s) after filtering")

                    // Pick the best candidate input field (usually near bottom of screen)
                    inputNode = filteredNodes.maxByOrNull { node ->
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        rect.top // Prefer input fields lower on screen
                    } ?: filteredNodes[0]
                    isComposeUI = false  // Traditional EditText

                    // Recycle unused nodes
                    filteredNodes.filter { it != inputNode }.forEach { it.recycle() }
                } else if (previousText != null) {
                    // When updating existing draft, don't use Compose UI fallback
                    // The overlay was just dismissed, and the Compose UI heuristics might find it or other views
                    Log.w(TAG, "No EditText nodes found when updating draft - cannot proceed without EditText")
                } else {
                    Log.d(TAG, "No EditText nodes found - trying Compose UI fallback")

                    // Fallback 2: For Compose-based Google Messages
                    // Look for clickable View near the bottom that's part of the compose area
                    // These are typically wider views in the bottom third of the screen
                    val allClickableNodes = mutableListOf<AccessibilityNodeInfo>()
                    findClickableChildren(rootNode, allClickableNodes)

                    val screenHeight = context.resources.displayMetrics.heightPixels
                    val bottomThirdStart = (screenHeight * 0.67).toInt()

                    // Find wide clickable views in bottom third that could be the compose area
                    val composeAreaCandidates = allClickableNodes.filter { node ->
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        val width = rect.right - rect.left
                        val screenWidth = context.resources.displayMetrics.widthPixels

                        // Must be in bottom third, reasonably wide, and not too tall (not a list item)
                        rect.top > bottomThirdStart &&
                        width > (screenWidth * 0.4) &&
                        (rect.bottom - rect.top) < 400
                    }

                    if (composeAreaCandidates.isNotEmpty()) {
                        // Use the first candidate (usually the compose text area)
                        inputNode = composeAreaCandidates[0]
                        isComposeUI = true  // Compose UI needs double-click
                        Log.d(TAG, "Found compose area candidate in Compose UI at bottom of screen")

                        // Recycle others
                        composeAreaCandidates.drop(1).forEach { it.recycle() }
                        allClickableNodes.filter { it !in composeAreaCandidates }.forEach { it.recycle() }
                    } else {
                        Log.w(TAG, "No compose area candidates found")
                        allClickableNodes.forEach { it.recycle() }
                    }
                }
            }

            if (inputNode != null) {
                Log.d(TAG, "Using input node for drafting")

                // Get the initial bounds of the input field
                val initialRect = android.graphics.Rect()
                inputNode.getBoundsInScreen(initialRect)

                Log.d(TAG, "Initial input field bounds before keyboard: $initialRect (top=${initialRect.top}, bottom=${initialRect.bottom})")
                Log.d(TAG, "Initial input field is at ${(initialRect.top.toFloat() / screenHeight * 100).toInt()}% of screen height")

                // Click on the input field to focus it and open the keyboard
                // For EditText (traditional UI): Use focus only, then toggleSoftInput fallback if needed
                // For Compose UI: Use double-click pattern
                val clickSuccess: Boolean
                if (isComposeUI) {
                    // Compose UI needs click actions
                    val focusResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val clickResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickSuccess = focusResult || clickResult
                    Log.d(TAG, "Compose UI: focus=$focusResult, click=$clickResult")

                    // Double-tap for Compose fields - they sometimes need the click action twice
                    delay(50)
                    val secondClick = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Second click result (Compose UI): $secondClick")
                } else {
                    // Traditional EditText: Just click to open keyboard
                    // Note: ACTION_FOCUS sets accessibility focus which may interfere with input focus
                    val clickResult = inputNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickSuccess = clickResult
                    Log.d(TAG, "EditText: click=$clickResult")
                }

                if (clickSuccess) {
                    Log.d(TAG, "Clicked/focused input field to open keyboard (isComposeUI=$isComposeUI)")

                    // Wait for the keyboard to open and input field to move up
                    // The input field should move significantly upward when keyboard opens
                    val keyboardOpened = waitForCondition(maxWaitMs = 2000) {
                        val currentRootNode = accessibilityService.getCurrentRootNode()
                        if (currentRootNode != null) {
                            // Try to find EditText nodes first (traditional UI)
                            val currentInputNodes = mutableListOf<AccessibilityNodeInfo>()
                            findEditTextNodes(currentRootNode, currentInputNodes)

                            val movedUp = if (currentInputNodes.isNotEmpty()) {
                                val currentRect = android.graphics.Rect()
                                currentInputNodes[0].getBoundsInScreen(currentRect)
                                val moved = initialRect.top - currentRect.top > 300
                                currentInputNodes.forEach { it.recycle() }
                                moved
                            } else {
                                false
                            }

                            currentRootNode.recycle()
                            movedUp
                        } else {
                            false
                        }
                    }

                    if (keyboardOpened) {
                        Log.d(TAG, "Keyboard opened successfully, input field moved up")
                    } else {
                        Log.w(TAG, "Keyboard may not have opened, input field didn't move 300px")
                    }
                } else {
                    Log.w(TAG, "Could not click/focus input field to open keyboard")
                }

                // Wait a bit more for keyboard animation to complete
                delay(300)

                // Get the updated root node and input field after keyboard opened
                val updatedRootNode = accessibilityService.getCurrentRootNode()
                if (updatedRootNode == null) {
                    inputNode?.recycle()
                    rootNode.recycle()
                    return DraftResult(
                        success = false,
                        message = message,
                        error = "Could not get root node after keyboard opened"
                    )
                }

                // Find the input field again to get its new position
                val updatedInputNodes = mutableListOf<AccessibilityNodeInfo>()
                findEditTextNodes(updatedRootNode, updatedInputNodes)

                val finalInputNode: AccessibilityNodeInfo
                val rect = android.graphics.Rect()

                if (updatedInputNodes.isNotEmpty()) {
                    // Traditional EditText found
                    finalInputNode = updatedInputNodes.maxByOrNull { node ->
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        r.top
                    } ?: updatedInputNodes[0]

                    finalInputNode.getBoundsInScreen(rect)

                    // Clean up others
                    updatedInputNodes.filter { it != finalInputNode }.forEach { it.recycle() }
                } else {
                    // Compose UI - find wide clickable view in bottom area (after keyboard moved it up)
                    Log.d(TAG, "No EditText found after keyboard, looking for Compose input area...")

                    val allClickable = mutableListOf<AccessibilityNodeInfo>()
                    findClickableChildren(updatedRootNode, allClickable)

                    val screenWidth = context.resources.displayMetrics.widthPixels
                    val wideBottomViews = allClickable.filter { node ->
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        val width = r.right - r.left
                        // Look for wide views that are now in the middle of screen (moved up from bottom)
                        width > (screenWidth * 0.4) && r.top > 800 && r.top < 1800
                    }

                    if (wideBottomViews.isEmpty()) {
                        allClickable.forEach { it.recycle() }
                        dumpUIHierarchy(updatedRootNode, "sms_input_not_found_compose", "Could not find input field after keyboard opened (Compose UI)")
                        inputNode.recycle()
                        rootNode.recycle()
                        updatedRootNode.recycle()
                        return DraftResult(
                            success = false,
                            message = message,
                            error = "Could not find input field after keyboard opened (Compose UI)"
                        )
                    }

                    finalInputNode = wideBottomViews[0]
                    finalInputNode.getBoundsInScreen(rect)

                    Log.d(TAG, "Found Compose input area at: $rect")

                    // Clean up
                    wideBottomViews.drop(1).forEach { it.recycle() }
                    allClickable.filter { it !in wideBottomViews }.forEach { it.recycle() }
                }

                // Get the app window bounds
                val appBounds = android.graphics.Rect()
                updatedRootNode.getBoundsInScreen(appBounds)

                Log.d(TAG, "Using input field at bounds: $rect (left=${rect.left}, top=${rect.top}, right=${rect.right}, bottom=${rect.bottom})")
                Log.d(TAG, "SMS app window bounds: $appBounds (width=${appBounds.width()})")
                Log.d(TAG, "Input field is at ${(rect.top.toFloat() / screenHeight * 100).toInt()}% of screen height")

                // Create bounds for overlay that uses app width but input field's vertical position
                val overlayBounds = android.graphics.Rect(
                    appBounds.left,  // Use app's left edge
                    rect.top,        // Use input field's vertical position
                    appBounds.right, // Use app's right edge
                    rect.bottom      // Use input field's bottom
                )

                // Start the draft overlay service with the bounds, message, and previousText
                val overlayStarted = MessageDraftOverlayService.show(
                    context,
                    overlayBounds,
                    message,
                    previousText
                )

                // Clean up
                inputNode.recycle()
                finalInputNode.recycle()
                rootNode.recycle()
                updatedRootNode.recycle()

                return DraftResult(
                    success = overlayStarted,
                    message = message,
                    overlayShown = overlayStarted,
                    error = if (!overlayStarted) "Failed to show draft overlay" else null
                )
            }

            // Clean up - inputNode is null
            if (inputNode != null) {
                inputNode.recycle()
            }
            dumpUIHierarchy(rootNode, "sms_input_not_found", "Could not find message input field in SMS app")
            rootNode.recycle()

            return DraftResult(
                success = false,
                message = message,
                error = "Could not find message input field"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error drafting SMS message", e)
            logScreenAgentError(
                reason = "sms_draft_error",
                errorMessage = "Exception drafting SMS message: ${e.message}",
                packageName = "com.google.android.apps.messaging"
            )
            return DraftResult(
                success = false,
                message = message,
                error = "Error drafting message: ${e.message}"
            )
        }
    }

    suspend fun sendSMSMessage(message: String): SMSResult {
        Log.d(TAG, "Attempting to send SMS message: $message")

        try {
            // Skip launching app - sendSMSMessage is always called after a draft was shown,
            // so the Messages app should already be open in the correct conversation
            Log.d(TAG, "Skipping app launch - send is called after draft, app should already be in correct state")

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError("accessibility_unavailable", "Accessibility service not enabled", "com.google.android.apps.messaging")
                return SMSResult(
                    success = false,
                    action = "send_message",
                    error = "Accessibility service not enabled"
                )
            }

            // Wait a bit to ensure UI is ready
            delay(500)

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                logScreenAgentError("root_node_null", "Could not get root node", "com.google.android.apps.messaging")
                return SMSResult(
                    success = false,
                    action = "send_message",
                    error = "Could not get root node"
                )
            }

            // Find the message input field
            // Try by resource ID first (Google Messages)
            var inputNode: AccessibilityNodeInfo? = null
            val composeMessageId = "com.google.android.apps.messaging:id/compose_message_text"
            val composeMessageNodes = rootNode.findAccessibilityNodeInfosByViewId(composeMessageId)

            if (composeMessageNodes != null && composeMessageNodes.isNotEmpty()) {
                Log.d(TAG, "Found message input field by resource ID: $composeMessageId")
                inputNode = composeMessageNodes[0]
                composeMessageNodes.drop(1).forEach { it.recycle() }
            } else {
                // Fallback: Find any EditText nodes
                Log.d(TAG, "Resource ID not found, falling back to generic EditText search")
                val inputNodes = mutableListOf<AccessibilityNodeInfo>()
                findEditTextNodes(rootNode, inputNodes)

                if (inputNodes.isNotEmpty()) {
                    inputNode = inputNodes[0]
                    inputNodes.drop(1).forEach { it.recycle() }
                }
            }

            if (inputNode != null) {

                // Dismiss the draft overlay if it's active
                try {
                    if (com.example.whiz.services.MessageDraftOverlayService.isActive) {
                        Log.d(TAG, "Dismissing draft overlay before sending message")
                        com.example.whiz.services.MessageDraftOverlayService.stop(accessibilityService.applicationContext)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not dismiss draft overlay: ${e.message}")
                }

                // Clear the input field first
                val clearBundle = Bundle()
                clearBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
                Log.d(TAG, "Cleared input field")

                // Wait a bit for the clear to take effect
                delay(200)

                // Set the text
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    message
                )
                val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                if (textSet) {
                    Log.d(TAG, "Message text set successfully")

                    // Wait for text to be set and send button to become active
                    delay(500)

                    // Try to find the send button
                    // Common identifiers: "Send", button with contentDescription "Send"
                    val currentRoot = accessibilityService.getCurrentRootNode()
                    var sendSuccess = false

                    if (currentRoot != null) {
                        // Find by content description and try direct click
                        val sendButtons = mutableListOf<AccessibilityNodeInfo>()
                        // Try all possible send button content descriptions
                        val sendButtonDescriptions = listOf("Send message", "Send SMS", "Send encrypted message")
                        for (desc in sendButtonDescriptions) {
                            findNodesByContentDescription(currentRoot, desc, sendButtons)
                            if (sendButtons.isNotEmpty()) break
                        }

                        if (sendButtons.isNotEmpty()) {
                            Log.d(TAG, "Found send button by content description")
                            val sendButtonChild = sendButtons[0]

                            // Try clicking the node directly first - Android might bubble the click up
                            sendSuccess = sendButtonChild.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Tried clicking node directly, result: $sendSuccess")

                            // If direct click didn't work, walk up the tree to find clickable ancestor
                            if (!sendSuccess) {
                                Log.d(TAG, "Direct click failed, walking up tree to find clickable ancestor")
                                var sendButton = sendButtonChild.parent

                                // Walk up the tree to find a clickable ancestor
                                var attempts = 0
                                while (sendButton != null && !sendButton.isClickable && attempts < 3) {
                                    Log.d(TAG, "Parent node not clickable, trying grandparent (attempt ${attempts + 1})")
                                    val nextParent = sendButton.parent
                                    sendButton.recycle()
                                    sendButton = nextParent
                                    attempts++
                                }

                                if (sendButton != null && sendButton.isClickable) {
                                    sendSuccess = accessibilityService.clickNode(sendButton)
                                    Log.d(TAG, "Clicked send button by walking up tree, result: $sendSuccess")
                                    sendButton.recycle()
                                } else {
                                    Log.w(TAG, "Could not find clickable ancestor for send button")
                                    sendButton?.recycle()
                                }
                            }

                            sendButtons.forEach { it.recycle() }
                        } else {
                            Log.e(TAG, "Could not find send button by content description")
                        }

                        currentRoot.recycle()
                    }

                    // Clean up
                    inputNode.recycle()
                    rootNode.recycle()

                    if (sendSuccess) {
                        return SMSResult(
                            success = true,
                            action = "send_message"
                        )
                    } else {
                        // UI dump for send button not found
                        val dumpRoot = accessibilityService.getCurrentRootNode()
                        if (dumpRoot != null) {
                            dumpUIHierarchy(dumpRoot, "sms_send_button_not_found", "Could not find or click send button in SMS app")
                            dumpRoot.recycle()
                        }
                        return SMSResult(
                            success = false,
                            action = "send_message",
                            error = "Could not find or click send button"
                        )
                    }
                }
            }

            // Clean up - inputNode is null
            if (inputNode != null) {
                inputNode.recycle()
            }
            dumpUIHierarchy(rootNode, "sms_send_input_not_found", "Could not find message input field in sendSMSMessage")
            rootNode.recycle()

            return SMSResult(
                success = false,
                action = "send_message",
                error = "Could not find message input field"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error sending SMS message", e)
            logScreenAgentError(
                reason = "sms_send_error",
                errorMessage = "Exception sending SMS message: ${e.message}",
                packageName = "com.google.android.apps.messaging"
            )
            return SMSResult(
                success = false,
                action = "send_message",
                error = "Error sending message: ${e.message}"
            )
        }
    }

    // ========== SMS Helper Functions ==========

    /**
     * Get the name of the current SMS contact if inside a conversation.
     * Returns null if not inside a conversation.
     */
    private fun getCurrentSMSContactName(rootNode: AccessibilityNodeInfo): String? {
        try {
            // Try multiple approaches to find the contact name

            // Approach 1: Look for top_app_bar_title_row in modern Google Messages (Compose UI)
            // The contact name is in a TextView child of this element
            val topAppBarTitleId = "com.google.android.apps.messaging:id/top_app_bar_title_row"
            val topAppBarTitleNodes = rootNode.findAccessibilityNodeInfosByViewId(topAppBarTitleId)
            if (topAppBarTitleNodes != null && topAppBarTitleNodes.isNotEmpty()) {
                val titleRow = topAppBarTitleNodes[0]
                // Find the TextView child that contains the contact name
                val name = findTextInChildren(titleRow)
                topAppBarTitleNodes.forEach { it.recycle() }
                if (name != null && name.isNotEmpty()) {
                    Log.d(TAG, "Current SMS contact name (from top_app_bar_title_row): $name")
                    return name
                }
            }

            // Approach 2: Look for the toolbar title in Google Messages (legacy)
            val toolbarTitleId = "com.google.android.apps.messaging:id/toolbar_title"
            val toolbarTitleNodes = rootNode.findAccessibilityNodeInfosByViewId(toolbarTitleId)
            if (toolbarTitleNodes != null && toolbarTitleNodes.isNotEmpty()) {
                val name = toolbarTitleNodes[0].text?.toString()
                toolbarTitleNodes.forEach { it.recycle() }
                if (name != null && name.isNotEmpty()) {
                    Log.d(TAG, "Current SMS contact name (from toolbar_title): $name")
                    return name
                }
            }

            // Approach 3: Look for the conversation header
            val headerTitleId = "com.google.android.apps.messaging:id/conversation_title"
            val headerTitleNodes = rootNode.findAccessibilityNodeInfosByViewId(headerTitleId)
            if (headerTitleNodes != null && headerTitleNodes.isNotEmpty()) {
                val name = headerTitleNodes[0].text?.toString()
                headerTitleNodes.forEach { it.recycle() }
                if (name != null && name.isNotEmpty()) {
                    Log.d(TAG, "Current SMS contact name (from conversation_title): $name")
                    return name
                }
            }

            // Approach 4: Look for action bar title
            val actionBarId = "com.google.android.apps.messaging:id/action_bar_title"
            val actionBarNodes = rootNode.findAccessibilityNodeInfosByViewId(actionBarId)
            if (actionBarNodes != null && actionBarNodes.isNotEmpty()) {
                val name = actionBarNodes[0].text?.toString()
                actionBarNodes.forEach { it.recycle() }
                if (name != null && name.isNotEmpty()) {
                    Log.d(TAG, "Current SMS contact name (from action_bar_title): $name")
                    return name
                }
            }

            Log.d(TAG, "Could not determine current SMS contact name")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current SMS contact name", e)
            return null
        }
    }

    /**
     * Find text content in children of an accessibility node.
     * Used to extract contact name from Compose UI elements.
     */
    private fun findTextInChildren(node: AccessibilityNodeInfo): String? {
        // Check if this node has text
        val text = node.text?.toString()
        if (text != null && text.isNotEmpty()) {
            return text
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = findTextInChildren(child)
            child.recycle()
            if (childText != null && childText.isNotEmpty()) {
                return childText
            }
        }

        return null
    }

    private suspend fun performSMSSearch(rootNode: AccessibilityNodeInfo, searchQuery: String, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            Log.i(TAG, "Attempting to open SMS search and search for: $searchQuery")

            // Look for the search button in Google Messages
            val searchButtonId = "com.google.android.apps.messaging:id/action_zero_state_search"

            // Try to find search button by ID
            val searchNodes = rootNode.findAccessibilityNodeInfosByViewId(searchButtonId)
            if (searchNodes != null && searchNodes.isNotEmpty()) {
                Log.d(TAG, "Found search button with ID: $searchButtonId")
                val clicked = accessibilityService.clickNode(searchNodes[0])
                searchNodes.forEach { it.recycle() }

                if (clicked) {
                    Log.d(TAG, "Clicked search button, waiting for search field...")
                    // Wait for search field to appear
                    delay(800)

                    // Now find the search input field and enter text
                    val searchRootNode = accessibilityService.getCurrentRootNode()
                    if (searchRootNode != null) {
                        val searchFieldEntered = enterSMSSearchText(searchRootNode, searchQuery)
                        searchRootNode.recycle()
                        return searchFieldEntered
                    }
                }
            } else {
                Log.w(TAG, "Could not find search button by ID: $searchButtonId")
            }

            // Alternative: Look for search by content description or text
            // IMPORTANT: Only click if the node belongs to the Messages app to avoid triggering Google Search
            val searchByDesc = rootNode.findAccessibilityNodeInfosByText("Search")
            if (searchByDesc != null && searchByDesc.isNotEmpty()) {
                for (node in searchByDesc) {
                    // Skip nodes that don't belong to the Messages app
                    val nodePackage = node.packageName?.toString() ?: ""
                    if (!nodePackage.contains("messaging") && !nodePackage.contains("com.google.android.apps.messaging")) {
                        Log.d(TAG, "Skipping search node from non-Messages package: $nodePackage")
                        continue
                    }

                    val clickableNode = if (node.isClickable) node else findClickableParent(node)
                    if (clickableNode != null) {
                        Log.d(TAG, "Found search button by text/description in Messages app")
                        val clicked = accessibilityService.clickNode(clickableNode)

                        if (clickableNode != node) {
                            clickableNode.recycle()
                        }

                        if (clicked) {
                            delay(800)
                            val searchRootNode = accessibilityService.getCurrentRootNode()
                            if (searchRootNode != null) {
                                val searchFieldEntered = enterSMSSearchText(searchRootNode, searchQuery)
                                searchRootNode.recycle()
                                searchByDesc.forEach { it.recycle() }
                                return searchFieldEntered
                            }
                        }
                    }
                }
                searchByDesc.forEach { it.recycle() }
            }

            Log.w(TAG, "Could not find or click search button")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error performing SMS search", e)
            return false
        }
    }

    private suspend fun enterSMSSearchText(rootNode: AccessibilityNodeInfo, searchQuery: String): Boolean {
        try {
            Log.d(TAG, "Looking for SMS search input field...")

            // Try to find the search field by specific resource ID first
            val searchFieldId = "com.google.android.apps.messaging:id/zero_state_search_box_auto_complete"
            val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId(searchFieldId)

            if (searchFieldNodes != null && searchFieldNodes.isNotEmpty()) {
                Log.d(TAG, "Found search field by resource ID: $searchFieldId")
                val searchField = searchFieldNodes[0]

                // Set the search text
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    searchQuery
                )
                val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                if (textSet) {
                    Log.d(TAG, "Successfully entered search text: $searchQuery")

                    // Trigger search using ACTION_IME_ENTER (requires Android 11+)
                    Log.d(TAG, "Triggering IME action...")
                    delay(300) // Brief delay for text to be set

                    val imeActionPerformed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Log.d(TAG, "Using ACTION_IME_ENTER (Android 11+)")
                        searchField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else {
                        // Fallback for older Android versions: use KEYCODE_ENTER
                        Log.d(TAG, "Android < 11, using KEYCODE_ENTER fallback")
                        try {
                            val process = Runtime.getRuntime().exec("input keyevent 66")
                            process.waitFor() == 0
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                            false
                        }
                    }
                    if (imeActionPerformed) {
                        Log.i(TAG, "✅ Successfully triggered IME action")
                    } else {
                        Log.w(TAG, "❌ IME action returned false")
                    }

                    searchFieldNodes.forEach { it.recycle() }

                    // Wait for search results to appear
                    val accessibilityService = WhizAccessibilityService.getInstance()
                    if (accessibilityService != null) {
                        val resultsAppeared = waitForCondition(maxWaitMs = 3000) {
                            val currentRoot = accessibilityService.getCurrentRootNode()
                            if (currentRoot != null) {
                                val chatResults = currentRoot.findAccessibilityNodeInfosByViewId(
                                    "com.google.android.apps.messaging:id/zero_state_search_chat_results"
                                )
                                val hasResults = chatResults != null && chatResults.isNotEmpty()
                                chatResults?.forEach { it.recycle() }
                                currentRoot.recycle()
                                hasResults
                            } else {
                                false
                            }
                        }
                        if (resultsAppeared) {
                            Log.i(TAG, "✅ Search results appeared")
                        } else {
                            Log.w(TAG, "Search results did not appear after IME_ENTER, trying KEYCODE_ENTER fallback...")

                            // Try sending KEYCODE_ENTER (keyevent 66) as a fallback
                            // Note: KEYCODE_SEARCH (84) triggers system-wide Google Search, so we use Enter instead
                            try {
                                val process = Runtime.getRuntime().exec("input keyevent 66")
                                process.waitFor()
                                Log.d(TAG, "Sent KEYCODE_ENTER (66) as fallback")
                                delay(500)

                                // Wait for results again
                                val resultsAppearedAfterKeycode = waitForCondition(maxWaitMs = 3000) {
                                    val currentRoot = accessibilityService.getCurrentRootNode()
                                    if (currentRoot != null) {
                                        val chatResults = currentRoot.findAccessibilityNodeInfosByViewId(
                                            "com.google.android.apps.messaging:id/zero_state_search_chat_results"
                                        )
                                        val hasResults = chatResults != null && chatResults.isNotEmpty()
                                        chatResults?.forEach { it.recycle() }
                                        currentRoot.recycle()
                                        hasResults
                                    } else false
                                }

                                if (resultsAppearedAfterKeycode) {
                                    Log.i(TAG, "✅ Search results appeared after KEYCODE_ENTER fallback")
                                } else {
                                    Log.w(TAG, "Search results still not appearing after KEYCODE_ENTER")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                            }
                        }
                    }

                    return true
                } else {
                    Log.w(TAG, "Failed to set search text via resource ID")
                    searchFieldNodes.forEach { it.recycle() }
                }
            }

            // Fallback: Find any EditText nodes (search field)
            Log.d(TAG, "Trying fallback: searching for any EditText nodes")
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, editTextNodes)

            if (editTextNodes.isNotEmpty()) {
                Log.d(TAG, "Found ${editTextNodes.size} EditText nodes, using first one for search")
                val searchField = editTextNodes[0]

                // Set the search text
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    searchQuery
                )
                val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)

                if (textSet) {
                    Log.d(TAG, "Successfully entered search text: $searchQuery (fallback path)")
                    editTextNodes.forEach { it.recycle() }

                    // Trigger search by sending KEYCODE_ENTER
                    // Note: KEYCODE_SEARCH (84) triggers system-wide Google Search, so we use Enter instead
                    Log.d(TAG, "Triggering search submission with KEYCODE_ENTER (fallback path)...")
                    delay(300)

                    try {
                        val process = Runtime.getRuntime().exec("input keyevent 66") // KEYCODE_ENTER
                        process.waitFor()
                        Log.d(TAG, "Sent KEYCODE_ENTER (66) - fallback")
                        delay(500)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                        return false
                    }

                    // Wait for search results
                    Log.d(TAG, "Waiting for search results (fallback path)...")
                    val accessibilityService = WhizAccessibilityService.getInstance()
                    if (accessibilityService != null) {
                        val resultsAppeared = waitForCondition(maxWaitMs = 3000) {
                            val currentRoot = accessibilityService.getCurrentRootNode()
                            if (currentRoot != null) {
                                val chatResults = currentRoot.findAccessibilityNodeInfosByViewId(
                                    "com.google.android.apps.messaging:id/zero_state_search_chat_results"
                                )
                                val hasResults = chatResults != null && chatResults.isNotEmpty()
                                chatResults?.forEach { it.recycle() }
                                currentRoot.recycle()
                                hasResults
                            } else {
                                false
                            }
                        }

                        if (resultsAppeared) {
                            Log.d(TAG, "Search results appeared after KEYCODE_ENTER (fallback path)")
                        } else {
                            Log.w(TAG, "Search results did not appear after KEYCODE_ENTER (fallback path)")
                        }
                    } else {
                        Log.w(TAG, "Accessibility service not available (fallback path)")
                        delay(1000)
                    }

                    return true
                } else {
                    Log.w(TAG, "Failed to set search text")
                    editTextNodes.forEach { it.recycle() }
                }
            } else {
                Log.w(TAG, "Could not find search input field")
            }

            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error entering SMS search text", e)
            return false
        }
    }

    // ========== YouTube Music Specific Functions ==========

    suspend fun playYouTubeMusicSong(
        query: String,
        contentType: String = "song"
    ): MusicActionResult {
        Log.d(TAG, "Playing on YouTube Music via deep link + accessibility: $query (contentType=$contentType)")
        trackAction("playYouTubeMusicSong: $query")

        try {
            val ytMusicPackage = "com.google.android.apps.youtube.music"

            // 1. Build deep link to YouTube Music search results
            val deepLinkUrl = "https://music.youtube.com/search?q=${Uri.encode(query)}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl)).apply {
                setPackage(ytMusicPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 2. Start bubble overlay before launching (same pattern as launchApp)
            if (hasOverlayPermission()) {
                BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            }

            context.startActivity(intent)

            // Start overlay if permission granted
            if (hasOverlayPermission()) {
                startBubbleOverlay()
            }

            // 3. Wait for YouTube Music to be in foreground
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for playYouTubeMusicSong",
                    packageName = ytMusicPackage
                )
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Accessibility service not enabled"
                )
            }

            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = ytMusicPackage,
                maxWaitMs = 5000
            )

            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "ytmusic_app_not_ready", "YouTube Music did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("ytmusic_app_not_ready", "YouTube Music did not become ready in time", "com.google.android.apps.youtube.music")
                }
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "YouTube Music did not become ready in time"
                )
            }

            // 4. Wait for search results to load and click the first result
            //    Use a longer wait since the deep link needs to load the search page
            val clickResult = waitForAndClickPlayButton(
                accessibilityService = accessibilityService,
                contentType = contentType,
                maxWaitMs = 8000
            )

            if (!clickResult.clicked) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "ytmusic_play_deeplink_no_result", "Deep link search loaded but could not find/click result for: $query")
                    dumpRoot.recycle()
                }
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not find search result to click after deep link"
                )
            }

            // 5. Verify playback started
            val isSongType = contentType.lowercase() in listOf("song", "video")
            if (isSongType) {
                // For songs/videos, wait for mini player to show the playing title
                delay(2000)
                val verifyRoot = accessibilityService.getCurrentRootNode()
                val nowPlaying = if (verifyRoot != null) {
                    val title = getMiniPlayerTitle(verifyRoot)
                    verifyRoot.recycle()
                    title
                } else null

                return MusicActionResult(
                    success = true,
                    action = "play_song",
                    query = query,
                    nowPlaying = nowPlaying ?: clickResult.clickedTitle ?: query
                )
            } else {
                // For albums/playlists/artists, clicking opens the page — playback may not start immediately
                return MusicActionResult(
                    success = true,
                    action = "play_song",
                    query = query,
                    nowPlaying = clickResult.clickedTitle ?: query
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing YouTube Music via deep link", e)
            logScreenAgentError(
                reason = "ytmusic_play_error",
                errorMessage = "Exception playing YouTube Music '$query': ${e.message}",
                packageName = "com.google.android.apps.youtube.music"
            )
            return MusicActionResult(
                success = false,
                action = "play_song",
                query = query,
                error = "Failed to play via deep link: ${e.message}"
            )
        }
    }

    suspend fun queueYouTubeMusicSong(query: String): MusicActionResult {
        Log.d(TAG, "Attempting to queue song on YouTube Music: $query")
        trackAction("queueYouTubeMusicSong: $query")

        try {
            // Auto-launch YouTube Music if not already open
            val launchResult = launchApp("YouTube Music", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch YouTube Music: ${launchResult.error}")
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Failed to open YouTube Music: ${launchResult.error}"
                )
            }
            Log.i(TAG, "YouTube Music launched successfully")
            delay(1000) // Wait for YouTube Music to fully load

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for queueYouTubeMusicSong",
                    packageName = "com.google.android.apps.youtube.music"
                )
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Accessibility service not enabled"
                )
            }

            // Wait for YouTube Music to be ready (max 3 seconds)
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.youtube.music",
                maxWaitMs = 3000
            )

            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "ytmusic_app_not_ready", "YouTube Music did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("ytmusic_app_not_ready", "YouTube Music did not become ready in time", "com.google.android.apps.youtube.music")
                }
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "YouTube Music did not become ready in time"
                )
            }

            // Navigate to a searchable screen (speed dial or search screen)
            val navigationSuccess = navigateToYouTubeMusicSearchableScreen(accessibilityService)
            if (!navigationSuccess) {
                // UI dump for navigation failure
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "ytmusic_queue_nav_failed", "Could not navigate to searchable screen in YouTube Music for queueing")
                    dumpRoot.recycle()
                }
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not navigate to searchable screen in YouTube Music"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                logScreenAgentError("root_node_null", "Could not get root node", "com.google.android.apps.youtube.music")
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node"
                )
            }

            // Perform search with filter chip selection (shared logic)
            val searchResult = performYouTubeMusicSearch(rootNode, query, "song", accessibilityService)
            rootNode.recycle()

            if (searchResult is YouTubeMusicSearchResult.Error) {
                // UI dump for search failure
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "ytmusic_queue_search_failed", searchResult.message)
                    dumpRoot.recycle()
                }
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = searchResult.message
                )
            }

            // Make sure we're on the "YT Music" tab (not Library or Downloads)
            val tabRootNode = accessibilityService.getCurrentRootNode()
            if (tabRootNode != null) {
                val ytMusicTabClicked = ensureYTMusicTabSelected(tabRootNode, accessibilityService)
                if (ytMusicTabClicked) {
                    Log.d(TAG, "Switched to YT Music tab for queueing")
                    delay(500) // Wait for tab content to load
                }
                tabRootNode.recycle()
            }

            // Poll for context menu button to appear and open it (max 3 seconds)
            val menuSuccess = waitForAndOpenContextMenu(accessibilityService, maxWaitMs = 3000)

            if (!menuSuccess) {
                // Dump UI for debugging (the openYouTubeMusicContextMenu already dumps, but this captures the full state)
                val debugRoot = accessibilityService.getCurrentRootNode()
                if (debugRoot != null) {
                    dumpUIHierarchy(debugRoot, "ytmusic_queue_no_context_menu", "Could not find or open context menu after waiting for search results")
                    debugRoot.recycle()
                }
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not find or open context menu after waiting for search results"
                )
            }

            // Poll for "Add to queue" option to appear and click it (max 1.5 seconds)
            val queueSuccess = waitForAndClickAddToQueue(accessibilityService, maxWaitMs = 1500)
            rootNode.recycle()

            return if (queueSuccess) {
                MusicActionResult(
                    success = true,
                    action = "queue_song",
                    query = query
                )
            } else {
                // Note: waitForAndClickAddToQueue already dumps UI when it fails
                MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not find 'Add to queue' option in menu"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error queueing YouTube Music song", e)
            logScreenAgentError(
                reason = "ytmusic_queue_error",
                errorMessage = "Exception queueing YouTube Music song '$query': ${e.message}",
                packageName = "com.google.android.apps.youtube.music"
            )
            return MusicActionResult(
                success = false,
                action = "queue_song",
                query = query,
                error = "Error queueing song: ${e.message}"
            )
        }
    }

    /**
     * Pause or resume YouTube Music playback via media key event.
     * This function toggles between playing and paused states.
     */
    suspend fun pauseYouTubeMusic(): MusicActionResult {
        Log.d(TAG, "Toggling YouTube Music playback via media key event")
        trackAction("pauseYouTubeMusic")

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val wasPlaying = audioManager.isMusicActive

            // Dispatch MEDIA_PLAY_PAUSE key event (down + up)
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            )

            delay(300) // Brief wait for state to update

            val isNowPlaying = audioManager.isMusicActive
            val newState = if (isNowPlaying) "playing" else "paused"

            Log.d(TAG, "Media key dispatched, wasPlaying=$wasPlaying, newState=$newState")

            return MusicActionResult(
                success = true,
                action = "pause_music",
                nowPlaying = newState
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling playback via media key", e)
            return MusicActionResult(
                success = false,
                action = "pause_music",
                error = "Failed to toggle playback: ${e.message}"
            )
        }
    }

    /**
     * Recursively search for a play/pause button by content description.
     */
    private fun findPlayPauseButtonRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val contentDesc = node.contentDescription?.toString() ?: ""
        if ((contentDesc.contains("Pause video", ignoreCase = true) ||
             contentDesc.contains("Play video", ignoreCase = true)) && node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findPlayPauseButtonRecursive(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    // ========== Google Maps Functions ==========

    suspend fun searchGoogleMapsLocation(address: String): MapsActionResult {
        Log.d(TAG, "Attempting to search for location in Google Maps: $address")
        trackAction("searchGoogleMapsLocation: $address")

        try {
            // Launch Google Maps with geo: intent directly to search results
            val launchResult = launchGoogleMapsSearch(address)
            if (!launchResult.success) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps geo: search launched for: $address")

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for searchGoogleMapsLocation",
                    packageName = "com.google.android.apps.maps"
                )
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Accessibility service not enabled"
                )
            }

            // Wait for Maps to be in foreground
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.maps",
                maxWaitMs = 5000
            )
            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_app_not_ready", "Google Maps did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("gmaps_app_not_ready", "Google Maps did not become ready in time", "com.google.android.apps.maps")
                }
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Google Maps did not become ready in time"
                )
            }

            // Poll for search results to load (wait for search_list_layout or location details to appear)
            val resultsLoaded = waitForCondition(maxWaitMs = 8000) {
                val node = accessibilityService.getCurrentRootNode()
                if (node != null) {
                    // Dismiss "Exit navigation?" dialog if present
                    dismissExitNavigationDialog(node)
                    val state = detectGoogleMapsScreenState(node)
                    node.recycle()
                    state == GoogleMapsScreenState.SEARCH_RESULTS_LIST || state == GoogleMapsScreenState.LOCATION_DETAILS
                } else false
            }
            if (!resultsLoaded) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_search_results_timeout", "Search results did not load in time")
                    dumpRoot.recycle()
                }
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Search results did not load in time"
                )
            }

            // Select the first non-sponsored result from the list
            // If all visible results are sponsored, scroll down and retry
            Log.d(TAG, "Search results loaded, selecting first non-sponsored location from results")

            val maxScrollAttempts = 3
            var locationSelected = false

            for (scrollAttempt in 0 until maxScrollAttempts) {
                if (scrollAttempt > 0) {
                    Log.d(TAG, "All visible results may be sponsored, scrolling down (attempt $scrollAttempt)")
                    val scrollSuccess = scrollGoogleMapsResultsList()
                    if (!scrollSuccess) {
                        Log.w(TAG, "Failed to scroll results list")
                        break
                    }
                    delay(1000) // Wait for scroll to complete
                }

                val listRootNode = accessibilityService.getCurrentRootNode()
                if (listRootNode == null) {
                    Log.w(TAG, "Could not get root node for location selection")
                    continue
                }

                val locationClicked = clickLocationFromList(listRootNode, 1, null, accessibilityService, skipSponsored = true)
                listRootNode.recycle()

                // Wait for screen to change from SEARCH_RESULTS_LIST
                waitForCondition(maxWaitMs = 2000) {
                    val node = accessibilityService.getCurrentRootNode()
                    if (node != null) {
                        val state = detectGoogleMapsScreenState(node)
                        node.recycle()
                        state != GoogleMapsScreenState.SEARCH_RESULTS_LIST
                    } else false
                }

                // Check what screen we're on after the click
                val checkRootNode = accessibilityService.getCurrentRootNode()
                if (checkRootNode != null) {
                    val screenState = detectGoogleMapsScreenState(checkRootNode)
                    checkRootNode.recycle()

                    when (screenState) {
                        GoogleMapsScreenState.LOCATION_DETAILS -> {
                            // Successfully selected a location
                            Log.d(TAG, "Successfully navigated to location details")
                            locationSelected = true
                            break
                        }
                        GoogleMapsScreenState.FILTERS_SCREEN -> {
                            // Accidentally opened Filters - press Back to close it and retry
                            Log.w(TAG, "Accidentally opened Filters screen - pressing Back to close")
                            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            waitForCondition(maxWaitMs = 1500) {
                                val node = accessibilityService.getCurrentRootNode()
                                if (node != null) {
                                    val state = detectGoogleMapsScreenState(node)
                                    node.recycle()
                                    state != GoogleMapsScreenState.FILTERS_SCREEN
                                } else false
                            }
                            // Continue to next scroll attempt
                        }
                        GoogleMapsScreenState.SEARCH_RESULTS_LIST -> {
                            // Still on search results - try scrolling to find non-sponsored results
                            Log.d(TAG, "Still on search results list, will try scrolling (attempt ${scrollAttempt + 1}/$maxScrollAttempts)")
                        }
                        else -> {
                            // Some other state (could be direct navigation to a single result, etc.)
                            Log.d(TAG, "Click resulted in screen state $screenState - treating as success")
                            locationSelected = true
                            break
                        }
                    }
                } else if (locationClicked) {
                    // Couldn't get root node but click succeeded - assume success
                    locationSelected = true
                    break
                }
            }

            if (!locationSelected) {
                // Check final screen state before returning error
                val finalCheckRoot = accessibilityService.getCurrentRootNode()
                if (finalCheckRoot != null) {
                    val finalState = detectGoogleMapsScreenState(finalCheckRoot)
                    finalCheckRoot.recycle()
                    // Only treat LOCATION_DETAILS as success, not FILTERS_SCREEN or other states
                    if (finalState == GoogleMapsScreenState.LOCATION_DETAILS) {
                        Log.d(TAG, "Final state is LOCATION_DETAILS - treating as success")
                        locationSelected = true
                    } else if (finalState == GoogleMapsScreenState.FILTERS_SCREEN) {
                        // Still on filters screen - press Back and try one more time
                        Log.w(TAG, "Still on Filters screen after retries - pressing Back")
                        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        waitForCondition(maxWaitMs = 1500) {
                            val node = accessibilityService.getCurrentRootNode()
                            if (node != null) {
                                val state = detectGoogleMapsScreenState(node)
                                node.recycle()
                                state != GoogleMapsScreenState.FILTERS_SCREEN
                            } else false
                        }
                    }
                }
            }

            if (!locationSelected) {
                Log.w(TAG, "Could not select location from search results after $maxScrollAttempts scroll attempts")
                // Dump UI hierarchy to help debug why no non-sponsored result could be selected
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_no_nonsponsored_result", "Could not select non-sponsored location for '$address' after $maxScrollAttempts scroll attempts")
                    dumpRoot.recycle()
                }
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not select a non-sponsored location from search results after scrolling"
                )
            }

            // Extract the actual address from the place details
            val finalRootNode = accessibilityService.getCurrentRootNode()
            val actualAddress = if (finalRootNode != null) {
                val extracted = extractAddressFromMaps(finalRootNode)
                finalRootNode.recycle()
                extracted ?: address // Fallback to search query if extraction fails
            } else {
                address
            }

            return MapsActionResult(
                success = true,
                action = "search_location",
                location = actualAddress
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error searching Google Maps location", e)
            logScreenAgentError(
                reason = "gmaps_search_error",
                errorMessage = "Exception searching Google Maps location '$address': ${e.message}",
                packageName = "com.google.android.apps.maps"
            )
            return MapsActionResult(
                success = false,
                action = "search_location",
                location = address,
                error = "Error searching location: ${e.message}"
            )
        }
    }

    suspend fun getGoogleMapsDirections(mode: String? = null, search: String? = null, position: Int? = null, fragment: String? = null): MapsActionResult {
        Log.d(TAG, "Attempting to get directions in Google Maps with mode: ${mode ?: "default"}, search: $search, position: $position, fragment: $fragment")
        trackAction("getGoogleMapsDirections: mode=${mode ?: "default"}, search=$search")

        try {
            // If search is provided, search for the location first
            if (search != null) {
                Log.d(TAG, "Searching for location before getting directions: $search")
                val searchResult = searchGoogleMapsLocation(search)
                if (!searchResult.success) {
                    Log.e(TAG, "Failed to search for location: ${searchResult.error}")
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Failed to search for '$search': ${searchResult.error}"
                    )
                }
                Log.i(TAG, "Successfully searched and selected location: ${searchResult.location}")
                // searchGoogleMapsLocation already selects the first non-sponsored result
                // so we can proceed directly to getting directions
            }

            // If position or fragment is provided, first select the location from the list
            // For directions, skip sponsored results and scroll if needed
            if (position != null || fragment != null) {
                Log.d(TAG, "Selecting location from list before getting directions (skipping sponsored)")

                val maxScrollAttempts = 3
                var selectResult: MapsActionResult? = null

                for (scrollAttempt in 0 until maxScrollAttempts) {
                    if (scrollAttempt > 0) {
                        Log.d(TAG, "All visible results may be sponsored, scrolling down (attempt $scrollAttempt)")
                        // Scroll down to reveal more results
                        val scrollSuccess = scrollGoogleMapsResultsList()
                        if (!scrollSuccess) {
                            Log.w(TAG, "Failed to scroll results list")
                            break
                        }
                        delay(1000) // Wait for scroll to complete
                    }

                    selectResult = selectLocationFromList(position, fragment, skipSponsored = true)
                    if (selectResult.success) {
                        Log.i(TAG, "Successfully selected non-sponsored location from list")
                        break
                    }

                    // Check if the error indicates no non-sponsored results found
                    if (selectResult.error?.contains("non-sponsored") == true) {
                        Log.d(TAG, "No non-sponsored results found, will try scrolling")
                        continue
                    } else {
                        // Different error, don't retry
                        break
                    }
                }

                if (selectResult == null || !selectResult.success) {
                    Log.e(TAG, "Failed to select location from list: ${selectResult?.error}")
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Failed to select location: ${selectResult?.error ?: "unknown error"}"
                    )
                }

                delay(1500) // Wait for location details screen to fully load
            }

            // Auto-launch Google Maps if not already open
            val launchResult = launchApp("Maps", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch Google Maps: ${launchResult.error}")
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps launched successfully")
            delay(1000) // Wait for Google Maps to fully load

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError(
                    reason = "accessibility_unavailable",
                    errorMessage = "Accessibility service not available for getGoogleMapsDirections",
                    packageName = "com.google.android.apps.maps"
                )
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Accessibility service not enabled"
                )
            }

            // Wait for Google Maps to be ready (max 3 seconds)
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.maps",
                maxWaitMs = 3000
            )

            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_directions_app_not_ready", "Google Maps did not become ready in time")
                    dumpRoot.recycle()
                }
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Google Maps did not become ready in time"
                )
            }

            var rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Could not get root node"
                )
            }

            // Detect current screen state and handle accordingly
            val screenState = detectGoogleMapsScreenState(rootNode)
            Log.d(TAG, "Google Maps screen state: $screenState")

            when (screenState) {
                GoogleMapsScreenState.LOCATION_DETAILS -> {
                    // Perfect - we're on location details page, just proceed to click Directions
                    Log.d(TAG, "On location details page, will click Directions button")
                    rootNode.recycle()
                }
                GoogleMapsScreenState.DIRECTIONS_INPUT -> {
                    // On directions input screen - press back to get to location details first
                    Log.d(TAG, "On directions input screen, pressing back to return to location details")
                    rootNode.recycle()
                    val backPressed = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    if (backPressed) {
                        Log.d(TAG, "Successfully pressed back button")
                        delay(1000) // Wait for UI to update
                    } else {
                        Log.w(TAG, "Failed to press back button, continuing anyway")
                    }
                    // Get fresh root node after back press
                    rootNode = accessibilityService.getCurrentRootNode()
                    if (rootNode == null) {
                        return MapsActionResult(
                            success = false,
                            action = "get_directions",
                            mode = mode,
                            error = "Could not get root node after pressing back"
                        )
                    }
                }
                GoogleMapsScreenState.ACTIVE_NAVIGATION -> {
                    // We're in active turn-by-turn navigation - need to exit first
                    Log.d(TAG, "In active navigation, pressing back to exit navigation mode")
                    rootNode.recycle()

                    // Press back and wait for navigation to exit
                    var exitedNavigation = false
                    for (backAttempt in 1..3) {
                        val backPressed = accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        if (backPressed) {
                            Log.d(TAG, "Back press $backAttempt successful, waiting for screen state change")
                        } else {
                            Log.w(TAG, "Back press $backAttempt failed")
                            continue
                        }

                        // Wait for screen state to change from ACTIVE_NAVIGATION
                        exitedNavigation = waitForCondition(maxWaitMs = 2000) {
                            val checkNode = accessibilityService.getCurrentRootNode()
                            if (checkNode != null) {
                                val newState = detectGoogleMapsScreenState(checkNode)
                                checkNode.recycle()
                                newState != GoogleMapsScreenState.ACTIVE_NAVIGATION
                            } else {
                                false
                            }
                        }

                        if (exitedNavigation) {
                            Log.d(TAG, "Exited navigation after $backAttempt back presses")
                            break
                        }
                    }

                    // Get fresh root node after exiting navigation
                    rootNode = accessibilityService.getCurrentRootNode()
                    if (rootNode == null) {
                        return MapsActionResult(
                            success = false,
                            action = "get_directions",
                            mode = mode,
                            error = "Could not get root node after exiting navigation"
                        )
                    }

                    // Re-check state - we might now be on location details or need to search again
                    var newState = detectGoogleMapsScreenState(rootNode)
                    Log.d(TAG, "After exiting navigation, screen state is: $newState")
                    if (newState == GoogleMapsScreenState.ACTIVE_NAVIGATION) {
                        dumpUIHierarchy(rootNode, "gmaps_navigation_exit_failed", "Could not exit active navigation mode")
                        rootNode.recycle()
                        return MapsActionResult(
                            success = false,
                            action = "get_directions",
                            mode = mode,
                            error = "Could not exit active navigation mode"
                        )
                    }

                }
                GoogleMapsScreenState.TRANSIT_ROUTE_DETAIL -> {
                    Log.d(TAG, "On transit route detail, pressing back once to return to mode selection")
                    rootNode.recycle()
                    accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    val reachedDirections = waitForCondition(maxWaitMs = 3000) {
                        val checkNode = accessibilityService.getCurrentRootNode()
                        if (checkNode != null) {
                            val state = detectGoogleMapsScreenState(checkNode)
                            checkNode.recycle()
                            state == GoogleMapsScreenState.DIRECTIONS_INPUT || state == GoogleMapsScreenState.LOCATION_DETAILS
                        } else false
                    }
                    if (!reachedDirections) {
                        Log.w(TAG, "Did not reach directions screen after back from transit route detail, pressing back once more")
                        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1500)
                    }
                }
                GoogleMapsScreenState.SEARCH_RESULTS_LIST -> {
                    // We're on the search results list - need to select a result first
                    Log.d(TAG, "On search results list, selecting first non-sponsored result")
                    rootNode.recycle()

                    // Select the first non-sponsored result
                    val selectResult = selectLocationFromList(position = 1, fragment = null, skipSponsored = true)
                    if (!selectResult.success) {
                        // If no non-sponsored results found, try scrolling
                        Log.d(TAG, "No non-sponsored results found, trying to scroll")
                        val scrollSuccess = scrollGoogleMapsResultsList()
                        if (scrollSuccess) {
                            delay(1000)
                            val retryResult = selectLocationFromList(position = 1, fragment = null, skipSponsored = true)
                            if (!retryResult.success) {
                                val dumpRoot = accessibilityService.getCurrentRootNode()
                                if (dumpRoot != null) {
                                    dumpUIHierarchy(dumpRoot, "gmaps_no_nonsponsor_result", "Could not find non-sponsored result in search list: ${retryResult.error}")
                                    dumpRoot.recycle()
                                }
                                return MapsActionResult(
                                    success = false,
                                    action = "get_directions",
                                    mode = mode,
                                    error = "Could not find non-sponsored result in search list: ${retryResult.error}"
                                )
                            }
                        } else {
                            val dumpRoot = accessibilityService.getCurrentRootNode()
                            if (dumpRoot != null) {
                                dumpUIHierarchy(dumpRoot, "gmaps_no_nonsponsor_result", "Could not find non-sponsored result in search list: ${selectResult.error}")
                                dumpRoot.recycle()
                            }
                            return MapsActionResult(
                                success = false,
                                action = "get_directions",
                                mode = mode,
                                error = "Could not find non-sponsored result in search list: ${selectResult.error}"
                            )
                        }
                    }
                    Log.i(TAG, "Successfully selected location from search results list")
                    delay(1500) // Wait for location details to load
                }
                GoogleMapsScreenState.FILTERS_SCREEN -> {
                    // Accidentally on the Filters screen - press Back to close it
                    Log.w(TAG, "On Filters screen, pressing back to close")
                    rootNode.recycle()
                    accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    waitForCondition(maxWaitMs = 1500) {
                        val node = accessibilityService.getCurrentRootNode()
                        if (node != null) {
                            val state = detectGoogleMapsScreenState(node)
                            node.recycle()
                            state != GoogleMapsScreenState.FILTERS_SCREEN
                        } else false
                    }
                    // Continue to the Directions button click logic below
                }
                else -> {
                    Log.w(TAG, "Unknown screen state, pressing back to try to reach a known state")
                    // Dump UI hierarchy for debugging unknown states
                    dumpUIHierarchy(rootNode, "google_maps_unknown_state", "Google Maps screen state not recognized")
                    rootNode.recycle()

                    // Press back multiple times if needed to get to a known screen
                    for (backAttempt in 1..3) {
                        accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1000)

                        // Re-detect screen state after pressing back
                        val newRootNode = accessibilityService.getCurrentRootNode()
                        if (newRootNode != null) {
                            val newScreenState = detectGoogleMapsScreenState(newRootNode)
                            Log.d(TAG, "After back press $backAttempt, new screen state: $newScreenState")
                            newRootNode.recycle()

                            when (newScreenState) {
                                GoogleMapsScreenState.LOCATION_DETAILS -> {
                                    Log.d(TAG, "Now on location details page, can proceed")
                                    break // Exit the loop, we're in a good state
                                }
                                GoogleMapsScreenState.DIRECTIONS_INPUT -> {
                                    Log.d(TAG, "Now on directions screen after pressing back")
                                    break // Exit the loop, we're in a good state
                                }
                                GoogleMapsScreenState.ACTIVE_NAVIGATION -> {
                                    Log.d(TAG, "In active navigation, pressing back again to exit")
                                    // Continue the loop to press back again
                                }
                                GoogleMapsScreenState.SEARCH_RESULTS_LIST -> {
                                    Log.d(TAG, "Now on search results list, can proceed to select result")
                                    break // Exit the loop, we're in a good state
                                }
                                GoogleMapsScreenState.FILTERS_SCREEN -> {
                                    Log.d(TAG, "On Filters screen, pressing back again to close")
                                    // Continue the loop to press back again
                                }
                                GoogleMapsScreenState.TRANSIT_ROUTE_DETAIL -> {
                                    Log.d(TAG, "Now on transit route detail after pressing back")
                                    break
                                }
                                else -> {
                                    Log.w(TAG, "Still in unknown state after back press $backAttempt")
                                    // Continue the loop to try again
                                }
                            }
                        }
                    }
                }
            }

            // Get fresh root node after handling screen states (especially important after UNKNOWN pressed back)
            var currentRootNode = accessibilityService.getCurrentRootNode()
            if (currentRootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Could not get root node after screen state handling"
                )
            }

            // After pressing back from UNKNOWN, we might now be on SEARCH_RESULTS_LIST or FILTERS_SCREEN
            // Need to check and handle appropriately
            var stateAfterHandling = detectGoogleMapsScreenState(currentRootNode)

            // If still on Filters screen, press Back to close it
            if (stateAfterHandling == GoogleMapsScreenState.FILTERS_SCREEN) {
                Log.w(TAG, "Still on Filters screen after handling - pressing back to close")
                currentRootNode?.recycle()
                accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                waitForCondition(maxWaitMs = 1500) {
                    val node = accessibilityService.getCurrentRootNode()
                    if (node != null) {
                        val state = detectGoogleMapsScreenState(node)
                        node.recycle()
                        state != GoogleMapsScreenState.FILTERS_SCREEN
                    } else false
                }
                currentRootNode = accessibilityService.getCurrentRootNode()
                if (currentRootNode != null) {
                    stateAfterHandling = detectGoogleMapsScreenState(currentRootNode)
                }
            }

            if (stateAfterHandling == GoogleMapsScreenState.SEARCH_RESULTS_LIST) {
                Log.d(TAG, "After handling, now on search results list - selecting first non-sponsored result")
                currentRootNode?.recycle()

                val selectResult = selectLocationFromList(position = 1, fragment = null, skipSponsored = true)
                if (!selectResult.success) {
                    Log.d(TAG, "No non-sponsored results found after handling, trying to scroll")
                    val scrollSuccess = scrollGoogleMapsResultsList()
                    if (scrollSuccess) {
                        delay(1000)
                        val retryResult = selectLocationFromList(position = 1, fragment = null, skipSponsored = true)
                        if (!retryResult.success) {
                            val dumpRoot = accessibilityService.getCurrentRootNode()
                            if (dumpRoot != null) {
                                dumpUIHierarchy(dumpRoot, "gmaps_no_nonsponsor_after_handling", "Could not find non-sponsored result after handling: ${retryResult.error}")
                                dumpRoot.recycle()
                            }
                            return MapsActionResult(
                                success = false,
                                action = "get_directions",
                                mode = mode,
                                error = "Could not find non-sponsored result after handling: ${retryResult.error}"
                            )
                        }
                    } else {
                        val dumpRoot = accessibilityService.getCurrentRootNode()
                        if (dumpRoot != null) {
                            dumpUIHierarchy(dumpRoot, "gmaps_no_nonsponsor_after_handling", "Could not find non-sponsored result after handling: ${selectResult.error}")
                            dumpRoot.recycle()
                        }
                        return MapsActionResult(
                            success = false,
                            action = "get_directions",
                            mode = mode,
                            error = "Could not find non-sponsored result after handling: ${selectResult.error}"
                        )
                    }
                }
                Log.i(TAG, "Successfully selected location from search results list after handling")
                delay(1500) // Wait for location details to load

                // Get fresh root node after selecting
                currentRootNode = accessibilityService.getCurrentRootNode()
                if (currentRootNode == null) {
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Could not get root node after selecting from search list"
                    )
                }
            }

            // Ensure we have a valid root node for direction checks
            if (currentRootNode == null) {
                currentRootNode = accessibilityService.getCurrentRootNode()
                if (currentRootNode == null) {
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Could not get root node for directions check"
                    )
                }
            }

            // Re-check if we're now on the directions screen (in case we were already there)
            // Check for trip_details_footer_layout which contains the Start button on directions screen
            // This is more reliable than just looking for any "Start" button (which can appear on location pages)
            val footerLayoutNodes = currentRootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/trip_details_footer_layout")
            val hasFooterLayout = footerLayoutNodes != null && footerLayoutNodes.isNotEmpty()
            footerLayoutNodes?.forEach { it.recycle() }

            val alreadyOnDirectionsScreen = if (hasFooterLayout) {
                Log.d(TAG, "trip_details_footer_layout found - already on directions screen")
                true
            } else {
                // Fallback: check for directions_mode_tabs
                val directionsTabsNodes = currentRootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/directions_mode_tabs")
                val hasTabs = directionsTabsNodes != null && directionsTabsNodes.isNotEmpty()
                directionsTabsNodes?.forEach { it.recycle() }
                if (hasTabs) {
                    Log.d(TAG, "directions_mode_tabs found - already on directions screen")
                }
                hasTabs
            }

            if (!alreadyOnDirectionsScreen) {
                Log.d(TAG, "Not on directions screen, will click Directions button")
                // Find and click the "Directions" button
                val directionsClicked = clickGoogleMapsDirections(currentRootNode, accessibilityService)
                currentRootNode.recycle()

                if (!directionsClicked) {
                    // UI dump for directions button not found
                    val dumpRoot = accessibilityService.getCurrentRootNode()
                    if (dumpRoot != null) {
                        dumpUIHierarchy(dumpRoot, "gmaps_directions_button_not_found", "Could not find Directions button in Google Maps")
                        dumpRoot.recycle()
                    }
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Could not find Directions button in Google Maps"
                    )
                }

                // Wait for directions screen to appear with Start button
                delay(1500)
            } else {
                currentRootNode.recycle()
            }

            // Wait for directions screen to be fully loaded
            // Look for Start button OR directions_mode_tabs (for transit mode which has no Start button initially)
            var modeRootNode: AccessibilityNodeInfo? = null
            var directionsScreenFound = false
            var hasStartButton = false
            for (attempt in 1..5) {
                modeRootNode = accessibilityService.getCurrentRootNode()
                if (modeRootNode != null) {
                    // Check for Start button using built-in search (no depth limit - Start button is at depth ~22)
                    val startNodes = modeRootNode.findAccessibilityNodeInfosByText("Start")
                    hasStartButton = startNodes.any { node ->
                        if (node.isClickable && node.className == "android.widget.Button") {
                            true
                        } else {
                            // The Start button may have text="" with desc="Start", and its child View
                            // has text="Start" but is not clickable. Check if the parent is a clickable Button.
                            val parent = node.parent
                            val parentMatch = parent != null && parent.isClickable &&
                                parent.className == "android.widget.Button" &&
                                parent.contentDescription?.toString()?.equals("Start") == true
                            parent?.recycle()
                            parentMatch
                        }
                    }
                    startNodes.forEach { it.recycle() }

                    if (hasStartButton) {
                        directionsScreenFound = true
                        Log.d(TAG, "Found Start button on attempt $attempt")
                        break
                    }

                    // If no Start button, check for mode tabs using built-in search (no depth limit)
                    val modeTabNodes = modeRootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/directions_mode_tabs")
                    val hasModeTabs = modeTabNodes != null && modeTabNodes.isNotEmpty()
                    modeTabNodes?.forEach { it.recycle() }

                    if (hasModeTabs) {
                        // Check what mode we're in using built-in search (no depth limit)
                        var currentMode: String? = null
                        val allModeCheckNodes = modeRootNode.findAccessibilityNodeInfosByText("mode")
                        for (modeNode in allModeCheckNodes) {
                            val desc = modeNode.contentDescription?.toString()
                            if (modeNode.isSelected && desc != null && desc.contains("mode", ignoreCase = true)) {
                                currentMode = desc.substringBefore(":").trim()
                                break
                            }
                        }
                        allModeCheckNodes.forEach { it.recycle() }
                        val isTransitMode = currentMode?.contains("Transit", ignoreCase = true) == true

                        if (isTransitMode) {
                            // Transit mode genuinely doesn't have Start button until route is selected
                            directionsScreenFound = true
                            Log.d(TAG, "Found directions mode tabs on attempt $attempt (transit mode - no Start button expected)")
                            break
                        } else {
                            // Non-transit mode - Start button should exist, keep waiting for it to render
                            Log.d(TAG, "Found directions mode tabs on attempt $attempt but no Start button yet (mode: $currentMode), waiting...")
                            // Don't break - continue the loop to wait for Start button
                            modeRootNode.recycle()
                            modeRootNode = null
                        }
                    }

                    modeRootNode?.recycle()
                    modeRootNode = null
                }
                Log.d(TAG, "Directions screen indicators not found, waiting... (attempt $attempt/5)")
                delay(1000)
            }

            if (!directionsScreenFound || modeRootNode == null) {
                Log.w(TAG, "Directions screen did not fully load - neither Start button nor mode tabs found after 5 attempts")
                // UI dump for Start button not found
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_directions_screen_not_found", "Neither Start button nor mode tabs found after 5 attempts on directions screen")
                    dumpRoot.recycle()
                }
                modeRootNode?.recycle()
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Directions screen did not fully load - Start button not found"
                )
            }

            // Select transportation mode if needed and click Start
            val success = selectTransportModeAndStart(modeRootNode, mode, accessibilityService)
            modeRootNode.recycle()

            if (!success) {
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Could not select transport mode or click Start button"
                )
            }

            return MapsActionResult(
                success = true,
                action = "get_directions",
                mode = mode
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error getting Google Maps directions", e)
            logScreenAgentError(
                reason = "gmaps_directions_error",
                errorMessage = "Exception getting Google Maps directions: ${e.message}",
                packageName = "com.google.android.apps.maps"
            )
            return MapsActionResult(
                success = false,
                action = "get_directions",
                mode = mode,
                error = "Error getting directions: ${e.message}"
            )
        }
    }

    suspend fun recenterGoogleMaps(): MapsActionResult {
        Log.d(TAG, "Attempting to recenter Google Maps")

        try {
            // Auto-launch Google Maps if not already open
            val launchResult = launchApp("Maps", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch Google Maps: ${launchResult.error}")
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps launched successfully")
            delay(1000) // Wait for Google Maps to fully load

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError("accessibility_unavailable", "Accessibility service not enabled", "com.google.android.apps.maps")
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Accessibility service not enabled"
                )
            }

            // Wait for Google Maps to be ready (max 3 seconds)
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.maps",
                maxWaitMs = 3000
            )

            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_app_not_ready", "Google Maps did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("gmaps_app_not_ready", "Google Maps did not become ready in time", "com.google.android.apps.maps")
                }
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                logScreenAgentError("root_node_null", "Could not get root node", "com.google.android.apps.maps")
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Could not get root node"
                )
            }

            // Find and click the Re-center button
            val recenterClicked = clickGoogleMapsRecenter(rootNode, accessibilityService)

            if (!recenterClicked) {
                dumpUIHierarchy(rootNode, "gmaps_recenter_button_not_found", "Could not find Re-center button in Google Maps")
                rootNode.recycle()
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Could not find Re-center button in Google Maps"
                )
            }
            rootNode.recycle()

            return MapsActionResult(
                success = true,
                action = "recenter"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error recentering Google Maps", e)
            return MapsActionResult(
                success = false,
                action = "recenter",
                error = "Error recentering map: ${e.message}"
            )
        }
    }

    suspend fun fullscreenGoogleMaps(): MapsActionResult {
        Log.d(TAG, "Attempting to fullscreen Google Maps")

        try {
            // Auto-launch Google Maps to bring it to foreground
            val launchResult = launchApp("Maps", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch Google Maps: ${launchResult.error}")
                return MapsActionResult(
                    success = false,
                    action = "fullscreen",
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps launched/foregrounded successfully")
            return MapsActionResult(
                success = true,
                action = "fullscreen"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fullscreening Google Maps", e)
            return MapsActionResult(
                success = false,
                action = "fullscreen",
                error = "Error fullscreening map: ${e.message}"
            )
        }
    }

    suspend fun selectLocationFromList(position: Int? = null, fragment: String? = null, skipSponsored: Boolean = false): MapsActionResult {
        val selectionDesc = if (position != null) "position $position" else "fragment '$fragment'"
        Log.d(TAG, "Attempting to select location from list: $selectionDesc, skipSponsored=$skipSponsored")

        try {
            // Auto-launch Google Maps to bring it to foreground
            val launchResult = launchApp("Maps", enableOverlay = true)
            if (!launchResult.success) {
                Log.e(TAG, "Failed to launch Google Maps: ${launchResult.error}")
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps launched/foregrounded successfully")

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError("accessibility_unavailable", "Accessibility service not enabled", "com.google.android.apps.maps")
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Accessibility service not enabled"
                )
            }

            // Wait for Google Maps to be ready (max 3 seconds)
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.maps",
                maxWaitMs = 3000
            )

            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_app_not_ready", "Google Maps did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("gmaps_app_not_ready", "Google Maps did not become ready in time", "com.google.android.apps.maps")
                }
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                logScreenAgentError("root_node_null", "Could not get root node", "com.google.android.apps.maps")
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Could not get root node"
                )
            }

            // Find and click the location from the list
            val locationClicked = clickLocationFromList(rootNode, position, fragment, accessibilityService, skipSponsored)
            rootNode.recycle()

            if (!locationClicked) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_location_select_failed", "Could not find or click location: $selectionDesc")
                    dumpRoot.recycle()
                }
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Could not find or click location: $selectionDesc" + if (skipSponsored) " (non-sponsored)" else ""
                )
            }

            // Wait for location to load
            delay(1000)

            // Extract the actual address from the place details
            val finalRootNode = accessibilityService.getCurrentRootNode()
            val actualAddress = if (finalRootNode != null) {
                val extracted = extractAddressFromMaps(finalRootNode)
                finalRootNode.recycle()
                extracted ?: selectionDesc // Fallback to selection description if extraction fails
            } else {
                selectionDesc
            }

            return MapsActionResult(
                success = true,
                action = "select_location",
                location = actualAddress
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error selecting location from list", e)
            return MapsActionResult(
                success = false,
                action = "select_location",
                error = "Error selecting location: ${e.message}"
            )
        }
    }

    suspend fun searchGoogleMapsPhrase(searchPhrase: String): MapsActionResult {
        Log.d(TAG, "Attempting to search Google Maps with phrase: $searchPhrase")
        trackAction("searchGoogleMapsPhrase: $searchPhrase")

        try {
            // Launch Google Maps with geo: intent directly to search results
            val launchResult = launchGoogleMapsSearch(searchPhrase)
            if (!launchResult.success) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Failed to open Google Maps: ${launchResult.error}"
                )
            }
            Log.i(TAG, "Google Maps geo: search launched for phrase: $searchPhrase")

            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                logScreenAgentError("accessibility_unavailable", "Accessibility service not enabled", "com.google.android.apps.maps")
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Accessibility service not enabled"
                )
            }

            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.google.android.apps.maps",
                maxWaitMs = 5000
            )
            if (!appReady) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_app_not_ready", "Google Maps did not become ready in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("gmaps_app_not_ready", "Google Maps did not become ready in time", "com.google.android.apps.maps")
                }
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Google Maps did not become ready in time"
                )
            }

            // Poll for search results to load
            val resultsLoaded = waitForCondition(maxWaitMs = 8000) {
                val node = accessibilityService.getCurrentRootNode()
                if (node != null) {
                    // Dismiss "Exit navigation?" dialog if present
                    dismissExitNavigationDialog(node)
                    val state = detectGoogleMapsScreenState(node)
                    node.recycle()
                    state == GoogleMapsScreenState.SEARCH_RESULTS_LIST || state == GoogleMapsScreenState.LOCATION_DETAILS
                } else false
            }
            if (!resultsLoaded) {
                val dumpRoot = accessibilityService.getCurrentRootNode()
                if (dumpRoot != null) {
                    dumpUIHierarchy(dumpRoot, "gmaps_search_results_timeout", "Search results did not load in time")
                    dumpRoot.recycle()
                } else {
                    logScreenAgentError("gmaps_search_results_timeout", "Search results did not load in time", "com.google.android.apps.maps")
                }
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Search results did not load in time"
                )
            }

            return MapsActionResult(
                success = true,
                action = "search_phrase",
                location = searchPhrase
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error searching Google Maps with phrase", e)
            return MapsActionResult(
                success = false,
                action = "search_phrase",
                location = searchPhrase,
                error = "Error searching with phrase: ${e.message}"
            )
        }
    }

    // ========== Google Maps Helper Functions ==========

    /**
     * Enum representing the different screen states in Google Maps.
     * Used for automatic detection of current UI state.
     */
    private enum class GoogleMapsScreenState {
        LOCATION_DETAILS,      // place_page_view exists - showing a location with Directions button
        DIRECTIONS_INPUT,      // directions_mode_tabs exists - directions/choose destination screen
        ACTIVE_NAVIGATION,     // Turn-by-turn navigation is active
        SEARCH_RESULTS_LIST,   // search_list_layout exists - showing search results list
        FILTERS_SCREEN,        // Filters/sort screen is open (has "Sort by", "Clear", "Apply")
        TRANSIT_ROUTE_DETAIL,  // Transit route detail screen (trip steps + "Start glanceable directions" button)
        UNKNOWN
    }

    /**
     * Detects the current Google Maps screen state by checking for unique UI elements.
     */
    private fun detectGoogleMapsScreenState(rootNode: AccessibilityNodeInfo): GoogleMapsScreenState {
        // Check for place_page_view (location details page with Directions button)
        val placePageNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/place_page_view")
        if (placePageNodes != null && placePageNodes.isNotEmpty()) {
            placePageNodes.forEach { it.recycle() }
            Log.d(TAG, "Detected Google Maps screen state: LOCATION_DETAILS (place_page_view found)")
            return GoogleMapsScreenState.LOCATION_DETAILS
        }

        // Check for directions_mode_tabs (directions input screen)
        val directionsTabsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/directions_mode_tabs")
        if (directionsTabsNodes != null && directionsTabsNodes.isNotEmpty()) {
            directionsTabsNodes.forEach { it.recycle() }
            Log.d(TAG, "Detected Google Maps screen state: DIRECTIONS_INPUT (directions_mode_tabs found)")
            return GoogleMapsScreenState.DIRECTIONS_INPUT
        }

        // Check for active navigation by looking for nav_container
        // This is the main navigation container that only exists during turn-by-turn navigation
        val navContainerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/nav_container")
        if (navContainerNodes != null && navContainerNodes.isNotEmpty()) {
            navContainerNodes.forEach { it.recycle() }
            Log.d(TAG, "Detected Google Maps screen state: ACTIVE_NAVIGATION (nav_container found)")
            return GoogleMapsScreenState.ACTIVE_NAVIGATION
        }

        // Check for transit route detail screen
        // NOTE: trip_details_footer_layout also exists on the normal directions screen,
        // but that case is already caught above by the directions_mode_tabs check (DIRECTIONS_INPUT).
        // If we reach here, it means we have the footer but no mode tabs = transit route detail.
        val tripDetailsFooterNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/trip_details_footer_layout")
        if (tripDetailsFooterNodes != null && tripDetailsFooterNodes.isNotEmpty()) {
            tripDetailsFooterNodes.forEach { it.recycle() }
            Log.d(TAG, "Detected Google Maps screen state: TRANSIT_ROUTE_DETAIL (trip_details_footer_layout found, no directions_mode_tabs)")
            return GoogleMapsScreenState.TRANSIT_ROUTE_DETAIL
        }

        // Check for search results list by looking for search_list_layout
        val searchListNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_list_layout")
        if (searchListNodes != null && searchListNodes.isNotEmpty()) {
            searchListNodes.forEach { it.recycle() }
            Log.d(TAG, "Detected Google Maps screen state: SEARCH_RESULTS_LIST (search_list_layout found)")
            return GoogleMapsScreenState.SEARCH_RESULTS_LIST
        }

        // Check for Filters screen by looking for "Sort by" description and Clear/Apply buttons
        // The Filters screen has a unique combination of these elements
        val filtersIndicators = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Sort by", filtersIndicators)
        if (filtersIndicators.isNotEmpty()) {
            // Also verify there's a "Clear" or "Apply" button
            val clearNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDescription(rootNode, "Clear", clearNodes)
            val applyNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDescription(rootNode, "Apply", applyNodes)

            if (clearNodes.isNotEmpty() || applyNodes.isNotEmpty()) {
                Log.d(TAG, "Detected Google Maps screen state: FILTERS_SCREEN (Sort by + Clear/Apply found)")
                return GoogleMapsScreenState.FILTERS_SCREEN
            }
        }

        // Note: "Arriving at" screen (post-navigation arrival) is intentionally treated as UNKNOWN
        // because pressing back from it goes to the main search page, not a useful state.
        // When UNKNOWN, the code will search for the address using the search bar.

        Log.d(TAG, "Detected Google Maps screen state: UNKNOWN")
        return GoogleMapsScreenState.UNKNOWN
    }

    private fun extractAddressFromMaps(rootNode: AccessibilityNodeInfo): String? {
        // Try to extract the place name and address from the place details screen
        // Look for the title (place name) and subtitle (address)

        // First try to find the title TextView (place name)
        val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/title")
        val placeName = if (titleNodes != null && titleNodes.isNotEmpty()) {
            val name = titleNodes[0].text?.toString()
            titleNodes.forEach { it.recycle() }
            name
        } else {
            null
        }

        // Then try to find the subtitle TextView (address)
        val subtitleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/subtitle")
        val address = if (subtitleNodes != null && subtitleNodes.isNotEmpty()) {
            val addr = subtitleNodes[0].text?.toString()
            subtitleNodes.forEach { it.recycle() }
            addr
        } else {
            null
        }

        // Combine place name and address
        return when {
            placeName != null && address != null -> "$placeName, $address"
            address != null -> address
            placeName != null -> placeName
            else -> {
                Log.w(TAG, "Could not extract address from Maps place details")
                null
            }
        }
    }

    /**
     * Combined function to click and type into Google Maps search box in one operation.
     * This avoids the race condition of clicking, recycling, then trying to find the node again.
     */
    private fun clickAndTypeGoogleMapsSearch(
        rootNode: AccessibilityNodeInfo,
        query: String
    ): Boolean {
        // Try to find search box by resource ID - check both possible IDs
        val resourceIds = listOf(
            "com.google.android.apps.maps:id/search_omnibox_text_box",   // Initial state
            "com.google.android.apps.maps:id/search_omnibox_edit_text"   // When focused
        )

        for (resourceId in resourceIds) {
            val searchBoxNodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
            if (searchBoxNodes != null && searchBoxNodes.isNotEmpty()) {
                for (node in searchBoxNodes) {
                    // Click to focus (if clickable)
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }

                    // Set the text
                    val arguments = Bundle()
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                    val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                    if (success) {
                        Log.d(TAG, "Clicked and entered search query in Google Maps via $resourceId")
                        searchBoxNodes.forEach { it.recycle() }
                        return true
                    }
                }
                searchBoxNodes.forEach { it.recycle() }
            }
        }

        // Fallback: try finding by "Search here" text
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Search here", textNodes)
        for (node in textNodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                Log.d(TAG, "Clicked and entered search query via text match")
                textNodes.forEach { it.recycle() }
                return true
            }
        }
        textNodes.forEach { it.recycle() }

        Log.w(TAG, "Could not find Google Maps search box to click and type")
        return false
    }

    /**
     * Press Enter to submit a Google Maps search query.
     * This submits the search exactly as typed, getting location-aware results near the user.
     * Use this instead of clicking autocomplete suggestions for address searches.
     */
    private fun pressEnterToSubmitSearch(rootNode: AccessibilityNodeInfo): Boolean {
        Log.d(TAG, "Pressing Enter to submit Google Maps search")
        val imeActionPerformed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val searchFields = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_edit_text")
                ?: rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_text_box")
            val result = searchFields?.firstOrNull()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            searchFields?.forEach { it.recycle() }
            result
        } else {
            try {
                val process = Runtime.getRuntime().exec("input keyevent 66")
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                false
            }
        }

        if (imeActionPerformed) {
            Log.d(TAG, "Successfully submitted search via Enter key")
        } else {
            Log.w(TAG, "Failed to submit search via Enter key")
        }
        return imeActionPerformed
    }

    private fun clickMatchingSuggestion(rootNode: AccessibilityNodeInfo, query: String, accessibilityService: WhizAccessibilityService): Pair<Boolean, Boolean> {
        // Look for the typed_suggest_container RecyclerView
        val suggestContainerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/typed_suggest_container")

        if (suggestContainerNodes != null && suggestContainerNodes.isNotEmpty()) {
            for (containerNode in suggestContainerNodes) {
                if (containerNode.childCount > 0) {
                    // First pass: try to find exact match (case-insensitive)
                    for (i in 0 until containerNode.childCount) {
                        val child = containerNode.getChild(i)
                        if (child != null && child.isClickable) {
                            val suggestionText = getSuggestionText(child)
                            if (suggestionText != null && suggestionText.equals(query, ignoreCase = true)) {
                                Log.d(TAG, "Found exact matching suggestion: '$suggestionText'")
                                val hasSeeLocations = checkForSeeLocations(child)
                                val clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                child.recycle()
                                containerNode.recycle()
                                suggestContainerNodes.forEach { it.recycle() }
                                if (clicked) {
                                    Log.d(TAG, "Clicked matching suggestion${if (hasSeeLocations) " (See locations)" else ""}")
                                    return Pair(true, hasSeeLocations)
                                }
                            }
                            child.recycle()
                        }
                    }

                    // No exact match found - don't click first suggestion as it may be different
                    // (e.g., "Trader Joe's near me open now" instead of "Trader Joe's near me")
                    Log.d(TAG, "No exact match found in suggestions, will press Enter to submit search")
                }
                containerNode.recycle()
            }
        }

        // No exact match found - press Enter to submit the search exactly as typed
        Log.d(TAG, "No clickable suggestions found, trying to press Enter to submit search")
        val imeActionPerformed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Find the search field and perform IME action
            val searchFields = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_edit_text")
                ?: rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_text_box")
            val result = searchFields?.firstOrNull()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
            searchFields?.forEach { it.recycle() }
            result
        } else {
            try {
                val process = Runtime.getRuntime().exec("input keyevent 66")
                process.waitFor() == 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                false
            }
        }

        if (imeActionPerformed) {
            Log.d(TAG, "Successfully submitted search via Enter key")
            return Pair(true, false)
        }

        Log.w(TAG, "Could not find or click any suggestions, and Enter key failed")
        return Pair(false, false)
    }

    private fun getSuggestionText(node: AccessibilityNodeInfo): String? {
        // Try to get text from TextViews in the suggestion node
        if (node.className == "android.widget.TextView" && node.text != null) {
            return node.text.toString()
        }

        // Search children for TextView
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val text = getSuggestionText(child)
                child.recycle()
                if (text != null) {
                    return text
                }
            }
        }

        return null
    }

    private fun checkForSeeLocations(node: AccessibilityNodeInfo): Boolean {
        // Check if this node or its children contain "See locations" text
        if (node.text?.toString()?.contains("See locations", ignoreCase = true) == true) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = checkForSeeLocations(child)
                child.recycle()
                if (found) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Scroll the Google Maps search results list down to reveal more results.
     * Used when all visible results are sponsored ads.
     * @return true if scroll was performed successfully
     */
    private suspend fun scrollGoogleMapsResultsList(): Boolean {
        val accessibilityService = WhizAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.w(TAG, "Accessibility service not available for scrolling")
            return false
        }

        // Get screen dimensions for gesture coordinates
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Calculate swipe coordinates (swipe UP from bottom to top to scroll DOWN and reveal more results)
        // Use 50% of screen height to scroll enough to reveal results further down the list
        val centerX = screenWidth / 2f
        val startY = screenHeight * 0.75f  // Start at 75% down the screen
        val endY = screenHeight * 0.25f    // End at 25% down the screen (swipe upward to scroll down)

        Log.d(TAG, "Performing scroll gesture on Maps results list (swipe from $startY to $endY)")
        val scrolled = accessibilityService.performScrollGesture(
            centerX, startY, centerX, endY, duration = 300
        )

        if (scrolled) {
            Log.d(TAG, "Successfully scrolled Maps results list")
        } else {
            Log.w(TAG, "Failed to scroll Maps results list")
        }

        return scrolled
    }

    private fun clickGoogleMapsDirections(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        // Use Android's built-in search (no depth limit) - the Directions button can be ~22 levels deep
        val freshRoot = accessibilityService.getCurrentRootNode()
        if (freshRoot == null) {
            Log.w(TAG, "Could not get fresh root node for Directions button search")
            return false
        }

        val directionNodes = freshRoot.findAccessibilityNodeInfosByText("Directions")
        Log.d(TAG, "Found ${directionNodes.size} nodes matching 'Directions' via built-in search")

        for (node in directionNodes) {
            val desc = node.contentDescription?.toString() ?: ""
            // Match the Directions button (content-desc starts with "Directions to") or text is "Directions"
            if (desc.startsWith("Directions to", ignoreCase = true) || node.text?.toString() == "Directions") {
                // If the node itself is clickable, click it directly
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Clicked Directions button directly")
                        directionNodes.forEach { it.recycle() }
                        freshRoot.recycle()
                        return true
                    }
                }
                // Otherwise find clickable parent
                val clickableNode = findClickableParent(node)
                if (clickableNode != null) {
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickableNode.recycle()
                    if (clicked) {
                        Log.d(TAG, "Clicked Directions button via parent")
                        directionNodes.forEach { it.recycle() }
                        freshRoot.recycle()
                        return true
                    }
                }
            }
        }
        directionNodes.forEach { it.recycle() }
        freshRoot.recycle()

        Log.w(TAG, "Could not find Directions button")
        return false
    }

    private fun selectTransportModeAndStart(rootNode: AccessibilityNodeInfo, mode: String?, accessibilityService: WhizAccessibilityService): Boolean {
        // If no mode specified, just click Start without changing the mode
        if (mode == null) {
            Log.d(TAG, "No mode specified, using currently selected mode")
            // Skip to clicking Start button at the end
        } else {
            // Map mode to content description pattern
            val modeText = when (mode.lowercase()) {
                "drive" -> "Driving mode"
                "walk" -> "Walking mode"
                "bike" -> "Bicycling mode"
                "transit" -> "Transit mode"
                else -> {
                    Log.w(TAG, "Unknown mode: $mode, using currently selected mode")
                    null
                }
            }

            // Only change mode if we have a valid modeText
            if (modeText != null) {
                // Get a fresh root node from the accessibility service (the polling loop root may be stale)
                val freshRoot = accessibilityService.getCurrentRootNode()
                if (freshRoot == null) {
                    Log.w(TAG, "Could not get fresh root node for transport mode selection")
                } else {
                    // Detect currently selected mode using built-in search (no depth limit)
                    var currentlySelected: String? = null
                    val allModeNodes = freshRoot.findAccessibilityNodeInfosByText("mode")
                    for (modeNode in allModeNodes) {
                        val desc = modeNode.contentDescription?.toString()
                        if (modeNode.isSelected && desc != null && desc.contains("mode", ignoreCase = true)) {
                            currentlySelected = desc.substringBefore(":").trim()
                            break
                        }
                    }
                    allModeNodes.forEach { it.recycle() }
                    Log.d(TAG, "Currently selected transport mode: $currentlySelected, desired: $modeText")

                    // If the mode is not already selected, click it
                    if (currentlySelected != modeText) {
                        // Use Android's built-in findAccessibilityNodeInfosByText (searches both text and content-description, no depth limit)
                        val modeNodes = freshRoot.findAccessibilityNodeInfosByText(modeText)
                        Log.d(TAG, "Found ${modeNodes.size} nodes matching '$modeText' via built-in search")

                        var modeChanged = false
                        for (node in modeNodes) {
                            val desc = node.contentDescription?.toString()
                            if (desc != null && desc.contains(modeText, ignoreCase = true)) {
                                val clickableNode = findClickableParent(node)
                                if (clickableNode != null) {
                                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    clickableNode.recycle()
                                    if (clicked) {
                                        Log.d(TAG, "Selected transport mode: $modeText")
                                        modeChanged = true
                                        break
                                    }
                                }
                            }
                        }
                        modeNodes.forEach { it.recycle() }

                        if (!modeChanged && currentlySelected != modeText) {
                            Log.w(TAG, "Could not change transport mode to: $modeText")
                            // Continue anyway - might already be on correct mode
                        }

                        // Wait for mode change to complete
                        if (modeChanged) {
                            Thread.sleep(500)
                        }
                    }
                    freshRoot.recycle()
                }
            }
        }

        // Now click the Start button using built-in search (no depth limit)
        val freshStartRoot = accessibilityService.getCurrentRootNode()
        if (freshStartRoot != null) {
            val startNodes = freshStartRoot.findAccessibilityNodeInfosByText("Start")

            for (node in startNodes) {
                if (node.isClickable && node.className == "android.widget.Button") {
                    // Direct match - clickable Button with "Start" text
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (clicked) {
                        Log.d(TAG, "Clicked Start button")
                        startNodes.forEach { it.recycle() }
                        freshStartRoot.recycle()

                        // Check for and dismiss "Start this trip?" dialog (appears when already on an active trip)
                        dismissStartThisTripDialog(accessibilityService)

                        // Check for and dismiss welcome popup (no wait needed - detect by text)
                        dismissGoogleMapsWelcomePopup(accessibilityService)

                        return true
                    }
                } else if (node.text?.toString() == "Start" || node.contentDescription?.toString() == "Start") {
                    // Child View with text="Start" but not clickable - find clickable parent Button
                    val clickableParent = findClickableParent(node)
                    if (clickableParent != null && clickableParent.contentDescription?.toString() == "Start") {
                        val clicked = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableParent.recycle()
                        if (clicked) {
                            Log.d(TAG, "Clicked Start button via clickable parent")
                            node.recycle()
                            startNodes.forEach { it.recycle() }
                            freshStartRoot.recycle()

                            dismissStartThisTripDialog(accessibilityService)
                            dismissGoogleMapsWelcomePopup(accessibilityService)

                            return true
                        }
                    } else {
                        clickableParent?.recycle()
                    }
                }
                node.recycle()
            }
            freshStartRoot.recycle()
        }

        Log.w(TAG, "Could not find or click Start button")

        // For transit mode, we need to click a route first, then find the Start button
        if (mode?.lowercase() == "transit") {
            Log.d(TAG, "Transit mode: looking for route options to click first")

            // Poll for transit route cards using Android's built-in search (no depth limit)
            var tripCardNodeList = emptyList<AccessibilityNodeInfo>()
            var transitRoot: AccessibilityNodeInfo? = null
            for (transitAttempt in 1..25) {
                Thread.sleep(200)
                transitRoot?.recycle()
                transitRoot = accessibilityService.getCurrentRootNode()
                if (transitRoot == null) {
                    Log.w(TAG, "Transit mode: could not get fresh root node on attempt $transitAttempt")
                    continue
                }
                tripCardNodeList = transitRoot.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/trip_card_main_header")
                Log.d(TAG, "Transit mode: found ${tripCardNodeList.size} trip card nodes on attempt $transitAttempt")
                if (tripCardNodeList.isNotEmpty()) break
            }

            if (transitRoot == null) {
                Log.w(TAG, "Transit mode: could not get fresh root node after all attempts")
                return false
            }

            if (tripCardNodeList.isNotEmpty()) {
                val tripCardNode = tripCardNodeList[0]
                val clickableRoute = findClickableParent(tripCardNode)
                if (clickableRoute != null) {
                    clickableRoute.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickableRoute.recycle()
                    Log.d(TAG, "Clicked first transit route option")

                    // Wait for detail screen to load
                    Thread.sleep(500)

                    // Get fresh root node and look for "Start glanceable directions"
                    val newRoot = accessibilityService.getCurrentRootNode()
                    if (newRoot != null) {
                        val glanceableNodes = mutableListOf<AccessibilityNodeInfo>()
                        findNodesByContentDesc(newRoot, "Start glanceable directions", glanceableNodes)
                        Log.d(TAG, "Transit mode: found ${glanceableNodes.size} glanceable directions buttons")

                        for (node in glanceableNodes) {
                            val clickableStart = findClickableParent(node)
                            if (clickableStart != null) {
                                val clicked = clickableStart.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                clickableStart.recycle()
                                if (clicked) {
                                    Log.d(TAG, "Clicked Start glanceable directions button")

                                    // Check for and dismiss "Start this trip?" dialog (appears when already on an active trip)
                                    dismissStartThisTripDialog(accessibilityService)

                                    glanceableNodes.forEach { it.recycle() }
                                    newRoot.recycle()
                                    tripCardNodeList.forEach { it.recycle() }
                                    transitRoot.recycle()
                                    return true
                                }
                            }
                        }
                        glanceableNodes.forEach { it.recycle() }
                        newRoot.recycle()
                    }
                } else {
                    Log.w(TAG, "Transit mode: could not find clickable parent for trip card")
                }
            } else {
                Log.w(TAG, "Transit mode: no trip cards found in UI")
            }
            tripCardNodeList.forEach { it.recycle() }
            transitRoot.recycle()
        }

        return false
    }

    private fun dismissGoogleMapsWelcomePopup(accessibilityService: WhizAccessibilityService) {
        try {
            val rootNode = accessibilityService.getCurrentRootNode() ?: return

            // First check if welcome popup exists by looking for the title text
            val welcomeNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, "Welcome to Google Maps navigation", welcomeNodes)

            if (welcomeNodes.isEmpty()) {
                Log.d(TAG, "No welcome popup detected")
                rootNode.recycle()
                return
            }
            welcomeNodes.forEach { it.recycle() }

            // Found welcome popup, click OK to dismiss
            val okNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, "OK", okNodes)

            try {
                for (node in okNodes) {
                    val clickableNode = findClickableParent(node)
                    if (clickableNode != null) {
                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableNode.recycle()
                        if (clicked) {
                            Log.d(TAG, "Dismissed Google Maps welcome popup")
                            return
                        }
                    }
                }
            } finally {
                okNodes.forEach { it.recycle() }
                rootNode.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing welcome popup: ${e.message}")
        }
    }

    private fun dismissStartThisTripDialog(accessibilityService: WhizAccessibilityService) {
        try {
            // Brief delay to allow the dialog to appear
            Thread.sleep(500)

            val rootNode = accessibilityService.getCurrentRootNode() ?: return

            // Check if the "Start this trip?" dialog is present by looking for dialog_title
            val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/dialog_title")
            val hasStartTripDialog = titleNodes.any { it.text?.toString() == "Start this trip?" }
            titleNodes.forEach { it.recycle() }

            if (!hasStartTripDialog) {
                Log.d(TAG, "No 'Start this trip?' dialog detected")
                rootNode.recycle()
                return
            }

            Log.d(TAG, "Found 'Start this trip?' dialog, clicking 'Start trip' button")

            // Click the positive button ("Start trip")
            val positiveButtons = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/dialog_positive_button")
            try {
                for (button in positiveButtons) {
                    if (button.isClickable) {
                        val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.d(TAG, "Dismissed 'Start this trip?' dialog by clicking 'Start trip'")
                            return
                        }
                    }
                }
                Log.w(TAG, "Could not click 'Start trip' button in dialog")
            } finally {
                positiveButtons.forEach { it.recycle() }
                rootNode.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing 'Start this trip?' dialog: ${e.message}")
        }
    }

    private fun dismissExitNavigationDialog(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            val exitNavNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, "Exit navigation?", exitNavNodes)

            if (exitNavNodes.isEmpty()) {
                return false
            }

            Log.d(TAG, "Found 'Exit navigation?' dialog, clicking 'Yes' to dismiss")

            val yesNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(rootNode, "Yes", yesNodes)

            for (node in yesNodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Dismissed 'Exit navigation?' dialog")
                        return true
                    }
                }
                // If the node itself isn't clickable, try finding a clickable parent
                val clickableParent = findClickableParent(node)
                if (clickableParent != null) {
                    val clicked = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clickableParent.recycle()
                    if (clicked) {
                        Log.d(TAG, "Dismissed 'Exit navigation?' dialog via clickable parent")
                        return true
                    }
                }
            }

            Log.w(TAG, "Found 'Exit navigation?' dialog but could not click 'Yes'")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Error dismissing 'Exit navigation?' dialog: ${e.message}")
            return false
        }
    }

    private fun findSelectedTransportMode(rootNode: AccessibilityNodeInfo): String? {
        // Look for a node with selected="true" in the transport modes area
        return findSelectedTransportModeRecursive(rootNode)
    }

    private fun findSelectedTransportModeRecursive(node: AccessibilityNodeInfo): String? {
        if (node.isSelected && node.contentDescription != null) {
            val desc = node.contentDescription.toString()
            if (desc.contains("mode:", ignoreCase = true)) {
                // Extract the mode name (e.g., "Bicycling mode: 1 minute" -> "Bicycling mode")
                return desc.substringBefore(":").trim()
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val result = findSelectedTransportModeRecursive(child)
                child.recycle()
                if (result != null) {
                    return result
                }
            }
        }

        return null
    }

    private fun findNodesByContentDesc(node: AccessibilityNodeInfo, contentDesc: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.contentDescription?.toString()?.contains(contentDesc, ignoreCase = true) == true) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByContentDesc(child, contentDesc, results)
                // Don't recycle here - let caller handle recycling
            }
        }
    }

    private fun clickGoogleMapsRecenter(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        // Look for "Re-center" button by text
        val recenterNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Re-center", recenterNodes)

        for (node in recenterNodes) {
            // Find the clickable parent (FrameLayout)
            var clickableNode = node
            if (!node.isClickable && node.parent != null) {
                clickableNode = node.parent
            }

            if (clickableNode.isClickable) {
                val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked Re-center button")
                    recenterNodes.forEach { it.recycle() }
                    return true
                }
            }
            node.recycle()
        }

        Log.w(TAG, "Could not find Re-center button")
        return false
    }

    private fun clickLocationFromList(rootNode: AccessibilityNodeInfo, position: Int?, fragment: String?, accessibilityService: WhizAccessibilityService, skipSponsored: Boolean = false): Boolean {
        // Find the RecyclerView containing the location list
        // Try typed_suggest_container first (search results), then search_list_layout (other views)
        var listNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/typed_suggest_container")
        Log.d(TAG, "Looking for typed_suggest_container: found ${listNodes?.size ?: 0}")

        if (listNodes == null || listNodes.isEmpty()) {
            listNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_list_layout")
            Log.d(TAG, "Looking for search_list_layout: found ${listNodes?.size ?: 0}")
        }

        if (listNodes == null || listNodes.isEmpty()) {
            Log.w(TAG, "Could not find location list RecyclerView (tried typed_suggest_container and search_list_layout)")
            return false
        }

        val listNode = listNodes[0]
        Log.d(TAG, "Found list with ${listNode.childCount} children, skipSponsored=$skipSponsored")

        // Debug: Log details of first few children to understand list structure
        if (skipSponsored) {
            Log.d(TAG, "=== DEBUG: Analyzing first 5 children of list ===")
            for (debugIdx in 0 until minOf(listNode.childCount, 5)) {
                val debugChild = listNode.getChild(debugIdx)
                if (debugChild != null) {
                    val isSponsored = isResultSponsored(debugChild)
                    val isFilter = isFilterChipRow(debugChild)
                    Log.d(TAG, "DEBUG Child $debugIdx: class=${debugChild.className}, clickable=${debugChild.isClickable}, sponsored=$isSponsored, filterRow=$isFilter, desc=${debugChild.contentDescription?.toString()?.take(50)}")
                    debugChild.recycle()
                } else {
                    Log.d(TAG, "DEBUG Child $debugIdx: NULL")
                }
            }
            Log.d(TAG, "=== END DEBUG ===")
        }

        // Position takes precedence over fragment
        if (position != null) {
            if (skipSponsored) {
                // Find the Nth non-sponsored result (1-indexed position)
                var nonSponsoredCount = 0
                for (i in 0 until listNode.childCount) {
                    val child = listNode.getChild(i) ?: continue

                    if (isResultSponsored(child)) {
                        Log.d(TAG, "Child $i is sponsored, skipping")
                        child.recycle()
                        continue
                    }

                    // Skip filter chip rows (e.g., "Open now", "Top rated" filter buttons)
                    if (isFilterChipRow(child)) {
                        Log.d(TAG, "Child $i is a filter chip row, skipping")
                        child.recycle()
                        continue
                    }

                    nonSponsoredCount++
                    if (nonSponsoredCount == position) {
                        Log.d(TAG, "Found non-sponsored result at index $i (position $position)")
                        Log.d(TAG, "Child $i: isClickable=${child.isClickable}, className=${child.className}")

                        // Find a clickable node - prefer descendants (e.g., Button inside FrameLayout)
                        var clickableNode: AccessibilityNodeInfo? = if (child.isClickable) child else null

                        // First try to find a clickable descendant
                        if (clickableNode == null) {
                            Log.d(TAG, "Child not clickable, searching for clickable descendant")
                            clickableNode = findClickableDescendant(child)
                            if (clickableNode != null) {
                                Log.d(TAG, "Found clickable descendant: ${clickableNode.className}")
                            }
                        }

                        // If no descendant, try walking up the parent tree
                        if (clickableNode == null) {
                            var parent = child.parent
                            var depth = 0
                            while (parent != null && clickableNode == null && depth < 5) {
                                Log.d(TAG, "  Parent depth $depth: isClickable=${parent.isClickable}, className=${parent.className}")
                                if (parent.isClickable) {
                                    clickableNode = parent
                                } else {
                                    parent = parent.parent
                                }
                                depth++
                            }
                        }

                        // If still nothing, try clicking the child directly anyway
                        if (clickableNode == null) {
                            Log.w(TAG, "No clickable node found, trying click on child directly")
                            clickableNode = child
                        }

                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        child.recycle()
                        listNodes.forEach { it.recycle() }

                        if (clicked) {
                            Log.d(TAG, "Clicked non-sponsored location at position $position (actual index $i)")
                            return true
                        }
                        return false
                    }
                    child.recycle()
                }

                // If we get here, we didn't find enough non-sponsored results
                Log.w(TAG, "Could not find non-sponsored result at position $position (found $nonSponsoredCount non-sponsored)")
                listNodes.forEach { it.recycle() }
                return false
            } else {
                // Original behavior: Select by position (1-indexed, convert to 0-indexed)
                val targetIndex = position - 1

                if (targetIndex >= 0 && targetIndex < listNode.childCount) {
                    val child = listNode.getChild(targetIndex)
                    if (child != null) {
                        Log.d(TAG, "Child at index $targetIndex: isClickable=${child.isClickable}, className=${child.className}")

                        // Find a clickable node - prefer descendants (e.g., Button inside FrameLayout)
                        var clickableNode: AccessibilityNodeInfo? = if (child.isClickable) child else null

                        // First try to find a clickable descendant
                        if (clickableNode == null) {
                            Log.d(TAG, "Child not clickable, searching for clickable descendant")
                            clickableNode = findClickableDescendant(child)
                            if (clickableNode != null) {
                                Log.d(TAG, "Found clickable descendant: ${clickableNode.className}")
                            }
                        }

                        // If no descendant, try walking up the parent tree
                        if (clickableNode == null) {
                            var parent = child.parent
                            var depth = 0
                            while (parent != null && clickableNode == null && depth < 5) {
                                Log.d(TAG, "  Parent depth $depth: isClickable=${parent.isClickable}, className=${parent.className}")
                                if (parent.isClickable) {
                                    clickableNode = parent
                                } else {
                                    parent = parent.parent
                                }
                                depth++
                            }
                        }

                        // If still nothing, try clicking the child directly anyway
                        if (clickableNode == null) {
                            Log.w(TAG, "No clickable node found, trying click on child directly")
                            clickableNode = child
                        }

                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        child.recycle()
                        listNodes.forEach { it.recycle() }

                        if (clicked) {
                            Log.d(TAG, "Clicked location at position $position (index $targetIndex)")
                            return true
                        }
                    }
                } else {
                    Log.w(TAG, "Invalid position $position (list has ${listNode.childCount} items)")
                    listNodes.forEach { it.recycle() }
                    return false
                }
            }
        } else if (fragment != null) {
            // Select by fragment match
            Log.d(TAG, "Searching for fragment '$fragment' in ${listNode.childCount} children")
            for (i in 0 until listNode.childCount) {
                val child = listNode.getChild(i)
                if (child != null) {
                    // Check if this item contains the fragment text
                    val found = containsText(child, fragment)
                    if (found) {
                        Log.d(TAG, "Child $i contains fragment '$fragment', attempting click")
                        Log.d(TAG, "Child $i: isClickable=${child.isClickable}, className=${child.className}, bounds=${getBoundsString(child)}")

                        // Try to find a clickable node - start with the child and walk up the tree
                        var clickableNode: AccessibilityNodeInfo? = if (child.isClickable) child else null

                        // Walk up the parent tree to find a clickable ancestor
                        if (clickableNode == null) {
                            var parent = child.parent
                            var depth = 0
                            while (parent != null && clickableNode == null && depth < 5) {
                                Log.d(TAG, "  Parent depth $depth: isClickable=${parent.isClickable}, className=${parent.className}")
                                if (parent.isClickable) {
                                    clickableNode = parent
                                } else {
                                    parent = parent.parent
                                }
                                depth++
                            }
                        }

                        // If still no clickable found, search for clickable descendant
                        if (clickableNode == null) {
                            Log.d(TAG, "No clickable ancestor found, searching for clickable descendant")
                            clickableNode = findClickableDescendant(child)
                            if (clickableNode != null) {
                                Log.d(TAG, "Found clickable descendant: ${clickableNode.className}")
                            }
                        }

                        // If still nothing, try clicking the child directly anyway
                        if (clickableNode == null) {
                            Log.w(TAG, "No clickable node found, trying click on child directly")
                            clickableNode = child
                        }

                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        child.recycle()
                        listNodes.forEach { it.recycle() }

                        if (clicked) {
                            Log.d(TAG, "Clicked location matching fragment '$fragment'")
                            return true
                        } else {
                            Log.w(TAG, "Click action returned false for fragment '$fragment'")
                        }
                        return false
                    }
                    child.recycle()
                }
            }

            Log.w(TAG, "Could not find location matching fragment '$fragment'")
            listNodes.forEach { it.recycle() }
            return false
        }

        listNodes.forEach { it.recycle() }
        return false
    }

    private fun getBoundsString(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
    }

    /**
     * Find a clickable descendant node within the given node tree.
     * Returns the first clickable node found, or null if none found.
     */
    private fun findClickableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable) {
                return child
            }
            val descendant = findClickableDescendant(child)
            if (descendant != null) {
                return descendant
            }
        }
        return null
    }

    /**
     * Check if a search result node is a sponsored/ad result.
     * Sponsored results in Google Maps have an "About this ad" button.
     * IMPORTANT: This function does NOT recycle any nodes - caller is responsible for recycling.
     */
    private fun isResultSponsored(node: AccessibilityNodeInfo): Boolean {
        // Check if this node has "About this ad" content description
        if (node.contentDescription?.toString()?.equals("About this ad", ignoreCase = true) == true) {
            return true
        }

        // Check all descendants for "About this ad"
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isResultSponsored(child)) {
                // Do NOT recycle child here - keep node tree intact
                return true
            }
        }

        return false
    }

    /**
     * Check if a node looks like a filter chip row rather than a search result.
     * Filter chip rows have text like "Open now", "Top rated", "Filters" but no "Directions" button.
     * In older Google Maps versions, real search results have a "Directions" button inside them.
     * In newer Google Maps versions (Compose-based UI), the "Directions" button may not appear in
     * the accessibility tree, so we rely on filter indicator text to detect filter chip rows.
     * IMPORTANT: This function does NOT recycle any nodes - caller is responsible for recycling.
     */
    private fun isFilterChipRow(node: AccessibilityNodeInfo): Boolean {
        // In older Google Maps versions, a real search result has a "Directions" button inside.
        // Fast-path: if Directions button is found, definitely a real search result.
        val directionsNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByContentDescription(node, "Directions", directionsNodes)

        if (directionsNodes.isNotEmpty()) {
            // Has Directions button - this is a real search result, not a filter chip row
            Log.d(TAG, "Node has Directions button - is a search result")
            return false
        }

        // Check for filter-related text that indicates this is a filter chip row
        val filterIndicators = listOf("Open now", "Top rated", "Filters", "Sort by", "Price", "Rating")
        for (indicator in filterIndicators) {
            val indicatorNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByText(node, indicator, indicatorNodes)
            if (indicatorNodes.isNotEmpty()) {
                Log.d(TAG, "Node contains filter indicator '$indicator' - is a filter chip row")
                return true
            }
        }

        // Also check content descriptions for filter indicators
        for (indicator in filterIndicators) {
            val indicatorNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDescription(node, indicator, indicatorNodes)
            if (indicatorNodes.isNotEmpty()) {
                Log.d(TAG, "Node contains filter indicator desc '$indicator' - is a filter chip row")
                return true
            }
        }

        // No filter indicators found. In newer Google Maps versions (Compose UI), search results
        // no longer include a "Directions" button in the accessibility tree, so absence of
        // "Directions" does NOT mean this is not a valid result. Treat as a valid search result.
        Log.d(TAG, "Node has no Directions button but no filter indicators - treating as valid search result (new UI)")
        return false
    }

    /**
     * Check if a node or its descendants contain the given text.
     * IMPORTANT: This function does NOT recycle any nodes - caller is responsible for recycling.
     * This is intentional so the node can still be clicked after this check.
     */
    private fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = containsText(child, text)
                // Do NOT recycle child here - keep node tree intact for subsequent click operations
                if (found) return true
            }
        }

        return false
    }

    private fun getNodeTextRecursive(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (node.text != null) {
            sb.append(node.text)
        }
        if (node.contentDescription != null) {
            if (sb.isNotEmpty()) sb.append(" | ")
            sb.append("[desc:${node.contentDescription}]")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val childText = getNodeTextRecursive(child)
                if (childText.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(childText)
                }
                child.recycle()
            }
        }
        return sb.toString()
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, text: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            results.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByText(child, text, results)
                // Don't recycle here - let caller handle recycling
            }
        }
    }

    /**
     * Wait for a specific element to appear in the UI hierarchy with a timeout.
     * Returns true if the element was found within the timeout, false otherwise.
     */
    private suspend fun waitForElement(
        accessibilityService: WhizAccessibilityService,
        checkElement: (AccessibilityNodeInfo) -> Boolean,
        timeoutMs: Long = 3000,
        pollIntervalMs: Long = 200
    ): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                try {
                    if (checkElement(rootNode)) {
                        rootNode.recycle()
                        return true
                    }
                } finally {
                    rootNode.recycle()
                }
            }
            delay(pollIntervalMs)
        }

        return false
    }

    /**
     * Wait for YouTube Music search results to load by checking the UI for indicators.
     * Looks for filter chips (Songs, Videos, Artists) or search result rows with type indicators.
     * Returns true if search results appear, false if timeout.
     */
    private suspend fun waitForSearchResultsToLoad(
        accessibilityService: WhizAccessibilityService,
        maxWaitMs: Long = 3000
    ): Boolean {
        return waitForElement(accessibilityService, { rootNode ->
            // Check for filter chips (Songs, Videos, Artists, Albums, etc.)
            val chipNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/chip_cloud_chip_text"
            )
            if (chipNodes != null && chipNodes.isNotEmpty()) {
                // Found filter chips - search results are loaded
                Log.d(TAG, "Found ${chipNodes.size} filter chips - search results loaded")
                chipNodes.forEach { it.recycle() }
                return@waitForElement true
            }
            chipNodes?.forEach { it.recycle() }

            // Alternatively, look for search result rows with type indicators ("Song •", "Video •", etc.)
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            for (node in allNodes) {
                val contentDesc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                // Check if this looks like a search result type indicator
                if (contentDesc.startsWith("Song •") || contentDesc.startsWith("Video •") ||
                    contentDesc.startsWith("Album •") || contentDesc.startsWith("Episode •") ||
                    text.startsWith("Song •") || text.startsWith("Video •") ||
                    text.startsWith("Album •") || text.startsWith("Episode •")) {
                    Log.d(TAG, "Found search result with type indicator: $contentDesc$text")
                    allNodes.forEach { it.recycle() }
                    return@waitForElement true
                }
            }
            allNodes.forEach { it.recycle() }

            false
        }, timeoutMs = maxWaitMs, pollIntervalMs = 200)
    }

    /**
     * Detect and dismiss YouTube Music promotional pop-ups (e.g., Family Plan, Premium).
     * Returns true if a pop-up was detected and dismissed, false otherwise.
     */
    private fun dismissYouTubeMusicPopup(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Look for dismiss buttons by text or content-description (case-insensitive)
            val dismissLabels = listOf("Close", "No thanks")
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            for (node in allNodes) {
                if (!node.isClickable) continue
                val text = node.text?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""
                for (label in dismissLabels) {
                    if (text.equals(label, ignoreCase = true) || contentDesc.equals(label, ignoreCase = true)) {
                        Log.d(TAG, "Found dismiss button: text='$text', contentDesc='$contentDesc', clicking")
                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        allNodes.forEach { it.recycle() }
                        if (clicked) {
                            Log.d(TAG, "Successfully dismissed YouTube Music pop-up")
                            return true
                        }
                    }
                }
            }
            allNodes.forEach { it.recycle() }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing YouTube Music pop-up", e)
            return false
        }
    }

    /**
     * Navigate to either the speed dial screen (with search button visible) or the search screen.
     * If we're not on either screen, press back until we are.
     * Returns true if successful, false otherwise.
     */
    private suspend fun navigateToYouTubeMusicSearchableScreen(accessibilityService: WhizAccessibilityService): Boolean {
        Log.d(TAG, "Attempting to navigate to YouTube Music searchable screen")

        // Wait for the search button to be clickable (indicates screen is ready)
        Log.d(TAG, "Waiting for YouTube Music search button to be ready...")
        val searchButtonReady = waitForElement(accessibilityService, { rootNode ->
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/action_search_button"
            )
            var isReady = false
            if (nodes != null && nodes.isNotEmpty()) {
                // Check if the button is actually clickable and enabled
                for (node in nodes) {
                    if (node.isClickable && node.isEnabled && node.isVisibleToUser) {
                        isReady = true
                        break
                    }
                }
            }
            nodes?.forEach { it.recycle() }
            isReady
        }, timeoutMs = 3000)

        if (!searchButtonReady) {
            Log.w(TAG, "YouTube Music search button did not become ready within timeout - will try navigation anyway")
            // Don't return false - the navigation loop will press back to find a searchable screen
            // This is common when a song is already playing and we're on the Now Playing screen
        } else {
            Log.d(TAG, "Search button is ready, proceeding with navigation")
        }

        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                Log.w(TAG, "Could not get root node during navigation attempt $attempt")
                delay(500)
                continue
            }

            try {
                // Check if we're still in YouTube Music
                val packageName = rootNode.packageName?.toString() ?: ""
                Log.d(TAG, "Navigation attempt $attempt: Current package = $packageName")

                if (packageName != "com.google.android.apps.youtube.music") {
                    Log.w(TAG, "Not in YouTube Music app anymore (package: $packageName), navigation failed")
                    rootNode.recycle()
                    return false
                }

                // Check for and dismiss promotional pop-ups
                if (dismissYouTubeMusicPopup(rootNode)) {
                    Log.d(TAG, "Dismissed a pop-up, waiting for UI to stabilize")
                    rootNode.recycle()

                    // Wait for the pop-up to be gone and search button to be clickable again
                    val uiStabilized = waitForElement(accessibilityService, { node ->
                        val searchNodes = node.findAccessibilityNodeInfosByViewId(
                            "com.google.android.apps.youtube.music:id/action_search_button"
                        )
                        var isClickable = false
                        if (searchNodes != null && searchNodes.isNotEmpty()) {
                            for (searchNode in searchNodes) {
                                if (searchNode.isClickable && searchNode.isEnabled) {
                                    isClickable = true
                                    break
                                }
                            }
                        }
                        searchNodes?.forEach { it.recycle() }
                        isClickable
                    }, timeoutMs = 2000)

                    if (!uiStabilized) {
                        Log.w(TAG, "UI did not stabilize after dismissing pop-up")
                    }

                    continue  // Retry navigation after dismissing pop-up
                }

                // Check if we're on the speed dial screen (has "Speed dial" text and search button)
                val speedDialNodes = rootNode.findAccessibilityNodeInfosByText("Speed dial")
                val isOnSpeedDial = speedDialNodes != null && speedDialNodes.isNotEmpty()
                speedDialNodes?.forEach { it.recycle() }

                // Check if search button is visible
                val searchButtonNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.apps.youtube.music:id/action_search_button"
                )
                val hasSearchButton = searchButtonNodes != null && searchButtonNodes.isNotEmpty()
                searchButtonNodes?.forEach { it.recycle() }

                // Check if we're on the expanded Now Playing screen (which we should navigate away from)
                // The mini player at bottom has height ~400px, expanded Now Playing covers most of screen (>1000px)
                val playerPageNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.apps.youtube.music:id/player_page"
                )
                var isOnNowPlayingScreen = false
                if (playerPageNodes != null && playerPageNodes.isNotEmpty()) {
                    for (node in playerPageNodes) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        val height = rect.height()
                        // If player_page height > 1000px, it's expanded (full Now Playing)
                        // If height < 500px, it's just the mini player at bottom - don't block navigation
                        if (height > 1000) {
                            Log.d(TAG, "Found expanded Now Playing screen (height=$height)")
                            isOnNowPlayingScreen = true
                            break
                        } else {
                            Log.d(TAG, "Found mini player only (height=$height), not blocking navigation")
                        }
                    }
                }
                playerPageNodes?.forEach { it.recycle() }

                // Check if we're on the search screen (has search edit text that is visible and enabled)
                // BUT reject if we're on the Now Playing screen (search field might be underneath)
                val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.apps.youtube.music:id/search_edit_text"
                )
                var isOnSearchScreen = false
                if (searchFieldNodes != null && searchFieldNodes.isNotEmpty() && !isOnNowPlayingScreen) {
                    // Check if at least one search field is actually visible and enabled
                    for (node in searchFieldNodes) {
                        if (node.isVisibleToUser && node.isEnabled) {
                            Log.d(TAG, "Found valid search field")
                            isOnSearchScreen = true
                            break
                        }
                    }
                } else if (searchFieldNodes != null && searchFieldNodes.isNotEmpty() && isOnNowPlayingScreen) {
                    Log.d(TAG, "Found search field but we're on Now Playing screen - search field likely underneath")
                }
                searchFieldNodes?.forEach { it.recycle() }

                Log.d(TAG, "Navigation check: speedDial=$isOnSpeedDial, searchButton=$hasSearchButton, searchScreen=$isOnSearchScreen, nowPlaying=$isOnNowPlayingScreen, attempt=$attempt")

                // If we're on either the speed dial page OR the search screen, we're good
                // BUT if we're on the Now Playing screen, the search button is covered and not clickable
                if ((hasSearchButton && !isOnNowPlayingScreen) || isOnSearchScreen) {
                    Log.d(TAG, "On searchable screen (has search button: $hasSearchButton, search screen: $isOnSearchScreen)")
                    rootNode.recycle()
                    return true
                }

                // Not on the right screen, or on Now Playing - press back
                Log.d(TAG, "Not on searchable screen (attempt $attempt/$maxAttempts), pressing back...")
                accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                rootNode.recycle()

                // Wait for navigation to complete
                delay(1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error during navigation attempt $attempt", e)
                rootNode.recycle()
                delay(500)
            }
        }

        Log.w(TAG, "Failed to navigate to YouTube Music searchable screen after $maxAttempts attempts")
        return false
    }

    private suspend fun clickYouTubeMusicSearch(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        val maxAttempts = 3
        val delayBetweenAttempts = 300L

        for (attempt in 1..maxAttempts) {
            try {
                // Get fresh root node on retry attempts (first attempt uses passed-in node)
                val currentRoot = if (attempt == 1) {
                    rootNode
                } else {
                    Log.d(TAG, "Retry attempt $attempt: getting fresh root node")
                    delay(delayBetweenAttempts)
                    accessibilityService.getCurrentRootNode() ?: continue
                }

                // Look for search button - try various possible IDs and descriptions
                val searchViewIds = listOf(
                    "com.google.android.apps.youtube.music:id/action_search_button",
                    "com.google.android.apps.youtube.music:id/action_bar_search"
                )

                var foundButClickFailed = false

                // Try by view ID first
                for (viewId in searchViewIds) {
                    val nodes = currentRoot.findAccessibilityNodeInfosByViewId(viewId)
                    if (nodes != null && nodes.isNotEmpty()) {
                        Log.d(TAG, "Found YouTube Music search button with ID: $viewId (attempt $attempt)")
                        for (node in nodes) {
                            val clickableNode = if (node.isClickable) node else findClickableParent(node)
                            if (clickableNode != null) {
                                val clicked = accessibilityService.clickNode(clickableNode)
                                if (clickableNode != node) {
                                    clickableNode.recycle()
                                }
                                if (clicked) {
                                    Log.d(TAG, "Successfully clicked YouTube Music search button (attempt $attempt)")
                                    nodes.forEach { it.recycle() }
                                    if (attempt > 1 && currentRoot != rootNode) {
                                        currentRoot.recycle()
                                    }
                                    return true
                                } else {
                                    Log.w(TAG, "Click returned false for search button (attempt $attempt)")
                                    foundButClickFailed = true
                                }
                            }
                        }
                        nodes.forEach { it.recycle() }
                    }
                }

                // Try by content description if view ID didn't work
                val searchNodes = currentRoot.findAccessibilityNodeInfosByText("Search")
                if (searchNodes != null && searchNodes.isNotEmpty()) {
                    Log.d(TAG, "Found YouTube Music search button by text/description (attempt $attempt)")
                    for (node in searchNodes) {
                        // Make sure this is actually a search button (ImageButton), not just any text containing "Search"
                        if (node.className == "android.widget.ImageButton") {
                            val clickableNode = if (node.isClickable) node else findClickableParent(node)
                            if (clickableNode != null) {
                                val clicked = accessibilityService.clickNode(clickableNode)
                                if (clickableNode != node) {
                                    clickableNode.recycle()
                                }
                                if (clicked) {
                                    Log.d(TAG, "Successfully clicked YouTube Music search ImageButton (attempt $attempt)")
                                    searchNodes.forEach { it.recycle() }
                                    if (attempt > 1 && currentRoot != rootNode) {
                                        currentRoot.recycle()
                                    }
                                    return true
                                } else {
                                    Log.w(TAG, "Click returned false for search ImageButton (attempt $attempt)")
                                    foundButClickFailed = true
                                }
                            }
                        }
                    }
                    searchNodes.forEach { it.recycle() }
                }

                // Recycle fresh root node if we got one
                if (attempt > 1 && currentRoot != rootNode) {
                    currentRoot.recycle()
                }

                if (foundButClickFailed) {
                    Log.w(TAG, "Found YouTube Music search button but click failed (attempt $attempt/$maxAttempts)")
                } else {
                    Log.w(TAG, "Could not find YouTube Music search button (attempt $attempt/$maxAttempts)")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error clicking YouTube Music search (attempt $attempt)", e)
            }
        }

        Log.w(TAG, "Failed to click YouTube Music search button after $maxAttempts attempts")
        return false
    }

    private suspend fun enterYouTubeMusicSearchQuery(
        rootNode: AccessibilityNodeInfo,
        query: String,
        accessibilityService: WhizAccessibilityService
    ): Boolean {
        try {
            // Check if there's a clear button (meaning there's existing text)
            val clearButtonNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/search_clear"
            )
            var clearedExistingText = false
            if (clearButtonNodes != null && clearButtonNodes.isNotEmpty()) {
                Log.d(TAG, "Found clear button, clicking to clear existing search text")
                val clearButton = clearButtonNodes[0]
                if (clearButton.isClickable) {
                    clearButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clearedExistingText = true
                }
                clearButtonNodes.forEach { it.recycle() }
            }

            // If we cleared existing text, wait for UI to update and get fresh root node
            val searchRootNode = if (clearedExistingText) {
                delay(200)
                accessibilityService.getCurrentRootNode() ?: run {
                    Log.w(TAG, "Could not get fresh root node after clearing search text")
                    return false
                }
            } else {
                rootNode
            }

            // Find EditText for search input
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(searchRootNode, editTextNodes)

            if (editTextNodes.isNotEmpty()) {
                val searchField = editTextNodes[0]

                // Focus on the search field first
                searchField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                // Now set the search text
                val setBundle = Bundle()
                setBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    query
                )
                val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setBundle)

                editTextNodes.forEach { it.recycle() }
                if (clearedExistingText) {
                    searchRootNode.recycle()
                }

                Log.d(TAG, "Entered search query: $query, textSet=$textSet")
                return textSet
            }

            Log.w(TAG, "No EditText nodes found for search input")
            if (clearedExistingText) {
                searchRootNode.recycle()
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error entering YouTube Music search query", e)
            return false
        }
    }

    private fun clickFirstYouTubeMusicSuggestion(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // After entering text, YouTube Music shows autocomplete suggestions
            // These are clickable LinearLayouts that contain TextViews with the suggestion text
            // Look for the first clickable LinearLayout after the search toolbar

            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            // Helper function to check if a node or its children have suggestion text
            fun hasValidSuggestionText(node: AccessibilityNodeInfo): Boolean {
                // Check current node
                val text = node.text?.toString() ?: ""
                if (text.isNotEmpty() && !text.equals("Recent searches", ignoreCase = true)) {
                    return true
                }

                // Check children
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        val childText = child.text?.toString() ?: ""
                        val isValid = childText.isNotEmpty() &&
                                     !childText.equals("Recent searches", ignoreCase = true) &&
                                     child.className?.toString()?.contains("TextView") == true
                        child.recycle()
                        if (isValid) return true
                    }
                }
                return false
            }

            // Look for clickable LinearLayouts that are likely autocomplete suggestions
            for (node in allNodes) {
                val className = node.className?.toString() ?: ""
                val contentDesc = node.contentDescription?.toString() ?: ""

                // Skip "Edit suggestion" buttons and other non-suggestion items
                if (contentDesc.contains("Edit suggestion", ignoreCase = true) ||
                    contentDesc.contains("Recent searches", ignoreCase = true)) {
                    continue
                }

                // Look for clickable LinearLayouts with valid suggestion text in children
                if (node.isClickable &&
                    className == "android.widget.LinearLayout" &&
                    hasValidSuggestionText(node)) {

                    // Get text from children for logging
                    var suggestionText = ""
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            val childText = child.text?.toString() ?: ""
                            if (childText.isNotEmpty()) {
                                suggestionText = childText
                                child.recycle()
                                break
                            }
                            child.recycle()
                        }
                    }

                    Log.d(TAG, "Clicking search suggestion: $suggestionText")
                    val clicked = accessibilityService.clickNode(node)
                    allNodes.forEach { it.recycle() }
                    return clicked
                }
            }

            allNodes.forEach { it.recycle() }
            Log.w(TAG, "Could not find search suggestions")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking first YouTube Music suggestion", e)
            return false
        }
    }

    /**
     * Sealed class representing the result of a YouTube Music search operation.
     */
    private sealed class YouTubeMusicSearchResult {
        object Success : YouTubeMusicSearchResult()
        data class Error(val message: String) : YouTubeMusicSearchResult()
    }

    /**
     * Shared function to perform YouTube Music search with filter chip selection.
     * Used by both playYouTubeMusicSong() and queueYouTubeMusicSong().
     *
     * @param rootNode The current root accessibility node
     * @param query The search query
     * @param filterType The filter chip to click (e.g., "song", "video", "album")
     * @param accessibilityService The accessibility service instance
     * @return YouTubeMusicSearchResult indicating success or error with message
     */
    private suspend fun performYouTubeMusicSearch(
        rootNode: AccessibilityNodeInfo,
        query: String,
        filterType: String,
        accessibilityService: WhizAccessibilityService
    ): YouTubeMusicSearchResult {
        // Check if search field is already visible (may already be on search screen)
        val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId(
            "com.google.android.apps.youtube.music:id/search_edit_text"
        )
        val searchFieldAlreadyVisible = searchFieldNodes != null && searchFieldNodes.isNotEmpty()
        searchFieldNodes?.forEach { it.recycle() }

        if (!searchFieldAlreadyVisible) {
            // Try to find and click the search button
            val searchSuccess = clickYouTubeMusicSearch(rootNode, accessibilityService)

            if (!searchSuccess) {
                return YouTubeMusicSearchResult.Error("Failed to open search in YouTube Music (button may not be found or click may have failed)")
            }

            // Wait for search field to appear
            delay(500)
        } else {
            Log.d(TAG, "Search field already visible, skipping search button click")
        }

        // Enter search query
        val searchRootNode = accessibilityService.getCurrentRootNode()
            ?: return YouTubeMusicSearchResult.Error("Could not get root node after opening search")

        val queryEntered = enterYouTubeMusicSearchQuery(searchRootNode, query, accessibilityService)
        searchRootNode.recycle()

        if (!queryEntered) {
            return YouTubeMusicSearchResult.Error("Could not enter search query")
        }

        // Wait briefly for search field to be ready
        delay(300)

        // Submit search directly by pressing Enter (avoids stale autocomplete suggestions)
        val submitRootNode = accessibilityService.getCurrentRootNode()
            ?: return YouTubeMusicSearchResult.Error("Could not get root node after entering query")

        val searchSubmitted = submitYouTubeMusicSearch(submitRootNode)
        submitRootNode.recycle()

        if (searchSubmitted) {
            Log.d(TAG, "Successfully submitted search via Enter, waiting for results to load")
            // Wait for search results to load
            val searchResultsReady = waitForSearchResultsToLoad(accessibilityService, maxWaitMs = 3000)
            if (!searchResultsReady) {
                Log.w(TAG, "Search results did not load in time")
            }
        } else {
            Log.w(TAG, "Failed to submit search via Enter key, continuing anyway")
        }

        // No longer clicking filter chips - we'll find matching results directly from the main list
        // and click their Play button or the result row itself
        delay(500) // Wait for search results to load

        return YouTubeMusicSearchResult.Success
    }

    /**
     * Submit YouTube Music search by pressing Enter key.
     * This bypasses autocomplete suggestions which can be stale from previous searches.
     */
    private fun submitYouTubeMusicSearch(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Find the search field
            val searchFields = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/search_edit_text"
            )

            if (searchFields != null && searchFields.isNotEmpty()) {
                val searchField = searchFields[0]

                // Try to perform IME action (Enter)
                val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    searchField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    // Fall back to keyevent
                    try {
                        val process = Runtime.getRuntime().exec("input keyevent 66")
                        process.waitFor() == 0
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send KEYCODE_ENTER: ${e.message}")
                        false
                    }
                }

                searchFields.forEach { it.recycle() }
                Log.d(TAG, "Submitted YouTube Music search via Enter key: $result")
                return result
            }

            searchFields?.forEach { it.recycle() }
            Log.w(TAG, "Could not find YouTube Music search field to submit")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting YouTube Music search", e)
            return false
        }
    }

    private fun ensureYTMusicTabSelected(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // After search, YouTube Music shows tabs: YT MUSIC, LIBRARY, DOWNLOADS
            // We need to ensure we're on the YT MUSIC tab to see actual song results

            // Look for text "YT MUSIC" - find the clickable tab and click it
            // Note: We always click it because the selected state isn't reliable
            val ytMusicTextNodes = rootNode.findAccessibilityNodeInfosByText("YT MUSIC")

            if (ytMusicTextNodes != null && ytMusicTextNodes.isNotEmpty()) {
                Log.d(TAG, "Found ${ytMusicTextNodes.size} nodes with 'YT MUSIC' text")

                // Try to find a clickable node directly by looking for the tab container
                // The tab is a LinearLayout with content-desc "YT Music"
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(rootNode, allNodes)

                for (node in allNodes) {
                    if (node.contentDescription?.toString()?.equals("YT Music", ignoreCase = true) == true && node.isClickable) {
                        Log.d(TAG, "Found clickable YT Music tab by content-desc, clicking it")
                        val clicked = accessibilityService.clickNode(node)
                        allNodes.forEach { it.recycle() }
                        ytMusicTextNodes.forEach { it.recycle() }
                        return clicked
                    }
                }

                allNodes.forEach { it.recycle() }
                ytMusicTextNodes.forEach { it.recycle() }
                Log.w(TAG, "Found 'YT MUSIC' text but could not find clickable tab")
            } else {
                Log.w(TAG, "Could not find any nodes with 'YT MUSIC' text")
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring YT Music tab selected", e)
            return false
        }
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        results.add(AccessibilityNodeInfo.obtain(node))
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectAllNodes(child, results)
            }
        }
    }

    /**
     * Poll for the play button to appear in search results, checking every 200ms up to maxWaitMs.
     * Returns true if play button is found and clicked (or song is already playing), false otherwise.
     */
    /**
     * Wait for search results to appear and click the first result.
     * Returns ClickResultInfo with the clicked status and the title of the song that was clicked.
     */
    private suspend fun waitForAndClickPlayButton(
        accessibilityService: WhizAccessibilityService,
        contentType: String = "song",
        maxWaitMs: Long = 3000
    ): ClickResultInfo {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 200L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val result = clickFirstYouTubeMusicResult(rootNode, accessibilityService, contentType)
                rootNode.recycle()

                if (result.clicked) {
                    Log.d(TAG, "Result found and clicked after ${System.currentTimeMillis() - startTime}ms")
                    return result
                }
            }
            delay(pollIntervalMs)
        }

        Log.w(TAG, "Play button not found after waiting ${maxWaitMs}ms")
        return ClickResultInfo(false, null)
    }

    /**
     * Click the appropriate filter chip (Songs, Albums, Artists, etc.) based on content type.
     */
    private fun clickYouTubeMusicFilterChip(
        accessibilityService: WhizAccessibilityService,
        contentType: String
    ): Boolean {
        // Map content type to filter chip text
        val chipText = when (contentType.lowercase()) {
            "song" -> "Songs"
            "album" -> "Albums"
            "artist" -> "Artists"
            "video" -> "Videos"
            "episode" -> "Episodes"
            "community_playlist" -> "Community playlists"
            else -> "Songs" // Default to Songs
        }

        Log.d(TAG, "Looking for filter chip: '$chipText' for content type: $contentType")

        val rootNode = accessibilityService.getCurrentRootNode() ?: return false

        try {
            // Look for the chip_cloud RecyclerView which contains filter chips
            val chipCloudNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/chip_cloud"
            )

            if (chipCloudNodes != null && chipCloudNodes.isNotEmpty()) {
                // Find the chip with matching text
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(rootNode, allNodes)

                for (node in allNodes) {
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.equals(chipText, ignoreCase = true)) {
                        // Find the clickable parent (the FrameLayout containing the chip)
                        var clickableNode: AccessibilityNodeInfo? = node
                        while (clickableNode != null && !clickableNode.isClickable) {
                            clickableNode = clickableNode.parent
                        }

                        if (clickableNode != null && clickableNode.isClickable) {
                            Log.d(TAG, "Found and clicking filter chip: $chipText")
                            val clicked = accessibilityService.clickNode(clickableNode)
                            allNodes.forEach { it.recycle() }
                            chipCloudNodes.forEach { it.recycle() }
                            rootNode.recycle()
                            return clicked
                        }
                    }
                }

                allNodes.forEach { it.recycle() }
                chipCloudNodes.forEach { it.recycle() }
            }

            rootNode.recycle()
            Log.w(TAG, "Could not find filter chip: $chipText")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking filter chip", e)
            rootNode.recycle()
            return false
        }
    }

    /**
     * Poll for the context menu button to appear in search results, checking every 200ms up to maxWaitMs.
     * Returns true if context menu is found and opened, false otherwise.
     */
    private suspend fun waitForAndOpenContextMenu(
        accessibilityService: WhizAccessibilityService,
        maxWaitMs: Long = 3000
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 200L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val result = openYouTubeMusicContextMenu(rootNode, accessibilityService)
                rootNode.recycle()

                if (result) {
                    Log.d(TAG, "Context menu opened after ${System.currentTimeMillis() - startTime}ms")
                    delay(500) // Small delay so user can see the menu appear
                    return true
                }
            }
            delay(pollIntervalMs)
        }

        Log.w(TAG, "Context menu not found after waiting ${maxWaitMs}ms")
        return false
    }

    /**
     * Poll for the "Add to queue" button to appear in the context menu, checking every 200ms up to maxWaitMs.
     * Returns true if "Add to queue" is found and clicked, false otherwise.
     */
    private suspend fun waitForAndClickAddToQueue(
        accessibilityService: WhizAccessibilityService,
        maxWaitMs: Long = 1500
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val pollIntervalMs = 200L

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val result = clickAddToQueue(rootNode, accessibilityService)
                rootNode.recycle()

                if (result) {
                    Log.d(TAG, "Add to queue clicked after ${System.currentTimeMillis() - startTime}ms")
                    delay(500) // Small delay so user can see the action complete
                    return true
                }
            }
            delay(pollIntervalMs)
        }

        Log.w(TAG, "Add to queue not found after waiting ${maxWaitMs}ms")
        return false
    }

    /**
     * Result of clicking a YouTube Music search result.
     * Contains success status and the title of the clicked song (if found).
     */
    private data class ClickResultInfo(
        val clicked: Boolean,
        val clickedTitle: String? = null
    )

    /**
     * Extract the song title from a search result row node.
     * Looks for text nodes within the row that contain the song title.
     */
    private fun extractTitleFromResultRow(rowNode: AccessibilityNodeInfo): String? {
        // The title is usually in a TextView with id "title" inside the row
        val titleNodes = rowNode.findAccessibilityNodeInfosByViewId(
            "com.google.android.apps.youtube.music:id/title"
        )
        if (titleNodes != null && titleNodes.isNotEmpty()) {
            val title = titleNodes[0].text?.toString()
            titleNodes.forEach { it.recycle() }
            if (title != null) return title
        }

        // Fallback: look for the first text node that looks like a title
        // (not duration, not play count, etc.)
        val allChildren = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(rowNode, allChildren)

        for (child in allChildren) {
            val text = child.text?.toString() ?: continue
            // Skip if it looks like metadata (duration, play count, etc.)
            if (text.contains("•") || text.matches(Regex("\\d+:\\d+")) ||
                text.matches(Regex("\\d+(\\.\\d+)?[KMB]?\\s*(plays|views)?", RegexOption.IGNORE_CASE))) {
                continue
            }
            // Found a text that could be a title
            if (text.length > 1) {
                allChildren.forEach { it.recycle() }
                return text
            }
        }

        allChildren.forEach { it.recycle() }
        return null
    }

    private suspend fun clickFirstYouTubeMusicResult(
        rootNode: AccessibilityNodeInfo,
        accessibilityService: WhizAccessibilityService,
        contentType: String = "song"
    ): ClickResultInfo {
        try {
            // First, try to find the big "Play" button in the top result card (preferred)
            val topResultPlay = findTopResultPlayButton(rootNode, contentType)
            if (topResultPlay != null) {
                val (playButton, title) = topResultPlay
                Log.d(TAG, "Found top result Play button for '$contentType': '$title'")
                val clicked = accessibilityService.clickNode(playButton)
                playButton.recycle()
                if (clicked) {
                    Log.d(TAG, "Successfully clicked top result Play button")
                    return ClickResultInfo(clicked, title)
                }
                Log.w(TAG, "Failed to click top result Play button, falling back to row click")
            }

            // Fall back to clicking a result row that matches the content type
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            // Get acceptable type indicators for this content type
            val acceptableTypes = getAcceptableTypeIndicators(contentType)
            Log.d(TAG, "Searching for result row with types: $acceptableTypes among ${allNodes.size} nodes")

            // Find clickable result rows (Button class, long-clickable)
            var matchingRow: AccessibilityNodeInfo? = null
            var matchingTitle: String? = null

            for (node in allNodes) {
                val className = node.className?.toString() ?: ""
                // Result rows are typically Button or ViewGroup that are clickable and long-clickable
                if ((className == "android.widget.Button" || className == "android.view.ViewGroup")
                    && node.isClickable && node.isLongClickable) {

                    // Check if this row contains a matching type indicator in its children
                    if (rowContainsTypeIndicator(node, acceptableTypes)) {
                        // Extract the title from this row
                        val title = extractTitleFromResultRow(node)
                        Log.d(TAG, "Found matching result row with title: '$title'")
                        matchingRow = node
                        matchingTitle = title
                        break
                    }
                }
            }

            if (matchingRow != null) {
                // Click the matching result row
                val clicked = accessibilityService.clickNode(matchingRow)
                Log.d(TAG, "Clicked matching result row: $clicked, title: '$matchingTitle'")
                allNodes.forEach { it.recycle() }

                // For List-type and Podcast-type content, clicking the row opens the detail page
                // We need to then click the Play button on that page to actually start playback
                val needsDetailPagePlay = contentType.lowercase() in listOf("album", "community_playlist", "artist", "episode")
                if (clicked && needsDetailPagePlay) {
                    Log.d(TAG, "Content type '$contentType' needs detail page Play button click")
                    // Wait for the detail page to load, then click the Play button
                    val playClicked = clickPlayButtonOnDetailPage(accessibilityService, contentType)
                    if (playClicked) {
                        Log.d(TAG, "Successfully clicked Play button on detail page")
                        return ClickResultInfo(true, matchingTitle)
                    } else {
                        Log.w(TAG, "Could not find Play button on detail page")
                        return ClickResultInfo(false, matchingTitle)
                    }
                }

                return ClickResultInfo(clicked, matchingTitle)
            }

            allNodes.forEach { it.recycle() }
            Log.w(TAG, "Could not find any result rows matching types: $acceptableTypes")
            return ClickResultInfo(false, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking YouTube Music result", e)
            return ClickResultInfo(false, null)
        }
    }

    /**
     * Click the Play button on a playlist/album/podcast detail page.
     * Called after clicking a playlist/album/podcast row from search results.
     * Waits for the detail page to load and finds the Play button.
     *
     * For podcasts, we look for the Play button on the first (most recent) episode row.
     * For playlists/albums, we look for the big central Play button.
     */
    private suspend fun clickPlayButtonOnDetailPage(accessibilityService: WhizAccessibilityService, contentType: String = "album"): Boolean {
        // Wait for the detail page to load
        delay(1000)

        val isPodcast = contentType.lowercase() == "episode"
        Log.d(TAG, "Looking for Play button on detail page, contentType=$contentType, isPodcast=$isPodcast")

        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val rootNode = accessibilityService.getCurrentRootNode() ?: continue

            try {
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectAllNodes(rootNode, allNodes)

                if (isPodcast) {
                    // For podcasts, find the Play button in the first episode row
                    // Episode rows are in section_list_content RecyclerView
                    // The Play button has content-desc like "Play <episode name>"
                    val playButtons = mutableListOf<Pair<AccessibilityNodeInfo, Int>>() // node to Y position

                    for (node in allNodes) {
                        val contentDesc = node.contentDescription?.toString() ?: ""

                        // Look for Play buttons with episode names (not just "Play")
                        if (node.isClickable && contentDesc.startsWith("Play ", ignoreCase = true)) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)

                            // Skip mini player buttons (they're at the bottom of the screen, Y > 2000)
                            if (bounds.top < 2000) {
                                Log.d(TAG, "Found podcast episode Play button: '$contentDesc' at Y=${bounds.top}")
                                playButtons.add(Pair(node, bounds.top))
                            }
                        }
                    }

                    // Sort by Y position and click the topmost one (first/latest episode)
                    if (playButtons.isNotEmpty()) {
                        val topmost = playButtons.minByOrNull { it.second }!!
                        val (playButton, yPos) = topmost
                        Log.d(TAG, "Clicking topmost episode Play button at Y=$yPos: '${playButton.contentDescription}'")
                        val clicked = accessibilityService.clickNode(playButton)
                        allNodes.forEach { it.recycle() }
                        rootNode.recycle()
                        if (clicked) {
                            delay(500) // Wait for playback to start
                            return true
                        }
                    }
                } else {
                    // For playlists/albums/episodes, look for the big central Play button
                    for (node in allNodes) {
                        val contentDesc = node.contentDescription?.toString() ?: ""
                        val resourceId = node.viewIdResourceName ?: ""

                        // Look for Play button - it should be clickable and have "Play" in content-desc
                        // Content-desc can be "Play" or "Play <episode/song name>" (e.g., "Play Mini-Stories: Volume 9")
                        if (node.isClickable && (contentDesc.equals("Play", ignoreCase = true) || contentDesc.startsWith("Play ", ignoreCase = true))) {
                            // Make sure this isn't a mini player button by checking it's not too small
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            val buttonSize = bounds.width()

                            // Mini player play button is typically small (~126px), detail page button is larger
                            if (buttonSize > 150) {
                                Log.d(TAG, "Found Play button on detail page (size=$buttonSize): contentDesc='$contentDesc'")
                                val clicked = accessibilityService.clickNode(node)
                                allNodes.forEach { it.recycle() }
                                rootNode.recycle()
                                if (clicked) {
                                    delay(500) // Wait for playback to start
                                    return true
                                }
                            } else {
                                Log.d(TAG, "Skipping small Play button (size=$buttonSize), likely mini player")
                            }
                        }

                        // Also look for shuffle_play button which is common on playlist pages
                        if (node.isClickable && resourceId.contains("play") && !resourceId.contains("mini_player")) {
                            Log.d(TAG, "Found play-related button: resourceId='$resourceId', contentDesc='$contentDesc'")
                            if (contentDesc.equals("Play", ignoreCase = true) || contentDesc.equals("Shuffle play", ignoreCase = true)) {
                                val clicked = accessibilityService.clickNode(node)
                                allNodes.forEach { it.recycle() }
                                rootNode.recycle()
                                if (clicked) {
                                    delay(500)
                                    return true
                                }
                            }
                        }
                    }
                }

                allNodes.forEach { it.recycle() }
                rootNode.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error finding Play button on detail page", e)
                rootNode.recycle()
            }

            delay(500) // Wait before next attempt
        }

        Log.w(TAG, "Could not find Play button on detail page after $maxAttempts attempts")
        return false
    }

    /**
     * Get the title of the currently playing song.
     * Checks both the mini player (minimized) and expanded Now Playing view.
     */
    private fun getMiniPlayerTitle(rootNode: AccessibilityNodeInfo): String? {
        // Try mini player title (when player is minimized at bottom)
        val miniPlayerTitleNodes = rootNode.findAccessibilityNodeInfosByViewId(
            "com.google.android.apps.youtube.music:id/mini_player_title"
        )
        if (miniPlayerTitleNodes != null && miniPlayerTitleNodes.isNotEmpty()) {
            val title = miniPlayerTitleNodes[0].text?.toString()
            miniPlayerTitleNodes.forEach { it.recycle() }
            if (title != null) {
                Log.d(TAG, "Found song title in mini player: $title")
                return title
            }
        }

        // Try expanded player title (when player is full screen)
        val expandedTitleNodes = rootNode.findAccessibilityNodeInfosByViewId(
            "com.google.android.apps.youtube.music:id/title"
        )
        if (expandedTitleNodes != null && expandedTitleNodes.isNotEmpty()) {
            val title = expandedTitleNodes[0].text?.toString()
            expandedTitleNodes.forEach { it.recycle() }
            if (title != null) {
                Log.d(TAG, "Found song title in expanded player: $title")
                return title
            }
        }

        // Try watch_header_title (another ID used in expanded view)
        val watchHeaderNodes = rootNode.findAccessibilityNodeInfosByViewId(
            "com.google.android.apps.youtube.music:id/watch_header_title"
        )
        if (watchHeaderNodes != null && watchHeaderNodes.isNotEmpty()) {
            val title = watchHeaderNodes[0].text?.toString()
            watchHeaderNodes.forEach { it.recycle() }
            if (title != null) {
                Log.d(TAG, "Found song title in watch header: $title")
                return title
            }
        }

        return null
    }

    // Helper to check if a node or its descendants contain "Song •" in content description
    private fun nodeContainsSongIndicator(node: AccessibilityNodeInfo): Boolean {
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (contentDesc.startsWith("Song •")) {
            return true
        }
        // Check children recursively
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (nodeContainsSongIndicator(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }


    private fun openYouTubeMusicContextMenu(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // We need to find the "Action menu" button (3-dot menu) specifically for a song result
            // Song results are Button rows containing a child with content-desc like "Song •..." or "Video •..."
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            val acceptableSongTypes = listOf("Song •", "Video •")

            // Find result rows (Button or ViewGroup) that are song search results
            var foundResultRows = 0
            var foundSongRows = 0
            for (node in allNodes) {
                val className = node.className?.toString() ?: ""
                val isResultRow = (className == "android.widget.Button" || className == "android.view.ViewGroup") && node.isClickable
                if (isResultRow) {
                    foundResultRows++
                    // Check if this row contains a song type indicator
                    val isSongResult = hasSongTypeIndicator(node, acceptableSongTypes)
                    if (isSongResult) {
                        foundSongRows++
                        // Look for "Action menu" button within this row
                        val actionMenuButton = findActionMenuInRow(node)
                        if (actionMenuButton != null) {
                            val clicked = accessibilityService.clickNode(actionMenuButton)
                            Log.d(TAG, "Clicked Action menu button for song result: $clicked")
                            actionMenuButton.recycle()
                            allNodes.forEach { it.recycle() }
                            return clicked
                        } else {
                            Log.d(TAG, "Found song row but no Action menu button inside")
                        }
                    }
                }
            }

            // Fallback: If no song type indicator found, collect all Action menus in result rows and try them
            // Skip artist/album cards (which have "Shuffle play" instead of "Add to queue")
            Log.d(TAG, "openContextMenu: no typed song results found (foundResultRows=$foundResultRows, foundSongRows=$foundSongRows). Trying fallback...")
            val actionMenuButtons = mutableListOf<AccessibilityNodeInfo>()
            for (node in allNodes) {
                val className = node.className?.toString() ?: ""
                val isResultRow = (className == "android.widget.Button" || className == "android.view.ViewGroup") && node.isClickable
                if (isResultRow) {
                    val actionMenuButton = findActionMenuInRow(node)
                    if (actionMenuButton != null) {
                        actionMenuButtons.add(actionMenuButton)
                    }
                }
            }
            // Skip the first result (often a "Top result" artist/album card) and click the second one
            if (actionMenuButtons.size > 1) {
                val clicked = accessibilityService.clickNode(actionMenuButtons[1])
                Log.d(TAG, "Fallback: Clicked second Action menu button (skipping first/top result): $clicked")
                actionMenuButtons.forEach { it.recycle() }
                allNodes.forEach { it.recycle() }
                return clicked
            } else if (actionMenuButtons.isNotEmpty()) {
                val clicked = accessibilityService.clickNode(actionMenuButtons[0])
                Log.d(TAG, "Fallback: Only one Action menu found, clicking it: $clicked")
                actionMenuButtons.forEach { it.recycle() }
                allNodes.forEach { it.recycle() }
                return clicked
            }

            // Final fallback: Find ANY "Action menu" buttons directly by content description
            // This handles cases where the Action menu is not a child of a clickable row
            val directActionMenus = mutableListOf<AccessibilityNodeInfo>()
            for (node in allNodes) {
                val contentDesc = node.contentDescription?.toString() ?: ""
                if (contentDesc.equals("Action menu", ignoreCase = true) && node.isClickable) {
                    directActionMenus.add(node)
                }
            }
            if (directActionMenus.isNotEmpty()) {
                // Click the first Action menu (since we've already filtered with Songs chip)
                val clicked = accessibilityService.clickNode(directActionMenus[0])
                Log.d(TAG, "Final fallback: Found ${directActionMenus.size} direct Action menu buttons, clicked first: $clicked")
                allNodes.forEach { if (!directActionMenus.contains(it)) it.recycle() }
                directActionMenus.forEach { it.recycle() }
                return clicked
            }

            // Log what content-descs we found for debugging
            val contentDescs = mutableListOf<String>()
            for (node in allNodes) {
                val cd = node.contentDescription?.toString()
                if (!cd.isNullOrEmpty()) contentDescs.add(cd)
            }
            Log.d(TAG, "Content descs: ${contentDescs.take(20).joinToString(", ")}")

            Log.w(TAG, "No song search results with action menu found (even with fallback)")
            // Dump UI for debugging before recycling nodes
            dumpUIHierarchy(rootNode, "ytmusic_no_action_menu", "No song search results with action menu found in YouTube Music")
            allNodes.forEach { it.recycle() }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening YouTube Music context menu", e)
            return false
        }
    }

    /**
     * Get acceptable type indicators based on content type.
     * Categories:
     * - Song: accepts "Song •" and "Video •"
     * - List: accepts "Album •", "Playlist •", "Mix created by AI", "Artist •"
     * - Podcast: accepts "Episode •" and "Podcast •"
     */
    private fun getAcceptableTypeIndicators(contentType: String): List<String> {
        return when (contentType.lowercase()) {
            "song", "video" -> listOf("Song •", "Video •")
            "album", "community_playlist", "artist" -> listOf("Album •", "Playlist •", "Mix created by AI", "Artist •")
            "episode" -> listOf("Episode •", "Podcast •")
            else -> listOf("Song •", "Video •") // Default to Song category
        }
    }

    /**
     * Check if a node or any of its descendants contains any of the acceptable type indicators.
     */
    private fun treeContainsTypeIndicator(node: AccessibilityNodeInfo, acceptableTypes: List<String>): Boolean {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""

        // Check this node for any acceptable type
        for (typeIndicator in acceptableTypes) {
            if (text.startsWith(typeIndicator) || text.contains(typeIndicator) ||
                contentDesc.startsWith(typeIndicator) || contentDesc.contains(typeIndicator)) {
                Log.d(TAG, "Found type indicator '$typeIndicator' in text='$text' or contentDesc='$contentDesc'")
                return true
            }
        }

        // Check children recursively
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (treeContainsTypeIndicator(child, acceptableTypes)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Find the "Play" button in the top result card, matching the content type category.
     * Returns the button node and title, or null if not found or wrong type.
     */
    private fun findTopResultPlayButton(rootNode: AccessibilityNodeInfo, contentType: String): Pair<AccessibilityNodeInfo, String>? {
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(rootNode, allNodes)
        val acceptableTypes = getAcceptableTypeIndicators(contentType)
        Log.d(TAG, "Looking for Play button with acceptable types: $acceptableTypes")

        for (node in allNodes) {
            val contentDesc = node.contentDescription?.toString() ?: ""
            // Look for button with content-desc like "Play <title>"
            if (node.isClickable &&
                contentDesc.startsWith("Play ") &&
                contentDesc.length > 5) {  // More than just "Play "

                // Verify the result matches our content type category
                val parent = node.parent
                if (parent != null) {
                    if (treeContainsTypeIndicator(parent, acceptableTypes)) {
                        val title = contentDesc.removePrefix("Play ")
                        Log.d(TAG, "Found Play button matching category '$contentType': '$title'")
                        allNodes.filter { it != node }.forEach { it.recycle() }
                        return Pair(node, title)
                    } else {
                        Log.d(TAG, "Found Play button but wrong category (expected $acceptableTypes): $contentDesc")
                    }
                    parent.recycle()
                }
            }
        }

        allNodes.forEach { it.recycle() }
        return null
    }

    private fun hasSongTypeIndicator(node: AccessibilityNodeInfo, acceptableTypes: List<String>): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childContentDesc = child.contentDescription?.toString() ?: ""
            val childText = child.text?.toString() ?: ""

            for (type in acceptableTypes) {
                if (childContentDesc.startsWith(type) || childText.startsWith(type)) {
                    child.recycle()
                    return true
                }
            }

            // Check grandchildren
            for (j in 0 until child.childCount) {
                val grandchild = child.getChild(j) ?: continue
                val grandchildContentDesc = grandchild.contentDescription?.toString() ?: ""
                val grandchildText = grandchild.text?.toString() ?: ""

                for (type in acceptableTypes) {
                    if (grandchildContentDesc.startsWith(type) || grandchildText.startsWith(type)) {
                        grandchild.recycle()
                        child.recycle()
                        return true
                    }
                }
                grandchild.recycle()
            }
            child.recycle()
        }
        return false
    }

    /**
     * Alias for hasSongTypeIndicator - checks if a result row contains any of the acceptable type indicators.
     */
    private fun rowContainsTypeIndicator(node: AccessibilityNodeInfo, acceptableTypes: List<String>): Boolean {
        return hasSongTypeIndicator(node, acceptableTypes)
    }

    private fun findActionMenuInRow(rowNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until rowNode.childCount) {
            val child = rowNode.getChild(i) ?: continue
            val childContentDesc = child.contentDescription?.toString() ?: ""

            if (childContentDesc.equals("Action menu", ignoreCase = true)) {
                return child
            }

            // Check grandchildren
            for (j in 0 until child.childCount) {
                val grandchild = child.getChild(j) ?: continue
                val grandchildContentDesc = grandchild.contentDescription?.toString() ?: ""

                if (grandchildContentDesc.equals("Action menu", ignoreCase = true)) {
                    child.recycle()
                    return grandchild
                }
                grandchild.recycle()
            }
            child.recycle()
        }
        return null
    }

    private fun hasChildWithResourceId(node: AccessibilityNodeInfo, resourceId: String): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childResourceId = child.viewIdResourceName ?: ""

            if (childResourceId == resourceId) {
                child.recycle()
                return true
            }

            // Check grandchildren
            for (j in 0 until child.childCount) {
                val grandchild = child.getChild(j) ?: continue
                val grandchildResourceId = grandchild.viewIdResourceName ?: ""

                if (grandchildResourceId == resourceId) {
                    grandchild.recycle()
                    child.recycle()
                    return true
                }
                grandchild.recycle()
            }
            child.recycle()
        }
        return false
    }

    private fun clickAddToQueue(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for "Add to queue" or "Play next" text
            val queueTexts = listOf("Add to queue", "Play next", "add to queue", "play next")

            for (text in queueTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (nodes != null && nodes.isNotEmpty()) {
                    Log.d(TAG, "Found ${nodes.size} nodes for text '$text'")
                    for (node in nodes) {
                        val clickableNode = if (node.isClickable) node else findClickableParent(node)
                        if (clickableNode != null) {
                            val clicked = accessibilityService.clickNode(clickableNode)
                            if (clickableNode != node) {
                                clickableNode.recycle()
                            }
                            if (clicked) {
                                Log.d(TAG, "Successfully clicked 'Add to queue' option")
                                nodes.forEach { it.recycle() }
                                return true
                            }
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            // Log what's visible in the menu for debugging
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)
            val visibleTexts = mutableListOf<String>()
            for (node in allNodes) {
                val text = node.text?.toString()
                val contentDesc = node.contentDescription?.toString()
                if (!text.isNullOrEmpty()) visibleTexts.add("text='$text'")
                if (!contentDesc.isNullOrEmpty()) visibleTexts.add("desc='$contentDesc'")
            }
            Log.d(TAG, "Could not find queue option. Visible elements: ${visibleTexts.take(30).joinToString(", ")}")
            // Dump UI for debugging before recycling nodes
            dumpUIHierarchy(rootNode, "ytmusic_no_queue_option", "Add to queue option not found in YouTube Music context menu")
            allNodes.forEach { it.recycle() }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking add to queue", e)
            return false
        }
    }

    private fun findYouTubeMusicResults(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        // Look for ViewGroup items that are likely search results
        // They typically contain song title and artist information
        searchForMusicResults(node, results, depth = 0)

        return results
    }

    private fun searchForMusicResults(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 10) return // Limit recursion depth

        try {
            // Check if this node looks like a music result item
            // It should have text content and be part of a list
            val hasText = node.text != null || node.contentDescription != null
            val isListItem = node.className?.contains("ViewGroup") == true ||
                           node.className?.contains("LinearLayout") == true ||
                           node.className?.contains("FrameLayout") == true

            if (hasText && isListItem && node.isClickable && depth >= 3) {
                // This might be a result item - add it
                results.add(AccessibilityNodeInfo.obtain(node))

                // Don't search children of results we've already found
                return
            }

            // Continue searching children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    searchForMusicResults(child, results, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for music results", e)
        }
    }

    private fun findNodesByContentDescription(node: AccessibilityNodeInfo, contentDesc: String, results: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 15) return // Limit recursion depth

        try {
            // Check if this node matches the content description (using contains for partial matching)
            val nodeContentDesc = node.contentDescription?.toString()
            if (nodeContentDesc != null && nodeContentDesc.contains(contentDesc, ignoreCase = true)) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }

            // Continue searching children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findNodesByContentDescription(child, contentDesc, results, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nodes by content description", e)
        }
    }

    private fun findNodesByResourceId(node: AccessibilityNodeInfo, resourceId: String, results: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40) return // Limit recursion depth (Google Maps nests UI elements ~28 levels deep)

        try {
            // Check if this node matches the resource ID
            val nodeResourceId = node.viewIdResourceName
            if (nodeResourceId != null && nodeResourceId == resourceId) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }

            // Continue searching children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findNodesByResourceId(child, resourceId, results, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding nodes by resource ID", e)
        }
    }

    private fun findMoreOptionsButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Look for ImageButton or ImageView with "More options" or similar description
            return searchForMoreButton(node)
        } catch (e: Exception) {
            Log.e(TAG, "Error finding more options button", e)
            return null
        }
    }

    private fun searchForMoreButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 10) return null

        try {
            // Check if this is a more options button
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            val isButton = node.className == "android.widget.ImageButton" ||
                          node.className == "android.widget.ImageView"

            if (isButton && contentDesc != null &&
                (contentDesc.contains("more") || contentDesc.contains("options") || contentDesc.contains("menu"))) {
                return AccessibilityNodeInfo.obtain(node)
            }

            // Search children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val result = searchForMoreButton(child, depth + 1)
                    child.recycle()
                    if (result != null) {
                        return result
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in searchForMoreButton", e)
        }

        return null
    }

    // ========== Helper Functions ==========

    /**
     * Normalize a chat name for comparison by removing spaces, special characters, and suffixes.
     * This helps match chat names that have different formatting (e.g., "+1(628)209-9005" vs "+1 (628) 209-9005 (You)")
     */
    private fun normalizeChatName(name: String): String {
        return name
            .lowercase()
            .trim()
            .replace(Regex("[\\s\\u200B-\\u200D\\uFEFF]"), "") // Remove all whitespace and invisible chars
            .replace(Regex("\\(you\\)$"), "") // Remove "(You)" suffix
            .replace(Regex("[()\\-]"), "") // Remove parentheses and hyphens from phone numbers
    }

    /**
     * Check if a string looks like a phone number
     */
    private fun isPhoneNumber(text: String): Boolean {
        // Remove common phone number formatting characters
        val digitsOnly = text.replace(Regex("[^0-9]"), "")
        // Check if we have a reasonable number of digits (7-15 is typical for phone numbers)
        // and that the original text contains mostly numbers and phone formatting chars
        return digitsOnly.length >= 7 && digitsOnly.length <= 15 &&
                text.replace(Regex("[0-9()\\-\\s+]"), "").isEmpty()
    }

    /**
     * Poll for a specific condition with exponential backoff
     * @param condition The condition to check
     * @param maxWaitMs Maximum time to wait in milliseconds
     * @param initialDelayMs Initial delay between checks
     * @param maxIntervalMs Maximum interval between checks
     * @return true if condition was met within timeout
     */
    private suspend fun waitForCondition(
        maxWaitMs: Long = 2000,
        initialDelayMs: Long = 50,
        maxIntervalMs: Long = 500,
        condition: suspend () -> Boolean
    ): Boolean {
        val startTime = System.currentTimeMillis()
        var currentDelay = initialDelayMs

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (condition()) {
                Log.d(TAG, "Condition met after ${System.currentTimeMillis() - startTime}ms")
                return true
            }

            val remainingTime = maxWaitMs - (System.currentTimeMillis() - startTime)
            if (remainingTime > 0) {
                delay(minOf(currentDelay, remainingTime))
                currentDelay = minOf(currentDelay * 2, maxIntervalMs)
            }
        }

        Log.d(TAG, "Condition not met after ${maxWaitMs}ms timeout")
        return false
    }

    /**
     * Wait for a specific app to be in the foreground by checking the package name
     */
    private suspend fun waitForAppReady(
        accessibilityService: WhizAccessibilityService,
        packageName: String,
        maxWaitMs: Long = 3000
    ): Boolean {
        Log.d(TAG, "Waiting for app $packageName to be ready...")
        return waitForCondition(maxWaitMs = maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val isReady = rootNode.packageName?.toString() == packageName
                rootNode.recycle()
                if (isReady) {
                    Log.d(TAG, "App $packageName is now in foreground")
                }
                isReady
            } else {
                false
            }
        }
    }
    
    /**
     * Wait for WhatsApp UI to be ready after an action
     */
    private suspend fun waitForWhatsAppReady(
        accessibilityService: WhizAccessibilityService,
        expectedScreen: WhatsAppScreen? = null,
        maxWaitMs: Long = 2000
    ): Boolean {
        return waitForCondition(maxWaitMs = maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val currentScreen = detectWhatsAppScreen(rootNode)
                rootNode.recycle()
                
                // If we expect a specific screen, wait for it
                if (expectedScreen != null) {
                    currentScreen == expectedScreen
                } else {
                    // Otherwise just ensure we're on a known WhatsApp screen
                    currentScreen != WhatsAppScreen.UNKNOWN
                }
            } else {
                false
            }
        }
    }
    
    /**
     * Wait for search results to appear
     */
    private suspend fun waitForSearchResults(
        accessibilityService: WhizAccessibilityService,
        searchQuery: String,
        maxWaitMs: Long = 2000
    ): Boolean {
        return waitForCondition(maxWaitMs = maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                // Try text matching first
                val chatNodes = findChatNodes(rootNode, searchQuery)
                val hasResults = chatNodes.isNotEmpty()
                chatNodes.forEach { it.recycle() }
                if (hasResults) {
                    rootNode.recycle()
                    return@waitForCondition true
                }
                // Fallback: check if any clickable result exists below search input
                // This handles group chats where the search query differs from the display name
                val firstResult = findFirstSearchResult(rootNode)
                val hasAnyResult = firstResult != null
                firstResult?.recycle()
                rootNode.recycle()
                hasAnyResult
            } else {
                false
            }
        }
    }
    
    /**
     * Wait for a specific UI element to appear
     */
    private suspend fun waitForElement(
        accessibilityService: WhizAccessibilityService,
        viewId: String? = null,
        text: String? = null,
        maxWaitMs: Long = 2000
    ): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        var currentDelay = 50L
        
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode != null) {
                val nodes = when {
                    viewId != null -> rootNode.findAccessibilityNodeInfosByViewId(viewId)
                    text != null -> rootNode.findAccessibilityNodeInfosByText(text)
                    else -> null
                }
                
                if (nodes != null && nodes.isNotEmpty()) {
                    val result = nodes[0]
                    // Recycle the rest
                    for (i in 1 until nodes.size) {
                        nodes[i].recycle()
                    }
                    rootNode.recycle()
                    Log.d(TAG, "Element found after ${System.currentTimeMillis() - startTime}ms")
                    return result
                }
                
                nodes?.forEach { it.recycle() }
                rootNode.recycle()
            }
            
            val remainingTime = maxWaitMs - (System.currentTimeMillis() - startTime)
            if (remainingTime > 0) {
                delay(minOf(currentDelay, remainingTime))
                currentDelay = minOf(currentDelay * 2, 500)
            }
        }
        
        Log.d(TAG, "Element not found after ${maxWaitMs}ms timeout")
        return null
    }
    
    private enum class WhatsAppScreen {
        CHAT_LIST,
        INSIDE_CHAT,
        SEARCH_ACTIVE,
        SETTINGS,
        STATUS,
        CALLS,
        UNKNOWN
    }
    
    private fun detectWhatsAppScreen(rootNode: AccessibilityNodeInfo): WhatsAppScreen {
        try {
            // Check for Archived screen first - it looks like a chat list but isn't the main one
            // The Archived screen has "Archived" text in the toolbar
            // BUT: The main chat list also has "Archived" text in a row (com.whatsapp:id/archived_row)
            // So we first check indicators that we're on main chat list, not archived screen:
            // 1. archived_row exists (the row showing "Archived" with count)
            // 2. bottom_nav_container exists (main chat list has bottom nav, archived screen doesn't)
            val archivedRow = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/archived_row")
            val hasArchivedRow = archivedRow != null && archivedRow.isNotEmpty()
            archivedRow?.forEach { it.recycle() }

            val bottomNav = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/bottom_nav_container")
            val hasBottomNav = bottomNav != null && bottomNav.isNotEmpty()
            bottomNav?.forEach { it.recycle() }

            val isMainChatList = hasArchivedRow || hasBottomNav

            if (!isMainChatList) {
                // Only check for archived screen if we don't have the archived_row (which is on main chat list)
                val toolbar = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar")
                if (toolbar != null && toolbar.isNotEmpty()) {
                    for (toolbarNode in toolbar) {
                        val archivedNodes = rootNode.findAccessibilityNodeInfosByText("Archived")
                        if (archivedNodes != null && archivedNodes.isNotEmpty()) {
                            for (archivedNode in archivedNodes) {
                                // Check if "Archived" text is a direct title (not part of a chat name)
                                if (archivedNode.className?.toString() == "android.widget.TextView" &&
                                    archivedNode.text?.toString() == "Archived") {
                                    archivedNodes.forEach { it.recycle() }
                                    toolbar.forEach { it.recycle() }
                                    Log.d(TAG, "On WhatsApp Archived screen - need to go back to main chat list")
                                    return WhatsAppScreen.UNKNOWN
                                }
                            }
                            archivedNodes.forEach { it.recycle() }
                        }
                    }
                    toolbar.forEach { it.recycle() }
                }
            }

            // Check for Community page - it has conversations_row_contact_name like the
            // main chat list, but it's a different screen. Detect it early and return
            // UNKNOWN so back-navigation will press back to the real chat list.
            val communityName = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/community_navigation_communityName")
            if (communityName != null && communityName.isNotEmpty()) {
                communityName.forEach { it.recycle() }
                Log.d(TAG, "On WhatsApp Community page - not the main chat list")
                return WhatsAppScreen.UNKNOWN
            }

            // Check if we're inside a chat first (most specific screen)
            // A chat screen has the message input field and/or conversation elements
            val messageInputField = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
            if (messageInputField != null && messageInputField.isNotEmpty()) {
                messageInputField.forEach { it.recycle() }
                Log.d(TAG, "Inside WhatsApp chat (found message input field)")
                return WhatsAppScreen.INSIDE_CHAT
            }

            // Alternative: Check for conversation contact name in the action bar (indicates we're in a chat)
            val conversationContactName = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            if (conversationContactName != null && conversationContactName.isNotEmpty()) {
                conversationContactName.forEach { it.recycle() }
                Log.d(TAG, "Inside WhatsApp chat (found conversation contact name)")
                return WhatsAppScreen.INSIDE_CHAT
            }

            // Check for chat list elements - only if we have main screen indicators,
            // since conversations_row_contact_name also appears on Community pages
            if (isMainChatList) {
                val chatList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name")
                if (chatList != null && chatList.isNotEmpty()) {
                    chatList.forEach { it.recycle() }
                    Log.d(TAG, "On WhatsApp chat list")
                    return WhatsAppScreen.CHAT_LIST
                }
            }

            // Check for the tab layout which indicates we're on the main screen with tabs
            val tabLayout = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_layout")
            if (tabLayout != null && tabLayout.isNotEmpty()) {
                tabLayout.forEach { it.recycle() }
                Log.d(TAG, "On WhatsApp main screen with tabs (chat list)")
                return WhatsAppScreen.CHAT_LIST
            }

            // Check for the "Chats" tab specifically
            val chatsTab = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_chats")
            if (chatsTab != null && chatsTab.isNotEmpty()) {
                chatsTab.forEach { it.recycle() }
                Log.d(TAG, "Found Chats tab (on chat list)")
                return WhatsAppScreen.CHAT_LIST
            }

            // NEW: Check for the new WhatsApp search bar (com.whatsapp:id/my_search_bar)
            val newSearchBar = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/my_search_bar")
            if (newSearchBar != null && newSearchBar.isNotEmpty()) {
                newSearchBar.forEach { it.recycle() }
                Log.d(TAG, "Found new WhatsApp search bar (on chat list/main screen)")
                return WhatsAppScreen.CHAT_LIST
            }

            // NEW: Check for the pager which indicates main WhatsApp screen
            val pager = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/pager")
            if (pager != null && pager.isNotEmpty()) {
                pager.forEach { it.recycle() }
                Log.d(TAG, "Found WhatsApp pager (on main screen)")
                return WhatsAppScreen.CHAT_LIST
            }

            // NEW: Check for bottom nav "Chats" tab by content description
            val chatsNavNodes = rootNode.findAccessibilityNodeInfosByText("Chats")
            if (chatsNavNodes != null && chatsNavNodes.isNotEmpty()) {
                for (node in chatsNavNodes) {
                    val desc = node.contentDescription?.toString() ?: ""
                    if (desc == "Chats") {
                        chatsNavNodes.forEach { it.recycle() }
                        Log.d(TAG, "Found Chats navigation tab (on main screen)")
                        return WhatsAppScreen.CHAT_LIST
                    }
                }
                chatsNavNodes.forEach { it.recycle() }
            }

            Log.d(TAG, "Not on WhatsApp chat list or inside chat")
            return WhatsAppScreen.UNKNOWN

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting WhatsApp screen", e)
            return WhatsAppScreen.UNKNOWN
        }
    }

    /**
     * Get the name of the current WhatsApp chat/contact if inside a chat.
     * Returns null if not inside a chat.
     */
    private fun getCurrentWhatsAppChatName(rootNode: AccessibilityNodeInfo): String? {
        try {
            val conversationContactName = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            if (conversationContactName != null && conversationContactName.isNotEmpty()) {
                val name = conversationContactName[0].text?.toString()
                conversationContactName.forEach { it.recycle() }
                Log.d(TAG, "Current WhatsApp chat name: $name")
                return name
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current WhatsApp chat name", e)
            return null
        }
    }

    private fun isSearchBarVisible(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Check for search bar elements
            val searchBarIds = listOf(
                "com.whatsapp:id/my_search_bar",
                "com.whatsapp:id/menuitem_search",
                "com.whatsapp:id/search_button",
                "com.whatsapp:id/action_search",
                "com.whatsapp:id/search_input",    // Already in search mode
                "com.whatsapp:id/search_fragment"  // Already in search mode
            )

            for (searchId in searchBarIds) {
                val searchNodes = rootNode.findAccessibilityNodeInfosByViewId(searchId)
                if (searchNodes != null && searchNodes.isNotEmpty()) {
                    searchNodes.forEach { it.recycle() }
                    Log.d(TAG, "Search bar is visible (found $searchId)")
                    return true
                }
            }

            // Also try to find by text
            val searchByText = rootNode.findAccessibilityNodeInfosByText("Search")
            if (searchByText != null && searchByText.isNotEmpty()) {
                searchByText.forEach { it.recycle() }
                Log.d(TAG, "Search bar is visible (found by text)")
                return true
            }

            Log.d(TAG, "Search bar is NOT visible")
            return false

        } catch (e: Exception) {
            Log.e(TAG, "Error checking if search bar is visible", e)
            return false
        }
    }

    private suspend fun scrollToTopOfChatList(accessibilityService: WhizAccessibilityService) {
        try {
            val maxScrollAttempts = 10
            var scrollAttempt = 0

            // Get screen dimensions for gesture coordinates
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Calculate swipe coordinates (swipe down from top to bottom to scroll UP and reveal search bar)
            val centerX = screenWidth / 2f
            val startY = screenHeight * 0.3f  // Start at 30% down the screen (near top)
            val endY = screenHeight * 0.7f    // End at 70% down the screen (swipe downward to scroll up)

            while (scrollAttempt < maxScrollAttempts) {
                scrollAttempt++

                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode == null) {
                    Log.w(TAG, "Could not get root node for scrolling")
                    delay(200)
                    continue
                }

                // Check if search bar is now visible
                if (isSearchBarVisible(rootNode)) {
                    Log.i(TAG, "Search bar is now visible after $scrollAttempt scroll(s)")
                    rootNode.recycle()
                    break
                }

                rootNode.recycle()

                // Perform swipe gesture to scroll up
                Log.d(TAG, "Performing scroll gesture attempt $scrollAttempt (swipe from $startY to $endY)")
                val scrolled = accessibilityService.performScrollGesture(
                    centerX, startY, centerX, endY, duration = 300
                )
                Log.d(TAG, "Scroll attempt $scrollAttempt: $scrolled")

                if (!scrolled) {
                    Log.w(TAG, "Scroll gesture failed on attempt $scrollAttempt")
                    // Continue trying anyway - maybe the next one will work
                }

                delay(400) // Wait for scroll animation to complete
            }

            if (scrollAttempt >= maxScrollAttempts) {
                Log.w(TAG, "Max scroll attempts reached, search bar may still not be visible")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error scrolling to top of chat list", e)
        }
    }
    
    private fun isInsideWhatsAppChat(rootNode: AccessibilityNodeInfo): Boolean {
        return detectWhatsAppScreen(rootNode) == WhatsAppScreen.INSIDE_CHAT
    }
    
    private suspend fun performWhatsAppSearch(rootNode: AccessibilityNodeInfo, searchQuery: String, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for the search button/icon in WhatsApp
            val searchButtons = listOf(
                "com.whatsapp:id/my_search_bar",
                "com.whatsapp:id/menuitem_search",
                "com.whatsapp:id/search_button",
                "com.whatsapp:id/action_search"
            )
            
            // Try to find search button by ID
            for (searchId in searchButtons) {
                val searchNodes = rootNode.findAccessibilityNodeInfosByViewId(searchId)
                if (searchNodes != null && searchNodes.isNotEmpty()) {
                    Log.d(TAG, "Found search button with ID: $searchId")
                    val clicked = accessibilityService.clickNode(searchNodes[0])
                    searchNodes.forEach { it.recycle() }
                    
                    if (clicked) {
                        // Wait for search field to appear
                        val searchFieldAppeared = waitForElement(
                            accessibilityService,
                            viewId = "com.whatsapp:id/search_input",
                            maxWaitMs = 1500
                        )
                        searchFieldAppeared?.recycle()

                        // Now find the search input field and enter text
                        val searchRootNode = accessibilityService.getCurrentRootNode()
                        if (searchRootNode != null) {
                            val searchFieldEntered = enterSearchText(searchRootNode, searchQuery)
                            searchRootNode.recycle()
                            return searchFieldEntered
                        }
                    }
                }
            }
            
            // Alternative: Look for search by content description
            val searchByDesc = rootNode.findAccessibilityNodeInfosByText("Search")
            if (searchByDesc != null && searchByDesc.isNotEmpty()) {
                for (node in searchByDesc) {
                    val clickableNode = if (node.isClickable) node else findClickableParent(node)
                    if (clickableNode != null) {
                        Log.d(TAG, "Found search button by text/description")
                        val clicked = accessibilityService.clickNode(clickableNode)
                        
                        if (clickableNode != node) {
                            clickableNode.recycle()
                        }
                        
                        if (clicked) {
                            searchByDesc.forEach { it.recycle() }
                            // Wait for search field to appear
                            val searchFieldAppeared = waitForElement(
                                accessibilityService,
                                viewId = "com.whatsapp:id/search_input",
                                maxWaitMs = 1500
                            )
                            searchFieldAppeared?.recycle()

                            // Enter search text
                            val searchRootNode = accessibilityService.getCurrentRootNode()
                            if (searchRootNode != null) {
                                val searchFieldEntered = enterSearchText(searchRootNode, searchQuery)
                                searchRootNode.recycle()
                                return searchFieldEntered
                            }
                        }
                    }
                }
                searchByDesc.forEach { it.recycle() }
            }
            
            Log.w(TAG, "Could not find search button in WhatsApp")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error performing WhatsApp search", e)
            return false
        }
    }
    
    private suspend fun updateSearchText(rootNode: AccessibilityNodeInfo, searchQuery: String): Boolean {
        try {
            // Find the search input field that's already active
            val searchFields = listOf(
                "com.whatsapp:id/search_input",
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_edit_text"
            )
            
            for (searchFieldId in searchFields) {
                val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId(searchFieldId)
                if (searchFieldNodes != null && searchFieldNodes.isNotEmpty()) {
                    val searchField = searchFieldNodes[0]
                    
                    // Clear existing text first
                    val clearBundle = Bundle()
                    clearBundle.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        ""
                    )
                    searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
                    
                    // Small delay after clearing to ensure UI updates
                    delay(100)
                    
                    // Set the new search text
                    val setBundle = Bundle()
                    setBundle.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        searchQuery
                    )
                    val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setBundle)
                    
                    searchFieldNodes.forEach { it.recycle() }
                    
                    if (textSet) {
                        Log.d(TAG, "Successfully updated search text to: $searchQuery")
                        return true
                    }
                }
            }
            
            // If we couldn't find by ID, try any EditText
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, editTextNodes)
            
            if (editTextNodes.isNotEmpty()) {
                val searchField = editTextNodes[0]
                
                // Clear and set new text
                val clearBundle = Bundle()
                clearBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
                searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
                
                // Small delay after clearing to ensure UI updates
                delay(100)
                
                val setBundle = Bundle()
                setBundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    searchQuery
                )
                val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setBundle)
                
                editTextNodes.forEach { it.recycle() }
                
                if (textSet) {
                    Log.d(TAG, "Successfully updated search text in EditText: $searchQuery")
                    return true
                }
            }
            
            Log.w(TAG, "Could not update search text - no search field found")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating search text", e)
            return false
        }
    }
    
    private suspend fun enterSearchText(rootNode: AccessibilityNodeInfo, searchQuery: String): Boolean {
        try {
            // Find the search input field
            val searchFields = listOf(
                "com.whatsapp:id/search_input",
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_edit_text"
            )
            
            // Try to find search field by ID
            for (searchFieldId in searchFields) {
                val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId(searchFieldId)
                if (searchFieldNodes != null && searchFieldNodes.isNotEmpty()) {
                    val searchField = searchFieldNodes[0]
                    
                    // Set the search text
                    val bundle = Bundle()
                    bundle.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        searchQuery
                    )
                    val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    
                    searchFieldNodes.forEach { it.recycle() }
                    
                    if (textSet) {
                        Log.d(TAG, "Successfully entered search text: $searchQuery")
                        return true
                    }
                }
            }
            
            // Alternative: Find any EditText that might be the search field
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, editTextNodes)
            
            if (editTextNodes.isNotEmpty()) {
                // Use the first EditText we find (likely the search field if search was just opened)
                val searchField = editTextNodes[0]
                
                val bundle = Bundle()
                bundle.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    searchQuery
                )
                val textSet = searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                
                editTextNodes.forEach { it.recycle() }
                
                if (textSet) {
                    Log.d(TAG, "Successfully entered search text in EditText: $searchQuery")
                    return true
                }
            }
            
            Log.w(TAG, "Could not find search input field")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error entering search text", e)
            return false
        }
    }
    
    /**
     * Dump the UI hierarchy to a file for debugging and upload to server.
     * Saves locally to /sdcard/Download/whiz_ui_dump_<timestamp>.txt
     * Also uploads to server asynchronously (fire-and-forget).
     */
    internal fun dumpUIHierarchy(rootNode: AccessibilityNodeInfo, reason: String, errorMessage: String? = null) {
        try {
            val timestamp = System.currentTimeMillis()
            val fileName = "whiz_ui_dump_${reason}_$timestamp.txt"
            val file = java.io.File("/sdcard/Download", fileName)
            val packageName = rootNode.packageName?.toString()

            val sb = StringBuilder()
            sb.appendLine("=== UI Dump: $reason ===")
            sb.appendLine("Timestamp: $timestamp")
            sb.appendLine("Package: $packageName")
            sb.appendLine("")
            sb.appendLine("=== Node Tree ===")
            dumpNodeRecursive(rootNode, sb, 0)

            val uiHierarchy = sb.toString()

            // Save locally
            file.writeText(uiHierarchy)
            Log.i(TAG, "📋 UI dump saved to: ${file.absolutePath}")

            // Take screenshot, then upload with it (or without if screenshot fails)
            WhizAccessibilityService.takeScreenshotAsync { bitmap ->
                val screenshotBase64 = bitmap?.let {
                    try {
                        val softwareBitmap = it.copy(Bitmap.Config.ARGB_8888, false)
                        val stream = ByteArrayOutputStream()
                        softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        softwareBitmap.recycle()
                        it.recycle()
                        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to encode screenshot for UI dump", e)
                        null
                    }
                }

                val screenAgentContext = if (screenshotBase64 != null) {
                    mapOf("screenshot_base64" to screenshotBase64)
                } else {
                    null
                }

                uploadUiDumpToServer(
                    dumpReason = reason,
                    errorMessage = errorMessage,
                    uiHierarchy = uiHierarchy,
                    packageName = packageName,
                    screenAgentContext = screenAgentContext
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump UI hierarchy", e)
        }
    }

    /**
     * Upload UI dump to server asynchronously. Fire-and-forget - does not block.
     */
    private fun uploadUiDumpToServer(
        dumpReason: String,
        errorMessage: String?,
        uiHierarchy: String?,
        packageName: String?,
        screenAgentContext: Map<String, Any>? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get screen dimensions
                val displayMetrics = context.resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                val request = ApiService.UiDumpCreate(
                    dumpReason = dumpReason,
                    errorMessage = errorMessage,
                    uiHierarchy = uiHierarchy,
                    packageName = packageName,
                    deviceModel = Build.MODEL,
                    deviceManufacturer = Build.MANUFACTURER,
                    androidVersion = Build.VERSION.SDK_INT.toString(),
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    appVersion = BuildConfig.VERSION_NAME,
                    conversationId = null, // Could be passed in if available
                    recentActions = getRecentActionsCopy(),
                    screenAgentContext = screenAgentContext
                )

                val response = apiService.uploadUiDump(request)
                Log.i(TAG, "📤 UI dump uploaded to server: id=${response.id}")
            } catch (e: Exception) {
                // Fire-and-forget - just log the error
                Log.w(TAG, "Failed to upload UI dump to server (non-fatal): ${e.message}")
            }
        }
    }

    /**
     * Log a screen agent error without UI dump. For failures where UI context isn't useful
     * (app launch failures, accessibility service issues, generic exceptions).
     */
    private fun logScreenAgentError(reason: String, errorMessage: String, packageName: String? = null) {
        uploadUiDumpToServer(
            dumpReason = reason,
            errorMessage = errorMessage,
            uiHierarchy = null,
            packageName = packageName
        )
    }

    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        com.example.whiz.accessibility.AccessibilityDumpUtil.dumpNodeRecursive(node, sb, depth)
    }

    private fun logCurrentScreen(rootNode: AccessibilityNodeInfo) {
        try {
            // Log the package name to understand which app/screen we're on
            val packageName = rootNode.packageName?.toString() ?: "Unknown"
            Log.d(TAG, "Current package: $packageName")
            
            // Try to find any text that might indicate what screen we're on
            val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("android:id/title")
            if (titleNodes != null && titleNodes.isNotEmpty()) {
                val title = titleNodes[0].text?.toString() ?: titleNodes[0].contentDescription?.toString()
                Log.d(TAG, "Screen title: $title")
                titleNodes.forEach { it.recycle() }
            }
            
            // Look for WhatsApp-specific identifiers
            val actionBarTitle = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            if (actionBarTitle != null && actionBarTitle.isNotEmpty()) {
                val name = actionBarTitle[0].text?.toString()
                Log.d(TAG, "WhatsApp conversation with: $name")
                actionBarTitle.forEach { it.recycle() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging current screen", e)
        }
    }
    
    private fun clickMessageButton(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            Log.d(TAG, "Looking for Message button in profile view...")
            
            // Look for "Message" button by text
            val messageButtons = listOf("Message", "message", "Chat", "chat", "Send message", "send message")
            
            for (buttonText in messageButtons) {
                val buttonNodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
                if (buttonNodes != null && buttonNodes.isNotEmpty()) {
                    Log.d(TAG, "Found button with text: $buttonText")
                    
                    for (buttonNode in buttonNodes) {
                        // Check if it's actually a button or has a clickable parent
                        val clickableNode = if (buttonNode.isClickable) {
                            buttonNode
                        } else {
                            findClickableParent(buttonNode)
                        }
                        
                        if (clickableNode != null) {
                            val clicked = accessibilityService.clickNode(clickableNode)
                            Log.d(TAG, "Clicked Message button: $clicked")
                            
                            if (clickableNode != buttonNode) {
                                clickableNode.recycle()
                            }
                            
                            if (clicked) {
                                buttonNodes.forEach { it.recycle() }
                                return true
                            }
                        }
                    }
                    buttonNodes.forEach { it.recycle() }
                }
            }
            
            // Also try to find by view ID (WhatsApp specific)
            val messageButtonById = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_action_btn")
            if (messageButtonById != null && messageButtonById.isNotEmpty()) {
                Log.d(TAG, "Found Message button by ID")
                val clicked = accessibilityService.clickNode(messageButtonById[0])
                messageButtonById.forEach { it.recycle() }
                if (clicked) return true
            }
            
            // Try to find any ImageButton that might be the message button
            findMessageButtonByType(rootNode, accessibilityService)?.let { success ->
                if (success) return true
            }
            
            Log.d(TAG, "Could not find Message button")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking message button", e)
            return false
        }
    }
    
    private fun findMessageButtonByType(node: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean? {
        try {
            // Check if this is an ImageButton with message-related content description
            if (node.className == "android.widget.ImageButton") {
                val contentDesc = node.contentDescription?.toString()?.lowercase()
                if (contentDesc != null && (contentDesc.contains("message") || contentDesc.contains("chat"))) {
                    Log.d(TAG, "Found ImageButton with content description: ${node.contentDescription}")
                    if (node.isClickable) {
                        return accessibilityService.clickNode(node)
                    }
                }
            }
            
            // Search children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val result = findMessageButtonByType(child, accessibilityService)
                    child.recycle()
                    if (result == true) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding message button by type", e)
        }
        
        return null
    }
    
    private fun findChatNodes(node: AccessibilityNodeInfo, chatName: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val normalizedChatName = normalizeChatName(chatName)

        Log.d(TAG, "Searching for chat: '$chatName' (normalized: '$normalizedChatName')")

        // Search by text - exact match first
        val nodesByText = node.findAccessibilityNodeInfosByText(chatName)
        if (nodesByText != null && nodesByText.isNotEmpty()) {
            Log.d(TAG, "Found ${nodesByText.size} nodes with text matching '$chatName'")
            // Filter to ensure we're getting exact or very close matches
            for (node in nodesByText) {
                val nodeText = node.text?.toString()?.lowercase()?.trim()
                val nodeDesc = node.contentDescription?.toString()?.lowercase()?.trim()

                // Skip EditText nodes - these are input fields, not chat items
                if (node.className == "android.widget.EditText") {
                    Log.d(TAG, "Skipping EditText node: text='${node.text}', desc='${node.contentDescription}'")
                    node.recycle()
                    continue
                }

                // Normalize the node text and description for comparison
                val cleanedNodeText = nodeText?.let { normalizeChatName(it) }
                val cleanedNodeDesc = nodeDesc?.let { normalizeChatName(it) }
                val cleanedChatName = normalizedChatName

                // Handle truncated names with ellipsis (…)
                val isEllipsisMatchText = cleanedNodeText?.let { text ->
                    if (text.contains("…")) {
                        val prefix = text.substringBefore("…")
                        cleanedChatName.startsWith(prefix) && prefix.length >= 5
                    } else false
                } ?: false

                val isEllipsisMatchDesc = cleanedNodeDesc?.let { desc ->
                    if (desc.contains("…")) {
                        val prefix = desc.substringBefore("…")
                        cleanedChatName.startsWith(prefix) && prefix.length >= 5
                    } else false
                } ?: false

                // Check if this is actually a match for the chat name
                if ((cleanedNodeText != null && (cleanedNodeText == cleanedChatName || cleanedNodeText.startsWith(cleanedChatName))) ||
                    (cleanedNodeDesc != null && (cleanedNodeDesc == cleanedChatName || cleanedNodeDesc.startsWith(cleanedChatName))) ||
                    isEllipsisMatchText || isEllipsisMatchDesc) {
                    Log.d(TAG, "Confirmed match: text='${node.text}', desc='${node.contentDescription}'")
                    results.add(node)
                } else {
                    // Not a real match, recycle it
                    Log.d(TAG, "False match filtered out: text='${node.text}', desc='${node.contentDescription}'")
                    node.recycle()
                }
            }
        }
        
        // Also search for nodes in the chat list specifically (by ViewId)
        val chatListItems = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name")
        if (chatListItems != null && chatListItems.isNotEmpty()) {
            Log.d(TAG, "Found ${chatListItems.size} chat list items")
            for (item in chatListItems) {
                val itemText = item.text?.toString()
                if (itemText != null) {
                    // Normalize the item text for comparison
                    val cleanedItemText = normalizeChatName(itemText)
                    val cleanedChatName = normalizedChatName

                    Log.d(TAG, "Checking chat list item: original='$itemText', cleaned='$cleanedItemText' vs '$cleanedChatName'")

                    // Handle truncated names with ellipsis (…)
                    val isEllipsisMatch = if (cleanedItemText.contains("…")) {
                        // Remove ellipsis and everything after it, then check if search term starts with this prefix
                        val prefix = cleanedItemText.substringBefore("…")
                        cleanedChatName.startsWith(prefix) && prefix.length >= 5  // At least 5 chars to avoid false matches
                    } else {
                        false
                    }

                    // Be more strict: require exact match or that the chat name starts with our search
                    if (cleanedItemText == cleanedChatName ||
                        cleanedItemText.startsWith(cleanedChatName) ||
                        cleanedItemText.contains(cleanedChatName) ||
                        isEllipsisMatch) {
                        Log.d(TAG, "Found chat list match: $itemText")
                        results.add(AccessibilityNodeInfo.obtain(item))
                    }
                }
            }
            chatListItems.forEach {
                if (!results.contains(it)) it.recycle()
            }
        }
        
        // If no results yet, do a recursive search for partial matches
        if (results.isEmpty()) {
            Log.d(TAG, "No exact matches found, searching recursively...")
            searchNodeRecursively(node, normalizedChatName, results, strictMatch = false)
        }

        // If we have multiple results, prioritize ones from "Chats" section over "Groups in common"
        if (results.size > 1) {
            val chatsResults = results.filter { resultNode ->
                isInChatsSection(resultNode)
            }

            if (chatsResults.isNotEmpty()) {
                Log.d(TAG, "Found ${chatsResults.size} results in 'Chats' section, prioritizing these over ${results.size - chatsResults.size} other results")
                // Recycle the non-chats results
                results.filterNot { chatsResults.contains(it) }.forEach { it.recycle() }
                results.clear()
                results.addAll(chatsResults)
            }
        }

        Log.d(TAG, "Total chat nodes found: ${results.size}")
        return results
    }

    /**
     * Check if a node is in the "Chats" section (not "Groups in common")
     * by checking if it appears before any "Groups in common" header
     */
    private fun isInChatsSection(node: AccessibilityNodeInfo): Boolean {
        try {
            // Get the vertical position of the node
            val nodeRect = android.graphics.Rect()
            node.getBoundsInScreen(nodeRect)

            // Get the root to search for section headers
            var current: AccessibilityNodeInfo? = node
            var root: AccessibilityNodeInfo? = null
            while (current != null) {
                val parent = current.parent
                if (parent == null) {
                    root = current
                    break
                }
                if (current != node) {
                    current.recycle()
                }
                current = parent
            }

            if (root != null) {
                // Look for "Groups in common" text
                val groupsHeaderNodes = root.findAccessibilityNodeInfosByText("Groups in common")
                if (groupsHeaderNodes != null && groupsHeaderNodes.isNotEmpty()) {
                    for (headerNode in groupsHeaderNodes) {
                        val headerRect = android.graphics.Rect()
                        headerNode.getBoundsInScreen(headerRect)

                        // If our node is below the "Groups in common" header, it's in that section
                        if (nodeRect.top > headerRect.bottom) {
                            groupsHeaderNodes.forEach { it.recycle() }
                            if (root != node) {
                                root.recycle()
                            }
                            Log.d(TAG, "Node is in 'Groups in common' section (below header)")
                            return false
                        }
                    }
                    groupsHeaderNodes.forEach { it.recycle() }
                }

                if (root != node) {
                    root.recycle()
                }
            }

            // If we didn't find a "Groups in common" header above this node, assume it's in Chats
            Log.d(TAG, "Node is in 'Chats' section (no 'Groups in common' header found above it)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if node is in Chats section", e)
            return true // Default to true if we can't determine
        }
    }
    
    private fun searchNodeRecursively(
        node: AccessibilityNodeInfo,
        searchText: String,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
        strictMatch: Boolean = true
    ) {
        try {
            // Skip EditText nodes - these are input fields, not chat items
            if (node.className == "android.widget.EditText") {
                return
            }

            // Check current node's text
            val nodeText = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()

            // Normalize the node text and description for comparison
            val cleanedNodeText = nodeText?.let { normalizeChatName(it) }
            val cleanedSearchText = searchText  // searchText is already normalized
            val cleanedContentDesc = contentDesc?.let { normalizeChatName(it) }

            // Handle truncated names with ellipsis (…)
            val isEllipsisMatchText = cleanedNodeText?.let { text ->
                if (text.contains("…")) {
                    val prefix = text.substringBefore("…")
                    cleanedSearchText.startsWith(prefix) && prefix.length >= 5
                } else false
            } ?: false

            val isEllipsisMatchDesc = cleanedContentDesc?.let { desc ->
                if (desc.contains("…")) {
                    val prefix = desc.substringBefore("…")
                    cleanedSearchText.startsWith(prefix) && prefix.length >= 5
                } else false
            } ?: false

            val matches = if (strictMatch) {
                // For strict matching, require exact match or starts with
                ((cleanedNodeText != null && (cleanedNodeText == cleanedSearchText || cleanedNodeText.startsWith(cleanedSearchText))) ||
                 (cleanedContentDesc != null && (cleanedContentDesc == cleanedSearchText || cleanedContentDesc.startsWith(cleanedSearchText))) ||
                 isEllipsisMatchText || isEllipsisMatchDesc)
            } else {
                // For non-strict, allow contains
                ((cleanedNodeText != null && cleanedNodeText.contains(cleanedSearchText)) ||
                 (cleanedContentDesc != null && cleanedContentDesc.contains(cleanedSearchText)) ||
                 isEllipsisMatchText || isEllipsisMatchDesc)
            }

            if (matches) {
                val info = "Found match at depth $depth: text='$nodeText', desc='$contentDesc', class=${node.className}, clickable=${node.isClickable}"
                Log.d(TAG, info)
                results.add(AccessibilityNodeInfo.obtain(node))
            }
            
            // Search children (limit depth to avoid excessive recursion)
            if (depth < 10) {
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        searchNodeRecursively(child, searchText, results, depth + 1, strictMatch)
                        child.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching node recursively at depth $depth", e)
        }
    }
    
    /**
     * Find the first clickable search result in WhatsApp search results.
     * Used as a fallback when text matching fails (e.g., group chats where
     * the search query differs from the group's display name).
     */
    private fun findFirstSearchResult(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            // Find the search input field to determine where results start
            val searchInputIds = listOf(
                "com.whatsapp:id/search_input",
                "com.whatsapp:id/search_src_text"
            )
            var searchInputBottom = 0
            for (id in searchInputIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (nodes != null && nodes.isNotEmpty()) {
                    val rect = android.graphics.Rect()
                    nodes[0].getBoundsInScreen(rect)
                    searchInputBottom = rect.bottom
                    nodes.forEach { it.recycle() }
                    break
                }
            }

            if (searchInputBottom == 0) {
                Log.d(TAG, "findFirstSearchResult: Could not find search input field")
                return null
            }

            val screenWidth = context.resources.displayMetrics.widthPixels
            val minWidth = (screenWidth * 0.4).toInt()

            // Section headers to skip
            val sectionHeaders = setOf("chats", "messages", "groups in common", "contacts", "more")

            // Collect all clickable nodes
            val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
            findClickableChildren(rootNode, clickableNodes)

            // Filter and find the topmost valid result
            var bestNode: AccessibilityNodeInfo? = null
            var bestTop = Int.MAX_VALUE

            for (node in clickableNodes) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                // Must be below the search input
                if (rect.top <= searchInputBottom) {
                    node.recycle()
                    continue
                }

                // Must span a significant portion of screen width (skip small buttons)
                if (rect.width() < minWidth) {
                    node.recycle()
                    continue
                }

                // Skip section headers
                val nodeText = getNodeTextRecursive(node).lowercase().trim()
                if (nodeText.isNotEmpty() && nodeText in sectionHeaders) {
                    node.recycle()
                    continue
                }

                // Track the topmost result
                if (rect.top < bestTop) {
                    bestNode?.recycle()
                    bestNode = node
                    bestTop = rect.top
                } else {
                    node.recycle()
                }
            }

            if (bestNode != null) {
                Log.i(TAG, "findFirstSearchResult: Found first result at y=$bestTop")
            } else {
                Log.d(TAG, "findFirstSearchResult: No valid search results found below search input")
            }

            return bestNode
        } catch (e: Exception) {
            Log.e(TAG, "Error in findFirstSearchResult", e)
            return null
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo, skipProfilePictures: Boolean = false): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)

        while (current != null) {
            if (current.isClickable) {
                // If we should skip profile pictures, check if this is a profile picture node
                if (skipProfilePictures && isProfilePictureNode(current)) {
                    Log.d(TAG, "Skipping profile picture clickable node: viewId=${current.viewIdResourceName}, desc=${current.contentDescription}")
                    val parent = current.parent
                    current.recycle()
                    current = parent
                    continue
                }
                return current
            }

            val parent = current.parent
            current.recycle()
            current = parent
        }

        return null
    }

    /**
     * Check if a node is a profile picture that we should skip clicking on.
     * Clicking on profile pictures in WhatsApp opens QuickContact popup instead of the chat.
     */
    private fun isProfilePictureNode(node: AccessibilityNodeInfo): Boolean {
        // Check resource ID for profile picture indicators
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("picture") || viewId.contains("photo") || viewId.contains("avatar") || viewId.contains("contact_photo") || viewId.contains("contact_selector")) {
            return true
        }

        // Check content description for profile picture indicators
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (contentDesc.contains("profile photo") || contentDesc.contains("profile picture") || contentDesc.contains("contact photo")) {
            return true
        }

        // Check if this is a small square node (likely a profile picture)
        // Profile pictures are typically small and square-ish
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val width = rect.width()
        val height = rect.height()

        // Profile pictures are typically 100-200px and roughly square
        val isSmallSquare = width in 80..250 && height in 80..250 &&
                           Math.abs(width - height) < 50

        // If it's a small square ImageView, it's likely a profile picture
        if (isSmallSquare && node.className == "android.widget.ImageView") {
            Log.d(TAG, "Detected likely profile picture by size/shape: ${width}x${height}")
            return true
        }

        return false
    }
    
    private fun findEditTextNodes(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        try {
            if (node.className == "android.widget.EditText") {
                results.add(AccessibilityNodeInfo.obtain(node))
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findEditTextNodes(child, results)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding EditText nodes", e)
        }
    }

    private fun findClickableChildren(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        try {
            if (node.isClickable) {
                results.add(AccessibilityNodeInfo.obtain(node))
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findClickableChildren(child, results)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding clickable children", e)
        }
    }

    private fun clickSendButton(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Look for send button by content description
            val sendDescriptions = listOf("Send", "send", "Send button")
            
            for (desc in sendDescriptions) {
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
                if (sendNodes != null && sendNodes.isNotEmpty()) {
                    val success = sendNodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    sendNodes.forEach { it.recycle() }
                    if (success) return true
                }
            }
            
            // Try to find by traversing the tree
            return findAndClickSendButton(rootNode)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking send button", e)
            return false
        }
    }
    
    private fun findAndClickSendButton(node: AccessibilityNodeInfo): Boolean {
        try {
            // Check if this node is a send button
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            if (contentDesc != null && contentDesc.contains("send") && node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            
            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    if (findAndClickSendButton(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findAndClickSendButton", e)
        }
        
        return false
    }
    
    // ========== Fitbit Functions ==========

    /**
     * Enum for detecting current Fitbit screen state
     */
    private enum class FitbitScreen {
        TODAY_HOME,           // Main "Today" tab with todayLazyGrid
        FOOD_DETAIL,          // Food detail page (with or without "More options" dropdown open)
        ADD_QUICK_CALORIES,   // The Add Quick Calories entry screen
        UNKNOWN
    }

    /**
     * Detect the current Fitbit screen state from the root node
     */
    private fun detectFitbitScreen(rootNode: AccessibilityNodeInfo): FitbitScreen {
        try {
            // Check for Add Quick Calories screen (edit_calories field)
            val editCalories = rootNode.findAccessibilityNodeInfosByViewId(
                "com.fitbit.FitbitMobile:id/edit_calories"
            )
            if (editCalories != null && editCalories.isNotEmpty()) {
                editCalories.forEach { it.recycle() }
                return FitbitScreen.ADD_QUICK_CALORIES
            }
            editCalories?.forEach { it.recycle() }

            // Check for Food detail page — either the dropdown is open (has "Add Quick Calories" text)
            // or it's the base page (has "More options" button and "Food" title)
            val addQuickCalNodes = rootNode.findAccessibilityNodeInfosByText("Add Quick Calories")
            if (addQuickCalNodes != null && addQuickCalNodes.isNotEmpty()) {
                addQuickCalNodes.forEach { it.recycle() }
                return FitbitScreen.FOOD_DETAIL
            }
            addQuickCalNodes?.forEach { it.recycle() }

            val foodTitleNodes = rootNode.findAccessibilityNodeInfosByText("Food")
            if (foodTitleNodes != null && foodTitleNodes.isNotEmpty()) {
                // Make sure we're on the detail page, not just seeing "Food" on the Today screen
                // Look for "More options" content description which is on the Food detail page
                val moreOptionsNodes = findNodesByContentDescription(rootNode, "More options")
                val isDetailPage = moreOptionsNodes.isNotEmpty()
                moreOptionsNodes.forEach { it.recycle() }
                foodTitleNodes.forEach { it.recycle() }
                if (isDetailPage) {
                    return FitbitScreen.FOOD_DETAIL
                }
            }
            foodTitleNodes?.forEach { it.recycle() }

            // Check for Today home screen — look for the "Today" title text
            val currentPackage = rootNode.packageName?.toString()
            if (currentPackage == "com.fitbit.FitbitMobile") {
                val todayTitleNode = findNodeByText(rootNode, "Today")
                if (todayTitleNode != null) {
                    todayTitleNode.recycle()
                    // Make sure we're actually on the Today home screen, not a sub-detail page
                    // (e.g. the Weight detail screen also shows "Today" as a date label for a
                    // weight entry but has a "Go back" back-navigation button).
                    val goBackNodes = findNodesByContentDescription(rootNode, "Go back")
                    val isSubDetailPage = goBackNodes.isNotEmpty()
                    goBackNodes.forEach { it.recycle() }
                    if (!isSubDetailPage) {
                        return FitbitScreen.TODAY_HOME
                    }
                    // Sub-detail page — fall through to UNKNOWN so the BACK-pressing logic kicks in
                }
                // Not on Today home — fall through to UNKNOWN so the BACK-pressing logic kicks in
            }

            return FitbitScreen.UNKNOWN
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting Fitbit screen", e)
            return FitbitScreen.UNKNOWN
        }
    }

    /**
     * Find nodes by content description (recursive search)
     */
    private fun findNodesByContentDescription(
        rootNode: AccessibilityNodeInfo,
        contentDesc: String,
        depth: Int = 0
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (depth > 20) return results

        try {
            val nodeContentDesc = rootNode.contentDescription?.toString()
            if (nodeContentDesc != null && nodeContentDesc == contentDesc) {
                results.add(rootNode)
            }

            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChild(i) ?: continue
                results.addAll(findNodesByContentDescription(child, contentDesc, depth + 1))
                if (results.isEmpty()) {
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findNodesByContentDescription", e)
        }
        return results
    }

    /**
     * Add quick calories to Fitbit food log.
     * Handles navigation from any Fitbit screen by pressing BACK to reach a known state.
     */
    suspend fun addFitbitQuickCalories(calories: Int): FitbitResult {
        Log.i(TAG, "addFitbitQuickCalories called with calories=$calories")
        trackAction("addFitbitQuickCalories: $calories")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
                ?: return FitbitResult(
                    success = false,
                    error = "Accessibility service not enabled. Please enable it in settings."
                )

            // Launch Fitbit app
            val launchResult = launchApp("Fitbit")
            if (!launchResult.success) {
                return FitbitResult(
                    success = false,
                    error = "Failed to launch Fitbit: ${launchResult.error}"
                )
            }

            // Wait for Fitbit to be in foreground
            val appReady = waitForAppReady(
                accessibilityService = accessibilityService,
                packageName = "com.fitbit.FitbitMobile",
                maxWaitMs = 5000
            )
            if (!appReady) {
                return FitbitResult(
                    success = false,
                    error = "Fitbit did not become ready in time"
                )
            }

            // Wait for a recognizable Fitbit screen to appear (handles loading/splash)
            var currentScreen = FitbitScreen.UNKNOWN
            val initialDetected = waitForCondition(maxWaitMs = 5000) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    currentScreen = detectFitbitScreen(rootNode)
                    rootNode.recycle()
                    currentScreen != FitbitScreen.UNKNOWN
                } else false
            }

            // If we detected a known screen, use it; otherwise press BACK to navigate to one
            if (!initialDetected) {
                for (attempt in 0 until 5) {
                    accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

                    val found = waitForCondition(maxWaitMs = 2000) {
                        val rootNode = accessibilityService.getCurrentRootNode()
                        if (rootNode != null) {
                            currentScreen = detectFitbitScreen(rootNode)
                            rootNode.recycle()
                            currentScreen != FitbitScreen.UNKNOWN
                        } else false
                    }

                    Log.i(TAG, "Fitbit screen detection after BACK $attempt: $currentScreen")
                    if (found) break
                }
            } else {
                Log.i(TAG, "Fitbit screen detected on launch: $currentScreen")
            }

            // Now navigate based on current screen
            when (currentScreen) {
                FitbitScreen.ADD_QUICK_CALORIES -> {
                    // Already on the Add Quick Calories screen, go directly to entering calories
                    return enterCaloriesAndLog(accessibilityService, calories)
                }
                FitbitScreen.FOOD_DETAIL -> {
                    // On Food detail page (with or without dropdown open), tap "More options" → "Add Quick Calories"
                    return tapMoreOptionsAndLog(accessibilityService, calories)
                }
                FitbitScreen.TODAY_HOME -> {
                    // On Today home, find and tap the Food tile
                    return tapFoodTileAndLog(accessibilityService, calories)
                }
                FitbitScreen.UNKNOWN -> {
                    return FitbitResult(
                        success = false,
                        error = "Could not navigate to a known Fitbit screen after multiple attempts"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in addFitbitQuickCalories", e)
            return FitbitResult(
                success = false,
                error = "Failed to add quick calories: ${e.message}"
            )
        }
    }

    /**
     * Starting from Today home screen, find and tap the Food tile then continue
     */
    /**
     * Recursively find a node by exact text match.
     * Used because findAccessibilityNodeInfosByText doesn't work with Compose nodes.
     */
    private fun findNodeByText(node: AccessibilityNodeInfo, text: String, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 25) return null

        if (node.text?.toString() == text) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByText(child, text, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    /**
     * Collect all visible text nodes for scroll-change detection.
     */
    private fun collectVisibleTexts(node: AccessibilityNodeInfo, depth: Int = 0): Set<String> {
        val texts = mutableSetOf<String>()
        if (depth > 25) return texts

        node.text?.toString()?.let { if (it.isNotEmpty()) texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            texts.addAll(collectVisibleTexts(child, depth + 1))
            child.recycle()
        }
        return texts
    }

    private suspend fun tapFoodTileAndLog(
        accessibilityService: WhizAccessibilityService,
        calories: Int
    ): FitbitResult {
        Log.i(TAG, "tapFoodTileAndLog: Looking for Food tile on Today screen")

        val displayMetrics = context.resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val screenHeight = displayMetrics.heightPixels

        // Step 1: Scroll to top first (like delete alarm tool)
        Log.i(TAG, "Scrolling to top of Fitbit Today screen")
        for (i in 1..10) {
            accessibilityService.performScrollGesture(
                centerX, screenHeight * 0.3f, centerX, screenHeight * 0.7f, duration = 300
            )
            delay(400)

            // Check if we've reached the top by comparing content before/after another scroll
            val rootBefore = accessibilityService.getCurrentRootNode()
            val textsBefore = if (rootBefore != null) collectVisibleTexts(rootBefore) else emptySet()
            rootBefore?.recycle()

            accessibilityService.performScrollGesture(
                centerX, screenHeight * 0.3f, centerX, screenHeight * 0.7f, duration = 300
            )
            delay(400)

            val rootAfter = accessibilityService.getCurrentRootNode()
            val textsAfter = if (rootAfter != null) collectVisibleTexts(rootAfter) else emptySet()
            rootAfter?.recycle()

            if (textsBefore.isNotEmpty() && textsBefore == textsAfter) {
                Log.i(TAG, "Reached top of Today screen after $i scroll-to-top attempts")
                break
            }
        }

        // Step 2: Scroll down looking for Food tile
        var foodTileFound = false
        val maxScrollAttempts = 20

        for (scrollAttempt in 0..maxScrollAttempts) {
            val rootNode = accessibilityService.getCurrentRootNode() ?: continue

            // Use recursive search since findAccessibilityNodeInfosByText
            // doesn't work with Compose nodes
            val foodNode = findNodeByText(rootNode, "Food")
            if (foodNode != null) {
                Log.i(TAG, "Found 'Food' text node on attempt $scrollAttempt")
                val clickable = findClickableParent(foodNode)
                if (clickable != null) {
                    val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked Food tile: $clicked")
                    foodNode.recycle()
                    rootNode.recycle()
                    if (clicked) {
                        foodTileFound = true
                        break
                    }
                } else {
                    Log.w(TAG, "Found 'Food' text but no clickable parent")
                    foodNode.recycle()
                }
            }

            rootNode.recycle()

            if (foodTileFound) break

            // Scroll down (same distance as delete alarm tool)
            if (scrollAttempt < maxScrollAttempts) {
                Log.d(TAG, "Food tile not found, scrolling down (attempt ${scrollAttempt + 1})")
                accessibilityService.performScrollGesture(
                    centerX, screenHeight * 0.7f, centerX, screenHeight * 0.3f, duration = 300
                )
                delay(400)
            }
        }

        if (!foodTileFound) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_food_tile_not_found", "Could not find Food tile on Fitbit Today screen")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Could not find Food tile on Fitbit Today screen"
            )
        }

        // Wait for Food detail page to load
        val foodPageReady = waitForCondition(maxWaitMs = 5000) {
            val node = accessibilityService.getCurrentRootNode()
            if (node != null) {
                val screen = detectFitbitScreen(node)
                node.recycle()
                screen == FitbitScreen.FOOD_DETAIL
            } else false
        }

        if (!foodPageReady) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_food_detail_not_loaded", "Food detail page did not load after tapping Food tile")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Food detail page did not load after tapping Food tile"
            )
        }

        return tapMoreOptionsAndLog(accessibilityService, calories)
    }

    /**
     * Starting from Food detail page, tap "More options" then continue
     */
    private suspend fun tapMoreOptionsAndLog(
        accessibilityService: WhizAccessibilityService,
        calories: Int
    ): FitbitResult {
        Log.i(TAG, "tapMoreOptionsAndLog: On Food detail page")

        val rootNode = accessibilityService.getCurrentRootNode()
            ?: return FitbitResult(success = false, error = "Could not get screen content")

        // Check if the dropdown is already open (has "Add Quick Calories" text visible)
        val existingDropdown = rootNode.findAccessibilityNodeInfosByText("Add Quick Calories")
        if (existingDropdown != null && existingDropdown.isNotEmpty()) {
            Log.i(TAG, "Dropdown already open, skipping 'More options' tap")
            existingDropdown.forEach { it.recycle() }
            rootNode.recycle()
            return tapAddQuickCaloriesAndLog(accessibilityService, calories)
        }
        existingDropdown?.forEach { it.recycle() }

        // Find and tap "More options" by content description
        val moreOptionsNodes = findNodesByContentDescription(rootNode, "More options")
        if (moreOptionsNodes.isEmpty()) {
            dumpUIHierarchy(rootNode, "fitbit_more_options_not_found", "Could not find 'More options' button on Food detail page")
            rootNode.recycle()
            return FitbitResult(
                success = false,
                error = "Could not find 'More options' button on Food detail page"
            )
        }

        val moreOptionsNode = moreOptionsNodes.first()
        val clickable = if (moreOptionsNode.isClickable) {
            moreOptionsNode
        } else {
            findClickableParent(moreOptionsNode) ?: moreOptionsNode
        }

        val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Clicked More options: $clicked")
        moreOptionsNodes.forEach { it.recycle() }
        rootNode.recycle()

        if (!clicked) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_more_options_tap_failed", "Failed to tap 'More options' button")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Failed to tap 'More options' button"
            )
        }

        // Wait for dropdown to appear (look for "Add Quick Calories" text)
        val dropdownReady = waitForCondition(maxWaitMs = 3000) {
            val node = accessibilityService.getCurrentRootNode()
            if (node != null) {
                val addQuickCalNodes = node.findAccessibilityNodeInfosByText("Add Quick Calories")
                val found = addQuickCalNodes != null && addQuickCalNodes.isNotEmpty()
                addQuickCalNodes?.forEach { it.recycle() }
                node.recycle()
                found
            } else false
        }

        if (!dropdownReady) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_dropdown_not_appeared", "More options dropdown did not appear")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "More options dropdown did not appear"
            )
        }

        return tapAddQuickCaloriesAndLog(accessibilityService, calories)
    }

    /**
     * From the More Options dropdown, tap "Add Quick Calories" then continue
     */
    private suspend fun tapAddQuickCaloriesAndLog(
        accessibilityService: WhizAccessibilityService,
        calories: Int
    ): FitbitResult {
        Log.i(TAG, "tapAddQuickCaloriesAndLog: Looking for 'Add Quick Calories' option")

        val rootNode = accessibilityService.getCurrentRootNode()
            ?: return FitbitResult(success = false, error = "Could not get screen content")

        val addQuickCalNodes = rootNode.findAccessibilityNodeInfosByText("Add Quick Calories")
        if (addQuickCalNodes == null || addQuickCalNodes.isEmpty()) {
            dumpUIHierarchy(rootNode, "fitbit_add_quick_cal_not_found", "Could not find 'Add Quick Calories' in dropdown menu")
            rootNode.recycle()
            return FitbitResult(
                success = false,
                error = "Could not find 'Add Quick Calories' in dropdown menu"
            )
        }

        val targetNode = addQuickCalNodes.first()
        val clickable = if (targetNode.isClickable) {
            targetNode
        } else {
            findClickableParent(targetNode) ?: targetNode
        }

        val clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Clicked 'Add Quick Calories': $clicked")
        addQuickCalNodes.forEach { it.recycle() }
        rootNode.recycle()

        if (!clicked) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_add_quick_cal_tap_failed", "Failed to tap 'Add Quick Calories'")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Failed to tap 'Add Quick Calories'"
            )
        }

        // Wait for Add Quick Calories screen
        val screenReady = waitForCondition(maxWaitMs = 5000) {
            val node = accessibilityService.getCurrentRootNode()
            if (node != null) {
                val screen = detectFitbitScreen(node)
                node.recycle()
                screen == FitbitScreen.ADD_QUICK_CALORIES
            } else false
        }

        if (!screenReady) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_add_quick_cal_screen_not_loaded", "Add Quick Calories screen did not appear")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Add Quick Calories screen did not appear"
            )
        }

        return enterCaloriesAndLog(accessibilityService, calories)
    }

    /**
     * On the Add Quick Calories screen, enter calories and tap LOG THIS
     */
    private suspend fun enterCaloriesAndLog(
        accessibilityService: WhizAccessibilityService,
        calories: Int
    ): FitbitResult {
        Log.i(TAG, "enterCaloriesAndLog: Entering $calories calories")

        val rootNode = accessibilityService.getCurrentRootNode()
            ?: return FitbitResult(success = false, error = "Could not get screen content")

        // Find the edit_calories field
        val editCaloriesNodes = rootNode.findAccessibilityNodeInfosByViewId(
            "com.fitbit.FitbitMobile:id/edit_calories"
        )
        if (editCaloriesNodes == null || editCaloriesNodes.isEmpty()) {
            dumpUIHierarchy(rootNode, "fitbit_calories_input_not_found", "Could not find calories input field")
            rootNode.recycle()
            return FitbitResult(
                success = false,
                error = "Could not find calories input field"
            )
        }

        val editField = editCaloriesNodes.first()

        // Focus the field and set text
        editField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, calories.toString())
        }
        val textSet = editField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.i(TAG, "Set calories text to $calories: $textSet")

        editCaloriesNodes.forEach { it.recycle() }
        rootNode.recycle()

        if (!textSet) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_calories_entry_failed", "Failed to enter calorie amount")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Failed to enter calorie amount"
            )
        }

        // Find and tap "LOG THIS" button
        val logRootNode = accessibilityService.getCurrentRootNode()
            ?: return FitbitResult(success = false, error = "Could not get screen content after entering calories")

        val logBtnNodes = logRootNode.findAccessibilityNodeInfosByViewId(
            "com.fitbit.FitbitMobile:id/log_this_btn"
        )

        // If resource ID doesn't work, fall back to text search
        val logButton = if (logBtnNodes != null && logBtnNodes.isNotEmpty()) {
            logBtnNodes.first()
        } else {
            logBtnNodes?.forEach { it.recycle() }
            val textNodes = logRootNode.findAccessibilityNodeInfosByText("LOG THIS")
            textNodes?.firstOrNull()
        }

        if (logButton == null) {
            dumpUIHierarchy(logRootNode, "fitbit_log_button_not_found", "Could not find 'LOG THIS' button")
            logRootNode.recycle()
            return FitbitResult(
                success = false,
                error = "Could not find 'LOG THIS' button"
            )
        }

        val clickable = if (logButton.isClickable) {
            logButton
        } else {
            findClickableParent(logButton) ?: logButton
        }

        val logClicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Clicked 'LOG THIS': $logClicked")
        logButton.recycle()
        logRootNode.recycle()

        if (!logClicked) {
            val dumpRoot = accessibilityService.getCurrentRootNode()
            if (dumpRoot != null) {
                dumpUIHierarchy(dumpRoot, "fitbit_log_button_tap_failed", "Failed to tap 'LOG THIS' button")
                dumpRoot.recycle()
            }
            return FitbitResult(
                success = false,
                error = "Failed to tap 'LOG THIS' button"
            )
        }

        // Wait for the Add Quick Calories screen to go away (log saved)
        waitForCondition(maxWaitMs = 5000) {
            val node = accessibilityService.getCurrentRootNode()
            if (node != null) {
                val screen = detectFitbitScreen(node)
                node.recycle()
                screen != FitbitScreen.ADD_QUICK_CALORIES
            } else false
        }

        // Press BACK to return to Fitbit home, waiting for screen change after each press
        for (i in 0 until 2) {
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            waitForCondition(maxWaitMs = 2000) {
                val node = accessibilityService.getCurrentRootNode()
                if (node != null) {
                    val screen = detectFitbitScreen(node)
                    node.recycle()
                    screen == FitbitScreen.TODAY_HOME
                } else false
            }
        }

        clearRecentActions()
        Log.i(TAG, "Successfully logged $calories quick calories to Fitbit")

        return FitbitResult(
            success = true,
            action = "add_quick_calories",
            calories = calories
        )
    }

    /**
     * Scroll a node down by finding a scrollable container
     */
    private fun scrollNodeDown(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 20) return false

        if (node.isScrollable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scrollNodeDown(child, depth + 1)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    /**
     * Perform a swipe gesture using accessibility service (API 24+)
     */
    private fun performSwipeGesture(
        accessibilityService: WhizAccessibilityService,
        direction: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        try {
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val centerX = screenWidth / 2f
            val startY: Float
            val endY: Float

            when (direction) {
                "up" -> {
                    startY = screenHeight * 0.7f
                    endY = screenHeight * 0.3f
                }
                "down" -> {
                    startY = screenHeight * 0.3f
                    endY = screenHeight * 0.7f
                }
                else -> return false
            }

            val path = android.graphics.Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            accessibilityService.dispatchGesture(gesture, null, null)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error performing swipe gesture", e)
            return false
        }
    }

    // ========== Utility Functions ==========

    fun getInstalledApps(): List<String> {
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        
        return installedApps
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { packageManager.getApplicationLabel(it).toString() }
            .sorted()
    }
}
