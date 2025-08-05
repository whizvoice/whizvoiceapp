package com.example.whiz.integration

import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.MainActivity
import com.example.whiz.test_helpers.ComposeTestHelper
import com.example.whiz.data.repository.WhizRepository
import com.example.whiz.di.AppModule
import com.example.whiz.di.TestInterceptor
import com.example.whiz.di.TestAppModule
import com.example.whiz.data.remote.WhizServerRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import org.junit.After

/**
 * Integration tests for chats list loading error scenarios.
 * Tests error handling when the chats list fails to load due to network issues.
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChatsListLoadErrorTest : BaseIntegrationTest() {
    
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
        private const val TAG = "ChatsListLoadErrorTest"
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
        
        // Configure TestInterceptor to check WebSocket manual disconnect state
        TestInterceptor.isManuallyDisconnectedCheck = { whizServerRepository.isManuallyDisconnected() }
        TestInterceptor.simulateNetworkErrorForManualDisconnect = true
        
        Log.d(TAG, "Test setup complete")
    }
    
    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "Cleaning up test data...")
            
            // Ensure WebSocket is reconnected for next test
            if (!whizServerRepository.isConnected()) {
                whizServerRepository.connect()
                // Wait for connection
                withTimeout(5000) {
                    while (!whizServerRepository.isConnected()) {
                        delay(100)
                    }
                }
            }
            
            // Reset TestInterceptor state
            TestInterceptor.isManuallyDisconnectedCheck = null
            TestInterceptor.simulateNetworkErrorForManualDisconnect = true
            testInterceptor.resetErrorState()
            
            Log.d(TAG, "Cleanup complete")
        }
    }
    
    /**
     * Test that connection errors during chats list load show the error UI
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
            
            // Wait for initial chat list to load (or fail)
            delay(1000)
            
            // Disconnect WebSocket to simulate connection error
            Log.d(TAG, "Disconnecting WebSocket to simulate connection error...")
            whizServerRepository.disconnect()
            
            // Wait for WebSocket to disconnect
            Log.d(TAG, "Waiting for WebSocket to disconnect...")
            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            Log.d(TAG, "WebSocket disconnected - TestInterceptor will now throw IOException for API calls")
            
            // Force a refresh which should fail with network error
            Log.d(TAG, "Triggering pull-to-refresh to force network error")
            
            // Perform swipe down gesture to trigger refresh
            composeTestRule.onRoot().performTouchInput {
                swipeDown(
                    startY = centerY - (height * 0.2f),
                    endY = centerY + (height * 0.2f)
                )
            }
            
            // Wait for error UI to appear
            val errorAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("Couldn't load chats") },
                TEST_TIMEOUT,
                "error message for chats list"
            )
            
            if (!errorAppeared) {
                failWithScreenshot("Connection error should show error UI", "chats_list_no_error_ui")
                return@runBlocking
            }
            
            // Verify error details are shown
            val hasErrorDetails = try {
                composeTestRule.onNodeWithText("Check your connection and try again").assertExists()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!hasErrorDetails) {
                failWithScreenshot("Error UI should show connection message", "chats_list_error_no_details")
            }
            
            // Verify retry button exists
            val retryButtonExists = try {
                composeTestRule.onNodeWithText("Retry").assertExists()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!retryButtonExists) {
                failWithScreenshot("Retry button should be visible for connection error", "chats_list_no_retry")
            }
            
            // Verify no chats list is shown
            try {
                composeTestRule.onNodeWithText("My Chats").assertExists()
                // The app bar should still be visible
            } catch (e: Exception) {
                failWithScreenshot("App bar should remain visible during error", "chats_list_no_app_bar")
            }
            
            Log.d(TAG, "Test completed - connection error shows error UI correctly")
        }
    }
    
    /**
     * Test that the retry button attempts to reload chats
     */
    @Test
    fun testRetryButton_AttemptsReload() {
        runBlocking {
            Log.d(TAG, "Starting testRetryButton_AttemptsReload")
            
            // App is already launched by createAndroidComposeRule
            // Handle potential voice launch by checking if we're on chat screen
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                    return@runBlocking
                }
            }
            
            // Wait for initial load
            delay(1000)
            
            // Disconnect WebSocket to simulate connection error
            Log.d(TAG, "Disconnecting WebSocket to simulate connection error...")
            whizServerRepository.disconnect()
            
            // Wait for disconnect
            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            
            // Trigger refresh to get error state
            composeTestRule.onRoot().performTouchInput {
                swipeDown(
                    startY = centerY - (height * 0.2f),
                    endY = centerY + (height * 0.2f)
                )
            }
            
            // Wait for error UI
            val errorAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("Couldn't load chats") },
                TEST_TIMEOUT,
                "error message"
            )
            
            if (!errorAppeared) {
                failWithScreenshot("Error UI did not appear", "retry_test_no_error_ui")
                return@runBlocking
            }
            
            // Reconnect WebSocket before clicking retry
            Log.d(TAG, "Reconnecting WebSocket before retry...")
            whizServerRepository.connect()
            
            // Wait for reconnection
            withTimeout(5000) {
                while (!whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            Log.d(TAG, "WebSocket reconnected")
            
            // Reset interceptor to allow successful requests
            testInterceptor.resetErrorState()
            TestInterceptor.simulateNetworkErrorForManualDisconnect = false
            
            // Click retry button
            Log.d(TAG, "Clicking retry button")
            composeTestRule.onNodeWithText("Retry").performClick()
            
            // Wait for error to clear - first check if empty state appears
            val successfulRetry = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("No chats yet") },
                TEST_TIMEOUT,
                "empty chats list after retry"
            )
            
            if (!successfulRetry) {
                // Check if error is still showing
                val errorStillShowing = try {
                    composeTestRule.onNodeWithText("Couldn't load chats").assertExists()
                    true
                } catch (e: Exception) {
                    false
                }
                
                if (errorStillShowing) {
                    failWithScreenshot("Retry did not clear error state", "retry_error_still_showing")
                } else {
                    failWithScreenshot("Unexpected state after retry", "retry_unexpected_state")
                }
            }
            
            // Verify error UI is gone
            try {
                composeTestRule.onNodeWithText("Couldn't load chats").assertDoesNotExist()
                Log.d(TAG, "Confirmed: Error UI removed after successful retry")
            } catch (e: Exception) {
                failWithScreenshot("Error UI still visible after successful retry", "retry_error_ui_remains")
            }
            
            Log.d(TAG, "Test completed - retry button works correctly")
        }
    }
    
    /**
     * Test that error appears on initial load when there's no connection
     */
    @Test
    fun testInitialLoadError_ShowsErrorUI() {
        runBlocking {
            Log.d(TAG, "Starting testInitialLoadError_ShowsErrorUI")
            
            // Disconnect WebSocket BEFORE the app loads chats
            Log.d(TAG, "Disconnecting WebSocket before app loads...")
            whizServerRepository.disconnect()
            
            // Wait for disconnect
            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            
            // The TestInterceptor will automatically throw IOException for all API calls
            // when WebSocket is manually disconnected (already configured in setUp)
            
            // Now navigate to trigger initial load
            // App is already launched by createAndroidComposeRule
            // Handle potential voice launch by checking if we're on chat screen
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                    return@runBlocking
                }
            }
            
            // Wait for error UI to appear on initial load
            val errorAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("Couldn't load chats") },
                TEST_TIMEOUT,
                "error message on initial load"
            )
            
            if (!errorAppeared) {
                // Check if we see empty state instead (which would be wrong)
                val showsEmptyState = try {
                    composeTestRule.onNodeWithText("No chats yet").assertExists()
                    true
                } catch (e: Exception) {
                    false
                }
                
                if (showsEmptyState) {
                    failWithScreenshot("Shows empty state instead of error on connection failure", "initial_load_wrong_state")
                } else {
                    failWithScreenshot("Initial load error should show error UI", "initial_load_no_error_ui")
                }
            }
            
            Log.d(TAG, "Test completed - initial load error shows error UI correctly")
        }
    }
}