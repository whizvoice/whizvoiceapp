package com.example.whiz.util

import android.os.Build

/**
 * Detects Android emulator environments by checking Build.HARDWARE.
 *
 * Used to tag UI dumps uploaded to the server so the screen-agent autofix
 * pipeline can ignore dumps that come from local dev / CI emulators
 * (whiz-test-device snapshot on CI runs on ranchu).
 *
 * ranchu  — modern QEMU2 Android Studio emulator
 * goldfish — legacy QEMU1 emulator
 * gce      — Google Compute Engine cloud emulator
 *
 * No shipping physical phone reports any of these values.
 */
object EmulatorDetection {
    fun isRunningOnEmulator(): Boolean {
        return Build.HARDWARE == "ranchu" ||
                Build.HARDWARE == "goldfish" ||
                Build.HARDWARE == "gce"
    }
}
