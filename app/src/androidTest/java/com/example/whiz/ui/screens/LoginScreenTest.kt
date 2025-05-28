package com.example.whiz.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginScreen_displaysWelcomeMessage() {
        // When
        composeTestRule.setContent {
            LoginScreen(
                isLoading = false,
                onGoogleSignIn = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Welcome to Whiz").assertIsDisplayed()
    }

    @Test
    fun loginScreen_displaysGoogleSignInButton() {
        // When
        composeTestRule.setContent {
            LoginScreen(
                isLoading = false,
                onGoogleSignIn = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Sign in with Google").assertIsDisplayed()
    }

    @Test
    fun loginScreen_googleSignInButton_triggersCallback() {
        // Given
        var signInClicked = false

        // When
        composeTestRule.setContent {
            LoginScreen(
                isLoading = false,
                onGoogleSignIn = { signInClicked = true }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Sign in with Google").performClick()
        assert(signInClicked)
    }

    @Test
    fun loginScreen_showsLoadingIndicator_whenLoading() {
        // When
        composeTestRule.setContent {
            LoginScreen(
                isLoading = true,
                onGoogleSignIn = { }
            )
        }

        // Then
        composeTestRule.onNodeWithContentDescription("Loading").assertIsDisplayed()
    }

    @Test
    fun loginScreen_disablesSignInButton_whenLoading() {
        // When
        composeTestRule.setContent {
            LoginScreen(
                isLoading = true,
                onGoogleSignIn = { }
            )
        }

        // Then
        composeTestRule.onNodeWithText("Sign in with Google").assertIsNotEnabled()
    }
} 