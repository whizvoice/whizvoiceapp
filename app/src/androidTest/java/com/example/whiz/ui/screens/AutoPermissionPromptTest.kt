package com.example.whiz.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class AutoPermissionPromptTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatScreen_withoutMicPermission_showsAutomaticPermissionDialog() {
        var permissionRequested = false

        composeTestRule.setContent {
            val navController = rememberNavController()
            
            ChatScreen(
                chatId = 1L,
                onChatsListClick = { },
                hasPermission = false, // No microphone permission
                onRequestPermission = { permissionRequested = true },
                navController = navController
            )
        }

        // Wait for the automatic dialog to appear (500ms delay + some buffer)
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Verify the permission dialog appears automatically
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertIsDisplayed()

        // Verify the dialog text content
        composeTestRule
            .onNodeWithText("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?")
            .assertIsDisplayed()

        // Verify both buttons are present
        composeTestRule
            .onNodeWithText("Grant Permission")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Not Now")
            .assertIsDisplayed()
    }

    @Test
    fun chatScreen_withoutMicPermission_grantPermissionButtonWorks() {
        var permissionRequested = false

        composeTestRule.setContent {
            val navController = rememberNavController()
            
            ChatScreen(
                chatId = 1L,
                onChatsListClick = { },
                hasPermission = false,
                onRequestPermission = { permissionRequested = true },
                navController = navController
            )
        }

        // Wait for the automatic dialog to appear
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Click the "Grant Permission" button
        composeTestRule
            .onNodeWithText("Grant Permission")
            .performClick()

        // Verify that the permission request callback was called
        assert(permissionRequested) { "Permission request callback should have been called" }

        // Verify the dialog is dismissed
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
    }

    @Test
    fun chatScreen_withoutMicPermission_notNowButtonDismissesDialog() {
        var permissionRequested = false

        composeTestRule.setContent {
            val navController = rememberNavController()
            
            ChatScreen(
                chatId = 1L,
                onChatsListClick = { },
                hasPermission = false,
                onRequestPermission = { permissionRequested = true },
                navController = navController
            )
        }

        // Wait for the automatic dialog to appear
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Click the "Not Now" button
        composeTestRule
            .onNodeWithText("Not Now")
            .performClick()

        // Verify that the permission request callback was NOT called
        assert(!permissionRequested) { "Permission request callback should not have been called" }

        // Verify the dialog is dismissed
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
    }

    @Test
    fun chatScreen_withMicPermission_doesNotShowPermissionDialog() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            
            ChatScreen(
                chatId = 1L,
                onChatsListClick = { },
                hasPermission = true, // Permission already granted
                onRequestPermission = { },
                navController = navController
            )
        }

        // Wait to ensure no dialog appears
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Verify the permission dialog does NOT appear
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
    }

    @Test
    fun permissionDialog_showsCorrectText() {
        composeTestRule.setContent {
            val navController = rememberNavController()
            
            ChatScreen(
                chatId = 1L,
                onChatsListClick = { },
                hasPermission = false,
                onRequestPermission = { },
                navController = navController
            )
        }

        // Wait for dialog to appear
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Verify specific dialog content
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertExists()

        composeTestRule
            .onNodeWithText("Whiz is a voice assistant that requires microphone access to function properly. Would you like to grant microphone permission now?")
            .assertExists()

        composeTestRule
            .onNodeWithText("Grant Permission")
            .assertExists()
            .assertIsEnabled()

        composeTestRule
            .onNodeWithText("Not Now")
            .assertExists()
            .assertIsEnabled()
    }
} 