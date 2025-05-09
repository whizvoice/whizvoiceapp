package com.example.whiz.data.auth

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// Preference keys
private object PreferenceKeys {
    const val AUTH_TOKEN = "auth_token"
    const val SERVER_TOKEN = "server_token"
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
    const val WEB_CLIENT_ID = "REDACTED_WEB_CLIENT_ID" // Replace with your actual web client ID
}

data class UserProfile(
    val userId: String,
    val name: String?,
    val email: String?,
    val photoUrl: String?
)

@Singleton
class AuthRepository @Inject constructor(
    private val context: Context
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
            
            GoogleSignIn.getClient(context, gso).also {
                // Check if there's a previously signed in account
                val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
                Log.d(TAG, "Previously signed in account: ${lastAccount?.email ?: "none"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating GoogleSignInClient", e)
            throw e
        }
    }
    
    // Private flow to update when preferences change
    private val _userProfileFlow = MutableStateFlow<UserProfile?>(null)
    val userProfile: StateFlow<UserProfile?> = _userProfileFlow

    // Server token flow
    private val _serverTokenFlow = MutableStateFlow<String?>(null)
    val serverToken: StateFlow<String?> = _serverTokenFlow
    
    init {
        // Load the initial user profile
        refreshUserProfile()
        // Load initial server token
        _serverTokenFlow.value = sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null)
        
        // Check if there's a previously signed in account
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        val localServerToken = sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null)
        Log.d(TAG, "Initial auth state check: Google account=${lastAccount != null}, Server token=${localServerToken != null}")
        
        // if (lastAccount == null || localServerToken == null) { // Commented out auto sign-out
        //     // Clear any stale data if not fully authenticated
        //     GlobalScope.launch {
        //         signOut()
        //     }
        // }
    }
    
    // Check if user is signed in
    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val serverToken = sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null)
        Log.d(TAG, "Checking sign-in state: Google account=${account != null}, Server token=${serverToken != null}")
        return account != null && serverToken != null
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
    
    // Save the server-issued JWT token
    suspend fun saveServerToken(token: String) {
        withContext(Dispatchers.IO) {
            sharedPreferences.edit()
                .putString(PreferenceKeys.SERVER_TOKEN, token)
                .apply()
            _serverTokenFlow.value = token // Update the flow
            Log.d(TAG, "Server token saved to preferences and flow updated")
        }
    }
    
    // Get the Google authentication token (can remain as is or also become a StateFlow if needed for reactivity)
    val authToken: Flow<String?> = kotlinx.coroutines.flow.flow { // Explicitly use kotlinx.coroutines.flow.flow
        emit(sharedPreferences.getString(PreferenceKeys.AUTH_TOKEN, null))
    }
    
    // Get the server-issued JWT token (now a StateFlow)
    // val serverToken: Flow<String?> = flow { // This is replaced by the StateFlow above
    //     emit(sharedPreferences.getString(PreferenceKeys.SERVER_TOKEN, null))
    // }
    
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
        googleSignInClient.signOut()
        
        // Clear stored preferences
        withContext(Dispatchers.IO) {
            sharedPreferences.edit().apply {
                remove(PreferenceKeys.AUTH_TOKEN)
                remove(PreferenceKeys.SERVER_TOKEN)
                remove(PreferenceKeys.USER_ID)
                remove(PreferenceKeys.USER_NAME)
                remove(PreferenceKeys.USER_EMAIL)
                remove(PreferenceKeys.USER_PHOTO_URL)
                apply()
            }
            
            // Update the flows
            refreshUserProfile()
            _serverTokenFlow.value = null // Clear the server token flow
            
            Log.d(TAG, "User signed out and preferences cleared")
        }
    }
} 