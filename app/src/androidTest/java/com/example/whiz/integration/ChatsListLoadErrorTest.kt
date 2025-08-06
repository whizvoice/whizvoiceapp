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
 * Integration tests for chats list offline scenarios.
 * Tests that a snackbar is shown when the app is offline and showing cached data.
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
     * Test that being offline shows a snackbar with connection status
     */
    @Test
    fun testOfflineMode_ShowsSnackbar() {
        runBlocking {
            Log.d(TAG, "Starting testOfflineMode_ShowsSnackbar")
            
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
            
            // Wait for snackbar to appear
            val snackbarAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("No connection. Showing offline data") },
                TEST_TIMEOUT,
                "offline snackbar"
            )
            
            if (!snackbarAppeared) {
                failWithScreenshot("Offline mode should show snackbar", "offline_no_snackbar")
                return@runBlocking
            }
            
            // Verify chats list is still shown
            val chatListVisible = try {
                composeTestRule.onNodeWithText("My Chats").assertExists()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!chatListVisible) {
                failWithScreenshot("Chat list should remain visible when offline", "offline_no_chat_list")
            }
            
            Log.d(TAG, "Test completed - offline mode shows snackbar correctly")
        }
    }
    
    /**
     * Test that snackbar disappears when connection is restored
     */
    @Test
    fun testSnackbarDisappears_WhenConnectionRestored() {
        runBlocking {
            Log.d(TAG, "Starting testSnackbarDisappears_WhenConnectionRestored")
            
            // First ensure we're on the chat list and have shown the offline snackbar
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                    return@runBlocking
                }
            }
            
            // Disconnect to show offline snackbar
            Log.d(TAG, "Disconnecting WebSocket to trigger offline mode...")
            whizServerRepository.disconnect()
            
            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            
            // Trigger refresh to show snackbar
            composeTestRule.onRoot().performTouchInput {
                swipeDown(
                    startY = centerY - (height * 0.2f),
                    endY = centerY + (height * 0.2f)
                )
            }
            
            // Wait for snackbar
            val snackbarAppeared = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("No connection. Showing offline data") },
                TEST_TIMEOUT,
                "offline snackbar"
            )
            
            if (!snackbarAppeared) {
                failWithScreenshot("Snackbar should appear when offline", "no_initial_snackbar")
                return@runBlocking
            }
            
            // Now reconnect
            Log.d(TAG, "Reconnecting WebSocket...")
            whizServerRepository.connect()
            
            withTimeout(5000) {
                while (!whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            
            // Reset interceptor
            testInterceptor.resetErrorState()
            TestInterceptor.simulateNetworkErrorForManualDisconnect = false
            
            // Trigger another refresh when online
            composeTestRule.onRoot().performTouchInput {
                swipeDown(
                    startY = centerY - (height * 0.2f),
                    endY = centerY + (height * 0.2f)
                )
            }
            
            // Wait a bit for the refresh to complete
            delay(2000)
            
            // Verify snackbar is gone (or shows different message)
            val snackbarGone = try {
                composeTestRule.onNodeWithText("No connection. Showing offline data").assertDoesNotExist()
                true
            } catch (e: Exception) {
                false
            }
            
            if (!snackbarGone) {
                failWithScreenshot("Snackbar should disappear when connection restored", "snackbar_still_showing")
            }
            
            Log.d(TAG, "Test completed - snackbar behavior correct")
        }
    }
}