package com.example.whiz.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.data.preferences.WakeWordPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class WakeWordBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordBootReceiver"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WakeWordBootReceiverEntryPoint {
        fun wakeWordPreferences(): WakeWordPreferences
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, checking wake word preference")

        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WakeWordBootReceiverEntryPoint::class.java
            )
            val wakeWordPreferences = entryPoint.wakeWordPreferences()
            val enabled = wakeWordPreferences.isEnabledOnce()
            Log.d(TAG, "Wake word enabled=$enabled")
            if (enabled) {
                WakeWordService.start(context)
                Log.d(TAG, "Started WakeWordService after boot")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking wake word preference on boot", e)
        }
    }
}
