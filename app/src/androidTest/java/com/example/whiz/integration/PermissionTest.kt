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
import com.example.whiz.accessibility.AccessibilityChecker
import com.example.whiz.accessibility.WhizAccessibilityService
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.test_helpers.TestAccessibilityChecker
import com.example.whiz.test_helpers.TestPermissionManager
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
 * Tests for permission dialogs and settings flow
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UninstallModules(com.example.whiz.di.AppModule::class)
class PermissionTest : BaseIntegrationTest() {
    
    // Override to control permissions per test
    override val skipAutoMicrophoneGrant = true  // We'll control microphone permission per test

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var permissionManager: PermissionManager
    
    @Inject
    lateinit var accessibilityChecker: AccessibilityChecker

    @Before
    fun setup() {
        // BaseIntegrationTest already handles injection and basic setup
        // Ensure the app is ready (on chat list or chat screen)
        val appReady = ComposeTestHelper.isAppReady(composeTestRule)
        if (!appReady) {
            failWithScreenshot(
                "App is not ready for testing - UI elements not found",
                "app_not_ready"
            )
        }
    }
    
    // Helper to mock accessibility and update permission manager
    private fun mockAccessibilityAndUpdate(enabled: Boolean) {
        // Cast to TestAccessibilityChecker to access test methods
        val testChecker = accessibilityChecker as TestAccessibilityChecker
        testChecker.setMockServiceEnabled(enabled)
        
        // Now PermissionManager will use our mocked state!
        runBlocking {
            permissionManager.checkAccessibilityPermission()
        }
    }
    
    // Helper to grant/revoke microphone and update permission manager
    private fun grantMicrophoneAndUpdate() {
        // Use TestPermissionManager to simulate granting without actually granting
        val testPermissionManager = permissionManager as TestPermissionManager
        testPermissionManager.simulateMicrophoneGrant()
    }
    
    private fun revokeMicrophoneAndUpdate() {
        // Use TestPermissionManager to simulate revoking without killing the app
        val testPermissionManager = permissionManager as TestPermissionManager
        testPermissionManager.simulateMicrophoneRevoke()
    }
    
    // Helper to mock overlay permission and update permission manager
    private fun mockOverlayAndUpdate(enabled: Boolean) {
        // Use TestPermissionManager to simulate overlay permission state
        val testPermissionManager = permissionManager as TestPermissionManager
        if (enabled) {
            testPermissionManager.simulateOverlayGrant()
        } else {
            testPermissionManager.simulateOverlayRevoke()
        }
    }

    @Test
    fun testAccessibilityDialogAppearsAfterMicrophonePermissionGranted() = runTest {
        // Setup: Ensure microphone permission is granted
        grantMicrophoneAndUpdate()
        
        // Mock accessibility service as disabled
        mockAccessibilityAndUpdate(false)
        
        // Given: The app checks permissions
        permissionManager.checkAllPermissions()
        
        // Then: Check what step is needed next
        val nextStep = permissionManager.nextRequiredStep.first()

        // Since microphone is granted and accessibility is not, next should be accessibility
        if (nextStep != PermissionManager.RequiredStep.ACCESSIBILITY) {
            failWithScreenshot(
                "Expected ACCESSIBILITY to be next required step but got $nextStep",
                "wrong_next_step"
            )
        }
        
        // Verify the permission manager is tracking correctly
        val accessibilityGranted = permissionManager.accessibilityPermissionGranted.first()
        if (accessibilityGranted) {
            failWithScreenshot(
                "Accessibility should not be granted after mocking it as disabled",
                "accessibility_incorrectly_granted"
            )
        }
    }

    @Test
    fun testMicrophonePermissionDialogShowsWhenMicrophoneNotGranted() {
        // Setup: Revoke microphone permission and mock accessibility as enabled
        revokeMicrophoneAndUpdate()
        mockAccessibilityAndUpdate(true)
        
        // Force permission manager to update its state
        runBlocking {
            permissionManager.checkMicrophonePermission()
        }
        
        // Wait for the microphone permission dialog to appear
        val dialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Microphone permission dialog") },
            timeoutMs = 1000L,
            description = "microphone permission dialog"
        )
        
