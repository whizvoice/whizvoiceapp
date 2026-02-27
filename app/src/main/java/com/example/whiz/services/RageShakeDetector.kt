package com.example.whiz.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class RageShakeDetector @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "RageShakeDetector"
        private const val SHAKE_THRESHOLD = 15f // m/s^2 net acceleration
        private const val SHAKE_COUNT_THRESHOLD = 4
        private const val SHAKE_WINDOW_MS = 1000L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _shakeDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shakeDetected: SharedFlow<Unit> = _shakeDetected.asSharedFlow()

    private val shakeTimestamps = mutableListOf<Long>()

    @VisibleForTesting
    var triggerCount = 0
        private set

    @VisibleForTesting
    var clock: () -> Long = { System.currentTimeMillis() }

    fun startListening() {
        if (accelerometer == null) {
            Log.w(TAG, "No accelerometer sensor available")
            return
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        Log.d(TAG, "Started listening for shakes")
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Stopped listening for shakes")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        processAcceleration(event.values[0], event.values[1], event.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @VisibleForTesting
    fun processAcceleration(x: Float, y: Float, z: Float) {
        val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
        if (acceleration < SHAKE_THRESHOLD) return

        val now = clock()

        // Add this shake event and prune old ones outside the window
        shakeTimestamps.add(now)
        shakeTimestamps.removeAll { it < now - SHAKE_WINDOW_MS }

        if (shakeTimestamps.size >= SHAKE_COUNT_THRESHOLD) {
            Log.i(TAG, "Shake detected! (${shakeTimestamps.size} events in window)")
            shakeTimestamps.clear()
            triggerCount++
            _shakeDetected.tryEmit(Unit)
        }
    }

    @VisibleForTesting
    fun simulateShake() {
        _shakeDetected.tryEmit(Unit)
    }
}
