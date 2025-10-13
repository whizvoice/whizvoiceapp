package com.example.whiz.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.test_helpers.ComposeTestHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Tests that login screen takes priority over permission dialogs
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UninstallModules(com.example.whiz.di.AppModule::class)
class LoginPriorityTest : BaseIntegrationTest() {
    
    // Override to prevent automatic authentication
    override val skipAutoAuthentication = true
    
    // Override to prevent automatic microphone grant
    override val skipAutoMicrophoneGrant = true
    
    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    @Before
    fun setup() {
        // Ensure user is logged out
        runBlocking {
            authRepository.signOut()
        }
        
        // Use TestPermissionManager to mock permissions instead of revoking them
        // (revoking causes the app to be killed by Android)
        if (permissionManager is com.example.whiz.test_helpers.TestPermissionManager) {
            val testPermissionManager = permissionManager as com.example.whiz.test_helpers.TestPermissionManager
            // Simulate microphone permission not granted
            testPermissionManager.simulateMicrophoneRevoke()
            // Simulate overlay permission not granted
            testPermissionManager.simulateOverlayRevoke()
        }
        
        // Verify permissions are not granted
        runBlocking {
            permissionManager.checkAllPermissions()
            val micGranted = permissionManager.microphonePermissionGranted.value
            assertEquals("Microphone should not be granted", false, micGranted)
        }
    }
    
    @Test
    fun testLoginScreenAppearsBeforePermissionDialogs() {
        // The app should launch and show login screen, not permission dialogs
        
        try {
            // Wait for the login screen to appear
            val loginScreenAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithText("Sign in with Google") },
                timeoutMs = 5000L,
                description = "login screen"
            )
            
            // Verify login screen is shown
            if (loginScreenAppeared) {
                composeTestRule
                    .onNodeWithText("Sign in with Google")
                    .assertIsDisplayed()
                println("✓ Login screen appears when user is not authenticated")
            } else {
                // Alternative: Check for any login-related UI elements
                val hasLoginElements = ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("Login", substring = true, ignoreCase = true) },
                    timeoutMs = 2000L,
                    description = "login elements"
                ) || ComposeTestHelper.waitForElement(
                    composeTestRule = composeTestRule,
                    selector = { composeTestRule.onNodeWithText("Sign", substring = true, ignoreCase = true) },
                    timeoutMs = 1000L,
                    description = "sign in elements"
                )
                
                if (!hasLoginElements) {
                    failWithScreenshot("Login screen not found when user is not authenticated", "login_screen_not_found")
                }
            }
            
            // Verify NO permission dialogs are shown
            val micDialogAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Microphone permission dialog") },
                timeoutMs = 1000L,
                description = "microphone permission dialog"
            )
            
            val accessibilityDialogAppeared = ComposeTestHelper.waitForElement(
                composeTestRule = composeTestRule,
                selector = { composeTestRule.onNodeWithContentDescription("Accessibility permission dialog") },
                timeoutMs = 1000L,
                description = "accessibility permission dialog"
            )
            
            // Assert that permission dialogs did NOT appear
            if (micDialogAppeared) {
                failWithScreenshot("Microphone dialog should not appear before login", "mic_dialog_before_login")
            }
            if (accessibilityDialogAppeared) {
                failWithScreenshot("Accessibility dialog should not appear before login", "accessibility_dialog_before_login")
            }
            
            println("✓ No permission dialogs shown when user is not logged in")
            println("✓ Login takes priority over all permission requests")
            
        } catch (e: AssertionError) {
            failWithScreenshot("Test assertion failed: ${e.message}", "login_priority_assertion_failed")
        } catch (e: Exception) {
            failWithScreenshot("Unexpected error in login priority test: ${e.message}", "login_priority_unexpected_error")
        }
    }
}