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
            
            // Wait for WhatsApp UI to be ready
            if (!waitForWhatsAppReady(accessibilityService, maxWaitMs = 1000)) {
                Log.w(TAG, "WhatsApp UI not ready after waiting")
            }
            
            // First detect what screen we're on and navigate accordingly
            val initialRootNode = accessibilityService.getCurrentRootNode()
            if (initialRootNode != null) {
                val currentScreen = detectWhatsAppScreen(initialRootNode)
                Log.i(TAG, "Current WhatsApp screen: $currentScreen")
                
                when (currentScreen) {
                    WhatsAppScreen.INSIDE_CHAT -> {
                        // Check if we're already in the correct chat
                        val chatHeader = initialRootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                        var isCorrectChat = false
                        
                        if (chatHeader != null && chatHeader.isNotEmpty()) {
                            val currentChatName = chatHeader[0].text?.toString()
                            if (currentChatName != null && currentChatName.lowercase().trim() == chatName.lowercase().trim()) {
                                Log.i(TAG, "Already in the correct chat: $currentChatName")
                                isCorrectChat = true
                            } else {
                                Log.i(TAG, "Currently in different chat: $currentChatName, need to navigate to: $chatName")
                            }
                            chatHeader.forEach { it.recycle() }
                        }
                        
                        if (isCorrectChat) {
                            // We're already in the right chat, no need to search
                            initialRootNode.recycle()
                            return WhatsAppResult(
                                success = true,
                                action = "select_chat",
                                chatName = chatName
                            )
                        } else {
                            // Navigate back to find the correct chat
                            Log.i(TAG, "Navigating back to chat list to find correct contact")
                            initialRootNode.recycle()
                            accessibilityService.performGlobalActionSafely(AccessibilityService.GLOBAL_ACTION_BACK)
                            // Wait for navigation to complete and return to chat list
                            waitForWhatsAppReady(accessibilityService, WhatsAppScreen.CHAT_LIST, maxWaitMs = 1500)
                        }
                    }
                    WhatsAppScreen.SETTINGS -> {
                        Log.i(TAG, "In settings, navigating back to main screen")
                        initialRootNode.recycle()
                        accessibilityService.performGlobalActionSafely(AccessibilityService.GLOBAL_ACTION_BACK)
                        // Wait for navigation to complete
                        waitForWhatsAppReady(accessibilityService, WhatsAppScreen.CHAT_LIST, maxWaitMs = 1500)
                    }
                    WhatsAppScreen.STATUS, WhatsAppScreen.CALLS -> {
                        Log.i(TAG, "On $currentScreen tab, need to switch to Chats tab")
                        // Try to click on the Chats tab
                        val chatsTab = initialRootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_chats")
                        if (chatsTab != null && chatsTab.isNotEmpty()) {
                            accessibilityService.clickNode(chatsTab[0])
                            chatsTab.forEach { it.recycle() }
                            // Wait for tab switch to complete
                            waitForWhatsAppReady(accessibilityService, WhatsAppScreen.CHAT_LIST, maxWaitMs = 1000)
                        }
                        initialRootNode.recycle()
                    }
                    WhatsAppScreen.SEARCH_ACTIVE -> {
                        Log.i(TAG, "Search is already active, will use existing search")
                        initialRootNode.recycle()
                        // We'll handle this in the search logic below
                    }
                    else -> {
                        initialRootNode.recycle()
                    }
                }
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
                                // Wait to see if we're in a chat or profile view
                                waitForCondition(maxWaitMs = 2000) {
                                    val rootNode = accessibilityService.getCurrentRootNode()
                                    if (rootNode != null) {
                                        val screen = detectWhatsAppScreen(rootNode)
                                        val isReady = screen == WhatsAppScreen.INSIDE_CHAT || 
                                                     // Check for profile view (has Message button)
                                                     rootNode.findAccessibilityNodeInfosByText("Message").let { nodes ->
                                                         val hasMessageButton = nodes != null && nodes.isNotEmpty()
                                                         nodes?.forEach { it.recycle() }
                                                         hasMessageButton
                                                     }
                                        rootNode.recycle()
                                        isReady
                                    } else {
                                        false
                                    }
                                }
                                
                                // Check if we need to click a "Message" button (profile view)
                                val newRootNode = accessibilityService.getCurrentRootNode()
                                if (newRootNode != null) {
                                    val messageButtonClicked = clickMessageButton(newRootNode, accessibilityService)
                                    newRootNode.recycle()
                                    
                                    if (messageButtonClicked) {
                                        Log.d(TAG, "Successfully clicked Message button after opening profile")
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
                
                // Get the bounds of the input field
                val rect = android.graphics.Rect()
                inputNode.getBoundsInScreen(rect)
                
                // Get the app window bounds (WhatsApp's actual width on screen)
                val appBounds = android.graphics.Rect()
                rootNode.getBoundsInScreen(appBounds)
                
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
                rootNode.recycle()
                
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
    
    // ========== Helper Functions ==========
    
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
            // Check for search field first (highest priority as it could be active over other screens)
            val searchField = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/search_src_text")
            if (searchField != null && searchField.isNotEmpty()) {
                searchField.forEach { it.recycle() }
                Log.d(TAG, "Search field is active")
                return WhatsAppScreen.SEARCH_ACTIVE
            }
            
            // Check if we're in settings
            val settingsTitle = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/settings")
            if (settingsTitle != null && settingsTitle.isNotEmpty()) {
                settingsTitle.forEach { it.recycle() }
                Log.d(TAG, "In WhatsApp Settings")
                return WhatsAppScreen.SETTINGS
            }
            
            // Check for settings indicators by text
            val settingsText = rootNode.findAccessibilityNodeInfosByText("Settings")
            if (settingsText != null && settingsText.isNotEmpty()) {
                // Verify it's actually the settings screen header
                for (node in settingsText) {
                    if (node.className == "android.widget.TextView") {
                        settingsText.forEach { it.recycle() }
                        Log.d(TAG, "In WhatsApp Settings (detected by text)")
                        return WhatsAppScreen.SETTINGS
                    }
                }
                settingsText.forEach { it.recycle() }
            }
            
            // Check if we're inside a specific chat
            val chatHeader = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
            if (chatHeader != null && chatHeader.isNotEmpty()) {
                chatHeader.forEach { it.recycle() }
                Log.d(TAG, "Inside a WhatsApp chat")
                return WhatsAppScreen.INSIDE_CHAT
            }
            
            // Check for message input field (also indicates chat view)
            val messageInput = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
            if (messageInput != null && messageInput.isNotEmpty()) {
                messageInput.forEach { it.recycle() }
                Log.d(TAG, "Inside a WhatsApp chat (found message input)")
                return WhatsAppScreen.INSIDE_CHAT
            }
            
            // Check if we're on the Status tab
            val statusTab = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_status")
            if (statusTab != null && statusTab.isNotEmpty()) {
                // Check if it's selected
                for (node in statusTab) {
                    if (node.isSelected) {
                        statusTab.forEach { it.recycle() }
                        Log.d(TAG, "On WhatsApp Status tab")
                        return WhatsAppScreen.STATUS
                    }
                }
                statusTab.forEach { it.recycle() }
            }
            
            // Check if we're on the Calls tab
            val callsTab = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_calls")
            if (callsTab != null && callsTab.isNotEmpty()) {
                // Check if it's selected
                for (node in callsTab) {
                    if (node.isSelected) {
                        callsTab.forEach { it.recycle() }
                        Log.d(TAG, "On WhatsApp Calls tab")
                        return WhatsAppScreen.CALLS
                    }
                }
                callsTab.forEach { it.recycle() }
            }
            
            // Check for chat list elements
            val chatList = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name")
            if (chatList != null && chatList.isNotEmpty()) {
                chatList.forEach { it.recycle() }
                Log.d(TAG, "On WhatsApp chat list")
                return WhatsAppScreen.CHAT_LIST
            }
            
            // Default assumption - if we see any tab layout, we're probably on the main screen
            val tabLayout = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/tab_layout")
            if (tabLayout != null && tabLayout.isNotEmpty()) {
                tabLayout.forEach { it.recycle() }
                Log.d(TAG, "On WhatsApp main screen (likely chat list)")
                return WhatsAppScreen.CHAT_LIST
            }
            
            Log.d(TAG, "Could not determine WhatsApp screen")
            return WhatsAppScreen.UNKNOWN
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting WhatsApp screen", e)
            return WhatsAppScreen.UNKNOWN
        }
    }
    
    private fun isInsideWhatsAppChat(rootNode: AccessibilityNodeInfo): Boolean {
        return detectWhatsAppScreen(rootNode) == WhatsAppScreen.INSIDE_CHAT
    }
    
    private suspend fun performWhatsAppSearch(rootNode: AccessibilityNodeInfo, searchQuery: String, accessibilityService: WhizAccessibilityService): Boolean {
        try {
            // Look for the search button/icon in WhatsApp
            val searchButtons = listOf(
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
                            viewId = "com.whatsapp:id/search_src_text",
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
                                viewId = "com.whatsapp:id/search_src_text",
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
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_input",
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
                "com.whatsapp:id/search_src_text",
                "com.whatsapp:id/search_input",
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
        val normalizedChatName = chatName.lowercase().trim()
        
        Log.d(TAG, "Searching for chat: '$chatName' (normalized: '$normalizedChatName')")
        
        // Search by text - exact match first
        val nodesByText = node.findAccessibilityNodeInfosByText(chatName)
        if (nodesByText != null && nodesByText.isNotEmpty()) {
            Log.d(TAG, "Found ${nodesByText.size} nodes with text matching '$chatName'")
            // Filter to ensure we're getting exact or very close matches
            for (node in nodesByText) {
                val nodeText = node.text?.toString()?.lowercase()?.trim()
                val nodeDesc = node.contentDescription?.toString()?.lowercase()?.trim()
                
                // Check if this is actually a match for the chat name
                if ((nodeText != null && (nodeText == normalizedChatName || nodeText.startsWith(normalizedChatName))) ||
                    (nodeDesc != null && (nodeDesc == normalizedChatName || nodeDesc.startsWith(normalizedChatName)))) {
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
                    val normalizedItemText = itemText.lowercase().trim()
                    // Be more strict: require exact match or that the chat name starts with our search
                    if (normalizedItemText == normalizedChatName || 
                        normalizedItemText.startsWith(normalizedChatName) ||
                        (normalizedChatName.length >= 3 && normalizedItemText.contains(normalizedChatName))) {
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
        
        Log.d(TAG, "Total chat nodes found: ${results.size}")
        return results
    }
    
    private fun searchNodeRecursively(
        node: AccessibilityNodeInfo, 
        searchText: String, 
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0,
        strictMatch: Boolean = true
    ) {
        try {
            // Check current node's text
            val nodeText = node.text?.toString()
            val contentDesc = node.contentDescription?.toString()
            
            val matches = if (strictMatch) {
                // For strict matching, require exact match or starts with
                ((nodeText != null && (nodeText.lowercase().trim() == searchText || nodeText.lowercase().startsWith(searchText))) ||
                 (contentDesc != null && (contentDesc.lowercase().trim() == searchText || contentDesc.lowercase().startsWith(searchText))))
            } else {
                // For non-strict, allow contains
                ((nodeText != null && nodeText.lowercase().contains(searchText)) ||
                 (contentDesc != null && contentDesc.lowercase().contains(searchText)))
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