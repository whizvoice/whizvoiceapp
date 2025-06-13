package com.example.whiz.examples

import android.util.Log
import com.example.whiz.BaseIntegrationTest
import com.example.whiz.BaseLoginTest
import com.example.whiz.data.repository.WhizRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.example.whiz.di.AppModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Example tests demonstrating the automatic authentication system.
 * 
 * REGULAR TESTS: Extend BaseIntegrationTest for automatic authentication
 * LOGIN UI TESTS: Extend BaseLoginTest to skip automatic authentication
 */

@UninstallModules(AppModule::class)
@HiltAndroidTest
class AutoAuthenticationExampleTest : BaseIntegrationTest() {
    
    @Inject
    lateinit var repository: WhizRepository

    companion object {
        private const val TAG = "AutoAuthExample"
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This automatically handles authentication!
        Log.d(TAG, "✅ Test setup complete - authentication handled automatically")
    }

    @Test
    fun example_regularTest_hasAutomaticAuthentication() {
        runTest {
            Log.d(TAG, "🧪 Example: Regular test with automatic authentication")
            
            // No need to manually authenticate - it's done automatically!
            // Just verify we're authenticated
            val userProfile = authRepository.userProfile.first()
            val serverToken = authRepository.serverToken.first()
            
            Log.d(TAG, "📋 Authentication status:")
            Log.d(TAG, "  User: ${userProfile?.email}")
            Log.d(TAG, "  Server token: ${serverToken != null}")
            Log.d(TAG, "  Signed in: ${authRepository.isSignedIn()}")
            
            // Test can now make API calls
            val testChatTitle = "Auto-auth example - ${System.currentTimeMillis()}"
            val chatId = repository.createChat(testChatTitle)
            
            assert(chatId > 0) { "Chat creation should succeed with automatic authentication" }
            Log.d(TAG, "✅ Created chat with ID: $chatId")
            
            // Clean up
            repository.deleteChat(chatId)
            Log.d(TAG, "✅ Example test completed successfully")
        }
    }
}

/**
 * Example test for login UI that skips automatic authentication
 */
@UninstallModules(AppModule::class)
@HiltAndroidTest
class LoginUIExampleTest : BaseLoginTest() {

    companion object {
        private const val TAG = "LoginUIExample"
    }

    @Before
    override fun setUpAuthentication() {
        super.setUpAuthentication() // This SKIPS automatic authentication for login UI tests
        Log.d(TAG, "✅ Login UI test setup complete - no automatic authentication")
    }

    @Test
    fun example_loginUITest_skipsAutomaticAuthentication() {
        runTest {
            Log.d(TAG, "🧪 Example: Login UI test without automatic authentication")
            
            // This test can test the login UI without interference
            // No automatic authentication was performed
            
            Log.d(TAG, "📋 Authentication status (should be unauthenticated):")
            Log.d(TAG, "  Signed in: ${authRepository.isSignedIn()}")
            
            // Test login UI functionality here...
            // For example, test that login button is visible, etc.
            
            Log.d(TAG, "✅ Login UI test completed - can test login flow without interference")
        }
    }
} 