package com.example.whiz.test_helpers

import android.os.Build
import android.util.Log
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Test rule that skips tests when running on CI/GitHub Actions or on emulators.
 * Tests will run normally on real devices when executed locally.
 * 
 * Usage:
 * ```
 * @get:Rule
 * val skipOnCIOrEmulatorRule = SkipOnCIOrEmulatorRule()
 * ```
 * 
 * The test will be skipped (not failed) when:
 * - Running in GitHub Actions (CI=true or GITHUB_ACTIONS=true)
 * - Running on an Android emulator
 */
class SkipOnCIOrEmulatorRule : TestRule {
    
    companion object {
        private const val TAG = "SkipOnCIOrEmulatorRule"
    }
    
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                // Check if running in CI environment
                val isCI = System.getenv("CI") != null || 
                          System.getenv("GITHUB_ACTIONS") != null
                
                // Check if running on emulator
                val isEmulator = isRunningOnEmulator()
                
                if (isCI || isEmulator) {
                    val reason = when {
                        isCI && isEmulator -> "CI environment with emulator"
                        isCI -> "CI environment"
                        else -> "emulator"
                    }
                    
                    val testName = "${description.className}#${description.methodName}"
                    Log.i(TAG, "Skipping test '$testName' - running on $reason")
                    
                    // This will mark the test as skipped rather than failed
                    Assume.assumeFalse(
                        "Test skipped: requires real device (currently on $reason)",
                        true
                    )
                } else {
                    // Run the test normally on real devices
                    base.evaluate()
                }
            }
        }
    }
    
    /**
     * Detects if the test is running on an emulator.
     * Uses multiple heuristics to reliably detect emulators across different Android versions.
     */
    private fun isRunningOnEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic") ||
               Build.FINGERPRINT.contains("emulator") ||
               Build.FINGERPRINT.contains("unknown") ||
               Build.HARDWARE.contains("goldfish") ||
               Build.HARDWARE.contains("ranchu") ||
               Build.HARDWARE.contains("gce") ||
               Build.MODEL.contains("google_sdk") ||
               Build.MODEL.contains("Emulator") ||
               Build.MODEL.contains("Android SDK built for") ||
               Build.MANUFACTURER.contains("Genymotion") ||
               Build.PRODUCT.contains("sdk") ||
               Build.PRODUCT.contains("google_sdk") ||
               Build.PRODUCT.contains("sdk_google") ||
               Build.PRODUCT.contains("emulator") ||
               Build.BOARD.lowercase().contains("unknown") ||
               Build.BOOTLOADER.lowercase().contains("unknown") ||
               Build.BRAND.startsWith("generic") ||
               Build.DEVICE.startsWith("generic") ||
               (Build.BRAND == "google" && Build.MODEL.contains("sdk"))
    }
}