package com.example.whiz.ui.viewmodels

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.whiz.data.auth.AuthRepository
import com.example.whiz.data.auth.UserProfile
import com.example.whiz.data.remote.AuthApi
import com.example.whiz.data.remote.User
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val authApi: AuthApi
) : ViewModel() {
    
    private val TAG = "AuthViewModel"
    
    // Error state
    private val _errorState = MutableStateFlow<String?>(null)
    val errorState: StateFlow<String?> = _errorState
    
    // Loading state
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading
    
    // Navigation trigger
    private val _navigateToHome = MutableStateFlow(false)
    val navigateToHome: StateFlow<Boolean> = _navigateToHome
    
    // Stream of authentication state
    val isAuthenticated: StateFlow<Boolean> = combine(
        authRepository.userProfile,
        authRepository.serverToken
    ) { userProfile, serverToken ->
        userProfile != null && !serverToken.isNullOrBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )
    
    // Stream of user profile data
    val userProfile: StateFlow<UserProfile?> = authRepository.userProfile
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    
    // Get auth token
    val authToken: Flow<String?> = authRepository.authToken
    
    // Get sign-in intent for Google Sign-In
    fun getSignInIntent(): Intent {
        return authRepository.createSignInIntent()
    }
    
    fun onNavigateToHomeConsumed() {
        _navigateToHome.value = false
        Log.d(TAG, "onNavigateToHomeConsumed called, navigateToHome set to false")
    }

    // New function for UI to call, launches process in viewModelScope
    fun initiateSignInProcessing(account: GoogleSignInAccount?) {
        if (account == null) {
            Log.e(TAG, "Sign in failed: Could not get account from UI trigger")
            _errorState.value = "Sign in failed: Could not get account"
            _isLoading.value = false // Ensure loading is stopped
            return
        }

        Log.d(TAG, "Processing sign in for account: ${account.email}, has ID token: ${account.idToken != null}")
        _isLoading.value = true
        _errorState.value = null // Clear previous errors

        viewModelScope.launch { // Launch coroutine here
            try {
                // First save the basic account info locally
                authRepository.processSignInAccount(account) // Suspend call

                // Get ID token and authenticate with server
                val idToken = account.idToken
                if (idToken != null) {
                    Log.d(TAG, "Got ID token of length ${idToken.length}, authenticating with server")
                    val result = authApi.authenticateWithGoogle(idToken) // Suspend call
                    Log.d(TAG, "Server /auth/google result: $result")

                    if (result.isSuccess) {
                        val authResponse = result.getOrThrow()
                        Log.d(TAG, "Server authentication successful: ${authResponse.user.email}")

                        // Save the server token
                        Log.d(TAG, "Saving server tokens: Access: ${authResponse.accessToken.take(10)}..., Refresh: ${authResponse.refreshToken.take(10)}...")
                        authRepository.saveAuthTokensFromServer(authResponse.accessToken, authResponse.refreshToken) // Suspend call, now using both tokens
                        
                        _navigateToHome.value = true // Trigger navigation on success
                        Log.d(TAG, "Sign-in process complete, navigateToHome set to true")

                    } else {
                        val exception = result.exceptionOrNull()
                        Log.e(TAG, "Server authentication failed", exception)
                        _errorState.value = "Server authentication failed: ${exception?.message}"
                    }
                } else {
                    Log.w(TAG, "No ID token available for server auth - this suggests the client ID configuration is incorrect")
                    _errorState.value = "No ID token available for server authentication"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing sign in", e)
                _errorState.value = "Error processing sign in: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // Sign out
    fun signOut() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                authRepository.signOut()
                _errorState.value = null
            } catch (e: Exception) {
                _errorState.value = "Error signing out: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    // Clear error
    fun clearError() {
        _errorState.value = null
    }

    fun cancelSignInAttempt() {
        Log.d(TAG, "Sign-in attempt was canceled or failed from UI.")
        _isLoading.value = false
        // Optionally, set an error message or log specific cancellation details
        // _errorState.value = "Sign-in canceled." 
    }

    init {
        // Observe authentication state and set error if in half-logged-in state
        viewModelScope.launch {
            isAuthenticated.collect { authenticated ->
                android.util.Log.d("AuthViewModel", "isAuthenticated changed: $authenticated")
                if (!authenticated) {
                    val account = authRepository.getLastSignedInGoogleAccount()
                    if (account != null) {
                        _errorState.value = "Your session is incomplete. Please log in again."
                    }
                }
            }
        }
    }
} 