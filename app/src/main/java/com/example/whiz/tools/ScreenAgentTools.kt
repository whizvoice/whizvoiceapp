package com.example.whiz.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.MessageDraftOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Screen Agent Tools - Consolidated tools for screen interaction via Accessibility Service
 * Combines app launching and app-specific interactions (like WhatsApp chat selection)
 */
@Singleton
class ScreenAgentTools @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "ScreenAgentTools"
    
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
        val error: String? = null
    )

    data class MapsActionResult(
        val success: Boolean,
        val action: String,
        val location: String? = null,
        val mode: String? = null,
        val error: String? = null
    )

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
                    context.startActivity(launchIntent)
                    
                    val appLabel = packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                    
                    // Start bubble overlay if enabled and we have permission
                    var overlayStarted = false
                    var overlayPermissionRequired = false
                    Log.d(TAG, "Checking overlay (fuzzy): enableOverlay=$enableOverlay, isWhizApp=${isWhizApp(packageName)}, hasPermission=${hasOverlayPermission()}")
                    if (enableOverlay && !isWhizApp(packageName)) {
                        if (hasOverlayPermission()) {
                            Log.d(TAG, "Starting bubble overlay service (fuzzy)")
                            overlayStarted = startBubbleOverlay()
                            Log.d(TAG, "Bubble overlay started (fuzzy): $overlayStarted")
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
                        var overlayStarted = false
                        var overlayPermissionRequired = false
                        Log.i(TAG, "🔵 BUBBLE CHECK: enableOverlay=$enableOverlay, packageName=$mappedPackage, isWhizApp=${isWhizApp(mappedPackage)}, hasPermission=${hasOverlayPermission()}")
                        if (enableOverlay && !isWhizApp(mappedPackage)) {
                            if (hasOverlayPermission()) {
                                Log.i(TAG, "🔵 STARTING BUBBLE OVERLAY SERVICE for $mappedPackage")
                                overlayStarted = startBubbleOverlay()
                                Log.i(TAG, "🔵 BUBBLE OVERLAY RESULT: $overlayStarted")
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
                    }
                }
            }
            
            Log.w(TAG, "Could not find app matching: $appName")
            return LaunchResult(
                success = false,
                appName = appName,
                error = "Could not find an app matching '$appName'"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app: $appName", e)
            return LaunchResult(
                success = false,
                appName = appName,
                error = "Error launching app: ${e.message}"
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
        
        try {
            // Note: This assumes WhatsApp is already open. 
            // The server should use launch_app tool first if needed.
            
            // Get accessibility service instance
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                Log.e(TAG, "Accessibility service not available")
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

            for (backAttempt in 1..maxBackAttempts) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode != null) {
                    val currentScreen = detectWhatsAppScreen(rootNode)
                    Log.i(TAG, "Back attempt $backAttempt: Current screen = $currentScreen")

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
                        val clickableNode = findClickableParent(chatNode)
                        if (clickableNode != null) {
                            Log.d(TAG, "Found clickable parent, attempting click...")
                            success = accessibilityService.clickNode(clickableNode)
                            Log.d(TAG, "Click result: $success")
                            
                            if (success) {
                                // Wait to verify we're in the chat
                                waitForCondition(maxWaitMs = 2000) {
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
                                
                                // Clean up
                                chatNodes.forEach { it.recycle() }
                                rootNode.recycle()
                                
                                return WhatsAppResult(
                                    success = true,
                                    action = "select_chat",
                                    chatName = chatName
                                )
                            }
                            clickableNode.recycle()
                        }
                    }
                    
                    // Recycle nodes
                    chatNodes.forEach { it.recycle() }
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

            return WhatsAppResult(
                success = false,
                action = "select_chat",
                chatName = chatName,
                error = "Could not find contact or chat named '$chatName' in WhatsApp. Please verify the contact name and ask the user to confirm the exact spelling if needed."
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error selecting WhatsApp chat", e)
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
    
    suspend fun draftWhatsAppMessage(message: String, previousText: String? = null): DraftResult {
        Log.d(TAG, "Attempting to draft message in WhatsApp: $message, previousText: $previousText")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                return DraftResult(
                    success = false,
                    message = message,
                    error = "Accessibility service not enabled"
                )
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
                        Log.w(TAG, "Keyboard may not have opened, or input field didn't move as expected")
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
                    inputNodes.forEach { it.recycle() }
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
                inputNodes.forEach { it.recycle() }
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

            // Clean up
            inputNodes.forEach { it.recycle() }
            rootNode.recycle()

            return DraftResult(
                success = false,
                message = message,
                error = "Could not find message input field"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error drafting WhatsApp message", e)
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
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
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
                    
                    return if (sendSuccess) {
                        WhatsAppResult(
                            success = true,
                            action = "send_message"
                        )
                    } else {
                        WhatsAppResult(
                            success = false,
                            action = "send_message",
                            error = "Could not find or click send button"
                        )
                    }
                }
            }
            
            // Clean up
            inputNodes.forEach { it.recycle() }
            rootNode.recycle()
            
            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Could not find message input field"
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending WhatsApp message", e)
            return WhatsAppResult(
                success = false,
                action = "send_message",
                error = "Error sending message: ${e.message}"
            )
        }
    }
    
    // ========== YouTube Music Specific Functions ==========

    suspend fun playYouTubeMusicSong(query: String): MusicActionResult {
        Log.d(TAG, "Attempting to play song on YouTube Music: $query")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                return MusicActionResult(
                    success = false,
                    action = "play_song",
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
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "YouTube Music did not become ready in time"
                )
            }

            // Navigate to a searchable screen (speed dial or search screen)
            val navigationSuccess = navigateToYouTubeMusicSearchableScreen(accessibilityService)
            if (!navigationSuccess) {
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not navigate to searchable screen in YouTube Music"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not get root node"
                )
            }

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
                    rootNode.recycle()
                    return MusicActionResult(
                        success = false,
                        action = "play_song",
                        query = query,
                        error = "Could not find search button in YouTube Music"
                    )
                }

                // Wait for search field to appear
                delay(500)
            } else {
                Log.d(TAG, "Search field already visible, skipping search button click")
            }

            // Enter search query
            val searchRootNode = accessibilityService.getCurrentRootNode()
            if (searchRootNode == null) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not get root node after opening search"
                )
            }

            val queryEntered = enterYouTubeMusicSearchQuery(searchRootNode, query)
            searchRootNode.recycle()

            if (!queryEntered) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not enter search query"
                )
            }

            // Wait for suggestions to appear or search results to load
            delay(500)

            // Try to click first suggestion (autocomplete dropdown)
            val suggestionRootNode = accessibilityService.getCurrentRootNode()
            if (suggestionRootNode == null) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not get root node after entering query"
                )
            }

            val suggestionClicked = clickFirstYouTubeMusicSuggestion(suggestionRootNode, accessibilityService)
            suggestionRootNode.recycle()
            rootNode.recycle()

            // Whether we clicked a suggestion or not, we need to wait for search results
            // and then try to click the play button (but only if it's not already playing)
            if (suggestionClicked) {
                Log.d(TAG, "Successfully clicked autocomplete suggestion, waiting for search results")
                delay(2000) // Wait for search results to load
            } else {
                Log.d(TAG, "No autocomplete suggestions found, waiting for search results")
                delay(1500)
            }

            // Try to click the play button on the first search result
            val resultsRootNode = accessibilityService.getCurrentRootNode()
            if (resultsRootNode == null) {
                return MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not get root node for search results"
                )
            }

            val playClicked = clickFirstYouTubeMusicResult(resultsRootNode, accessibilityService)
            resultsRootNode.recycle()

            return if (playClicked) {
                Log.d(TAG, "Successfully clicked play button on search result")
                MusicActionResult(
                    success = true,
                    action = "play_song",
                    query = query
                )
            } else {
                MusicActionResult(
                    success = false,
                    action = "play_song",
                    query = query,
                    error = "Could not find autocomplete suggestions or play button"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error playing YouTube Music song", e)
            return MusicActionResult(
                success = false,
                action = "play_song",
                query = query,
                error = "Error playing song: ${e.message}"
            )
        }
    }

    suspend fun queueYouTubeMusicSong(query: String): MusicActionResult {
        Log.d(TAG, "Attempting to queue song on YouTube Music: $query")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
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
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not navigate to searchable screen in YouTube Music"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node"
                )
            }

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
                    rootNode.recycle()
                    return MusicActionResult(
                        success = false,
                        action = "queue_song",
                        query = query,
                        error = "Could not find search button in YouTube Music"
                    )
                }

                // Wait for search field to appear
                delay(500)
            } else {
                Log.d(TAG, "Search field already visible, skipping search button click")
            }

            // Enter search query
            val searchRootNode = accessibilityService.getCurrentRootNode()
            if (searchRootNode == null) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node after opening search"
                )
            }

            val queryEntered = enterYouTubeMusicSearchQuery(searchRootNode, query)
            searchRootNode.recycle()

            if (!queryEntered) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not enter search query"
                )
            }

            // Wait for suggestions to appear or search results to load
            delay(500)

            // Try to click first suggestion (autocomplete dropdown)
            val suggestionRootNode = accessibilityService.getCurrentRootNode()
            if (suggestionRootNode == null) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node after entering query"
                )
            }

            val suggestionClicked = clickFirstYouTubeMusicSuggestion(suggestionRootNode, accessibilityService)
            suggestionRootNode.recycle()

            // Whether we clicked a suggestion or not, we need to wait for search results
            if (suggestionClicked) {
                Log.d(TAG, "Successfully clicked autocomplete suggestion, waiting for search results")
                delay(2000) // Wait for search results to load
            } else {
                Log.d(TAG, "No autocomplete suggestions found, waiting for search results")
                delay(1500)
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

            // Long-press on first result to bring up menu, then select "Add to queue"
            val resultsRootNode = accessibilityService.getCurrentRootNode()
            if (resultsRootNode == null) {
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node after search"
                )
            }

            val menuSuccess = openYouTubeMusicContextMenu(resultsRootNode, accessibilityService)

            if (!menuSuccess) {
                resultsRootNode.recycle()
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not open context menu for result"
                )
            }

            // Wait for menu to appear
            delay(500)

            // Click "Add to queue" option
            val menuRootNode = accessibilityService.getCurrentRootNode()
            if (menuRootNode == null) {
                resultsRootNode.recycle()
                rootNode.recycle()
                return MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not get root node after opening menu"
                )
            }

            val queueSuccess = clickAddToQueue(menuRootNode, accessibilityService)
            menuRootNode.recycle()
            resultsRootNode.recycle()
            rootNode.recycle()

            return if (queueSuccess) {
                MusicActionResult(
                    success = true,
                    action = "queue_song",
                    query = query
                )
            } else {
                MusicActionResult(
                    success = false,
                    action = "queue_song",
                    query = query,
                    error = "Could not find 'Add to queue' option in menu"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error queueing YouTube Music song", e)
            return MusicActionResult(
                success = false,
                action = "queue_song",
                query = query,
                error = "Error queueing song: ${e.message}"
            )
        }
    }

    // ========== Google Maps Functions ==========

    suspend fun searchGoogleMapsLocation(address: String): MapsActionResult {
        Log.d(TAG, "Attempting to search for location in Google Maps: $address")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
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
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not get root node"
                )
            }

            // Find and click the search box
            val searchBoxClicked = clickGoogleMapsSearch(rootNode, accessibilityService)
            rootNode.recycle()

            if (!searchBoxClicked) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not find search box in Google Maps"
                )
            }

            // Wait for search field to appear
            delay(500)

            // Enter search query
            val searchRootNode = accessibilityService.getCurrentRootNode()
            if (searchRootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not get root node after opening search"
                )
            }

            val queryEntered = enterGoogleMapsSearchQuery(searchRootNode, address)
            searchRootNode.recycle()

            if (!queryEntered) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not enter search query"
                )
            }

            // Wait for suggestions to appear
            delay(500)

            // Click first suggestion
            val suggestionRootNode = accessibilityService.getCurrentRootNode()
            if (suggestionRootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not get root node after entering query"
                )
            }

            val suggestionClicked = clickFirstGoogleMapsSuggestion(suggestionRootNode, accessibilityService)
            suggestionRootNode.recycle()

            if (!suggestionClicked) {
                return MapsActionResult(
                    success = false,
                    action = "search_location",
                    location = address,
                    error = "Could not find or click search suggestion"
                )
            }

            // Wait for location to load
            delay(1000)

            return MapsActionResult(
                success = true,
                action = "search_location",
                location = address
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error searching Google Maps location", e)
            return MapsActionResult(
                success = false,
                action = "search_location",
                location = address,
                error = "Error searching location: ${e.message}"
            )
        }
    }

    suspend fun getGoogleMapsDirections(mode: String = "drive"): MapsActionResult {
        Log.d(TAG, "Attempting to get directions in Google Maps with mode: $mode")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
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
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "get_directions",
                    mode = mode,
                    error = "Could not get root node"
                )
            }

            // Check if we're already on the directions screen (look for transport mode tabs)
            val directionsTabsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/directions_mode_tabs")
            val alreadyOnDirectionsScreen = directionsTabsNodes != null && directionsTabsNodes.isNotEmpty()
            directionsTabsNodes?.forEach { it.recycle() }

            if (!alreadyOnDirectionsScreen) {
                // Find and click the "Directions" button
                val directionsClicked = clickGoogleMapsDirections(rootNode, accessibilityService)
                rootNode.recycle()

                if (!directionsClicked) {
                    return MapsActionResult(
                        success = false,
                        action = "get_directions",
                        mode = mode,
                        error = "Could not find Directions button in Google Maps"
                    )
                }

                // Wait for directions screen to appear
                delay(1500)
            } else {
                rootNode.recycle()
            }

            // Get fresh root node for transport mode selection and start
            val modeRootNode = accessibilityService.getCurrentRootNode()
            if (modeRootNode != null) {
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
            }

            return MapsActionResult(
                success = true,
                action = "get_directions",
                mode = mode
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error getting Google Maps directions", e)
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
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
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
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Could not get root node"
                )
            }

            // Find and click the Re-center button
            val recenterClicked = clickGoogleMapsRecenter(rootNode, accessibilityService)
            rootNode.recycle()

            if (!recenterClicked) {
                return MapsActionResult(
                    success = false,
                    action = "recenter",
                    error = "Could not find Re-center button in Google Maps"
                )
            }

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

    suspend fun selectLocationFromList(selection: String = "first"): MapsActionResult {
        Log.d(TAG, "Attempting to select location from list: $selection")

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
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
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Could not get root node"
                )
            }

            // Find and click the location from the list
            val locationClicked = clickLocationFromList(rootNode, selection, accessibilityService)
            rootNode.recycle()

            if (!locationClicked) {
                return MapsActionResult(
                    success = false,
                    action = "select_location",
                    error = "Could not find or click location '$selection' in list"
                )
            }

            // Wait for location to load
            delay(1000)

            return MapsActionResult(
                success = true,
                action = "select_location",
                location = selection
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

        try {
            val accessibilityService = WhizAccessibilityService.getInstance()
            if (accessibilityService == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
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
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Google Maps did not become ready in time"
                )
            }

            val rootNode = accessibilityService.getCurrentRootNode()
            if (rootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Could not get root node"
                )
            }

            // Find and click the search box
            val searchBoxClicked = clickGoogleMapsSearch(rootNode, accessibilityService)
            rootNode.recycle()

            if (!searchBoxClicked) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Could not find search box in Google Maps"
                )
            }

            // Wait for search field to appear
            delay(500)

            // Enter search query
            val searchRootNode = accessibilityService.getCurrentRootNode()
            if (searchRootNode == null) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Could not get root node after opening search"
                )
            }

            val queryEntered = enterGoogleMapsSearchQuery(searchRootNode, searchPhrase)
            searchRootNode.recycle()

            if (!queryEntered) {
                return MapsActionResult(
                    success = false,
                    action = "search_phrase",
                    location = searchPhrase,
                    error = "Could not enter search query"
                )
            }

            // Wait for search results to appear
            // Google Maps auto-searches as you type, so we just need to wait
            delay(2000)

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

    private fun clickGoogleMapsSearch(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        // Try to find search box by resource ID
        val searchBoxNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_text_box")
        if (searchBoxNodes != null && searchBoxNodes.isNotEmpty()) {
            for (node in searchBoxNodes) {
                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    if (clicked) {
                        Log.d(TAG, "Clicked Google Maps search box")
                        return true
                    }
                }
                node.recycle()
            }
        }

        // Try to find by text content
        val textNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Search here", textNodes)
        for (node in textNodes) {
            if (node.isClickable) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked search box by text")
                    textNodes.forEach { it.recycle() }
                    return true
                }
            }
            node.recycle()
        }

        Log.w(TAG, "Could not find Google Maps search box")
        return false
    }

    private fun enterGoogleMapsSearchQuery(rootNode: AccessibilityNodeInfo, query: String): Boolean {
        // The search_omnibox_text_box itself is the editable field - we already clicked it
        // So we can just use ACTION_SET_TEXT or paste directly
        val searchFieldNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_text_box")
        if (searchFieldNodes != null && searchFieldNodes.isNotEmpty()) {
            for (node in searchFieldNodes) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                node.recycle()
                if (success) {
                    Log.d(TAG, "Entered search query in Google Maps")
                    searchFieldNodes.forEach { it.recycle() }
                    return true
                }
                node.recycle()
            }
        }

        Log.w(TAG, "Could not find Google Maps search text box")
        return false
    }

    private fun clickFirstGoogleMapsSuggestion(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        // Look for the typed_suggest_container RecyclerView
        val suggestContainerNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/typed_suggest_container")

        if (suggestContainerNodes != null && suggestContainerNodes.isNotEmpty()) {
            for (containerNode in suggestContainerNodes) {
                // Get the first clickable child (first suggestion)
                if (containerNode.childCount > 0) {
                    val firstChild = containerNode.getChild(0)
                    if (firstChild != null && firstChild.isClickable) {
                        // Check if this is a "See locations" suggestion
                        val hasSeeLocations = checkForSeeLocations(firstChild)

                        if (hasSeeLocations) {
                            Log.d(TAG, "Found 'See locations' suggestion, clicking it")
                        }

                        val clicked = firstChild.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        firstChild.recycle()
                        containerNode.recycle()
                        suggestContainerNodes.forEach { it.recycle() }
                        if (clicked) {
                            Log.d(TAG, "Clicked first Google Maps suggestion${if (hasSeeLocations) " (See locations)" else ""}")
                            return true
                        }
                    }
                    firstChild?.recycle()
                }
                containerNode.recycle()
            }
        }

        // If no suggestions found, the search will auto-submit or we can look for a search button
        // For now, just return true as the search query is already entered
        Log.i(TAG, "No suggestions container found, search query should auto-submit or be ready")
        return true
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

    private fun clickGoogleMapsDirections(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        // Look for "Directions" button
        val directionNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByText(rootNode, "Directions", directionNodes)

        for (node in directionNodes) {
            if (node.isClickable || node.className == "android.widget.Button") {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked Directions button")
                    directionNodes.forEach { it.recycle() }
                    return true
                }
            }
            node.recycle()
        }

        Log.w(TAG, "Could not find Directions button")
        return false
    }

    private fun selectTransportModeAndStart(rootNode: AccessibilityNodeInfo, mode: String, accessibilityService: WhizAccessibilityService): Boolean {
        // Map mode to content description pattern
        val modeText = when (mode.lowercase()) {
            "drive" -> "Driving mode"
            "walk" -> "Walking mode"
            "bike" -> "Bicycling mode"
            "transit" -> "Transit mode"
            else -> "Driving mode"
        }

        // First, check if the desired mode is already selected
        val currentlySelected = findSelectedTransportMode(rootNode)
        Log.d(TAG, "Currently selected transport mode: $currentlySelected, desired: $modeText")

        // If the mode is not already selected, click it
        if (currentlySelected != modeText) {
            val modeNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDesc(rootNode, modeText, modeNodes)

            var modeChanged = false
            for (node in modeNodes) {
                // The parent LinearLayout is clickable
                var clickableNode = node
                if (!node.isClickable && node.parent != null) {
                    clickableNode = node.parent
                }

                if (clickableNode.isClickable) {
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d(TAG, "Selected transport mode: $modeText")
                        modeChanged = true
                        break
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

        // Now click the Start button
        val startNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByContentDesc(rootNode, "Start", startNodes)

        for (node in startNodes) {
            if (node.isClickable && node.className == "android.widget.Button") {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked Start button")
                    startNodes.forEach { it.recycle() }
                    return true
                }
            }
            node.recycle()
        }

        Log.w(TAG, "Could not find or click Start button")
        return false
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

    private fun clickLocationFromList(rootNode: AccessibilityNodeInfo, selection: String, accessibilityService: WhizAccessibilityService): Boolean {
        // Find the RecyclerView containing the location list
        val listNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_list_layout")

        if (listNodes == null || listNodes.isEmpty()) {
            Log.w(TAG, "Could not find location list RecyclerView")
            return false
        }

        val listNode = listNodes[0]

        // Determine if selection is an ordinal (first, second, third) or an address fragment
        val ordinalMap = mapOf(
            "first" to 0,
            "second" to 1,
            "third" to 2,
            "fourth" to 3,
            "fifth" to 4
        )

        val targetIndex = ordinalMap[selection.lowercase()]

        if (targetIndex != null) {
            // Select by ordinal position
            if (targetIndex < listNode.childCount) {
                val child = listNode.getChild(targetIndex)
                if (child != null) {
                    // Find the clickable parent (RelativeLayout)
                    var clickableNode = child
                    if (!child.isClickable && child.parent != null) {
                        clickableNode = child.parent
                    }

                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    child.recycle()
                    listNodes.forEach { it.recycle() }

                    if (clicked) {
                        Log.d(TAG, "Clicked location at position $targetIndex")
                        return true
                    }
                }
            } else {
                Log.w(TAG, "List has only ${listNode.childCount} items, cannot select position $targetIndex")
                listNodes.forEach { it.recycle() }
                return false
            }
        } else {
            // Select by address fragment match
            for (i in 0 until listNode.childCount) {
                val child = listNode.getChild(i)
                if (child != null) {
                    // Check if this item contains the selection text
                    if (containsText(child, selection)) {
                        // Find the clickable parent (RelativeLayout)
                        var clickableNode = child
                        if (!child.isClickable && child.parent != null) {
                            clickableNode = child.parent
                        }

                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        child.recycle()
                        listNodes.forEach { it.recycle() }

                        if (clicked) {
                            Log.d(TAG, "Clicked location matching '$selection'")
                            return true
                        }
                    }
                    child.recycle()
                }
            }

            Log.w(TAG, "Could not find location matching '$selection'")
            listNodes.forEach { it.recycle() }
            return false
        }

        listNodes.forEach { it.recycle() }
        return false
    }

    private fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = containsText(child, text)
                child.recycle()
                if (found) return true
            }
        }

        return false
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
     * Detect and dismiss YouTube Music promotional pop-ups (e.g., Family Plan, Premium).
     * Returns true if a pop-up was detected and dismissed, false otherwise.
     */
    private fun dismissYouTubeMusicPopup(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Look for common pop-up text indicators
            val popupIndicators = listOf(
                "Save with family plan",
                "family plan",
                "Premium",
                "Music Premium",
                "Free trial"
            )

            var hasPopup = false
            for (indicator in popupIndicators) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(indicator)
                if (nodes != null && nodes.isNotEmpty()) {
                    Log.d(TAG, "Detected YouTube Music pop-up with text: $indicator")
                    hasPopup = true
                    nodes.forEach { it.recycle() }
                    break
                }
            }

            if (!hasPopup) {
                return false
            }

            // Look for "No thanks" button
            val noThanksNodes = rootNode.findAccessibilityNodeInfosByText("No thanks")
            if (noThanksNodes != null && noThanksNodes.isNotEmpty()) {
                for (node in noThanksNodes) {
                    // Find the clickable parent (the Button element)
                    val clickableNode = if (node.isClickable) node else findClickableParent(node)
                    if (clickableNode != null) {
                        Log.d(TAG, "Found 'No thanks' button, clicking to dismiss pop-up")
                        val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        clickableNode.recycle()
                        noThanksNodes.forEach { it.recycle() }

                        if (clicked) {
                            Log.d(TAG, "Successfully dismissed YouTube Music pop-up")
                            return true
                        } else {
                            Log.w(TAG, "Failed to click 'No thanks' button")
                        }
                    }
                }
                noThanksNodes.forEach { it.recycle() }
            }

            // Alternative: Look for dismiss/close buttons by content description
            val dismissDescriptions = listOf("No thanks", "Dismiss", "Close", "Not now")
            for (desc in dismissDescriptions) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(desc)
                if (nodes != null && nodes.isNotEmpty()) {
                    for (node in nodes) {
                        val clickableNode = if (node.isClickable) node else findClickableParent(node)
                        if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.d(TAG, "Dismissed pop-up using button: $desc")
                            clickableNode.recycle()
                            nodes.forEach { it.recycle() }
                            return true
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            Log.w(TAG, "Detected pop-up but could not find dismiss button")
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

                // Check if we're on the Now Playing screen (which we should navigate away from)
                val playerPageNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.apps.youtube.music:id/player_page"
                )
                val isOnNowPlayingScreen = playerPageNodes != null && playerPageNodes.isNotEmpty()
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

                Log.d(TAG, "Navigation check: speedDial=$isOnSpeedDial, searchButton=$hasSearchButton, searchScreen=$isOnSearchScreen, attempt=$attempt")

                // If we're on either the speed dial page OR the search screen, we're good
                // The search button being clickable is what matters, not the "Speed dial" text
                if (hasSearchButton || isOnSearchScreen) {
                    Log.d(TAG, "On searchable screen (has search button: $hasSearchButton, search screen: $isOnSearchScreen)")
                    rootNode.recycle()
                    return true
                }

                // Not on the right screen, press back
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

    private fun clickYouTubeMusicSearch(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for search button - try various possible IDs and descriptions
            val searchViewIds = listOf(
                "com.google.android.apps.youtube.music:id/action_search_button",
                "com.google.android.apps.youtube.music:id/action_bar_search"
            )

            // Try by view ID first
            for (viewId in searchViewIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
                if (nodes != null && nodes.isNotEmpty()) {
                    Log.d(TAG, "Found YouTube Music search button with ID: $viewId")
                    for (node in nodes) {
                        val clickableNode = if (node.isClickable) node else findClickableParent(node)
                        if (clickableNode != null) {
                            val clicked = accessibilityService.clickNode(clickableNode)
                            if (clickableNode != node) {
                                clickableNode.recycle()
                            }
                            if (clicked) {
                                Log.d(TAG, "Successfully clicked YouTube Music search button")
                                nodes.forEach { it.recycle() }
                                return true
                            }
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }

            // Try by content description if view ID didn't work
            val searchNodes = rootNode.findAccessibilityNodeInfosByText("Search")
            if (searchNodes != null && searchNodes.isNotEmpty()) {
                Log.d(TAG, "Found YouTube Music search button by text/description")
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
                                Log.d(TAG, "Successfully clicked YouTube Music search ImageButton")
                                searchNodes.forEach { it.recycle() }
                                return true
                            }
                        }
                    }
                }
                searchNodes.forEach { it.recycle() }
            }

            Log.w(TAG, "Could not find YouTube Music search button")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking YouTube Music search", e)
            return false
        }
    }

    private fun enterYouTubeMusicSearchQuery(rootNode: AccessibilityNodeInfo, query: String): Boolean {
        try {
            // Check if there's a clear button (meaning there's existing text)
            val clearButtonNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.apps.youtube.music:id/search_clear"
            )
            if (clearButtonNodes != null && clearButtonNodes.isNotEmpty()) {
                Log.d(TAG, "Found clear button, clicking to clear existing search text")
                val clearButton = clearButtonNodes[0]
                if (clearButton.isClickable) {
                    clearButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                clearButtonNodes.forEach { it.recycle() }
            }

            // Find EditText for search input
            val editTextNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditTextNodes(rootNode, editTextNodes)

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

                Log.d(TAG, "Entered search query: $query")
                return textSet
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

    private fun clickFirstYouTubeMusicResult(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for the "Play" or "Resume" button in the featured search result
            // The button's content-desc is like "Play Golden" or "Resume Golden"
            // But DON'T click if we find a "Pause" button, which means it's already playing
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectAllNodes(rootNode, allNodes)

            // First check if there's a Pause button, which means the song is already playing
            for (node in allNodes) {
                val contentDesc = node.contentDescription?.toString() ?: ""
                if (contentDesc.startsWith("Pause ", ignoreCase = true) &&
                    node.className?.toString()?.contains("Button") == true) {
                    Log.d(TAG, "Found Pause button - song is already playing, not clicking")
                    allNodes.forEach { it.recycle() }
                    return true // Return true because the song is already playing (success)
                }
            }

            // No Pause button found, look for Play/Resume button
            for (node in allNodes) {
                val contentDesc = node.contentDescription?.toString() ?: ""
                // Match "Play <song>" or "Resume <song>" buttons only (not just "Play" alone to avoid child text nodes)
                if ((contentDesc.startsWith("Play ", ignoreCase = true) || contentDesc.startsWith("Resume ", ignoreCase = true)) &&
                    node.className?.toString()?.contains("Button") == true &&
                    node.isClickable) {
                    Log.d(TAG, "Found Play/Resume button with content-desc: $contentDesc, clicking it")
                    val clicked = accessibilityService.clickNode(node)
                    allNodes.forEach { it.recycle() }
                    return clicked
                }
            }

            allNodes.forEach { it.recycle() }
            Log.w(TAG, "Could not find Play/Resume button in search results")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking first YouTube Music result", e)
            return false
        }
    }

    private fun openYouTubeMusicContextMenu(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for "Action menu" button (three-dot menu on each result) by content description
            val actionMenuNodes = mutableListOf<AccessibilityNodeInfo>()
            findNodesByContentDescription(rootNode, "Action menu", actionMenuNodes)

            if (actionMenuNodes.isNotEmpty()) {
                val firstMenuButton = actionMenuNodes[0]
                val clickableNode = if (firstMenuButton.isClickable) firstMenuButton else findClickableParent(firstMenuButton)

                if (clickableNode != null) {
                    val clicked = accessibilityService.clickNode(clickableNode)
                    Log.d(TAG, "Clicked Action menu button for first result: $clicked")
                    clickableNode.recycle()
                    actionMenuNodes.forEach { it.recycle() }
                    return clicked
                }

                actionMenuNodes.forEach { it.recycle() }
            }

            // Fallback: Try to long-press on the first search result
            val resultNodes = findYouTubeMusicResults(rootNode)

            if (resultNodes.isNotEmpty()) {
                val firstResult = resultNodes[0]

                // Long-press on the first search result to open context menu
                val longPressed = firstResult.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

                Log.d(TAG, "Long-pressed on first search result (fallback): $longPressed")

                resultNodes.forEach { it.recycle() }
                return longPressed
            }

            resultNodes.forEach { it.recycle() }
            Log.w(TAG, "No search results or action menu found")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening YouTube Music context menu", e)
            return false
        }
    }

    private fun clickAddToQueue(rootNode: AccessibilityNodeInfo, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for "Add to queue" or "Play next" text
            val queueTexts = listOf("Add to queue", "Play next", "add to queue", "play next")

            for (text in queueTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                if (nodes != null && nodes.isNotEmpty()) {
                    for (node in nodes) {
                        val clickableNode = if (node.isClickable) node else findClickableParent(node)
                        if (clickableNode != null) {
                            val clicked = accessibilityService.clickNode(clickableNode)
                            if (clickableNode != node) {
                                clickableNode.recycle()
                            }
                            if (clicked) {
                                nodes.forEach { it.recycle() }
                                return true
                            }
                        }
                    }
                    nodes.forEach { it.recycle() }
                }
            }

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
            // Check if this node matches the content description
            val nodeContentDesc = node.contentDescription?.toString()
            if (nodeContentDesc != null && nodeContentDesc.equals(contentDesc, ignoreCase = true)) {
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
                // Check if search results are visible by looking for chat items
                val chatNodes = findChatNodes(rootNode, searchQuery)
                val hasResults = chatNodes.isNotEmpty()
                chatNodes.forEach { it.recycle() }
                rootNode.recycle()
                hasResults
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

            // Check for chat list elements
            val chatList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name")
            if (chatList != null && chatList.isNotEmpty()) {
                chatList.forEach { it.recycle() }
                Log.d(TAG, "On WhatsApp chat list")
                return WhatsAppScreen.CHAT_LIST
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

            Log.d(TAG, "Not on WhatsApp chat list or inside chat")
            return WhatsAppScreen.UNKNOWN

        } catch (e: Exception) {
            Log.e(TAG, "Error detecting WhatsApp screen", e)
            return WhatsAppScreen.UNKNOWN
        }
    }

    private fun isSearchBarVisible(rootNode: AccessibilityNodeInfo): Boolean {
        try {
            // Check for search bar elements
            val searchBarIds = listOf(
                "com.whatsapp:id/my_search_bar",
                "com.whatsapp:id/menuitem_search",
                "com.whatsapp:id/search_button",
                "com.whatsapp:id/action_search"
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
    
    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            
            val parent = current.parent
            current.recycle()
            current = parent
        }
        
        return null
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