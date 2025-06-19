package com.example.whiz.data.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.example.whiz.data.remote.AuthApi
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
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
open class AuthRepository @Inject constructor(
    protected val context: Context,
    protected val authApi: AuthApi // Inject your actual AuthApi interface
) {
    private val TAG = "AuthRepository"
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        "auth_preferences", Context.MODE_PRIVATE
    )
    
    // Android Credential Manager for programmatic authentication
    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }
    
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
        val isTestMode = sharedPreferences.getBoolean("test_mode", false)
        
        Log.d(TAG, "Checking sign-in state: Google account=${account != null}, Server token=${serverToken != null}, User ID=${userId != null}, Test mode=${isTestMode}")
        
        if (account != null && serverToken == null) {
            Log.w(TAG, "Google account present but server token is missing. User is in a half-logged-in state.")
        }
        
        // In test mode, only require server token and user ID (skip Google account check)
        if (isTestMode) {
            Log.d(TAG, "Test mode active - checking only server token and user ID")
            return serverToken != null && userId != null
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
        Log.d(TAG, "🔄 refreshAccessToken called. Refresh token present: ${currentRefreshToken != null}")
        if (currentRefreshToken == null) {
            Log.w(TAG, "🔄 No refresh token available. Cannot refresh access token.")
            return false
        }

        return try {
            Log.d(TAG, "🔄 Making refresh token API call to server...")
            // ACTUAL API CALL using the injected authApi
            val response = authApi.refreshAccessToken(RefreshTokenRequest(currentRefreshToken))
            // Assuming successful response (2xx), Retrofit would have populated this.
            // If server returns non-2xx, Retrofit throws HttpException, caught below.
            
            Log.i(TAG, "🔄 Access token refreshed successfully. New token: ${response.access_token.take(10)}...")
            // Save the new access token. The refresh token remains the same in this iteration.
            saveAuthTokensFromServer(response.access_token, currentRefreshToken)
            Log.d(TAG, "🔄 New access token saved to preferences")
            true
        } catch (e: retrofit2.HttpException) { // Catch specific HttpException from Retrofit
            Log.e(TAG, "🔄 HttpException during access token refresh. Code: ${e.code()}, Message: ${e.message()}", e)
            
            // Try to get response body for more details
            try {
                val errorBody = e.response()?.errorBody()?.string()
                if (!errorBody.isNullOrEmpty()) {
                    Log.e(TAG, "🔄 Server error response: $errorBody")
                }
            } catch (bodyException: Exception) {
                Log.e(TAG, "🔄 Could not read error response body", bodyException)
            }
            
            if (e.code() == 401 || e.code() == 403) { // Unauthorized or Forbidden
                Log.w(TAG, "🔄 Refresh token rejected by server (HTTP ${e.code()}). Signing out.")
                signOut() // Critical: If refresh fails due to bad refresh token, sign out user
            }
            // For other HTTP errors, we might not sign out immediately, just return false.
            // Depending on policy, could also sign out for e.g. 400 Bad Request if it means malformed refresh token.
            false
        } catch (e: Exception) { // Catch other exceptions (network, IO, etc.)
            Log.e(TAG, "🔄 Generic exception during access token refresh", e)
            // For generic errors (like no network), don't automatically sign out, just indicate refresh failed.
            false
        }
    }
    
    // Get the Google authentication token (can remain as is or also become a StateFlow if needed for reactivity)
    val authToken: Flow<String?> = kotlinx.coroutines.flow.flow { // Explicitly use kotlinx.coroutines.flow.flow
        emit(sharedPreferences.getString(PreferenceKeys.AUTH_TOKEN, null))
    }
    
    // Refresh the user profile data from SharedPreferences
    protected fun refreshUserProfile() {
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
                    remove("test_mode") // Clear test mode flag
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
    
    /**
     * Programmatically authenticate using existing Google credentials without UI interaction.
     * This method checks for existing Google accounts and uses them directly.
     */
    open suspend fun authenticateProgrammatically(
        email: String,
        password: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.e(TAG, "🤖 [DETAILED] Starting programmatic authentication for: $email")
            
            // First check if we already have a valid Google account with the right email
            val existingAccount = getLastSignedInGoogleAccount()
            Log.e(TAG, "🤖 [DETAILED] getLastSignedInGoogleAccount() returned: ${existingAccount?.email ?: "null"}")
            
            if (existingAccount?.email == email) {
                Log.e(TAG, "🤖 [DETAILED] Found matching Google account: ${existingAccount.email}")
                
                val idToken = existingAccount.idToken
                Log.e(TAG, "🤖 [DETAILED] ID token present: ${idToken != null}, length: ${idToken?.length ?: 0}")
                
                if (idToken != null) {
                    Log.e(TAG, "🤖 [DETAILED] Calling authApi.authenticateWithGoogle()...")
                    
                    try {
                        // Authenticate with server using existing token
                        val authResult = authApi.authenticateWithGoogle(idToken)
                        Log.e(TAG, "🤖 [DETAILED] authApi.authenticateWithGoogle() completed, success: ${authResult.isSuccess}")
                        
                        if (authResult.isSuccess) {
                            val authResponse = authResult.getOrThrow()
                            Log.e(TAG, "🤖 [DETAILED] Server authentication successful, user: ${authResponse.user.email}")
                            
                            // Save all authentication data
                            Log.e(TAG, "🤖 [DETAILED] Saving authentication data to SharedPreferences...")
                            sharedPreferences.edit().apply {
                                putString(PreferenceKeys.USER_ID, authResponse.user.id)
                                putString(PreferenceKeys.USER_NAME, authResponse.user.name)
                                putString(PreferenceKeys.USER_EMAIL, authResponse.user.email)
                                putString(PreferenceKeys.USER_PHOTO_URL, authResponse.user.photoUrl)
                                putString(PreferenceKeys.AUTH_TOKEN, idToken)
                                putBoolean("test_mode", true)
                                apply()
                            }
                            Log.e(TAG, "🤖 [DETAILED] SharedPreferences saved successfully")
                            
                            Log.e(TAG, "🤖 [DETAILED] Calling saveAuthTokensFromServer()...")
                            saveAuthTokensFromServer(
                                accessToken = authResponse.accessToken,
                                refreshToken = authResponse.refreshToken ?: ""
                            )
                            Log.e(TAG, "🤖 [DETAILED] saveAuthTokensFromServer() completed")
                            
                            Log.e(TAG, "🤖 [DETAILED] Calling refreshUserProfile()...")
                            refreshUserProfile()
                            Log.e(TAG, "🤖 [DETAILED] refreshUserProfile() completed")
                            
                            Log.e(TAG, "🤖 [DETAILED] Programmatic authentication SUCCESS!")
                            return@withContext Result.success(true)
                        } else {
                            val exception = authResult.exceptionOrNull()
                            Log.e(TAG, "🤖 [DETAILED] Server authentication failed: ${exception?.message}", exception)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "🤖 [DETAILED] Exception during server authentication", e)
                    }
                } else {
                    Log.e(TAG, "🤖 [DETAILED] Existing Google account has no ID token")
                }
            } else {
                if (existingAccount != null) {
                    Log.e(TAG, "🤖 [DETAILED] Existing Google account email (${existingAccount.email}) doesn't match target ($email)")
                } else {
                    Log.e(TAG, "🤖 [DETAILED] No existing Google account found")
                }
            }
            
            // Try using Credential Manager with authorized accounts only (no UI)
            Log.d(TAG, "🤖 Attempting Credential Manager with authorized accounts only...")
            
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(AuthConfig.WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(true) // Only use pre-authorized accounts (no UI)
                    .build()
                    
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                
                val result = credentialManager.getCredential(
                    context = context,
                    request = request
                )
                
                val credential = result.credential
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken
                        
                        Log.d(TAG, "🤖 Got Google ID token via Credential Manager (authorized accounts)")
                        
                        // Authenticate with server
                        val authResult = authApi.authenticateWithGoogle(idToken)
                        if (authResult.isSuccess) {
                            val authResponse = authResult.getOrThrow()
                            Log.d(TAG, "🤖 Programmatic authentication successful!")
                            
                            // Save all authentication data
                            sharedPreferences.edit().apply {
                                putString(PreferenceKeys.USER_ID, authResponse.user.id)
                                putString(PreferenceKeys.USER_NAME, authResponse.user.name)
                                putString(PreferenceKeys.USER_EMAIL, authResponse.user.email)
                                putString(PreferenceKeys.USER_PHOTO_URL, authResponse.user.photoUrl)
                                putString(PreferenceKeys.AUTH_TOKEN, idToken)
                                putBoolean("test_mode", true)
                                apply()
                            }
                            
                            saveAuthTokensFromServer(
                                accessToken = authResponse.accessToken,
                                refreshToken = authResponse.refreshToken ?: ""
                            )
                            
                            refreshUserProfile()
                            return@withContext Result.success(true)
                        } else {
                            Log.e(TAG, "🤖 Server authentication failed: ${authResult.exceptionOrNull()}")
                        }
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "🤖 Invalid Google ID token response", e)
                    }
                } else {
                    Log.e(TAG, "🤖 Unexpected credential type: ${credential.type}")
                }
            } catch (e: GetCredentialException) {
                Log.w(TAG, "🤖 Credential Manager with authorized accounts failed (expected if no pre-authorized accounts): ${e.message}")
            }
            
            // If we get here, programmatic authentication failed
            Log.w(TAG, "🤖 Programmatic authentication failed - no valid credentials available")
            return@withContext Result.failure(Exception("No valid Google credentials available for programmatic authentication"))
            
        } catch (e: Exception) {
            Log.e(TAG, "🤖 Programmatic authentication failed", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Prepare authentication state after manual Google Sign-In.
     * This method should be called after a user manually signs in to prepare for programmatic use.
     */
    suspend fun prepareAuthenticationForTesting(email: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔧 Preparing authentication for testing: $email")
            
            // Check if we have the right account signed in
            val currentAccount = getLastSignedInGoogleAccount()
            if (currentAccount?.email == email) {
                Log.d(TAG, "🔧 Found correct Google account: ${currentAccount.email}")
                
                val idToken = currentAccount.idToken
                if (idToken != null) {
                    Log.d(TAG, "🔧 Account has valid ID token, authenticating with server...")
                    
                    // Authenticate with server
                    val authResult = authApi.authenticateWithGoogle(idToken)
                    if (authResult.isSuccess) {
                        val authResponse = authResult.getOrThrow()
                        Log.d(TAG, "🔧 Server authentication successful!")
                        
                        // Save all authentication data
                        sharedPreferences.edit().apply {
                            putString(PreferenceKeys.USER_ID, authResponse.user.id)
                            putString(PreferenceKeys.USER_NAME, authResponse.user.name)
                            putString(PreferenceKeys.USER_EMAIL, authResponse.user.email)
                            putString(PreferenceKeys.USER_PHOTO_URL, authResponse.user.photoUrl)
                            putString(PreferenceKeys.AUTH_TOKEN, idToken)
                            putBoolean("test_mode", true)
                            apply()
                        }
                        
                        saveAuthTokensFromServer(
                            accessToken = authResponse.accessToken,
                            refreshToken = authResponse.refreshToken ?: ""
                        )
                        
                        refreshUserProfile()
                        Log.d(TAG, "🔧 Authentication preparation complete - programmatic auth should now work")
                        return@withContext Result.success(true)
                    } else {
                        Log.e(TAG, "🔧 Server authentication failed: ${authResult.exceptionOrNull()}")
                        return@withContext Result.failure(authResult.exceptionOrNull() ?: Exception("Server authentication failed"))
                    }
                } else {
                    Log.e(TAG, "🔧 Google account has no ID token")
                    return@withContext Result.failure(Exception("Google account has no ID token"))
                }
            } else {
                Log.e(TAG, "🔧 Wrong Google account signed in. Expected: $email, Found: ${currentAccount?.email}")
                return@withContext Result.failure(Exception("Wrong Google account signed in"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔧 Error preparing authentication for testing", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Test-only method to set up authentication state for integration tests.
     * This method NEVER shows UI and fails fast if programmatic authentication is not possible.
     */
    open suspend fun setTestAuthenticationState(
        email: String,
        userId: String = "test_user_${System.currentTimeMillis()}",
        name: String = "Test User"
    ) {
        withContext(Dispatchers.IO) {
            Log.e(TAG, "🧪 [TEST] Setting test authentication state for: $email")
            Log.e(TAG, "🧪 [TEST] IMPORTANT: This method will NEVER show UI - it will fail fast if programmatic auth is not possible")
            
            try {
                // Try programmatic authentication first (this should work if user manually signed in earlier)
                Log.e(TAG, "🧪 [TEST] Attempting programmatic authentication...")
                try {
                    val authResult = authenticateProgrammatically(email)
                    if (authResult.isSuccess) {
                        Log.e(TAG, "🧪 [TEST] ✅ Programmatic authentication successful!")
                        return@withContext
                    } else {
                        Log.e(TAG, "🧪 [TEST] ❌ Programmatic authentication failed: ${authResult.exceptionOrNull()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🧪 [TEST] ❌ Programmatic authentication exception: ${e.message}", e)
                }
                
                // If programmatic auth failed, try to prepare it
                Log.e(TAG, "🧪 [TEST] Attempting to prepare authentication from existing sign-in...")
                try {
                    val prepareResult = prepareAuthenticationForTesting(email)
                    if (prepareResult.isSuccess) {
                        Log.e(TAG, "🧪 [TEST] ✅ Authentication preparation successful!")
                        return@withContext
                    } else {
                        Log.e(TAG, "🧪 [TEST] ❌ Authentication preparation failed: ${prepareResult.exceptionOrNull()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "🧪 [TEST] ❌ Authentication preparation exception: ${e.message}", e)
                }
                
                // CRITICAL: Never allow UI fallback in test mode
                Log.e(TAG, "🧪 [TEST] ❌ CRITICAL ERROR: Programmatic authentication failed completely")
                Log.e(TAG, "🧪 [TEST] ❌ This means the test account ($email) is not properly set up on this device")
                Log.e(TAG, "🧪 [TEST] ❌ Required steps:")
                Log.e(TAG, "🧪 [TEST] ❌   1. Add $email to device Settings > Accounts")
                Log.e(TAG, "🧪 [TEST] ❌   2. Sign into WhizVoice app manually with $email at least once")
                Log.e(TAG, "🧪 [TEST] ❌   3. Ensure the account has a valid ID token")
                Log.e(TAG, "🧪 [TEST] ❌ FAILING TEST to prevent UI dialog...")
                
                throw Exception("TEST AUTHENTICATION FAILED: Cannot authenticate $email programmatically. UI authentication is disabled in test mode. Please set up the test account properly on the device.")
                
            } catch (e: Exception) {
                Log.e(TAG, "🧪 [TEST] ❌ Error setting test authentication state", e)
                throw e
            }
        }
    }
} 