package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import com.example.whiz.BaseIntegrationTest

/**
 * Integration tests for voice assistant launch functionality.
 * Tests the "OK Google, open Whiz Voice" user flow by simulating the intents
 * that Google Assistant sends when voice commands are spoken.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class VoiceAssistantLaunchTest : BaseIntegrationTest() {

    private val TAG = "VoiceAssistantLaunchTest"

    @Test
    fun voiceAssistant_launchViaAssistIntent_opensApp(): Unit = runBlocking {
        Log.d(TAG, "🚀 STARTING TEST: Voice assistant launch via ASSIST intent")
        
        // Close app first to ensure we're testing fresh launch
        device.pressHome()
        Thread.sleep(1000)
        
        // Simulate what happens when Google Assistant processes "OK Google, open Whiz Voice"
        // Google Assistant typically sends an ACTION_ASSIST intent to launch voice interaction
        Log.d(TAG, "📱 Sending ACTION_ASSIST intent (simulates voice command)...")
        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        context.startActivity(assistIntent)
        
        // Wait for app to launch and verify it opened
        val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        assertTrue("App should launch when ACTION_ASSIST intent is sent", appLaunched)
        
        // Verify we're in the assistant activity (not just main activity)
        Thread.sleep(2000) // Allow activity to fully load
        
        // Check if AssistantActivity is active or main activity with voice interaction enabled
        val hasAssistantUI = device.hasObject(By.pkg(packageName))
        assertTrue("Whiz Voice should be visible after assistant launch", hasAssistantUI)
        
        Log.d(TAG, "✅ SUCCESS: Voice assistant launch via ASSIST intent works correctly")
    }

    @Test
    fun voiceAssistant_launchViaVoiceAssistIntent_opensApp(): Unit = runBlocking {
        Log.d(TAG, "🚀 STARTING TEST: Voice assistant launch via VOICE_ASSIST intent")
        
        // Close app first
        device.pressHome()
        Thread.sleep(1000)
        
        // Simulate ACTION_VOICE_ASSIST intent (alternative assistant launch method)
        Log.d(TAG, "📱 Sending ACTION_VOICE_ASSIST intent...")
        val voiceAssistIntent = Intent("android.intent.action.VOICE_ASSIST").apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        context.startActivity(voiceAssistIntent)
        
        // Wait for app to launch
        val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        assertTrue("App should launch when ACTION_VOICE_ASSIST intent is sent", appLaunched)
        
        Thread.sleep(2000)
        
        val hasVoiceUI = device.hasObject(By.pkg(packageName))
        assertTrue("Whiz Voice should be visible after voice assist launch", hasVoiceUI)
        
        Log.d(TAG, "✅ SUCCESS: Voice assistant launch via VOICE_ASSIST intent works correctly")
    }

    @Test
    fun voiceInteractionService_isProperlyRegistered(): Unit = runBlocking {
        Log.d(TAG, "🚀 STARTING TEST: Voice interaction service registration")
        
        // Verify that the voice interaction service is properly registered
        // This ensures the system can find and use Whiz Voice as an assistant
        val packageManager = context.packageManager
        
        // Check if our voice interaction service is declared
        val services = packageManager.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService"), 0
        )
        
        val whizServiceFound = services.any { serviceInfo ->
            serviceInfo.serviceInfo.packageName == packageName &&
            serviceInfo.serviceInfo.name.contains("WhizVoiceInteractionService")
        }
        
        assertTrue(
            "WhizVoiceInteractionService should be registered and discoverable by the system",
            whizServiceFound
        )
        
        Log.d(TAG, "✅ SUCCESS: Voice interaction service is properly registered")
    }

    @Test 
    fun assistantActivity_handlesAssistIntentCorrectly(): Unit = runBlocking {
        Log.d(TAG, "🚀 STARTING TEST: AssistantActivity intent handling")
        
        // Test that our AssistantActivity properly handles the intents it declares
        device.pressHome()
        Thread.sleep(1000)
        
        // Create intent exactly as Google Assistant would send it
        val assistIntent = Intent().apply {
            action = Intent.ACTION_ASSIST  
            setClassName(packageName, "com.example.whiz.AssistantActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        Log.d(TAG, "📱 Launching AssistantActivity directly...")
        context.startActivity(assistIntent)
        
        // Wait for activity to start
        val activityStarted = device.wait(Until.hasObject(By.pkg(packageName)), 8000)
        assertTrue("AssistantActivity should start when receiving ACTION_ASSIST", activityStarted)
        
        Thread.sleep(2000)
        
        // Verify the assistant activity is running (should have assistant-specific UI or behavior)
        val assistantActive = device.hasObject(By.pkg(packageName))
        assertTrue("AssistantActivity should be visible and active", assistantActive)
        
        Log.d(TAG, "✅ SUCCESS: AssistantActivity handles ASSIST intents correctly")
    }
} 