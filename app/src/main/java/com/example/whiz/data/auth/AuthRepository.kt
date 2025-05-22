package com.example.whiz.data.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.example.whiz.data.remote.AuthApi
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Actual data classes for network request/response
data class RefreshTokenRequest(val refresh_token: String)
data class NewAccessTokenResponse(
    val access_token: String,
    val token_type: String = "bearer" // Defaulted, server might not send it
)

// Preference keys
private object PreferenceKeys {
    const val AUTH_TOKEN = "auth_token"
    const val SERVER_TOKEN = "server_token"
    const val REFRESH_TOKEN = "refresh_token"
    const val USER_ID = "user_id"
    const val USER_NAME = "user_name"
    const val USER_EMAIL = "user_email"
    const val USER_PHOTO_URL = "user_photo_url"
}

// OAuth client ID for Google Sign-In
private object AuthConfig {
    // Android client ID (must match package name com.example.whiz and SHA-1 fingerprint)
    const val GOOGLE_CLIENT_ID = "REDACTED_GOOGLE_CLIENT_ID"
    
    // Web client ID for server authentication
    const val WEB_CLIENT_ID = "REDACTED_WEB_CLIENT_ID"
}

data class UserProfile(
    val userId: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
)

// Placeholder for where your network API calls are defined (e.g., AuthApi.kt or similar)
// You'll need to add these to your actual Retrofit interface.
/*
interface AuthServerApi {
    @POST("/auth/refresh") // Ensure this path matches your server
    suspend fun refreshAccessToken(@Body request: RefreshTokenRequest): NewAccessTokenResponse
}
data class RefreshTokenRequest(val refresh_token: String)
data class NewAccessTokenResponse(
    val access_token: String,
    val token_type: String = "bearer" // Defaulted, server might not send it
)
*/

