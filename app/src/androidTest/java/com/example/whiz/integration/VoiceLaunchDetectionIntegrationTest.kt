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
 * We test the end results (optimistic chat creation) rather than internal state.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class VoiceLaunchDetectionIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: WhizRepository

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        // Ensure we start with a clean state
        runBlocking {
            // Clear any existing optimistic chats for clean testing
            val chats = repository.getAllChats()
            chats.forEach { chat ->
                if (chat.id < 0) { // Only clean up test/optimistic chats
                    repository.deleteChat(chat.id)
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

        val initialChatCount = runBlocking { repository.getAllChats().size }

        // Launch through real Android system like Google Assistant would
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // Wait for voice launch processing to complete
        runBlocking { delay(3000) }
        
        // Check if voice launch created an optimistic chat
        val finalChats = runBlocking { repository.getAllChats() }
        val finalChatCount = finalChats.size
        val optimisticChats = finalChats.filter { it.id < 0 && it.id > -2000000000000L }
        
        // Clean up
        activity.finish()
        
        // Assertions - Voice launch should create optimistic chat
        val chatCreated = finalChatCount > initialChatCount || optimisticChats.isNotEmpty()
        assertTrue("Voice launch should create optimistic chat (found ${optimisticChats.size} optimistic chats)", chatCreated)
        
        if (optimisticChats.isNotEmpty()) {
            val chat = optimisticChats.first()
            assertTrue("Optimistic chat should have negative ID", chat.id < 0)
            assertEquals("Voice launch chat should have correct title", "Assistant Chat", chat.title)
        }
    }

    @Test
    fun testVoiceLaunch_withVoiceFlags_createsOptimisticChat() {
        // Test secondary detection method: voice flags without trace ID
        val voiceLaunchIntent = Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 0x10000000 // Voice launch flags
            // No tracing_intent_id to test secondary detection
            // No sourceBounds (voice launches don't have bounds)
        }

        val initialChatCount = runBlocking { repository.getAllChats().size }

        // Launch through real Android system
        val activity = instrumentation.startActivitySync(voiceLaunchIntent)
        
        // Wait for voice launch processing
        runBlocking { delay(3000) }
        
        // Check results
        val finalChats = runBlocking { repository.getAllChats() }
        val optimisticChats = finalChats.filter { it.id < 0 && it.id > -2000000000000L }
        val chatCreated = finalChats.size > initialChatCount || optimisticChats.isNotEmpty()
        
        // Clean up
        activity.finish()
        
        // Should detect as voice launch based on flags + no bounds
        assertTrue("Voice launch should be detected with voice flags and no bounds", chatCreated)
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

        val initialChatCount = runBlocking { repository.getAllChats().size }

        // Launch through real Android system
        val activity = instrumentation.startActivitySync(manualLaunchIntent)
        
        // Wait for any potential processing
        runBlocking { delay(3000) }
        
        // Check results
        val finalChats = runBlocking { repository.getAllChats() }
        val optimisticChats = finalChats.filter { it.id < 0 && it.id > -2000000000000L }
        val chatCreated = finalChats.size > initialChatCount || optimisticChats.isNotEmpty()
        
        // Clean up
        activity.finish()
        
        // Manual launch should NOT automatically create a new chat
        assertFalse("Manual launch should NOT automatically create new chat", chatCreated)
    }

    @Test
    fun testOptimisticChatCreation_succeeds() {
        // Test that optimistic chat creation works and returns valid negative ID
        // This is the core functionality used by voice launch detection
        runBlocking {
            // Clear any leftover chats first
            val existingChats = repository.getAllChats()
            existingChats.forEach { chat ->
                if (chat.id < 0) { // Clean up any existing optimistic chats
                    repository.deleteChat(chat.id)
                }
            }
            
            val initialChatCount = repository.getAllChats().size
            
            // Create optimistic chat (simulating voice launch behavior)
            val chatId = repository.createChatOptimistic("Voice Assistant Chat")
            
            // Verify chat was created
            assertNotEquals("Chat creation should not fail", -1L, chatId)
            assertTrue("Optimistic chat should have negative ID", chatId < 0)
            
            val finalChatCount = repository.getAllChats().size
            assertEquals("Chat count should increase by 1", initialChatCount + 1, finalChatCount)
            
            // Verify chat exists and has correct title
            val createdChat = repository.getAllChats().find { it.id == chatId }
            assertNotNull("Created chat should exist", createdChat)
            assertEquals("Chat should have correct title", "Voice Assistant Chat", createdChat?.title)
            
            // Clean up the test chat
            repository.deleteChat(chatId)
        }
    }
} 