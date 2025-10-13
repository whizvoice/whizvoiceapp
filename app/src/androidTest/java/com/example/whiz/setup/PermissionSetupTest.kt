package com.example.whiz.setup

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.whiz.test_helpers.PermissionAutomator
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

        init {
            // Tell TestAppModule to use real accessibility for this test
            System.setProperty("test.real.accessibility", "true")
            Log.d(TAG, "📱 Configured test to use REAL accessibility services (not mocked)")
        }
    }

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private lateinit var device: UiDevice
    private lateinit var permissionAutomator: PermissionAutomator

    @Before
    fun setUp() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        permissionAutomator = PermissionAutomator()

        Log.d(TAG, "🚀 Permission Setup Test initialized")
    }

    @Test
    fun setupAllPermissions() {
        runBlocking {
        Log.d(TAG, "==========================================")
        Log.d(TAG, "🔧 SETTING UP PERMISSIONS FOR TESTING")
        Log.d(TAG, "==========================================")

        // Use UI automation to grant permissions
        Log.d(TAG, "📱 Using UI automation to grant permissions...")

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

        // The test passes regardless - its job is just to set up permissions
        Log.d(TAG, "✅ Permission setup complete. Permissions will persist for subsequent tests.")
        Log.d(TAG, "ℹ️ You can now run your actual tests with permissions already granted.")
        }
    }

    @Test
    fun setupAccessibilityOnly() {
        runBlocking {
        Log.d(TAG, "==========================================")
        Log.d(TAG, "🔧 SETTING UP ACCESSIBILITY SERVICE ONLY")
        Log.d(TAG, "==========================================")

        // Launch the app to foreground first
        Log.d(TAG, "📱 Launching WhizVoice app to foreground...")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.example.whiz.debug")

        if (launchIntent != null) {
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)

            // Wait for app to fully launch
            Log.d(TAG, "⏳ Waiting for app to launch...")
            delay(3000)

            // Now the app should be in foreground and might show permission dialogs
            Log.d(TAG, "🔍 Looking for permission dialogs...")

            var dialogsHandled = false
            var attempts = 0
            val maxAttempts = 5

            while (attempts < maxAttempts) {
                Log.d(TAG, "🔍 Checking for permission dialogs (attempt ${attempts + 1}/$maxAttempts)...")

                // PermissionAutomator will handle any permission dialogs including accessibility
                if (permissionAutomator.handlePermissionDialogs()) {
                    dialogsHandled = true
                    Log.d(TAG, "✅ Permission dialog handled by PermissionAutomator")
                    // Wait a bit in case there are more dialogs
                    delay(2000)
                } else {
                    if (dialogsHandled) {
                        Log.d(TAG, "✅ All permission dialogs handled")
                        break
                    } else {
                        Log.d(TAG, "ℹ️ No permission dialogs found, waiting...")
                        // Maybe the dialog will appear after a delay
                        delay(2000)
                    }
                }
                attempts++
            }

            if (!dialogsHandled) {
                Log.w(TAG, "⚠️ No permission dialogs were shown by the app")
                Log.d(TAG, "The app may already have permissions or needs to be configured to request them")
            }
        } else {
            Log.e(TAG, "❌ Could not create launch intent for app")
        }

        Log.d(TAG, "✅ Accessibility setup test complete")
        }
    }
}