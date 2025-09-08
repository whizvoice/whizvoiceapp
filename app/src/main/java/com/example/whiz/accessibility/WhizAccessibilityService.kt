package com.example.whiz.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WhizAccessibilityService : AccessibilityService() {
    
    private val TAG = "WhizAccessibilityService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        @Volatile
        private var instance: WhizAccessibilityService? = null
        
        fun getInstance(): WhizAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility service created")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            
            notificationTimeout = 100
        }
        
        this.serviceInfo = info
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            when (it.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.v(TAG, "Window state changed: ${it.packageName}")
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    Log.v(TAG, "View clicked in: ${it.packageName}")
                }
                else -> {
                    // Handle other event types if needed
                }
            }
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        Log.d(TAG, "Accessibility service destroyed")
    }
    
    /**
     * Opens any app by package name
     */
    fun openApp(packageName: String): Boolean {
        return try {
            if (!isAppInstalled(packageName)) {
                Log.w(TAG, "App $packageName is not installed")
                return false
            }
            
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "Successfully launched $packageName")
                
                // Start bubble overlay when launching non-WhizVoice apps
                if (!packageName.contains("com.example.whiz")) {
                    startBubbleOverlay()
                }
                
                true
            } else {
                Log.e(TAG, "Could not get launch intent for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app $packageName", e)
            false
        }
    }
    
    /**
     * Starts the bubble overlay service if overlay permission is granted
     */
    private fun startBubbleOverlay() {
        try {
            // Check if bubble is already active
            if (com.example.whiz.services.BubbleOverlayService.isActive) {
                Log.d(TAG, "Bubble overlay already active, skipping start")
                return
            }
            
            if (android.provider.Settings.canDrawOverlays(this)) {
                com.example.whiz.services.BubbleOverlayService.start(this)
                Log.d(TAG, "Started bubble overlay service after launching app")
            } else {
                Log.w(TAG, "Cannot start bubble overlay - permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start bubble overlay", e)
        }
    }
    
    /**
     * Performs a global action like going home, back, recent apps, etc.
     */
    fun performGlobalActionSafely(action: Int): Boolean {
        return try {
            performGlobalAction(action)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error performing global action", e)
            false
        }
    }
    
    /**
     * Gets the root node of the current window to interact with UI elements
     */
    fun getCurrentRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error getting root node", e)
            null
        }
    }
    
    /**
     * Finds nodes by text in the current window
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val rootNode = getCurrentRootNode() ?: return emptyList()
        return rootNode.findAccessibilityNodeInfosByText(text)
    }
    
    /**
     * Finds nodes by view ID in the current window
     */
    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val rootNode = getCurrentRootNode() ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            rootNode.findAccessibilityNodeInfosByViewId(viewId)
        } else {
            emptyList()
        }
    }
    
    /**
     * Performs a click action on a node
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking node", e)
            false
        }
    }
    
    /**
     * Checks if an app is installed
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * Gets list of all installed apps with launch intents
     */
    fun getInstalledApps(): List<AppInfo> {
        val apps = mutableListOf<AppInfo>()
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
        for (info in resolveInfos) {
            apps.add(
                AppInfo(
                    packageName = info.activityInfo.packageName,
                    appName = info.loadLabel(packageManager).toString()
                )
            )
        }
        
        return apps.sortedBy { it.appName }
    }
    
    data class AppInfo(
        val packageName: String,
        val appName: String
    )
}