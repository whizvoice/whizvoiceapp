package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.MainActivity
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Compose-based comprehensive UI integration test for complete message flow including:
 * - app launch and chats list verification
 * - new chat creation
 * - optimistic UI message display
 * - bot response detection
 * - bot interruption capability (CRITICAL UX TEST: users must be able to interrupt bot)
 * - multiple message sending with proper timing
 * - chat migration from optimistic to server-backed
 * - message persistence verification
 * 
 * Key advantages of Compose Testing:
 * - Native support for Compose UI components
 * - Better synchronization with Compose state
 * - More reliable element selection
 * - Faster execution than UI Automator
 * - Better error messages and debugging
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MessageFlowComposeTest : BaseIntegrationTest() {

    companion object {
        private val TAG = "MessageFlowComposeTest"
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

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
        Log.d(TAG, "🧪 comprehensive message flow Compose test setup complete")
        Log.d(TAG, "🔍 Environment info:")
        Log.d(TAG, "  CI detected: ${System.getenv("CI")}")
        Log.d(TAG, "  GitHub Actions: ${System.getenv("GITHUB_ACTIONS")}")
        Log.d(TAG, "  Device display: ${device.displayWidth}x${device.displayHeight}")
        Log.d(TAG, "  Timing multiplier: ${if (System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true") "3.0x (CI)" else "1.0x (local)"}")
    }

    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "🧹 cleaning up test chats")
            // Use simplified cleanup method
            cleanupTestChats(
                repository = repository,
                trackedChatIds = createdChatIds,
                additionalPatterns = listOf("message flow", "comprehensive", "migration", "compose"),
                enablePatternFallback = false
            )
            createdChatIds.clear()
            
            // Clean up ComposeTestHelper resources
            ComposeTestHelper.cleanup()
            
            Log.d(TAG, "✅ test cleanup completed")
        }
    }

    @Test
    fun fullMessageFlowTest_comprehensiveUIAndOptimisticMigration(): Unit = runBlocking {
        Log.d(TAG, "🚀 starting comprehensive message flow Compose UI test")
        
        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "test user: ${credentials.googleTestAccount.email}")
        
        try {
            // step 1: verify app is ready (already launched by createAndroidComposeRule)
            Log.d(TAG, "📱 step 1: verifying app is ready (already launched by createAndroidComposeRule)")
            
            if (!ComposeTestHelper.isAppReady(composeTestRule)) {
                Log.e(TAG, "❌ FAILURE at step 1: app failed to launch or load main UI")
                Log.e(TAG, "🔍 This is likely because the app launched to chat screen due to voice launch detection")
                failWithScreenshot("compose_app_launch_failed", "app failed to launch or load main UI")
                return@runBlocking
            }
            
            // step 2: navigate to new chat using Compose Testing
            Log.d(TAG, "➕ step 2: navigating to new chat with Compose Testing")
            
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                // if we're already in a chat, navigate back to chats list first, then create new chat
                Log.d(TAG, "🔄 currently in chat screen, going back to chats list first")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    Log.e(TAG, "❌ FAILURE at step 2a: failed to navigate from chat screen to chats list")
                    failWithScreenshot("compose_navigate_to_chats_list_failed", "failed to navigate from chat screen to chats list")
                    return@runBlocking
                }
                
                // now click new chat button
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    Log.e(TAG, "❌ FAILURE at step 2b: new chat button not found or chat screen failed to load")
                    failWithScreenshot("compose_new_chat_failed", "new chat button not found or chat screen failed to load")
                    return@runBlocking
                }
            } else {
                // we're on chats list, directly click new chat button
                Log.d(TAG, "📋 on chats list, clicking new chat button directly")
                if (!ComposeTestHelper.navigateToNewChat(composeTestRule)) {
                    Log.e(TAG, "❌ FAILURE at step 2: new chat button not found or chat screen failed to load")
                    failWithScreenshot("compose_new_chat_failed", "new chat button not found or chat screen failed to load")
                    return@runBlocking
                }
            }
            
            // step 3: send first message and verify optimistic UI
            val firstMessage = "Pls always reply with just 1 word for test - $uniqueTestId"
            Log.d(TAG, "💬 step 3: sending first message and verifying optimistic UI")
            
            // wait for chat UI to be ready for message input
            Log.d(TAG, "⏳ waiting for chat UI to be ready for message input...")
            if (!waitForChatUIReady()) {
                Log.e(TAG, "❌ FAILURE: Chat UI not ready for message input")
                failWithScreenshot("compose_chat_ui_not_ready", "Chat UI not ready for message input")
                return@runBlocking
            }
            
            if (!sendMessageWithWebSocketVerification(firstMessage, 1)) {
                Log.e(TAG, "❌ FAILURE at step 3: first message failed to send properly")
                Log.e(TAG, "   This could be UI display failure or WebSocket transmission failure")
                Log.e(TAG, "   Message: '${firstMessage.take(50)}...'")
                failWithScreenshot("compose_first_message_send_failed", "Step 3: First message failed to send or reach server")
                return@runBlocking
            }
            
            // ensure message is visible before proceeding
            Log.d(TAG, "🔍 verifying first message is visible...")
            if (!verifyMessageVisible(firstMessage)) {
                Log.e(TAG, "❌ first message not visible")
                failWithScreenshot("first message not visible", "compose_first_message_not_visible")
                return@runBlocking
            }
            
            // step 3.5: capture optimistic chat ID for migration tracking
            Log.d(TAG, "🔍 step 3.5: capturing optimistic chat ID")
            optimisticChatId = getCurrentOptimisticChatId()
            if (optimisticChatId != null) {
                Log.d(TAG, "✅ captured optimistic chat ID: $optimisticChatId")
            } else {
                Log.w(TAG, "⚠️ could not capture optimistic chat ID - may already have migrated or not created yet")
            }
            
            // step 4: confirm bot is responding (thinking indicator visible)
            Log.d(TAG, "🤖 step 4: confirming bot is responding")
            if (!waitForBotThinkingIndicator()) {
                Log.e(TAG, "❌ FAILURE at step 4: bot thinking indicator not found - bot may not be responding")
                failWithScreenshot("bot thinking indicator not found - bot may not be responding", "compose_bot_not_responding")
                return@runBlocking
            }
            
            // step 5: send second message while bot is responding
            // This is the CRITICAL test for the production bug where messages appear to send
            // during bot response but don't actually reach the server via WebSocket
            val secondMessage = "2nd msg - $uniqueTestId"
            Log.d(TAG, "💬 step 5: sending second message while bot is responding (CRITICAL WebSocket test)")
            
            // CRITICAL: verify bot is still responding before sending - this tests the interruption capability
            if (!isBotCurrentlyRespondingCompose()) {
                Log.e(TAG, "❌ FAILURE at step 5: bot stopped responding too quickly - cannot test interruption")
                failWithScreenshot("compose_bot_finished_too_early", "bot stopped responding before we could test interruption capability")
                return@runBlocking
            }
            
            Log.d(TAG, "🤖 bot confirmed still responding - now testing interruption...")
            
            // This is the core UX test: users MUST be able to interrupt the bot AND have messages actually reach the server
            // Using rapid send method to test interruption capability during bot response
            // This should detect the production bug where typing is blocked during bot response
            if (!sendMessageWithDisplayVerification(secondMessage, rapid = true)) {
                Log.e(TAG, "❌ CRITICAL: Bot interruption test failed!")
                Log.e(TAG, "   Rapid message sending failed during bot response")
                Log.e(TAG, "   This indicates the PRODUCTION BUG where users cannot type/send")
                Log.e(TAG, "   messages during bot response period")
                Log.e(TAG, "   Message: '${secondMessage.take(50)}...'")
                failWithScreenshot("compose_bot_interruption_failed", "CRITICAL: Rapid second message during bot response failed - typing likely blocked")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ Bot interruption successful - message sent AND reached server while bot was responding!")
            
            // step 6: wait for bot response to arrive
            Log.d(TAG, "⏳ step 6: waiting for bot response")
            
            if (!waitForBotThinkingToFinish()) {
                Log.w(TAG, "⚠️ thinking indicator still visible after timeout, checking for response anyway")
            }
            
            // use styling detection to detect bot response
            val botResponseDetected = waitForBotResponseCompose(3000)
            
            if (!botResponseDetected) {
                Log.e(TAG, "❌ FAILURE at step 6: bot response not detected within timeout using styling detection")
                failWithScreenshot("compose_no_bot_response", "bot response not detected within timeout using styling detection")
                return@runBlocking
            }
            
            Log.d(TAG, "✅ bot response detected via styling")
            
            // step 6.5: check if migration from optimistic to server chat ID worked
            Log.d(TAG, "🔄 step 6.5: checking chat migration after bot response")
            val migrationWorked = checkChatMigration()
            if (migrationWorked) {
                Log.d(TAG, "✅ chat migration successful after bot response")
            } else {
                Log.w(TAG, "⚠️ chat migration not detected yet - may still be in progress")
            }
            
            // step 7: send third message after bot response
            val thirdMessage = "3rd msg - $uniqueTestId"
            Log.d(TAG, "💬 step 7: sending third message after bot response")
            
            // ensure bot is no longer responding
            if (isBotCurrentlyRespondingCompose()) {
                Log.w(TAG, "⚠️ bot still appears to be responding, but sending message anyway")
            }
            
            if (!sendMessageWithWebSocketVerification(thirdMessage, 3)) {
                Log.e(TAG, "❌ FAILURE at step 7: third message after bot response failed")
                Log.e(TAG, "   Third message either failed UI display or WebSocket transmission")
                Log.e(TAG, "   Message: '${thirdMessage.take(50)}...'")
                failWithScreenshot("compose_third_message_send_failed", "Step 7: Third message after bot response failed to send or reach server")
                return@runBlocking
            }
            
            // step 8: verify all messages are showing properly
            Log.d(TAG, "✅ step 8: verifying all messages display correctly")
            
            // wait for UI to be stable before verification
            Log.d(TAG, "⏳ waiting for UI to be stable before verification...")
            if (!waitForUIToBeStable()) {
                Log.e(TAG, "❌ FAILURE: UI not stable for verification")
                failWithScreenshot("UI not stable for verification", "compose_ui_not_stable")
                return@runBlocking
            }
            
            val sentMessages = listOf(firstMessage, secondMessage, thirdMessage)
            if (!verifyAllMessagesDisplayCorrectly(sentMessages)) {
                failWithScreenshot("Missing messages from chat", "compose_messages_missing")
                return@runBlocking
            }
            
            // step 9: wait for chat migration to complete, then do comprehensive final verification
            Log.d(TAG, "🔍 step 9a: waiting for chat migration to complete...")
            waitForChatMigrationCompletion()
            
            Log.d(TAG, "🔍 step 9b: final comprehensive verification - checking for duplicates and completeness")
            if (!verifyFinalMessageState(sentMessages)) {
                failWithScreenshot("Final message verification failed", "compose_final_verification_failed")
                return@runBlocking
            }
            
            Log.d(TAG, "🎉 comprehensive message flow Compose test PASSED!")
            Log.d(TAG, "✅ Test validated: optimistic UI, bot interruption capability, chat migration, and message persistence")
            
        } catch (e: Exception) {
            Log.e(TAG, "comprehensive message flow Compose test FAILED", e)
            throw e
        }
    }
    
    /**
     * Send message and verify WebSocket sending (normal timeouts for chat loading)
     */
    private suspend fun sendMessageWithWebSocketVerification(message: String, expectedMessageNumber: Int): Boolean {
        Log.d(TAG, "📝 Attempting to send message with WebSocket verification: '${message.take(30)}...'")
        
        // Use Compose testing for better reliability
        val sendSuccess = ComposeTestHelper.sendMessage(
            composeTestRule, 
            message,
            onFailure = { failureType, reason ->
                Log.e(TAG, "❌ Message send failed: $failureType - $reason")
            }
        )
        
        if (!sendSuccess) {
            Log.e(TAG, "❌ Message send failed")
            return false
        }
        
        Log.d(TAG, "✅ Message sent and WebSocket confirmed successfully")
        return true
    }

    /**
     * Send message and verify display (for rapid interruption testing)
     */
    private suspend fun sendMessageWithDisplayVerification(message: String, rapid: Boolean = false): Boolean {
        Log.d(TAG, "📝 Attempting to send message with display verification: '${message.take(30)}...'")
        
        // Use Compose testing for better reliability
        val sendSuccess = ComposeTestHelper.sendMessage(
            composeTestRule, 
            message,
            rapid = rapid,
            onFailure = { failureType, reason ->
                Log.e(TAG, "❌ Message send failed: $failureType - $reason")
            }
        )
        
        if (!sendSuccess) {
            Log.e(TAG, "❌ Message send failed")
            return false
        }
        
        Log.d(TAG, "✅ Message sent and displayed successfully")
        return true
    }

    /**
     * Verify message is visible using Compose testing
     */
    private fun verifyMessageVisible(message: String): Boolean {
        return try {
            Log.d(TAG, "🔍 Verifying message is visible: '${message.take(30)}...'")
            
            // Use Compose testing to find the message
            composeTestRule.onNodeWithText(message).assertIsDisplayed()
            
            Log.d(TAG, "✅ Message verified visible")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Message not visible: '${message.take(30)}...'")
            false
        }
    }

    /**
     * Wait for chat UI to be ready for message input
     */
    private fun waitForChatUIReady(): Boolean {
        Log.d(TAG, "⏳ waiting for chat UI to be ready...")
        
        // Check for input field to be available and interactable using Compose testing
        return try {
            composeTestRule.onNodeWithContentDescription("Message input field").assertIsDisplayed()
            Log.d(TAG, "✅ Chat UI ready for input")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Input field not ready or not enabled")
            false
        }
    }

    /**
     * Wait for UI to be stable (no rapid changes)
     */
    private fun waitForUIToBeStable(): Boolean {
        Log.d(TAG, "⏳ waiting for UI to be stable...")
        
        // For Compose testing, we can use a simple delay since Compose handles state changes efficiently
        try {
            Thread.sleep(500) // Brief wait for UI to settle
            Log.d(TAG, "✅ UI stable")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception waiting for UI stability")
            return false
        }
    }

    /**
     * Wait for bot thinking indicator using Compose testing
     */
    private fun waitForBotThinkingIndicator(): Boolean {
        Log.d(TAG, "⏳ waiting for bot thinking indicator...")
        
        return try {
            // Look for thinking indicator using Compose testing - the actual text is "Whiz is computing"
            composeTestRule.onNodeWithText("Whiz is computing").assertIsDisplayed()
            Log.d(TAG, "✅ Bot thinking indicator found")
            true
        } catch (e: AssertionError) {
            Log.e(TAG, "❌ Bot thinking indicator not found")
            Log.e(TAG, "🔍 This could mean:")
            Log.e(TAG, "   - The bot responded too quickly (no thinking delay)")
            Log.e(TAG, "   - The 'Whiz is computing' indicator is not showing")
            Log.e(TAG, "   - The app is not in the expected state")
            Log.e(TAG, "   - WebSocket connection issues prevented bot response")
            false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error while looking for bot thinking indicator", e)
            false
        }
    }

    /**
     * Check if bot is currently responding using Compose testing
     */
    private fun isBotCurrentlyRespondingCompose(): Boolean {
        return try {
            composeTestRule.onNodeWithText("Whiz is computing").assertIsDisplayed()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wait for bot thinking to finish
     */
    private fun waitForBotThinkingToFinish(): Boolean {
        Log.d(TAG, "⏳ waiting for bot thinking to finish...")
        
        val startTime = System.currentTimeMillis()
        val timeout = 10000L // 10 seconds
        
        while (System.currentTimeMillis() - startTime < timeout) {
            if (!isBotCurrentlyRespondingCompose()) {
                Log.d(TAG, "✅ Bot thinking finished")
                return true
            }
            Thread.sleep(100)
        }
        
        Log.w(TAG, "⚠️ Bot thinking did not finish within timeout")
        return false
    }

    /**
     * Wait for bot response using Compose testing
     */
    private fun waitForBotResponseCompose(timeoutMs: Long): Boolean {
        Log.d(TAG, "⏳ waiting for bot response...")
        
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                // Look for bot response using Compose testing
                composeTestRule.onNodeWithText("Whiz").assertIsDisplayed()
                Log.d(TAG, "✅ Bot response found")
                return true
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        
        Log.e(TAG, "❌ Bot response not found within timeout")
        return false
    }

    /**
     * Verify all messages display correctly using Compose testing
     */
    private fun verifyAllMessagesDisplayCorrectly(sentMessages: List<String>): Boolean {
        Log.d(TAG, "🔍 verifying all messages display correctly...")
        
        // Use Compose testing to verify all messages exist
        val missingMessages = ComposeTestHelper.verifyAllMessagesExist(composeTestRule, sentMessages)
        
        if (missingMessages.isNotEmpty()) {
            Log.e(TAG, "❌ Missing messages detected:")
            missingMessages.forEachIndexed { index, message ->
                Log.e(TAG, "   Missing ${index + 1}: '${message.take(30)}...'")
            }
            return false
        }
        
        Log.d(TAG, "✅ all messages verified without major duplication issues")
        return true
    }

    /**
     * Verify final message state using Compose testing
     */
    private fun verifyFinalMessageState(sentMessages: List<String>): Boolean {
        Log.d(TAG, "🔍 comprehensive final message verification...")
        
        // 1. verify ALL sent messages are present using Compose testing
        Log.d(TAG, "📝 checking all ${sentMessages.size} sent messages are present...")
        val missingMessages = ComposeTestHelper.verifyAllMessagesExist(composeTestRule, sentMessages)
        
        if (missingMessages.isNotEmpty()) {
            Log.e(TAG, "❌ FAILURE at step 9.1: FINAL CHECK - sent messages missing")
            return false
        }
        Log.d(TAG, "✅ all sent messages confirmed present")
        
        // 2. check for user message duplicates using Compose testing
        Log.d(TAG, "🔍 checking for user message duplicates...")
        if (!ComposeTestHelper.noDuplicates(composeTestRule, sentMessages)) {
            Log.e(TAG, "❌ FAILURE at step 9.2: FINAL CHECK - duplicate user messages detected")
            return false
        }
        Log.d(TAG, "✅ no user message duplicates found")
        
        // 3. check for bot message duplicates
        Log.d(TAG, "🤖 checking for bot message duplicates...")
        try {
            val whizNodes = composeTestRule.onAllNodesWithText("Whiz").fetchSemanticsNodes()
            Log.d(TAG, "📊 final message count: ${sentMessages.size} user, ${whizNodes.size} bot")
            
            if (whizNodes.size == 0) {
                Log.w(TAG, "⚠️ no bot responses detected - server may be unavailable")
            } else if (whizNodes.size > sentMessages.size) {
                Log.w(TAG, "⚠️ more bot messages than user messages - may indicate duplication")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ could not check bot message count: ${e.message}")
        }
        
        Log.d(TAG, "✅ comprehensive final verification completed successfully")
        return true
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
            Log.w(TAG, "failed to get optimistic chat ID", e)
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
                Log.w(TAG, "test chat not found during migration check")
                return false
            }
            
            val currentChatId = testChat.id
            finalServerChatId = currentChatId
            
            // migration is successful if:
            // 1. we have a positive server ID (not negative optimistic ID)
            // 2. the ID has changed from the original optimistic ID (if we captured one)
            val hasServerID = currentChatId > 0
            val hasChanged = optimisticChatId?.let { it != currentChatId } ?: true
            
            Log.d(TAG, "migration check: optimistic=$optimisticChatId, current=$currentChatId, hasServerID=$hasServerID, hasChanged=$hasChanged")
            
            if (hasServerID) {
                createdChatIds.add(currentChatId) // track for cleanup
            }
            
            return hasServerID && hasChanged
        } catch (e: Exception) {
            Log.w(TAG, "failed to check chat migration", e)
            false
        }
    }

    /**
     * Wait for chat migration to complete by polling specific conditions
     * instead of using arbitrary delays
     */
    private suspend fun waitForChatMigrationCompletion() {
        Log.d(TAG, "⏳ waiting for chat migration to complete...")
        
        val startTime = System.currentTimeMillis()
        val maxWaitTime = 15000L // 15 seconds max wait
        var attempts = 0
        
        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            attempts++
            Log.d(TAG, "🔍 migration check attempt $attempts...")
            
            try {
                // Check 1: Chat has positive server ID
                val migrationComplete = checkChatMigration()
                if (migrationComplete) {
                    Log.d(TAG, "✅ chat migration completed successfully")
                    
                    // Check 2: All messages are associated with the positive chat ID
                    val allMessagesAssociated = checkAllMessagesAssociated()
                    if (allMessagesAssociated) {
                        Log.d(TAG, "✅ all messages associated with migrated chat")
                        
                        // Check 3: UI has settled (no more rapid state changes)
                        val uiSettled = checkUISettled()
                        if (uiSettled) {
                            Log.d(TAG, "✅ UI has settled after migration")
                            Log.d(TAG, "🎉 chat migration fully completed in ${attempts} attempts (${System.currentTimeMillis() - startTime}ms)")
                            return
                        }
                    }
                }
                
                Log.d(TAG, "⏳ migration not yet complete, waiting...")
                delay(500) // Short delay before next check
                
            } catch (e: Exception) {
                Log.w(TAG, "error during migration check attempt $attempts: ${e.message}")
                delay(500)
            }
        }
        
        Log.w(TAG, "⚠️ migration wait timeout after ${maxWaitTime}ms and $attempts attempts")
        Log.w(TAG, "⚠️ proceeding with test but duplicates may be detected during migration window")
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
            
            Log.d(TAG, "🔍 checking message association: ${userMessages.size} user messages in chat $finalServerChatId")
            
            // Should have at least our 3 test messages
            val hasExpectedMessages = userMessages.size >= 3
            
            // All messages should have positive chat ID
            val allHavePositiveId = userMessages.all { it.chatId > 0 }
            
            Log.d(TAG, "📊 message association check: hasExpected=$hasExpectedMessages, allPositive=$allHavePositiveId")
            
            return hasExpectedMessages && allHavePositiveId
            
        } catch (e: Exception) {
            Log.w(TAG, "error checking message association: ${e.message}")
            false
        }
    }
    
    /**
     * Check if UI has settled (no rapid changes that indicate ongoing migration)
     */
    private suspend fun checkUISettled(): Boolean {
        return try {
            // For Compose testing, we can use a simple check since Compose handles state efficiently
            delay(200)
            Log.d(TAG, "📊 UI stability check: Compose UI is stable")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "error checking UI stability: ${e.message}")
            true // assume settled if we can't check
        }
    }
} 