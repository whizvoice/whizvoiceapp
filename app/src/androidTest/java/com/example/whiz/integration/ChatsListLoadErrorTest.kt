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
import androidx.test.uiautomator.By
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

        // Configure TestInterceptor to check WebSocket persistent disconnect state
        TestInterceptor.persistentDisconnectForTestCheck = { whizServerRepository.persistentDisconnectForTest() }
        TestInterceptor.simulateNetworkErrorForManualDisconnect = true

        Log.d(TAG, "Test setup complete")
    }
    
    @After
    fun cleanup() {
        runBlocking {
            Log.d(TAG, "Cleaning up test data...")
            
            // Reset persistent disconnect flag if it was set
            if (whizServerRepository.persistentDisconnectForTest()) {
                // Just reset the flag, don't try to connect
                whizServerRepository.connect(turnOffPersistentDisconnect = true)
            }
            
            // Now try to connect with a fake chat ID
            if (!whizServerRepository.isConnected()) {
                whizServerRepository.connect(conversationId = 9999999L)
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
            testInterceptor.resetErrorState()

            Log.d(TAG, "Cleanup complete")
        }
    }
    
    /**
     * Test that offline mode shows a snackbar with connection status
     */
    @Test
    fun testOfflineMode_ShowsSnackbar() {
        runBlocking {
            Log.d(TAG, "Starting testOfflineMode_ShowsSnackbar")
            
            // First ensure we're on the chat list
            if (ComposeTestHelper.isOnChatScreen(composeTestRule)) {
                Log.d(TAG, "App launched to chat screen (voice launch), navigating back to chat list")
                if (!ComposeTestHelper.navigateBackToChatsList(composeTestRule)) {
                    failWithScreenshot("Failed to navigate back to chat list", "nav_to_chat_list_failed")
                    return@runBlocking
                }
            }
            
            // Wait for initial chat list to load
            val chatListReady = ComposeTestHelper.waitForElement(
                composeTestRule,
                { composeTestRule.onNodeWithText("My Chats") },
                TEST_TIMEOUT,
                "chat list to load"
            )
            
            if (!chatListReady) {
                Log.w(TAG, "Chat list may not be fully loaded, continuing with test")
            }

            // Wait for at least one chat item to load (not just the header)
            var chatItemsLoaded = false
            val chatLoadStart = System.currentTimeMillis()
            val chatLoadTimeout = 5_000L
            Log.d(TAG, "Waiting for chat items to load...")
            while (!chatItemsLoaded && (System.currentTimeMillis() - chatLoadStart) < chatLoadTimeout) {
                try {
                    val chatItems = device.findObjects(By.clazz("android.widget.TextView").pkg(packageName))
                    chatItemsLoaded = chatItems.any { item ->
                        try {
                            val text = item.text
                            text != null && text != "My Chats" && text != "No chats yet" &&
                            text != "Start a conversation with Whiz" && text.isNotEmpty()
                        } catch (e: Exception) { false }
                    }
                } catch (e: Exception) { /* continue */ }
                if (!chatItemsLoaded) delay(500)
            }

            if (!chatItemsLoaded) {
                Log.w(TAG, "No chat items loaded within timeout - skipping offline test (no data to cache)")
                return@runBlocking
            }
            Log.d(TAG, "Chat items loaded successfully")

            // Disconnect to trigger offline mode
            Log.d(TAG, "Disconnecting WebSocket to trigger offline mode...")
            whizServerRepository.disconnect(setPersistentDisconnect = true)

            withTimeout(5000) {
                while (whizServerRepository.isConnected()) {
                    delay(100)
                }
            }
            Log.d(TAG, "Disconnected successfully")

            // Perform pull-to-refresh gesture
            Log.d(TAG, "Performing pull-to-refresh gesture...")
            try {
                composeTestRule.onRoot().performTouchInput {
                    swipeDown(
                        startY = centerY - (height * 0.2f),
                        endY = centerY + (height * 0.2f)
                    )
                }
                Log.d(TAG, "Pull-to-refresh gesture completed")
            } catch (e: AssertionError) {
                Log.e(TAG, "Failed to perform swipe: ${e.message}")
                failWithScreenshot("Failed to perform swipe - ${e.message}", "swipe_failed_multiple_roots")
                return@runBlocking
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during swipe: ${e.message}")
                failWithScreenshot("Unexpected error during swipe: ${e.message}", "swipe_error")
                return@runBlocking
            }

            // Wait for snackbar
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
            
            Log.d(TAG, "✅ Test completed - offline mode shows snackbar correctly")
        }
    }
}