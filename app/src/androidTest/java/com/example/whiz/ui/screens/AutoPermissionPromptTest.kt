package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.example.whiz.di.AppModule
import com.example.whiz.ui.theme.WhizTheme
import androidx.compose.material3.Text
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UninstallModules(AppModule::class)
@HiltAndroidTest
@MediumTest
@RunWith(AndroidJUnit4::class)
class AutoPermissionPromptTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun chatScreen_withoutMicPermission_showsAutomaticPermissionDialog() {
        var permissionRequested = false

        composeTestRule.setContent {
            WhizTheme {
                val navController = rememberNavController()
                
                ChatScreenWithPermissionDialog(
                    chatId = 1L,
                    onChatsListClick = { },
                    hasPermission = false, // No microphone permission
                    onRequestPermission = { permissionRequested = true },
                    navController = navController,
                    content = {
                        // Simple content for testing - just show that main content loads
                        Text("Chat Content Loaded")
                    }
                )
            }
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
            WhizTheme {
                val navController = rememberNavController()
                
                ChatScreenWithPermissionDialog(
                    chatId = 1L,
                    onChatsListClick = { },
                    hasPermission = false,
                    onRequestPermission = { permissionRequested = true },
                    navController = navController,
                    content = {
                        Text("Chat Content Loaded")
                    }
                )
            }
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
            WhizTheme {
                val navController = rememberNavController()
                
                ChatScreenWithPermissionDialog(
                    chatId = 1L,
                    onChatsListClick = { },
                    hasPermission = false,
                    onRequestPermission = { permissionRequested = true },
                    navController = navController,
                    content = {
                        Text("Chat Content Loaded")
                    }
                )
            }
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
            WhizTheme {
                val navController = rememberNavController()
                
                ChatScreenWithPermissionDialog(
                    chatId = 1L,
                    onChatsListClick = { },
                    hasPermission = true, // Permission already granted
                    onRequestPermission = { },
                    navController = navController,
                    content = {
                        Text("Chat Content Loaded")
                    }
                )
            }
        }

        // Wait to ensure no dialog appears
        composeTestRule.waitForIdle()
        Thread.sleep(600)

        // Verify the permission dialog does NOT appear
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
            
        // Verify the main content is shown instead
        composeTestRule
            .onNodeWithText("Chat Content Loaded")
            .assertIsDisplayed()
    }

    @Test
    fun permissionDialog_showsCorrectText() {
        composeTestRule.setContent {
            WhizTheme {
                val navController = rememberNavController()
                
                ChatScreenWithPermissionDialog(
                    chatId = 1L,
                    onChatsListClick = { },
                    hasPermission = false,
                    onRequestPermission = { },
                    navController = navController,
                    content = {
                        Text("Chat Content Loaded")
                    }
                )
            }
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