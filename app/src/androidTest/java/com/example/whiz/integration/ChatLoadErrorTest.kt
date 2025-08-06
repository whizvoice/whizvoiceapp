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
import com.example.whiz.data.remote.WhizServerRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After

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
    
    @Inject
    lateinit var whizServerRepository: WhizServerRepository
    
    companion object {
        private const val TAG = "ChatLoadErrorTest"
        private const val TEST_TIMEOUT = 10000L
    }
    
    // Track created chats for cleanup
    private val createdChatIds = mutableListOf<Long>()
    
    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication()
        
        // Grant microphone permission
        device.executeShellCommand("pm grant com.example.whiz.debug android.permission.RECORD_AUDIO")
        permissionManager.updateMicrophonePermission(true)
        
        // Reset test interceptor state
        testInterceptor.resetErrorState()
        
        // Configure TestInterceptor to check WebSocket persistent disconnect state
        TestInterceptor.persistentDisconnectForTestCheck = { whizServerRepository.persistentDisconnectForTest() }
        TestInterceptor.simulateNetworkErrorForManualDisconnect = true
        
        Log.d(TAG, "Test setup complete")
    }
    
    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "Cleaning up test data...")
            
            // Clean up any created chats
            if (createdChatIds.isNotEmpty()) {
                // Use the same cleanup approach as WebSocketReconnectionTest
                cleanupTestChats(
                    repository = repository,
                    trackedChatIds = createdChatIds,
                    additionalPatterns = listOf(
                        "Test message for connection error test",
                        "connection error test"
                    ),
                    enablePatternFallback = true
                )
                createdChatIds.clear()
            }
            
            // Ensure WebSocket is reconnected for next test and reset persistent disconnect flag
            if (!whizServerRepository.isConnected()) {
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
                // Wait for connection
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
            }
            
            // Reset TestInterceptor state
            TestInterceptor.persistentDisconnectForTestCheck = null
            TestInterceptor.simulateNetworkErrorForManualDisconnect = true
            
            Log.d(TAG, "Cleanup complete")
        }
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
        
        // After retry, the interceptor returns 404, which should create a new chat
        // Wait for new chat UI to appear
        val newChatCreated = ComposeTestHelper.waitForElement(
            composeTestRule,
            { composeTestRule.onNodeWithText("Start chatting with Whiz!\nType or tap the mic.") },
            TEST_TIMEOUT,
            "new chat placeholder after retry"
        )
        
        if (!newChatCreated) {
            failWithScreenshot("Retry should create a new chat when server returns 404", "retry_no_new_chat")
        }
        
        // Verify we have the input field in the new chat
        val hasInputField = try {
            composeTestRule.onNodeWithTag("chat_input_field").assertExists()
            true
        } catch (e: Exception) {
            false
        }
        
        if (!hasInputField) {
            failWithScreenshot("New chat should have input field", "retry_new_chat_no_input")
        }
        
        // Verify error UI is gone
        try {
            composeTestRule.onNodeWithText("Couldn't load this chat").assertDoesNotExist()
            Log.d(TAG, "Confirmed: Error UI removed and new chat created after retry")
        } catch (e: Exception) {
            failWithScreenshot("Error UI still visible after retry created new chat", "retry_error_ui_still_visible")
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
    
    /**
     * Test that connection errors during chat load show the error UI
     * Uses TestInterceptor to simulate network errors
     */
    @Test
    fun testConnectionError_ShowsErrorUI() {
        runBlocking {
            Log.d(TAG, "Starting testConnectionError_ShowsErrorUI")
            
            // App is already launched by createAndroidComposeRule
            // Handle potential voice launch by checking if we're on chat screen
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                    return@runBlocking
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
                return@runBlocking
            }
            
            // Disconnect WebSocket to simulate connection error
            Log.d(TAG, "Disconnecting WebSocket to simulate connection error...")
            whizServerRepository.disconnect(setPersistentDisconnect = true)
            
            // Wait for WebSocket to disconnect
            Log.d(TAG, "Waiting for WebSocket to disconnect...")
            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            Log.d(TAG, "WebSocket disconnected - TestInterceptor will now throw IOException for API calls")
            
            // Navigate to any chat ID - the interceptor will throw IOException
            val chatId = 12345L
            Log.d(TAG, "Navigating to chat ID: $chatId (will fail with IOException)")
            navigateToChatId(chatId)
            
            // Wait for error UI to appear
            // The exact error message may vary, but should indicate connection failure
            val errorAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("Couldn't load this chat") },
                TEST_TIMEOUT,
                "error message for connection failure"
            )
            
            if (!errorAppeared) {
                failWithScreenshot("Connection error should show error UI", "connection_error_no_ui")
            }
            
            // Verify no input field is shown
            try {
                composeTestRule.onNodeWithTag("chat_input_field").assertDoesNotExist()
            } catch (e: Exception) {
                failWithScreenshot("Input field should not be visible during connection error", "connection_error_has_input")
            }
            
            // Verify retry button exists
            val retryButtonExists = try {
                composeTestRule.onNodeWithText("Retry").assertExists()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!retryButtonExists) {
                failWithScreenshot("Retry button should be visible for connection error", "connection_error_no_retry")
            }
            
            // Verify go back button exists
            val goBackButtonExists = try {
                composeTestRule.onNodeWithText("Go back").assertExists()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!goBackButtonExists) {
                failWithScreenshot("Go back button should be visible for connection error", "connection_error_no_go_back")
            }
            
        }
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
        
        // Use the NavController directly to avoid activity lifecycle issues
        composeTestRule.runOnUiThread {
            val navController = composeTestRule.activity.getNavController()
            if (navController != null) {
                Log.d(TAG, "Using NavController to navigate to chat/$chatId")
                navController.navigate("chat/$chatId") {
                    launchSingleTop = true
                }
            } else {
                Log.e(TAG, "NavController not available, cannot navigate")
            }
        }
        
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