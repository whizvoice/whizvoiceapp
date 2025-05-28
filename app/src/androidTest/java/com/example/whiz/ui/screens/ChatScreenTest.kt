package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.data.local.MessageEntity
import com.example.whiz.data.local.MessageType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chatScreen_displaysEmptyState_whenNoMessages() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Start a conversation").assertIsDisplayed()
    }

    @Test
    fun chatScreen_displaysMessages_whenMessagesExist() {
        // Given
        val testMessages = listOf(
            MessageEntity(
                id = 1L,
                chatId = 1L,
                content = "Hello, how are you?",
                type = MessageType.USER,
                timestamp = System.currentTimeMillis()
            ),
            MessageEntity(
                id = 2L,
                chatId = 1L,
                content = "I'm doing well, thank you!",
                type = MessageType.ASSISTANT,
                timestamp = System.currentTimeMillis()
            )
        )

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = testMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Hello, how are you?").assertIsDisplayed()
        composeTestRule.onNodeWithText("I'm doing well, thank you!").assertIsDisplayed()
    }

    @Test
    fun chatScreen_inputField_isDisplayed() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Message input").assertIsDisplayed()
    }

    @Test
    fun chatScreen_microphoneButton_isDisplayed() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Voice input").assertIsDisplayed()
    }

    @Test
    fun chatScreen_backButton_isDisplayed() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun chatScreen_settingsButton_isDisplayed() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun chatScreen_sendMessage_triggersCallback() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()
        var sentMessage: String? = null

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { sentMessage = it },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Type message and send
        composeTestRule.onNodeWithContentDescription("Message input")
            .performTextInput("Test message")
        composeTestRule.onNodeWithContentDescription("Send message").performClick()

        // Then
        assert(sentMessage == "Test message")
    }

    @Test
    fun chatScreen_backButton_triggersCallback() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()
        var backClicked = false

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { backClicked = true },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backClicked)
    }

    @Test
    fun chatScreen_settingsButton_triggersCallback() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()
        var settingsClicked = false

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = false,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { settingsClicked = true }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        assert(settingsClicked)
    }

    @Test
    fun chatScreen_showsLoadingIndicator_whenLoading() {
        // Given
        val emptyMessages = emptyList<MessageEntity>()

        // When
        composeTestRule.setContent {
            ChatScreen(
                messages = emptyMessages,
                isLoading = true,
                onSendMessage = { },
                onBackClick = { },
                onSettingsClick = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }
} 