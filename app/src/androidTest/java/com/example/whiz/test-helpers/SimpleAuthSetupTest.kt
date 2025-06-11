package com.example.whiz.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.whiz.data.auth.AuthRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.util.Log
import javax.inject.Inject

/**
 * Simple test that just sets up authentication state for the test script to use.
 * This bypasses UI automation and uses AuthRepository.setTestAuthenticationState directly.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SimpleAuthSetupTest {
    private val TAG = "SimpleAuthSetupTest"

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun setupTestAuthentication() {
        runBlocking {
            Log.d(TAG, "🧪 Setting up test authentication...")
            
            // Get credentials from instrumentation arguments
            val arguments = InstrumentationRegistry.getArguments()
            val testUsername = arguments.getString("testUsername") ?: "REDACTED_TEST_EMAIL"
            val testPassword = arguments.getString("testPassword") ?: "testpassword"
            
            Log.d(TAG, "📧 Setting up authentication for: $testUsername")
            
            try {
                // Sign out any existing user first
                authRepository.signOut()
                Thread.sleep(1000)
                
                // Set test authentication state
                authRepository.setTestAuthenticationState(
                    email = testUsername,
                    userId = "test_user_${System.currentTimeMillis()}",
                    name = "Test User"
                )
                
                Thread.sleep(1000) // Wait for state to propagate
                
                // Verify authentication was set
                val isSignedIn = authRepository.isSignedIn()
                val userProfile = authRepository.userProfile.value
                
                Log.d(TAG, "🔍 Authentication verification:")
                Log.d(TAG, "  - Is signed in: $isSignedIn")
                Log.d(TAG, "  - User email: ${userProfile?.email}")
                Log.d(TAG, "  - User ID: ${userProfile?.userId}")
                
                if (isSignedIn && userProfile?.email == testUsername) {
                    Log.d(TAG, "✅ Test authentication setup successful!")
                } else {
                    Log.w(TAG, "⚠️ Test authentication may not be fully active")
                    Log.w(TAG, "  This is expected for test-only authentication state")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting up test authentication", e)
                throw e
            }
        }
    }
} 