        if (!dialogAppeared) {
            // Double-check the permission state
            val currentPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            
            if (currentPermission) {
                failWithScreenshot(
                    "Failed to revoke microphone permission - test cannot proceed",
                    "failed_to_revoke_mic_permission"
                )
            } else {
                failWithScreenshot(
                    "Microphone permission is not granted but dialog is not showing",
                    "no_mic_dialog_when_permission_revoked"
                )
            }
        }
        
        println("✓ Microphone permission dialog correctly shown when permission not granted")
    }
    
    @Test
    fun testMicrophonePermissionDialogHasPriority() = runTest {
        // Setup: Revoke microphone and mock accessibility as disabled
        revokeMicrophoneAndUpdate()
        mockAccessibilityAndUpdate(false)
        
        // Force permission manager to update its state
        permissionManager.checkAllPermissions()
        
        // No need to restart activity with mocked permissions - dialog should appear immediately
        
        // Step 1: Verify microphone permission dialog appears first
        val micDialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Microphone permission dialog") },
            timeoutMs = 1000L,
            description = "microphone permission dialog"
        )
        
        if (!micDialogAppeared) {
            // If microphone dialog doesn't appear, check if we successfully revoked permission
            val currentPermission = ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            
            if (currentPermission) {
                failWithScreenshot(
                    "Could not revoke microphone permission for test",
                    "failed_to_revoke_mic_priority_test"
                )
            } else {
                failWithScreenshot(
                    "Microphone permission not granted but dialog not showing",
                    "no_mic_dialog_priority_test"
                )
            }
        }
        
        println("✓ Microphone permission dialog appeared first")
        
        // Step 2: Grant microphone permission (simulate user action)
        grantMicrophoneAndUpdate()
        
        // No need to recreate activity - permission change takes effect immediately with mocking
        
        // Step 3: Verify accessibility dialog appears after microphone is granted
        val accessibilityDialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Accessibility permission dialog") },
            timeoutMs = 1000L,
            description = "accessibility permission dialog"
        )
        
        if (accessibilityDialogAppeared) {
            println("✓ Accessibility permission dialog appeared after microphone was granted")
            println("✓ Dialog priority verified: Microphone → Accessibility")
        } else {
            // Check if we're on the main screen (both permissions might be granted)
            val onMainScreen = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("My Chats") },
                timeoutMs = 1000L,
                description = "main screen"
            )
            
            if (onMainScreen) {
                // If we're on the main screen, our mock didn't work - accessibility is still enabled
                failWithScreenshot(
                    "Failed to mock accessibility as disabled - app proceeded to main screen instead of showing dialog",
                    "failed_to_mock_accessibility_disabled"
                )
            } else {
                failWithScreenshot(
                    "Expected accessibility dialog after granting microphone, but it didn't appear",
                    "no_accessibility_dialog_after_mic"
                )
            }
        }
    }
    
    @Test
    fun testOverlayPermissionDialogShows() {
        // Setup: Grant microphone and accessibility, revoke overlay
        grantMicrophoneAndUpdate()
        mockAccessibilityAndUpdate(true)
        mockOverlayAndUpdate(false)
        
        // Force permission manager to update its state
        runBlocking {
            permissionManager.checkAllPermissions()
        }
        
        // Wait for the overlay permission dialog to appear
        val dialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Overlay permission dialog") },
            timeoutMs = 1000L,
            description = "overlay permission dialog"
        )
        
        if (!dialogAppeared) {
            failWithScreenshot(
                "Overlay permission dialog should appear when overlay permission is not granted",
                "no_overlay_dialog_when_permission_revoked"
            )
        }
        
        println("✓ Overlay permission dialog correctly shown when permission not granted")
    }
    
    @Test
    fun testPermissionPriorityOrder() = runTest {
        // Setup: Revoke all permissions
        revokeMicrophoneAndUpdate()
        mockAccessibilityAndUpdate(false)
        mockOverlayAndUpdate(false)
        
        // Force permission manager to update its state
        permissionManager.checkAllPermissions()
        
        // Step 1: Verify microphone permission dialog appears first
        val micDialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Microphone permission dialog") },
            timeoutMs = 1000L,
            description = "microphone permission dialog"
        )
        
        if (!micDialogAppeared) {
            failWithScreenshot(
                "Microphone permission dialog should appear first in priority order",
                "no_mic_dialog_priority_order_test"
            )
        }
        
        println("✓ Microphone permission dialog appeared first")
        
        // Step 2: Grant microphone permission
        grantMicrophoneAndUpdate()
        
        // Step 3: Verify accessibility dialog appears next
        val accessibilityDialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Accessibility permission dialog") },
            timeoutMs = 1000L,
            description = "accessibility permission dialog"
        )
        
        if (!accessibilityDialogAppeared) {
            failWithScreenshot(
                "Accessibility permission dialog should appear after microphone is granted",
                "no_accessibility_dialog_priority_order"
            )
        }
        
        println("✓ Accessibility permission dialog appeared second")
        
        // Step 4: Grant accessibility permission
        mockAccessibilityAndUpdate(true)
        
        // Step 5: Verify overlay dialog appears last
        val overlayDialogAppeared = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Overlay permission dialog") },
            timeoutMs = 1000L,
            description = "overlay permission dialog"
        )
        
        if (!overlayDialogAppeared) {
            failWithScreenshot(
                "Overlay permission dialog should appear after accessibility is granted",
                "no_overlay_dialog_priority_order"
            )
        }
        
        println("✓ Overlay permission dialog appeared third")
        println("✓ Dialog priority verified: Microphone → Accessibility → Overlay")
    }
    
    @Test
    fun testNoPermissionDialogsWhenAllPermissionsGrantedIncludingOverlay() {
        // Setup: Grant all permissions including overlay
        grantMicrophoneAndUpdate()
        mockAccessibilityAndUpdate(true)
        mockOverlayAndUpdate(true)
        
        // Force permission manager to update its state
        runBlocking {
            permissionManager.checkAllPermissions()
        }
        
        // Give UI just enough time to show dialogs if they were going to appear
        Thread.sleep(100)
        
        // Verify NO permission dialogs appear
        val micDialogExists = try {
            composeTestRule.onNodeWithContentDescription("Microphone permission dialog").assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
        
        val accessibilityDialogExists = try {
            composeTestRule.onNodeWithContentDescription("Accessibility permission dialog").assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
        
        val overlayDialogExists = try {
            composeTestRule.onNodeWithContentDescription("Overlay permission dialog").assertExists()
            true
        } catch (e: AssertionError) {
            false
        }
        
        // Should NOT find any permission dialogs
        if (micDialogExists) {
            failWithScreenshot(
                "Microphone dialog should not appear when permission is granted",
                "unexpected_mic_dialog_with_overlay"
            )
        }
        
        if (accessibilityDialogExists) {
            failWithScreenshot(
                "Accessibility dialog should not appear when permission is granted",
                "unexpected_accessibility_dialog_with_overlay"
            )
        }
        
        if (overlayDialogExists) {
            failWithScreenshot(
                "Overlay dialog should not appear when permission is granted",
                "unexpected_overlay_dialog"
            )
        }
        
        // Should be on the main screen
        val onMainScreen = ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithText("My Chats") },
            timeoutMs = 1000L,
            description = "main chats list"
        ) || ComposeTestHelper.waitForElement(
            composeTestRule = composeTestRule,
            selector = { composeTestRule.onNodeWithContentDescription("Message input field") },
            timeoutMs = 1000L,
            description = "chat input field"
        )
        
        if (!onMainScreen) {
            failWithScreenshot(
                "Should be on main screen when all permissions including overlay are granted",
                "not_on_main_screen_after_all_permissions"
            )
        }
        
        println("✓ No permission dialogs shown when all permissions including overlay are granted")
        println("✓ App correctly proceeded to main screen")
    }
}