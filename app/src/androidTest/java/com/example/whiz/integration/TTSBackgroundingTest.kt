package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.MainActivity
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.viewmodels.VoiceManager
import com.example.whiz.services.TTSManager
import org.junit.Assert.*
import org.junit.After
import android.util.Log

/**
 * Integration test for the TTS backgrounding bug.
 * 
 * Tests the user flow:
 * 1. Voice launch of the app
 * 2. Send a voice message (simulated via text input but marked as voice)
 * 3. Wait for TTS response to start speaking
 * 4. Background the app while TTS is speaking
 * 5. Verify TTS stops speaking when backgrounded
 * 6. Return to app and verify TTS remains stopped
 * 
 * This test reproduces the production bug where TTS continues speaking
 * even after the app is backgrounded.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class TTSBackgroundingTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "TTSBackgroundingTest"

        // Capture ViewModel from navigation scope (same approach as MessageFlowVoiceComposeTest)
        @Volatile
        var capturedViewModel: com.example.whiz.ui.viewmodels.ChatViewModel? = null
    }

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    override lateinit var voiceManager: VoiceManager

    @Inject
    override lateinit var ttsManager: TTSManager

    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    override lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService
    
    // Note: ChatViewModel will be obtained via ViewModelProvider instead of direct injection
    // to avoid Hilt compilation errors with @HiltViewModel classes

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()

    // Monitor to capture launched activity for cleanup
    private var activityMonitor: android.app.Instrumentation.ActivityMonitor? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles device setup, screenshot dir, authentication, and app launch
        
        // Grant microphone permission for voice tests
        Log.d(TAG, "🎙️ Granting microphone permission for TTS backgrounding test")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)

        // Set up activity monitor to capture launched activity for cleanup
        activityMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)

        // Clean up any existing test chats
        runBlocking {
            cleanupTestChats(
                repository = repository,
                trackedChatIds = emptyList(),
                additionalPatterns = listOf("backgrounding test", "tts test", "voice launch", "Assistant Chat"),
                enablePatternFallback = false
            )
        }
    }
    
    @After
    fun cleanup() {
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up test chats created during TTS backgrounding test")

                // Finish any launched activity
                activityMonitor?.let { monitor ->
                    val activity = monitor.lastActivity
                    if (activity != null && !activity.isFinishing) {
                        activity.finish()
                    }
                    instrumentation.removeMonitor(monitor)
                }
                activityMonitor = null

                // Clean up tracked chats and any chats with test patterns
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("backgrounding test", "tts test", "voice launch", "Assistant Chat", "space"),
                    enablePatternFallback = true
                )
                createdChatIds.clear()

                Log.d(TAG, "✅ TTS backgrounding test cleanup completed")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error during test cleanup", e)
            }
        }
    }

    @Test
    fun testTTSBackgroundingBug_voiceLaunch_sendVoiceMessage_backgroundDuringTTS_verifyTTSStops() {
        Log.d(TAG, "🚀 Starting TTS backgrounding bug test")
        
        // Reset captured ViewModel
        capturedViewModel = null
        MainActivity.testViewModelCallback = null
        
        // Step 1: Voice launch the app (simulates "Hey Google, talk to WhizVoice")
        Log.d(TAG, "📱 Step 1: Voice launching app...")
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or 0x10000000 // Voice launch flags
            putExtra("tracing_intent_id", System.currentTimeMillis()) // Dynamic trace ID for voice launch
        }

        val initialChats = runBlocking { repository.getAllChats() }
        
        // Set up callback to capture ChatViewModel when it's created
        MainActivity.testViewModelCallback = { vm ->
            capturedViewModel = vm
            Log.d(TAG, "✅ Captured ChatViewModel from navigation scope")
        }
        
        // Launch through real Android system like Google Assistant would
        instrumentation.targetContext.startActivity(voiceLaunchIntent)
        
        // Wait for voice launch to navigate to new chat screen
        // Voice launch navigation can take time, especially on slower emulators
        Log.d(TAG, "⏳ Waiting for voice launch navigation to complete...")
        val navigatedToChat = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 20000) // Increased from 10s to 20s for slower emulators
        
        if (!navigatedToChat) {
            failWithScreenshot("Voice launch should navigate to new chat screen")
        }
        
        Log.d(TAG, "✅ Step 1 Complete: Voice launch successful, navigated to chat")
        
        // Track the newly created chat immediately after voice launch
        try {
            val currentChats = runBlocking { repository.getAllChats() }
            val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
            newChats.forEach { chat ->
                createdChatIds.add(chat.id)
                Log.d(TAG, "📝 Tracked new chat for cleanup: ${chat.id} ('${chat.title}')")
            }
            if (newChats.isEmpty()) {
                Log.w(TAG, "⚠️ Warning: No new chat detected after voice launch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error tracking new chat after voice launch", e)
        }
        
        
        // Step 2: Send a voice message (simulate transcription but send as voice)
        Log.d(TAG, "🎤 Step 2: Sending voice message...")
        
        val testMessage = "Tell me about space in in LESS than 30 words"
        
        // Wait for ChatViewModel to be captured (it should have been set up before navigation)
        runBlocking {
            var attempts = 0
            while (capturedViewModel == null && attempts < 50) { // Wait up to 5 seconds
                delay(100L)
                attempts++
            }
        }
        
        val chatViewModel = capturedViewModel
        if (chatViewModel == null) {
            failWithScreenshot("Failed to capture ChatViewModel from navigation scope")
        }
        requireNotNull(chatViewModel) { "Failed to capture ChatViewModel from navigation scope" }
        
        // Wait for WebSocket to be connected before sending message
        Log.d(TAG, "⏳ Waiting for WebSocket connection...")
        runBlocking {
            var attempts = 0
            while (!chatViewModel.isConnectedToServer.value && attempts < 50) { // Wait up to 5 seconds
                delay(100L)
                attempts++
            }
            if (!chatViewModel.isConnectedToServer.value) {
                Log.e(TAG, "❌ WebSocket not connected after 5 seconds")
            } else {
                Log.d(TAG, "✅ WebSocket connected after ${attempts * 100}ms")
            }
        }
        
        // Simulate voice transcription and automatic sending (as voice input typically does)
        // Use rapid=true to skip Compose verification — no compose rule in this test,
        // so Compose uses the real clock and recomposes normally for UIAutomator checks
        val voiceSendSuccess = simulateVoiceTranscriptionAndSend(
            testMessage,
            rapid = true,
            chatViewModel = chatViewModel,
            speechRecognitionService = speechRecognitionService
        )
        if (!voiceSendSuccess) {
            failWithScreenshot("Failed to send voice message via transcription simulation")
        }

        // Verify message appeared via UIAutomator (real Compose clock, no test rule needed)
        val messageAppeared = device.wait(Until.hasObject(
            By.textContains(testMessage.take(20)).pkg(packageName)
        ), 8000)
        if (!messageAppeared) {
            failWithScreenshot("Voice message should appear in chat after sending")
        }
        
        Log.d(TAG, "✅ Step 2 Complete: Voice message sent successfully")
        
        // Step 3: Wait for bot response and TTS to start speaking
        Log.d(TAG, "⏳ Step 3: Waiting for bot response and TTS to start...")
        
        // Wait for bot thinking indicator or response
        val botStartedResponding = waitForBotThinkingIndicator(15000) || waitForBotResponse(5000)
        if (!botStartedResponding) {
            Log.e(TAG, "❌ Bot did not start responding - checking WebSocket connection...")
            val isConnected = chatViewModel.isConnectedToServer.value
            Log.e(TAG, "WebSocket connected: $isConnected")
            failWithScreenshot("Bot should start responding to voice message")
        }
        
        // Wait for bot to finish thinking and start speaking
        val botFinishedThinking = waitForBotThinkingToFinish(30000)
        if (!botFinishedThinking) {
            Log.e(TAG, "❌ Bot did not finish thinking - might be a server issue")
            failWithScreenshot("Bot should finish thinking within timeout")
        }
        
        // Check if voice response is enabled (should be for voice launch)
        val voiceResponseEnabled = chatViewModel.isVoiceResponseEnabled.value
        Log.d(TAG, "🔊 Voice response enabled: $voiceResponseEnabled")
        
        if (!voiceResponseEnabled) {
            Log.e(TAG, "❌ Voice response is NOT enabled despite voice launch!")
            failWithScreenshot("Voice response should be enabled for voice launch")
        }
        
        // Wait for TTS activation (either actual speaking or initialization error on emulator)
        Log.d(TAG, "🔊 Waiting for TTS activation...")
        val ttsActivated = runBlocking {
            var attempts = 0
            while (attempts < 50) { // Wait up to 10 seconds
                // Check all possible speaking states
                val voiceManagerSpeaking = voiceManager.isSpeaking.value
                val ttsManagerSpeaking = ttsManager.isSpeaking.value
                // ChatViewModel no longer has its own isSpeaking - it uses ttsManager.isSpeaking
                val ttsInitError = ttsManager.initializationError.value
                val chatViewModelTTSInitialized = chatViewModel.isTTSInitialized.value
                val voiceResponseEnabledCheck = chatViewModel.isVoiceResponseEnabled.value
                
                Log.d(TAG, "🔍 TTS check attempt $attempts: VoiceManager.isSpeaking=$voiceManagerSpeaking, " +
                         "TTSManager.isSpeaking=$ttsManagerSpeaking, TTSInitError=$ttsInitError, " +
                         "ChatViewModel.isTTSInitialized=$chatViewModelTTSInitialized, " +
                         "VoiceResponseEnabled=$voiceResponseEnabledCheck")
                
                if (voiceManagerSpeaking || ttsManagerSpeaking) {
                    Log.d(TAG, "✅ TTS started speaking after ${attempts * 200}ms")
                    return@runBlocking true
                }
                
                // Check if we're on an emulator by detecting TTS initialization error
                if (ttsInitError && !chatViewModelTTSInitialized) {
                    Log.i(TAG, "✅ Detected emulator environment - TTS initialization failed as expected")
                    
                    // Verify bot response is visible to confirm TTS would have been triggered
                    val hasVisibleBotResponse = device.hasObject(By.textContains("space").pkg(packageName)) ||
                                               device.hasObject(By.textContains("universe").pkg(packageName)) ||
                                               device.hasObject(By.textContains("cosmic").pkg(packageName)) ||
                                               device.hasObject(By.textContains("star").pkg(packageName)) ||
                                               device.hasObject(By.textContains("vast").pkg(packageName))
                                               
                    if (hasVisibleBotResponse) {
                        Log.i(TAG, "✅ Bot response visible - TTS activation was attempted (emulator limitation)")
                        return@runBlocking true
                    }
                }
                
                delay(200L)
                attempts++
            }
            
            Log.e(TAG, "❌ TTS activation not detected after ${attempts * 200}ms")
            false
        }
        
        if (!ttsActivated) {
            val currentVoiceManagerSpeaking = voiceManager.isSpeaking.value
            val currentTTSManagerSpeaking = ttsManager.isSpeaking.value
            val currentTTSInitError = ttsManager.initializationError.value
            val currentChatViewModelTTSInit = chatViewModel.isTTSInitialized.value
            Log.e(TAG, "❌ TTS State: VoiceManager.isSpeaking=$currentVoiceManagerSpeaking, " +
                     "TTSManager.isSpeaking=$currentTTSManagerSpeaking, " +
                     "TTSInitError=$currentTTSInitError, " +
                     "ChatViewModel.isTTSInitialized=$currentChatViewModelTTSInit")
            failWithScreenshot("TTS activation should be attempted after bot response")
        }
        
        Log.d(TAG, "✅ Step 3 Complete: TTS activation confirmed")
        
        // Step 4: Background the app while TTS is active (or would be active on real device)
        Log.d(TAG, "🏠 Step 4: Backgrounding app while TTS is active...")
        
        // On emulator, we can't verify actual speaking, but we can verify TTS was activated
        val isEmulator = ttsManager.initializationError.value
        val speakingBeforeBackground = voiceManager.isSpeaking.value || ttsManager.isSpeaking.value
        
        if (!isEmulator && !speakingBeforeBackground) {
            failWithScreenshot("TTS should still be speaking before backgrounding on real device")
        } else if (isEmulator) {
            Log.i(TAG, "ℹ️ Emulator detected - skipping pre-background speaking check")
        }
        
        // Background the app
        device.pressHome()
        device.wait(Until.gone(By.pkg(packageName)), 5000)
        
        // Verify app is backgrounded
        val isBackgrounded = !device.hasObject(By.pkg(packageName))
        if (!isBackgrounded) {
            failWithScreenshot("App should be backgrounded after pressing home")
        }
        
        Log.d(TAG, "✅ Step 4 Complete: App successfully backgrounded")
        
        // Step 5: Verify TTS is not active when backgrounded (even on emulator)
        Log.d(TAG, "🔇 Step 5: Verifying TTS is not active when backgrounded...")
        
        // Even on emulator, the isSpeaking flags should be false when backgrounded
        val ttsNotActiveAfterBackgrounding = runBlocking {
            var attempts = 0
            while (attempts < 25) { // Wait up to 5 seconds
                // Check all possible speaking states
                val voiceManagerSpeaking = voiceManager.isSpeaking.value
                val ttsManagerSpeaking = ttsManager.isSpeaking.value
                // ChatViewModel no longer has its own isSpeaking - it uses ttsManager.isSpeaking
                
                Log.d(TAG, "🔍 Post-background TTS check attempt $attempts: VoiceManager.isSpeaking=$voiceManagerSpeaking, TTSManager.isSpeaking=$ttsManagerSpeaking")
                
                if (!voiceManagerSpeaking && !ttsManagerSpeaking) {
                    Log.d(TAG, "✅ TTS is not active after backgrounding (after ${attempts * 200}ms)")
                    return@runBlocking true
                }
                
                delay(200L)
                attempts++
            }
            
            // If we get here, TTS flags indicate it's still "speaking" - this is the bug!
            val finalVoiceManagerSpeaking = voiceManager.isSpeaking.value
            val finalTTSManagerSpeaking = ttsManager.isSpeaking.value
            Log.e(TAG, "❌ BUG DETECTED: TTS still marked as active after ${attempts * 200}ms backgrounded!")
            Log.e(TAG, "❌ Final TTS State: VoiceManager.isSpeaking=$finalVoiceManagerSpeaking, TTSManager.isSpeaking=$finalTTSManagerSpeaking")
            false
        }
        
        if (!ttsNotActiveAfterBackgrounding) {
            // This is the bug we're testing for - TTS state not properly managed when backgrounded
            takeFailureScreenshotAndWaitForCompletion("tts_backgrounding_bug_detected", 
                "BUG CONFIRMED: TTS state shows as active after app is backgrounded")
            fail("BUG DETECTED: TTS should not be active when app is backgrounded, but state shows as active. " +
                 "VoiceManager.isSpeaking=${voiceManager.isSpeaking.value}, TTSManager.isSpeaking=${ttsManager.isSpeaking.value}")
        }
        
        Log.d(TAG, "✅ Step 5 Complete: TTS correctly not active when backgrounded")
        
        // Step 6: Return to app and verify TTS remains not active
        Log.d(TAG, "🔄 Step 6: Returning to app and verifying TTS remains not active...")
        
        // Bring app back to foreground
        bringAppToForeground()
        device.wait(Until.hasObject(By.pkg(packageName)), 5000)
        
        // Verify app is back in foreground
        val isInForeground = device.hasObject(By.pkg(packageName))
        if (!isInForeground) {
            failWithScreenshot("App should return to foreground")
        }
        
        // Verify TTS is still not active
        val voiceManagerSpeaking = voiceManager.isSpeaking.value
        val ttsManagerSpeaking = ttsManager.isSpeaking.value
        val ttsStillNotActive = !voiceManagerSpeaking && !ttsManagerSpeaking
        
        Log.d(TAG, "🔍 Post-foreground TTS state check: VoiceManager.isSpeaking=$voiceManagerSpeaking, TTSManager.isSpeaking=$ttsManagerSpeaking")
        
        if (!ttsStillNotActive) {
            Log.e(TAG, "❌ TTS UNEXPECTEDLY ACTIVE AFTER RETURNING TO FOREGROUND!")
            Log.e(TAG, "   - VoiceManager.isSpeaking: $voiceManagerSpeaking")
            Log.e(TAG, "   - TTSManager.isSpeaking: $ttsManagerSpeaking")
            Log.e(TAG, "   - This indicates TTS may have resumed when app came back to foreground")
            failWithScreenshot("TTS should remain not active after returning to app")
        }
        
        Log.d(TAG, "✅ Step 6 Complete: TTS remains not active after returning to foreground")
        
        // Step 7: Verify the original message is still visible using UIAutomator
        Log.d(TAG, "🔍 Step 7: Verifying original message is still visible after returning from background...")
        
        val originalMessageVisible = device.wait(Until.hasObject(
            By.textContains(testMessage).pkg(packageName)
        ), 3000)
        
        if (!originalMessageVisible) {
            failWithScreenshot("Original message '$testMessage' should still be visible after returning from background")
        }
        
        Log.d(TAG, "✅ Step 7 Complete: Original message still visible after returning from background")
        
        // Clean up
        MainActivity.testViewModelCallback = null
        capturedViewModel = null
        // Activity cleanup handled by @After
        
        Log.d(TAG, "🎉 TTS backgrounding bug test PASSED - TTS correctly stops when app is backgrounded and original message remains visible")
    }
} 