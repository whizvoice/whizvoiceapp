package com.example.whiz.ui.screens

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.whiz.R
import com.example.whiz.ui.navigation.Screen
import com.example.whiz.ui.viewmodels.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    navController: NavController,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()
    val userProfile by authViewModel.userProfile.collectAsState()
    val isLoading by authViewModel.isLoading.collectAsState()
    val navigateToHome by authViewModel.navigateToHome.collectAsState()
    val errorState by authViewModel.errorState.collectAsState()
    val scope = rememberCoroutineScope()
    var signInError by remember { mutableStateOf<String?>(null) }

    // Set up the launcher for Google Sign-In
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("LoginScreen", "Sign in successful, processing result")
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                Log.d("LoginScreen", "Successfully got account: ${account.email}, calling ViewModel to process.")
                scope.launch {
                    authViewModel.initiateSignInProcessing(account)
                }
            } catch (e: ApiException) {
                Log.e("LoginScreen", "Google sign in failed with status code: ${e.statusCode}", e)
                signInError = if (e.statusCode == 7) {
                    "No internet connection. Please check your connection and try again."
                } else {
                    "Sign-in failed (error ${e.statusCode}). Please try again."
                }
                Log.e("LoginScreen", "Sign-in error: ${signInError}")
            }
        } else {
            Log.d("LoginScreen", "Sign in canceled or failed, result code: ${result.resultCode}")
            if (result.data != null) {
                try {
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    val account = task.getResult(ApiException::class.java)
                    Log.d("LoginScreen", "Task successful despite canceled result: ${account.email}")
                } catch (e: ApiException) {
                    Log.e("LoginScreen", "Sign-in failed with status code: ${e.statusCode}", e)
                    signInError = if (e.statusCode == 7) {
                        "No internet connection. Please check your connection and try again."
                    } else {
                        "Sign-in failed (error ${e.statusCode}). Please try again."
                    }
                } catch (e: Exception) {
                    Log.e("LoginScreen", "Error checking sign-in data", e)
                }
            }
            authViewModel.cancelSignInAttempt()
        }
    }

    // Check Google Play Services availability
    LaunchedEffect(Unit) {
        val availability = GoogleApiAvailability.getInstance()
        val result = availability.isGooglePlayServicesAvailable(context)
        if (result != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            Log.e("LoginScreen", "Google Play Services not available: $result")
            if (availability.isUserResolvableError(result)) {
                Log.d("LoginScreen", "Error is user resolvable")
            }
        } else {
            Log.d("LoginScreen", "Google Play Services available")
        }
    }

    // Navigate to home screen when authenticated
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        }
    }

    // Show error screen when auth fails
    LaunchedEffect(errorState) {
        errorState?.let { errorMsg ->
            Log.d("LoginScreen", "Auth error: $errorMsg")
            signInError = if (errorMsg.contains("UnknownHost", ignoreCase = true) ||
                errorMsg.contains("Unable to resolve host", ignoreCase = true) ||
                errorMsg.contains("network", ignoreCase = true) ||
                errorMsg.contains("SocketTimeout", ignoreCase = true) ||
                errorMsg.contains("ConnectException", ignoreCase = true)) {
                "No internet connection. Please check your connection and try again."
            } else {
                "Something went wrong. Please try again."
            }
            authViewModel.clearError()
        }
    }

    if (isLoading && signInError == null) {
        // Show loading indicator while authentication is in progress (first attempt)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (!isAuthenticated) {
        if (signInError != null) {
            // Error screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher),
                    contentDescription = "WhizVoice Logo",
                    modifier = Modifier.size(120.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Can't sign in",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = signInError!!,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(0.8f)
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        try {
                            val signInIntent = authViewModel.getSignInIntent()
                            Log.d("LoginScreen", "Retrying sign-in intent")
                            signInLauncher.launch(signInIntent)
                        } catch (e: Exception) {
                            Log.e("LoginScreen", "Error launching sign-in retry", e)
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(50.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = "Try again", fontSize = 16.sp)
                    }
                }
            }
        } else {
            // Normal sign-in screen
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // App Logo/Icon
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher),
                    contentDescription = "WhizVoice Logo",
                    modifier = Modifier.size(120.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Welcome to WhizVoice",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = {
                        try {
                            val signInIntent = authViewModel.getSignInIntent()
                            Log.d("LoginScreen", "Launching sign-in intent")
                            signInLauncher.launch(signInIntent)
                        } catch (e: Exception) {
                            Log.e("LoginScreen", "Error launching sign-in", e)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(50.dp)
                        .semantics { contentDescription = "Sign in with Google button" }
                ) {
                    Text(text = "Sign in with Google", fontSize = 16.sp)
                }
            }
        }
    }
} 