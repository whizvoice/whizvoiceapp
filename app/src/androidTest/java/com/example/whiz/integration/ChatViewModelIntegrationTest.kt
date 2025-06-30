package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.services.SpeechRecognitionService
import com.example.whiz.TestCredentialsManager
import com.example.whiz.BaseIntegrationTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.After
import android.util.Log
import com.example.whiz.data.local.MessageType

/**
 * Integration tests for business logic covering message ordering and duplication:
 * 1. Send multiple messages rapidly and verify correct ordering
 * 2. Verify messages appear immediately (optimistic UI)
 * 3. Check bot response placement and message synchronization
 * 4. Detect and prevent message duplication
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Integration tests disabled - device connection issues")
class ChatViewModelIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var speechRecognitionService: SpeechRecognitionService

    companion object {
        private const val TAG = "ChatViewModelIntegrationTest"
        private const val TEST_TIMEOUT = 15000L // 15 seconds
        private const val MESSAGE_COUNT = 5
    }
    
    // Track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This handles automatic authentication
        Log.d(TAG, "🧪 ChatViewModel Integration Test Setup Complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 Cleaning up test chats")
            createdChatIds.forEach { chatId ->
                try {
                    repository.deleteChat(chatId)
                    Log.d(TAG, "🗑️ Deleted test chat: $chatId")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Failed to delete test chat $chatId", e)
                }
            }
            createdChatIds.clear()
            Log.d(TAG, "✅ Test cleanup completed")
        }
    }

    @Test
    fun rapidMessageFlow_maintainsOrderingAndDetectsDuplicates() = runTest {
        Log.d(TAG, "🚀 Testing rapid message sending with ordering and duplication detection")
        
        try {
            val uniqueTestId = System.currentTimeMillis()
            val testChatTitle = "Rapid Message Test - $uniqueTestId"
            val chatId = repository.createChat(testChatTitle)
            createdChatIds.add(chatId)
            
            assertTrue("Chat should be created successfully", chatId > 0)
            Log.d(TAG, "✅ Created test chat: $chatId")
            
            // Step 1: Send 5 messages rapidly
            val sentMessages = mutableListOf<String>()
            val messageTimestamps = mutableListOf<Long>()
            
            Log.d(TAG, "📨 Sending $MESSAGE_COUNT messages rapidly...")
            for (i in 1..MESSAGE_COUNT) {
                val timestamp = System.currentTimeMillis()
                val message = "Message $i - Rapid Test $uniqueTestId - $timestamp"
                
                val messageId = repository.addUserMessage(chatId, message)
                assertTrue("Message should be added successfully", messageId > 0)
                
                sentMessages.add(message)
                messageTimestamps.add(timestamp)
                
                Log.d(TAG, "📤 Sent message $i: ID=$messageId, Content='${message.take(30)}...'")
                
                // Small delay between messages to test rapid succession but allow for ordering
                delay(50)
            }
            
            // Step 2: Wait for optimistic messages to appear and verify immediate display
            Log.d(TAG, "⏳ Waiting for optimistic messages to appear...")
            withTimeout(TEST_TIMEOUT) {
                var attempts = 0
                while (attempts < 30) { // Max 30 attempts (3 seconds)
                    val messages = repository.getMessagesForChat(chatId).first()
                    val userMessages = messages.filter { it.type == MessageType.USER }
                    
                    if (userMessages.size >= MESSAGE_COUNT) {
                        Log.d(TAG, "✅ All $MESSAGE_COUNT messages appeared optimistically")
                        break
                    }
                    
                    attempts++
                    delay(100)
                }
                
                // Verify all messages are present
                val messages = repository.getMessagesForChat(chatId).first()
                val userMessages = messages.filter { it.type == MessageType.USER }
                assertEquals("Should have exactly $MESSAGE_COUNT user messages", MESSAGE_COUNT, userMessages.size)
            }
            
            // Step 3: Verify message ordering (messages should be in timestamp order)
            Log.d(TAG, "🔍 Verifying message ordering...")
            val messages = repository.getMessagesForChat(chatId).first()
            val userMessages = messages.filter { it.type == MessageType.USER }.sortedBy { it.timestamp }
            
            // Check that messages are in the correct order based on content
            for (i in 0 until MESSAGE_COUNT) {
                val expectedMessageStart = "Message ${i + 1} - Rapid Test"
                val actualMessage = userMessages[i].content
                
                assertTrue(
                    "Message $i should contain expected content. Expected: '$expectedMessageStart', Actual: '${actualMessage.take(50)}'",
                    actualMessage.contains(expectedMessageStart)
                )
                
                Log.d(TAG, "✅ Message ${i + 1} in correct position: '${actualMessage.take(30)}...'")
            }
            
            // Step 4: Check for duplicates
            Log.d(TAG, "🔍 Checking for duplicate messages...")
            val allMessageContents = userMessages.map { it.content }
            val uniqueContents = allMessageContents.toSet()
            
            assertEquals(
                "Should have no duplicate messages. All contents: ${allMessageContents.map { it.take(20) }}",
                MESSAGE_COUNT,
                uniqueContents.size
            )
            Log.d(TAG, "✅ No duplicate messages detected")
            
            // Step 5: Wait for potential bot response and verify placement
            Log.d(TAG, "⏳ Waiting for potential bot response...")
            var botResponseReceived = false
            
            withTimeout(TEST_TIMEOUT) {
                var attempts = 0
                while (attempts < 50) { // Max 50 attempts (5 seconds)
                    val allMessages = repository.getMessagesForChat(chatId).first()
                    val assistantMessages = allMessages.filter { it.type == MessageType.ASSISTANT }
                    
                    if (assistantMessages.isNotEmpty()) {
                        Log.d(TAG, "🤖 Bot response detected!")
                        botResponseReceived = true
                        
                        // Verify bot response placement
                        val sortedMessages = allMessages.sortedBy { it.timestamp }
                        val lastUserMessage = sortedMessages.filter { it.type == MessageType.USER }.lastOrNull()
                        val firstBotResponse = assistantMessages.first()
                        
                        if (lastUserMessage != null) {
                            assertTrue(
                                "Bot response should come after the last user message",
                                firstBotResponse.timestamp >= lastUserMessage.timestamp
                            )
                            Log.d(TAG, "✅ Bot response correctly placed after user messages")
                        }
                        
                        // Check that bot response doesn't create duplicates
                        val finalAllMessages = repository.getMessagesForChat(chatId).first()
                        val finalUserMessages = finalAllMessages.filter { it.type == MessageType.USER }
                        assertEquals("User message count should remain unchanged after bot response", MESSAGE_COUNT, finalUserMessages.size)
                        
                        break
                    }
                    
                    attempts++
                    delay(100)
                }
            }
            
            if (!botResponseReceived) {
                Log.w(TAG, "⚠️ No bot response received within timeout - this is acceptable for testing")
                Log.w(TAG, "   The test focuses on message ordering and duplication prevention")
            }
            
            // Step 6: Final verification of message synchronization
            Log.d(TAG, "🔍 Final verification of message state...")
            val finalMessages = repository.getMessagesForChat(chatId).first()
            val finalUserMessages = finalMessages.filter { it.type == MessageType.USER }
            
            // Verify all original messages are still present and in order
            assertEquals("Final user message count should be $MESSAGE_COUNT", MESSAGE_COUNT, finalUserMessages.size)
            
            // Verify ordering is maintained
            for (i in 0 until MESSAGE_COUNT - 1) {
                assertTrue(
                    "Messages should be in timestamp order",
                    finalUserMessages[i].timestamp <= finalUserMessages[i + 1].timestamp
                )
            }
            
            Log.d(TAG, "✅ Message synchronization and ordering verification completed")
            Log.d(TAG, "📊 Final message summary:")
            Log.d(TAG, "   User messages: ${finalUserMessages.size}")
            Log.d(TAG, "   Assistant messages: ${finalMessages.filter { it.type == MessageType.ASSISTANT }.size}")
            Log.d(TAG, "   Total messages: ${finalMessages.size}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Rapid message flow test failed", e)
            throw e
        }
    }
}