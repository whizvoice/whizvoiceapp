package com.example.whiz.health

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "HealthConnectManager"

    enum class DataType(val toolName: String) {
        CALORIES("calories"), WEIGHT("weight");

        companion object {
            /** The type for an `agent_log_health_data` `data_type` string, or null if unknown. */
            fun fromToolName(name: String?): DataType? = entries.firstOrNull { it.toolName == name }
        }
    }

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
     * to write so that, on Android 13 and below, we can check whether other health apps
     * (Google Health, Samsung Health, etc.) have written data into HC — our proxy there
     * for whether they're "connected" (see [legacyUnconnectedHealthApps]). Requested as a
     * single bundle so the user grants once and covers calories + weight +
     * connection-detection together.
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

    /** Whether another app can read a given data type out of Health Connect. */
    enum class ReadAccess {
        /** Holds the read permission — data Whiz writes can show up in this app. */
        GRANTED,
        /** Declares the read permission but the user hasn't granted it (or it was revoked). */
        DENIED,
        /** Doesn't declare the read permission, so connecting it can't surface this data type. */
        NOT_REQUESTED,
    }

    /**
     * Which installed health apps can't see [type] data that Whiz just wrote to Health
     * Connect, so the assistant can offer to connect them.
     *
     * Detection (Android 14+): Health Connect permissions are ordinary runtime permissions
     * there, so we ask PackageManager whether each known app holds the *read* permission
     * for [type]. This is the real grant state — it doesn't depend on the app ever having
     * written anything, and a grant wiped by an app update shows up as denied right away.
     *
     * Gate: we only surface the prompt list when no known app can read the data. If any
     * app already can, the user has a working setup and we stay silent. (Per UX direction:
     * don't pester users whose HC is already wired up to something.)
     */
    suspend fun unconnectedHealthApps(type: DataType): List<UnconnectedApp> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return legacyUnconnectedHealthApps()
        }
        if (!isAvailable()) return emptyList()
        val permission = readPermissionFor(type)
        val access = knownHealthApps.keys
            .mapNotNull { pkg -> readAccess(context.packageManager, pkg, permission)?.let { pkg to it } }
            .toMap()
        Log.i(TAG, "Health app read access for $type: $access")
        return selectUnconnectedApps(knownHealthApps, access)
    }

    /**
     * Android 13 and below: Health Connect is a standalone app that keeps grants in its own
     * store, invisible to PackageManager, so fall back to a heuristic — an app is
     * "connected" if it has written any nutrition/weight record into HC recently. Only
     * surfaces the prompt list when Whiz is the sole writer.
     */
    private suspend fun legacyUnconnectedHealthApps(): List<UnconnectedApp> {
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
     * Reads newest-first: only one page is scanned, and the oldest page can be all Whiz
     * writes even when another app has written since.
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
                ReadRecordsRequest(NutritionRecord::class, window, ascendingOrder = false, pageSize = 100)
            )
            nutrition.records.forEach { origins += it.metadata.dataOrigin.packageName }
            anyReadSucceeded = true
        } catch (e: Exception) {
            Log.w(TAG, "NutritionRecord read failed during origin scan: ${e.message}")
        }
        try {
            val weight = c.readRecords(
                ReadRecordsRequest(WeightRecord::class, window, ascendingOrder = false, pageSize = 100)
            )
            weight.records.forEach { origins += it.metadata.dataOrigin.packageName }
            anyReadSucceeded = true
        } catch (e: Exception) {
            Log.w(TAG, "WeightRecord read failed during origin scan: ${e.message}")
        }
        return if (anyReadSucceeded) origins else null
    }

    /**
     * The instant to stamp a record with. `null`/today → [Instant.now] (unchanged behavior).
     * A past date → noon local time on that day. Noon avoids the midnight edge where a
     * timezone offset could roll the record into an adjacent calendar day. A future date is
     * clamped to now defensively (the tool schema already tells Claude not to send one).
     */
    private fun recordInstant(date: LocalDate?): Instant {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return if (date == null || !date.isBefore(today)) Instant.now()
               else date.atTime(12, 0).atZone(zone).toInstant()
    }

    suspend fun logCalories(kcal: Int, date: LocalDate? = null): Result {
        val c = client() ?: return Result.Unavailable("Health Connect not installed or unavailable")
        if (!hasWritePermission(DataType.CALORIES)) {
            return Result.PermissionMissing(writePermissionFor(DataType.CALORIES))
        }
        val end = recordInstant(date)
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
            Log.i(TAG, "Wrote $kcal kcal to Health Connect (${date ?: "today"})")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert NutritionRecord", e)
            Result.Failed(e.message ?: "unknown error")
        }
    }

    suspend fun logWeight(kg: Double, date: LocalDate? = null): Result {
        val c = client() ?: return Result.Unavailable("Health Connect not installed or unavailable")
        if (!hasWritePermission(DataType.WEIGHT)) {
            return Result.PermissionMissing(writePermissionFor(DataType.WEIGHT))
        }
        val time = recordInstant(date)
        val offset = ZoneId.systemDefault().rules.getOffset(time)
        val record = WeightRecord(
            time = time,
            zoneOffset = offset,
            metadata = Metadata.manualEntry(),
            weight = Mass.kilograms(kg),
        )
        return try {
            c.insertRecords(listOf(record))
            Log.i(TAG, "Wrote $kg kg to Health Connect (${date ?: "today"})")
            Result.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert WeightRecord", e)
            Result.Failed(e.message ?: "unknown error")
        }
    }

    companion object {
        /**
         * [packageName]'s grant state for a Health Connect read [permission], or null if
         * the package isn't installed.
         */
        internal fun readAccess(pm: PackageManager, packageName: String, permission: String): ReadAccess? {
            val info = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                }
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
            val index = info.requestedPermissions?.indexOf(permission) ?: -1
            if (index < 0) return ReadAccess.NOT_REQUESTED
            val flags = info.requestedPermissionsFlags?.getOrNull(index) ?: 0
            return if (flags and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) ReadAccess.GRANTED
                   else ReadAccess.DENIED
        }

        /**
         * Apps to name in the "requires_connection" prompt: the ones that could read the
         * data but haven't been granted access — and none at all if some app already can.
         */
        internal fun selectUnconnectedApps(
            appNames: Map<String, String>,
            access: Map<String, ReadAccess>,
        ): List<UnconnectedApp> {
            if (access.values.any { it == ReadAccess.GRANTED }) return emptyList()
            return appNames
                .filter { (pkg, _) -> access[pkg] == ReadAccess.DENIED }
                .map { (pkg, name) -> UnconnectedApp(pkg, name) }
        }
    }
}
