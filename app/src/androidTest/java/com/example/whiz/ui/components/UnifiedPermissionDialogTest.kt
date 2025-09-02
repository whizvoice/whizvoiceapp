package com.example.whiz.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.theme.WhizTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for UnifiedPermissionDialog component
 */
@RunWith(AndroidJUnit4::class)
class UnifiedPermissionDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun microphonePermissionDialog_displaysCorrectContent() {
        var dismissCalled = false
        var requestPermissionCalled = false
        var openSettingsCalled = false

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.MICROPHONE,
                    onDismiss = { dismissCalled = true },
                    onRequestMicrophonePermission = { requestPermissionCalled = true },
                    onOpenAccessibilitySettings = { openSettingsCalled = true }
                )
            }
        }

        // Verify dialog title
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertIsDisplayed()

        // Verify dialog message
        composeTestRule
            .onNodeWithText("Whiz is a voice assistant that requires microphone access to function properly", substring = true)
            .assertIsDisplayed()

        // Verify buttons
        composeTestRule
            .onNodeWithText("Grant Permission")
            .assertIsDisplayed()
            .assertHasClickAction()

        composeTestRule
            .onNodeWithText("Not Now")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun microphonePermissionDialog_grantButtonTriggersCallback() {
        var dismissCalled = false
        var requestPermissionCalled = false

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.MICROPHONE,
                    onDismiss = { dismissCalled = true },
                    onRequestMicrophonePermission = { requestPermissionCalled = true },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Click Grant Permission button
        composeTestRule
            .onNodeWithText("Grant Permission")
            .performClick()

        // Verify callbacks
        assertThat(requestPermissionCalled).isTrue()
        assertThat(dismissCalled).isTrue()
    }

    @Test
    fun microphonePermissionDialog_dismissButtonTriggersCallback() {
        var dismissCalled = false
        var requestPermissionCalled = false

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.MICROPHONE,
                    onDismiss = { dismissCalled = true },
                    onRequestMicrophonePermission = { requestPermissionCalled = true },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Click Not Now button
        composeTestRule
            .onNodeWithText("Not Now")
            .performClick()

        // Verify only dismiss was called
        assertThat(dismissCalled).isTrue()
        assertThat(requestPermissionCalled).isFalse()
    }

    @Test
    fun accessibilityPermissionDialog_displaysCorrectContent() {
        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.ACCESSIBILITY,
                    onDismiss = { },
                    onRequestMicrophonePermission = { },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Verify dialog title
        composeTestRule
            .onNodeWithText("Enable Accessibility Service")
            .assertIsDisplayed()

        // Verify dialog message
        composeTestRule
            .onNodeWithText("To control your phone with voice commands and open apps like WhatsApp", substring = true)
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("You'll be taken to Settings", substring = true)
            .assertIsDisplayed()

        // Verify buttons
        composeTestRule
            .onNodeWithText("Open Settings")
            .assertIsDisplayed()
            .assertHasClickAction()

        composeTestRule
            .onNodeWithText("Not Now")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun accessibilityPermissionDialog_openSettingsButtonTriggersCallback() {
        var dismissCalled = false
        var openSettingsCalled = false
        var requestMicCalled = false

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.ACCESSIBILITY,
                    onDismiss = { dismissCalled = true },
                    onRequestMicrophonePermission = { requestMicCalled = true },
                    onOpenAccessibilitySettings = { openSettingsCalled = true }
                )
            }
        }

        // Click Open Settings button
        composeTestRule
            .onNodeWithText("Open Settings")
            .performClick()

        // Verify callbacks
        assertThat(openSettingsCalled).isTrue()
        assertThat(dismissCalled).isTrue()
        assertThat(requestMicCalled).isFalse()
    }

    @Test
    fun accessibilityPermissionDialog_dismissButtonTriggersCallback() {
        var dismissCalled = false
        var openSettingsCalled = false

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.ACCESSIBILITY,
                    onDismiss = { dismissCalled = true },
                    onRequestMicrophonePermission = { },
                    onOpenAccessibilitySettings = { openSettingsCalled = true }
                )
            }
        }

        // Click Not Now button
        composeTestRule
            .onNodeWithText("Not Now")
            .performClick()

        // Verify only dismiss was called
        assertThat(dismissCalled).isTrue()
        assertThat(openSettingsCalled).isFalse()
    }

    @Test
    fun noPermissionRequired_showsNoDialog() {
        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = null, // All permissions granted
                    onDismiss = { },
                    onRequestMicrophonePermission = { },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Verify no dialog is shown
        composeTestRule
            .onNodeWithText("Permission", substring = true)
            .assertDoesNotExist()

        composeTestRule
            .onNodeWithText("Settings")
            .assertDoesNotExist()

        composeTestRule
            .onNodeWithText("Grant")
            .assertDoesNotExist()
    }

    @Test
    fun dialogTransition_fromMicrophoneToAccessibility() {
        val permissionType = mutableStateOf<PermissionManager.PermissionType?>(
            PermissionManager.PermissionType.MICROPHONE
        )

        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = permissionType.value,
                    onDismiss = { },
                    onRequestMicrophonePermission = { 
                        // Simulate microphone permission granted
                        permissionType.value = PermissionManager.PermissionType.ACCESSIBILITY
                    },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Initially shows microphone dialog
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertIsDisplayed()

        // Click Grant Permission
        composeTestRule
            .onNodeWithText("Grant Permission")
            .performClick()

        composeTestRule.waitForIdle()

        // Now should show accessibility dialog
        composeTestRule
            .onNodeWithText("Enable Accessibility Service")
            .assertIsDisplayed()

        // Microphone dialog should be gone
        composeTestRule
            .onNodeWithText("Microphone Permission Required")
            .assertDoesNotExist()
    }

    @Test
    fun dialogContent_isAccessible() {
        // Test that dialog content has proper accessibility attributes
        composeTestRule.setContent {
            WhizTheme {
                UnifiedPermissionDialog(
                    permissionType = PermissionManager.PermissionType.ACCESSIBILITY,
                    onDismiss = { },
                    onRequestMicrophonePermission = { },
                    onOpenAccessibilitySettings = { }
                )
            }
        }

        // Check that important elements are accessible
        composeTestRule
            .onNodeWithText("Enable Accessibility Service")
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText("Open Settings")
            .assert(hasClickAction())

        composeTestRule
            .onNodeWithText("Not Now")
            .assert(hasClickAction())
    }
}