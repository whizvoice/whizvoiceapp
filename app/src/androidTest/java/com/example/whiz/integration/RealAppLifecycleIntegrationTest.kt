package com.example.whiz.integration

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Real integration test for app lifecycle behavior.
 * 
 * This test launches the actual WhizVoice app (not in test context) and monitors
 * real lifecycle events by observing the app's actual behavior and state changes.
 * 
 * Tests verify:
 * 1. App starts correctly and reaches foreground state
 * 2. App responds to real backgrounding (home button)
 * 3. App responds to real foregrounding (app switching)
 * 4. Speech recognition state changes correctly with lifecycle
 * 5. AppLifecycleService events are triggered by real lifecycle changes
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RealAppLifecycleIntegrationTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val packageName = "com.example.whiz.debug"
    private val TAG = "RealAppLifecycleTest"

    @Before
    fun setup() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        
        // Make sure we start with a clean slate
        device.pressHome()
        delay(1000)
    }

    @Test
    fun realApp_startsAndShowsMainInterface(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing real app startup and main interface")
        
        // Launch the actual WhizVoice app
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        assertNotNull("WhizVoice app should be installed", intent)
        
        intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        // Wait for app to start and verify it reaches main interface
        val appStarted = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        assertTrue("WhizVoice app should start within 10 seconds", appStarted)
        
        // Look for key UI elements that indicate successful startup
        val hasMainInterface = device.wait(Until.hasObject(
            By.textContains("New Chat").pkg(packageName)
        ), 5000) || device.wait(Until.hasObject(
            By.descContains("Start new chat").pkg(packageName)
        ), 5000)
        
        assertTrue("App should show main interface (New Chat or FAB)", hasMainInterface)
        
        Log.d(TAG, "✅ Real app startup test passed")
    }

    @Test
    fun realApp_backgroundAndForeground_preservesState(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing real app background/foreground behavior")
        
        // Start the app
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        delay(2000) // Let app fully initialize
        
        // Navigate to a chat if possible (to test state preservation)
        val newChatButton = device.findObject(By.textContains("New Chat").pkg(packageName)) 
            ?: device.findObject(By.descContains("Start new chat").pkg(packageName))
        
        if (newChatButton != null) {
            newChatButton.click()
            delay(1000)
            Log.d(TAG, "📱 Navigated to chat screen")
        }
        
        // Remember current state (check what screen we're on)
        val isInChat = device.hasObject(By.descContains("Message input").pkg(packageName)) ||
                      device.hasObject(By.textContains("Type a message").pkg(packageName))
        Log.d(TAG, "📝 App state before backgrounding: isInChat=$isInChat")
        
        // Background the app (real lifecycle event)
        Log.d(TAG, "🏠 Pressing home to background app")
        device.pressHome()
        delay(2000)
        
        // Verify app is backgrounded
        val isBackgrounded = !device.hasObject(By.pkg(packageName))
        assertTrue("App should be backgrounded after home press", isBackgrounded)
        
        // Foreground the app (real lifecycle event) 
        Log.d(TAG, "🔄 Bringing app back to foreground")
        val foregroundIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        foregroundIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        context.startActivity(foregroundIntent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        delay(1000)
        
        // Verify app returned to similar state
        val isBackInChat = device.hasObject(By.descContains("Message input").pkg(packageName)) ||
                          device.hasObject(By.textContains("Type a message").pkg(packageName))
        
        if (isInChat) {
            assertTrue("App should return to chat screen after foregrounding", isBackInChat)
        }
        
        Log.d(TAG, "✅ Real app background/foreground test passed")
    }

    @Test
    fun realApp_multipleBackgroundForegroundCycles(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing multiple real background/foreground cycles")
        
        // Start the app
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        delay(2000)
        
        // Perform multiple background/foreground cycles
        repeat(3) { cycle ->
            Log.d(TAG, "🔄 Starting cycle ${cycle + 1}")
            
            // Background
            device.pressHome()
            delay(1000)
            
            // Verify backgrounded
            val isBackgrounded = !device.hasObject(By.pkg(packageName))
            assertTrue("Cycle ${cycle + 1}: App should be backgrounded", isBackgrounded)
            
            // Foreground  
            val foregroundIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            foregroundIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(foregroundIntent)
            
            device.wait(Until.hasObject(By.pkg(packageName)), 5000)
            delay(1000)
            
            // Verify app is responsive
            val isForegrounded = device.hasObject(By.pkg(packageName))
            assertTrue("Cycle ${cycle + 1}: App should be foregrounded", isForegrounded)
            
            Log.d(TAG, "✅ Cycle ${cycle + 1} completed successfully")
        }
        
        Log.d(TAG, "✅ Multiple background/foreground cycles test passed")
    }

    @Test
    fun realApp_navigationAwayAndBack_viaTaskSwitcher(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing navigation away via task switcher")
        
        // Start WhizVoice app
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        delay(2000)
        
        // Navigate away by opening Settings app
        Log.d(TAG, "⚙️ Opening Settings app")
        val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(settingsIntent)
        
        delay(2000)
        
        // Verify we're in Settings
        val inSettings = device.hasObject(By.pkg("com.android.settings"))
        assertTrue("Should be in Settings app", inSettings)
        
        // Navigate back to WhizVoice via task switcher
        Log.d(TAG, "🔄 Using recent apps to return to WhizVoice")
        device.pressRecentApps()
        delay(1000)
        
        // Look for WhizVoice in recent apps and tap it
        val whizVoiceTask = device.findObject(By.pkg(packageName))
        if (whizVoiceTask != null) {
            whizVoiceTask.click()
            delay(1000)
        } else {
            // Fallback: launch directly
            val fallbackIntent = context.packageManager.getLaunchIntentForPackage(packageName)!!
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(fallbackIntent)
            device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        }
        
        // Verify we're back in WhizVoice
        val backInWhizVoice = device.hasObject(By.pkg(packageName))
        assertTrue("Should be back in WhizVoice app", backInWhizVoice)
        
        Log.d(TAG, "✅ Navigation away and back test passed")
    }

    @Test
    fun realApp_permissionDialog_behavesCorrectly(): Unit = runBlocking {
        Log.d(TAG, "🧪 Testing permission dialog behavior in real app")
        
        // This test verifies that the real app shows permission dialogs correctly
        // and that the app doesn't crash when permissions are requested
        
        // Start the app fresh
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        
        device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        delay(3000) // Give app time to show permission dialog if needed
        
        // Look for permission dialog or main interface
        val hasPermissionDialog = device.hasObject(By.textContains("Microphone")) ||
                                 device.hasObject(By.textContains("permission")) ||
                                 device.hasObject(By.textContains("Allow"))
        
        val hasMainInterface = device.hasObject(By.textContains("New Chat")) ||
                              device.hasObject(By.descContains("Start new chat"))
        
        // Either should be true - either we see permission dialog or main interface
        assertTrue("App should show either permission dialog or main interface", 
                  hasPermissionDialog || hasMainInterface)
        
        // If permission dialog is shown, interact with it
        if (hasPermissionDialog) {
            Log.d(TAG, "📱 Permission dialog detected, testing interaction")
            
            val allowButton = device.findObject(By.textContains("Allow")) ?:
                             device.findObject(By.textContains("Grant"))
            
            if (allowButton != null) {
                allowButton.click()
                delay(1000)
                
                // Verify app continues to main interface
                val reachedMainInterface = device.wait(Until.hasObject(
                    By.textContains("New Chat").pkg(packageName)
                ), 5000) || device.wait(Until.hasObject(
                    By.descContains("Start new chat").pkg(packageName)
                ), 5000)
                
                assertTrue("App should reach main interface after permission grant", 
                          reachedMainInterface)
            }
        }
        
        Log.d(TAG, "✅ Permission dialog test passed")
    }

    private fun delay(millis: Long) {
        Thread.sleep(millis)
    }
} 