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
                additionalPatterns = listOf("bubble test", "calculator test", "mode switch"),
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
                    additionalPatterns = listOf("bubble test", "calculator test", "mode switch"),
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
        
        // Step 1: Launch WhizVoice app normally
        Log.d(TAG, "📱 Step 1: Launching WhizVoice app...")
        val launchSuccess = launchAppAndWaitForLoad()
        if (!launchSuccess) {
            failWithScreenshot("Failed to launch WhizVoice app")
        }
        
        // For this test, we don't need to navigate to a new chat first
        // We'll just test the bubble mode switching functionality
        Log.d(TAG, "📝 Skipping new chat creation for bubble mode test...")
        
        // Step 2: Open Calculator app using ToolExecutor
        // This simulates what happens when the server sends a launch_app tool request
        Log.d(TAG, "🧮 Step 2: Opening Calculator app using ToolExecutor...")
        
        val toolRequest = JSONObject().apply {
            put("tool", "launch_app")
            put("request_id", "test_request_${System.currentTimeMillis()}")
            put("params", JSONObject().apply {
                put("app_name", "Calculator")
            })
        }
        
        Log.d(TAG, "Executing tool request: ${toolRequest.toString(2)}")
        toolExecutor.executeToolFromJson(toolRequest)
        
        // Wait for Calculator to open
        val calculatorOpened = device.wait(Until.hasObject(
            By.pkg("com.google.android.calculator")
        ), 5000)
        
        if (!calculatorOpened) {
            failWithScreenshot("Calculator app did not open")
        }
        
        Log.d(TAG, "✅ Calculator app opened successfully")
        
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
            Log.e(TAG, "❌ Bubble did NOT start automatically when switching to Calculator")
            Log.e(TAG, "Let's investigate why the bubble didn't start...")
            failWithScreenshot("Bubble did not start automatically when switching to Calculator app - need to investigate production code")
        }
        
        // Step 4: Verify bubble UI element is visible on Calculator screen
        Log.d(TAG, "🔵 Step 4: Verifying bubble UI element is visible...")
        
        val bubbleVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "chat_head")),
            1000
        )
        
        if (!bubbleVisible) {
            failWithScreenshot("Bubble UI element should be visible on Calculator screen but was not found")
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
        
        // Verify that VoiceManager is actually listening in CONTINUOUS_LISTENING mode
        if (!initialListeningState) {
            failWithScreenshot("VoiceManager should be actively listening in CONTINUOUS_LISTENING mode but isListening=$initialListeningState")
        }
        Log.d(TAG, "✅ VoiceManager is actively listening")
        
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
        performBubbleLongPress()
        
        // Wait for mode to actually change
        runBlocking {
            val modeStartTime = System.currentTimeMillis()
            val modeTimeoutMs = 2000L
            var modeChanged = false
            while (System.currentTimeMillis() - modeStartTime < modeTimeoutMs) {
                if (BubbleOverlayService.bubbleListeningMode != initialMode) {
                    modeChanged = true
                    break
                }
                delay(100)
            }
            if (!modeChanged) {
                failWithScreenshot("Mode should change after long press but remained at $initialMode")
            }
        }
        
        if (BubbleOverlayService.bubbleListeningMode != ListeningMode.MIC_OFF) {
            failWithScreenshot("Mode should be MIC_OFF after first cycle but is ${BubbleOverlayService.bubbleListeningMode}")
        }
        
        // Verify the mode indicator is still visible (showing mic-off icon in MIC_OFF mode)
        Log.d(TAG, "🔇 Verifying mode indicator is visible on bubble...")
        val micOffModeIndicatorVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "mode_indicator")),
            1000
        )
        
        if (!micOffModeIndicatorVisible) {
            failWithScreenshot("Mode indicator should be visible on bubble in MIC_OFF mode but was not found")
        }
        
        Log.d(TAG, "✅ Mode indicator is visible on bubble (showing mic-off icon for MIC_OFF)")
        
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
        
        // Verify the mode indicator is visible (showing speaker icon in TTS_WITH_LISTENING mode)
        Log.d(TAG, "🔊 Verifying mode indicator is visible on bubble...")
        val ttsModeIndicatorVisible = device.wait(
            Until.hasObject(By.res("com.example.whiz.debug", "mode_indicator")),
            1000
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
        
        // Microphone should be listening in TTS_WITH_LISTENING mode
        if (!ttsListeningState) {
            failWithScreenshot("VoiceManager should be actively listening in TTS_WITH_LISTENING mode but isListening=$ttsListeningState")
        }
        Log.d(TAG, "✅ VoiceManager is actively listening in TTS_WITH_LISTENING mode")
        
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
        
        // Verify listening is active and TTS is off after cycling back to CONTINUOUS_LISTENING
        val finalListeningState = voiceManager.isListening.value
        val finalTTSEnabled = voiceManager.shouldEnableTTS()
        val finalTTSSpeaking = ttsManager.isSpeaking.value
        
        Log.d(TAG, "Final state - Listening: $finalListeningState, TTS enabled: $finalTTSEnabled, TTS speaking: $finalTTSSpeaking")
        
        // Verify microphone is listening again
        if (!finalListeningState) {
            failWithScreenshot("VoiceManager should be actively listening after cycling back to CONTINUOUS_LISTENING but isListening=$finalListeningState")
        }
        Log.d(TAG, "✅ VoiceManager resumed listening in CONTINUOUS_LISTENING mode")
        
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
        // The bubble uses a CardView with id "chat_head" 
        // Since it's an overlay, we need to find it by its content or appearance
        
        // Try to find the bubble by looking for the overlay window
        // The bubble should be visible as a small circular element on screen
        val bubbleElement = device.findObject(By.res("com.example.whiz.debug", "chat_head"))
        
        if (bubbleElement == null) {
            failWithScreenshot("Bubble element should be found on screen but was null")
        }
        
        Log.d(TAG, "Found bubble element, performing long press")
        // Perform actual long press
        bubbleElement.longClick()
        
        // Mode change detection is handled by the caller
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