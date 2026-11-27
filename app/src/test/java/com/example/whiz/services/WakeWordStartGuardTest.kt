package com.example.whiz.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordStartGuardTest {

    private var enabled = true
    private var running = false
    private var micPermission = true
    private var startError: Throwable? = null
    private var startRequests = 0
    private val reported = mutableListOf<WakeWordStartFailure>()

    private fun guard() = WakeWordStartGuard(
        isEnabled = { enabled },
        isRunning = { running },
        hasMicPermission = { micPermission },
        requestStart = {
            startRequests++
            startError?.let { throw it }
        },
        report = { reported += it },
    )

    @Test
    fun `requests a start when wake word is enabled and the service is not running`() {
        val outcome = guard().ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        assertEquals(WakeWordStartOutcome.REQUESTED, outcome)
        assertEquals(1, startRequests)
    }

    @Test
    fun `does not start when wake word is disabled`() {
        enabled = false

        val outcome = guard().ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        assertEquals(WakeWordStartOutcome.NOT_ENABLED, outcome)
        assertEquals(0, startRequests)
    }

    @Test
    fun `does not start a second service when one is already running`() {
        running = true

        val outcome = guard().ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        assertEquals(WakeWordStartOutcome.ALREADY_RUNNING, outcome)
        assertEquals(0, startRequests)
    }

    @Test
    fun `does not start without the microphone permission`() {
        micPermission = false

        val outcome = guard().ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        assertEquals(WakeWordStartOutcome.NO_MIC_PERMISSION, outcome)
        assertEquals(0, startRequests)
    }

    @Test
    fun `a refused background start is not surfaced because the foreground retry can still fix it`() {
        startError = IllegalStateException("startForegroundService not allowed")
        val guard = guard()

        val outcome = guard.ensureRunning(WakeWordStartTrigger.PROCESS_START)

        assertEquals(WakeWordStartOutcome.REFUSED, outcome)
        assertNull(guard.failure.value)
        assertEquals(emptyList<WakeWordStartFailure>(), reported)
    }

    @Test
    fun `a refused start with the app on screen is surfaced and reported`() {
        startError = IllegalStateException("startForegroundService not allowed")
        val guard = guard()

        val outcome = guard.ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        assertEquals(WakeWordStartOutcome.REFUSED, outcome)
        val expected = WakeWordStartFailure(
            WakeWordStartTrigger.APP_FOREGROUND,
            "IllegalStateException: startForegroundService not allowed",
        )
        assertEquals(expected, guard.failure.value)
        assertEquals(listOf(expected), reported)
    }

    @Test
    fun `repeated failures are reported only once per process`() {
        startError = IllegalStateException("nope")
        val guard = guard()

        guard.ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)
        guard.ensureRunning(WakeWordStartTrigger.USER_RETRY)

        assertEquals(1, reported.size)
        assertEquals(WakeWordStartTrigger.USER_RETRY, guard.failure.value?.trigger)
    }

    @Test
    fun `a failure inside the service is attributed to the trigger that requested the start`() {
        val guard = guard()
        guard.ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        guard.onStartForegroundFailed(SecurityException("mic not allowed"))

        val expected = WakeWordStartFailure(
            WakeWordStartTrigger.APP_FOREGROUND,
            "SecurityException: mic not allowed",
        )
        assertEquals(expected, guard.failure.value)
        assertEquals(listOf(expected), reported)
    }

    @Test
    fun `a failure inside the service after a background request is not surfaced`() {
        val guard = guard()
        guard.ensureRunning(WakeWordStartTrigger.ACCESSIBILITY_CONNECTED)

        guard.onStartForegroundFailed(SecurityException("mic not allowed"))

        assertNull(guard.failure.value)
        assertEquals(emptyList<WakeWordStartFailure>(), reported)
    }

    @Test
    fun `a successful start clears an earlier failure`() {
        startError = IllegalStateException("nope")
        val guard = guard()
        guard.ensureRunning(WakeWordStartTrigger.APP_FOREGROUND)

        guard.onStarted()

        assertNull(guard.failure.value)
    }
}
