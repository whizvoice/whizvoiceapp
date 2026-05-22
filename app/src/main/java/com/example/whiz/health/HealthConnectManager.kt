package com.example.whiz.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
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

    suspend fun hasWritePermission(type: DataType): Boolean {
        val c = client() ?: return false
        val granted = c.permissionController.getGrantedPermissions()
        return writePermissionFor(type) in granted
    }

    suspend fun logCalories(kcal: Int): Result {
        val c = client() ?: return Result.Unavailable("Health Connect not installed or unavailable")
        if (!hasWritePermission(DataType.CALORIES)) {
            return Result.PermissionMissing(writePermissionFor(DataType.CALORIES))
        }
        val now = Instant.now()
        val offset = ZoneId.systemDefault().rules.getOffset(now)
        val record = NutritionRecord(
            startTime = now,
            startZoneOffset = offset,
            endTime = now,
            endZoneOffset = offset,
            metadata = Metadata.manualEntry(),
            energy = Energy.kilocalories(kcal.toDouble()),
            mealType = MealType.MEAL_TYPE_UNKNOWN,
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
