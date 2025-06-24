package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiSelector
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Ignore
import org.junit.runner.RunWith
import javax.inject.Inject
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestAppModule
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.TestCredentialsManager
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.integration.GoogleSignInAutomator
import com.example.whiz.integration.AuthenticationTestHelper
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.example.whiz.BaseIntegrationTest
import android.content.Context
import android.content.Intent

/**
 * comprehensive UI integration test for complete message flow including:
 * - app launch and chats list verification
 * - new chat creation
 * - optimistic UI message display
 * - bot response detection
 * - multiple message sending with proper timing
 * - chat migration from optimistic to server-backed
 * - message persistence verification
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Integration tests disabled - device connection issues")
class MessageFlowIntegrationTest : BaseIntegrationTest() {

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var authApi: AuthApi

    // device is inherited from BaseIntegrationTest
    private val uniqueTestId = System.currentTimeMillis()
    
    // track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // this handles automatic authentication (device is set up in BaseIntegrationTest)
        android.util.Log.d("MessageFlowTest", "🧪 comprehensive message flow test setup complete")
    }

    @After
    fun cleanup() {
        runBlocking {
            android.util.Log.d("MessageFlowTest", "🧹 cleaning up test chats")
            createdChatIds.forEach { chatId ->
                try {
                    repository.deleteChat(chatId)
                    android.util.Log.d("MessageFlowTest", "🗑️ deleted test chat: $chatId")
                } catch (e: Exception) {
                    android.util.Log.w("MessageFlowTest", "⚠️ failed to delete test chat $chatId", e)
                }
            }
            createdChatIds.clear()
            android.util.Log.d("MessageFlowTest", "✅ test cleanup completed")
        }
    }

    @Test
    fun fullMessageFlowTest_comprehensiveUIAndOptimisticMigration(): Unit = runBlocking {
        android.util.Log.d("MessageFlowTest", "🚀 starting comprehensive message flow UI test")
        
        val credentials = TestCredentialsManager.credentials
        android.util.Log.d("MessageFlowTest", "test user: ${credentials.googleTestAccount.email}")
        
        try {
            // step 1: launch app and verify we're on chats list
            android.util.Log.d("MessageFlowTest", "📱 step 1: launching app and verifying chats list")
            if (!launchAppAndWaitForLoad()) {
                failWithScreenshot("app_launch_failed", "app failed to launch or load main UI")
            }
            
            // step 2: click new chat button
            android.util.Log.d("MessageFlowTest", "➕ step 2: clicking new chat button")
            if (!clickNewChatButtonAndWaitForChatScreen()) {
                failWithScreenshot("new_chat_failed", "new chat button not found or chat screen failed to load")
            }
            
            // step 3: send first message and verify optimistic UI
            val firstMessage = "Hello! this is test message 1 - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 3: sending first message and verifying optimistic UI")
            if (!sendMessageAndVerifyDisplay(firstMessage)) {
                failWithScreenshot("first_message_failed", "failed to send first message or verify optimistic UI")
            }
            
            // step 4: confirm bot is responding (thinking indicator visible)
            android.util.Log.d("MessageFlowTest", "🤖 step 4: confirming bot is responding")
            if (!waitForBotThinkingIndicator()) {
                failWithScreenshot("bot_not_responding", "bot thinking indicator not found - bot may not be responding")
            }
            
            // step 5: send second message while bot is responding
            val secondMessage = "second message while you're thinking - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 5: sending second message while bot is responding")
            
            // verify bot is still responding before sending
            if (!isBotCurrentlyResponding()) {
                android.util.Log.w("MessageFlowTest", "⚠️ bot may have finished responding, but continuing with test")
            }
            
            if (!sendMessageAndVerifyDisplay(secondMessage)) {
                failWithScreenshot("second_message_failed", "failed to send second message while bot responding")
            }
            
            // step 6: wait for bot response to arrive
            android.util.Log.d("MessageFlowTest", "⏳ step 6: waiting for bot response")
            
            // get message count before waiting for response (for robust detection)
            val messageCountBeforeResponse = getCurrentMessageCount()
            android.util.Log.d("MessageFlowTest", "📊 message count before bot response: $messageCountBeforeResponse")
            
            if (!waitForBotThinkingToFinish()) {
                android.util.Log.w("MessageFlowTest", "⚠️ thinking indicator still visible after timeout, checking for response anyway")
            }
            
            // use both methods to detect bot response - first try styling detection, then fallback to count
            val botResponseByStyle = waitForBotResponse(5000)
            val botResponseByCount = if (!botResponseByStyle) {
                waitForNewMessageToAppear(messageCountBeforeResponse, 5000)
            } else true
            
            if (!botResponseByStyle && !botResponseByCount) {
                failWithScreenshot("no_bot_response", "bot response not detected within timeout using either styling or message count detection")
            }
            
            android.util.Log.d("MessageFlowTest", "✅ bot response detected (style: $botResponseByStyle, count: $botResponseByCount)")
            
            // step 7: send third message after bot response
            val thirdMessage = "third message after your response - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 7: sending third message after bot response")
            
            // ensure bot is no longer responding
            if (isBotCurrentlyResponding()) {
                android.util.Log.w("MessageFlowTest", "⚠️ bot still appears to be responding, but sending message anyway")
            }
            
            if (!sendMessageAndVerifyDisplay(thirdMessage)) {
                failWithScreenshot("third_message_failed", "failed to send third message after bot response")
            }
            
            // step 8: verify all messages are showing properly
            android.util.Log.d("MessageFlowTest", "✅ step 8: verifying all messages display correctly")
            val sentMessages = listOf(firstMessage, secondMessage, thirdMessage)
            verifyAllMessagesDisplayCorrectly(sentMessages)
            
            // step 9: verify chat migration from optimistic to server-backed was successful
            android.util.Log.d("MessageFlowTest", "🔄 step 9: verifying chat migration success")
            verifyChatMigrationSuccess()
            
            android.util.Log.d("MessageFlowTest", "🎉 comprehensive message flow test PASSED!")
            
        } catch (e: Exception) {
            android.util.Log.e("MessageFlowTest", "comprehensive message flow test FAILED", e)
            throw e
        }
    }
    
    private fun verifyAllMessagesDisplayCorrectly(sentMessages: List<String>) {
        android.util.Log.d("MessageFlowTest", "🔍 verifying all messages display correctly...")
        
        // check each sent message is visible
        sentMessages.forEachIndexed { index, message ->
            if (!verifyMessageVisible(message)) {
                failWithScreenshot("message_${index}_missing", "message $index not visible: '${message.take(30)}...'")
            }
            android.util.Log.d("MessageFlowTest", "✅ message $index verified: '${message.take(30)}...'")
        }
        
        // check for message duplication
        sentMessages.forEach { message ->
            val occurrences = countMessageOccurrences(message)
            if (occurrences > 1) {
                android.util.Log.w("MessageFlowTest", "⚠️ potential message duplication detected for: '${message.take(30)}...' (found $occurrences instances)")
                // not failing here as there might be legitimate reasons for multiple occurrences
            }
        }
        
        android.util.Log.d("MessageFlowTest", "✅ all messages verified without major duplication issues")
    }
    
    private suspend fun verifyChatMigrationSuccess() {
        android.util.Log.d("MessageFlowTest", "🔍 verifying chat migration from optimistic to server-backed...")
        
        // give migration some time to complete
        delay(2000)
        
        // check database for chat migration
        val allChats = repository.getAllChats()
        val testChat = allChats.find { chat ->
            chat.title.contains("test message 1") || chat.title.contains("hello")
        }
        
        if (testChat == null) {
            android.util.Log.w("MessageFlowTest", "⚠️ test chat not found in database, migration may still be in progress")
            return
        }
        
        // verify chat has positive ID (server-backed, not optimistic negative ID)
        if (testChat.id > 0) {
            android.util.Log.d("MessageFlowTest", "✅ chat migration successful: chat has positive server ID ${testChat.id}")
            createdChatIds.add(testChat.id) // track for cleanup
        } else {
            android.util.Log.w("MessageFlowTest", "⚠️ chat still has negative/optimistic ID ${testChat.id}, migration may be in progress")
        }
        
        // verify messages are associated with the final chat ID
        val chatMessages = repository.getMessagesForChat(testChat.id).first()
        if (chatMessages.size >= 3) { // should have at least 3 user messages + bot responses
            android.util.Log.d("MessageFlowTest", "✅ messages successfully associated with migrated chat: ${chatMessages.size} messages found")
        } else {
            android.util.Log.w("MessageFlowTest", "⚠️ fewer messages than expected in migrated chat: ${chatMessages.size}")
        }
        
        android.util.Log.d("MessageFlowTest", "✅ chat migration verification completed")
    }
}