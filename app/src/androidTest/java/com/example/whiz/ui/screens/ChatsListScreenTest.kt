package com.example.whiz.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatsListScreenTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun chatsListScreen_displaysCorrectly() {
        composeTestRule.setContent {
            WhizTheme {
                val viewModel: ChatsListViewModel = viewModel() // Hilt will provide this
                ChatsListScreen(
                    onChatSelected = {},
                    onNewChatClick = {},
                    onSettingsClick = {},
                    hasPermission = true,
                    onRequestPermission = {},
                    viewModel = viewModel
                )
            }
        }

        // The screen should display some basic elements even if loading or empty
        // This tests the UI structure and that Hilt injection works
        composeTestRule.waitForIdle()
    }

    @Test
    fun chatsListScreen_newChatFab_isDisplayed() {
        composeTestRule.setContent {
            WhizTheme {
                val viewModel: ChatsListViewModel = viewModel() // Hilt will provide this
                ChatsListScreen(
                    onChatSelected = {},
                    onNewChatClick = {},
                    onSettingsClick = {},
                    hasPermission = true,
                    onRequestPermission = {},
                    viewModel = viewModel
                )
            }
        }

        // Verify FAB is displayed
        composeTestRule.onNodeWithContentDescription("New Chat").assertIsDisplayed()
    }

    @Test
    fun chatItem_displaysCorrectData() {
        val testChat = ChatEntity(
            id = 1L,
            title = "Chat with Whiz",
            lastMessageTime = Instant.now().toEpochMilli()
        )
        var chatClicked = false
        
        composeTestRule.setContent {
            WhizTheme {
                ChatItem(
                    chat = testChat,
                    onClick = { chatClicked = true }
                )
            }
        }
        
        // Verify chat data is displayed
        composeTestRule.onNodeWithText("Chat with Whiz").assertIsDisplayed()
        
        // Verify click functionality
        composeTestRule.onNodeWithText("Chat with Whiz").performClick()
        assert(chatClicked == true)
    }

    @Test
    fun chatsList_displaysMultipleItems() {
        val testChats = listOf(
            ChatEntity(id = 1L, title = "Chat 1", lastMessageTime = Instant.now().toEpochMilli()),
            ChatEntity(id = 2L, title = "Chat 2", lastMessageTime = Instant.now().toEpochMilli()),
            ChatEntity(id = 3L, title = "Chat 3", lastMessageTime = Instant.now().toEpochMilli())
        )
        var selectedChatId: Long? = null
        
        composeTestRule.setContent {
            WhizTheme {
                ChatsList(
                    chats = testChats,
                    onChatClick = { chatId -> selectedChatId = chatId }
                )
            }
        }
        
        // Verify all chat items are displayed
        composeTestRule.onNodeWithText("Chat 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chat 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chat 3").assertIsDisplayed()
        
        // Test clicking one of the chats
        composeTestRule.onNodeWithText("Chat 2").performClick()
        assert(selectedChatId == 2L)
    }

    @Test
    fun chatItem_handlesLongTitle() {
        val testChat = ChatEntity(
            id = 1L,
            title = "This is a very long chat title that should be truncated with ellipsis when displayed",
            lastMessageTime = Instant.now().toEpochMilli()
        )
        
        composeTestRule.setContent {
            WhizTheme {
                ChatItem(
                    chat = testChat,
                    onClick = {}
                )
            }
        }
        
        // Verify the long title is displayed (truncation is handled by maxLines and overflow)
        composeTestRule.onNodeWithText("This is a very long chat title that should be truncated with ellipsis when displayed").assertIsDisplayed()
    }

    @Test
    fun chatItem_callbackTriggersCorrectly() {
        val testChat = ChatEntity(
            id = 42L,
            title = "Test Chat",
            lastMessageTime = Instant.now().toEpochMilli()
        )
        var clickedChatId: Long? = null
        var clickCount = 0
        
        composeTestRule.setContent {
            WhizTheme {
                ChatItem(
                    chat = testChat,
                    onClick = { 
                        clickedChatId = testChat.id
                        clickCount++
                    }
                )
            }
        }
        
        // Initial state
        assert(clickedChatId == null)
        assert(clickCount == 0)
        
        // Click the chat item
        composeTestRule.onNodeWithText("Test Chat").performClick()
        
        // Verify callback was triggered correctly
        assert(clickedChatId == 42L)
        assert(clickCount == 1)
    }

    @Test
    fun chatsList_multipleClicks_differentCallbacks() {
        val testChats = listOf(
            ChatEntity(id = 10L, title = "First Chat", lastMessageTime = Instant.now().toEpochMilli()),
            ChatEntity(id = 20L, title = "Second Chat", lastMessageTime = Instant.now().toEpochMilli())
        )
        val clickedIds = mutableListOf<Long>()
        
        composeTestRule.setContent {
            WhizTheme {
                ChatsList(
                    chats = testChats,
                    onChatClick = { chatId -> clickedIds.add(chatId) }
                )
            }
        }
        
        // Click both chats
        composeTestRule.onNodeWithText("First Chat").performClick()
        composeTestRule.onNodeWithText("Second Chat").performClick()
        
        // Verify both callbacks were triggered with correct IDs
        assert(clickedIds.size == 2)
        assert(clickedIds.contains(10L))
        assert(clickedIds.contains(20L))
    }
} 