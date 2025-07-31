package com.example.whiz.integration

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.R
import androidx.navigation.findNavController
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
    
    @get:Rule
    val composeTestRule = createComposeRule()
    
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
        
        // Launch the app manually to avoid voice launch
        launchAppAndWaitForLoad()
        
        // Make sure we're on the chat list screen
        ComposeTestHelper.navigateBackToChatsList(composeTestRule)
        
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
        
        // Launch app and navigate to home
        launchAppAndWaitForLoad()
        
        // Make sure we're on the chat list screen
        ComposeTestHelper.navigateBackToChatsList(composeTestRule)
        
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
        
        // Launch app and navigate
        launchAppAndWaitForLoad()
        
        // Make sure we're on the chat list screen
        ComposeTestHelper.navigateBackToChatsList(composeTestRule)
        
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
        
        // Since the MainActivity is already running from launchAppAndWaitForLoad(),
        // we need to trigger navigation to the specific chat ID by sending a new intent
        
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("NAVIGATE_TO_CHAT_ID", chatId)
            putExtra("FORCE_NAVIGATION", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        Log.d(TAG, "Sending navigation intent with NAVIGATE_TO_CHAT_ID: $chatId")
        
        // Launch the intent to navigate to the chat
        context.startActivity(intent)
        
        // Wait for navigation to complete
        Thread.sleep(2000)
        composeTestRule.waitForIdle()
        
        Log.d(TAG, "Navigation to chat $chatId completed")
    }
}