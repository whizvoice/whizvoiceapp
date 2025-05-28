package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsScreen_displaysTitle() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysBackButton() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysVoiceSettingsSection() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Voice Settings").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysSpeedSlider() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Speech Speed").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysPitchSlider() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Speech Pitch").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysSignOutButton() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Sign Out").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_displaysDeleteAllChatsButton() {
        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delete All Chats").assertIsDisplayed()
    }

    @Test
    fun settingsScreen_backButton_triggersCallback() {
        // Given
        var backClicked = false

        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { backClicked = true },
                onSignOut = { },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assert(backClicked)
    }

    @Test
    fun settingsScreen_signOutButton_triggersCallback() {
        // Given
        var signOutClicked = false

        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { signOutClicked = true },
                onDeleteAllChats = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Sign Out").performClick()
        assert(signOutClicked)
    }

    @Test
    fun settingsScreen_deleteAllChatsButton_triggersCallback() {
        // Given
        var deleteAllClicked = false

        // When
        composeTestRule.setContent {
            SettingsScreen(
                onBackClick = { },
                onSignOut = { },
                onDeleteAllChats = { deleteAllClicked = true }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Delete All Chats").performClick()
        assert(deleteAllClicked)
    }
} 