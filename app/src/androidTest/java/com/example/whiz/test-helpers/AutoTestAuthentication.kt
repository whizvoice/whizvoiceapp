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
     * 2. If not, attempt test authentication using the new bypass method
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
            
            Log.e(TAG, "🔐 [AUTO] Not authenticated, attempting test authentication...")
            
            // Cast to TestAuthRepository to access test-specific methods
            val testAuthRepository = authRepository as? TestAuthRepository
            if (testAuthRepository != null) {
                Log.e(TAG, "🔐 [AUTO] Using TestAuthRepository for bypass authentication...")
                
                // Use the new test authentication method that bypasses Google OAuth
                val authResult = testAuthRepository.authenticateWithTestCredentials()
                
                if (authResult.isSuccess) {
                    Log.e(TAG, "🔐 [AUTO] ✅ Test authentication successful!")
                    
                    // Wait for authentication state to settle
                    Log.e(TAG, "🔐 [AUTO] Waiting for authentication state to settle...")
                    val isAuthenticated = withTimeoutOrNull(10000) {
                        Log.e(TAG, "🔐 [AUTO] Checking userProfile flow...")
                        val userProfile = authRepository.userProfile.first { it != null }
                        Log.e(TAG, "🔐 [AUTO] userProfile settled: ${userProfile?.email}")
                        
                        Log.e(TAG, "🔐 [AUTO] Checking serverToken flow...")
                        val serverToken = authRepository.serverToken.first { it != null }
                        Log.e(TAG, "🔐 [AUTO] serverToken settled: ${if (serverToken != null) "present" else "null"}")
                        
                        Log.e(TAG, "🔐 [AUTO] Both flows settled successfully")
                        true
                    } ?: false
                    
                    if (isAuthenticated) {
                        val userProfile = authRepository.userProfile.first()
                        Log.e(TAG, "🔐 [AUTO] ✅ Authentication confirmed: ${userProfile?.email}")
                        return true
                    } else {
                        Log.e(TAG, "🔐 [AUTO] ❌ Authentication state did not settle properly")
                        
                        // Debug: Check current state
                        Log.e(TAG, "🔐 [AUTO] 🔍 Debugging current state:")
                        try {
                            val currentUserProfile = authRepository.userProfile.first()
                            Log.e(TAG, "🔐 [AUTO] 🔍 Current userProfile: ${currentUserProfile?.email ?: "null"}")
                        } catch (e: Exception) {
                            Log.e(TAG, "🔐 [AUTO] 🔍 Failed to get userProfile: ${e.message}")
                        }
                        
                        try {
                            val currentServerToken = authRepository.serverToken.first()
                            Log.e(TAG, "🔐 [AUTO] 🔍 Current serverToken: ${if (currentServerToken != null) "present" else "null"}")
                        } catch (e: Exception) {
                            Log.e(TAG, "🔐 [AUTO] 🔍 Failed to get serverToken: ${e.message}")
                        }
                        
                        Log.e(TAG, "🔐 [AUTO] 🔍 isSignedIn(): ${authRepository.isSignedIn()}")
                        
                        return false
                    }
                } else {
                    val exception = authResult.exceptionOrNull()
                    Log.e(TAG, "🔐 [AUTO] ❌ Test authentication failed: ${exception?.message}", exception)
                    return false
                }
            } else {
                Log.e(TAG, "🔐 [AUTO] ❌ AuthRepository is not TestAuthRepository - cannot use bypass authentication")
                return false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "🔐 [AUTO] ❌ Exception during automatic authentication", e)
            
            // Provide helpful error message
            Log.e(TAG, "🔐 [AUTO] ❌ POSSIBLE ISSUES:")
            Log.e(TAG, "🔐 [AUTO] ❌   1. Server test endpoint not available (check server logs)")
            Log.e(TAG, "🔐 [AUTO] ❌   2. Network connectivity issues")
            Log.e(TAG, "🔐 [AUTO] ❌   3. Test credentials not properly configured")
            Log.e(TAG, "🔐 [AUTO] ❌   4. Server not running or not accessible")
            
            return false
        }
    }
} 