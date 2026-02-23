package com.example.whiz.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WakeWordPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wake_word_settings", Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val isEnabled: StateFlow<Boolean> = _isEnabled

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabled.value = enabled
    }

    fun isEnabledOnce(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    // --- Wake word detection metrics (Welford's online algorithm) ---

    private fun metricsKey(phrase: String, field: String): String =
        "metrics_${phrase}_$field"

    /**
     * Record a wake word detection event, updating running aggregates.
     * @param phrase normalized key: "hey_whiz" or "ok_whiz"
     * @param confidence the Vosk confidence score
     * @param accepted whether it met the threshold
     */
    fun recordDetection(phrase: String, confidence: Double, accepted: Boolean) {
        val editor = prefs.edit()

        // Update count
        val countKey = metricsKey(phrase, "count")
        val oldCount = prefs.getLong(countKey, 0)
        val newCount = oldCount + 1
        editor.putLong(countKey, newCount)

        // Update accepted count
        if (accepted) {
            val acceptedKey = metricsKey(phrase, "accepted_count")
            editor.putLong(acceptedKey, prefs.getLong(acceptedKey, 0) + 1)
        }

        // Welford's online algorithm for mean and variance
        val meanKey = metricsKey(phrase, "mean")
        val m2Key = metricsKey(phrase, "m2")
        val oldMean = Double.fromBits(prefs.getLong(meanKey, 0.0.toBits()))
        val oldM2 = Double.fromBits(prefs.getLong(m2Key, 0.0.toBits()))

        val delta = confidence - oldMean
        val newMean = oldMean + delta / newCount
        val delta2 = confidence - newMean
        val newM2 = oldM2 + delta * delta2

        editor.putLong(meanKey, newMean.toBits())
        editor.putLong(m2Key, newM2.toBits())

        // Last confidence and timestamp
        val now = System.currentTimeMillis()
        editor.putLong(metricsKey(phrase, "last_confidence"), confidence.toBits())
        editor.putLong(metricsKey(phrase, "last_timestamp"), now)

        // Rolling window of last 10 detections
        val recentKey = metricsKey(phrase, "recent")
        val recentArray = try {
            JSONArray(prefs.getString(recentKey, "[]"))
        } catch (e: Exception) { JSONArray() }
        val entry = JSONObject().apply {
            put("confidence", confidence)
            put("accepted", accepted)
            put("timestamp", now)
        }
        recentArray.put(entry)
        while (recentArray.length() > 10) {
            recentArray.remove(0)
        }
        editor.putString(recentKey, recentArray.toString())

        editor.apply()
        writeStatsFile()
    }

    /**
     * Write current stats for all phrases to a file in external storage,
     * readable via: adb pull /sdcard/Android/data/<package>/files/wake_word_stats.txt
     */
    private fun writeStatsFile() {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, "wake_word_stats.txt")
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val sb = StringBuilder()
            sb.appendLine("Wake Word Detection Stats (updated ${df.format(Date())})")
            sb.appendLine("=".repeat(50))
            for (phrase in listOf("hey_whiz", "ok_whiz")) {
                val s = getStats(phrase)
                if (s.count == 0L) continue
                sb.appendLine()
                sb.appendLine("[$phrase]")
                sb.appendLine("  count:    ${s.count}")
                sb.appendLine("  accepted: ${s.acceptedCount}")
                sb.appendLine("  mean:     ${"%.2f".format(s.mean)}")
                sb.appendLine("  stdDev:   ${"%.2f".format(s.stdDev)}")
                sb.appendLine("  last:     ${"%.2f".format(s.lastConfidence)} at ${df.format(Date(s.lastTimestamp))}")
                val recent = getRecentDetections(phrase)
                if (recent.isNotEmpty()) {
                    sb.appendLine("  recent (last ${recent.size}):")
                    for (r in recent) {
                        val status = if (r.accepted) "ACCEPTED" else "REJECTED"
                        sb.appendLine("    ${"%.2f".format(r.confidence)} $status at ${df.format(Date(r.timestamp))}")
                    }
                }
            }
            file.writeText(sb.toString())
        } catch (e: Exception) {
            Log.w("WakeWordPreferences", "Failed to write stats file", e)
        }
    }

    data class WakeWordStats(
        val count: Long,
        val acceptedCount: Long,
        val mean: Double,
        val stdDev: Double,
        val lastConfidence: Double,
        val lastTimestamp: Long
    )

    fun getStats(phrase: String): WakeWordStats {
        val count = prefs.getLong(metricsKey(phrase, "count"), 0)
        val acceptedCount = prefs.getLong(metricsKey(phrase, "accepted_count"), 0)
        val mean = Double.fromBits(prefs.getLong(metricsKey(phrase, "mean"), 0.0.toBits()))
        val m2 = Double.fromBits(prefs.getLong(metricsKey(phrase, "m2"), 0.0.toBits()))
        val lastConfidence = Double.fromBits(prefs.getLong(metricsKey(phrase, "last_confidence"), 0.0.toBits()))
        val lastTimestamp = prefs.getLong(metricsKey(phrase, "last_timestamp"), 0)

        val stdDev = if (count >= 2) Math.sqrt(m2 / (count - 1)) else 0.0

        return WakeWordStats(count, acceptedCount, mean, stdDev, lastConfidence, lastTimestamp)
    }

    data class RecentDetection(
        val confidence: Double,
        val accepted: Boolean,
        val timestamp: Long
    )

    fun getRecentDetections(phrase: String): List<RecentDetection> {
        val recentKey = metricsKey(phrase, "recent")
        val recentArray = try {
            JSONArray(prefs.getString(recentKey, "[]"))
        } catch (e: Exception) { return emptyList() }
        return (0 until recentArray.length()).map { i ->
            val obj = recentArray.getJSONObject(i)
            RecentDetection(
                confidence = obj.getDouble("confidence"),
                accepted = obj.getBoolean("accepted"),
                timestamp = obj.getLong("timestamp")
            )
        }
    }

    fun clearMetrics() {
        val editor = prefs.edit()
        for (phrase in listOf("hey_whiz", "ok_whiz")) {
            for (field in listOf("count", "accepted_count", "mean", "m2", "last_confidence", "last_timestamp", "recent")) {
                editor.remove(metricsKey(phrase, field))
            }
        }
        editor.apply()
    }

    companion object {
        private const val KEY_ENABLED = "wake_word_enabled"
    }
}
