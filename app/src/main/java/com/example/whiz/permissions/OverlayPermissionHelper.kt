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
                val appPackageName = activity.packageName
                Log.d(TAG, "Requesting overlay permission for package: $appPackageName")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$appPackageName")
                Log.d(TAG, "Starting activity with URI: ${intent.data}")
                activity.startActivityForResult(intent, requestCode)
            }
        }
    }
    
    fun requestOverlayPermissionWithLauncher(
        activity: Activity, 
        launcher: ActivityResultLauncher<Intent>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(activity)) {
                val appPackageName = activity.packageName
                Log.d(TAG, "Requesting overlay permission with launcher for package: $appPackageName")
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$appPackageName")
                Log.d(TAG, "Starting activity with URI: ${intent.data}")
                launcher.launch(intent)
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