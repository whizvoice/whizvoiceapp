package com.example.whiz.ui.accessibility

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.whiz.MainActivity
import com.example.whiz.R
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.test.BaseIntegrationTest
import com.example.whiz.test.ComposeTestHelper
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Tests for accessibility permission dialog and settings flow
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UninstallModules(com.example.whiz.di.AppModule::class)
class AccessibilityPermissionTest : BaseIntegrationTest() {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var permissionManager: PermissionManager

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var testHelper: ComposeTestHelper

    @Before
    fun setup() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context = ApplicationProvider.getApplicationContext()
        testHelper = ComposeTestHelper(composeTestRule)
        
        // Ensure we're on the chat screen initially
        testHelper.waitForText("Whiz", timeout = 5000)
    }

    @Test
    fun testAccessibilityDialogAppearsAfterMicrophonePermissionGranted() = runTest {
        // Given: Microphone permission is granted
        // This should be handled by the test setup or mock
        
        // When: The app checks permissions
        permissionManager.checkAllPermissions()
        
        // Then: If microphone is granted but accessibility is not, the accessibility dialog should appear
        val nextPermission = permissionManager.nextRequiredPermission.first()
        if (nextPermission == PermissionManager.PermissionType.ACCESSIBILITY) {
            composeTestRule.waitForIdle()
            
            // Verify the accessibility dialog is shown
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("To control your phone with voice commands and open apps like WhatsApp", substring = true)
                .assertIsDisplayed()
            
            // Verify dialog buttons
            composeTestRule
                .onNodeWithText("Open Settings")
                .assertIsDisplayed()
                .assertHasClickAction()
            
            composeTestRule
                .onNodeWithText("Not Now")
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    @Test
    fun testMicrophonePermissionDialogHasPriority() = runTest {
        // When: Both permissions are missing
        // The microphone dialog should appear first
        
        composeTestRule.waitForIdle()
        
        // Look for either permission dialog
        val hasMicDialog = try {
            composeTestRule
                .onNodeWithText("Microphone Permission Required")
                .assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
        
        val hasAccessibilityDialog = try {
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertIsDisplayed()
            true
        } catch (e: AssertionError) {
            false
        }
        
        // If microphone permission is not granted, mic dialog should show first
        val micPermissionGranted = runBlocking { 
            permissionManager.microphonePermissionGranted.first() 
        }
        
        if (!micPermissionGranted) {
            assertThat(hasMicDialog).isTrue()
            assertThat(hasAccessibilityDialog).isFalse()
        }
    }

    @Test
    fun testAccessibilityScreenShowsCorrectStatus() {
        // Navigate to Settings
        composeTestRule
            .onNodeWithContentDescription("Settings")
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Navigate to Accessibility screen
        composeTestRule
            .onNodeWithText("Accessibility Controls")
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Check if accessibility service is enabled
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        
        if (isEnabled) {
            // Verify enabled state UI
            composeTestRule
                .onNodeWithText("Accessibility Service Enabled")
                .assertIsDisplayed()
            
            // Verify quick actions are shown
            composeTestRule
                .onNodeWithText("WhatsApp")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Home")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Back")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Recent")
                .assertIsDisplayed()
        } else {
            // Verify disabled state UI
            composeTestRule
                .onNodeWithText("Accessibility Service Disabled")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Enable the accessibility service to control other apps")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    @Test
    fun testAccessibilityDialogOpenSettingsButton() {
        // Navigate to Accessibility screen
        navigateToAccessibilityScreen()
        
        // If service is not enabled, click the enable button
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        if (!isEnabled) {
            // Click Enable button which should show the dialog
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .performClick()
            
            composeTestRule.waitForIdle()
            
            // Verify dialog appears
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertIsDisplayed()
            
            // Click Open Settings button
            composeTestRule
                .onNodeWithText("Open Settings")
                .performClick()
            
            // Note: We can't easily verify that Android Settings opened
            // in an instrumented test, but we can verify the dialog dismisses
            composeTestRule.waitForIdle()
            
            // Dialog should be dismissed
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertDoesNotExist()
        }
    }

    @Test
    fun testAccessibilityDialogDismissButton() {
        // Navigate to Accessibility screen
        navigateToAccessibilityScreen()
        
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        if (!isEnabled) {
            // Show the dialog
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .performClick()
            
            composeTestRule.waitForIdle()
            
            // Click Not Now button
            composeTestRule
                .onNodeWithText("Not Now")
                .performClick()
            
            composeTestRule.waitForIdle()
            
            // Dialog should be dismissed
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertDoesNotExist()
            
            // Should still be on Accessibility screen
            composeTestRule
                .onNodeWithText("Accessibility Controls")
                .assertIsDisplayed()
        }
    }

    @Test
    fun testWhatsAppLaunchButtonRequiresAccessibility() {
        // Navigate to Accessibility screen
        navigateToAccessibilityScreen()
        
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        
        if (isEnabled) {
            // Try to click WhatsApp button
            composeTestRule
                .onNodeWithText("WhatsApp")
                .performClick()
            
            composeTestRule.waitForIdle()
            
            // Should show snackbar (success or app not installed)
            val hasWhatsApp = isAppInstalled("com.whatsapp")
            if (hasWhatsApp) {
                composeTestRule
                    .onNodeWithText("Opening WhatsApp")
                    .assertIsDisplayed()
            } else {
                composeTestRule
                    .onNodeWithText("WhatsApp not installed or cannot be opened")
                    .assertIsDisplayed()
            }
        } else {
            // WhatsApp button shouldn't be visible when service is disabled
            composeTestRule
                .onNodeWithText("WhatsApp")
                .assertDoesNotExist()
        }
    }

    @Test
    fun testInstalledAppsListShowsWhenAccessibilityEnabled() {
        // Navigate to Accessibility screen
        navigateToAccessibilityScreen()
        
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        
        if (isEnabled) {
            // Scroll down to see installed apps
            composeTestRule
                .onNodeWithText("Installed Apps")
                .assertIsDisplayed()
            
            // There should be at least some system apps
            composeTestRule
                .onAllNodesWithText("com.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        } else {
            // Installed apps section shouldn't show when disabled
            composeTestRule
                .onNodeWithText("Installed Apps")
                .assertDoesNotExist()
        }
    }

    @Test
    fun testPermissionManagerTracksAccessibilityStatus() = runTest {
        // Check initial state
        permissionManager.checkAccessibilityPermission()
        
        val initialStatus = permissionManager.accessibilityPermissionGranted.first()
        val actualStatus = WhizAccessibilityService.isServiceEnabled()
        
        // Permission manager should accurately reflect service status
        assertThat(initialStatus).isEqualTo(actualStatus)
        
        // Check that nextRequiredPermission correctly identifies accessibility
        val nextPermission = permissionManager.nextRequiredPermission.first()
        
        if (!actualStatus) {
            // If accessibility is not enabled and mic is granted,
            // next required should be accessibility
            val micGranted = permissionManager.microphonePermissionGranted.first()
            if (micGranted) {
                assertThat(nextPermission).isEqualTo(PermissionManager.PermissionType.ACCESSIBILITY)
            }
        }
    }

    @Test
    fun testUnifiedPermissionDialogShowsCorrectContent() {
        // This test verifies the unified dialog shows the right content based on permission type
        
        // Force show the accessibility dialog by navigating to a screen that triggers it
        composeTestRule.waitForIdle()
        
        // Check for any permission dialog
        val nextPermission = runBlocking {
            permissionManager.nextRequiredPermission.first()
        }
        
        when (nextPermission) {
            PermissionManager.PermissionType.MICROPHONE -> {
                // Verify microphone dialog content
                composeTestRule
                    .onNodeWithText("Microphone Permission Required")
                    .assertIsDisplayed()
                
                composeTestRule
                    .onNodeWithText("Grant Permission")
                    .assertIsDisplayed()
            }
            PermissionManager.PermissionType.ACCESSIBILITY -> {
                // Verify accessibility dialog content
                composeTestRule
                    .onNodeWithText("Enable Accessibility Service")
                    .assertIsDisplayed()
                
                composeTestRule
                    .onNodeWithText("Open Settings")
                    .assertIsDisplayed()
            }
            null -> {
                // All permissions granted, no dialog should show
                composeTestRule
                    .onNodeWithText("Permission")
                    .assertDoesNotExist()
            }
        }
    }

    // Helper functions
    
    private fun navigateToAccessibilityScreen() {
        // Start from main screen
        composeTestRule.waitForIdle()
        
        // Try to find and click settings (might be in menu or visible)
        try {
            composeTestRule
                .onNodeWithContentDescription("Settings")
                .performClick()
        } catch (e: AssertionError) {
            // Settings might be in a different location
            composeTestRule
                .onNodeWithText("Settings")
                .performClick()
        }
        
        composeTestRule.waitForIdle()
        
        // Click on Accessibility Controls
        composeTestRule
            .onNodeWithText("Accessibility Controls")
            .performClick()
        
        composeTestRule.waitForIdle()
    }
    
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}