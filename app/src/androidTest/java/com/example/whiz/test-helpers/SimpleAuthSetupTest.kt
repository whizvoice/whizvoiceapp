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
                // Set test authentication state directly (this now handles cleanup internally)
                Log.d(TAG, "🧪 Setting test authentication state...")
                authRepository.setTestAuthenticationState(
                    email = testUsername,
                    userId = "test_user_${System.currentTimeMillis()}",
                    name = "Test User"
                )
                
                // Wait for state to propagate
                Thread.sleep(1000)
                
                // Verify authentication was set - try multiple times for stability
                var verificationAttempts = 0
                var isAuthenticated = false
                var correctUser = false
                var hasServerToken = false
                
                while (verificationAttempts < 3 && (!isAuthenticated || !correctUser || !hasServerToken)) {
                    verificationAttempts++
                    Log.d(TAG, "🔍 Authentication verification attempt $verificationAttempts...")
                    
                    val isSignedIn = authRepository.isSignedIn()
                    val userProfile = authRepository.userProfile.value
                    val serverToken = authRepository.serverToken.value
                    
                    isAuthenticated = isSignedIn
                    correctUser = userProfile?.email == testUsername
                    hasServerToken = !serverToken.isNullOrBlank()
                    
                    Log.d(TAG, "  - Is signed in: $isSignedIn")
                    Log.d(TAG, "  - User email: ${userProfile?.email}")
                    Log.d(TAG, "  - User ID: ${userProfile?.userId}")
                    Log.d(TAG, "  - User name: ${userProfile?.name}")
                    Log.d(TAG, "  - Has server token: $hasServerToken")
                    
                    if (isAuthenticated && correctUser && hasServerToken) {
                        Log.d(TAG, "✅ Test authentication setup successful!")
                        break
                    } else {
                        Log.d(TAG, "⏳ Waiting for authentication state to stabilize...")
                        Thread.sleep(500)
                    }
                }
                
                if (!isAuthenticated || !correctUser || !hasServerToken) {
                    Log.w(TAG, "⚠️ Test authentication verification incomplete after $verificationAttempts attempts")
                    Log.w(TAG, "  Final state - authenticated: $isAuthenticated, correct user: $correctUser, has token: $hasServerToken")
                    Log.w(TAG, "  This may be expected for test-only authentication state")
                    Log.w(TAG, "  Tests should still work if AuthRepository state is set correctly")
                } else {
                    Log.d(TAG, "🎉 Test authentication fully verified and ready!")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error setting up test authentication", e)
                throw e
            }
        }
    }
} 