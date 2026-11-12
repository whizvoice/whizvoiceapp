package com.example.whiz.services

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager
import com.example.whiz.data.preferences.RageShakePreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

class RageShakeDetectorTest {

    private lateinit var detector: RageShakeDetector
    private lateinit var prefs: RageShakePreferences
    private var currentTime = 0L

    /** In-memory stand-in for SharedPreferences so persistence can be checked across instances. */
    private val store = mutableMapOf<String, Boolean>()

    private fun fakeSharedPrefs(): SharedPreferences {
        val sp = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        whenever(sp.getBoolean(any(), any())).doAnswer { inv ->
            store[inv.getArgument(0)] ?: inv.getArgument(1)
        }
        whenever(sp.edit()).thenReturn(editor)
        whenever(editor.putBoolean(any(), any())).doAnswer { inv ->
            store[inv.getArgument(0)] = inv.getArgument(1)
            editor
        }
        return sp
    }

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val sensorManager = mock(SensorManager::class.java)
        org.mockito.Mockito.`when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)

        prefs = RageShakePreferences(fakeSharedPrefs())
        detector = RageShakeDetector(context, prefs)
        currentTime = 1000L
        detector.clock = { currentTime }
    }

    /** Helper: fires 4 strong shakes 100ms apart */
    private fun fireShakeSequence() {
        repeat(4) {
            detector.processAcceleration(20f, 20f, 20f)
            currentTime += 100
        }
    }

    @Test
    fun `shake below threshold is ignored`() {
        // Gravity-only values (~9.8 m/s^2 total) should not trigger
        repeat(10) {
            detector.processAcceleration(0f, 9.8f, 0f)
            currentTime += 50
        }
        assertEquals("Gentle movement should not trigger shake detection", 0, detector.triggerCount)
    }

    @Test
    fun `single strong shake is not enough`() {
        detector.processAcceleration(20f, 20f, 20f)
        assertEquals("A single strong shake event should not trigger detection", 0, detector.triggerCount)
    }

    @Test
    fun `4+ strong shakes within 1 second triggers detection`() {
        fireShakeSequence()
        assertEquals("4 strong shakes within 1 second should trigger detection", 1, detector.triggerCount)
    }

    @Test
    fun `shakes spread over more than 1 second dont trigger`() {
        // 4 shakes but spread over >1 second
        repeat(4) {
            detector.processAcceleration(20f, 20f, 20f)
            currentTime += 400 // 400ms apart = 1.6 seconds total
        }
        assertEquals("Shakes spread over >1 second should not trigger", 0, detector.triggerCount)
    }

    @Test
    fun `multiple shake sequences each trigger detection`() {
        fireShakeSequence()
        assertEquals("First shake should trigger", 1, detector.triggerCount)

        // Second shake sequence shortly after
        currentTime += 2000
        fireShakeSequence()
        assertEquals("Second shake should also trigger", 2, detector.triggerCount)
    }

    @Test
    fun `shakes during snooze period dont trigger`() {
        detector.snooze()
        fireShakeSequence()
        assertEquals("Shakes during snooze should not trigger", 0, detector.triggerCount)
    }

    @Test
    fun `shakes after snooze expires do trigger`() {
        detector.snooze()
        // Advance past the 10-minute snooze
        currentTime += 10 * 60 * 1000L + 1
        fireShakeSequence()
        assertEquals("Shakes after snooze expires should trigger", 1, detector.triggerCount)
    }

    @Test
    fun `shakes while permanently disabled dont trigger`() {
        detector.setEnabled(false)
        currentTime += 60 * 60 * 1000L // well past any snooze window
        fireShakeSequence()
        assertEquals("Shakes while disabled should not trigger", 0, detector.triggerCount)
    }

    @Test
    fun `re-enabling after permanent disable triggers again`() {
        detector.setEnabled(false)
        detector.setEnabled(true)
        fireShakeSequence()
        assertEquals("Shakes after re-enable should trigger", 1, detector.triggerCount)
    }

    @Test
    fun `enabling clears an active snooze`() {
        detector.snooze()
        detector.setEnabled(true)
        fireShakeSequence()
        assertEquals("Enabling should cancel the snooze", 1, detector.triggerCount)
    }

    @Test
    fun `permanent disable survives a new detector instance`() {
        detector.setEnabled(false)

        val context = mock(Context::class.java)
        org.mockito.Mockito.`when`(context.getSystemService(Context.SENSOR_SERVICE))
            .thenReturn(mock(SensorManager::class.java))
        val fresh = RageShakeDetector(context, RageShakePreferences(fakeSharedPrefs()))
        fresh.clock = { currentTime }

        assertFalse("Disabled flag should persist", fresh.isEnabled.value)
        repeat(4) { fresh.processAcceleration(20f, 20f, 20f); currentTime += 100 }
        assertEquals("Fresh instance should honor persisted disable", 0, fresh.triggerCount)
    }
}
