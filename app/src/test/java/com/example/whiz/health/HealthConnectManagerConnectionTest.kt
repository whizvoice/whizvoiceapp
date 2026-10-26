package com.example.whiz.health

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.example.whiz.health.HealthConnectManager.Companion.readAccess
import com.example.whiz.health.HealthConnectManager.Companion.selectUnconnectedApps
import com.example.whiz.health.HealthConnectManager.DataType
import com.example.whiz.health.HealthConnectManager.ReadAccess
import com.example.whiz.health.HealthConnectManager.UnconnectedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

/**
 * Unit tests for the "is a health app connected to Health Connect?" check (bug #1588:
 * weight log returned `requires_connection` for Google Health even though it was
 * connected — the old check only counted apps that had *written* records).
 */
class HealthConnectManagerConnectionTest {

    private val GOOGLE_HEALTH = "com.fitbit.FitbitMobile"
    private val GOOGLE_FIT = "com.google.android.apps.fitness"
    private val READ_WEIGHT = "android.permission.health.READ_WEIGHT"
    private val READ_NUTRITION = "android.permission.health.READ_NUTRITION"

    private val installed = mapOf(GOOGLE_HEALTH to "Google Health", GOOGLE_FIT to "Google Fit")

    private fun packageManagerWith(pkg: String, permissions: Array<String>?, flags: IntArray?): PackageManager {
        val info = PackageInfo().apply {
            requestedPermissions = permissions
            requestedPermissionsFlags = flags
        }
        return mock { on { getPackageInfo(eq(pkg), any<Int>()) } doReturn info }
    }

    // ---- bug #1588: a granted read permission means connected, no writes needed ----

    @Test
    fun `app holding the read permission is reported as granted`() {
        val pm = packageManagerWith(
            GOOGLE_HEALTH,
            arrayOf(READ_NUTRITION, READ_WEIGHT),
            intArrayOf(0, PackageInfo.REQUESTED_PERMISSION_GRANTED),
        )
        assertEquals(ReadAccess.GRANTED, readAccess(pm, GOOGLE_HEALTH, READ_WEIGHT))
    }

    @Test
    fun `app that requests the read permission without a grant is reported as denied`() {
        val pm = packageManagerWith(
            GOOGLE_HEALTH,
            arrayOf(READ_NUTRITION, READ_WEIGHT),
            intArrayOf(0, PackageInfo.REQUESTED_PERMISSION_GRANTED),
        )
        assertEquals(ReadAccess.DENIED, readAccess(pm, GOOGLE_HEALTH, READ_NUTRITION))
    }

    @Test
    fun `app that never requests the read permission is reported as not requested`() {
        val pm = packageManagerWith(GOOGLE_FIT, arrayOf("android.permission.INTERNET"), intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED))
        assertEquals(ReadAccess.NOT_REQUESTED, readAccess(pm, GOOGLE_FIT, READ_WEIGHT))
    }

    @Test
    fun `app with no requested permissions is reported as not requested`() {
        val pm = packageManagerWith(GOOGLE_FIT, null, null)
        assertEquals(ReadAccess.NOT_REQUESTED, readAccess(pm, GOOGLE_FIT, READ_WEIGHT))
    }

    @Test
    fun `app that is not installed has no read access`() {
        val pm: PackageManager = mock {
            on { getPackageInfo(any<String>(), any<Int>()) } doThrow PackageManager.NameNotFoundException()
        }
        assertNull(readAccess(pm, GOOGLE_HEALTH, READ_WEIGHT))
    }

    // ---- tool data_type string -> DataType ----

    @Test
    fun `tool data_type strings map to their DataType`() {
        assertEquals(DataType.CALORIES, DataType.fromToolName("calories"))
        assertEquals(DataType.WEIGHT, DataType.fromToolName("weight"))
    }

    @Test
    fun `unknown or missing data_type maps to null`() {
        assertNull(DataType.fromToolName("steps"))
        assertNull(DataType.fromToolName(null))
    }

    // ---- which apps to name in the requires_connection prompt ----

    @Test
    fun `connected app is not flagged`() {
        val access = mapOf(GOOGLE_HEALTH to ReadAccess.GRANTED)
        assertEquals(emptyList<UnconnectedApp>(), selectUnconnectedApps(installed, access))
    }

    @Test
    fun `app without a grant is flagged`() {
        val access = mapOf(GOOGLE_HEALTH to ReadAccess.DENIED)
        assertEquals(
            listOf(UnconnectedApp(GOOGLE_HEALTH, "Google Health")),
            selectUnconnectedApps(installed, access),
        )
    }

    @Test
    fun `stays silent when any app can already read the data`() {
        // Per UX direction: don't pester users whose Health Connect is already wired up.
        val access = mapOf(GOOGLE_HEALTH to ReadAccess.GRANTED, GOOGLE_FIT to ReadAccess.DENIED)
        assertEquals(emptyList<UnconnectedApp>(), selectUnconnectedApps(installed, access))
    }

    @Test
    fun `app that cannot read this data type is never flagged`() {
        // Connecting it would not make the data visible, so there is nothing to offer.
        val access = mapOf(GOOGLE_HEALTH to ReadAccess.DENIED, GOOGLE_FIT to ReadAccess.NOT_REQUESTED)
        assertEquals(
            listOf(UnconnectedApp(GOOGLE_HEALTH, "Google Health")),
            selectUnconnectedApps(installed, access),
        )
    }
}
