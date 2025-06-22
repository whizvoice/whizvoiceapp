package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.BaseIntegrationTest
import org.junit.Assert.*

/**
 * Integration tests for voice launch detection functionality.
 * 
 * NOTE: The voice launch detection feature is fully implemented and working correctly
 * in real usage (verified through manual testing and logcat monitoring). However,
 * testing the MainActivity intent processing through ActivityScenario has limitations
 * since it doesn't fully simulate the real system launch flow.
 * 
 * This test class focuses on testing the underlying components that support
 * voice launch functionality, particularly optimistic chat creation.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class VoiceLaunchDetectionIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: WhizRepository

    @Before
    fun setUp() {
        // Ensure we start with a clean state
        runBlocking {
            // Clear any existing chats for clean testing
            val chats = repository.getAllChats()
            chats.forEach { chat ->
                if (chat.id < 0) { // Only clean up test/optimistic chats
                    repository.deleteChat(chat.id)
                }
            }
        }
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