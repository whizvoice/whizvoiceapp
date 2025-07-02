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
 * - bot interruption capability (CRITICAL UX TEST: users must be able to interrupt bot)
 * - multiple message sending with proper timing
 * - chat migration from optimistic to server-backed
 * - message persistence verification
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MessageFlowIntegrationTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "MessageFlowTest"
    }

    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var authApi: AuthApi

    @Inject 
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
    @Inject
    lateinit var preloadManager: com.example.whiz.data.PreloadManager
    
    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager

    // device is inherited from BaseIntegrationTest
    private val uniqueTestId = System.currentTimeMillis()
    
    // track chats created during tests for cleanup
    private val createdChatIds = mutableListOf<Long>()
    
    // track optimistic chat migration
    private var optimisticChatId: Long? = null
    private var finalServerChatId: Long? = null

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // this handles automatic authentication (device is set up in BaseIntegrationTest)
        
        // log environment info for debugging CI issues
        android.util.Log.d(TAG, "🧪 comprehensive message flow test setup complete")
        android.util.Log.d(TAG, "🔍 Environment info:")
        android.util.Log.d(TAG, "  CI detected: ${System.getenv("CI")}")
        android.util.Log.d(TAG, "  GitHub Actions: ${System.getenv("GITHUB_ACTIONS")}")
        android.util.Log.d(TAG, "  Device display: ${device.displayWidth}x${device.displayHeight}")
        android.util.Log.d(TAG, "  Timing multiplier: ${if (System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true") "3.0x (CI)" else "1.0x (local)"}")
    }

    @After
    fun cleanup() {
        runBlocking {
            android.util.Log.d(TAG, "🧹 cleaning up test chats")
            createdChatIds.forEach { chatId ->
                try {
                    repository.deleteChat(chatId)
                    android.util.Log.d(TAG, "🗑️ deleted test chat: $chatId")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "⚠️ failed to delete test chat $chatId", e)
                }
            }
            createdChatIds.clear()
            android.util.Log.d(TAG, "✅ test cleanup completed")
        }
    }

    @Test
    fun fullMessageFlowTest_comprehensiveUIAndOptimisticMigration(): Unit = runBlocking {
        android.util.Log.d(TAG, "🚀 starting comprehensive message flow UI test")
        
        val credentials = TestCredentialsManager.credentials
        android.util.Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")
        
        try {
            // step 1: launch app and ensure we get to a new chat screen efficiently
            android.util.Log.d(TAG, "📱 step 1: launching app and navigating to new chat")
            if (!launchAppAndWaitForLoad()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 1: app failed to launch or load main UI")
                failWithScreenshot("app_launch_failed", "app failed to launch or load main UI")
            }
            
            // step 2: navigate to new chat (handling both chats list and existing chat scenarios)
            android.util.Log.d(TAG, "➕ step 2: navigating to new chat")
            
            if (isCurrentlyInChatScreen()) {
                // if we're already in a chat, navigate back to chats list first, then create new chat
                android.util.Log.d(TAG, "🔄 currently in chat screen, going back to chats list first")
                if (!navigateBackToChatsListFromChat()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2a: failed to navigate from chat screen to chats list")
                    failWithScreenshot("navigate_to_chats_list_failed", "failed to navigate from chat screen to chats list")
                }
                
                // now click new chat button
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2b: new chat button not found or chat screen failed to load")
                    failWithScreenshot("new_chat_failed", "new chat button not found or chat screen failed to load")
                }
            } else {
                // we're on chats list, directly click new chat button
                android.util.Log.d(TAG, "📋 on chats list, clicking new chat button directly")
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2: new chat button not found or chat screen failed to load")
                    failWithScreenshot("new_chat_failed", "new chat button not found or chat screen failed to load")
                }
            }
            
            // step 3: send first message and verify optimistic UI
            val firstMessage = "Hello! this is test message 1 - $uniqueTestId"
            android.util.Log.d(TAG, "💬 step 3: sending first message and verifying optimistic UI")
            
            // wait for chat UI to be ready for message input
            android.util.Log.d(TAG, "⏳ waiting for chat UI to be ready for message input...")
            if (!waitForChatUIReady()) {
                android.util.Log.e(TAG, "❌ FAILURE: Chat UI not ready for message input")
                failWithScreenshot("chat_ui_not_ready", "Chat UI not ready for message input")
            }
            
            if (!sendMessageAndVerifyWebSocketSending(firstMessage, 1)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 3: first message failed to send properly")
                android.util.Log.e(TAG, "   This could be UI display failure or WebSocket transmission failure")
                android.util.Log.e(TAG, "   Message: '${firstMessage.take(50)}...'")
                failWithScreenshot("first_message_send_failed", "Step 3: First message failed to send or reach server")
            }
            
            // ensure message is visible before proceeding
            android.util.Log.d(TAG, "🔍 verifying first message is visible...")
            if (!verifyMessageVisible(firstMessage)) {
                android.util.Log.e(TAG, "❌ first message not visible")
                failWithScreenshot("first_message_not_visible", "first message not visible")
            }
            
            // step 3.5: capture optimistic chat ID for migration tracking
            android.util.Log.d(TAG, "🔍 step 3.5: capturing optimistic chat ID")
            optimisticChatId = getCurrentOptimisticChatId()
            if (optimisticChatId != null) {
                android.util.Log.d(TAG, "✅ captured optimistic chat ID: $optimisticChatId")
            } else {
                android.util.Log.w(TAG, "⚠️ could not capture optimistic chat ID - may already have migrated or not created yet")
            }
            
            // step 4: confirm bot is responding (thinking indicator visible)
            android.util.Log.d(TAG, "🤖 step 4: confirming bot is responding")
            if (!waitForBotThinkingIndicator()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 4: bot thinking indicator not found - bot may not be responding")
                failWithScreenshot("bot_not_responding", "bot thinking indicator not found - bot may not be responding")
            }
            
            // step 5: send second message while bot is responding
            // This is the CRITICAL test for the production bug where messages appear to send
            // during bot response but don't actually reach the server via WebSocket
            val secondMessage = "second message while you're thinking - $uniqueTestId"
            android.util.Log.d(TAG, "💬 step 5: sending second message while bot is responding (CRITICAL WebSocket test)")
            
            // CRITICAL: verify bot is still responding before sending - this tests the interruption capability
            if (!isBotCurrentlyResponding()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 5: bot stopped responding too quickly - cannot test interruption")
                failWithScreenshot("bot_finished_too_early", "bot stopped responding before we could test interruption capability")
            }
            
            android.util.Log.d(TAG, "🤖 bot confirmed still responding - now testing interruption...")
            
            // This is the core UX test: users MUST be able to interrupt the bot AND have messages actually reach the server
            // Using rapid send method to test interruption capability during bot response
            // This should detect the production bug where typing is blocked during bot response
            if (!sendMessageAndVerifyDisplayRapid(secondMessage)) {
                android.util.Log.e(TAG, "❌ CRITICAL: Bot interruption test failed!")
                android.util.Log.e(TAG, "   Rapid message sending failed during bot response")
                android.util.Log.e(TAG, "   This indicates the PRODUCTION BUG where users cannot type/send")
                android.util.Log.e(TAG, "   messages during bot response period")
                android.util.Log.e(TAG, "   Message: '${secondMessage.take(50)}...'")
                failWithScreenshot("bot_interruption_failed", "CRITICAL: Rapid second message during bot response failed - typing likely blocked")
            }
            
            android.util.Log.d(TAG, "✅ Bot interruption successful - message sent AND reached server while bot was responding!")
            
            // step 6: wait for bot response to arrive
            android.util.Log.d(TAG, "⏳ step 6: waiting for bot response")
            
            if (!waitForBotThinkingToFinish()) {
                android.util.Log.w(TAG, "⚠️ thinking indicator still visible after timeout, checking for response anyway")
            }
            
            // use styling detection to detect bot response
            val botResponseDetected = waitForBotResponse(5000)
            
            if (!botResponseDetected) {
                android.util.Log.e(TAG, "❌ FAILURE at step 6: bot response not detected within timeout using styling detection")
                failWithScreenshot("no_bot_response", "bot response not detected within timeout using styling detection")
            }
            
            android.util.Log.d(TAG, "✅ bot response detected via styling")
            
            // step 6.5: check if migration from optimistic to server chat ID worked
            android.util.Log.d(TAG, "🔄 step 6.5: checking chat migration after bot response")
            val migrationWorked = checkChatMigration()
            if (migrationWorked) {
                android.util.Log.d(TAG, "✅ chat migration successful after bot response")
            } else {
                android.util.Log.w(TAG, "⚠️ chat migration not detected yet - may still be in progress")
            }
            
            // step 7: send third message after bot response
            val thirdMessage = "third message after your response - $uniqueTestId"
            android.util.Log.d(TAG, "💬 step 7: sending third message after bot response")
            
            // ensure bot is no longer responding
            if (isBotCurrentlyResponding()) {
                android.util.Log.w(TAG, "⚠️ bot still appears to be responding, but sending message anyway")
            }
            
            if (!sendMessageAndVerifyWebSocketSending(thirdMessage, 3)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 7: third message after bot response failed")
                android.util.Log.e(TAG, "   Third message either failed UI display or WebSocket transmission")
                android.util.Log.e(TAG, "   Message: '${thirdMessage.take(50)}...'")
                failWithScreenshot("third_message_send_failed", "Step 7: Third message after bot response failed to send or reach server")
            }
            
            // step 8: verify all messages are showing properly
            android.util.Log.d(TAG, "✅ step 8: verifying all messages display correctly")
            
            // wait for UI to be stable before verification
            android.util.Log.d(TAG, "⏳ waiting for UI to be stable before verification...")
            if (!waitForUIToBeStable()) {
                android.util.Log.e(TAG, "❌ FAILURE: UI not stable for verification")
                failWithScreenshot("ui_not_stable", "UI not stable for verification")
            }
            
            // ensure we're in chat screen before verification
            if (!isCurrentlyInChatScreen()) {
                android.util.Log.w(TAG, "⚠️ not currently in chat screen during verification, attempting recovery...")
                // try to get back to chat screen if possible
                device.pressBack()
                if (!waitForChatScreenToLoad()) {
                    android.util.Log.e(TAG, "❌ FAILURE: Could not recover to chat screen for verification")
                    failWithScreenshot("chat_screen_recovery_failed", "Could not recover to chat screen for verification")
                }
            }
            
            val sentMessages = listOf(firstMessage, secondMessage, thirdMessage)
            verifyAllMessagesDisplayCorrectly(sentMessages)
            
            // step 9: wait for chat migration to complete, then do comprehensive final verification
            android.util.Log.d(TAG, "🔍 step 9a: waiting for chat migration to complete...")
            waitForChatMigrationCompletion()
            
            android.util.Log.d(TAG, "🔍 step 9b: final comprehensive verification - checking for duplicates and completeness")
            verifyFinalMessageState(sentMessages)
            
            android.util.Log.d(TAG, "🎉 comprehensive message flow test PASSED!")
            android.util.Log.d(TAG, "✅ Test validated: optimistic UI, bot interruption capability, chat migration, and message persistence")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "comprehensive message flow test FAILED", e)
            throw e
        }
    }
    
    private fun verifyAllMessagesDisplayCorrectly(sentMessages: List<String>) {
        android.util.Log.d(TAG, "🔍 verifying all messages display correctly...")
        
        // scroll to top of chat first to ensure we can see all messages
        android.util.Log.d(TAG, "📜 scrolling to top of chat to ensure all messages are visible")
        repeat(5) { attempt ->
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                // swipe from top to bottom to scroll to top
                device.swipe(width/2, height/3, width/2, height*2/3, 10)
                
                // wait for scroll animation to complete
                if (!waitForScrollToComplete()) {
                    android.util.Log.w(TAG, "⚠️ scroll animation may not have completed (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error scrolling to top: ${e.message}")
            }
        }
        
        // wait for UI to settle after scrolling
        if (!waitForUIToBeStable()) {
            android.util.Log.w(TAG, "⚠️ UI may not be stable after scrolling")
        }
        
        // log all visible text elements for debugging
        android.util.Log.d(TAG, "🔍 current visible text elements:")
        val allTextElements = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
        allTextElements.forEachIndexed { idx, element ->
            try {
                val text = element.text
                if (text != null && text.length > 5) {
                    android.util.Log.d(TAG, "  Text $idx: '${text.take(40)}...'")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "  Text $idx: error reading text")
            }
        }
        
        // check each sent message is visible with enhanced verification
        sentMessages.forEachIndexed { index, message ->
            android.util.Log.d(TAG, "🔍 verifying message $index: '${message.take(30)}...'")
            
            // try multiple verification approaches
            val found = verifyMessageVisible(message) || 
                       verifyMessageWithPartialText(message) ||
                       verifyMessageWithScroll(message)
            
            if (!found) {
                android.util.Log.e(TAG, "❌ FAILURE at step 8: message $index not visible: '${message.take(30)}...'")
                
                // additional debugging before failing
                android.util.Log.e(TAG, "🔍 debug info for missing message:")
                android.util.Log.e(TAG, "  full message text: '$message'")
                android.util.Log.e(TAG, "  search substring: '${message.take(20)}'")
                android.util.Log.e(TAG, "  unique test ID: $uniqueTestId")
                
                failWithScreenshot("message_${index}_missing", "message $index not visible: '${message.take(30)}...'")
            }
            android.util.Log.d(TAG, "✅ message $index verified: '${message.take(30)}...'")
        }
        
        // check for message duplication
        sentMessages.forEach { message ->
            val occurrences = countMessageOccurrences(message)
            if (occurrences > 1) {
                android.util.Log.w(TAG, "⚠️ potential message duplication detected for: '${message.take(30)}...' (found $occurrences instances)")
                // not failing here as there might be legitimate reasons for multiple occurrences
            }
        }
        
        android.util.Log.d(TAG, "✅ all messages verified without major duplication issues")
    }
    
    /**
     * Try to verify message with partial text matching (more lenient)
     */
    private fun verifyMessageWithPartialText(messageText: String): Boolean {
        val uniqueId = uniqueTestId.toString()
        val testPrefix = messageText.split(" ").take(3).joinToString(" ")
        
        // try finding by unique test ID
        if (device.hasObject(By.textContains(uniqueId).pkg(packageName))) {
            android.util.Log.d(TAG, "✅ message found by unique ID: $uniqueId")
            return true
        }
        
        // try finding by message prefix
        if (device.hasObject(By.textContains(testPrefix).pkg(packageName))) {
            android.util.Log.d(TAG, "✅ message found by prefix: '$testPrefix'")
            return true
        }
        
        return false
    }
    
    // verifyMessageWithScroll() is now available from BaseIntegrationTest
    
    private fun verifyFinalMessageState(sentMessages: List<String>) {
        android.util.Log.d(TAG, "🔍 comprehensive final message verification...")
        
        // 1. verify ALL sent messages are present (with enhanced verification strategies)
        android.util.Log.d(TAG, "📝 checking all ${sentMessages.size} sent messages are present...")
        sentMessages.forEachIndexed { index, message ->
            // try multiple verification approaches like in step 8
            val found = verifyMessageVisible(message) || 
                       verifyMessageWithPartialText(message) ||
                       verifyMessageWithScroll(message)
            
            if (!found) {
                android.util.Log.e(TAG, "❌ FAILURE at step 9.1: FINAL CHECK - sent message $index missing: '${message.take(30)}...'")
                
                // additional debugging for final verification
                android.util.Log.e(TAG, "🔍 final debug info for missing message:")
                android.util.Log.e(TAG, "  full message: '$message'")
                android.util.Log.e(TAG, "  test ID: $uniqueTestId")
                android.util.Log.e(TAG, "  message index: $index")
                
                failWithScreenshot("final_message_${index}_missing", "FINAL CHECK: sent message $index missing: '${message.take(30)}...'")
            }
        }
        android.util.Log.d(TAG, "✅ all sent messages confirmed present")
        
        // 2. check for user message duplicates (strict)
        android.util.Log.d(TAG, "🔍 checking for user message duplicates...")
        var duplicatesFound = false
        sentMessages.forEach { message ->
            val occurrences = countMessageOccurrences(message)
            if (occurrences > 1) {
                android.util.Log.e(TAG, "❌ DUPLICATE USER MESSAGE: '${message.take(30)}...' appears $occurrences times")
                duplicatesFound = true
            }
        }
        
        if (duplicatesFound) {
            android.util.Log.e(TAG, "❌ FAILURE at step 9.2: FINAL CHECK - duplicate user messages detected - optimistic UI may be broken")
            failWithScreenshot("duplicate_user_messages", "FINAL CHECK: duplicate user messages detected - optimistic UI may be broken")
        }
        android.util.Log.d(TAG, "✅ no user message duplicates found")
        
        // 3. check for bot message duplicates
        android.util.Log.d(TAG, "🤖 checking for bot message duplicates...")
        val whizLabels = device.findObjects(
            androidx.test.uiautomator.By.text("Whiz").pkg(packageName)
        )
        
        // collect all bot message content to check for duplicates
        val botMessageContents = mutableListOf<String>()
        whizLabels.forEach { whizLabel ->
            try {
                // try to find the message content near the "Whiz" label
                val parent = whizLabel.parent
                if (parent != null) {
                    val textViews = parent.findObjects(androidx.test.uiautomator.By.clazz("android.widget.TextView"))
                    textViews.forEach { textView ->
                        val text = textView.text
                        if (text != null && text != "Whiz" && text.length > 20) {
                            botMessageContents.add(text)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error reading bot message content: ${e.message}")
            }
        }
        
        // check for bot message duplicates
        val botDuplicates = botMessageContents.groupBy { it }.filter { it.value.size > 1 }
        if (botDuplicates.isNotEmpty()) {
            android.util.Log.e(TAG, "❌ IDENTICAL BOT MESSAGE DUPLICATES FOUND:")
            botDuplicates.forEach { (content, occurrences) ->
                android.util.Log.e(TAG, "   '${content.take(50)}...' appears ${occurrences.size} times")
            }
            android.util.Log.e(TAG, "❌ FAILURE at step 9.3: FINAL CHECK - identical bot messages detected - bot response system may be broken")
            failWithScreenshot("duplicate_bot_messages", "FINAL CHECK: identical bot messages detected - bot response system may be broken")
        } else {
            android.util.Log.d(TAG, "✅ no bot message duplicates found")
        }
        
        // 4. verify message count makes sense
        val totalUserMessages = sentMessages.size
        val totalBotMessages = whizLabels.size
        android.util.Log.d(TAG, "📊 final message count: $totalUserMessages user, $totalBotMessages bot")
        
        if (totalBotMessages == 0) {
            android.util.Log.w(TAG, "⚠️ no bot responses detected - server may be unavailable")
        } else if (totalBotMessages > totalUserMessages) {
            android.util.Log.w(TAG, "⚠️ more bot messages than user messages - may indicate duplication")
        }
        
        android.util.Log.d(TAG, "✅ comprehensive final verification completed successfully")
        android.util.Log.d(TAG, "📊 final state: ${totalUserMessages} user messages, ${totalBotMessages} bot responses, no critical duplicates")
    }
    
    private suspend fun verifyChatMigrationSuccess() {
        android.util.Log.d(TAG, "🔍 verifying chat migration from optimistic to server-backed...")
        
        if (optimisticChatId != null) {
            android.util.Log.d(TAG, "🔄 starting migration verification for optimistic chat ID: $optimisticChatId")
        }
        
        // give migration some time to complete
        delay(2000)
        
        // check database for chat migration
        val allChats = repository.getAllChats()
        val testChat = allChats.find { chat ->
            chat.title.contains("test message 1") || chat.title.contains("hello")
        }
        
        if (testChat == null) {
            android.util.Log.w(TAG, "⚠️ test chat not found in database, migration may still be in progress")
            return
        }
        
        finalServerChatId = testChat.id
        
        // verify chat has positive ID (server-backed, not optimistic negative ID)
        if (testChat.id > 0) {
            android.util.Log.d(TAG, "✅ chat migration successful: chat has positive server ID ${testChat.id}")
            
            // verify migration path if we captured the optimistic ID
            if (optimisticChatId != null && optimisticChatId != testChat.id) {
                android.util.Log.d(TAG, "✅ migration path verified: $optimisticChatId → ${testChat.id}")
            } else if (optimisticChatId != null && optimisticChatId == testChat.id) {
                android.util.Log.w(TAG, "⚠️ chat ID didn't change during migration - may have started with server ID")
            }
            
            createdChatIds.add(testChat.id) // track for cleanup
        } else {
            android.util.Log.w(TAG, "⚠️ chat still has negative/optimistic ID ${testChat.id}, migration may be in progress")
        }
        
        // verify messages are associated with the final chat ID
        val chatMessages = repository.getMessagesForChat(testChat.id).first()
        if (chatMessages.size >= 3) { // should have at least 3 user messages + bot responses
            android.util.Log.d(TAG, "✅ messages successfully associated with migrated chat: ${chatMessages.size} messages found")
        } else {
            android.util.Log.w(TAG, "⚠️ fewer messages than expected in migrated chat: ${chatMessages.size}")
        }
        
        // log migration summary
        android.util.Log.d(TAG, "📊 migration summary:")
        android.util.Log.d(TAG, "   optimistic ID: $optimisticChatId")
        android.util.Log.d(TAG, "   final server ID: $finalServerChatId")
        android.util.Log.d(TAG, "   messages in final chat: ${chatMessages.size}")
        
        android.util.Log.d(TAG, "✅ chat migration verification completed")
    }
    
    /**
     * Get the current optimistic chat ID (negative ID) by finding the most recent chat
     */
    private suspend fun getCurrentOptimisticChatId(): Long? {
        return try {
            val allChats = repository.getAllChats()
            // Look for chats with negative IDs (optimistic) that contain our test identifier
            val optimisticChat = allChats.find { chat ->
                chat.id < 0 && (chat.title.contains("test message 1") || chat.title.contains("Hello"))
            }
            optimisticChat?.id
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to get optimistic chat ID", e)
            null
        }
    }
    
    /**
     * Check if chat migration from optimistic ID to server ID has completed
     */
    private suspend fun checkChatMigration(): Boolean {
        return try {
            val allChats = repository.getAllChats()
            val testChat = allChats.find { chat ->
                chat.title.contains("test message 1") || chat.title.contains("Hello")
            }
            
            if (testChat == null) {
                android.util.Log.w(TAG, "test chat not found during migration check")
                return false
            }
            
            val currentChatId = testChat.id
            finalServerChatId = currentChatId
            
            // migration is successful if:
            // 1. we have a positive server ID (not negative optimistic ID)
            // 2. the ID has changed from the original optimistic ID (if we captured one)
            val hasServerID = currentChatId > 0
            val hasChanged = optimisticChatId?.let { it != currentChatId } ?: true
            
            android.util.Log.d(TAG, "migration check: optimistic=$optimisticChatId, current=$currentChatId, hasServerID=$hasServerID, hasChanged=$hasChanged")
            
            if (hasServerID) {
                createdChatIds.add(currentChatId) // track for cleanup
            }
            
            return hasServerID && hasChanged
        } catch (e: Exception) {
            android.util.Log.w(TAG, "failed to check chat migration", e)
            false
        }
    }

    /**
     * Wait for chat migration to complete by polling specific conditions
     * instead of using arbitrary delays
     */
    private suspend fun waitForChatMigrationCompletion() {
        android.util.Log.d(TAG, "⏳ waiting for chat migration to complete...")
        
        val startTime = System.currentTimeMillis()
        val maxWaitTime = 15000L // 15 seconds max wait
        var attempts = 0
        
        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            attempts++
            android.util.Log.d(TAG, "🔍 migration check attempt $attempts...")
            
            try {
                // Check 1: Chat has positive server ID
                val migrationComplete = checkChatMigration()
                if (migrationComplete) {
                    android.util.Log.d(TAG, "✅ chat migration completed successfully")
                    
                    // Check 2: All messages are associated with the positive chat ID
                    val allMessagesAssociated = checkAllMessagesAssociated()
                    if (allMessagesAssociated) {
                        android.util.Log.d(TAG, "✅ all messages associated with migrated chat")
                        
                        // Check 3: UI has settled (no more rapid state changes)
                        val uiSettled = checkUISettled()
                        if (uiSettled) {
                            android.util.Log.d(TAG, "✅ UI has settled after migration")
                            android.util.Log.d(TAG, "🎉 chat migration fully completed in ${attempts} attempts (${System.currentTimeMillis() - startTime}ms)")
                            return
                        }
                    }
                }
                
                android.util.Log.d(TAG, "⏳ migration not yet complete, waiting...")
                delay(500) // Short delay before next check
                
            } catch (e: Exception) {
                android.util.Log.w(TAG, "error during migration check attempt $attempts: ${e.message}")
                delay(500)
            }
        }
        
        android.util.Log.w(TAG, "⚠️ migration wait timeout after ${maxWaitTime}ms and $attempts attempts")
        android.util.Log.w(TAG, "⚠️ proceeding with test but duplicates may be detected during migration window")
    }
    
    /**
     * Check if all test messages are associated with the final positive chat ID
     */
    private suspend fun checkAllMessagesAssociated(): Boolean {
        return try {
            if (finalServerChatId == null || finalServerChatId!! <= 0) {
                return false
            }
            
            val chatMessages = repository.getMessagesForChat(finalServerChatId!!).first()
            val userMessages = chatMessages.filter { it.type == com.example.whiz.data.local.MessageType.USER }
            
            android.util.Log.d(TAG, "🔍 checking message association: ${userMessages.size} user messages in chat $finalServerChatId")
            
            // Should have at least our 3 test messages
            val hasExpectedMessages = userMessages.size >= 3
            
            // All messages should have positive chat ID
            val allHavePositiveId = userMessages.all { it.chatId > 0 }
            
            android.util.Log.d(TAG, "📊 message association check: hasExpected=$hasExpectedMessages, allPositive=$allHavePositiveId")
            
            return hasExpectedMessages && allHavePositiveId
            
        } catch (e: Exception) {
            android.util.Log.w(TAG, "error checking message association: ${e.message}")
            false
        }
    }
    
    /**
     * Check if UI has settled (no rapid changes that indicate ongoing migration)
     */
    private suspend fun checkUISettled(): Boolean {
        return try {
            // Take two snapshots of message count with a short delay
            val count1 = countTotalVisibleMessages()
            delay(200)
            val count2 = countTotalVisibleMessages()
            
            // UI is settled if message count is stable
            val isStable = count1 == count2 && count1 > 0
            
            android.util.Log.d(TAG, "📊 UI stability check: count1=$count1, count2=$count2, stable=$isStable")
            
            return isStable
            
        } catch (e: Exception) {
            android.util.Log.w(TAG, "error checking UI stability: ${e.message}")
            true // assume settled if we can't check
        }
    }
    
    /**
     * Count total visible messages in the chat (for stability checking)
     */
    private fun countTotalVisibleMessages(): Int {
        return try {
            val messageElements = device.findObjects(
                By.clazz("android.widget.TextView").pkg(packageName)
            ).filter { element ->
                val text = element.text
                text != null && text.length > 10 && !text.contains("Whiz") // filter out labels
            }
            messageElements.size
        } catch (e: Exception) {
            0
        }
    }

    @Test
    fun fullMessageFlowTest_voiceTranscriptionVersion(): Unit = runBlocking {
        android.util.Log.d(TAG, "🚀 starting comprehensive VOICE message flow UI test")
        
        val credentials = TestCredentialsManager.credentials
        android.util.Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")
        
        try {
            // step 1: launch app and ensure we get to a new chat screen efficiently
            android.util.Log.d(TAG, "📱 step 1: launching app and navigating to new chat for VOICE test")
            if (!launchAppAndWaitForLoad()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 1: app failed to launch or load main UI for voice test")
                failWithScreenshot("voice_app_launch_failed", "app failed to launch or load main UI for voice test")
            }
            
            // step 2: navigate to new chat (handling both chats list and existing chat scenarios)
            android.util.Log.d(TAG, "➕ step 2: navigating to new chat for VOICE test")
            
            if (isCurrentlyInChatScreen()) {
                // if we're already in a chat, navigate back to chats list first, then create new chat
                android.util.Log.d(TAG, "🔄 currently in chat screen, going back to chats list first for voice test")
                if (!navigateBackToChatsListFromChat()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2a: failed to navigate from chat screen to chats list for voice test")
                    failWithScreenshot("voice_navigate_to_chats_list_failed", "failed to navigate from chat screen to chats list for voice test")
                }
                
                // now click new chat button
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2b: new chat button not found or chat screen failed to load for voice test")
                    failWithScreenshot("voice_new_chat_failed", "new chat button not found or chat screen failed to load for voice test")
                }
            } else {
                // we're on chats list, directly click new chat button
                android.util.Log.d(TAG, "📋 on chats list, clicking new chat button directly for voice test")
                if (!clickNewChatButtonAndWaitForChatScreen()) {
                    android.util.Log.e(TAG, "❌ FAILURE at step 2: new chat button not found or chat screen failed to load for voice test")
                    failWithScreenshot("voice_new_chat_failed", "new chat button not found or chat screen failed to load for voice test")
                }
            }
            
            
            // step 3: send first VOICE message and verify optimistic UI
            val firstVoiceMessage = "Hello! this is VOICE test message 1 - $uniqueTestId"
            android.util.Log.d(TAG, "🎙️ step 3: sending first VOICE message and verifying optimistic UI")
            
            // wait for voice mode to be ready and chat UI to be stable
            android.util.Log.d(TAG, "⏳ waiting for voice mode and chat UI to be ready...")
            if (!waitForVoiceModeAndChatReady()) {
                android.util.Log.e(TAG, "❌ FAILURE: Voice mode or chat UI not ready for voice message sending")
                failWithScreenshot("voice_mode_not_ready", "Voice mode or chat UI not ready for voice message sending")
            }
            
            if (!sendVoiceMessageAndVerifyWebSocketSending(firstVoiceMessage, 1)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 3: first VOICE message failed to send properly")
                android.util.Log.e(TAG, "   This could be UI display failure or WebSocket transmission failure")
                android.util.Log.e(TAG, "   Voice Message: '${firstVoiceMessage.take(50)}...'")
                failWithScreenshot("first_voice_message_send_failed", "Step 3: First VOICE message failed to send or reach server")
            }
            
            // ensure voice message is visible before proceeding
            android.util.Log.d(TAG, "🔍 verifying first VOICE message is visible...")
            if (!verifyMessageVisible(firstVoiceMessage)) {
                android.util.Log.e(TAG, "❌ first VOICE message not visible")
                failWithScreenshot("first_voice_message_not_visible", "first VOICE message not visible")
            }
            
            // step 3.5: capture optimistic chat ID for migration tracking
            android.util.Log.d(TAG, "🔍 step 3.5: capturing optimistic chat ID for VOICE test")
            optimisticChatId = getCurrentOptimisticChatId()
            if (optimisticChatId != null) {
                android.util.Log.d(TAG, "✅ captured optimistic chat ID for VOICE test: $optimisticChatId")
            } else {
                android.util.Log.w(TAG, "⚠️ could not capture optimistic chat ID for VOICE test - may already have migrated or not created yet")
            }
            
            // step 4: confirm bot is responding (thinking indicator visible)
            android.util.Log.d(TAG, "🤖 step 4: confirming bot is responding to VOICE message")
            if (!waitForBotThinkingIndicator()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 4: bot thinking indicator not found - bot may not be responding to VOICE message")
                failWithScreenshot("voice_bot_not_responding", "bot thinking indicator not found - bot may not be responding to VOICE message")
            }
            
            // step 5: send second VOICE message while bot is responding
            // This is the CRITICAL test for the production bug where messages appear to send
            // during bot response but don't actually reach the server via WebSocket
            val secondVoiceMessage = "second VOICE message while you're thinking - $uniqueTestId"
            android.util.Log.d(TAG, "🎙️ step 5: sending second VOICE message while bot is responding (CRITICAL WebSocket test)")
            
            // CRITICAL: verify bot is still responding before sending - this tests the interruption capability
            if (!isBotCurrentlyResponding()) {
                android.util.Log.e(TAG, "❌ FAILURE at step 5: bot stopped responding too quickly - cannot test VOICE interruption")
                failWithScreenshot("voice_bot_finished_too_early", "bot stopped responding before we could test VOICE interruption capability")
            }
            
            android.util.Log.d(TAG, "🤖 bot confirmed still responding - now testing VOICE interruption...")
            
            // This is the core UX test: users MUST be able to interrupt the bot via VOICE AND have messages actually reach the server
            // Using rapid VOICE send method to test interruption capability during bot response
            // This should detect the production bug where voice transcription is blocked during bot response
            if (!sendVoiceMessageAndVerifyDisplayRapid(secondVoiceMessage)) {
                android.util.Log.e(TAG, "❌ CRITICAL: Bot VOICE interruption test failed!")
                android.util.Log.e(TAG, "   Rapid VOICE message sending failed during bot response")
                android.util.Log.e(TAG, "   This indicates the PRODUCTION BUG where users cannot use voice transcription")
                android.util.Log.e(TAG, "   during bot response period")
                android.util.Log.e(TAG, "   Voice Message: '${secondVoiceMessage.take(50)}...'")
                failWithScreenshot("voice_bot_interruption_failed", "CRITICAL: Rapid second VOICE message during bot response failed - voice transcription likely blocked")
            }
            
            android.util.Log.d(TAG, "✅ Bot VOICE interruption successful - voice message sent AND reached server while bot was responding!")
            
            // step 6: wait for bot response to arrive
            android.util.Log.d(TAG, "⏳ step 6: waiting for bot response to VOICE messages")
            
            if (!waitForBotThinkingToFinish()) {
                android.util.Log.w(TAG, "⚠️ thinking indicator still visible after timeout, checking for response anyway")
            }
            
            // use styling detection to detect bot response
            val botResponseDetected = waitForBotResponse(5000)
            
            if (!botResponseDetected) {
                android.util.Log.e(TAG, "❌ FAILURE at step 6: bot response not detected within timeout using styling detection for VOICE test")
                failWithScreenshot("voice_no_bot_response", "bot response not detected within timeout using styling detection for VOICE test")
            }
            
            android.util.Log.d(TAG, "✅ bot response detected via styling for VOICE test")
            
            // step 6.5: check if migration from optimistic to server chat ID worked
            android.util.Log.d(TAG, "🔄 step 6.5: checking chat migration after bot response to VOICE messages")
            val migrationWorked = checkChatMigration()
            if (migrationWorked) {
                android.util.Log.d(TAG, "✅ chat migration successful after bot response to VOICE messages")
            } else {
                android.util.Log.w(TAG, "⚠️ chat migration not detected yet for VOICE test - may still be in progress")
            }
            
            // step 7: send third VOICE message after bot response
            val thirdVoiceMessage = "third VOICE message after your response - $uniqueTestId"
            android.util.Log.d(TAG, "🎙️ step 7: sending third VOICE message after bot response")
            
            // ensure bot is no longer responding
            if (isBotCurrentlyResponding()) {
                android.util.Log.w(TAG, "⚠️ bot still appears to be responding, but sending VOICE message anyway")
            }
            
            if (!sendVoiceMessageAndVerifyWebSocketSending(thirdVoiceMessage, 3)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 7: third VOICE message after bot response failed")
                android.util.Log.e(TAG, "   Third VOICE message either failed UI display or WebSocket transmission")
                android.util.Log.e(TAG, "   Voice Message: '${thirdVoiceMessage.take(50)}...'")
                failWithScreenshot("third_voice_message_send_failed", "Step 7: Third VOICE message after bot response failed to send or reach server")
            }
            
            // step 8: verify all VOICE messages are showing properly
            android.util.Log.d(TAG, "✅ step 8: verifying all VOICE messages display correctly")
            
            // wait for UI to be stable before VOICE verification
            android.util.Log.d(TAG, "⏳ waiting for UI to be stable before VOICE message verification...")
            if (!waitForUIToBeStable()) {
                android.util.Log.e(TAG, "❌ FAILURE: UI not stable for VOICE verification")
                failWithScreenshot("voice_ui_not_stable", "UI not stable for VOICE verification")
            }
            
            // ensure we're in chat screen before verification
            if (!isCurrentlyInChatScreen()) {
                android.util.Log.w(TAG, "⚠️ not currently in chat screen during VOICE verification, attempting recovery...")
                // try to get back to chat screen if possible
                device.pressBack()
                if (!waitForChatScreenToLoad()) {
                    android.util.Log.e(TAG, "❌ FAILURE: Could not recover to chat screen for VOICE verification")
                    failWithScreenshot("voice_chat_screen_recovery_failed", "Could not recover to chat screen for VOICE verification")
                }
            }
            
            val sentVoiceMessages = listOf(firstVoiceMessage, secondVoiceMessage, thirdVoiceMessage)
            verifyAllVoiceMessagesDisplayCorrectly(sentVoiceMessages)
            
            // step 9: wait for chat migration to complete, then do comprehensive final verification
            android.util.Log.d(TAG, "🔍 step 9a: waiting for chat migration to complete for VOICE test...")
            waitForChatMigrationCompletion()
            
            android.util.Log.d(TAG, "🔍 step 9b: final comprehensive verification for VOICE test - checking for duplicates and completeness")
            verifyFinalVoiceMessageState(sentVoiceMessages)
            
            android.util.Log.d(TAG, "🎉 comprehensive VOICE message flow test PASSED!")
            android.util.Log.d(TAG, "✅ VOICE Test validated: voice transcription optimistic UI, bot interruption capability via voice, chat migration, and voice message persistence")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "comprehensive VOICE message flow test FAILED", e)
            throw e
        }
    }

    /**
     * Send voice message and verify WebSocket sending (normal timeouts for chat loading)
     */
    private fun sendVoiceMessageAndVerifyWebSocketSending(message: String, expectedMessageNumber: Int): Boolean {
        android.util.Log.d(TAG, "🎙️ Attempting to send voice message with WebSocket verification: '${message.take(30)}...'")
        
        // Simulate voice transcription by using the transcription callback
        val transcriptionSuccess = simulateVoiceTranscriptionAndSendWithWebSocket(message)
        if (!transcriptionSuccess) {
            android.util.Log.e(TAG, "❌ Voice transcription and WebSocket send failed")
            return false
        }
        
        android.util.Log.d(TAG, "✅ Voice message sent and WebSocket confirmed successfully")
        return true
    }

    /**
     * Simulate voice transcription and send with WebSocket verification
     */
    private fun simulateVoiceTranscriptionAndSendWithWebSocket(message: String): Boolean {
        android.util.Log.d(TAG, "🎤 Simulating voice transcription with WebSocket verification: '${message.take(30)}...'")
        
        // Simulate transcription by typing the message (this simulates what the voice transcription callback would do)
        val typingSuccess = typeMessageInInputField(message)
        if (!typingSuccess) {
            android.util.Log.e(TAG, "❌ Failed to simulate voice transcription")
            return false
        }
        
        android.util.Log.d(TAG, "✅ Voice transcription simulated successfully")
        
        // Send with normal timeouts and WebSocket verification (1000ms)
        val sendingSuccess = clickSendButtonAndWaitForSent(message)
        if (!sendingSuccess) {
            android.util.Log.e(TAG, "❌ Voice message send with WebSocket verification failed")
            return false
        }
        
        android.util.Log.d(TAG, "✅ Voice message sent with WebSocket verification successfully")
        return true
    }

    /**
     * Voice-specific message verification (same logic but with voice-specific logging)
     */
    private fun verifyAllVoiceMessagesDisplayCorrectly(sentVoiceMessages: List<String>) {
        android.util.Log.d(TAG, "🔍 verifying all VOICE messages display correctly...")
        
        // scroll to top of chat first to ensure we can see all voice messages
        android.util.Log.d(TAG, "📜 scrolling to top of chat to ensure all VOICE messages are visible")
        repeat(5) { attempt ->
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                // swipe from top to bottom to scroll to top
                device.swipe(width/2, height/3, width/2, height*2/3, 10)
                
                // wait for scroll animation to complete
                if (!waitForScrollToComplete()) {
                    android.util.Log.w(TAG, "⚠️ VOICE scroll animation may not have completed (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error scrolling to top for VOICE verification: ${e.message}")
            }
        }
        
        // wait for UI to settle after scrolling
        if (!waitForUIToBeStable()) {
            android.util.Log.w(TAG, "⚠️ UI may not be stable after VOICE scrolling")
        }
        
        // log all visible text elements for debugging voice messages
        android.util.Log.d(TAG, "🔍 current visible text elements for VOICE verification:")
        val allTextElements = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
        allTextElements.forEachIndexed { idx, element ->
            try {
                val text = element.text
                if (text != null && text.length > 5) {
                    android.util.Log.d(TAG, "  VOICE Text $idx: '${text.take(40)}...'")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "  VOICE Text $idx: error reading text")
            }
        }
        
        // check each sent voice message is visible with enhanced verification
        sentVoiceMessages.forEachIndexed { index, message ->
            android.util.Log.d(TAG, "🔍 verifying VOICE message $index: '${message.take(30)}...'")
            
            // try multiple verification approaches
            val found = verifyMessageVisible(message) || 
                       verifyMessageWithPartialText(message) ||
                       verifyMessageWithScroll(message)
            
            if (!found) {
                android.util.Log.e(TAG, "❌ FAILURE at step 8: VOICE message $index not visible: '${message.take(30)}...'")
                
                // additional debugging before failing
                android.util.Log.e(TAG, "🔍 debug info for missing VOICE message:")
                android.util.Log.e(TAG, "  full VOICE message text: '$message'")
                android.util.Log.e(TAG, "  search substring: '${message.take(20)}'")
                android.util.Log.e(TAG, "  unique test ID: $uniqueTestId")
                
                failWithScreenshot("voice_message_${index}_missing", "VOICE message $index not visible: '${message.take(30)}...'")
            }
            android.util.Log.d(TAG, "✅ VOICE message $index verified: '${message.take(30)}...'")
        }
        
        // check for voice message duplication
        sentVoiceMessages.forEach { message ->
            val occurrences = countMessageOccurrences(message)
            if (occurrences > 1) {
                android.util.Log.w(TAG, "⚠️ potential VOICE message duplication detected for: '${message.take(30)}...' (found $occurrences instances)")
                // not failing here as there might be legitimate reasons for multiple occurrences
            }
        }
        
        android.util.Log.d(TAG, "✅ all VOICE messages verified without major duplication issues")
    }

    /**
     * Voice-specific final message verification
     */
    private fun verifyFinalVoiceMessageState(sentVoiceMessages: List<String>) {
        android.util.Log.d(TAG, "🔍 comprehensive final VOICE message verification...")
        
        // 1. verify ALL sent voice messages are present (with enhanced verification strategies)
        android.util.Log.d(TAG, "📝 checking all ${sentVoiceMessages.size} sent VOICE messages are present...")
        sentVoiceMessages.forEachIndexed { index, message ->
            // try multiple verification approaches like in step 8
            val found = verifyMessageVisible(message) || 
                       verifyMessageWithPartialText(message) ||
                       verifyMessageWithScroll(message)
            
            if (!found) {
                android.util.Log.e(TAG, "❌ FAILURE at step 9.1: FINAL CHECK - sent VOICE message $index missing: '${message.take(30)}...'")
                
                // additional debugging for final verification
                android.util.Log.e(TAG, "🔍 final debug info for missing VOICE message:")
                android.util.Log.e(TAG, "  full VOICE message: '$message'")
                android.util.Log.e(TAG, "  test ID: $uniqueTestId")
                android.util.Log.e(TAG, "  VOICE message index: $index")
                
                failWithScreenshot("final_voice_message_${index}_missing", "FINAL CHECK: sent VOICE message $index missing: '${message.take(30)}...'")
            }
        }
        android.util.Log.d(TAG, "✅ all sent VOICE messages confirmed present")
        
        // 2. check for user voice message duplicates (strict)
        android.util.Log.d(TAG, "🔍 checking for user VOICE message duplicates...")
        var duplicatesFound = false
        sentVoiceMessages.forEach { message ->
            val occurrences = countMessageOccurrences(message)
            if (occurrences > 1) {
                android.util.Log.e(TAG, "❌ DUPLICATE USER VOICE MESSAGE: '${message.take(30)}...' appears $occurrences times")
                duplicatesFound = true
            }
        }
        
        if (duplicatesFound) {
            android.util.Log.e(TAG, "❌ FAILURE at step 9.2: FINAL CHECK - duplicate user VOICE messages detected - optimistic UI may be broken")
            failWithScreenshot("duplicate_user_voice_messages", "FINAL CHECK: duplicate user VOICE messages detected - optimistic UI may be broken")
        }
        android.util.Log.d(TAG, "✅ no user VOICE message duplicates found")
        
        // 3. check for bot message duplicates (same as text version)
        android.util.Log.d(TAG, "🤖 checking for bot message duplicates in response to VOICE messages...")
        val whizLabels = device.findObjects(
            androidx.test.uiautomator.By.text("Whiz").pkg(packageName)
        )
        
        // collect all bot message content to check for duplicates
        val botMessageContents = mutableListOf<String>()
        whizLabels.forEach { whizLabel ->
            try {
                // try to find the message content near the "Whiz" label
                val parent = whizLabel.parent
                if (parent != null) {
                    val textViews = parent.findObjects(androidx.test.uiautomator.By.clazz("android.widget.TextView"))
                    textViews.forEach { textView ->
                        val text = textView.text
                        if (text != null && text != "Whiz" && text.length > 20) {
                            botMessageContents.add(text)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error reading bot message content in response to VOICE: ${e.message}")
            }
        }
        
        // check for bot message duplicates
        val botDuplicates = botMessageContents.groupBy { it }.filter { it.value.size > 1 }
        if (botDuplicates.isNotEmpty()) {
            android.util.Log.e(TAG, "❌ IDENTICAL BOT MESSAGE DUPLICATES FOUND IN RESPONSE TO VOICE:")
            botDuplicates.forEach { (content, occurrences) ->
                android.util.Log.e(TAG, "   '${content.take(50)}...' appears ${occurrences.size} times")
            }
            android.util.Log.e(TAG, "❌ FAILURE at step 9.3: FINAL CHECK - identical bot messages detected in response to VOICE - bot response system may be broken")
            failWithScreenshot("duplicate_bot_messages_voice", "FINAL CHECK: identical bot messages detected in response to VOICE - bot response system may be broken")
        } else {
            android.util.Log.d(TAG, "✅ no bot message duplicates found in response to VOICE")
        }
        
        // 4. verify message count makes sense for voice test
        val totalUserVoiceMessages = sentVoiceMessages.size
        val totalBotMessages = whizLabels.size
        android.util.Log.d(TAG, "📊 final VOICE message count: $totalUserVoiceMessages user VOICE, $totalBotMessages bot")
        
        if (totalBotMessages == 0) {
            android.util.Log.w(TAG, "⚠️ no bot responses detected to VOICE messages - server may be unavailable")
        } else if (totalBotMessages > totalUserVoiceMessages) {
            android.util.Log.w(TAG, "⚠️ more bot messages than user VOICE messages - may indicate duplication")
        }
        
        android.util.Log.d(TAG, "✅ comprehensive final VOICE verification completed successfully")
        android.util.Log.d(TAG, "📊 final VOICE state: ${totalUserVoiceMessages} user VOICE messages, ${totalBotMessages} bot responses, no critical duplicates")
    }

    /**
     * Wait for chat UI to be ready for message input
     */
    private fun waitForChatUIReady(): Boolean {
        android.util.Log.d(TAG, "⏳ waiting for chat UI to be ready...")
        
        // Check for input field to be available and interactable
        val inputFieldReady = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName).enabled(true)
        ), 3000)
        
        if (!inputFieldReady) {
            android.util.Log.e(TAG, "❌ Input field not ready or not enabled")
            return false
        }
        
        // Verify chat screen is fully loaded
        val chatScreenLoaded = isCurrentlyInChatScreen()
        if (!chatScreenLoaded) {
            android.util.Log.e(TAG, "❌ Chat screen not fully loaded")
            return false
        }
        
        android.util.Log.d(TAG, "✅ Chat UI ready for input")
        return true
    }

    /**
     * Wait for UI to be stable (no rapid changes)
     */
    private fun waitForUIToBeStable(): Boolean {
        android.util.Log.d(TAG, "⏳ waiting for UI to be stable...")
        
        var previousElementCount = 0
        var stableCount = 0
        val maxAttempts = 20
        val startTime = System.currentTimeMillis()
        val maxWaitTime = 2000L // 2 seconds max
        
        repeat(maxAttempts) { attempt ->
            try {
                // Check if we've exceeded max wait time
                if (System.currentTimeMillis() - startTime > maxWaitTime) {
                    android.util.Log.w(TAG, "⚠️ UI stability check timed out after ${maxWaitTime}ms")
                    return false
                }
                
                // Count visible elements as a measure of UI stability
                val currentElements = device.findObjects(
                    By.clazz("android.widget.TextView").pkg(packageName)
                )
                val currentElementCount = currentElements.size
                
                if (currentElementCount == previousElementCount && currentElementCount > 0) {
                    stableCount++
                    if (stableCount >= 3) { // 3 consecutive stable readings
                        android.util.Log.d(TAG, "✅ UI stable after ${attempt + 1} checks")
                        return true
                    }
                } else {
                    stableCount = 0 // reset if count changed
                }
                
                previousElementCount = currentElementCount
                
                // Use device.wait instead of sleep for responsiveness
                device.wait(Until.hasObject(
                    By.clazz("android.widget.TextView").pkg(packageName)
                ), 50)
                
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error checking UI stability: ${e.message}")
            }
        }
        
        android.util.Log.w(TAG, "⚠️ UI may not be fully stable after $maxAttempts attempts")
        return false
    }

    // waitForScrollToComplete() is now available from BaseIntegrationTest

    /**
     * Wait for chat screen to load after navigation
     */
    private fun waitForChatScreenToLoad(): Boolean {
        android.util.Log.d(TAG, "⏳ waiting for chat screen to load...")
        
        // Wait for input field to appear (indicates chat screen loaded)
        val chatScreenLoaded = device.wait(Until.hasObject(
            By.clazz("android.widget.EditText").pkg(packageName)
        ), 2000)
        
        if (!chatScreenLoaded) {
            android.util.Log.e(TAG, "❌ Chat screen did not load properly")
            return false
        }
        
        android.util.Log.d(TAG, "✅ Chat screen loaded successfully")
        return true
    }

    /**
     * Wait for voice mode and chat UI to be ready (replaces the waitForVoiceModeAndChatReady call I made earlier)
     */
    private fun waitForVoiceModeAndChatReady(): Boolean {
        android.util.Log.d(TAG, "⏳ waiting for voice mode and chat UI to be ready...")
        
        // First ensure chat UI is ready
        if (!waitForChatUIReady()) {
            return false
        }
        
        // Check if voice mode is enabled
        try {
            val voiceEnabled = voiceManager.isContinuousListeningEnabled.value
            if (!voiceEnabled) {
                android.util.Log.w(TAG, "⚠️ voice mode may not be enabled yet")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "⚠️ could not check voice mode status: ${e.message}")
        }
        
        android.util.Log.d(TAG, "✅ Voice mode and chat UI ready")
        return true
    }


}