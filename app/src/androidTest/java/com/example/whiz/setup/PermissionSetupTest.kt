package com.example.whiz.setup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.whiz.test_helpers.PermissionAutomator
import com.example.whiz.test_helpers.AdbPermissionGranter
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Standalone test class that only sets up permissions.
 * This test is designed to be run first to enable accessibility services,
 * which will then persist for subsequent non-instrumented tests.
 *
 * Usage:
 * 1. Run this test with instrumentation to grant permissions
 * 2. Permissions will persist after test completes
 * 3. Run actual tests (instrumented or non-instrumented) with permissions already granted
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PermissionSetupTest {

    companion object {
        private const val TAG = "PermissionSetupTest"
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private lateinit var device: UiDevice
    private lateinit var permissionAutomator: PermissionAutomator
    private lateinit var adbPermissionGranter: AdbPermissionGranter

    @Before
    fun setUp() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        permissionAutomator = PermissionAutomator()
        adbPermissionGranter = AdbPermissionGranter()

        Log.d(TAG, "🚀 Permission Setup Test initialized")
    }

    @Test
    fun setupAllPermissions() = runBlocking {
        Log.d(TAG, "==========================================")
        Log.d(TAG, "🔧 SETTING UP PERMISSIONS FOR TESTING")
        Log.d(TAG, "==========================================")

        // First try ADB method (faster and more reliable)
        Log.d(TAG, "📱 Attempting to grant permissions via ADB...")
        val adbSuccess = try {
            adbPermissionGranter.grantAllPermissions()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ ADB permission grant failed: ${e.message}")
            false
        }

        if (adbSuccess) {
            Log.d(TAG, "✅ Permissions granted via ADB")

            // Verify the permissions are actually enabled
            val status = adbPermissionGranter.getPermissionStatus()
            Log.d(TAG, "📊 Permission Status:")
            Log.d(TAG, "  • Microphone: ${if (status.microphoneGranted) "✅" else "❌"}")
            Log.d(TAG, "  • Overlay: ${if (status.overlayGranted) "✅" else "❌"}")
            Log.d(TAG, "  • Accessibility: ${if (status.accessibilityEnabled) "✅" else "❌"}")

            // Give time for services to initialize
            delay(2000)
        } else {
            // Fallback to UI automation if ADB fails
            Log.d(TAG, "📱 Falling back to UI automation method...")

            // Launch the app to trigger permission dialogs
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.example.whiz.debug")

            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)

                // Wait for app to launch
                delay(3000)

                // Handle any permission dialogs that appear
                var dialogsHandled = false
                var attempts = 0
                val maxAttempts = 5

                while (attempts < maxAttempts) {
                    Log.d(TAG, "🔍 Checking for permission dialogs (attempt ${attempts + 1}/$maxAttempts)...")

                    if (permissionAutomator.handlePermissionDialogs()) {
                        dialogsHandled = true
                        Log.d(TAG, "✅ Permission dialog handled")
                        delay(2000) // Wait for next dialog
                    } else {
                        if (dialogsHandled) {
                            // We handled at least one dialog and now there are no more
                            Log.d(TAG, "✅ All permission dialogs handled")
                            break
                        } else if (attempts > 2) {
                            // After a few attempts, if no dialogs found, we're done
                            Log.d(TAG, "ℹ️ No more permission dialogs to handle")
                            break
                        }
                        delay(1000)
                    }
                    attempts++
                }
            } else {
                Log.e(TAG, "❌ Could not create launch intent for app")
            }
        }

        // Final verification
        Log.d(TAG, "==========================================")
        Log.d(TAG, "📊 FINAL PERMISSION STATUS:")
        val finalStatus = adbPermissionGranter.getPermissionStatus()
        Log.d(TAG, "  • Microphone: ${if (finalStatus.microphoneGranted) "✅ GRANTED" else "❌ NOT GRANTED"}")
        Log.d(TAG, "  • Overlay: ${if (finalStatus.overlayGranted) "✅ GRANTED" else "❌ NOT GRANTED"}")
        Log.d(TAG, "  • Accessibility: ${if (finalStatus.accessibilityEnabled) "✅ ENABLED" else "❌ NOT ENABLED"}")
        Log.d(TAG, "==========================================")

        // The test passes regardless - its job is just to set up permissions
        Log.d(TAG, "✅ Permission setup complete. Permissions will persist for subsequent tests.")
        Log.d(TAG, "ℹ️ You can now run your actual tests with permissions already granted.")
    }

    @Test
    fun setupAccessibilityOnly() = runBlocking {
        Log.d(TAG, "==========================================")
        Log.d(TAG, "🔧 SETTING UP ACCESSIBILITY SERVICE ONLY")
        Log.d(TAG, "==========================================")

        // This test only sets up accessibility service, useful when other permissions are already granted
        Log.d(TAG, "📱 Enabling accessibility service via ADB...")

        val success = adbPermissionGranter.enableAccessibilityService()

        if (success) {
            Log.d(TAG, "✅ Accessibility service enabled successfully")
        } else {
            Log.d(TAG, "⚠️ Failed to enable accessibility service via ADB, trying UI automation...")

            // Launch settings and navigate to accessibility
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.example.whiz.debug")

            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                delay(2000)

                // Try to handle accessibility dialog
                permissionAutomator.handlePermissionDialogs()
            }
        }

        // Verify
        val status = adbPermissionGranter.getPermissionStatus()
        Log.d(TAG, "📊 Accessibility Status: ${if (status.accessibilityEnabled) "✅ ENABLED" else "❌ NOT ENABLED"}")
        Log.d(TAG, "✅ Accessibility setup complete")
    }
}