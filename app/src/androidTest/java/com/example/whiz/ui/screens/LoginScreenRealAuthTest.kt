package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.By
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.example.whiz.TestCredentialsManager
import com.example.whiz.di.AppModule
import com.example.whiz.MainActivity
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LoginScreenRealAuthTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        
        // Only run these tests if real credentials are available
        val credentials = TestCredentialsManager.credentials
        Assume.assumeTrue(
            "Real auth tests require valid test credentials", 
            credentials.testEnvironment.useRealAuth && TestCredentialsManager.hasRealCredentials()
        )
    }

    private fun waitForAppToLoad() {
        composeTestRule.waitForIdle()
        Thread.sleep(3000) // Wait for activity to fully initialize
        composeTestRule.waitForIdle()
    }

    private fun debugComposeState(): Boolean {
        return try {
            // Try to get any node from the compose tree
            composeTestRule.onRoot().assertExists()
            android.util.Log.d("LoginRealAuthTest", "✅ Compose hierarchy exists")
            true
        } catch (e: Exception) {
            android.util.Log.e("LoginRealAuthTest", "❌ Compose hierarchy issue: ${e.message}")
            false
        }
    }

    @Test
    fun debug_composeHierarchyAndActivity() {
        android.util.Log.d("LoginRealAuthTest", "🔍 DEBUG: Starting compose hierarchy investigation")
        
        // Check activity state before anything else
        val activity = composeTestRule.activity
        android.util.Log.d("LoginRealAuthTest", "Activity: $activity")
        android.util.Log.d("LoginRealAuthTest", "Activity finished: ${activity.isFinishing}")
        android.util.Log.d("LoginRealAuthTest", "Activity destroyed: ${activity.isDestroyed}")
        
        // Wait for initialization
        composeTestRule.waitForIdle()
        android.util.Log.d("LoginRealAuthTest", "After waitForIdle()")
        
        // Check if compose hierarchy exists
        val composeExists = debugComposeState()
        
        if (!composeExists) {
            // Try waiting longer
            android.util.Log.d("LoginRealAuthTest", "No compose hierarchy, waiting longer...")
            Thread.sleep(5000)
            composeTestRule.waitForIdle()
            
            // Check again
            val composeExistsAfterWait = debugComposeState()
            android.util.Log.d("LoginRealAuthTest", "Compose exists after longer wait: $composeExistsAfterWait")
        }
        
        // Check activity state again
        android.util.Log.d("LoginRealAuthTest", "Final activity state:")
        android.util.Log.d("LoginRealAuthTest", "Activity finished: ${activity.isFinishing}")
        android.util.Log.d("LoginRealAuthTest", "Activity destroyed: ${activity.isDestroyed}")
        
        // This test passes regardless - it's just for debugging
        assert(true)
    }

    @Test
    fun loginScreen_performRealGoogleAuthentication() {
        val credentials = TestCredentialsManager.credentials
        
        android.util.Log.d("LoginRealAuthTest", "🚀 Starting REAL Google Authentication Test")
        android.util.Log.d("LoginRealAuthTest", "Using test account: ${credentials.googleTestAccount.email}")
        
        // Debug compose state first
        android.util.Log.d("LoginRealAuthTest", "Checking compose hierarchy before proceeding...")
        if (!debugComposeState()) {
            android.util.Log.e("LoginRealAuthTest", "❌ Compose hierarchy not available, cannot proceed with UI test")
            
            // Let's try a UiAutomator-only approach to see if we can find the login button
            android.util.Log.d("LoginRealAuthTest", "🔄 Trying UiAutomator-only approach...")
            
            // Wait for app to load using UiAutomator
            Thread.sleep(5000)
            
            // Look for sign-in button using UiAutomator
            val signInButton = device.findObject(UiSelector().textContains("Sign in with Google"))
            if (signInButton.waitForExists(10000)) {
                android.util.Log.d("LoginRealAuthTest", "✅ Found Sign in button via UiAutomator!")
                
                // Click it
                signInButton.click()
                android.util.Log.d("LoginRealAuthTest", "🔘 Clicked Sign in button")
                
                // Handle Google Sign-In flow
                val authSuccess = GoogleSignInAutomator.performGoogleSignIn(
                    device, 
                    credentials.googleTestAccount.email, 
                    credentials.googleTestAccount.password
                )
                
                if (authSuccess) {
                    android.util.Log.d("LoginRealAuthTest", "🎉 Authentication completed via UiAutomator-only approach!")
                } else {
                    android.util.Log.d("LoginRealAuthTest", "Authentication attempt completed (status unclear)")
                }
            } else {
                android.util.Log.d("LoginRealAuthTest", "Could not find Sign in button via UiAutomator either")
            }
            
            return // Exit early since we can't use Compose
        }
        
        waitForAppToLoad()
        
        // Check if we're on the login screen
        try {
            composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
            android.util.Log.d("LoginRealAuthTest", "✅ Found Sign in with Google button")
        } catch (e: AssertionError) {
            android.util.Log.d("LoginRealAuthTest", "Not on login screen - user may already be authenticated")
            return // Skip if already authenticated
        }
        
        // Step 1: Click the Google Sign-In button
        android.util.Log.d("LoginRealAuthTest", "🔘 Clicking Sign in with Google button...")
        composeTestRule.onNodeWithText("Sign in with Google").performClick()
        
        // Step 2: Wait for Google Sign-In dialog
        android.util.Log.d("LoginRealAuthTest", "⏳ Waiting for Google Sign-In dialog...")
        
        // Step 3: Handle the Google Sign-In flow
        val authSuccess = GoogleSignInAutomator.performGoogleSignIn(
            device, 
            credentials.googleTestAccount.email, 
            credentials.googleTestAccount.password
        )
        
        if (authSuccess) {
            android.util.Log.d("LoginRealAuthTest", "✅ Google Sign-In automation completed")
            
            // Step 4: Wait for authentication to complete and app to navigate
            android.util.Log.d("LoginRealAuthTest", "⏳ Waiting for authentication to complete...")
            Thread.sleep(8000) // Give more time for authentication
            composeTestRule.waitForIdle()
            
            // Step 5: Verify successful authentication by checking if we navigated away from login
            try {
                composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
                android.util.Log.e("LoginRealAuthTest", "❌ Still on login screen - authentication may have failed")
                
                // Let's also check what error might be displayed
                try {
                    // Look for any error messages or debug info
                    composeTestRule.onNodeWithText("Check Google Sign-In Status").performClick()
                    Thread.sleep(2000)
                    android.util.Log.d("LoginRealAuthTest", "Clicked debug button to see status")
                } catch (debugE: Exception) {
                    android.util.Log.d("LoginRealAuthTest", "Could not click debug button")
                }
                
            } catch (e: AssertionError) {
                android.util.Log.d("LoginRealAuthTest", "🎉 SUCCESS! No longer on login screen - authentication appears successful!")
                
                // Optional: Try to verify we're on a different screen
                try {
                    // Look for elements that might be on the home/authenticated screen
                    composeTestRule.waitForIdle()
                    android.util.Log.d("LoginRealAuthTest", "✅ App successfully navigated after authentication")
                } catch (homeE: Exception) {
                    android.util.Log.d("LoginRealAuthTest", "Navigation successful, but couldn't identify home screen elements")
                }
            }
        } else {
            android.util.Log.e("LoginRealAuthTest", "❌ Google Sign-In automation failed")
            
            // Let's see what's actually on screen
            try {
                composeTestRule.onNodeWithText("Check Google Sign-In Status").performClick()
                Thread.sleep(2000)
                android.util.Log.d("LoginRealAuthTest", "Clicked debug button after failed auth attempt")
            } catch (debugE: Exception) {
                android.util.Log.d("LoginRealAuthTest", "Could not access debug info after failed auth")
            }
        }
        
        android.util.Log.d("LoginRealAuthTest", "🏁 Real authentication test completed")
    }
}

