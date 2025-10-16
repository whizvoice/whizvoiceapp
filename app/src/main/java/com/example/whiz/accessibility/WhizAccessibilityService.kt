package com.example.whiz.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WhizAccessibilityService : AccessibilityService() {
    
    private val TAG = "WhizAccessibilityService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        @Volatile
        private var instance: WhizAccessibilityService? = null

        // StateFlow to track service connection state
        private val _serviceState = MutableStateFlow(ServiceState.DISCONNECTED)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        fun getInstance(): WhizAccessibilityService? = instance

        // Legacy method - kept for backward compatibility
        fun isServiceEnabled(): Boolean = _serviceState.value != ServiceState.DISCONNECTED

        // New method to check if service is fully connected
        fun isServiceConnected(): Boolean = _serviceState.value == ServiceState.CONNECTED
    }

    enum class ServiceState {
        DISCONNECTED,  // Service not running
        CREATED,       // onCreate called but not connected yet
        CONNECTED      // onServiceConnected called - fully ready
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        _serviceState.value = ServiceState.CREATED
        Log.d(TAG, "Accessibility service created - state: CREATED")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _serviceState.value = ServiceState.CONNECTED
        Log.d(TAG, "Accessibility service connected - state: CONNECTED")
        
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
        _serviceState.value = ServiceState.DISCONNECTED
        serviceScope.cancel()
        Log.d(TAG, "Accessibility service destroyed - state: DISCONNECTED")
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
     * Performs a scroll gesture (swipe) on the screen
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param endX Ending X coordinate
     * @param endY Ending Y coordinate
     * @param duration Duration of the swipe in milliseconds
     * @return true if gesture was successfully dispatched
     */
    suspend fun performScrollGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatching requires API 24+")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val path = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }

                val gestureBuilder = GestureDescription.Builder()
                val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
                gestureBuilder.addStroke(strokeDescription)
                val gesture = gestureBuilder.build()

                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Scroll gesture completed successfully")
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Scroll gesture was cancelled")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

                val dispatched = dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    Log.e(TAG, "Failed to dispatch scroll gesture")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing scroll gesture", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
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