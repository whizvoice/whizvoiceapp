package com.example.whiz.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
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
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class WhizAccessibilityService : AccessibilityService() {

    private val TAG = "WhizAccessibilityService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dumpReceiver: BroadcastReceiver? = null

    companion object {
        const val ACTION_DUMP_UI = "com.example.whiz.DUMP_UI"
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

        /**
         * Take a screenshot via the accessibility service.
         * Requires android:canTakeScreenshot="true" in accessibility_service_config.xml (API 30+).
         */
        fun takeScreenshotAsync(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null) {
                Log.w("WhizAccessibilityService", "takeScreenshotAsync: service not available")
                callback(null)
                return
            }
            try {
                val executor = Executor { it.run() }
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val bitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                callback(bitmap)
                            } catch (e: Exception) {
                                Log.e("WhizAccessibilityService", "Error converting screenshot", e)
                                callback(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("WhizAccessibilityService", "Screenshot failed with error code: $errorCode")
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("WhizAccessibilityService", "takeScreenshotAsync error", e)
                callback(null)
            }
        }

        /**
         * Dump the current UI hierarchy as a string via AccessibilityDumpUtil.
         */
        fun getCurrentUiHierarchy(): String? {
            val service = instance ?: return null
            return try {
                val rootNode = service.rootInActiveWindow ?: return null
                val sb = StringBuilder()
                AccessibilityDumpUtil.dumpNodeRecursive(rootNode, sb, 0)
                rootNode.recycle()
                sb.toString()
            } catch (e: Exception) {
                Log.e("WhizAccessibilityService", "Error getting UI hierarchy", e)
                null
            }
        }

        /**
         * Get the package name of the currently active window.
         */
        fun getCurrentPackageName(): String? {
            val service = instance ?: return null
            return try {
                service.rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                Log.e("WhizAccessibilityService", "Error getting package name", e)
                null
            }
        }
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

        // Register broadcast receiver for on-demand UI dumps
        dumpReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "UI dump broadcast received")
                dumpCurrentUI()
            }
        }
        registerReceiver(dumpReceiver, IntentFilter(ACTION_DUMP_UI), Context.RECEIVER_EXPORTED)
        Log.d(TAG, "UI dump broadcast receiver registered")
    }

    /**
     * Dump the current UI hierarchy to /sdcard/Download/ for debugging.
     * Triggered via: adb shell am broadcast -a com.example.whiz.DUMP_UI -p <package>
     */
    private fun dumpCurrentUI() {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "UI dump: no root node available")
                return
            }
            val sb = StringBuilder()
            val packageName = rootNode.packageName?.toString() ?: "unknown"
            sb.appendLine("=== UI Dump (on-demand) ===")
            sb.appendLine("Timestamp: ${System.currentTimeMillis()}")
            sb.appendLine("Package: $packageName")
            sb.appendLine("")

            // Search for "Save" by text (bypasses getChild() traversal)
            sb.appendLine("=== findByText(\"Save\") results ===")
            try {
                val saveNodes = rootNode.findAccessibilityNodeInfosByText("Save")
                if (saveNodes.isNullOrEmpty()) {
                    sb.appendLine("  (none found)")
                } else {
                    saveNodes.forEach { node ->
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        sb.appendLine("  [${node.className}] id=${node.viewIdResourceName} text=\"${node.text}\" desc=\"${node.contentDescription}\" bounds=$bounds clickable=${node.isClickable} pkg=${node.packageName}")
                        node.recycle()
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("  Error: ${e.message}")
            }
            sb.appendLine("")

            // Dump all accessibility windows (not just rootInActiveWindow)
            sb.appendLine("=== All Accessibility Windows ===")
            try {
                val allWindows = windows
                sb.appendLine("Window count: ${allWindows.size}")
                allWindows.forEachIndexed { idx, window ->
                    sb.appendLine("")
                    sb.appendLine("--- Window $idx: id=${window.id} type=${window.type} layer=${window.layer} title=\"${window.title}\" ---")
                    val windowRoot = window.root
                    if (windowRoot != null) {
                        AccessibilityDumpUtil.dumpNodeRecursive(windowRoot, sb, 1)
                        windowRoot.recycle()
                    } else {
                        sb.appendLine("  (no root node)")
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("  Error iterating windows: ${e.message}")
            }
            sb.appendLine("")

            sb.appendLine("=== Node Tree (rootInActiveWindow) ===")
            AccessibilityDumpUtil.dumpNodeRecursive(rootNode, sb, 0)

            val fileName = "whiz_ui_dump_manual_${System.currentTimeMillis()}.txt"
            val file = java.io.File("/sdcard/Download", fileName)
            file.writeText(sb.toString())
            Log.i(TAG, "UI dump saved to: ${file.absolutePath}")
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump UI", e)
        }
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
        dumpReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        dumpReceiver = null
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
     * Performs a tap gesture at the specified screen coordinates.
     * @param x X coordinate to tap
     * @param y Y coordinate to tap
     * @return true if the tap gesture was successfully dispatched
     */
    suspend fun performTapGesture(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Gesture dispatching requires API 24+")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val path = Path().apply {
                    moveTo(x, y)
                }

                val gestureBuilder = GestureDescription.Builder()
                val strokeDescription = GestureDescription.StrokeDescription(path, 0, 50)
                gestureBuilder.addStroke(strokeDescription)
                val gesture = gestureBuilder.build()

                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Tap gesture completed at ($x, $y)")
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }

                val dispatched = dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    Log.e(TAG, "Failed to dispatch tap gesture at ($x, $y)")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing tap gesture at ($x, $y)", e)
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
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