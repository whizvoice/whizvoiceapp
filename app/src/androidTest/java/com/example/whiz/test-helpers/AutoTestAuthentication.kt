package com.example.whiz

import android.util.Log
import com.example.whiz.TestCredentialsHelper
import com.example.whiz.data.auth.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic test authentication helper.
 * Ensures tests have proper authentication without requiring manual setup.
 */
object AutoTestAuthentication {
    private const val TAG = "AutoTestAuth"
    
    /**
     * Automatically set up authentication for tests that don't test the login UI.
     * This method will:
     * 1. Check if already authenticated
     * 2. If not, attempt programmatic authentication with test credentials
     * 3. Fail fast with clear error if authentication is not possible
     */
    suspend fun ensureAuthenticated(authRepository: AuthRepository): Boolean {
        Log.e(TAG, "🔐 [AUTO] Ensuring test authentication...")
        
        try {
            // Check if already authenticated
            if (authRepository.isSignedIn()) {
                val userProfile = authRepository.userProfile.first()
                Log.e(TAG, "🔐 [AUTO] ✅ Already authenticated as: ${userProfile?.email}")
                return true
            }
            
            Log.e(TAG, "🔐 [AUTO] Not authenticated, attempting automatic sign-in...")
            
            // Get test credentials from our new helper
            val credentials = TestCredentialsHelper.getTestCredentials()
            val testEmail = credentials.email
            
            Log.e(TAG, "🔐 [AUTO] Using test email: $testEmail")
            
            // Attempt programmatic authentication using the new method
            val authResult = authRepository.authenticateProgrammatically(testEmail)
            
            if (authResult.isSuccess) {
                Log.e(TAG, "🔐 [AUTO] ✅ Programmatic authentication successful!")
                
                // Wait for authentication state to settle
                val isAuthenticated = withTimeoutOrNull(5000) {
                    authRepository.userProfile.first { it != null }
                    authRepository.serverToken.first { it != null }
                    true
                } ?: false
                
                if (isAuthenticated) {
                    val userProfile = authRepository.userProfile.first()
                    Log.e(TAG, "🔐 [AUTO] ✅ Authentication confirmed: ${userProfile?.email}")
                    return true
                } else {
                    Log.e(TAG, "🔐 [AUTO] ❌ Authentication state did not settle properly")
                    return false
                }
            } else {
                val exception = authResult.exceptionOrNull()
                Log.e(TAG, "🔐 [AUTO] ❌ Programmatic authentication failed: ${exception?.message}", exception)
                
                // Try the fallback method
                Log.e(TAG, "🔐 [AUTO] Trying fallback test authentication method...")
                return setupTestAuthentication(authRepository)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "🔐 [AUTO] ❌ Exception during automatic authentication", e)
            return false
        }
    }
    
    /**
     * Set up authentication using the setTestAuthenticationState method.
     * This uses our new test authentication that bypasses Google OAuth.
     */
    suspend fun setupTestAuthentication(authRepository: AuthRepository): Boolean {
        Log.e(TAG, "🔐 [AUTO] Setting up test authentication (new method)...")
        
        try {
            val credentials = TestCredentialsHelper.getTestCredentials()
            
            // Use the new setTestAuthenticationState method with proper parameters
            authRepository.setTestAuthenticationState(
                email = credentials.email,
                userId = credentials.userId,
                name = credentials.displayName
            )
            
            // Wait for authentication state to settle
            val isAuthenticated = withTimeoutOrNull(10000) { // Increased timeout for server call
                authRepository.userProfile.first { it != null }
                authRepository.serverToken.first { it != null }
                true
            } ?: false
            
            if (isAuthenticated) {
                Log.e(TAG, "🔐 [AUTO] ✅ Test authentication setup successful")
                return true
            } else {
                Log.e(TAG, "🔐 [AUTO] ❌ Test authentication setup failed - state did not settle")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "🔐 [AUTO] ❌ Exception during test authentication setup: ${e.message}", e)
            
            // Provide helpful error message
            Log.e(TAG, "🔐 [AUTO] ❌ POSSIBLE ISSUES:")
            Log.e(TAG, "🔐 [AUTO] ❌   1. Server test endpoint not available (needs restart with env vars)")
            Log.e(TAG, "🔐 [AUTO] ❌   2. Network connectivity issues")
            Log.e(TAG, "🔐 [AUTO] ❌   3. Test credentials not properly configured")
            
            return false
        }
    }
} 