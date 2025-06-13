package com.example.whiz

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.remote.AuthApi
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import com.example.whiz.data.auth.UserProfile

/**
 * Test-only AuthRepository that bypasses Google OAuth and uses the server's test authentication endpoint.
 * This allows tests to authenticate without requiring Google account setup on the device.
 */
@Singleton
class TestAuthRepository @Inject constructor(
    context: Context,
    authApi: AuthApi
) : AuthRepository(context, authApi) {
    private val TAG = "TestAuthRepository"
    
    /**
     * Authenticate using test credentials - bypasses Google OAuth completely
     */
    suspend fun authenticateWithTestCredentials(
        email: String? = null,
        userId: String? = null, 
        name: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        // Use credentials from test_credentials.json file
        val testCreds = TestCredentialsHelper.getTestCredentials()
        val testEmail = email ?: testCreds.email
        val testUserId = userId ?: testCreds.userId
        val testName = name ?: testCreds.displayName
        try {
            Log.d(TAG, "🧪 Starting test authentication for: $testEmail (userId: $testUserId)")
            
            // Call the server's test auth endpoint
            val authResult = authApi.authenticateWithTestCredentials(
                email = testEmail,
                userId = testUserId,
                name = testName
            )
            
            if (authResult.isSuccess) {
                val authResponse = authResult.getOrThrow()
                Log.d(TAG, "🧪 Test authentication successful for: $testEmail")
                Log.d(TAG, "🧪 Server response - accessToken: ${authResponse.accessToken.take(20)}...")
                Log.d(TAG, "🧪 Server response - refreshToken: ${authResponse.refreshToken.take(20)}...")
                Log.d(TAG, "🧪 Server response - user.id: ${authResponse.user.id}")
                Log.d(TAG, "🧪 Server response - user.name: ${authResponse.user.name}")
                Log.d(TAG, "🧪 Server response - user.email: ${authResponse.user.email}")
                
                // Save auth tokens first
                Log.d(TAG, "🧪 Saving auth tokens to secure storage...")
                saveAuthTokensFromServer(
                    accessToken = authResponse.accessToken,
                    refreshToken = authResponse.refreshToken
                )
                Log.d(TAG, "🧪 Auth tokens saved successfully")
                
                // Save user data manually to SharedPreferences
                Log.d(TAG, "🧪 Saving user data to SharedPreferences...")
                val sharedPrefs = context.getSharedPreferences("auth_preferences", Context.MODE_PRIVATE)
                sharedPrefs.edit().apply {
                    putString("user_id", authResponse.user.id)
                    putString("user_name", authResponse.user.name)
                    putString("user_email", authResponse.user.email)
                    putString("user_photo_url", authResponse.user.photoUrl)
                    putString("auth_token", "test_auth_token") // Not used, but kept for compatibility
                    putBoolean("test_mode", true)
                    apply()
                }
                Log.d(TAG, "🧪 User data saved to SharedPreferences")
                
                // Trigger user profile refresh now that refreshUserProfile() is protected
                Log.d(TAG, "🧪 Calling refreshUserProfile() to update user profile flow...")
                refreshUserProfile()
                Log.d(TAG, "🧪 refreshUserProfile() completed")
                
                // Check if the state is properly set
                Log.d(TAG, "🧪 Checking authentication state after setup...")
                val isSignedIn = isSignedIn()
                Log.d(TAG, "🧪 isSignedIn() returns: $isSignedIn")
                
                // Check the flows
                try {
                    val userProfile = userProfile.first()
                    Log.d(TAG, "🧪 userProfile flow has: ${userProfile?.email ?: "null"}")
                } catch (e: Exception) {
                    Log.w(TAG, "🧪 Failed to read userProfile flow: ${e.message}")
                }
                
                try {
                    val serverToken = serverToken.first()
                    Log.d(TAG, "🧪 serverToken flow has: ${if (serverToken != null) "token present" else "null"}")
                } catch (e: Exception) {
                    Log.w(TAG, "🧪 Failed to read serverToken flow: ${e.message}")
                }
                
                Log.d(TAG, "🧪 Test authentication setup complete!")
                return@withContext Result.success(true)
            } else {
                val exception = authResult.exceptionOrNull()
                Log.e(TAG, "🧪 Test authentication failed: ${exception?.message}", exception)
                return@withContext Result.failure(exception ?: Exception("Test authentication failed"))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "🧪 Exception during test authentication", e)
            return@withContext Result.failure(e)
        }
    }
    
    /**
     * Clear all authentication data (for test cleanup)
     */
    suspend fun clearAuthState() {
        Log.d(TAG, "🧪 Clearing test authentication state")
        signOut()
    }
    
    /**
     * Override setTestAuthenticationState to use our test authentication
     */
    override suspend fun setTestAuthenticationState(
        email: String,
        userId: String,
        name: String
    ) {
        Log.d(TAG, "🧪 Setting test authentication state - using REDACTED_TEST_EMAIL")
        // Always use the specific test credentials regardless of parameters
        val result = authenticateWithTestCredentials()
        if (!result.isSuccess) {
            throw Exception("Failed to set test authentication state: ${result.exceptionOrNull()?.message}")
        }
    }
    
    /**
     * Override authenticateProgrammatically to use test credentials
     */
    override suspend fun authenticateProgrammatically(
        email: String,
        password: String?
    ): Result<Boolean> {
        Log.d(TAG, "🧪 Programmatic authentication - using REDACTED_TEST_EMAIL")
        // Always use the specific test credentials regardless of parameters
        return authenticateWithTestCredentials()
    }
} 