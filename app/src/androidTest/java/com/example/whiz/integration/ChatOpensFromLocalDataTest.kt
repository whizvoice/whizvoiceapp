package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.data.remote.WhizServerRepository
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestInterceptor
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.ui.navigation.Screen
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.inject.Inject

/**
 * Room is the source of truth for chats. Opening a chat never waits on the server: a chat whose
 * row exists locally shows its title and messages even when the server fails the history sync.
 * (A chat id with no local row opens as a new chat; that is covered by EmptyAccountChatListTest.)
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatOpensFromLocalDataTest : BaseIntegrationTest() {

    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var repository: WhizRepository

    @Inject
    lateinit var testInterceptor: TestInterceptor

    @Inject
    override lateinit var whizServerRepository: WhizServerRepository

    companion object {
        private const val TAG = "ChatOpensFromLocalDataTest"
        private const val TEST_TIMEOUT = 10000L
        private const val CHAT_TITLE = "Local chat sync failure test"
        private const val LOCAL_MESSAGE = "Message kept in Room while the server fails"
    }

    private val createdChatIds = mutableListOf<String>()

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        testInterceptor.resetErrorState()
        TestInterceptor.persistentDisconnectForTestCheck = { whizServerRepository.persistentDisconnectForTest() }
        TestInterceptor.simulateNetworkErrorForManualDisconnect = true
    }

    @After
    fun cleanup() {
        runBlocking {
            testInterceptor.resetErrorState()
            if (createdChatIds.isNotEmpty()) {
                cleanupTestChats(repository = repository, trackedChatIds = createdChatIds)
                createdChatIds.clear()
            }
            whizServerRepository.resetConnectionStateForTesting()
            TestInterceptor.persistentDisconnectForTestCheck = null
            TestInterceptor.simulateNetworkErrorForManualDisconnect = true
        }
    }

    @Test
    fun existingChat_opensWithLocalMessages_whenServerSyncFails() {
        runBlocking {
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                }
            }
            val chatListReady = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("My Chats") },
                TEST_TIMEOUT,
                "chat list to load"
            )
            if (!chatListReady) {
                failWithScreenshot("Chat list not ready", "chat_list_not_ready")
            }

            // A chat that exists only in Room, with one message
            val chatId = UUID.randomUUID().toString()
            createdChatIds.add(chatId)
            repository.createChatLocal(chatId, CHAT_TITLE)
            repository.addUserMessageOptimistic(chatId, LOCAL_MESSAGE)

            // The server fails this chat's history sync
            testInterceptor.failMessagesForChatId = chatId

            Log.d(TAG, "Opening chat $chatId with messages sync failing")
            composeTestRule.runOnUiThread {
                composeTestRule.activity.getNavController()
                    ?.navigate(Screen.Chat.route(chatId)) { launchSingleTop = true }
                    ?: failWithScreenshot("NavController not available", "no_nav_controller")
            }

            // The chat opens from Room: title and message visible, input available
            val titleShown = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText(CHAT_TITLE) },
                TEST_TIMEOUT,
                "chat title from Room"
            )
            if (!titleShown) {
                failWithScreenshot("Chat title should come from the local row", "no_local_title")
            }
            val messageShown = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText(LOCAL_MESSAGE) },
                TEST_TIMEOUT,
                "local message"
            )
            if (!messageShown) {
                failWithScreenshot("Local message should be shown despite the failed sync", "no_local_message")
            }
            composeTestRule.onNodeWithContentDescription("Message input field").assertExists()

            // The failing sync was actually attempted, and did not take the chat down
            val syncAttempted = withTimeoutOrNull(TEST_TIMEOUT) {
                while (testInterceptor.failedMessageFetches == 0) delay(100)
                true
            } ?: false
            if (!syncAttempted) {
                failWithScreenshot("Messages sync was never attempted for the opened chat", "sync_not_attempted")
            }
            composeTestRule.onNodeWithText(CHAT_TITLE).assertExists()
            composeTestRule.onNodeWithText(LOCAL_MESSAGE).assertExists()
            composeTestRule.onNodeWithContentDescription("Message input field").assertExists()
            Log.d(TAG, "Chat opened from Room while the server returned 500 for its messages")
        }
    }
}
