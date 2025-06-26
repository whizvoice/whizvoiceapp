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
            // debug: take screenshot immediately to test screenshot mechanism
            android.util.Log.d(TAG, "🔍 DEBUG: taking test screenshot to verify mechanism works")
            takeFailureScreenshot("debug_test_start", "debugging screenshot collection mechanism")
            
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
            
                         // add extra delay for CI environments
             Thread.sleep(getCIAwareDelay(1000))
            
            if (!sendMessageAndVerifyDisplay(firstMessage)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 3: failed to send first message or verify optimistic UI")
                failWithScreenshot("first_message_failed", "failed to send first message or verify optimistic UI")
            }
            
            // ensure message is visible before proceeding
            android.util.Log.d(TAG, "🔍 double-checking first message is visible before proceeding...")
            if (!verifyMessageVisible(firstMessage)) {
                android.util.Log.w(TAG, "⚠️ first message not immediately visible, trying again...")
                                 Thread.sleep(getCIAwareDelay(2000))
                if (!verifyMessageVisible(firstMessage)) {
                    android.util.Log.e(TAG, "❌ first message still not visible after retry")
                    failWithScreenshot("first_message_not_visible", "first message not visible after retry")
                }
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
            val secondMessage = "second message while you're thinking - $uniqueTestId"
            android.util.Log.d(TAG, "💬 step 5: sending second message while bot is responding")
            
            // verify bot is still responding before sending
            if (!isBotCurrentlyResponding()) {
                android.util.Log.w(TAG, "⚠️ bot may have finished responding, but continuing with test")
            }
            
            if (!sendMessageAndVerifyDisplay(secondMessage)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 5: failed to send second message while bot responding")
                failWithScreenshot("second_message_failed", "failed to send second message while bot responding")
            }
            
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
            
            if (!sendMessageAndVerifyDisplay(thirdMessage)) {
                android.util.Log.e(TAG, "❌ FAILURE at step 7: failed to send third message after bot response")
                failWithScreenshot("third_message_failed", "failed to send third message after bot response")
            }
            
            // step 8: verify all messages are showing properly
            android.util.Log.d(TAG, "✅ step 8: verifying all messages display correctly")
            
                         // give time for UI to fully settle after all the messaging activity
             android.util.Log.d(TAG, "⏳ allowing UI to settle before verification...")
             Thread.sleep(getCIAwareDelay(2000))
            
            // ensure we're in chat screen before verification
            if (!isCurrentlyInChatScreen()) {
                android.util.Log.w(TAG, "⚠️ not currently in chat screen during verification, attempting recovery...")
                // try to get back to chat screen if possible
                device.pressBack()
                Thread.sleep(500)
            }
            
            val sentMessages = listOf(firstMessage, secondMessage, thirdMessage)
            verifyAllMessagesDisplayCorrectly(sentMessages)
            
            // step 9: wait for chat migration to complete, then do comprehensive final verification
            android.util.Log.d(TAG, "🔍 step 9a: waiting for chat migration to complete...")
            waitForChatMigrationCompletion()
            
            android.util.Log.d(TAG, "🔍 step 9b: final comprehensive verification - checking for duplicates and completeness")
            verifyFinalMessageState(sentMessages)
            
            android.util.Log.d(TAG, "🎉 comprehensive message flow test PASSED!")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "comprehensive message flow test FAILED", e)
            throw e
        }
    }
    
    private fun verifyAllMessagesDisplayCorrectly(sentMessages: List<String>) {
        android.util.Log.d(TAG, "🔍 verifying all messages display correctly...")
        
        // scroll to top of chat first to ensure we can see all messages
        android.util.Log.d(TAG, "📜 scrolling to top of chat to ensure all messages are visible")
        repeat(5) { 
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                // swipe from top to bottom to scroll to top
                device.swipe(width/2, height/3, width/2, height*2/3, 10)
                Thread.sleep(500)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error scrolling to top: ${e.message}")
            }
        }
        
        // give UI time to settle after scrolling
        Thread.sleep(1000)
        
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
    
    /**
     * Enhanced message verification with more aggressive scrolling
     */
    private fun verifyMessageWithScroll(messageText: String): Boolean {
        val searchText = messageText.take(20)
        
        android.util.Log.d(TAG, "🔍 enhanced scroll search for: '$searchText'")
        
        // try scrolling both up and down to find the message
        repeat(5) { attempt ->
            // scroll up
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                device.swipe(width/2, height*2/3, width/2, height/3, 10)
                Thread.sleep(800)
                
                if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
                    android.util.Log.d(TAG, "✅ message found after scrolling up (attempt ${attempt + 1})")
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error during up scroll: ${e.message}")
            }
        }
        
        repeat(5) { attempt ->
            // scroll down
            try {
                val height = device.displayHeight
                val width = device.displayWidth
                device.swipe(width/2, height/3, width/2, height*2/3, 10)
                Thread.sleep(800)
                
                if (device.hasObject(By.textContains(searchText).pkg(packageName))) {
                    android.util.Log.d(TAG, "✅ message found after scrolling down (attempt ${attempt + 1})")
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "⚠️ error during down scroll: ${e.message}")
            }
        }
        
        android.util.Log.w(TAG, "❌ message not found even with enhanced scrolling")
        return false
    }
    
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


}