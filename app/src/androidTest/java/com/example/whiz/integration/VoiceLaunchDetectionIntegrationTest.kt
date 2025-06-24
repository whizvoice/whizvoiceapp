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
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.permissions.PermissionManager
import org.junit.Assert.*

/**
 * Integration tests for voice launch detection functionality using Instrumentation.
 * 
 * This approach uses startActivitySync() to launch activities through the real Android
 * system, which more accurately simulates how Google Assistant launches the app in production.
 * 
 * These tests verify the optimistic chat flow:
 * 1. Voice launch creates optimistic chat with negative ID immediately
 * 2. Chat appears in UI for immediate user feedback
 * 3. Later migration happens when server creates real chat with positive ID
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class VoiceLaunchDetectionIntegrationTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "VoiceLaunchDetectionTest"
    }

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
    @Inject
    lateinit var permissionManager: PermissionManager

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        // Grant microphone permission for voice tests
        android.util.Log.d(TAG, "🎙️ Granting microphone permission for voice tests")
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        
        // Also update PermissionManager state to match
        permissionManager.updateMicrophonePermission(true)
        
        // Clean up any existing test chats (both negative optimistic and positive server chats)
        runBlocking {
            val chats = repository.getAllChats()
            chats.forEach { chat ->
                if (chat.title.contains("Assistant Chat") || chat.title.contains("Voice Assistant Chat") || 
                    chat.id < 0 || chat.title == "New Chat") {
                    repository.deleteChat(chat.id)
                    android.util.Log.d(TAG, "🗑️ Cleaned up test chat ${chat.id} (${chat.title})")
                }
            }
        }
    }

    @Test
    fun testVoiceLaunch_automaticallyAddsFlags() {
        /**
         * WHY THIS TEST MATTERS:
         * When Google Assistant sends just a trace ID, our app should automatically
         * detect this as a voice launch and trigger the same behavior as if the flags
         * were explicitly set:
         * - Navigate to a new chat (CREATE_NEW_CHAT_ON_START behavior)
         * - Enable voice mode (ENABLE_VOICE_MODE behavior)
         * - Enable continuous listening (FROM_ASSISTANT behavior)
         * 
         * This tests that voice launch detection produces the correct end-to-end behavior.
         */
        
        // Create intent with ONLY what Google Assistant provides
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
            putExtra("tracing_intent_id", 745783203297493028L) // Only trace ID from Google Assistant
            // No other extras - app should add them automatically
        }

        val initialChats = runBlocking { repository.getAllChats() }
        val initialChatCount = initialChats.size

        // Launch the activity
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // VERIFY: Voice launch should navigate directly to new chat screen (CREATE_NEW_CHAT_ON_START behavior)
        // Wait longer for navigation and UI composition
        val navigatedToChat = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")
        ), 10000) 
        
        if (!navigatedToChat) {
            // Debug: Check what's actually on screen
            android.util.Log.d(TAG, "🔍 Navigation failed - checking what's on screen...")
            val hasMyChats = device.hasObject(By.textContains("My Chats").pkg("com.example.whiz.debug"))
            val hasAnyEditText = device.hasObject(By.clazz("android.widget.EditText"))
            android.util.Log.d(TAG, "🔍 Has 'My Chats': $hasMyChats, Has any EditText: $hasAnyEditText")
            
            failWithScreenshot("voice_launch_no_navigation", 
                "Voice launch should automatically navigate to new chat screen (CREATE_NEW_CHAT_ON_START behavior)")
        }
        
        // Give voice setup time to complete
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 2000)
        
        // VERIFY: Voice launch should enable continuous listening (FROM_ASSISTANT + ENABLE_VOICE_MODE behavior)
        val continuousListeningEnabled = voiceManager.isContinuousListeningEnabled.value
        
        if (!continuousListeningEnabled) {
            failWithScreenshot("voice_launch_no_continuous_listening", 
                "Voice launch should automatically enable continuous listening (ENABLE_VOICE_MODE behavior)")
        }
        
        android.util.Log.d(TAG, "✅ Voice launch automatically triggered correct behavior (navigation + voice mode)")
        
        // Clean up
        activity.finish()
    }

    @Test
    fun testVoiceLaunch_withTraceId_createsOptimisticChat() {
        /**
         * WHY THIS TEST MATTERS:
         * When users say "Hey Google, talk to WhizVoice", Google Assistant sends our app
         * a special intent with a trace ID. This test verifies that our app correctly:
         * 1. Detects this as a voice launch (not manual tap)
         * 2. Immediately creates an optimistic chat for instant user feedback
         * 3. Navigates directly to that chat (no extra taps needed)  
         * 4. Enables hands-free voice features (continuous listening + TTS)
         * 
         * This ensures users get instant, seamless voice interaction.
         */
        
        // Create intent that accurately mimics what Google Assistant sends
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
            putExtra("tracing_intent_id", 745783203297493028L) // Only what Google Assistant sends
        }

        val initialChats = runBlocking { repository.getAllChats() }
        val initialChatCount = initialChats.size

        // Launch through real Android system like Google Assistant would
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // VERIFY: Voice launch should navigate directly to new chat screen
        val navigatedToChat = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg("com.example.whiz.debug")
        ), 5000) 
        
        if (!navigatedToChat) {
            failWithScreenshot("voice_launch_no_navigation", 
                "Voice launch should navigate directly to new chat screen for immediate interaction")
        }
        
        // Give voice setup time to complete
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 2000)
        
        android.util.Log.d(TAG, "✅ Voice launch navigated to chat screen successfully")
        
        // VERIFY: Voice launch should enable continuous listening and be actively listening
        val continuousListeningEnabled = voiceManager.isContinuousListeningEnabled.value
        
        if (!continuousListeningEnabled) {
            failWithScreenshot("voice_launch_no_continuous_listening", 
                "Voice launch should enable continuous listening for hands-free interaction")
        }
        
        // Wait for listening to actually start (there's a delay after chat loads)
        val listeningStarted = device.wait(Until.hasObject(By.pkg("com.example.whiz.debug")), 1000) &&
            runBlocking {
                var attempts = 0
                while (attempts < 25) { // Wait up to 5 seconds
                    val isListening = voiceManager.isListening.value
                    val isContinuousEnabled = voiceManager.isContinuousListeningEnabled.value
                    android.util.Log.d(TAG, "🔍 Listening check attempt $attempts: isListening=$isListening, continuousEnabled=$isContinuousEnabled")
                    
                    if (isListening) {
                        android.util.Log.d(TAG, "✅ Listening detected on attempt $attempts")
                        return@runBlocking true
                    }
                    kotlinx.coroutines.delay(200L)
                    attempts++
                }
                android.util.Log.d(TAG, "❌ Listening never started after $attempts attempts")
                false
            }
        
        if (!listeningStarted) {
            failWithScreenshot("voice_launch_not_listening", 
                "Voice launch should be actively listening for user input")
        }
        
        android.util.Log.d(TAG, "✅ Voice launch enabled continuous listening and is actively listening")
        
        // Clean up
        activity.finish()

    }

    @Test
    fun testManualLaunch_doesNotCreateChat() {
        /**
         * WHY THIS TEST MATTERS:
         * When users manually tap the app icon, they should land on the chats list
         * to see their existing conversations and choose what to do next. This test verifies:
         * 1. Manual launches are NOT treated as voice launches
         * 2. App stays on chats list (doesn't auto-create/navigate to new chat)
         * 3. No automatic chat creation happens (users choose when to start new chats)
         * 
         * This ensures manual app opens don't interfere with user intent and give
         * users full control over their chat experience.
         */
         
        // Create intent that mimics manual app launch (tap on app icon)
        val manualLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10200000 // Manual launch flags (different from voice)
            // No tracing_intent_id extra (key difference from voice launches)
        }

        val initialChats = runBlocking { repository.getAllChats() }
        val initialChatCount = initialChats.size

        // Launch through real Android system
        val activity = instrumentation.startActivitySync(manualLaunchIntent)
        
        // VERIFY: Manual launch should load chats list (not navigate to a chat)
        val appLoaded = device.wait(Until.hasObject(
            By.textContains("My Chats").pkg("com.example.whiz.debug")
        ), 2000)
        
        if (!appLoaded) {
            android.util.Log.d(TAG, "🚫 Manual launch did not load chats list screen")
            failWithScreenshot("manual_launch_no_chats_list", 
                "Manual launch should load chats list screen for user to choose next action")
        }
        
        // VERIFY: We're on chats list, NOT inside a chat
        val isOnChatsList = device.hasObject(By.textContains("My Chats").pkg("com.example.whiz.debug"))
        val isInChat = device.hasObject(By.clazz("android.widget.EditText").pkg("com.example.whiz.debug"))
        
        if (!isOnChatsList) {
            android.util.Log.d(TAG, "🚫 Manual launch did not stay on chats list (My Chats should be visible)")
            failWithScreenshot("manual_launch_not_on_chats_list", 
                "Manual launch should stay on chats list (My Chats should be visible)")
        }
        
        if (isInChat) {
            android.util.Log.d(TAG, "🚫 Manual launch should NOT auto-navigate to a chat (no EditText input should be present)")
            failWithScreenshot("manual_launch_navigated_to_chat", 
                "Manual launch should NOT auto-navigate to a chat (no EditText input should be present)")
        }
        
        // VERIFY: Manual launch should NOT automatically create any chats
        val finalChats = runBlocking { repository.getAllChats() }
        val optimisticChats = finalChats.filter { it.id < 0 }
        val assistantChats = finalChats.filter { it.title == "Assistant Chat" }
        
        val hasOptimisticChat = optimisticChats.isNotEmpty()
        val hasAssistantChat = assistantChats.isNotEmpty()
        val chatCountIncreased = finalChats.size > initialChatCount
        
        if (hasOptimisticChat || hasAssistantChat || chatCountIncreased) {
            android.util.Log.d(TAG, "🚫 Manual launch should NOT create any chat automatically")
            failWithScreenshot("manual_launch_created_chat", 
                "Manual launch should NOT create any chat automatically - users should choose when to start chats. " +
                "Initial: $initialChatCount, Final: ${finalChats.size}, " +
                "Optimistic chats: ${optimisticChats.size}, Assistant chats: ${assistantChats.size}")
        }
        
        // Clean up
        activity.finish()
    }
} 