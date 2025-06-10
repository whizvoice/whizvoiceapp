package com.example.whiz.integration

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.example.whiz.TestCredentialsManager
import com.example.whiz.TestCredentials
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.integration.GoogleSignInAutomator
import kotlinx.coroutines.delay

/**
 * Helper class for managing authentication in integration tests.
 * Handles automated sign-in for REDACTED_TEST_EMAIL.
 */
object AuthenticationTestHelper {
    private const val TAG = "AuthTestHelper"
    
    /**
     * Ensures we're authenticated as REDACTED_TEST_EMAIL.
     * Returns true if successful, false if authentication failed.
     */
    suspend fun ensureWhizVoiceTestAuthentication(authRepository: AuthRepository, device: UiDevice): Boolean {
        Log.d(TAG, "🔐 Ensuring authentication as REDACTED_TEST_EMAIL...")
        
        // Check current authentication state
        val currentUser = authRepository.userProfile.value
        if (authRepository.isSignedIn() && currentUser?.email?.contains("whizvoicetest") == true) {
            Log.d(TAG, "✅ Already authenticated as whizvoicetest: ${currentUser.email}")
            return true
        }
        
        // Try to get credentials from instrumentation arguments (Firebase Test Lab / ADB)
        val arguments = InstrumentationRegistry.getArguments()
        val testUsername = arguments.getString("testUsername")
        val testPassword = arguments.getString("testPassword")
        
        if (!testUsername.isNullOrEmpty() && !testPassword.isNullOrEmpty()) {
            Log.d(TAG, "🔑 Found test credentials from instrumentation arguments")
            Log.d(TAG, "📧 Test email: $testUsername")
            
            // If we're authenticated as wrong user, sign out first
            if (authRepository.isSignedIn()) {
                Log.d(TAG, "🔄 Currently authenticated as: ${currentUser?.email}")
                Log.d(TAG, "🔄 Need to switch to whizvoicetest - signing out first...")
                authRepository.signOut()
                delay(2000) // Wait for signout to complete
            }
            
            return performAutomatedSignInUsingArguments(authRepository, device, testUsername, testPassword)
        }
        
        // Fallback: try local test credentials
        val credentials = TestCredentialsManager.credentials
        Log.d(TAG, "📧 Target test email: ${credentials.googleTestAccount.email}")
        Log.d(TAG, "🔑 Using test credentials from test_credentials.json")
        
        // Check if we have valid test credentials
        if (credentials.googleTestAccount.email == "test@example.com") {
            Log.e(TAG, "❌ No real test credentials found! Using default mock credentials.")
            Log.e(TAG, "❌ Cannot perform automated authentication without real whizvoicetest credentials.")
            return false
        }
        
        // If we're authenticated as wrong user, sign out first
        if (authRepository.isSignedIn()) {
            Log.d(TAG, "🔄 Currently authenticated as: ${currentUser?.email}")
            Log.d(TAG, "🔄 Need to switch to whizvoicetest - signing out first...")
            authRepository.signOut()
            delay(2000) // Wait for signout to complete
        } else {
            Log.d(TAG, "🔄 Not authenticated - will sign in as whizvoicetest")
        }
        
        // Now sign in as whizvoicetest using test credentials with total timeout
        Log.d(TAG, "🕐 Starting authentication with 30 second total timeout...")
        try {
            return kotlinx.coroutines.withTimeout(30_000) {
                performAutomatedSignInWithTestCredentials(authRepository, device, credentials)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "❌ Total authentication process timed out after 30 seconds")
            Log.e(TAG, "❌ This usually means GoogleSignInAutomator got stuck on the UI")
            
            // Try to get back to app
            Log.d(TAG, "🔄 Attempting to return to app...")
            device.pressBack()
            delay(500)
            device.pressBack()
            delay(500)
            
            return false
        }
    }
    
