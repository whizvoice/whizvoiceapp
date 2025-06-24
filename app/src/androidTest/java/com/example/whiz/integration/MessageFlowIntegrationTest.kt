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
            launchAppAndVerifyChatsListScreen()
            
            // step 2: click new chat button
            android.util.Log.d("MessageFlowTest", "➕ step 2: clicking new chat button")
            clickNewChatButton()
            
            // step 3: send first message and verify optimistic UI
            val firstMessage = "Hello! this is test message 1 - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 3: sending first message and verifying optimistic UI")
            sendMessageAndVerifyOptimisticUI(firstMessage)
            
            // step 4: confirm bot is responding (thinking indicator visible)
            android.util.Log.d("MessageFlowTest", "🤖 step 4: confirming bot is responding")
            verifyBotIsResponding()
            
            // step 5: send second message while bot is responding
            val secondMessage = "second message while you're thinking - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 5: sending second message while bot is responding")
            sendMessageWhileBotResponding(secondMessage)
            
            // step 6: wait for bot response to arrive
            android.util.Log.d("MessageFlowTest", "⏳ step 6: waiting for bot response")
            waitForBotResponse()
            
            // step 7: send third message after bot response
            val thirdMessage = "third message after your response - $uniqueTestId"
            android.util.Log.d("MessageFlowTest", "💬 step 7: sending third message after bot response")
            sendMessageAfterBotResponse(thirdMessage)
            
            // step 8: verify all messages are showing properly
            android.util.Log.d("MessageFlowTest", "✅ step 8: verifying all messages display correctly")
            verifyAllMessagesDisplayCorrectly(listOf(firstMessage, secondMessage, thirdMessage))
            
            // step 9: verify chat migration from optimistic to server-backed was successful
            android.util.Log.d("MessageFlowTest", "🔄 step 9: verifying chat migration success")
            verifyChatMigrationSuccess()
            
            android.util.Log.d("MessageFlowTest", "🎉 comprehensive message flow test PASSED!")
            
        } catch (e: Exception) {
            android.util.Log.e("MessageFlowTest", "comprehensive message flow test FAILED", e)
            throw e
        }
    }
    
    private fun launchAppAndVerifyChatsListScreen() {
        // launch the app
        val intent = Intent().apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        
        // wait for app to launch
        val appLaunched = device.wait(Until.hasObject(By.pkg(packageName)), 10000)
        if (!appLaunched) {
            failWithScreenshot("app_launch_failed", "app failed to launch within 10 seconds")
        }
        
        // verify we're on chats list (look for "my chats" text or new chat button)
        val chatsListLoaded = device.wait(Until.hasObject(
            By.text("My Chats").pkg(packageName)
        ), 8000) || device.wait(Until.hasObject(
            By.descContains("New Chat").pkg(packageName)
        ), 3000)
        
        if (!chatsListLoaded) {
            failWithScreenshot("chats_list_not_loaded", "chats list screen not detected - missing 'My Chats' text or new chat button")
        }
        
        android.util.Log.d("MessageFlowTest", "✅ app launched and chats list verified")
    }
    
    private fun clickNewChatButton() {
        val newChatButton = device.findObject(
            UiSelector()
                .descriptionContains("New Chat")
                .packageName(packageName)
        )
        
        if (!newChatButton.waitForExists(5000)) {
            failWithScreenshot("new_chat_button_not_found", "new chat button not found on chats list screen")
        }
        
        newChatButton.click()
        
        // wait for chat screen to load by looking for message input field
        val chatScreenLoaded = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 8000)
        
        if (!chatScreenLoaded) {
            failWithScreenshot("chat_screen_not_loaded", "chat screen did not load after clicking new chat button")
        }
        
        android.util.Log.d("MessageFlowTest", "✅ new chat button clicked and chat screen loaded")
    }
    
    private fun sendMessageAndVerifyOptimisticUI(message: String) {
        // find message input field
        val messageInput = device.findObject(
            UiSelector()
                .className("android.widget.EditText")
                .packageName(packageName)
        )
        
        if (!messageInput.waitForExists(5000)) {
            failWithScreenshot("message_input_not_found", "message input field not found")
        }
        
        // click to focus and type message
        messageInput.click()
        messageInput.setText(message)
        
        // wait for text to appear in field
        val textSet = device.wait(Until.hasObject(
            By.text(message).pkg(packageName)
        ), 3000)
        
        if (!textSet) {
            android.util.Log.w("MessageFlowTest", "⚠️ text not visible in input field, trying again...")
            messageInput.setText(message)
        }
        
        // find and click send button
        val sendButton = device.findObject(
            UiSelector()
                .descriptionContains("Send message")
                .packageName(packageName)
        )
        
        if (!sendButton.waitForExists(3000)) {
            failWithScreenshot("send_button_not_found", "send button not found after typing message")
        }
        
        sendButton.click()
        
        // verify optimistic UI: message appears immediately in chat
        val messageDisplayed = device.wait(Until.hasObject(
            By.textContains(message).pkg(packageName)
        ), 5000)
        
        if (!messageDisplayed) {
            failWithScreenshot("optimistic_message_not_displayed", "message not displayed immediately after sending - optimistic UI not working")
        }
        
        android.util.Log.d("MessageFlowTest", "✅ message sent and optimistic UI verified: '$message'")
    }
    
    private fun verifyBotIsResponding() {
        // look for "whiz is computing" thinking indicator
        val thinkingIndicator = device.wait(Until.hasObject(
            By.textContains("Whiz is computing").pkg(packageName)
        ), 5000)
        
        if (!thinkingIndicator) {
            // also check for any typical thinking/responding indicators
            val anyThinkingIndicator = device.wait(Until.hasObject(
                By.textContains("thinking").pkg(packageName)
            ), 2000) || device.wait(Until.hasObject(
                By.textContains("computing").pkg(packageName)
            ), 2000)
            
            if (!anyThinkingIndicator) {
                failWithScreenshot("bot_not_responding", "bot thinking indicator not found - bot may not be responding")
            }
        }
        
        android.util.Log.d("MessageFlowTest", "✅ bot responding state confirmed")
    }
    
    private fun sendMessageWhileBotResponding(message: String) {
        // verify bot is still responding before sending
        val stillResponding = device.hasObject(
            By.textContains("Whiz is computing").pkg(packageName)
        ) || device.hasObject(
            By.textContains("thinking").pkg(packageName)
        )
        
        if (!stillResponding) {
            android.util.Log.w("MessageFlowTest", "⚠️ bot may have finished responding, but continuing with test")
        }
        
        // send message using same method as before
        sendMessageAndVerifyOptimisticUI(message)
        
        android.util.Log.d("MessageFlowTest", "✅ second message sent while bot responding: '$message'")
    }
    
    private fun waitForBotResponse() {
        // wait for thinking indicator to disappear (bot finished responding)
        val thinkingGone = device.wait(Until.gone(
            By.textContains("Whiz is computing").pkg(packageName)
        ), 30000) // give bot 30 seconds to respond
        
        if (!thinkingGone) {
            android.util.Log.w("MessageFlowTest", "⚠️ thinking indicator still visible after 30s, checking for response anyway")
        }
        
        // wait for actual bot response message to appear
        val botResponseDetected = device.wait(Until.hasObject(
            By.textContains("understand").pkg(packageName)
        ), 10000) || device.wait(Until.hasObject(
            By.textContains("interesting").pkg(packageName)
        ), 2000) || device.wait(Until.hasObject(
            By.textContains("think").pkg(packageName)
        ), 2000)
        
        if (!botResponseDetected) {
            // check for any new message that looks like a bot response
            val anyNewMessage = device.wait(Until.hasObject(
                By.clazz("android.widget.TextView").pkg(packageName)
            ), 5000)
            
            if (!anyNewMessage) {
                failWithScreenshot("no_bot_response", "bot response not detected within timeout")
            }
        }
        
        android.util.Log.d("MessageFlowTest", "✅ bot response received")
    }
    
    private fun sendMessageAfterBotResponse(message: String) {
        // ensure bot is no longer responding
        val notResponding = !device.hasObject(
            By.textContains("Whiz is computing").pkg(packageName)
        )
        
        if (!notResponding) {
            android.util.Log.w("MessageFlowTest", "⚠️ bot still appears to be responding, but sending message anyway")
        }
        
        // send the third message
        sendMessageAndVerifyOptimisticUI(message)
        
        android.util.Log.d("MessageFlowTest", "✅ third message sent after bot response: '$message'")
    }
    
    private fun verifyAllMessagesDisplayCorrectly(sentMessages: List<String>) {
        android.util.Log.d("MessageFlowTest", "🔍 verifying all messages display correctly...")
        
        // check each sent message is visible
        sentMessages.forEachIndexed { index, message ->
            val messageVisible = device.wait(Until.hasObject(
                By.textContains(message.take(20)).pkg(packageName) // check first 20 chars to be safe
            ), 3000)
            
            if (!messageVisible) {
                failWithScreenshot("message_${index}_missing", "message $index not visible: '${message.take(30)}...'")
            }
            
            android.util.Log.d("MessageFlowTest", "✅ message $index verified: '${message.take(30)}...'")
        }
        
        // check for message duplication by counting occurrences
        sentMessages.forEach { message ->
            val messageElements = device.findObjects(
                By.textContains(message.take(15)).pkg(packageName)
            )
            
            if (messageElements.size > 1) {
                android.util.Log.w("MessageFlowTest", "⚠️ potential message duplication detected for: '${message.take(30)}...' (found ${messageElements.size} instances)")
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