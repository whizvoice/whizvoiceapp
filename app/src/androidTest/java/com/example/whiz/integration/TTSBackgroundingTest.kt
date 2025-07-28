package com.example.whiz.integration

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
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
    }

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var voiceManager: VoiceManager
    
    @Inject
    lateinit var ttsManager: TTSManager
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    @Inject
    lateinit var speechRecognitionService: com.example.whiz.services.SpeechRecognitionService
    
    // Note: ChatViewModel will be obtained via ViewModelProvider instead of direct injection
    // to avoid Hilt compilation errors with @HiltViewModel classes

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    
    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles device setup, screenshot dir, authentication, and app launch
        
        // Grant microphone permission for voice tests
        Log.d(TAG, "🎙️ Granting microphone permission for TTS backgrounding test")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)
        
        // Clean up any existing test chats
        runBlocking {
            cleanupTestChats(
                repository = repository,
                trackedChatIds = emptyList(),
                additionalPatterns = listOf("backgrounding test", "tts test", "voice launch"),
                enablePatternFallback = false
            )
        }
    }
    
    @After
    fun cleanup() {
        runBlocking {
            try {
                Log.d(TAG, "🧹 Cleaning up test chats created during TTS backgrounding test")
                
                // Clean up tracked chats and any chats with test patterns
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf("backgrounding test", "tts test", "voice launch"),
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
        
        // Step 1: Voice launch the app (simulates "Hey Google, talk to WhizVoice")
        Log.d(TAG, "📱 Step 1: Voice launching app...")
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
            putExtra("tracing_intent_id", 745783203297493028L) // Google Assistant trace ID
        }

        val initialChats = runBlocking { repository.getAllChats() }
        
        // Launch through real Android system like Google Assistant would
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // Wait for voice launch to navigate to new chat screen
        val navigatedToChat = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 10000)
        
        if (!navigatedToChat) {
            failWithScreenshot("Voice launch should navigate to new chat screen")
        }
        
        Log.d(TAG, "✅ Step 1 Complete: Voice launch successful, navigated to chat")
        
        // Step 2: Send a voice message (simulate transcription but send as voice)
        Log.d(TAG, "🎤 Step 2: Sending voice message...")
        
        val testMessage = "Tell me about space in 50 words"
        
        // Get reference to the ChatViewModel that's actually being used by the ChatScreen
        // The ChatScreen uses hiltViewModel() which is scoped to the current NavBackStackEntry
        // We need to get the same ChatViewModel instance from the navigation scope, not activity scope
        
        // First, try to get the NavController from the activity
        val navControllerField = activity::class.java.getDeclaredField("navController")
        navControllerField.isAccessible = true
        val navController = navControllerField.get(activity) as androidx.navigation.NavHostController
        
        // Get the current back stack entry's ViewModelStore
        val currentBackStackEntry = navController.currentBackStackEntry
        requireNotNull(currentBackStackEntry) { "No current back stack entry found" }
        
        // Get the ChatViewModel from the navigation scope (same as hiltViewModel() in ChatScreen)
        val chatViewModel = ViewModelProvider(currentBackStackEntry).get(com.example.whiz.ui.viewmodels.ChatViewModel::class.java)
        
        // Simulate voice transcription and automatic sending (as voice input typically does)
        // Use rapid=false to verify the message actually appears, and pass SpeechRecognitionService for real callback mechanism
        val voiceSendSuccess = simulateVoiceTranscriptionAndSend(
            testMessage, 
            rapid = false, 
            chatViewModel = chatViewModel,
            speechRecognitionService = speechRecognitionService
        )
        if (!voiceSendSuccess) {
            failWithScreenshot("Failed to send voice message via transcription simulation")
        }
        
        Log.d(TAG, "✅ Step 2 Complete: Voice message sent successfully")
        
        // Step 3: Wait for bot response and TTS to start speaking
        Log.d(TAG, "⏳ Step 3: Waiting for bot response and TTS to start...")
        
        // Wait for bot thinking indicator or response
        val botStartedResponding = waitForBotThinkingIndicator(15000) || waitForBotResponse(5000)
        if (!botStartedResponding) {
            failWithScreenshot("Bot should start responding to voice message")
        }
        
        // Wait for bot to finish thinking and start speaking
        val botFinishedThinking = waitForBotThinkingToFinish(30000)
        if (!botFinishedThinking) {
            failWithScreenshot("Bot should finish thinking within timeout")
        }
        
        // Wait for TTS activation (either actual speaking or initialization error on emulator)
        Log.d(TAG, "🔊 Waiting for TTS activation...")
        val ttsActivated = runBlocking {
            var attempts = 0
            while (attempts < 50) { // Wait up to 10 seconds
                val isSpeaking = voiceManager.isSpeaking.value
                val ttsManagerSpeaking = ttsManager.isSpeaking.value
                val ttsInitError = ttsManager.initializationError.value
                val chatViewModelTTSInitialized = chatViewModel.isTTSInitialized.value
                
                Log.d(TAG, "🔍 TTS check attempt $attempts: VoiceManager.isSpeaking=$isSpeaking, " +
                         "TTSManager.isSpeaking=$ttsManagerSpeaking, TTSInitError=$ttsInitError, " +
                         "ChatViewModel.isTTSInitialized=$chatViewModelTTSInitialized")
                
                if (isSpeaking || ttsManagerSpeaking) {
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
                val isSpeaking = voiceManager.isSpeaking.value
                val ttsManagerSpeaking = ttsManager.isSpeaking.value
                
                Log.d(TAG, "🔍 Post-background TTS check attempt $attempts: VoiceManager.isSpeaking=$isSpeaking, TTSManager.isSpeaking=$ttsManagerSpeaking")
                
                if (!isSpeaking && !ttsManagerSpeaking) {
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
        val ttsStillNotActive = !voiceManager.isSpeaking.value && !ttsManager.isSpeaking.value
        if (!ttsStillNotActive) {
            failWithScreenshot("TTS should remain not active after returning to app")
        }
        
        Log.d(TAG, "✅ Step 6 Complete: TTS remains not active after returning to foreground")
        
        // Track any chats created for cleanup
        try {
            val currentChats = runBlocking { repository.getAllChats() }
            val newChats = currentChats.filter { !initialChats.map { it.id }.contains(it.id) }
            newChats.forEach { chat ->
                createdChatIds.add(chat.id)
                Log.d(TAG, "📝 Tracked chat for cleanup: ${chat.id} ('${chat.title}')")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Could not track created chats: ${e.message}")
        }
        
        // Clean up
        activity.finish()
        
        Log.d(TAG, "🎉 TTS backgrounding bug test PASSED - TTS correctly stops when app is backgrounded")
    }
} 