// Quick test script to check WhatsApp
// Run with: adb shell am broadcast -a com.example.whiz.DEBUG_APPS

package com.example.whiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DebugAppsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launcher = AppLauncherTool(context)
        launcher.debugListAllApps()
    }
}