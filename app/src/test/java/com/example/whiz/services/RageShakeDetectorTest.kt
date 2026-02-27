package com.example.whiz.services

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import android.content.Context
import android.hardware.SensorManager

class RageShakeDetectorTest {

    private lateinit var detector: RageShakeDetector
    private var currentTime = 0L

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        val sensorManager = mock(SensorManager::class.java)
        org.mockito.Mockito.`when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)

        detector = RageShakeDetector(context)
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
}