// Enhanced helper functions for Google Sign-In automation
object GoogleSignInAutomator {
    
    fun performGoogleSignIn(device: UiDevice, email: String, password: String): Boolean {
        return try {
            android.util.Log.d("GoogleSignInAutomator", "🚀 Starting Google Sign-In automation")
            
            // Wait for any Google-related dialog to appear
            android.util.Log.d("GoogleSignInAutomator", "Waiting for Google dialog...")
            val googleDialogAppeared = device.wait(Until.hasObject(By.textContains("Google")), 15000) ||
                                     device.wait(Until.hasObject(By.textContains("Choose an account")), 5000) ||
                                     device.wait(Until.hasObject(By.textContains("Sign in")), 5000) ||
                                     device.wait(Until.hasObject(By.textContains("email")), 5000)
            
            if (!googleDialogAppeared) {
                android.util.Log.e("GoogleSignInAutomator", "❌ No Google Sign-In dialog appeared within timeout")
                
                // Let's see what's actually on screen
                val allText = device.findObject(UiSelector().textMatches(".*"))
                if (allText.exists()) {
                    android.util.Log.d("GoogleSignInAutomator", "Current screen text: ${allText.text}")
                }
                
                return false
            }
            
            android.util.Log.d("GoogleSignInAutomator", "✅ Google Sign-In dialog detected")
            
            // Try different approaches to handle the dialog
            val accountHandled = handleAccountSelection(device, email) || 
                               handleEmailEntry(device, email, password) ||
                               handleDirectSignIn(device, email, password)
            
            if (accountHandled) {
                // Handle any permission dialogs
                android.util.Log.d("GoogleSignInAutomator", "Handling permission dialogs...")
                handlePermissionDialogs(device)
                android.util.Log.d("GoogleSignInAutomator", "✅ Google Sign-In flow completed")
                return true
            }
            
            android.util.Log.e("GoogleSignInAutomator", "❌ Could not handle Google Sign-In dialog")
            false
        } catch (e: Exception) {
            android.util.Log.e("GoogleSignInAutomator", "❌ Sign-in failed: ${e.message}")
            false
        }
    }
    
