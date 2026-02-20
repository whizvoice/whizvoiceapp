package com.example.whiz.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
            context.startActivity(intent)
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
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label != null) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
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
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
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

    fun addCalendarEvent(params: JSONObject): JSONObject {
        val title = params.getString("title")
        val beginTimeStr = params.getString("begin_time")
        val endTimeStr = if (params.has("end_time")) params.getString("end_time") else null
        val description = if (params.has("description")) params.getString("description") else null
        val location = if (params.has("location")) params.getString("location") else null
        val allDay = if (params.has("all_day")) params.getBoolean("all_day") else false

        Log.i(TAG, "Adding calendar event: '$title' at $beginTimeStr")

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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)

            JSONObject().apply {
                put("success", true)
                put("message", "Calendar app opened with event '$title' pre-filled. User needs to tap Save to confirm.")
                put("title", title)
                put("begin_time", beginTimeStr)
                endTimeStr?.let { put("end_time", it) }
                description?.let { put("description", it) }
                location?.let { put("location", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add calendar event", e)
            JSONObject().apply {
                put("success", false)
                put("error", "Failed to add calendar event: ${e.message}")
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

            context.startActivity(intent)

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
}
