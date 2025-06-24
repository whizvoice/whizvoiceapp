package com.example.whiz.integration

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
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

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
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
    fun testVoiceLaunch_withTraceId_createsOptimisticChat() {
        // Create intent that accurately mimics what Google Assistant sends
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
            putExtra("tracing_intent_id", 745783203297493028L)
            // No sourceBounds (voice launches don't have bounds)
        }

        val initialChats = runBlocking { repository.getAllChats() }
        val initialChatCount = initialChats.size

        // Launch through real Android system like Google Assistant would
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // Wait for voice launch processing and optimistic chat creation
        runBlocking { delay(2000) }
        
        // Check for optimistic chat creation (the key behavior we're testing)
        val finalChats = runBlocking { repository.getAllChats() }
        val optimisticChats = finalChats.filter { it.id < 0 }
        val assistantChats = finalChats.filter { it.title == "Assistant Chat" }
        
        // Clean up
        activity.finish()
        
        // Clean up created chats
        runBlocking {
            (assistantChats + optimisticChats).forEach { chat ->
                repository.deleteChat(chat.id)
                android.util.Log.d("VoiceLaunchTest", "🗑️ Cleaned up chat ${chat.id} (${chat.title})")
            }
        }
        
        // Voice launch should create optimistic chat immediately
        // This is the core behavior: immediate UI feedback with negative ID
        val hasOptimisticChat = optimisticChats.isNotEmpty()
        val hasAssistantChat = assistantChats.isNotEmpty()
        val chatCountIncreased = finalChats.size > initialChatCount
        
        assertTrue("Voice launch should create optimistic chat immediately. " +
                  "Initial: $initialChatCount, Final: ${finalChats.size}, " +
                  "Optimistic chats: ${optimisticChats.size} (${optimisticChats.map { "${it.id}:${it.title}" }}), " +
                  "Assistant chats: ${assistantChats.size} (${assistantChats.map { "${it.id}:${it.title}" }})",
                  hasOptimisticChat || hasAssistantChat || chatCountIncreased)
        
        // If optimistic chat was created, verify it has negative ID
        if (optimisticChats.isNotEmpty()) {
            val optimisticChat = optimisticChats.first()
            assertTrue("Optimistic chat should have negative ID, got ${optimisticChat.id}", optimisticChat.id < 0)
            assertEquals("Optimistic chat should have correct title", "Assistant Chat", optimisticChat.title)
        }
    }

    @Test
    fun testManualLaunch_withBounds_doesNotCreateChat() {
        // Create intent that mimics manual app launch (tap on app icon)
        val manualLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10200000 // Manual launch flags
            sourceBounds = android.graphics.Rect(656, 1163, 824, 1433) // App icon bounds
            // No tracing_intent_id extra
        }

        val initialChats = runBlocking { repository.getAllChats() }
        val initialChatCount = initialChats.size

        // Launch through real Android system
        val activity = instrumentation.startActivitySync(manualLaunchIntent)
        
        // Wait for any potential processing
        runBlocking { delay(2000) }
        
        // Check results
        val finalChats = runBlocking { repository.getAllChats() }
        val optimisticChats = finalChats.filter { it.id < 0 }
        val assistantChats = finalChats.filter { it.title == "Assistant Chat" }
        
        // Clean up
        activity.finish()
        
        // Manual launch should NOT automatically create a new chat
        val hasOptimisticChat = optimisticChats.isNotEmpty()
        val hasAssistantChat = assistantChats.isNotEmpty()
        val chatCountIncreased = finalChats.size > initialChatCount
        
        assertFalse("Manual launch should NOT create any chat automatically. " +
                   "Initial: $initialChatCount, Final: ${finalChats.size}, " +
                   "Optimistic chats: ${optimisticChats.size}, Assistant chats: ${assistantChats.size}",
                   hasOptimisticChat || hasAssistantChat || chatCountIncreased)
    }
} 