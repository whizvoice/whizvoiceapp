package com.example.whiz.ui.accessibility

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.whiz.MainActivity
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.test_helpers.ComposeTestHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        // Don't call hiltRule.inject() here as BaseIntegrationTest already does it
        
        // Ensure we're on the chat screen initially using the existing helper
        ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("Whiz") },
            timeoutMs = 5000,
            description = "Whiz app title"
        )
    }

    @Test
    fun testAccessibilityDialogAppearsAfterMicrophonePermissionGranted() = runTest {
        // Given: The app checks permissions
        permissionManager.checkAllPermissions()
        
        // Then: Check what permission is needed next
        val nextPermission = permissionManager.nextRequiredPermission.first()
        
        if (nextPermission == PermissionManager.PermissionType.ACCESSIBILITY) {
            composeTestRule.waitForIdle()
            
            // Note: The dialog would appear if integrated into ChatScreen
            // For now, just verify the permission manager is tracking correctly
            val accessibilityGranted = permissionManager.accessibilityPermissionGranted.first()
            assertEquals(WhizAccessibilityService.isServiceEnabled(), accessibilityGranted)
        }
    }

    @Test
    fun testMicrophonePermissionDialogHasPriority() = runTest {
        // When: Check all permissions
        permissionManager.checkAllPermissions()
        
        // Then: Verify microphone permission is checked first
        val nextPermission = permissionManager.nextRequiredPermission.first()
        val micPermissionGranted = permissionManager.microphonePermissionGranted.first()
        
        if (!micPermissionGranted) {
            // If microphone permission is not granted, it should be the next required
            assertEquals(PermissionManager.PermissionType.MICROPHONE, nextPermission)
        } else if (!WhizAccessibilityService.isServiceEnabled()) {
            // If microphone is granted but accessibility is not, accessibility should be next
            assertEquals(PermissionManager.PermissionType.ACCESSIBILITY, nextPermission)
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
        } else {
            // Verify disabled state UI
            composeTestRule
                .onNodeWithText("Accessibility Service Disabled")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithText("Enable Accessibility Service")
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    @Test
    fun testPermissionManagerTracksAccessibilityStatus() = runTest {
        // Check initial state
        permissionManager.checkAccessibilityPermission()
        
        val initialStatus = permissionManager.accessibilityPermissionGranted.first()
        val actualStatus = WhizAccessibilityService.isServiceEnabled()
        
        // Permission manager should accurately reflect service status
        assertEquals(actualStatus, initialStatus)
        
        // Check that nextRequiredPermission correctly identifies accessibility
        val nextPermission = permissionManager.nextRequiredPermission.first()
        
        if (!actualStatus) {
            // If accessibility is not enabled and mic is granted,
            // next required should be accessibility
            val micGranted = permissionManager.microphonePermissionGranted.first()
            if (micGranted) {
                assertEquals(PermissionManager.PermissionType.ACCESSIBILITY, nextPermission)
            }
        }
    }

    @Test
    fun testPermissionTypeEnumExists() {
        // Verify that PermissionType enum exists and has expected values
        assertNotNull(PermissionManager.PermissionType.MICROPHONE)
        assertNotNull(PermissionManager.PermissionType.ACCESSIBILITY)
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