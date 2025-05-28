package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.data.local.ChatEntity
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatsListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chatsListScreen_displaysEmptyState_whenNoChats() {
        // Given
        val emptyChats = emptyList<ChatEntity>()

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("No conversations yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start a new conversation").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_displaysChatList_whenChatsExist() {
        // Given
        val testChats = listOf(
            ChatEntity(
                id = 1L,
                title = "Test Chat 1",
                createdAt = System.currentTimeMillis(),
                lastMessageTime = System.currentTimeMillis()
            ),
            ChatEntity(
                id = 2L,
                title = "Test Chat 2",
                createdAt = System.currentTimeMillis(),
                lastMessageTime = System.currentTimeMillis()
            )
        )

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = testChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Test Chat 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Chat 2").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_showsLoadingIndicator_whenLoading() {
        // Given
        val emptyChats = emptyList<ChatEntity>()

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = true,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_newChatFAB_isDisplayed() {
        // Given
        val emptyChats = emptyList<ChatEntity>()

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("New Chat").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_settingsButton_isDisplayed() {
        // Given
        val emptyChats = emptyList<ChatEntity>()

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_chatClick_triggersCallback() {
        // Given
        val testChats = listOf(
            ChatEntity(
                id = 1L,
                title = "Test Chat 1",
                createdAt = System.currentTimeMillis(),
                lastMessageTime = System.currentTimeMillis()
            )
        )
        var clickedChatId: Long? = null

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = testChats,
                isLoading = false,
                onChatClick = { clickedChatId = it },
                onNewChatClick = { },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Test Chat 1").performClick()
        assert(clickedChatId == 1L)
    }

    @Test
    fun chatsListScreen_newChatFAB_triggersCallback() {
        // Given
        val emptyChats = emptyList<ChatEntity>()
        var newChatClicked = false

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { newChatClicked = true },
                onSettingsClick = { },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("New Chat").performClick()
        assert(newChatClicked)
    }

    @Test
    fun chatsListScreen_settingsButton_triggersCallback() {
        // Given
        val emptyChats = emptyList<ChatEntity>()
        var settingsClicked = false

        // When
        composeTestRule.setContent {
            ChatsListScreen(
                chats = emptyChats,
                isLoading = false,
                onChatClick = { },
                onNewChatClick = { },
                onSettingsClick = { settingsClicked = true },
                onRefresh = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked)
    }
} 