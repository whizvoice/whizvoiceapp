package com.example.whiz.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Why a wake-word service start was attempted. Android only lets a microphone foreground
 * service start while the app is visible, so a start asked for with nothing on screen can be
 * refused; [appOnScreen] marks the triggers where a refusal is unexpected and worth surfacing.
 */
enum class WakeWordStartTrigger(val appOnScreen: Boolean) {
    /** Application.onCreate. After a kill the process usually comes back in the background. */
    PROCESS_START(appOnScreen = false),
    /** The system (re)bound the accessibility service. */
    ACCESSIBILITY_CONNECTED(appOnScreen = false),
    /** An activity became visible. */
    APP_FOREGROUND(appOnScreen = true),
    /** The user tapped the "wake word isn't running" banner. */
    USER_RETRY(appOnScreen = true),
}

enum class WakeWordStartOutcome { NOT_ENABLED, ALREADY_RUNNING, NO_MIC_PERMISSION, REQUESTED, REFUSED }

data class WakeWordStartFailure(val trigger: WakeWordStartTrigger, val error: String)

/**
 * Decides whether the wake-word service should be (re)started and keeps track of starts that
 * Android refused, so a dead service is retried and made visible instead of failing silently.
 *
 * Plain Kotlin with injected seams so it can be unit tested; [WakeWordServiceSupervisor] wires
 * it to the real service.
 */
class WakeWordStartGuard(
    private val isEnabled: () -> Boolean,
    private val isRunning: () -> Boolean,
    private val hasMicPermission: () -> Boolean,
    /** Asks Android to start the service. May throw when the start is not allowed. */
    private val requestStart: () -> Unit,
    /** Called at most once per process, with the first failure that happened on screen. */
    private val report: (WakeWordStartFailure) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val _failure = MutableStateFlow<WakeWordStartFailure?>(null)

    /** The latest start that failed with the app on screen, or null. Drives the banner. */
    val failure: StateFlow<WakeWordStartFailure?> = _failure

    @Volatile
    private var lastTrigger: WakeWordStartTrigger? = null
    private var reported = false

    @Synchronized
    fun ensureRunning(trigger: WakeWordStartTrigger): WakeWordStartOutcome {
        val outcome = when {
            !isEnabled() -> WakeWordStartOutcome.NOT_ENABLED
            isRunning() -> WakeWordStartOutcome.ALREADY_RUNNING
            !hasMicPermission() -> WakeWordStartOutcome.NO_MIC_PERMISSION
            else -> {
                lastTrigger = trigger
                try {
                    requestStart()
                    WakeWordStartOutcome.REQUESTED
                } catch (e: Exception) {
                    recordFailure(trigger, e)
                    WakeWordStartOutcome.REFUSED
                }
            }
        }
        log("ensureRunning($trigger) -> $outcome")
        return outcome
    }

    /** The service was created but its startForeground() call was refused. */
    @Synchronized
    fun onStartForegroundFailed(error: Throwable) {
        recordFailure(lastTrigger ?: WakeWordStartTrigger.PROCESS_START, error)
    }

    /** The service is running in the foreground. */
    @Synchronized
    fun onStarted() {
        log("started (requested by ${lastTrigger ?: "the system"})")
        _failure.value = null
    }

    private fun recordFailure(trigger: WakeWordStartTrigger, error: Throwable) {
        val failure = WakeWordStartFailure(trigger, "${error.javaClass.simpleName}: ${error.message}")
        log("start failed ($trigger): ${failure.error}")
        // A refusal with nothing on screen is the platform working as designed; the next
        // on-screen trigger retries, so there is nothing to show or report yet.
        if (!trigger.appOnScreen) return
        _failure.value = failure
        if (!reported) {
            reported = true
            report(failure)
        }
    }
}
