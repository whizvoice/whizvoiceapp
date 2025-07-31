package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.R
import androidx.navigation.findNavController
import androidx.navigation.Navigation
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestInterceptor
import com.example.whiz.di.TestAppModule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Integration tests for chat loading error scenarios.
 * Uses TestInterceptor to simulate different HTTP error codes.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatLoadErrorTest : BaseIntegrationTest() {
    
    @get:Rule(order = 2)
    val composeTestRule = createAndroidComposeRule<MainActivity>()
    
    @Inject
    lateinit var repository: WhizRepository
    
    @Inject
    lateinit var testInterceptor: TestInterceptor
    
    @Inject
    lateinit var permissionManager: com.example.whiz.permissions.PermissionManager
    
    companion object {
        private const val TAG = "ChatLoadErrorTest"
        private const val TEST_TIMEOUT = 10000L
    }
    
    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Grant microphone permission
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)
        
        // Reset test interceptor state
        testInterceptor.resetErrorState()
        
        Log.d(TAG, "Test setup complete")
    }
    
    /**
     * Test that a 404 error creates a new chat
     */
    @Test
    fun test404Error_CreatesNewChat() {
        Log.d(TAG, "Starting test404Error_CreatesNewChat")
        
        // App is already launched by createAndroidComposeRule
        // Handle potential voice launch by checking if we're on chat screen
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                return
            }
        }
        
        // Ensure we're on the chat list
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )
        
        if (!chatListReady) {
            failWithScreenshot("Chat list not ready", "chat_list_not_ready")
            return
        }
        
        // Navigate to the 404 chat
        navigateToChatId(TestInterceptor.CHAT_ID_404)
        
        // Should create a new chat - verify by checking for BOTH empty chat UI and input field
        val placeholderExists = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("Start chatting with Whiz!\nType or tap the mic.") },
            TEST_TIMEOUT,
            "new chat placeholder"
        )
        
        // Check for input field using Compose
        val hasInputField = try {
            composeTestRule.onNodeWithTag("chat_input_field").assertExists()
            true
        } catch (e: Exception) {
            false
        }
        val isInNewChat = placeholderExists && hasInputField
        
        if (!isInNewChat) {
            failWithScreenshot("404 should create a new chat", "404_no_new_chat")
        }
        
        // Verify NO error UI is shown
        try {
            composeTestRule.onNodeWithText("Couldn't load this chat").assertDoesNotExist()
            Log.d(TAG, "Confirmed: No error UI shown for 404")
        } catch (e: Exception) {
            failWithScreenshot("404 should NOT show error UI", "404_has_error_ui")
        }
        
        Log.d(TAG, "Test completed - 404 creates new chat as expected")
    }
    
    /**
     * Test that a 500 error shows the error UI
     */
    @Test
    fun test500Error_ShowsErrorUI_and_check_retry_button() {
        Log.d(TAG, "Starting test500Error_ShowsErrorUI")
        
        // App is already launched by createAndroidComposeRule
        // Handle potential voice launch by checking if we're on chat screen
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                return
            }
        }
        
        // Ensure we're on the chat list
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )
        
        if (!chatListReady) {
            failWithScreenshot("Chat list not ready", "chat_list_not_ready")
            return
        }
        
        // Navigate to the 500 error chat
        navigateToChatId(TestInterceptor.CHAT_ID_500)
        
        // Wait for error UI to appear
        val errorAppeared = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("Couldn't load this chat") },
            TEST_TIMEOUT,
            "error message"
        )
        
        if (!errorAppeared) {
            failWithScreenshot("500 error should show error UI", "500_no_error_ui")
        }
        
        // Verify no input field is shown
        try {
            composeTestRule.onNodeWithTag("chat_input_field").assertDoesNotExist()
        } catch (e: Exception) {
            failWithScreenshot("Input field should not be visible during error state", "error_has_input_field")
        }
        
        // Click retry button
        Log.d(TAG, "Clicking retry button")
        composeTestRule.onNodeWithText("Retry").performClick()
        
        // Wait for successful load - should show input field
        val chatLoaded = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithTag("chat_input_field") },
            TEST_TIMEOUT,
            "chat input field after retry"
        )
        
        if (!chatLoaded) {
            failWithScreenshot("Chat should load successfully after retry", "retry_failed")
        }
        
        // Verify error UI is gone using Compose testing
        try {
            composeTestRule.waitUntil(5000) {
                // Check that error UI doesn't exist
                try {
                    composeTestRule.onAllNodesWithText("Couldn't load this chat").fetchSemanticsNodes().isEmpty()
                } catch (e: Exception) {
                    false
                }
            }
            Log.d(TAG, "Confirmed: Error UI removed after successful retry")
        } catch (e: Exception) {
            failWithScreenshot("Chat not fully loaded after retry - error UI may still be visible", "retry_chat_not_loaded")
        }
        
        Log.d(TAG, "Test completed - 500 error shows error UI and retry button works")
    }
    
    
    /**
     * Test that the go back button returns to chat list
     */
    @Test
    fun testGoBackButton_NavigatesToChatList() {
        Log.d(TAG, "Starting testGoBackButton_NavigatesToChatList")
        
        // App is already launched by createAndroidComposeRule
        // Handle potential voice launch by checking if we're on chat screen
        if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
            Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
            if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                return
            }
        }
        
        // Ensure we're on the chat list
        val chatListReady = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            TEST_TIMEOUT,
            "chat list to load"
        )
        
        if (!chatListReady) {
            failWithScreenshot("Chat list not ready", "chat_list_not_ready")
            return
        }
        
        // Navigate to the 403 error chat
        navigateToChatId(TestInterceptor.CHAT_ID_403)
        
        // Wait for error UI
        val errorAppeared = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("Couldn't load this chat") },
            TEST_TIMEOUT,
            "error message before go back"
        )
        
        if (!errorAppeared) {
            failWithScreenshot("Error UI did not appear before go back", "go_back_test_no_initial_error")
        }
        
        // Click go back button
        Log.d(TAG, "Clicking Go back button")
        composeTestRule.onNodeWithText("Go back").performClick()

        // Verify we're back on chat list by looking for chat list UI elements
        val onChatList = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("My Chats") },
            5000,
            "chat list screen"
        )
        
        if (!onChatList) {
            failWithScreenshot("Should navigate back to chat list", "go_back_failed")
        }
        
        // Verify error UI is gone
        try {
            composeTestRule.onNodeWithText("Couldn't load this chat").assertDoesNotExist()
            Log.d(TAG, "Confirmed: Error UI gone after go back")
        } catch (e: Exception) {
            failWithScreenshot("Error UI still visible after go back", "go_back_error_ui_still_visible")
        }
        
        Log.d(TAG, "Test completed - go back button works")
    }
    
    // Helper methods
    
    private fun navigateToChatId(chatId: Long) {
        Log.d(TAG, "Navigating to chat ID: $chatId")
        
        // The TestInterceptor is configured to intercept specific chat IDs:
        // - CHAT_ID_404 (404404) returns 404
        // - CHAT_ID_500 (500500) returns 500
        // - CHAT_ID_401 (401401) returns 401
        // - CHAT_ID_403 (403403) returns 403
        // - CHAT_ID_503 (503503) returns 503
        
        // Navigate by sending a new intent to the existing activity
        // Since we can't create new activities, we'll use the test context and rely on SINGLE_TOP
        val activity = composeTestRule.activity
        val intent = Intent(activity, MainActivity::class.java).apply {
            putExtra("NAVIGATE_TO_CHAT_ID", chatId)
            putExtra("FORCE_NAVIGATION", true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        Log.d(TAG, "Sending navigation intent with NAVIGATE_TO_CHAT_ID: $chatId")
        // Use the activity context to start the activity, which should route to onNewIntent
        // because of FLAG_ACTIVITY_SINGLE_TOP
        activity.startActivity(intent)
        
        // Wait for navigation to complete by checking for chat screen elements
        // For error cases, wait for error UI or for 404 wait for new chat UI
        val navigationComplete = when (chatId) {
            TestInterceptor.CHAT_ID_404 -> {
                // For 404, we expect a new chat screen
                ComposeTestHelper.waitForElement(
                    composeTestRule,
                    { composeTestRule.onNodeWithText("Start chatting with Whiz!\nType or tap the mic.") },
                    5000,
                    "new chat placeholder after navigation"
                )
            }
            else -> {
                // For other errors, we expect error UI
                ComposeTestHelper.waitForElement(
                    composeTestRule,
                    { composeTestRule.onNodeWithText("Couldn't load this chat") },
                    5000,
                    "error UI after navigation"
                )
            }
        }
        
        if (!navigationComplete) {
            Log.w(TAG, "Navigation to chat $chatId may not have completed properly")
        }
        
        Log.d(TAG, "Navigation to chat $chatId completed")
    }
}