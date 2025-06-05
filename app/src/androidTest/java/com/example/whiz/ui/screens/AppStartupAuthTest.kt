package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.example.whiz.di.AppModule
import com.example.whiz.MainActivity
import com.example.whiz.data.auth.AuthRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import android.util.Log

@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppStartupAuthTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var authRepository: AuthRepository

    private lateinit var device: UiDevice

    @Before
    fun setup() {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        
        // Ensure user is signed out for these tests
        runBlocking {
            try {
                Log.d("AppStartupAuthTest", "Signing out user to start from clean state")
                authRepository.signOut()
            } catch (e: Exception) {
                Log.w("AppStartupAuthTest", "Could not sign out user - may already be signed out", e)
            }
        }
    }

    private fun waitForAppToLoad() {
        // Use efficient waiting with reduced sleep time instead of risky waitUntil
        composeTestRule.waitForIdle()
        Thread.sleep(1500) // Reduced from 3000ms - still allows initialization but much faster
        composeTestRule.waitForIdle()
    }

    @Test
    fun appStartup_whenNotAuthenticated_shouldShowLoginScreen() {
        val testStartTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 STARTING TEST: appStartup_whenNotAuthenticated_shouldShowLoginScreen at $testStartTime")
        
        Log.d("AppStartupAuthTest", "🚀 Testing app startup authentication flow")
        
        waitForAppToLoad()
        
        // Verify authentication state
        val isAuthenticated = runBlocking { 
            authRepository.isSignedIn()
        }
        Log.d("AppStartupAuthTest", "Authentication state: $isAuthenticated")
        
        // The app should automatically show the login screen when not authenticated
        try {
            composeTestRule.onNodeWithText("Welcome to WhizVoice").assertIsDisplayed()
            composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
            Log.d("AppStartupAuthTest", "✅ Login screen is properly displayed on startup when not authenticated")
        } catch (e: AssertionError) {
            Log.e("AppStartupAuthTest", "❌ Login screen not displayed when user is not authenticated", e)
            
            // Try to understand what screen is actually shown
            try {
                composeTestRule.onRoot().printToLog("APP_STARTUP_AUTH_TEST")
                
                // Check if we accidentally ended up on home screen
                try {
                    composeTestRule.onNodeWithContentDescription("Create new chat").assertIsDisplayed()
                    Log.e("AppStartupAuthTest", "❌ CRITICAL: App showed home screen instead of login screen for unauthenticated user!")
                } catch (homeE: Exception) {
                    Log.d("AppStartupAuthTest", "Not on home screen either - unknown state")
                }
                
            } catch (debugE: Exception) {
                Log.e("AppStartupAuthTest", "Could not debug current screen state", debugE)
            }
            
            throw AssertionError("Login screen should be displayed when user is not authenticated", e)
        }
        
        val testEndTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 COMPLETED TEST: appStartup_whenNotAuthenticated_shouldShowLoginScreen at $testEndTime (duration: ${testEndTime - testStartTime}ms)")
    }

    @Test
    fun appStartup_authenticationCheck_isPerformedImmediately() {
        val testStartTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 STARTING TEST: appStartup_authenticationCheck_isPerformedImmediately at $testStartTime")
        
        Log.d("AppStartupAuthTest", "🚀 Testing immediate authentication check on startup")
        
        // Use efficient waiting with shorter timeouts instead of risky waitUntil
        composeTestRule.waitForIdle()
        Thread.sleep(800) // Reduced from 1000ms - faster but still allows auth check
        composeTestRule.waitForIdle()
        
        // By this point, the app should have already determined authentication state
        // and navigated to the appropriate screen
        
        val currentAuthState = runBlocking { 
            authRepository.isSignedIn()
        }
        Log.d("AppStartupAuthTest", "Current auth state after efficient wait: $currentAuthState")
        
        if (!currentAuthState) {
            // Should be on login screen
            try {
                composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
                Log.d("AppStartupAuthTest", "✅ Login screen shown efficiently for unauthenticated user")
            } catch (e: AssertionError) {
                Log.e("AppStartupAuthTest", "❌ Authentication check may not have completed - login screen not shown", e)
                throw AssertionError("App should show login screen immediately when user is not authenticated")
            }
        } else {
            Log.d("AppStartupAuthTest", "User is authenticated - should be on home screen")
            // This test scenario assumes user is not authenticated, so this is unexpected
            Log.w("AppStartupAuthTest", "⚠️ User appears to be authenticated despite signOut() call")
        }
        
        val testEndTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 COMPLETED TEST: appStartup_authenticationCheck_isPerformedImmediately at $testEndTime (duration: ${testEndTime - testStartTime}ms)")
    }

    @Test 
    fun appStartup_navigationFlow_worksCorrectly() {
        val testStartTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 STARTING TEST: appStartup_navigationFlow_worksCorrectly at $testStartTime")
        
        Log.d("AppStartupAuthTest", "🚀 Testing navigation flow on app startup")
        
        waitForAppToLoad()
        
        // Verify we're on login screen (since we signed out in setup)
        composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
        
        // Test that login screen elements are functional
        composeTestRule.onNodeWithText("Sign in with Google").assertIsEnabled()
        
        // Test debug button if available
        try {
            composeTestRule.onNodeWithText("Check Google Sign-In Status").assertIsDisplayed()
            composeTestRule.onNodeWithText("Check Google Sign-In Status").assertIsEnabled()
            
            // Click debug button to see current state
            composeTestRule.onNodeWithText("Check Google Sign-In Status").performClick()
            
            // Wait for debug info to appear efficiently
            composeTestRule.waitForIdle()
            
            Log.d("AppStartupAuthTest", "✅ Debug button functional - can check auth status")
        } catch (e: AssertionError) {
            Log.d("AppStartupAuthTest", "Debug button not available - this is normal in production builds")
        }
        
        Log.d("AppStartupAuthTest", "✅ Navigation flow working correctly - login screen functional")
        
        val testEndTime = System.currentTimeMillis()
        Log.d("TEST_EXECUTION", "🕐 COMPLETED TEST: appStartup_navigationFlow_worksCorrectly at $testEndTime (duration: ${testEndTime - testStartTime}ms)")
    }
} 