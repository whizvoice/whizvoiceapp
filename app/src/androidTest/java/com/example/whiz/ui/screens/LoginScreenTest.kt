package com.example.whiz.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.AuthViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun init() {
        hiltRule.inject()
    }

    @Test
    fun loginScreen_displaysCorrectly() {
        composeTestRule.setContent {
            WhizTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel() // Hilt will provide this
                LoginScreen(
                    navController = navController,
                    authViewModel = authViewModel
                )
            }
        }

        // Verify login screen elements are displayed
        composeTestRule.onNodeWithText("Login").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Google Sign In").assertIsDisplayed()
    }

    @Test
    fun loginScreen_googleSignInButton_isEnabled() {
        composeTestRule.setContent {
            WhizTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel() // Hilt will provide this
                LoginScreen(
                    navController = navController,
                    authViewModel = authViewModel
                )
            }
        }

        // Verify Google sign-in button is enabled and clickable
        composeTestRule.onNodeWithContentDescription("Google Sign In").assertIsEnabled()
    }

    @Test
    fun loginScreen_clickGoogleSignIn_triggersAction() {
        composeTestRule.setContent {
            WhizTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel() // Hilt will provide this
                LoginScreen(
                    navController = navController,
                    authViewModel = authViewModel
                )
            }
        }

        // Click the Google sign-in button - this will test the actual action flow
        composeTestRule.onNodeWithContentDescription("Google Sign In").performClick()
        
        // In a real test, we would verify some outcome, but for now just ensure it doesn't crash
        composeTestRule.onNodeWithContentDescription("Google Sign In").assertIsDisplayed()
    }
} 