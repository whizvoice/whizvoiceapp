package com.example.whiz.tools

import android.Manifest
import android.app.AlarmManager
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
    @ApplicationContext private val context: Context
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

    // ========== Resolver Dialog Handling ==========

    /**
     * Dismisses the Android "Complete action using" resolver dialog by selecting
     * the Clock app and tapping "Just once". This fires on a background thread
     * so it doesn't block the tool result.
     */
    private fun dismissResolverDialog() {
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

                // Check if this is actually the resolver dialog
                val titleNodes = rootNode.findAccessibilityNodeInfosByViewId("android:id/title")
                val isResolver = titleNodes.any { it.text?.toString()?.startsWith("Complete action using") == true }
                if (!isResolver) {
                    Log.d(TAG, "No resolver dialog detected, skipping")
                    return@Thread
                }

                Log.i(TAG, "Resolver dialog detected, selecting Clock app")

                // Find and click "Clock" in the resolver list
                val clockNodes = rootNode.findAccessibilityNodeInfosByText("Clock")
                val clockClicked = clockNodes.any { node ->
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

                if (!clockClicked) {
                    Log.w(TAG, "Could not find/click Clock in resolver, checking if already pre-selected")
                    // Format B: title is "Complete action using Clock" — Clock is already pre-selected,
                    // so we can skip clicking it and go straight to "Just once"
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
                        Log.i(TAG, "Dismissed resolver dialog with Clock + Just once (attempt $attempt)")
                        clicked = true
                        break
                    }
                }
                if (!clicked) {
                    Log.w(TAG, "Just once button never became enabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing resolver dialog", e)
            }
        }.start()
    }
}
