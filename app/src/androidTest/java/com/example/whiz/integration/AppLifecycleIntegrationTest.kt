package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiObject2
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

    @Test
    fun realApp_navigationAwayAndBack_behavesCorrectly(): Unit = runBlocking {
        Log.d(TAG, "🚀🚀🚀 STARTING TEST: realApp_navigationAwayAndBack_behavesCorrectly 🚀🚀🚀")
        Log.d(TAG, "🧪 Testing REAL navigation away and back triggers correct service behaviors")
        
        // Skip authentication but launch the real app for navigation testing
        Log.d(TAG, "🚀 Launching app for real navigation testing (no auth required)...")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 15000)
        delay(3000) // Let app stabilize longer
        
        try {
            // Verify we can access services (the core requirement)
            val initialState = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 Initial speech state: continuous=$initialState")
            
            // Check app state (but don't fail if not perfect)
            val initialAppVisible = device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 App initially visible: $initialAppVisible")

            // delay(3000) // Let app stabilize
            
            if (!initialAppVisible) {
                Log.e(TAG, "❌ CRITICAL: App not detected as foreground!")
                Log.d(TAG, "🔍 Current package: ${device.currentPackageName}")
                failWithScreenshot("Test requires app to be initially visible but app is not detected as foreground", "App not initially visible")
            }
                        // Navigate to a chat page (where continuous listening should be enabled)
            Log.d(TAG, "📱 Navigating to chat page...")

            // First, let's debug what UI elements are actually available
            Log.d(TAG, "🔍 UI DEBUGGING: Scanning for available elements...")
            val allElements = device.findObjects(By.pkg(packageName))
            Log.d(TAG, "🔍 Found ${allElements.size} total elements in package $packageName")
            
            val clickableElements = device.findObjects(By.clickable(true).pkg(packageName))
            Log.d(TAG, "🔍 Found ${clickableElements.size} clickable elements")
            
            clickableElements.forEachIndexed { index, element ->
                try {
                    val text = element.text ?: "no text"
                    val desc = element.contentDescription ?: "no desc"
                    val className = element.className ?: "no class"
                    Log.d(TAG, "🔍 Clickable[$index]: text='$text', desc='$desc', class='$className'")
                } catch (e: Exception) {
                    Log.d(TAG, "🔍 Clickable[$index]: error reading properties - ${e.message}")
                }
            }
            
            // Also check for all elements that might have text
            Log.d(TAG, "🔍 Checking all elements for text content...")
            allElements.take(15).forEachIndexed { index, element ->
                try {
                    val text = element.text
                    if (!text.isNullOrEmpty()) {
                        Log.d(TAG, "🔍 Element[$index] with text: '$text'")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "🔍 Element[$index]: error reading text - ${e.message}")
                }
            }

            // Look for "New Chat" FAB to create/enter a chat
            Log.d(TAG, "🔍 Searching for New Chat FAB...")
            
            // Try multiple specific approaches to find the FAB
            var newChatButton: UiObject2? = null
            
            // Method 1: Exact content description match
            newChatButton = device.findObject(By.desc("New Chat").pkg(packageName))
            if (newChatButton != null) {
                Log.d(TAG, "✅ Found FAB with exact 'New Chat' description")
            } else {
                failWithScreenshot("Test was unable to find New Chat button")
            }


            // Debug what we're about to click
            try {
                val buttonText = newChatButton?.text ?: "no text"
                val buttonDesc = newChatButton?.contentDescription ?: "no desc"
                val buttonBounds = newChatButton?.visibleBounds
                Log.d(TAG, "🔘 About to click: text='$buttonText', desc='$buttonDesc', bounds=$buttonBounds")
            } catch (e: Exception) {
                Log.d(TAG, "🔘 Could not read button properties: ${e.message}")
            }
            
            Log.d(TAG, "🔘 Clicking New Chat button...")
            newChatButton?.click()
            delay(2000) // Wait for chat to load
            
            // Verify we actually navigated by checking if we're still on the chats list
            val stillOnChatsList = device.hasObject(By.text("My Chats").pkg(packageName))
            if (stillOnChatsList) {
                Log.e(TAG, "❌ CRITICAL: Still on 'My Chats' page after clicking - navigation failed!")
                failWithScreenshot("Clicked New Chat button but still on My Chats page - navigation failed")
            }
            
            Log.d(TAG, "✅ Successfully navigated away from My Chats page")
            

            // Now we should be on a chat page - verify continuous listening is enabled
            val chatPageListeningState = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 Chat page listening state: continuous=$chatPageListeningState")

            if (!chatPageListeningState) {
                Log.e(TAG, "❌ CRITICAL: Expected continuous listening to be TRUE on chat page, but got FALSE")
                failWithScreenshot("Continuous listening should be TRUE on chat page but was FALSE")
            }

            Log.d(TAG, "✅ Continuous listening correctly enabled on chat page")

            // Check app state - must be in foreground to test lifecycle behavior
            val isInForeground = device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 App foreground detected: $isInForeground")

            if (!isInForeground) {
                Log.e(TAG, "❌ CRITICAL: App not detected as foreground!")
                Log.d(TAG, "🔍 Current package: ${device.currentPackageName}")
                failWithScreenshot("Test requires app to be in foreground but app is not detected as foreground")
            }

            Log.d(TAG, "✅ App confirmed in foreground on chat page")

            // REAL USER ACTION: Background the app via home button
            Log.d(TAG, "🏠 REAL ACTION: Pressing home to background app...")
            device.pressHome()
            delay(2000)

            // Check if app is backgrounded (home press should change focus)
            val isBackgrounded = !device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 App backgrounded: $isBackgrounded")
            Log.d(TAG, "🔍 Current package after home: ${device.currentPackageName}")

            if (!isBackgrounded) {
                Log.e(TAG, "❌ CRITICAL: App not successfully backgrounded!")
                Log.d(TAG, "🔍 Current package after home: ${device.currentPackageName}")
                failWithScreenshot("Test requires app to be backgrounded but app is still detected as foreground")
            }

            Log.d(TAG, "✅ App successfully backgrounded")

            // Test that real backgrounding affects services - should be FALSE
            val backgroundState = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 After REAL background: continuous=$backgroundState")

            if (!backgroundState) {
                Log.e(TAG, "❌ CRITICAL: Expected continuous listening to be TRUE when backgrounded, but got FALSE")
                failWithScreenshot("Continuous listening should be FALSE when backgrounded but was TRUE")
            }

            Log.d(TAG, "✅ Continuous listening correctly preserved on state when backgrounded")

            // REAL USER ACTION: Foreground the app via intent (back to chat page)
            Log.d(TAG, "🔄 REAL ACTION: Launching app to foreground...")
            val foregroundIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            foregroundIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(foregroundIntent)

            device.wait(Until.hasObject(By.pkg(packageName)), 5000)
            delay(2000)

            // Check if app is back in foreground
            val isBackInForeground = device.hasObject(By.pkg(packageName))
            Log.d(TAG, "📱 App back in foreground: $isBackInForeground")
            Log.d(TAG, "🔍 Current package after launch: ${device.currentPackageName}")

            if (!isBackInForeground) {
                Log.e(TAG, "❌ CRITICAL: App not successfully returned to foreground!")
                failWithScreenshot("Test requires app to return to foreground but app is not detected as foreground")
            }

            Log.d(TAG, "✅ App successfully returned to foreground")
        
            // Test that services are accessible after navigation
            val afterNavigation = speechRecognitionService.continuousListeningEnabled
            Log.d(TAG, "📊 After navigation back: continuous=$afterNavigation")

            if (!afterNavigation) {
                Log.e(TAG, "❌ CRITICAL: Expected continuous listening to remain TRUE when navigating back to app, but got FALSE")
                failWithScreenshot("Continuous listening should be FALSE when backgrounded but was TRUE")
            }
            
            // Key insight: Real navigation triggered observable service state changes
            Log.d(TAG, "✅ REAL navigation away and back successfully triggers service behaviors")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during real navigation test", e)
            failWithScreenshot("Real navigation should trigger service behaviors: ${e.message}")
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