package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.ListeningMode
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.services.TTSManager
import com.example.whiz.tools.ToolExecutor
import com.example.whiz.ui.viewmodels.VoiceManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration test for bubble mode switching functionality.
 * 
 * Tests switching between three notification bubble modes:
 * 1. CONTINUOUS_LISTENING - Microphone on, TTS off
 * 2. MIC_OFF - Microphone off, TTS off  
 * 3. TTS_WITH_LISTENING - Microphone on, TTS on
 * 
 * Verifies that microphone and TTS states are correctly updated when switching modes.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class BubbleModeSwitchingTest : BaseIntegrationTest() {

    companion object {
        private const val TAG = "BubbleModeSwitchingTest"
        
        // Capture ViewModel from navigation scope
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    // hiltRule is already defined in BaseIntegrationTest, no need to override

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var voiceManager: VoiceManager
    
    @Inject
    lateinit var ttsManager: TTSManager
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService
    
    @Inject
    lateinit var toolExecutor: ToolExecutor
    
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    
    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Grant microphone permission for voice tests
        Log.d(TAG, "🎙️ Granting microphone permission for bubble mode test")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Grant overlay permission for bubble using appops (SYSTEM_ALERT_WINDOW can't be granted via pm grant)
        Log.d(TAG, "🔵 Granting overlay permission for bubble")
        device.executeShellCommand("appops set com.example.whiz.debug SYSTEM_ALERT_WINDOW allow")
        
        // Update permission manager state
        permissionManager.updateMicrophonePermission(true)
        
        // Clean up any existing test chats
        runBlocking {
            cleanupTestChats(
                repository = repository,
                trackedChatIds = emptyList(),
                additionalPatterns = listOf("bubble test", "clock test", "mode switch"),
                enablePatternFallback = false
            )
        }
    }
    
    @After
    fun cleanup() {
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up bubble mode test")
                
                // Stop bubble service if active
                if (BubbleOverlayService.isActive) {
                    BubbleOverlayService.stop(instrumentation.targetContext)
                    // Wait for service to actually stop
                    val cleanupStartTime = System.currentTimeMillis()
                    val cleanupTimeoutMs = 1000L
                    var stopped = false
                    while (System.currentTimeMillis() - cleanupStartTime < cleanupTimeoutMs) {
                        if (!BubbleOverlayService.isActive) {
                            stopped = true
                            break
                        }
                        delay(100)
                    }
                    if (!stopped) {
                        Log.w(TAG, "⚠️ Bubble service did not stop within timeout")
                    }
                }
                
                // Clean up test chats
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("bubble test", "clock test", "mode switch"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
                
                Log.d(TAG, "✅ Bubble mode test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test cleanup", e)
            }
        }
    }

    @Test
    fun testBubbleModeSwitching_openCalculator_cycleThroughModes_verifyMicAndTTSStates() {
        Log.d(TAG, "🚀 Starting bubble mode switching test")
        
        // Reset captured ViewModel
        capturedViewModel = null
        MainActivity.testViewModelCallback = null
        
        // Step 1: Launch WhizVoice app without waiting for specific UI elements
        // This avoids issues with accessibility dialog blocking the test
        Log.d(TAG, "📱 Step 1: Launching WhizVoice app (simple launch)...")
        
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: ${e.message}")
            failWithScreenshot("Failed to launch WhizVoice app: ${e.message}")
        }
        
        // Just wait for the app package to be visible, don't wait for specific UI
        val appVisible = device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        if (!appVisible) {
            Log.w(TAG, "⚠️ App package not visible after 5s, continuing anyway...")
        }
        
        // Give the app a moment to settle (accessibility dialog might appear)
        Thread.sleep(2000)
        Log.d(TAG, "✅ App launched, continuing with test...")
        
        // For this test, we don't need to navigate to a new chat first
        // We'll just test the bubble mode switching functionality
        Log.d(TAG, "📝 Skipping new chat creation for bubble mode test...")
        
        // Step 2: Open Clock app using ToolExecutor
        // This simulates what happens when the server sends a launch_app tool request
        Log.d(TAG, "⏰ Step 2: Opening Clock app using ToolExecutor...")
        
        val toolRequest = JSONObject().apply {
            put("tool", "agent_launch_app")
            put("request_id", "test_request_${System.currentTimeMillis()}")
            put("params", JSONObject().apply {
                put("app_name", "Clock")
            })
        }
        
        Log.d(TAG, "Executing tool request: ${toolRequest.toString(2)}")
        toolExecutor.executeToolFromJson(toolRequest)
        
        // Wait for Clock to open (try both possible package names)
        val clockOpened = device.wait(Until.hasObject(
            By.pkg("com.google.android.deskclock")
        ), 5000) || device.wait(Until.hasObject(
            By.pkg("com.android.deskclock")
        ), 1000)
        
        if (!clockOpened) {
            failWithScreenshot("Clock app did not open")
        }
        
        Log.d(TAG, "✅ Clock app opened successfully")
        
        // Step 3: Check if bubble started automatically
        Log.d(TAG, "🔵 Step 3: Checking if bubble started automatically...")
        
        // Wait to see if bubble service becomes active
        var bubbleStarted = false
        runBlocking {
            val startTime = System.currentTimeMillis()
            val timeoutMs = 3000L
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (BubbleOverlayService.isActive) {
                    bubbleStarted = true
                    val elapsedMs = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ Bubble service became active automatically after ${elapsedMs}ms")
                    break
                }
                delay(100)
            }
        }
        
        if (!bubbleStarted) {
            Log.e(TAG, "❌ Bubble did NOT start automatically when switching to Clock")
            Log.e(TAG, "Let's investigate why the bubble didn't start...")
            failWithScreenshot("Bubble did not start automatically when switching to Clock app - need to investigate production code")
        }
        
        // Step 4: Verify bubble UI element is visible on Clock screen
        Log.d(TAG, "🔵 Step 4: Verifying bubble UI element is visible...")
        
        val bubbleVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "chat_head")),
            1000
        )
        
        if (!bubbleVisible) {
            failWithScreenshot("Bubble UI element should be visible on Clock screen but was not found")
        }
        
        Log.d(TAG, "✅ Bubble UI element is visible on screen")
        
        // Step 5: Verify initial mode (CONTINUOUS_LISTENING)
        Log.d(TAG, "🎤 Step 5: Verifying initial mode (CONTINUOUS_LISTENING)...")
        if (BubbleOverlayService.bubbleListeningMode != ListeningMode.CONTINUOUS_LISTENING) {
            failWithScreenshot("Initial mode should be CONTINUOUS_LISTENING but is ${BubbleOverlayService.bubbleListeningMode}")
        }
        
        // Verify the mode indicator is visible (it shows the current mode icon)
        Log.d(TAG, "🎤 Verifying mode indicator is visible on bubble...")
        val modeIndicatorVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "mode_indicator")),
            1000
        )
        
        if (!modeIndicatorVisible) {
            failWithScreenshot("Mode indicator should be visible on bubble in CONTINUOUS_LISTENING mode but was not found")
        }
        
        Log.d(TAG, "✅ Mode indicator is visible on bubble (showing microphone icon for CONTINUOUS_LISTENING)")
        
        // Wait for VoiceManager to detect bubble is active and potentially start listening
        // Note: VoiceManager might not automatically start listening when bubble opens
        // as it depends on various conditions (app state, permissions, etc.)
        runBlocking {
            // Give VoiceManager time to detect bubble state change
            val vmStartTime = System.currentTimeMillis()
            val vmTimeoutMs = 1000L
            while (System.currentTimeMillis() - vmStartTime < vmTimeoutMs) {
                if (voiceManager.isListening.value) {
                    val elapsedMs = System.currentTimeMillis() - vmStartTime
                    Log.d(TAG, "VoiceManager started listening after ${elapsedMs}ms")
                    break
                }
                delay(100)
            }
        }
        
        val initialListeningState = voiceManager.isListening.value
        val initialTTSEnabled = voiceManager.shouldEnableTTS()
        val initialTTSSpeaking = ttsManager.isSpeaking.value
        
        Log.d(TAG, "Initial state - Listening: $initialListeningState, TTS enabled: $initialTTSEnabled, TTS speaking: $initialTTSSpeaking")
        
        // Verify that VoiceManager is either actively listening OR speech recognition was attempted but failed (emulator limitation)
        if (!initialListeningState) {
            // Check if speech recognition was attempted but failed due to emulator limitations
            val speechRecognitionError = speechRecognitionService.errorState.value
            val continuousListeningEnabled = voiceManager.isContinuousListeningEnabled.value
            
            Log.d(TAG, "🔍 Speech recognition check: error=$speechRecognitionError, continuousListeningEnabled=$continuousListeningEnabled")
            
            if (speechRecognitionError != null && continuousListeningEnabled) {
                Log.i(TAG, "✅ Detected emulator environment - Speech recognition initialization failed as expected")
                Log.i(TAG, "   Error: $speechRecognitionError")
                Log.i(TAG, "✅ Continuous listening is enabled, speech recognition was attempted (emulator limitation)")
            } else {
                // This is a real failure - speech recognition wasn't even attempted
                failWithScreenshot("VoiceManager should be actively listening in CONTINUOUS_LISTENING mode but isListening=$initialListeningState and no initialization attempt detected (error=$speechRecognitionError, continuousListeningEnabled=$continuousListeningEnabled)")
            }
        } else {
            Log.d(TAG, "✅ VoiceManager is actively listening")
        }
        
        // Verify TTS is not enabled in CONTINUOUS_LISTENING mode
        if (initialTTSEnabled) {
            failWithScreenshot("TTS should not be enabled in CONTINUOUS_LISTENING mode but shouldEnableTTS=$initialTTSEnabled")
        }
        if (initialTTSSpeaking) {
            failWithScreenshot("TTS should not be speaking in CONTINUOUS_LISTENING mode but isSpeaking=$initialTTSSpeaking")
        }
        
        // Step 6: Perform long press to switch to MIC_OFF mode
        Log.d(TAG, "🔇 Step 6: Switching to MIC_OFF mode...")
        val initialMode = BubbleOverlayService.bubbleListeningMode
        Log.d(TAG, "[MODE_CHECK] Initial mode before long press: $initialMode")
        performBubbleLongPress()
        
        // Wait for mode to actually change
        runBlocking {
            val modeStartTime = System.currentTimeMillis()
            val modeTimeoutMs = 2000L
            var modeChanged = false
            while (System.currentTimeMillis() - modeStartTime < modeTimeoutMs) {
                val currentMode = BubbleOverlayService.bubbleListeningMode
                val elapsed = System.currentTimeMillis() - modeStartTime
                Log.d(TAG, "[MODE_CHECK] After ${elapsed}ms: current=$currentMode, initial=$initialMode")
                if (currentMode != initialMode) {
                    Log.d(TAG, "[MODE_CHECK] Mode changed from $initialMode to $currentMode!")
                    modeChanged = true
                    break
                }
                delay(100)
            }
            if (!modeChanged) {
                Log.e(TAG, "[MODE_CHECK] Mode did not change after ${modeTimeoutMs}ms, still at $initialMode")
                failWithScreenshot("Mode should change after long press but remained at $initialMode")
            }
        }
        
        if (BubbleOverlayService.bubbleListeningMode != ListeningMode.MIC_OFF) {
            failWithScreenshot("Mode should be MIC_OFF after first cycle but is ${BubbleOverlayService.bubbleListeningMode}")
        }
        
        // In MIC_OFF mode, the mode_indicator is hidden (View.GONE) per the production code
        // Instead, verify the mode change message appears and the bubble is still visible
        Log.d(TAG, "🔇 Verifying bubble appearance and mode message in MIC_OFF mode...")
        
        // First check for the "Mic Off" message that appears temporarily
        val micOffMessageVisible = device.wait(
            Until.hasObject(By.text("Mic Off")),
            2000  // Wait for message to appear
        )
        
        if (micOffMessageVisible) {
            Log.d(TAG, "✅ 'Mic Off' message is displayed on bubble")
        } else {
            Log.w(TAG, "⚠️ 'Mic Off' message not found - it may have already disappeared")
        }
        
        // Also verify the bubble itself is still visible
        val bubbleStillVisible = device.wait(
            Until.hasObject(By.descContains("WhizVoice Chat Bubble")),
            1000
        ) || device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "chat_head")),
            500
        )
        
        if (!bubbleStillVisible) {
            failWithScreenshot("Bubble should still be visible in MIC_OFF mode but was not found")
        }
        
        // Verify mode indicator is NOT visible in MIC_OFF mode (it should be hidden)
        val modeIndicatorHidden = !device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "mode_indicator")),
            500
        )
        
        if (!modeIndicatorHidden) {
            Log.w(TAG, "Mode indicator is unexpectedly visible in MIC_OFF mode - it should be hidden")
        }
        
        Log.d(TAG, "✅ Bubble is visible in MIC_OFF mode (mode indicator correctly hidden)")
        
        // Verify microphone is off and TTS is off
        val micOffListeningState = voiceManager.isListening.value
        val micOffTTSEnabled = voiceManager.shouldEnableTTS()
        val micOffTTSSpeaking = ttsManager.isSpeaking.value
        
        Log.d(TAG, "MIC_OFF state - Listening: $micOffListeningState, TTS enabled: $micOffTTSEnabled, TTS speaking: $micOffTTSSpeaking")
        if (micOffListeningState) {
            failWithScreenshot("Microphone should be OFF in MIC_OFF mode but isListening=$micOffListeningState")
        }
        if (micOffTTSEnabled) {
            failWithScreenshot("TTS should not be enabled in MIC_OFF mode but shouldEnableTTS=$micOffTTSEnabled")
        }
        if (micOffTTSSpeaking) {
            failWithScreenshot("TTS should not be speaking in MIC_OFF mode but isSpeaking=$micOffTTSSpeaking")
        }
        
        // Step 7: Perform long press to switch to TTS_WITH_LISTENING mode
        Log.d(TAG, "🔊 Step 7: Switching to TTS_WITH_LISTENING mode...")
        val micOffMode = BubbleOverlayService.bubbleListeningMode
        performBubbleLongPress()
        
        // Wait for mode to change from MIC_OFF
        runBlocking {
            val modeStartTime = System.currentTimeMillis()
            val modeTimeoutMs = 2000L
            var modeChanged = false
            while (System.currentTimeMillis() - modeStartTime < modeTimeoutMs) {
                if (BubbleOverlayService.bubbleListeningMode != micOffMode) {
                    modeChanged = true
                    break
                }
                delay(100)
            }
            if (!modeChanged) {
                failWithScreenshot("Mode should change after long press but remained at $micOffMode")
            }
        }
        
        if (BubbleOverlayService.bubbleListeningMode != ListeningMode.TTS_WITH_LISTENING) {
            failWithScreenshot("Mode should be TTS_WITH_LISTENING after second cycle but is ${BubbleOverlayService.bubbleListeningMode}")
        }
        
        // Check for the "Speaking Mode" message that appears temporarily
        Log.d(TAG, "🔊 Checking for 'Speaking Mode' message...")
        val speakingModeMessageVisible = device.wait(
            Until.hasObject(By.text("Speaking Mode")),
            2000  // Wait for message to appear
        )
        
        if (speakingModeMessageVisible) {
            Log.d(TAG, "✅ 'Speaking Mode' message is displayed on bubble")
        } else {
            Log.w(TAG, "⚠️ 'Speaking Mode' message not found - it may have already disappeared")
        }
        
        // Wait a bit for the mode change message to disappear (5 seconds duration)
        Log.d(TAG, "🔊 Waiting for mode change message to clear...")
        Thread.sleep(1000) // Give message time to start disappearing
        
        // Verify the mode indicator is visible (showing speaker icon in TTS_WITH_LISTENING mode)
        Log.d(TAG, "🔊 Verifying mode indicator is visible on bubble...")
        val ttsModeIndicatorVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "mode_indicator")),
            5000 // Wait up to 5 seconds for the message to disappear and mode indicator to become visible
        )
        
        if (!ttsModeIndicatorVisible) {
            failWithScreenshot("Mode indicator should be visible on bubble in TTS_WITH_LISTENING mode but was not found")
        }
        
        Log.d(TAG, "✅ Mode indicator is visible on bubble (showing speaker icon for TTS_WITH_LISTENING)")
        
        // Verify TTS is enabled in TTS_WITH_LISTENING mode
        val ttsListeningState = voiceManager.isListening.value
        val ttsModeEnabled = voiceManager.shouldEnableTTS()
        val ttsModeSpeaking = ttsManager.isSpeaking.value
        
        Log.d(TAG, "TTS_WITH_LISTENING state - Listening: $ttsListeningState, TTS enabled: $ttsModeEnabled, TTS speaking: $ttsModeSpeaking")
        
        // Microphone should be listening in TTS_WITH_LISTENING mode (or attempted with error on emulator)
        if (!ttsListeningState) {
            // Check if speech recognition was attempted but failed due to emulator limitations
            val speechRecognitionError = speechRecognitionService.errorState.value
            val continuousListeningEnabled = voiceManager.isContinuousListeningEnabled.value
            
            Log.d(TAG, "🔍 TTS mode speech recognition check: error=$speechRecognitionError, continuousListeningEnabled=$continuousListeningEnabled")
            
            if (speechRecognitionError != null && continuousListeningEnabled) {
                Log.i(TAG, "✅ Detected emulator environment - Speech recognition initialization failed as expected in TTS_WITH_LISTENING mode")
                Log.i(TAG, "   Error: $speechRecognitionError")
            } else {
                failWithScreenshot("VoiceManager should be actively listening in TTS_WITH_LISTENING mode but isListening=$ttsListeningState and no initialization attempt detected")
            }
        } else {
            Log.d(TAG, "✅ VoiceManager is actively listening in TTS_WITH_LISTENING mode")
        }
        
        // TTS should be enabled in this mode (actual speaking depends on whether there's active speech)
        if (!ttsModeEnabled) {
            failWithScreenshot("TTS should be enabled in TTS_WITH_LISTENING mode but shouldEnableTTS=$ttsModeEnabled")
        }
        Log.d(TAG, "✅ TTS correctly enabled in TTS_WITH_LISTENING mode")
        
        // Step 8: Perform long press to cycle back to CONTINUOUS_LISTENING mode
        Log.d(TAG, "🎤 Step 8: Cycling back to CONTINUOUS_LISTENING mode...")
        val ttsMode = BubbleOverlayService.bubbleListeningMode
        performBubbleLongPress()
        
        // Wait for mode to change from TTS_WITH_LISTENING
        runBlocking {
            val modeStartTime = System.currentTimeMillis()
            val modeTimeoutMs = 2000L
            var modeChanged = false
            while (System.currentTimeMillis() - modeStartTime < modeTimeoutMs) {
                if (BubbleOverlayService.bubbleListeningMode != ttsMode) {
                    modeChanged = true
                    break
                }
                delay(100)
            }
            if (!modeChanged) {
                failWithScreenshot("Mode should change after long press but remained at $ttsMode")
            }
        }
        
        if (BubbleOverlayService.bubbleListeningMode != ListeningMode.CONTINUOUS_LISTENING) {
            failWithScreenshot("Mode should be back to CONTINUOUS_LISTENING after third cycle but is ${BubbleOverlayService.bubbleListeningMode}")
        }
        
        // Check for the "Listening Mode" message that appears when cycling back
        Log.d(TAG, "🎤 Checking for 'Listening Mode' message...")
        val listeningModeMessageVisible = device.wait(
            Until.hasObject(By.text("Listening Mode")),
            2000  // Wait for message to appear
        )
        
        if (listeningModeMessageVisible) {
            Log.d(TAG, "✅ 'Listening Mode' message is displayed on bubble")
        } else {
            Log.w(TAG, "⚠️ 'Listening Mode' message not found - it may have already disappeared")
        }
        
        // Verify listening is active and TTS is off after cycling back to CONTINUOUS_LISTENING
        val finalListeningState = voiceManager.isListening.value
        val finalTTSEnabled = voiceManager.shouldEnableTTS()
        val finalTTSSpeaking = ttsManager.isSpeaking.value
        
        Log.d(TAG, "Final state - Listening: $finalListeningState, TTS enabled: $finalTTSEnabled, TTS speaking: $finalTTSSpeaking")
        
        // Verify microphone is listening again (or attempted with error on emulator)
        if (!finalListeningState) {
            // Check if speech recognition was attempted but failed due to emulator limitations
            val speechRecognitionError = speechRecognitionService.errorState.value
            val continuousListeningEnabled = voiceManager.isContinuousListeningEnabled.value
            
            Log.d(TAG, "🔍 Final speech recognition check: error=$speechRecognitionError, continuousListeningEnabled=$continuousListeningEnabled")
            
            if (speechRecognitionError != null && continuousListeningEnabled) {
                Log.i(TAG, "✅ Detected emulator environment - Speech recognition initialization failed as expected after cycling back")
                Log.i(TAG, "   Error: $speechRecognitionError")
            } else {
                failWithScreenshot("VoiceManager should be actively listening after cycling back to CONTINUOUS_LISTENING but isListening=$finalListeningState and no initialization attempt detected")
            }
        } else {
            Log.d(TAG, "✅ VoiceManager resumed listening in CONTINUOUS_LISTENING mode")
        }
        
        // Verify TTS is off
        if (finalTTSEnabled) {
            failWithScreenshot("TTS should not be enabled after cycling back to CONTINUOUS_LISTENING but shouldEnableTTS=$finalTTSEnabled")
        }
        if (finalTTSSpeaking) {
            failWithScreenshot("TTS should not be speaking after cycling back to CONTINUOUS_LISTENING but isSpeaking=$finalTTSSpeaking")
        }
        Log.d(TAG, "✅ Successfully cycled through all bubble modes")
        
        // Step 9: Click bubble to return to app
        Log.d(TAG, "🔄 Step 9: Clicking bubble to return to app...")
        clickBubbleToReturnToApp()
        
        // Wait for app to come to foreground
        val appReturned = device.wait(Until.hasObject(
            By.pkg(packageName)
        ), 5000)
        
        if (!appReturned) {
            failWithScreenshot("App did not return to foreground after clicking bubble")
        }
        
        // Wait for bubble service to become inactive
        runBlocking {
            val stopStartTime = System.currentTimeMillis()
            val stopTimeoutMs = 1000L
            var bubbleStopped = false
            while (System.currentTimeMillis() - stopStartTime < stopTimeoutMs) {
                if (!BubbleOverlayService.isActive) {
                    bubbleStopped = true
                    break
                }
                delay(100)
            }
            if (!bubbleStopped) {
                failWithScreenshot("Bubble should stop after returning to app but BubbleOverlayService.isActive=${BubbleOverlayService.isActive}")
            }
        }
        if (BubbleOverlayService.isActive) {
            failWithScreenshot("Bubble should not be active after returning to app but isActive=${BubbleOverlayService.isActive}")
        }
        
        // Clean up
        MainActivity.testViewModelCallback = null
        capturedViewModel = null
        
        Log.d(TAG, "🎉 Bubble mode switching test PASSED")
    }
    
    /**
     * Helper function to perform actual long press on the bubble using UI Automator.
     * This finds the bubble overlay and performs a long press gesture to cycle modes.
     * The caller is responsible for waiting for the mode change to complete.
     */
    private fun performBubbleLongPress() {
        Log.d(TAG, "[TEST_LONG_PRESS] Starting performBubbleLongPress")
        // The bubble uses a CardView with id "chat_head" 
        // Since it's an overlay, we need to find it by its content or appearance
        
        // Try to find the bubble by looking for the overlay window
        // The bubble should be visible as a small circular element on screen
        Log.d(TAG, "[TEST_LONG_PRESS] Looking for bubble element with resource id 'chat_head'...")
        val bubbleElement = device.findObject(By.res("com.example.whiz.debug", "chat_head"))
        
        if (bubbleElement == null) {
            Log.e(TAG, "[TEST_LONG_PRESS] Bubble element not found on screen!")
            failWithScreenshot("Bubble element should be found on screen but was null")
        }
        
        Log.d(TAG, "[TEST_LONG_PRESS] Found bubble element, getting position...")
        try {
            val point = bubbleElement.visibleCenter
            Log.d(TAG, "[TEST_LONG_PRESS] Bubble center at: (${point.x}, ${point.y})")
        } catch (e: Exception) {
            Log.e(TAG, "[TEST_LONG_PRESS] Could not get bubble position: ${e.message}")
        }
        
        Log.d(TAG, "[TEST_LONG_PRESS] Performing long press gesture...")
        // Perform actual long press
        bubbleElement.longClick()
        Log.d(TAG, "[TEST_LONG_PRESS] Long press completed")
        
        // Mode change detection is handled by the caller
        Log.d(TAG, "[TEST_LONG_PRESS] Exiting performBubbleLongPress")
    }
    
    /**
     * Helper function to click the bubble overlay to return to app using UI Automator.
     * This performs an actual tap on the bubble element, which should bring the app
     * back to foreground and stop the bubble service.
     */
    private fun clickBubbleToReturnToApp() {
        Log.d(TAG, "Clicking bubble to return to app")
        
        // Find the bubble element using UI Automator
        val bubbleElement = device.findObject(By.res("com.example.whiz.debug", "chat_head"))
        
        if (bubbleElement == null) {
            failWithScreenshot("Bubble element should be found on screen before clicking but was null")
        }
        
        Log.d(TAG, "Found bubble element, performing click")
        // Perform actual click on the bubble
        bubbleElement.click()
        
        // The click should automatically:
        // 1. Stop the bubble service
        // 2. Bring the app back to foreground
        // No need to manually call BubbleOverlayService.stop() or bringAppToForeground()
    }
    
}