package com.example.whiz.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher

object OverlayPermissionHelper {
    private const val TAG = "OverlayPermissionHelper"
    
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
    
    fun requestOverlayPermission(activity: Activity, requestCode: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivityForResult(intent, requestCode)
                Log.d(TAG, "Requesting overlay permission")
            }
        }
    }
    
    fun requestOverlayPermissionWithLauncher(
        activity: Activity, 
        launcher: ActivityResultLauncher<Intent>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                launcher.launch(intent)
                Log.d(TAG, "Requesting overlay permission with launcher")
            }
        }
    }
    
    fun checkAndRequestIfNeeded(
        activity: Activity,
        onPermissionGranted: () -> Unit,
        onPermissionDenied: () -> Unit
    ): Boolean {
        return if (hasOverlayPermission(activity)) {
            onPermissionGranted()
            true
        } else {
            requestOverlayPermission(activity)
            onPermissionDenied()
            false
        }
    }
}