    /**
     * Performs automated sign-in using provided credentials (from instrumentation arguments)
     */
    private suspend fun performAutomatedSignInUsingArguments(
        authRepository: AuthRepository,
        device: UiDevice,
        username: String,
        password: String
    ): Boolean {
        Log.d(TAG, "🤖 Starting automated Google Sign-In with provided credentials...")
        
        try {
            // Step 1: Launch sign-in intent
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val signInIntent = authRepository.createSignInIntent()
            
            Log.d(TAG, "🚀 Launching Google Sign-In intent...")
            context.startActivity(signInIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            
            // Give UI time to appear
            delay(2000)
            
            // Step 2: Use GoogleSignInAutomator with provided credentials
            Log.d(TAG, "🤖 Running GoogleSignInAutomator with timeout...")
            Log.d(TAG, "📱 Email: $username")
            Log.d(TAG, "🔒 Password: ${password.take(3)}***")
            
            var automationSuccess = false
            var automationError: Exception? = null
            
            // Run GoogleSignInAutomator with timeout
            try {
                kotlinx.coroutines.withTimeout(10_000) {
                    automationSuccess = GoogleSignInAutomator.performGoogleSignIn(
                        device,
                        username,
                        password
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "❌ GoogleSignInAutomator timed out after 10 seconds")
                return false
            } catch (e: Exception) {
                Log.e(TAG, "❌ GoogleSignInAutomator threw exception", e)
                automationError = e
                automationSuccess = false
            }
            
            if (!automationSuccess) {
                Log.e(TAG, "❌ GoogleSignInAutomator failed")
                if (automationError != null) {
                    Log.e(TAG, "❌ Error details: ${automationError.message}")
                }
                
                // Try to dismiss any dialogs and return to app
                Log.d(TAG, "🔄 Trying to dismiss UI and return to app...")
                device.pressBack()
                delay(1000)
                device.pressBack()
                delay(1000)
                
                return false
            }
            
            Log.d(TAG, "✅ GoogleSignInAutomator completed")
            
            // Step 3: Wait for app authentication to complete
            Log.d(TAG, "⏳ Waiting for app authentication...")
            
            var attempts = 0
            val maxAttempts = 20 // 10 seconds maximum
            while (attempts < maxAttempts) {
                delay(500)
                
                if (authRepository.isSignedIn()) {
                    val userProfile = authRepository.userProfile.value
                    Log.d(TAG, "👤 App authenticated as: ${userProfile?.email}")
                    
                    if (userProfile?.email?.contains("whizvoicetest") == true) {
                        Log.d(TAG, "🎉 Successfully authenticated as whizvoicetest!")
                        return true
                    } else {
                        Log.w(TAG, "⚠️ Authenticated as wrong user: ${userProfile?.email}")
                        return false
                    }
                }
                
                if (attempts % 4 == 0) { // Log every 2 seconds
                    Log.d(TAG, "⏳ Auth check ${attempts/2}s...")
                }
                attempts++
            }
            
            Log.e(TAG, "❌ Authentication timed out after ${maxAttempts/2} seconds")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during automated authentication with provided credentials", e)
            return false
        }
    }
    
    /**
     * Performs automated sign-in using local test credentials
     */
    private suspend fun performAutomatedSignInWithTestCredentials(
        authRepository: AuthRepository,
        device: UiDevice,
        credentials: TestCredentials
    ): Boolean {
        Log.d(TAG, "🤖 Starting automated Google Sign-In for whizvoicetest...")
        
        try {
            // Step 1: Launch sign-in intent
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val signInIntent = authRepository.createSignInIntent()
            
            Log.d(TAG, "🚀 Launching Google Sign-In intent...")
            context.startActivity(signInIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            
            // Give UI time to appear
            delay(2000)
            
            // Step 2: Use GoogleSignInAutomator with test credentials
            Log.d(TAG, "🤖 Running GoogleSignInAutomator with 10 second timeout...")
            Log.d(TAG, "📱 Email: ${credentials.googleTestAccount.email}")
            Log.d(TAG, "🔒 Password: ${credentials.googleTestAccount.password.take(3)}***")
            
            var automationSuccess = false
            var automationError: Exception? = null
            
            // Run GoogleSignInAutomator with timeout
            try {
                kotlinx.coroutines.withTimeout(10_000) {
                    automationSuccess = GoogleSignInAutomator.performGoogleSignIn(
                        device,
                        credentials.googleTestAccount.email,
                        credentials.googleTestAccount.password
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "❌ GoogleSignInAutomator timed out after 10 seconds")
                return false
            } catch (e: Exception) {
                Log.e(TAG, "❌ GoogleSignInAutomator threw exception", e)
                automationError = e
                automationSuccess = false
            }
            
            if (!automationSuccess) {
                Log.e(TAG, "❌ GoogleSignInAutomator failed")
                if (automationError != null) {
                    Log.e(TAG, "❌ Error details: ${automationError.message}")
                }
                
                // Try to dismiss any dialogs and return to app
                Log.d(TAG, "🔄 Trying to dismiss UI and return to app...")
                device.pressBack()
                delay(1000)
                device.pressBack()
                delay(1000)
                
                return false
            }
            
            Log.d(TAG, "✅ GoogleSignInAutomator completed")
            
            // Step 3: Wait for app authentication to complete
            Log.d(TAG, "⏳ Waiting for app authentication...")
            
            var attempts = 0
            val maxAttempts = 20 // 10 seconds maximum
            while (attempts < maxAttempts) {
                delay(500)
                
                if (authRepository.isSignedIn()) {
                    val userProfile = authRepository.userProfile.value
                    Log.d(TAG, "👤 App authenticated as: ${userProfile?.email}")
                    
                    if (userProfile?.email?.contains("whizvoicetest") == true) {
                        Log.d(TAG, "🎉 Successfully authenticated as whizvoicetest!")
                        return true
                    } else {
                        Log.w(TAG, "⚠️ Authenticated as wrong user: ${userProfile?.email}")
                        return false
                    }
                }
                
                if (attempts % 4 == 0) { // Log every 2 seconds
                    Log.d(TAG, "⏳ Auth check ${attempts/2}s...")
                }
                attempts++
            }
            
            Log.e(TAG, "❌ Authentication timed out after ${maxAttempts/2} seconds")
            return false
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during automated authentication", e)
            return false
        }
    }
    
    /**
     * Verifies the current authentication state and throws if not authenticated as whizvoicetest
     */
    fun verifyWhizVoiceTestAuthentication(authRepository: AuthRepository, testName: String) {
        val isSignedIn = authRepository.isSignedIn()
        val currentUser = authRepository.userProfile.value
        
        if (!isSignedIn) {
            throw RuntimeException("$testName requires authentication but no user is signed in")
        }
        
        if (currentUser?.email?.contains("whizvoicetest") != true) {
            throw RuntimeException("$testName requires REDACTED_TEST_EMAIL but found: ${currentUser?.email}")
        }
        
        Log.d(TAG, "✅ Authentication verified for $testName: ${currentUser.email}")
    }
}