@Singleton
class AuthRepository @Inject constructor(
    private val context: Context,
    private val authApi: AuthApi // Inject your actual AuthApi interface
) {
    private val TAG = "AuthRepository"
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "auth_preferences", Context.MODE_PRIVATE
    )
    
    // Google Sign-In client
    private val googleSignInClient: GoogleSignInClient by lazy {
        try {
            Log.d(TAG, "Creating GoogleSignInClient with client ID: ${AuthConfig.GOOGLE_CLIENT_ID}")
            
            // Build Google Sign-In options
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                // Use web client ID for ID token
                .requestIdToken(AuthConfig.WEB_CLIENT_ID)
                .build()
            
            GoogleSignIn.getClient(context, gso)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating GoogleSignInClient", e)
            throw e
        }
    }
    
    // Private flow to update when preferences change
    private val _userProfileFlow = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfileFlow
    
    // Private flow to update when preferences change
    private val _serverTokenFlow = MutableStateFlow<String?>(null)
    val serverToken: StateFlow<String?> = _serverTokenFlow
    
    init {
        // Load the initial user profile
        refreshUserProfile()
        // Load the initial server token
        _serverTokenFlow.value = sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null)
    }
    
    // Check if user is signed in
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val serverToken = sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null)
        val userId = sharedPreferences.getString(PreferenceKeys.USER_ID, null)
        
        Log.d(TAG, "Checking sign-in state: Google account=${account != null}, Server token=${serverToken != null}, User ID=${userId != null}")
        
        if (account != null && serverToken == null) {
            Log.w(TAG, "Google account present but server token is missing. User is in a half-logged-in state.")
        }
        
        // Only consider signed in if we have all required data
        return account != null && serverToken != null && userId != null
    }
    
    // Get Google Sign-In Client for sign-in intent
    fun createSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }
    
    // Process sign-in result and save user data
    suspend fun processSignInAccount(account: GoogleSignInAccount?) {
        if (account != null) {
            Log.d(TAG, "Google Sign-In successful: ${account.email}")
            
            // Save account information to SharedPreferences
            withContext(Dispatchers.IO) {
                sharedPreferences.edit().apply {
                    putString(PreferenceKeys.USER_ID, account.id ?: "")
                    putString(PreferenceKeys.USER_NAME, account.displayName ?: "")
                    putString(PreferenceKeys.USER_EMAIL, account.email ?: "")
                    putString(PreferenceKeys.USER_PHOTO_URL, account.photoUrl?.toString() ?: "")
                    
                    // If you're getting an ID token for server authentication
                    account.idToken?.let { token ->
                        putString(PreferenceKeys.AUTH_TOKEN, token)
                        Log.d(TAG, "ID Token saved to preferences")
                    } ?: Log.w(TAG, "No ID token available from Google Sign-In")
                    
                    apply()
                }
                
                // Update the flow
                refreshUserProfile()
            }
        } else {
            Log.w(TAG, "Google Sign-In failed: account is null")
        }
    }
    
    // Renamed to reflect it saves tokens from our backend server
    suspend fun saveAuthTokensFromServer(accessToken: String, refreshToken: String) {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "Saving server tokens to preferences.")
            sharedPreferences.edit().apply {
                putString(PreferenceKeys.SERVER_TOKEN, accessToken)
                putString(PreferenceKeys.REFRESH_TOKEN, refreshToken)
                apply()
            }
            Log.d(TAG, "Access and Refresh tokens saved to preferences.")
            _serverTokenFlow.value = accessToken // Update flow with the new access token
            Log.d(TAG, "_serverTokenFlow updated with new access token.")
        }
    }

    private fun getRefreshToken(): String? {
        return sharedPreferences.getString(PreferenceKeys.REFRESH_TOKEN, null)
    }

    // The authServerApi parameter is removed as we now use the injected authApi.
    suspend fun refreshAccessToken(): Boolean {
        val currentRefreshToken = getRefreshToken()
        if (currentRefreshToken == null) {
            Log.w(TAG, "No refresh token available. Cannot refresh access token.")
            return false
        }

        return try {
            // ACTUAL API CALL using the injected authApi
            val response = authApi.refreshAccessToken(RefreshTokenRequest(currentRefreshToken))
            // Assuming successful response (2xx), Retrofit would have populated this.
            // If server returns non-2xx, Retrofit throws HttpException, caught below.
            
            Log.i(TAG, "Access token refreshed successfully. New token: ${response.access_token.take(10)}...")
            // Save the new access token. The refresh token remains the same in this iteration.
            saveAuthTokensFromServer(response.access_token, currentRefreshToken)
            true
        } catch (e: retrofit2.HttpException) { // Catch specific HttpException from Retrofit
            Log.e(TAG, "HttpException during access token refresh. Code: ${e.code()}", e)
            if (e.code() == 401 || e.code() == 403) { // Unauthorized or Forbidden
                Log.w(TAG, "Refresh token rejected by server (HTTP ${e.code()}). Signing out.")
                signOut() // Critical: If refresh fails due to bad refresh token, sign out user
            }
            // For other HTTP errors, we might not sign out immediately, just return false.
            // Depending on policy, could also sign out for e.g. 400 Bad Request if it means malformed refresh token.
            false
        } catch (e: Exception) { // Catch other exceptions (network, IO, etc.)
            Log.e(TAG, "Generic exception during access token refresh", e)
            // For generic errors (like no network), don't automatically sign out, just indicate refresh failed.
            false
        }
    }
    
    // Get the Google authentication token (can remain as is or also become a StateFlow if needed for reactivity)
    val authToken: Flow<String?> = kotlinx.coroutines.flow.flow { // Explicitly use kotlinx.coroutines.flow.flow
        emit(sharedPreferences.getString(PreferenceKeys.AUTH_TOKEN, null))
    }
    
    // Refresh the user profile data from SharedPreferences
    private fun refreshUserProfile() {
        val userId = sharedPreferences.getString(PreferenceKeys.USER_ID, null)
        
        if (userId != null) {
            _userProfileFlow.value = UserProfile(
                userId = userId,
                name = sharedPreferences.getString(PreferenceKeys.USER_NAME, null),
                email = sharedPreferences.getString(PreferenceKeys.USER_EMAIL, null),
                photoUrl = sharedPreferences.getString(PreferenceKeys.USER_PHOTO_URL, null)
            )
        } else {
            _userProfileFlow.value = null
        }
    }
    
    // Sign out the user
    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            try {
                // First clear stored preferences
                sharedPreferences.edit().apply {
                    remove(PreferenceKeys.AUTH_TOKEN)
                    remove(PreferenceKeys.SERVER_TOKEN)
                    remove(PreferenceKeys.REFRESH_TOKEN) // Clear refresh token
                    remove(PreferenceKeys.USER_ID)
                    remove(PreferenceKeys.USER_NAME)
                    remove(PreferenceKeys.USER_EMAIL)
                    remove(PreferenceKeys.USER_PHOTO_URL)
                    apply()
                }
                
                // Update the flow
                refreshUserProfile()
                _serverTokenFlow.value = null
                
                // Then sign out from Google
                googleSignInClient.signOut()
                
                Log.d(TAG, "User signed out and preferences cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error during sign out", e)
                throw e
            }
        }
    }
    
    // Expose last signed-in Google account
    fun getLastSignedInGoogleAccount(): GoogleSignInAccount? {
        return GoogleSignIn.getLastSignedInAccount(context)
    }

    suspend fun setUserTimezone(timezone: String): Boolean {
        return try {
            // Ensure we have a server token before making the call
            val currentServerToken = serverToken.first { it != null }
            if (currentServerToken == null) {
                Log.w(TAG, "Cannot set timezone via API, server token is null.")
                return false
            }
            val response = authApi.setUserTimezone(AuthApi.SetTimezoneRequest(timezone))
            if (response.isSuccessful) {
                Log.i(TAG, "Successfully set timezone '$timezone' via API.")
                true
            } else {
                Log.w(TAG, "Failed to set timezone via API. Code: ${response.code()}, Message: ${response.message()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while setting timezone '$timezone' via API", e)
            false
        }
    }
} 