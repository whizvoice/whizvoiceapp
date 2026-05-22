package com.example.whiz.health

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "HealthConnectManager"

    enum class DataType { CALORIES, WEIGHT }

    sealed class Result {
        object Success : Result()
        data class Unavailable(val reason: String) : Result()
        data class PermissionMissing(val permission: String) : Result()
        data class Failed(val error: String) : Result()
    }

    private fun client(): HealthConnectClient? {
        val status = HealthConnectClient.getSdkStatus(context)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(context)
            HealthConnectClient.SDK_UNAVAILABLE -> {
                Log.w(TAG, "Health Connect SDK unavailable on this device")
                null
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Log.w(TAG, "Health Connect provider update required")
                null
            }
            else -> null
        }
    }

    fun isAvailable(): Boolean = client() != null

    private fun writePermissionFor(type: DataType): String = when (type) {
        DataType.CALORIES -> HealthPermission.getWritePermission(NutritionRecord::class)
        DataType.WEIGHT -> HealthPermission.getWritePermission(WeightRecord::class)
    }

    private fun readPermissionFor(type: DataType): String = when (type) {
        DataType.CALORIES -> HealthPermission.getReadPermission(NutritionRecord::class)
        DataType.WEIGHT -> HealthPermission.getReadPermission(WeightRecord::class)
    }

    /**
     * All Health Connect permissions Whiz uses. Includes read permissions in addition
     * to write so we can check whether other health apps (Google Health, Samsung Health,
     * etc.) have written data into HC — which is our proxy for whether they're
     * "connected" to HC as a data source. Requested as a single bundle so the user
     * grants once and covers calories + weight + connection-detection together.
     */
    fun allHealthConnectPermissions(): Set<String> = buildSet {
        DataType.entries.forEach {
            add(writePermissionFor(it))
            add(readPermissionFor(it))
        }
    }

    suspend fun hasWritePermission(type: DataType): Boolean {
        val c = client() ?: return false
        val granted = c.permissionController.getGrantedPermissions()
        return writePermissionFor(type) in granted
    }

    /** Currently-granted HC permissions, or empty set if HC unavailable. */
    suspend fun grantedPermissions(): Set<String> {
        val c = client() ?: return emptySet()
        return c.permissionController.getGrantedPermissions()
    }

    /**
     * Known health apps we recognize for the "are they connected to HC?" check. Maps
     * Android package name → user-facing name. Adding to this list extends detection.
     */
    val knownHealthApps: Map<String, String> = mapOf(
        "com.fitbit.FitbitMobile" to "Google Health",
        "com.google.android.apps.fitness" to "Google Fit",
        "com.sec.android.app.shealth" to "Samsung Health",
        "com.myfitnesspal.android" to "MyFitnessPal",
        "com.cron.cronometer" to "Cronometer",
    )

    data class UnconnectedApp(val packageName: String, val name: String)

    /**
     * Heuristic check for which installed health apps are NOT connected to Health Connect,
     * gated on Whiz being the only writer to HC.
     *
     * Detection: an app is "connected" if it has written any nutrition/weight record into HC
     * recently (proxy for an active HC integration — we can't query other apps' permissions
     * directly).
     *
     * Gate: we only surface the prompt list when Whiz is effectively the *sole* source of
     * HC data — i.e. no non-Whiz origin appears in recent records. If any other app is
     * already writing to HC, the user has a working setup and we stay silent. (Per UX
     * direction: don't pester users whose HC is already wired up to something.)
     */
    suspend fun unconnectedHealthApps(): List<UnconnectedApp> {
        val c = client() ?: return emptyList()
        val installed = knownHealthApps.filter { (pkg, _) -> isPackageInstalled(pkg) }
        if (installed.isEmpty()) return emptyList()
        val origins = originsWithRecentData(c)
        if (origins == null) {
            Log.w(TAG, "Skipping unconnected-apps check — couldn't read HC (likely missing read permission)")
            return emptyList()
        }
        val nonWhizOrigins = origins - whizPackageNames()
        if (nonWhizOrigins.isNotEmpty()) {
            Log.i(TAG, "Skipping unconnected-apps prompt — non-Whiz HC writers present: $nonWhizOrigins")
            return emptyList()
        }
        return installed
            .filter { (pkg, _) -> pkg !in origins }
            .map { (pkg, name) -> UnconnectedApp(pkg, name) }
    }

    /**
     * Whiz's own package names across build flavors. context.packageName resolves to
     * the running flavor (e.g. com.example.whiz vs com.example.whiz.debug); we also
     * include the other flavor so a debug build doesn't see a prod build's writes as
     * "another app".
     */
    private fun whizPackageNames(): Set<String> {
        val current = context.packageName
        return setOf(current, "com.example.whiz", "com.example.whiz.debug")
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Collect distinct dataOrigin packages from recent HC records. Returns null if BOTH
     * reads failed (e.g. caller doesn't hold READ permissions) — null means "we don't
     * know what's in HC" so callers should not draw conclusions from an empty set.
     */
    private suspend fun originsWithRecentData(c: HealthConnectClient): Set<String>? {
        val window = TimeRangeFilter.between(
            Instant.now().minus(180, ChronoUnit.DAYS),
            Instant.now(),
        )
        val origins = mutableSetOf<String>()
        var anyReadSucceeded = false
        try {
            val nutrition = c.readRecords(
                ReadRecordsRequest(NutritionRecord::class, window, pageSize = 100)
            )
            nutrition.records.forEach { origins += it.metadata.dataOrigin.packageName }
            anyReadSucceeded = true
        } catch (e: Exception) {
            Log.w(TAG, "NutritionRecord read failed during origin scan: ${e.message}")
        }
        try {
            val weight = c.readRecords(
                ReadRecordsRequest(WeightRecord::class, window, pageSize = 100)
            )
            weight.records.forEach { origins += it.metadata.dataOrigin.packageName }
            anyReadSucceeded = true
        } catch (e: Exception) {
            Log.w(TAG, "WeightRecord read failed during origin scan: ${e.message}")
        }
        return if (anyReadSucceeded) origins else null
    }

    suspend fun logCalories(kcal: Int): Result {
        val c = client() ?: return Result.Unavailable("Health Connect not installed or unavailable")
        if (!hasWritePermission(DataType.CALORIES)) {
            return Result.PermissionMissing(writePermissionFor(DataType.CALORIES))
        }
        val end = Instant.now()
        val start = end.minusSeconds(1)
        val offset = ZoneId.systemDefault().rules.getOffset(end)
        // MEAL_TYPE_UNKNOWN maps to Google Health's "uncategorized" bucket, which is
        // the closest equivalent to Fitbit's legacy "Anytime" meal slot. The user can
        // re-categorize from inside Google Health if they want.
        val record = NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = offset,
            metadata = Metadata.manualEntry(),
            energy = Energy.kilocalories(kcal.toDouble()),
            mealType = MealType.MEAL_TYPE_UNKNOWN,
            name = "Quick Calories",
        )
        return try {
            c.insertRecords(listOf(record))
            Log.i(TAG, "Wrote $kcal kcal to Health Connect")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert NutritionRecord", e)
            Result.Failed(e.message ?: "unknown error")
        }
    }

    suspend fun logWeight(kg: Double): Result {
        val c = client() ?: return Result.Unavailable("Health Connect not installed or unavailable")
        if (!hasWritePermission(DataType.WEIGHT)) {
            return Result.PermissionMissing(writePermissionFor(DataType.WEIGHT))
        }
        val now = Instant.now()
        val offset = ZoneId.systemDefault().rules.getOffset(now)
        val record = WeightRecord(
            time = now,
            zoneOffset = offset,
            metadata = Metadata.manualEntry(),
            weight = Mass.kilograms(kg),
        )
        return try {
            c.insertRecords(listOf(record))
            Log.i(TAG, "Wrote $kg kg to Health Connect")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert WeightRecord", e)
            Result.Failed(e.message ?: "unknown error")
        }
    }
}
