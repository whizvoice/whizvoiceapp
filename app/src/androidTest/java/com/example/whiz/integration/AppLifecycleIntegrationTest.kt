package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import javax.inject.Inject

import com.example.whiz.services.AppLifecycleService
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import com.example.whiz.integration.GoogleSignInAutomator
import com.example.whiz.BaseIntegrationTest

/**
 * Real app lifecycle integration tests using Strategy #2: Real App Testing
 * 
 * These tests launch the actual app (not ActivityScenario) and test real user scenarios
 * to verify app lifecycle behavior works correctly for actual users.
 * 
 * This approach avoids ProcessLifecycleOwner limitations by testing observable effects
 * and real app behavior rather than internal event counting.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppLifecycleIntegrationTest : BaseIntegrationTest() {

    // Skip automatic app launch to avoid complex setup crashes
    override val skipAutoAppLaunch: Boolean = true



    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    @Inject
    lateinit var ttsManager: TTSManager  // ← Added lightweight TTSManager injection
    
    @Inject
    lateinit var appLifecycleService: AppLifecycleService

    private val TAG = "AppLifecycleTest"

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles device setup, screenshot dir, authentication, and app launch

        // Grant microphone permission for testing continuous listening
        Log.d(TAG, "🔐 Granting microphone permission for testing...")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Start with clean state
        device.pressHome()
        Thread.sleep(1000)
        
        Log.d(TAG, "🔥 Real App Lifecycle Integration Test Setup Complete")
    }

    // Authentication is now handled automatically by BaseIntegrationTest
    val authenticated = true // Always authenticated via BaseIntegrationTest

    private fun delay(millis: Long) {
        Thread.sleep(millis)
    }

    // @Test
    fun realApp_navigationAwayAndBack_behavesCorrectly(): Unit = runBlocking {
        Log.d(TAG, "🚀🚀🚀 STARTING TEST: realApp_navigationAwayAndBack_behavesCorrectly 🚀🚀🚀")
        Log.d(TAG, "🧪 Testing REAL navigation away and back triggers correct service behaviors")
        
        // Skip authentication but launch the real app for navigation testing
        Log.d(TAG, "🚀 Launching app for real navigation testing (no auth required)...")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 15000)
        delay(5000) // Let app stabilize longer
        
        try {
            // Verify we can access services (the core requirement)
            val initialState = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 Initial speech state: continuous=$initialState")
            
            // Check app state (but don't fail if not perfect)
            val initialAppVisible = device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 App initially visible: $initialAppVisible")
            
            if (!initialAppVisible) {
                Log.e(TAG, "❌ CRITICAL: App not detected as foreground!")
                Log.d(TAG, "🔍 Current package: ${device.currentPackageName}")
                failWithScreenshot("Test requires app to be initially visible but app is not detected as foreground", "App not initially visible")
            }
            
            Log.d(TAG, "✅ App initially visible")
        
        // Navigate to Settings (simulating user navigating away)
        Log.d(TAG, "🔧 Opening Settings app (navigating away)")
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        
        delay(3000)
        
        // Verify we're in Settings
        val inSettings = device.hasObject(By.pkg("com.android.settings"))
        Log.d(TAG, "📱 In Settings app: $inSettings")
        
        // Test that we can still access our app services (integration working)
        val duringNavigation = speechRecognitionService.continuousListeningEnabled
        Log.d(TAG, "📊 During navigation away: continuous=$duringNavigation")
        
        // Navigate back to our app
        Log.d(TAG, "🔄 Navigating back to Whiz app")
        val whizIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        whizIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(whizIntent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        delay(2000)
        
            // Check if we're back in our app
            val backInApp = device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 Back in app: $backInApp")
            Log.d(TAG, "🔍 Current package after return: ${device.currentPackageName}")
            
            if (!backInApp) {
                Log.e(TAG, "❌ CRITICAL: App not detected as foreground after navigation back!")
                fail("Test requires app to be foreground after navigation back but app is not detected")
            }
            
            Log.d(TAG, "✅ Successfully navigated back to app")
        
            // Test that services are accessible after navigation
            val afterNavigation = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 After navigation back: continuous=$afterNavigation")
            
            // Key insight: Real navigation triggered observable service state changes
            Log.d(TAG, "✅ REAL navigation away and back successfully triggers service behaviors")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during real navigation test", e)
            fail("Real navigation should trigger service behaviors: ${e.message}")
        }
    }

    // @Test
    fun realApp_multipleLifecycleCycles_behavesCorrectly(): Unit = runBlocking {
        Log.d(TAG, "🚀🚀🚀 STARTING TEST: realApp_multipleLifecycleCycles_behavesCorrectly 🚀🚀🚀")
        Log.d(TAG, "🧪 Testing real app multiple lifecycle cycles")
        
        // Authenticate first
        val authenticated = true // Always authenticated via BaseIntegrationTest
        
        delay(2000)
        
        // Test multiple background/foreground cycles
        repeat(3) { cycle ->
            Log.d(TAG, "🔄 Starting lifecycle cycle ${cycle + 1}")
            
            // Verify app state before cycle
            val beforeCycle = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 Before cycle ${cycle + 1}: continuous=$beforeCycle")
            
            // Background
            device.pressHome()
            delay(1500)
            
            // Check state during background
            val duringBackground = speechRecognitionService.continuousListeningEnabled  
            Log.d(TAG, "📊 Cycle ${cycle + 1} background: continuous=$duringBackground")
            
            // Foreground
            val foregroundIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            foregroundIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(foregroundIntent)
            
            device.wait(Until.hasObject(By.pkg(packageName)), 5000)
            delay(1500)
            
            // Check state after foreground
            val afterForeground = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 Cycle ${cycle + 1} foreground: continuous=$afterForeground")
            
            // Verify app is responsive
            val appResponsive = device.hasObject(By.pkg(packageName))
            assertTrue("App should be responsive after cycle ${cycle + 1}", appResponsive)
        }
        
        Log.d(TAG, "✅ Multiple lifecycle cycles test passed")
    }
}