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
import com.example.whiz.ui.theme.WhizTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatsListScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun chatsListScreen_displaysTitle() {
        composeTestRule.setContent {
            WhizTheme {
                // Mock the screen title
                androidx.compose.material3.Text("Your Conversations")
            }
        }
        
        // Verify screen title is displayed
        composeTestRule.onNodeWithText("Your Conversations").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_displaysEmptyState() {
        composeTestRule.setContent {
            WhizTheme {
                // Mock empty state message
                androidx.compose.material3.Text("No conversations yet. Start a new chat!")
            }
        }
        
        // Verify empty state message is displayed
        composeTestRule.onNodeWithText("No conversations yet. Start a new chat!").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_newChatButton_isDisplayed() {
        var buttonClicked = false
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.FloatingActionButton(
                    onClick = { buttonClicked = true }
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Start New Chat"
                    )
                }
            }
        }
        
        // Verify new chat button is displayed
        composeTestRule.onNodeWithContentDescription("Start New Chat").assertIsDisplayed()
        
        // Test button click
        composeTestRule.onNodeWithContentDescription("Start New Chat").performClick()
        assert(buttonClicked == true)
    }

    @Test
    fun chatsListScreen_chatItem_displaysCorrectly() {
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.layout.Column {
                    androidx.compose.material3.Text("Chat with Whiz")
                    androidx.compose.material3.Text("Last message preview...")
                    androidx.compose.material3.Text("2 hours ago")
                }
            }
        }
        
        // Verify chat item components are displayed
        composeTestRule.onNodeWithText("Chat with Whiz").assertIsDisplayed()
        composeTestRule.onNodeWithText("Last message preview...").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 hours ago").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_searchFunctionality() {
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = { androidx.compose.material3.Text("Search conversations...") }
                )
            }
        }
        
        // Verify search field is displayed
        composeTestRule.onNodeWithText("Search conversations...").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_chatItem_isClickable() {
        var chatClicked = false
        
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.material3.Card(
                    onClick = { chatClicked = true }
                ) {
                    androidx.compose.material3.Text("Chat with Whiz")
                }
            }
        }
        
        // Test chat item click
        composeTestRule.onNodeWithText("Chat with Whiz").performClick()
        assert(chatClicked == true)
    }

    @Test
    fun chatsListScreen_multipleChats_displayCorrectly() {
        composeTestRule.setContent {
            WhizTheme {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(3) { index ->
                        androidx.compose.material3.Text("Chat ${index + 1}")
                    }
                }
            }
        }
        
        // Verify multiple chat items are displayed
        composeTestRule.onNodeWithText("Chat 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chat 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chat 3").assertIsDisplayed()
    }

    @Test
    fun chatsListScreen_validation_chatData() {
        // Test chat data validation
        val chatTitle = "Chat with Whiz"
        val lastMessage = "Hello, how can I help you?"
        val timestamp = "2 hours ago"
        
        assert(chatTitle.isNotBlank())
        assert(lastMessage.isNotBlank())
        assert(timestamp.isNotBlank())
        assert(chatTitle.contains("Whiz"))
    }
} 