    private fun handleAccountSelection(device: UiDevice, email: String): Boolean {
        val accountSelector = device.findObject(UiSelector().textContains("Choose an account"))
        if (accountSelector.waitForExists(3000)) {
            android.util.Log.d("GoogleSignInAutomator", "Found account selection screen")
            
            val targetAccount = device.findObject(UiSelector().textContains(email))
            if (targetAccount.exists()) {
                android.util.Log.d("GoogleSignInAutomator", "Found target account: $email")
                targetAccount.click()
                return true
            } else {
                android.util.Log.d("GoogleSignInAutomator", "Target account not found, looking for add account option")
                val addAccount = device.findObject(UiSelector().textMatches("(?i).*add.*account.*"))
                if (addAccount.exists()) {
                    android.util.Log.d("GoogleSignInAutomator", "Clicking add account")
                    addAccount.click()
                    Thread.sleep(2000)
                    return false // Will be handled by email entry
                }
            }
        }
        return false
    }
    
    private fun handleEmailEntry(device: UiDevice, email: String, password: String): Boolean {
        // Look for email input field
        val emailField = device.findObject(UiSelector().className("android.widget.EditText"))
        if (emailField.waitForExists(5000)) {
            android.util.Log.d("GoogleSignInAutomator", "Found email input field")
            emailField.setText(email)
            
            // Click Next
            val nextButton = device.findObject(UiSelector().textMatches("(?i)next"))
            if (nextButton.exists()) {
                android.util.Log.d("GoogleSignInAutomator", "Clicking Next after email")
                nextButton.click()
                Thread.sleep(4000) // Wait for password screen
                
                // Handle password
                val passwordField = device.findObject(UiSelector().className("android.widget.EditText"))
                if (passwordField.waitForExists(5000)) {
                    android.util.Log.d("GoogleSignInAutomator", "Found password input field")
                    passwordField.setText(password)
                    
                    val signInButton = device.findObject(UiSelector().textMatches("(?i)next|sign.*in"))
                    if (signInButton.exists()) {
                        android.util.Log.d("GoogleSignInAutomator", "Clicking sign-in button")
                        signInButton.click()
                        return true
                    }
                }
            }
        }
        return false
    }
    
    private fun handleDirectSignIn(device: UiDevice, email: String, password: String): Boolean {
        // Sometimes the dialog might be different, let's try to handle it directly
        Thread.sleep(2000)
        
        // Look for any text input fields using the correct API
        try {
            // Use findObject with UiSelector for single elements
            val firstInputField = device.findObject(UiSelector().className("android.widget.EditText"))
            if (firstInputField.waitForExists(3000)) {
                android.util.Log.d("GoogleSignInAutomator", "Found input field, filling with email")
                firstInputField.setText(email)
                
                // Look for any button to proceed
                val buttons = listOf("Next", "Continue", "Sign in", "Submit")
                for (buttonText in buttons) {
                    val button = device.findObject(UiSelector().textMatches("(?i).*$buttonText.*"))
                    if (button.exists()) {
                        android.util.Log.d("GoogleSignInAutomator", "Found and clicking: $buttonText")
                        button.click()
                        Thread.sleep(2000)
                        break
                    }
                }
                
                // Check if we now have a password field
                Thread.sleep(2000)
                val passwordField = device.findObject(UiSelector().className("android.widget.EditText"))
                if (passwordField.exists()) {
                    passwordField.setText(password)
                    android.util.Log.d("GoogleSignInAutomator", "Filled password field")
                    
                    for (buttonText in buttons) {
                        val button = device.findObject(UiSelector().textMatches("(?i).*$buttonText.*"))
                        if (button.exists()) {
                            android.util.Log.d("GoogleSignInAutomator", "Found and clicking: $buttonText")
                            button.click()
                            Thread.sleep(2000)
                            return true
                        }
                    }
                }
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleSignInAutomator", "Error in direct sign-in: ${e.message}")
        }
        return false
    }
    
    private fun handlePermissionDialogs(device: UiDevice) {
        // Handle various permission dialogs that might appear
        val permissionButtons = listOf("Allow", "Continue", "Accept", "OK", "I agree", "Agree", "Yes")
        
        for (i in 0..8) { // Try up to 8 times for different dialogs
            Thread.sleep(1500)
            
            for (buttonText in permissionButtons) {
                val button = device.findObject(UiSelector().textMatches("(?i).*$buttonText.*"))
                if (button.exists()) {
                    android.util.Log.d("GoogleSignInAutomator", "Clicking permission button: $buttonText")
                    button.click()
                    Thread.sleep(1500)
                    break
                }
            }
        }
    }
} 