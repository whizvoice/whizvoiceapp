package com.example.whiz.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whiz.data.preferences.WakeWordPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WakeWordBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordBootReceiver"
    }

    @Inject
    lateinit var wakeWordPreferences: WakeWordPreferences

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, checking wake word preference")

        try {
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
