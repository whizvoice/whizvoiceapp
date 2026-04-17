package com.example.whiz.util

import android.util.Log

object LogcatCapture {
    private const val TAG = "LogcatCapture"
    private const val DEFAULT_MAX_BYTES = 200_000

    fun capture(maxBytes: Int = DEFAULT_MAX_BYTES): String? {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid", "${android.os.Process.myPid()}")
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.isBlank()) null
            else if (output.length > maxBytes) output.takeLast(maxBytes) else output
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture logcat", e)
            null
        }
    }
}
