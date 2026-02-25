package com.example.whiz.tools

import android.Manifest
import android.app.AlarmManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import android.view.accessibility.AccessibilityNodeInfo
import com.example.whiz.accessibility.WhizAccessibilityService
import kotlinx.coroutines.delay
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.ui.viewmodels.VoiceManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device Control Tools - Direct Android intents and system APIs for common device actions.
 *
 * These use direct intents/APIs (alarms, calendar, flashlight, phone, volume)
 * rather than UI automation via the accessibility service. Faster and more reliable.
 */
@Singleton
class DeviceControlTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenAgentTools: ScreenAgentTools
) {
    private val TAG = "DeviceControlTools"

    companion object {
        /** Partial transcription buffered before setAlarm fires an intent that steals focus. */
        @Volatile
        var bufferedPartial: String = ""

        @Volatile
        var bufferedPartialTimestamp: Long = 0L

    }

    // Track flashlight state since CameraManager doesn't expose it directly
    private var flashlightOn = false

    // ========== Alarm / Timer ==========

    fun setAlarm(params: JSONObject): JSONObject {
        val hour = params.getInt("hour")
        val minute = params.getInt("minute")
        val label = if (params.has("label")) params.getString("label") else null

        Log.i(TAG, "Setting alarm for $hour:${minute.toString().padStart(2, '0')}" +
                (if (label != null) " ($label)" else ""))

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label != null) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            // Buffer the current partial transcription before the Clock app steals focus.
            // Unlike deleteAlarm/setTimer/dialPhone, setAlarm doesn't start the bubble overlay
            // so the recognizer briefly dies. VoiceManager.onAppForegrounded() restores it.
            val currentPartial = VoiceManager.instance?.transcriptionState?.value ?: ""
            if (currentPartial.isNotBlank()) {
                Log.i(TAG, "Buffering partial transcription before setAlarm intent: '$currentPartial'")
                bufferedPartial = currentPartial
                bufferedPartialTimestamp = System.currentTimeMillis()
            }

            context.startActivity(intent)
            dismissResolverDialog()
            val timeStr = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            JSONObject().apply {
                put("success", true)
                put("message", "Alarm set for $timeStr" + (if (label != null) " ($label)" else ""))
                put("hour", hour)
                put("minute", minute)
                label?.let { put("label", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to set alarm: ${e.message}")
            }
        }
    }

    fun setTimer(params: JSONObject): JSONObject {
        val seconds = params.getInt("seconds")
        val label = if (params.has("label")) params.getString("label") else null

        Log.i(TAG, "Setting timer for $seconds seconds" +
                (if (label != null) " ($label)" else ""))

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            if (label != null) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            BubbleOverlayService.start(context)
            dismissResolverDialog()
            val minutes = seconds / 60
            val remainingSeconds = seconds % 60
            val timeStr = if (minutes > 0) {
                "${minutes}m${if (remainingSeconds > 0) " ${remainingSeconds}s" else ""}"
            } else {
                "${seconds}s"
            }
            JSONObject().apply {
                put("success", true)
                put("message", "Timer set for $timeStr" + (if (label != null) " ($label)" else ""))
                put("seconds", seconds)
                label?.let { put("label", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set timer", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to set timer: ${e.message}")
            }
        }
    }

    fun dismissAlarm(): JSONObject {
        Log.i(TAG, "Dismissing alarm")

        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            BubbleOverlayService.start(context)
            JSONObject().apply {
                put("success", true)
                put("message", "Alarm dismissed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss alarm", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to dismiss alarm: ${e.message}")
            }
        }
    }

    fun dismissTimer(): JSONObject {
        Log.i(TAG, "Dismissing timer")

        val intent = Intent(AlarmClock.ACTION_DISMISS_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            JSONObject().apply {
                put("success", true)
                put("message", "Timer dismissed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss timer", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to dismiss timer: ${e.message}")
            }
        }
    }

    suspend fun deleteAlarm(params: JSONObject): JSONObject {
        val hour = params.getInt("hour")
        val minute = params.getInt("minute")
        val label = if (params.has("label") && !params.isNull("label")) params.getString("label") else null

        // Format the time string to match Clock app display (e.g. "4:30 PM")
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val targetTimeStr = "$displayHour:${minute.toString().padStart(2, '0')} $amPm"

        Log.i(TAG, "Deleting alarm for $targetTimeStr" + (if (label != null) " ($label)" else ""))

        // Step 1: Open the alarm list (target Google Clock to avoid resolver dialog)
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.google.android.deskclock")
        }

        return try {
            context.startActivity(intent)
            BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            BubbleOverlayService.start(context)

            // Wait for the Clock app's alarm list to appear
            val accessibilityService = WhizAccessibilityService.getInstance()
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "Accessibility service not available")
                }

            val waitStart = System.currentTimeMillis()
            val waitTimeout = 5000L
            while (System.currentTimeMillis() - waitStart < waitTimeout) {
                val root = accessibilityService.getCurrentRootNode()
                if (root != null) {
                    val alarmList = root.findAccessibilityNodeInfosByViewId(
                        "com.google.android.deskclock:id/alarm_recycler_view"
                    )
                    val found = alarmList != null && alarmList.isNotEmpty()
                    alarmList?.forEach { it.recycle() }
                    root.recycle()
                    if (found) {
                        Log.d(TAG, "Alarm list appeared after ${System.currentTimeMillis() - waitStart}ms")
                        break
                    }
                }
                delay(200)
            }

            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val centerX = screenWidth / 2f

            // Scroll to top of alarm list first so we search from the beginning
            val maxScrollToTopAttempts = 10
            for (i in 1..maxScrollToTopAttempts) {
                val scrolledUp = accessibilityService.performScrollGesture(
                    centerX, screenHeight * 0.3f, centerX, screenHeight * 0.7f, duration = 300
                )
                Log.d(TAG, "Scroll-to-top attempt $i: $scrolledUp")
                delay(400)

                // Check if we've reached the top by seeing if scroll had no effect
                val rootBefore = accessibilityService.getCurrentRootNode()
                val clocksBefore = rootBefore?.findAccessibilityNodeInfosByViewId(
                    "com.google.android.deskclock:id/digital_clock"
                )?.mapNotNull { it.text?.toString() }?.toSet() ?: emptySet()
                clocksBefore.let { rootBefore?.recycle() }

                accessibilityService.performScrollGesture(
                    centerX, screenHeight * 0.3f, centerX, screenHeight * 0.7f, duration = 300
                )
                delay(400)

                val rootAfter = accessibilityService.getCurrentRootNode()
                val clocksAfter = rootAfter?.findAccessibilityNodeInfosByViewId(
                    "com.google.android.deskclock:id/digital_clock"
                )?.mapNotNull { it.text?.toString() }?.toSet() ?: emptySet()
                rootAfter?.recycle()

                if (clocksBefore.isNotEmpty() && clocksBefore == clocksAfter) {
                    Log.i(TAG, "Reached top of alarm list after $i scroll-to-top attempts")
                    break
                }
            }

            val maxScrollAttempts = 20
            var previousAlarmTimes = setOf<String>()

            for (attempt in 0..maxScrollAttempts) {
                val rootNode = accessibilityService.getCurrentRootNode()
                if (rootNode == null) {
                    Log.w(TAG, "Could not get root node on attempt $attempt")
                    delay(500)
                    continue
                }

                // Find all digital_clock nodes currently visible
                val clockNodes = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.deskclock:id/digital_clock"
                )

                val currentAlarmTimes = clockNodes.mapNotNull { it.text?.toString() }.toSet()
                Log.d(TAG, "Attempt $attempt: visible alarms = $currentAlarmTimes")

                // Check each alarm card for a match
                val alarmCards = rootNode.findAccessibilityNodeInfosByViewId(
                    "com.google.android.deskclock:id/alarm_card"
                )

                for (card in alarmCards) {
                    val contentDesc = card.contentDescription?.toString() ?: continue

                    // Check if this card matches the target time
                    if (!contentDesc.contains(targetTimeStr)) continue

                    // Check if alarm is active (not disabled)
                    if (contentDesc.contains("disabled")) {
                        Log.d(TAG, "Found $targetTimeStr but it's disabled, skipping")
                        continue
                    }

                    // Optional: check label match
                    if (label != null && !contentDesc.contains(label, ignoreCase = true)) {
                        Log.d(TAG, "Found active $targetTimeStr but label doesn't match ('$label' not in '$contentDesc')")
                        continue
                    }

                    Log.i(TAG, "Found matching active alarm: $contentDesc")

                    // Click the alarm card to expand the bottom sheet
                    val cardClicked = accessibilityService.clickNode(card)
                    Log.i(TAG, "Clicked alarm card to expand: $cardClicked")
                    clockNodes.forEach { it.recycle() }
                    alarmCards.forEach { it.recycle() }
                    rootNode.recycle()

                    if (!cardClicked) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "Found alarm for $targetTimeStr but failed to click the card to expand it")
                        }
                    }

                    // Wait for the delete button to appear in the bottom sheet
                    var deleteButton: android.view.accessibility.AccessibilityNodeInfo? = null
                    var deleteRoot: android.view.accessibility.AccessibilityNodeInfo? = null
                    val deleteWaitStart = System.currentTimeMillis()
                    val deleteWaitTimeout = 3000L
                    while (System.currentTimeMillis() - deleteWaitStart < deleteWaitTimeout) {
                        delay(300)
                        deleteRoot = accessibilityService.getCurrentRootNode() ?: continue
                        val deleteButtons = deleteRoot.findAccessibilityNodeInfosByViewId(
                            "com.google.android.deskclock:id/delete_button"
                        )
                        if (deleteButtons != null && deleteButtons.isNotEmpty()) {
                            deleteButton = deleteButtons.first()
                            // Recycle the extras but keep the first one
                            deleteButtons.drop(1).forEach { it.recycle() }
                            Log.d(TAG, "Delete button appeared after ${System.currentTimeMillis() - deleteWaitStart}ms")
                            break
                        }
                        deleteButtons?.forEach { it.recycle() }
                        deleteRoot.recycle()
                        deleteRoot = null
                    }

                    if (deleteButton == null) {
                        deleteRoot?.recycle()
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "Expanded alarm card for $targetTimeStr but delete button did not appear")
                        }
                    }

                    // Click the delete button
                    val deleteClicked = accessibilityService.clickNode(deleteButton)
                    Log.i(TAG, "Clicked delete button: $deleteClicked")
                    deleteButton.recycle()
                    deleteRoot?.recycle()

                    if (!deleteClicked) {
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "Found delete button for alarm $targetTimeStr but failed to click it")
                        }
                    }

                    // Verify the alarm was actually deleted by checking it's gone
                    delay(500)
                    val verifyRoot = accessibilityService.getCurrentRootNode()
                    if (verifyRoot != null) {
                        val verifyCards = verifyRoot.findAccessibilityNodeInfosByViewId(
                            "com.google.android.deskclock:id/alarm_card"
                        )
                        var stillExists = false
                        if (verifyCards != null) {
                            for (verifyCard in verifyCards) {
                                val verifyDesc = verifyCard.contentDescription?.toString() ?: continue
                                if (verifyDesc.contains(targetTimeStr)) {
                                    val labelMatches = label == null || verifyDesc.contains(label, ignoreCase = true)
                                    if (labelMatches && !verifyDesc.contains("disabled")) {
                                        stillExists = true
                                        break
                                    }
                                }
                            }
                            verifyCards.forEach { it.recycle() }
                        }
                        verifyRoot.recycle()

                        if (stillExists) {
                            Log.e(TAG, "Alarm $targetTimeStr still exists after clicking delete")
                            return JSONObject().apply {
                                put("success", false)
                                put("error", "Clicked delete but alarm $targetTimeStr still exists")
                            }
                        }
                        Log.i(TAG, "Verified: alarm $targetTimeStr has been deleted")
                    } else {
                        Log.w(TAG, "Could not get root node for verification, but delete click succeeded")
                    }

                    return JSONObject().apply {
                        put("success", true)
                        put("message", "Deleted alarm for $targetTimeStr")
                        put("alarm_time", targetTimeStr)
                        label?.let { put("label", it) }
                    }
                }

                clockNodes.forEach { it.recycle() }
                alarmCards.forEach { it.recycle() }
                rootNode.recycle()

                // Check if we've reached the end (same alarms visible as last time)
                if (currentAlarmTimes.isNotEmpty() && currentAlarmTimes == previousAlarmTimes) {
                    Log.i(TAG, "Alarm list stopped scrolling (same alarms visible), alarm not found")
                    break
                }
                previousAlarmTimes = currentAlarmTimes

                if (attempt < maxScrollAttempts) {
                    // Scroll about 5-6 alarm cards at a time
                    val startY = screenHeight * 0.80f
                    val endY = screenHeight * 0.20f
                    Log.d(TAG, "Scrolling alarm list (attempt ${attempt + 1})")
                    accessibilityService.performScrollGesture(
                        centerX, startY, centerX, endY, duration = 500
                    )
                    delay(600)
                }
            }

            JSONObject().apply {
                put("success", false)
                put("error", "Could not find active alarm for $targetTimeStr" +
                        (if (label != null) " with label '$label'" else ""))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete alarm", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to delete alarm: ${e.message}")
            }
        }
    }

    /**
     * Dismiss a currently ringing AMdroid alarm via accessibility service UI automation.
     *
     * Flow:
     * 1. If already on AMdroid full-screen alarm view (has rltvLytAlarmFullScreen) → click fab_off
     * 2. If AMdroid is in foreground with snackbar ("Ongoing alarm" + SHOW button) → click SHOW → wait → click fab_off
     * 3. If AMdroid not in foreground → launch AMdroid → wait for snackbar or full-screen → handle as 1 or 2
     * 4. No ringing alarm found → return error
     */
    suspend fun dismissAmdroidAlarm(): JSONObject {
        Log.i(TAG, "Dismissing AMdroid alarm")

        val accessibilityService = WhizAccessibilityService.getInstance()
            ?: return JSONObject().apply {
                put("success", false)
                put("error", "Accessibility service not available")
            }

        val AMDROID_PACKAGE = "com.amdroidalarmclock.amdroid"
        val ID_FULL_SCREEN = "$AMDROID_PACKAGE:id/rltvLytAlarmFullScreen"
        val ID_FAB_OFF = "$AMDROID_PACKAGE:id/fab_off"
        val ID_SNACKBAR_TEXT = "$AMDROID_PACKAGE:id/snackbar_text"
        val ID_SNACKBAR_ACTION = "$AMDROID_PACKAGE:id/snackbar_action"

        // --- Helper: click fab_off dismiss button ---
        suspend fun clickDismissButton(): JSONObject {
            val waitStart = System.currentTimeMillis()
            val waitTimeout = 3000L
            while (System.currentTimeMillis() - waitStart < waitTimeout) {
                val root = accessibilityService.getCurrentRootNode()
                if (root != null) {
                    val fabNodes = root.findAccessibilityNodeInfosByViewId(ID_FAB_OFF)
                    if (fabNodes != null && fabNodes.isNotEmpty()) {
                        val fabOff = fabNodes.first()
                        val clicked = accessibilityService.clickNode(fabOff)
                        Log.i(TAG, "Clicked fab_off dismiss button: $clicked")
                        fabNodes.forEach { it.recycle() }
                        root.recycle()
                        return if (clicked) {
                            JSONObject().apply {
                                put("success", true)
                                put("message", "AMdroid alarm dismissed")
                            }
                        } else {
                            JSONObject().apply {
                                put("success", false)
                                put("error", "Found AMdroid dismiss button but failed to click it")
                            }
                        }
                    }
                    fabNodes?.forEach { it.recycle() }
                    root.recycle()
                }
                delay(200)
            }
            return JSONObject().apply {
                put("success", false)
                put("error", "AMdroid dismiss button (fab_off) not found")
            }
        }

        // --- Helper: click SHOW on snackbar, then dismiss ---
        suspend fun clickShowThenDismiss(): JSONObject {
            val waitStart = System.currentTimeMillis()
            val waitTimeout = 3000L
            while (System.currentTimeMillis() - waitStart < waitTimeout) {
                val root = accessibilityService.getCurrentRootNode()
                if (root != null) {
                    val showButtons = root.findAccessibilityNodeInfosByViewId(ID_SNACKBAR_ACTION)
                    if (showButtons != null && showButtons.isNotEmpty()) {
                        val showBtn = showButtons.first()
                        val clicked = accessibilityService.clickNode(showBtn)
                        Log.i(TAG, "Clicked SHOW snackbar button: $clicked")
                        showButtons.forEach { it.recycle() }
                        root.recycle()
                        if (clicked) {
                            // Wait for full-screen alarm view, then click dismiss
                            delay(500)
                            return clickDismissButton()
                        }
                        // Click failed, retry after a short wait
                        delay(300)
                        continue
                    }
                    showButtons?.forEach { it.recycle() }
                    root.recycle()
                }
                delay(300)
            }
            return JSONObject().apply {
                put("success", false)
                put("error", "AMdroid SHOW snackbar button not found or not clickable")
            }
        }

        return try {
            // Step 1: Check if already on full-screen alarm view
            var root = accessibilityService.getCurrentRootNode()
            if (root != null) {
                val fullScreenNodes = root.findAccessibilityNodeInfosByViewId(ID_FULL_SCREEN)
                if (fullScreenNodes != null && fullScreenNodes.isNotEmpty()) {
                    Log.i(TAG, "Already on AMdroid full-screen alarm view")
                    fullScreenNodes.forEach { it.recycle() }
                    root.recycle()
                    return clickDismissButton()
                }
                fullScreenNodes?.forEach { it.recycle() }

                // Step 2: Check if AMdroid is in foreground with snackbar
                val snackbarNodes = root.findAccessibilityNodeInfosByViewId(ID_SNACKBAR_TEXT)
                if (snackbarNodes != null && snackbarNodes.isNotEmpty()) {
                    val text = snackbarNodes.firstOrNull()?.text?.toString() ?: ""
                    Log.i(TAG, "Found AMdroid snackbar: '$text'")
                    snackbarNodes.forEach { it.recycle() }
                    root.recycle()
                    if (text.contains("Ongoing alarm", ignoreCase = true)) {
                        return clickShowThenDismiss()
                    }
                }
                snackbarNodes?.forEach { it.recycle() }
                root.recycle()
            }

            // Step 3: Launch AMdroid and wait for alarm UI
            Log.i(TAG, "Launching AMdroid to find ringing alarm")
            val launchIntent = context.packageManager.getLaunchIntentForPackage(AMDROID_PACKAGE)
            if (launchIntent == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "AMdroid app not installed")
                }
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            BubbleOverlayService.start(context)

            val waitStart = System.currentTimeMillis()
            val waitTimeout = 5000L
            while (System.currentTimeMillis() - waitStart < waitTimeout) {
                delay(300)
                root = accessibilityService.getCurrentRootNode() ?: continue

                // Check for full-screen alarm view first
                val fullScreenNodes = root.findAccessibilityNodeInfosByViewId(ID_FULL_SCREEN)
                if (fullScreenNodes != null && fullScreenNodes.isNotEmpty()) {
                    Log.i(TAG, "AMdroid full-screen alarm view appeared after launch")
                    fullScreenNodes.forEach { it.recycle() }
                    root.recycle()
                    return clickDismissButton()
                }
                fullScreenNodes?.forEach { it.recycle() }

                // Check for snackbar
                val snackbarNodes = root.findAccessibilityNodeInfosByViewId(ID_SNACKBAR_TEXT)
                if (snackbarNodes != null && snackbarNodes.isNotEmpty()) {
                    val text = snackbarNodes.firstOrNull()?.text?.toString() ?: ""
                    Log.i(TAG, "AMdroid snackbar appeared after launch: '$text'")
                    snackbarNodes.forEach { it.recycle() }
                    root.recycle()
                    if (text.contains("Ongoing alarm", ignoreCase = true)) {
                        return clickShowThenDismiss()
                    }
                }
                snackbarNodes?.forEach { it.recycle() }
                root.recycle()
            }

            // Step 4: No ringing alarm found
            JSONObject().apply {
                put("success", false)
                put("error", "No ringing AMdroid alarm detected")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss AMdroid alarm", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to dismiss AMdroid alarm: ${e.message}")
            }
        }
    }

    fun getNextAlarm(): JSONObject {
        Log.i(TAG, "Getting next alarm")

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val alarmInfo = alarmManager.nextAlarmClock

            if (alarmInfo != null) {
                val triggerTime = alarmInfo.triggerTime
                val date = Date(triggerTime)
                val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                val dateFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                val fullFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

                JSONObject().apply {
                    put("success", true)
                    put("has_alarm", true)
                    put("time", timeFormat.format(date))
                    put("date", dateFormat.format(date))
                    put("iso_time", fullFormat.format(date))
                    put("trigger_millis", triggerTime)
                    put("message", "Next alarm: ${timeFormat.format(date)} on ${dateFormat.format(date)}")
                }
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("has_alarm", false)
                    put("message", "No upcoming alarms scheduled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get next alarm", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to get next alarm: ${e.message}")
            }
        }
    }

    // ========== Flashlight ==========

    fun toggleFlashlight(params: JSONObject): JSONObject {
        val turnOn = params.getBoolean("turn_on")
        Log.i(TAG, "Toggling flashlight: ${if (turnOn) "ON" else "OFF"}")

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "No camera available for flashlight")
                }

            cameraManager.setTorchMode(cameraId, turnOn)
            flashlightOn = turnOn

            JSONObject().apply {
                put("success", true)
                put("flashlight_on", turnOn)
                put("message", "Flashlight turned ${if (turnOn) "on" else "off"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to toggle flashlight: ${e.message}")
            }
        }
    }

    // ========== Calendar ==========

    /**
     * Dismiss the current calendar draft by pressing Cancel, then confirming Discard if prompted.
     * Used for redrafting when the user wants to change an already-drafted event.
     */
    suspend fun dismissCalendarDraft(): Boolean {
        Log.i(TAG, "dismissCalendarDraft called")

        val accessibilityService = WhizAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.w(TAG, "dismissCalendarDraft: accessibility service not available")
            return false
        }

        val rootNode = accessibilityService.getCurrentRootNode()
        if (rootNode == null) {
            Log.w(TAG, "dismissCalendarDraft: no root node")
            return false
        }

        try {
            val currentPackage = rootNode.packageName?.toString() ?: ""
            if (currentPackage != "com.google.android.calendar") {
                Log.i(TAG, "dismissCalendarDraft: not in Google Calendar ($currentPackage), skipping")
                return true // Not in calendar, nothing to dismiss
            }

            // Find and click the Cancel/close button (id=close, desc="Cancel")
            val closeNodes = rootNode.findAccessibilityNodeInfosByViewId(
                "com.google.android.calendar:id/close"
            )
            val closeButton = closeNodes?.firstOrNull()
            if (closeButton == null) {
                Log.w(TAG, "dismissCalendarDraft: Cancel button not found by ID, trying by description")
                closeNodes?.forEach { it.recycle() }

                // Fallback: search by content description
                val cancelByText = rootNode.findAccessibilityNodeInfosByText("Cancel")
                val cancelButton = cancelByText?.firstOrNull { it.isClickable }
                if (cancelButton == null) {
                    Log.w(TAG, "dismissCalendarDraft: Cancel button not found at all")
                    cancelByText?.forEach { it.recycle() }
                    return false
                }
                cancelButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cancelByText.forEach { it.recycle() }
            } else {
                closeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                closeNodes.forEach { it.recycle() }
            }
            Log.i(TAG, "dismissCalendarDraft: Cancel button clicked")
        } finally {
            rootNode.recycle()
        }

        // Wait for potential "Discard changes?" confirmation dialog
        for (attempt in 1..8) {
            delay(300)
            val dialogRoot = accessibilityService.getCurrentRootNode() ?: continue
            try {
                // Look for "Discard" button in confirmation dialog
                val discardNodes = dialogRoot.findAccessibilityNodeInfosByText("Discard")
                val discardButton = discardNodes?.firstOrNull { it.isClickable }
                if (discardButton != null) {
                    discardButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "dismissCalendarDraft: Discard button clicked")
                    discardNodes.forEach { it.recycle() }
                    break
                }
                discardNodes?.forEach { it.recycle() }

                // Check if the event editor is already gone (no title field = dismissed without dialog)
                val titleNodes = dialogRoot.findAccessibilityNodeInfosByViewId(
                    "com.google.android.calendar:id/title"
                )
                if (titleNodes.isNullOrEmpty()) {
                    Log.i(TAG, "dismissCalendarDraft: event editor already dismissed")
                    break
                }
                titleNodes.forEach { it.recycle() }
            } finally {
                dialogRoot.recycle()
            }
        }

        // Wait for calendar editor to fully close
        delay(500)
        Log.i(TAG, "dismissCalendarDraft: completed")
        return true
    }

    /**
     * Save a calendar event directly via ContentProvider instead of tapping the Save button.
     * Event params are passed directly from the server.
     */
    fun saveCalendarEventViaContentProvider(params: JSONObject): JSONObject {
        Log.i(TAG, "saveCalendarEventViaContentProvider called with params: ${params.toString(2)}")

        return try {
            // Query for the user's primary writable calendar
            val calendarInfo = getWritableCalendarId()
            if (calendarInfo == null) {
                Log.e(TAG, "No writable calendar found")
                return JSONObject().apply {
                    put("success", false)
                    put("error", "No writable calendar found on device")
                }
            }
            val calendarId = calendarInfo.calendarId
            Log.i(TAG, "Using calendar ID: $calendarId (account=${calendarInfo.accountName}, type=${calendarInfo.accountType})")

            val title = params.getString("title")
            val beginTimeStr = params.getString("begin_time")
            val allDay = params.optBoolean("all_day", false)
            val tz = params.optString("timezone", "").ifEmpty { TimeZone.getDefault().id }

            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginMillis = isoFormat.parse(beginTimeStr)?.time
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "Invalid begin_time format. Use ISO 8601 (e.g., 2025-01-15T14:00:00)")
                }

            val endMillis = if (params.has("end_time") && !params.isNull("end_time")) {
                isoFormat.parse(params.getString("end_time"))?.time ?: (beginMillis + 3600000)
            } else {
                beginMillis + 3600000 // default 1 hour
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, beginMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)

                if (params.has("description") && !params.isNull("description")) {
                    put(CalendarContract.Events.DESCRIPTION, params.getString("description"))
                }
                if (params.has("location") && !params.isNull("location")) {
                    put(CalendarContract.Events.EVENT_LOCATION, params.getString("location"))
                }
                if (params.has("recurrence") && !params.isNull("recurrence")) {
                    put(CalendarContract.Events.RRULE, params.getString("recurrence"))
                }
                if (params.has("availability") && !params.isNull("availability")) {
                    val availInt = when (params.getString("availability").lowercase()) {
                        "busy" -> CalendarContract.Events.AVAILABILITY_BUSY
                        "free" -> CalendarContract.Events.AVAILABILITY_FREE
                        else -> CalendarContract.Events.AVAILABILITY_BUSY
                    }
                    put(CalendarContract.Events.AVAILABILITY, availInt)
                }
                if (params.has("access_level") && !params.isNull("access_level")) {
                    val levelInt = when (params.getString("access_level").lowercase()) {
                        "private" -> CalendarContract.Events.ACCESS_PRIVATE
                        "public" -> CalendarContract.Events.ACCESS_PUBLIC
                        else -> CalendarContract.Events.ACCESS_DEFAULT
                    }
                    put(CalendarContract.Events.ACCESS_LEVEL, levelInt)
                }
            }

            val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (eventUri != null) {
                Log.i(TAG, "Calendar event inserted successfully: $eventUri")
                JSONObject().apply {
                    put("success", true)
                    put("message", "Calendar event '$title' saved successfully")
                    put("event_uri", eventUri.toString())
                    put("account_name", calendarInfo.accountName)
                    put("account_type", calendarInfo.accountType)
                }
            } else {
                Log.e(TAG, "ContentProvider insert returned null URI")
                JSONObject().apply {
                    put("success", false)
                    put("error", "Failed to insert calendar event - ContentProvider returned null")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar permission not granted", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Calendar permission not granted: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calendar event via ContentProvider", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to save calendar event: ${e.message}")
            }
        }
    }

    data class CalendarInfo(val calendarId: Long, val accountName: String, val accountType: String)

    /**
     * Find the first writable calendar on the device.
     * Prefers the primary calendar; falls back to any writable calendar.
     * Returns calendar ID along with account name/type needed for requestSync().
     */
    private fun getWritableCalendarId(): CalendarInfo? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        var primaryInfo: CalendarInfo? = null
        var firstWritableInfo: CalendarInfo? = null

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection, selection, selectionArgs, null
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val primaryIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountNameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val accountTypeIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val isPrimary = cursor.getInt(primaryIdx) == 1
                val name = cursor.getString(nameIdx)
                val accountName = cursor.getString(accountNameIdx)
                val accountType = cursor.getString(accountTypeIdx)
                Log.d(TAG, "Found writable calendar: id=$id, name=$name, primary=$isPrimary, account=$accountName ($accountType)")

                val info = CalendarInfo(id, accountName, accountType)
                if (isPrimary && primaryInfo == null) {
                    primaryInfo = info
                }
                if (firstWritableInfo == null) {
                    firstWritableInfo = info
                }
            }
        }

        val selected = primaryInfo ?: firstWritableInfo
        Log.i(TAG, "Selected calendar id=${selected?.calendarId} (primaryId=${primaryInfo?.calendarId}, firstWritableId=${firstWritableInfo?.calendarId})")
        return selected
    }

    fun draftCalendarEvent(params: JSONObject): JSONObject {
        val title = params.getString("title")
        val beginTimeStr = params.getString("begin_time")
        val endTimeStr = if (params.has("end_time")) params.getString("end_time") else null
        val description = if (params.has("description")) params.getString("description") else null
        val location = if (params.has("location")) params.getString("location") else null
        val allDay = if (params.has("all_day")) params.getBoolean("all_day") else false
        val attendees = if (params.has("attendees")) params.getString("attendees") else null
        val recurrence = if (params.has("recurrence")) params.getString("recurrence") else null
        val availability = if (params.has("availability")) params.getString("availability") else null
        val accessLevel = if (params.has("access_level")) params.getString("access_level") else null
        val timezone = if (params.has("timezone")) params.getString("timezone") else null

        Log.i(TAG, "Drafting calendar event: '$title' at $beginTimeStr")

        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTime = isoFormat.parse(beginTimeStr)?.time
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "Invalid begin_time format. Use ISO 8601 (e.g., 2025-01-15T14:00:00)")
                }

            val endTime = if (endTimeStr != null) {
                isoFormat.parse(endTimeStr)?.time ?: (beginTime + 3600000) // default 1 hour
            } else {
                beginTime + 3600000 // default 1 hour
            }

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                putExtra(CalendarContract.Events.ALL_DAY, allDay)
                description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
                location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
                attendees?.let { putExtra(Intent.EXTRA_EMAIL, it) }
                recurrence?.let { putExtra(CalendarContract.Events.RRULE, it) }
                availability?.let {
                    val availInt = when (it.lowercase()) {
                        "busy" -> CalendarContract.Events.AVAILABILITY_BUSY
                        "free" -> CalendarContract.Events.AVAILABILITY_FREE
                        else -> CalendarContract.Events.AVAILABILITY_BUSY
                    }
                    putExtra(CalendarContract.Events.AVAILABILITY, availInt)
                }
                accessLevel?.let {
                    val levelInt = when (it.lowercase()) {
                        "private" -> CalendarContract.Events.ACCESS_PRIVATE
                        "public" -> CalendarContract.Events.ACCESS_PUBLIC
                        else -> CalendarContract.Events.ACCESS_DEFAULT
                    }
                    putExtra(CalendarContract.Events.ACCESS_LEVEL, levelInt)
                }
                timezone?.let { putExtra(CalendarContract.Events.EVENT_TIMEZONE, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Start bubble overlay before launching calendar so user can still talk to Whiz
            val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
            if (hasOverlayPermission) {
                BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            }

            context.startActivity(intent)

            if (hasOverlayPermission) {
                try {
                    BubbleOverlayService.start(context)
                    Log.i(TAG, "Bubble overlay started for calendar")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start bubble overlay for calendar", e)
                    BubbleOverlayService.pendingStartTimestamp = 0L
                }
            }

            // Dismiss the keyboard that appears when Google Calendar focuses the title field
            dismissCalendarKeyboard()

            JSONObject().apply {
                put("success", true)
                put("message", "Calendar app opened with event '$title' pre-filled.")
                put("title", title)
                put("begin_time", beginTimeStr)
                endTimeStr?.let { put("end_time", it) }
                description?.let { put("description", it) }
                location?.let { put("location", it) }
                attendees?.let { put("attendees", it) }
                recurrence?.let { put("recurrence", it) }
                availability?.let { put("availability", it) }
                accessLevel?.let { put("access_level", it) }
                timezone?.let { put("timezone", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draft calendar event", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to draft calendar event: ${e.message}")
            }
        }
    }

    // ========== Phone ==========

    fun dialPhoneNumber(params: JSONObject): JSONObject {
        val phoneNumber = params.getString("phone_number")
        Log.i(TAG, "Dialing phone number: $phoneNumber")

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Start bubble overlay before launching dialer so user can still talk to Whiz
            val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
            if (hasOverlayPermission) {
                BubbleOverlayService.pendingStartTimestamp = System.currentTimeMillis()
            }

            context.startActivity(intent)

            if (hasOverlayPermission) {
                try {
                    BubbleOverlayService.start(context)
                    Log.i(TAG, "Bubble overlay started for dialer")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start bubble overlay for dialer", e)
                    BubbleOverlayService.pendingStartTimestamp = 0L
                }
            }

            JSONObject().apply {
                put("success", true)
                put("message", "Phone dialer opened with number $phoneNumber. User needs to tap the call button to place the call.")
                put("phone_number", phoneNumber)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dial phone number", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to dial phone number: ${e.message}")
            }
        }
    }

    // ========== Volume ==========

    fun setVolume(params: JSONObject): JSONObject {
        val volumeLevel = params.getInt("volume_level")
        val streamName = if (params.has("stream")) params.getString("stream") else "music"

        Log.i(TAG, "Setting volume: stream=$streamName, level=$volumeLevel")

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val streamType = when (streamName) {
                "music" -> AudioManager.STREAM_MUSIC
                "ring" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }

            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val clampedLevel = volumeLevel.coerceIn(0, maxVolume)

            audioManager.setStreamVolume(streamType, clampedLevel, AudioManager.FLAG_SHOW_UI)

            val currentVolume = audioManager.getStreamVolume(streamType)

            JSONObject().apply {
                put("success", true)
                put("message", "Volume set to $currentVolume/$maxVolume for $streamName")
                put("volume_level", currentVolume)
                put("max_volume", maxVolume)
                put("stream", streamName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to set volume: ${e.message}")
            }
        }
    }

    // ========== Contacts Lookup ==========

    fun lookupPhoneContacts(params: JSONObject): JSONObject {
        val name = params.getString("name")
        Log.i(TAG, "Looking up phone contacts for: $name")

        // Check READ_CONTACTS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "READ_CONTACTS permission not granted")
            return JSONObject().apply {
                put("success", true)
                put("contacts", JSONArray())
                put("permission_denied", true)
                put("message", "Contacts permission not granted.")
            }
        }

        return try {
            val contactsList = JSONArray()
            val contactIds = mutableListOf<Pair<String, String>>() // (contact_id, display_name)

            // Query contacts by display name
            val contactsCursor: Cursor? = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
                arrayOf("%$name%"),
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            contactsCursor?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < 5) {
                    val contactId = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    )
                    val displayName = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    )
                    contactIds.add(Pair(contactId, displayName))
                    count++
                }
            }

            // For each contact, get phone numbers, emails, and addresses
            for ((contactId, displayName) in contactIds) {
                val contactJson = JSONObject().apply {
                    put("display_name", displayName)
                }

                // Phone numbers
                val phoneNumbers = JSONObject()
                val phoneCursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                phoneCursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        )
                        val type = cursor.getInt(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                        )
                        val label = when (type) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                            else -> "other"
                        }
                        // If label already used, append a suffix
                        val finalLabel = if (phoneNumbers.has(label)) {
                            var i = 2
                            while (phoneNumbers.has("${label}_$i")) i++
                            "${label}_$i"
                        } else label
                        phoneNumbers.put(finalLabel, number)
                    }
                }
                if (phoneNumbers.length() > 0) {
                    contactJson.put("phone_numbers", phoneNumbers)
                }

                // Emails
                val emails = JSONObject()
                val emailCursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.TYPE
                    ),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                emailCursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val address = cursor.getString(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                        )
                        val type = cursor.getInt(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE)
                        )
                        val label = when (type) {
                            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "personal"
                            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "work"
                            else -> "other"
                        }
                        val finalLabel = if (emails.has(label)) {
                            var i = 2
                            while (emails.has("${label}_$i")) i++
                            "${label}_$i"
                        } else label
                        emails.put(finalLabel, address)
                    }
                }
                if (emails.length() > 0) {
                    contactJson.put("emails", emails)
                }

                // Addresses
                val addresses = JSONObject()
                val addressCursor: Cursor? = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                        ContactsContract.CommonDataKinds.StructuredPostal.TYPE
                    ),
                    "${ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                addressCursor?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val formattedAddress = cursor.getString(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                        )
                        val type = cursor.getInt(
                            cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.StructuredPostal.TYPE)
                        )
                        val label = when (type) {
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME -> "home"
                            ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "work"
                            else -> "other"
                        }
                        val finalLabel = if (addresses.has(label)) {
                            var i = 2
                            while (addresses.has("${label}_$i")) i++
                            "${label}_$i"
                        } else label
                        addresses.put(finalLabel, formattedAddress)
                    }
                }
                if (addresses.length() > 0) {
                    contactJson.put("addresses", addresses)
                }

                contactsList.put(contactJson)
            }

            JSONObject().apply {
                put("success", true)
                put("contacts", contactsList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup phone contacts", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to lookup contacts: ${e.message}")
            }
        }
    }

    // ========== Keyboard Dismissal ==========

    /**
     * Dismiss the soft keyboard after Google Calendar opens with the title field focused.
     * Clears focus from the title EditText so the keyboard goes away without navigating back.
     */
    private fun dismissCalendarKeyboard() {
        Thread {
            try {
                // Wait for the calendar to load and keyboard to appear
                Thread.sleep(1500)

                val service = WhizAccessibilityService.getInstance() ?: run {
                    Log.w(TAG, "Accessibility service not available to dismiss keyboard")
                    return@Thread
                }

                val rootNode = service.getCurrentRootNode() ?: run {
                    Log.w(TAG, "No root node available to dismiss keyboard")
                    return@Thread
                }

                try {
                    // Find the focused title field and clear its focus
                    val titleNodes = rootNode.findAccessibilityNodeInfosByViewId(
                        "com.google.android.calendar:id/title"
                    )
                    val titleNode = titleNodes?.firstOrNull()
                    if (titleNode != null) {
                        titleNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                        Log.i(TAG, "Cleared focus from calendar title field to dismiss keyboard")
                        titleNodes.forEach { it.recycle() }
                    } else {
                        titleNodes?.forEach { it.recycle() }
                        // Fallback: find any focused node and clear its focus
                        val focused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        if (focused != null) {
                            focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                            Log.i(TAG, "Cleared focus from input-focused node to dismiss keyboard")
                            focused.recycle()
                        } else {
                            Log.d(TAG, "No focused node found after calendar launch")
                        }
                    }
                } finally {
                    rootNode.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing calendar keyboard", e)
            }
        }.start()
    }

    // ========== Resolver Dialog Handling ==========

    /**
     * Dismisses the Android resolver dialog by selecting the specified app
     * and tapping "Just once". Handles both dialog formats:
     * - "Complete action using" (older)
     * - "Open with <app>" / "Use a different app" (newer)
     * Fires on a background thread so it doesn't block the tool result.
     */
    fun dismissResolverDialog(appName: String = "Clock") {
        Thread {
            try {
                // Wait for the resolver dialog to appear
                Thread.sleep(1000)

                val service = WhizAccessibilityService.getInstance() ?: run {
                    Log.w(TAG, "Accessibility service not available to dismiss resolver")
                    return@Thread
                }

                val rootNode = service.getCurrentRootNode() ?: run {
                    Log.d(TAG, "No root node - resolver dialog may not have appeared")
                    return@Thread
                }

                // Check if this is actually the resolver dialog (handles both formats)
                val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("android:id/title")
                val isResolver = titleNodes.any {
                    val text = it.text?.toString() ?: ""
                    text.startsWith("Complete action using") || text.startsWith("Open with")
                }
                if (!isResolver) {
                    Log.d(TAG, "No resolver dialog detected, skipping")
                    return@Thread
                }

                Log.i(TAG, "Resolver dialog detected, selecting $appName app")

                // Check if the desired app is already pre-selected in the title
                // e.g. "Complete action using Clock" or "Open with Calendar"
                val alreadySelected = titleNodes.any {
                    val text = it.text?.toString() ?: ""
                    text.contains(appName, ignoreCase = true)
                }

                if (alreadySelected) {
                    Log.i(TAG, "$appName already pre-selected in resolver title")
                } else {
                    // Find and click the app in the resolver list
                    val appNodes = rootNode.findAccessibilityNodeInfosByText(appName)
                    val appClicked = appNodes.any { node ->
                        // Click the clickable parent (the list item row)
                        var target = node
                        while (target.parent != null && !target.isClickable) {
                            target = target.parent
                        }
                        if (target.isClickable) {
                            service.clickNode(target)
                        } else {
                            false
                        }
                    }

                    if (!appClicked) {
                        Log.w(TAG, "Could not find/click $appName in resolver")
                        screenAgentTools.dumpUIHierarchy(rootNode, "resolver_app_not_found", "Could not find/click $appName in resolver dialog")
                    }
                }

                // Poll for "Just once" button to become enabled
                var clicked = false
                for (attempt in 1..10) {
                    Thread.sleep(100)
                    val updatedRoot = service.getCurrentRootNode() ?: continue
                    val justOnceNodes = updatedRoot.findAccessibilityNodeInfosByViewId("android:id/button_once")
                    val button = justOnceNodes.firstOrNull() ?: continue
                    if (button.isEnabled) {
                        service.clickNode(button)
                        Log.i(TAG, "Dismissed resolver dialog with $appName + Just once (attempt $attempt)")
                        clicked = true
                        break
                    }
                }
                if (!clicked) {
                    Log.w(TAG, "Just once button never became enabled")
                    val currentRoot = service.getCurrentRootNode()
                    if (currentRoot != null) {
                        screenAgentTools.dumpUIHierarchy(currentRoot, "resolver_just_once_failed", "Just once button never became enabled for $appName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing resolver dialog", e)
            }
        }.start()
    }

}
