package com.example.whiz.integration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.test.assertHasClickAction
import org.junit.After
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

    private var originalMicrophonePermission: Boolean = false
    private var originalAccessibilityPermission: Boolean = false

    @Before
    fun setup() {
        // Don't call hiltRule.inject() here as BaseIntegrationTest already does it
        // device and context are already available from BaseIntegrationTest
        
        // Save original permission states
        originalMicrophonePermission = ContextCompat.checkSelfPermission(
            context, 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        originalAccessibilityPermission = WhizAccessibilityService.isServiceEnabled()
        
        println("Original permission states - Microphone: $originalMicrophonePermission, Accessibility: $originalAccessibilityPermission")
        
        // Ensure the app is ready (on chat list or chat screen)
        val appReady = ComposeTestHelper.isAppReady(composeTestRule)
        if (!appReady) {
            throw AssertionError("App is not ready for testing - UI elements not found")
        }
    }

    @After
    fun teardown() {
        // Restore original permission states
        println("Restoring original permission states - Microphone: $originalMicrophonePermission, Accessibility: $originalAccessibilityPermission")
        
        // For microphone permission, we can restore it
        if (originalMicrophonePermission) {
            grantMicrophonePermission()
        } else {
            revokeMicrophonePermission()
        }
        
        // Note: We can't programmatically enable/disable accessibility service
        // but we can log what the original state was
        if (originalAccessibilityPermission != WhizAccessibilityService.isServiceEnabled()) {
            println("WARNING: Accessibility service state changed during test and cannot be automatically restored")
            println("Original: $originalAccessibilityPermission, Current: ${WhizAccessibilityService.isServiceEnabled()}")
        }
    }

    private fun grantMicrophonePermission() {
        try {
            val grantResult = device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            println("Microphone permission grant result: $grantResult")
            Thread.sleep(300) // Give the system time to process
            
            // Verify the permission was granted
            val currentState = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            println("Microphone permission after grant: $currentState")
            
            // Update permission manager
            permissionManager.updateMicrophonePermission(currentState)
        } catch (e: Exception) {
            println("Failed to grant microphone permission: ${e.message}")
        }
    }

    private fun revokeMicrophonePermission() {
        try {
            val revokeResult = device.executeShellCommand("pm revoke ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
            println("Microphone permission revoke result: $revokeResult")
            Thread.sleep(300) // Give the system time to process
            
            // Verify the permission was revoked
            val currentState = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            println("Microphone permission after revoke: $currentState")
            
            // Update permission manager
            permissionManager.updateMicrophonePermission(currentState)
        } catch (e: Exception) {
            println("Failed to revoke microphone permission: ${e.message}")
        }
    }

    @Test
    fun testAccessibilityDialogAppearsAfterMicrophonePermissionGranted() = runTest {
        // Setup: Ensure microphone permission is granted and accessibility is not enabled
        grantMicrophonePermission()
        
        // Note: We can't programmatically disable accessibility service,
        // so we'll check if it's already disabled
        if (WhizAccessibilityService.isServiceEnabled()) {
            println("Skipping test - accessibility service is already enabled")
            return@runTest
        }
        
        // Given: The app checks permissions
        permissionManager.checkAllPermissions()
        
        // Then: Check what permission is needed next
        val nextPermission = permissionManager.nextRequiredPermission.first()
        
        // Since microphone is granted and accessibility is not, next should be accessibility
        assertEquals(PermissionManager.PermissionType.ACCESSIBILITY, nextPermission)
        
        composeTestRule.waitForIdle()
        
        // Verify the permission manager is tracking correctly
        val accessibilityGranted = permissionManager.accessibilityPermissionGranted.first()
        assertEquals(false, accessibilityGranted)
    }

    @Test
    fun testMicrophonePermissionDialogShowsWhenMicrophoneNotGranted() {
        // Setup: Revoke microphone permission
        revokeMicrophonePermission()
        
        // Force permission manager to update its state
        runBlocking {
            permissionManager.checkMicrophonePermission()
        }
        
        // Restart the activity to trigger permission check
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        
        // Give time for the dialog to appear
        Thread.sleep(1000)
        
        // Then: The MicrophonePermissionDialog should be displayed
        val dialogExists = try {
            composeTestRule
                .onNodeWithContentDescription("Microphone permission dialog")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithContentDescription("Microphone permission explanation")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithContentDescription("Grant microphone permission button")
                .assertIsDisplayed()
                .assertHasClickAction()
            
            composeTestRule
                .onNodeWithContentDescription("Dismiss microphone permission dialog button")
                .assertIsDisplayed()
                .assertHasClickAction()
            true
        } catch (e: AssertionError) {
            false
        }
        
        if (!dialogExists) {
            // Double-check the permission state
            val currentPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            
            if (currentPermission) {
                println("WARNING: Failed to revoke microphone permission, skipping test")
                return
            } else {
                throw AssertionError("Microphone permission is not granted but dialog is not showing")
            }
        }
        
        println("✓ Microphone permission dialog correctly shown when permission not granted")
    }
    
    @Test
    fun testMicrophonePermissionDialogHasPriority() = runTest {
        // Setup: Try to set both permissions to not granted
        // (We can only control microphone permission)
        revokeMicrophonePermission()
        
        // When: Check all permissions
        permissionManager.checkAllPermissions()
        
        // Then: Verify microphone permission is checked first
        val nextPermission = permissionManager.nextRequiredPermission.first()
        val micPermissionGranted = permissionManager.microphonePermissionGranted.first()
        
        if (!micPermissionGranted) {
            // If microphone permission is not granted, it should be the next required
            assertEquals(PermissionManager.PermissionType.MICROPHONE, nextPermission)
            println("✓ Microphone permission correctly has priority when not granted")
        } else {
            println("WARNING: Could not revoke microphone permission for test")
            // If we couldn't revoke microphone, test accessibility priority
            if (!WhizAccessibilityService.isServiceEnabled()) {
                // If microphone is granted but accessibility is not, accessibility should be next
                assertEquals(PermissionManager.PermissionType.ACCESSIBILITY, nextPermission)
                println("✓ Accessibility permission is next when microphone is granted")
            }
        }
    }

    @Test
    fun testAccessibilityScreenShowsCorrectStatus() {
        // Setup: Ensure we have microphone permission so we can navigate
        grantMicrophonePermission()
        
        // Navigate to Settings
        composeTestRule
            .onNodeWithContentDescription("Settings")
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Navigate to Accessibility screen
        composeTestRule
            .onNodeWithContentDescription("Accessibility controls menu item")
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Check if accessibility service is enabled
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        println("Accessibility service enabled: $isEnabled")
        
        if (isEnabled) {
            // Verify enabled state UI
            composeTestRule
                .onNodeWithContentDescription("Accessibility service status enabled")
                .assertIsDisplayed()
            
            // Verify quick actions are shown
            composeTestRule
                .onNodeWithContentDescription("Open WhatsApp button")
                .assertIsDisplayed()
            println("✓ Accessibility screen correctly shows enabled status")
        } else {
            // Verify disabled state UI
            composeTestRule
                .onNodeWithContentDescription("Accessibility service status disabled")
                .assertIsDisplayed()
            
            composeTestRule
                .onNodeWithContentDescription("Enable accessibility service button")
                .assertIsDisplayed()
                .assertHasClickAction()
            println("✓ Accessibility screen correctly shows disabled status")
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
        // Setup: Ensure we have microphone permission
        grantMicrophonePermission()
        
        // Navigate to Accessibility screen
        navigateToAccessibilityScreen()
        
        val isEnabled = WhizAccessibilityService.isServiceEnabled()
        println("Testing WhatsApp button with accessibility service enabled: $isEnabled")
        
        if (isEnabled) {
            // Try to click WhatsApp button
            composeTestRule
                .onNodeWithContentDescription("Open WhatsApp button")
                .performClick()
            
            composeTestRule.waitForIdle()
            
            // Should show snackbar (success or app not installed)
            val hasWhatsApp = isAppInstalled("com.whatsapp")
            if (hasWhatsApp) {
                composeTestRule
                    .onNodeWithContentDescription("WhatsApp opening snackbar")
                    .assertIsDisplayed()
                println("✓ WhatsApp button works when accessibility is enabled")
            } else {
                composeTestRule
                    .onNodeWithContentDescription("WhatsApp not installed snackbar")
                    .assertIsDisplayed()
                println("✓ WhatsApp not installed message shown correctly")
            }
        } else {
            // WhatsApp button shouldn't be visible when service is disabled
            composeTestRule
                .onNodeWithContentDescription("Open WhatsApp button")
                .assertDoesNotExist()
            println("✓ WhatsApp button correctly hidden when accessibility is disabled")
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
                .onNodeWithContentDescription("Settings menu item")
                .performClick()
        }
        
        composeTestRule.waitForIdle()
        
        // Click on Accessibility Controls
        composeTestRule
            .onNodeWithContentDescription("Accessibility controls menu item")
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