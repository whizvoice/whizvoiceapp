package com.example.whiz

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.whiz.data.PreloadManager
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.navigation.Screen
import com.example.whiz.ui.navigation.WhizNavHost
import com.example.whiz.ui.theme.WhizTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preloadManager: PreloadManager
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    private lateinit var navController: NavHostController

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        try {
            // Update permission state safely
            permissionManager.updateMicrophonePermission(isGranted)
            
            // Log for debugging
            Log.d("MainActivity", "Microphone permission result: $isGranted")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling permission result", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // XML layout and button are removed, assuming full Compose navigation
        
        // Check for microphone permission at startup
        checkMicrophonePermission()
        
        setContent {
            WhizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    navController = rememberNavController()
                    // Get permission state from PermissionManager
                    val hasPermission by permissionManager.microphonePermissionGranted.collectAsState()
                    
                    WhizNavHost(
                        navController = navController,
                        preloadManager = preloadManager,
                        hasPermission = hasPermission,
                        onRequestPermission = { requestMicrophonePermission() }
                    )

                    // Handle initial navigation intent
                    LaunchedEffect(key1 = intent) { // Use intent as key
                        handleIntentNavigation(intent)
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) { // Corrected signature: Intent is not nullable
        super.onNewIntent(intent)
        Log.d("MainActivity", "onNewIntent: $intent")
        setIntent(intent) // Update the activity's intent
        if (::navController.isInitialized) {
            handleIntentNavigation(intent) // Pass the non-nullable intent
        }
    }

    private fun handleIntentNavigation(intent: Intent?) { // Keep intent nullable here for initial check from onCreate
        intent?.getLongExtra("NAVIGATE_TO_CHAT_ID", -1L)?.takeIf { it > 0 }?.let { chatId ->
            Log.d("MainActivity", "Intent to navigate to chat ID: $chatId")
            if (::navController.isInitialized) {
                 navController.navigate("chat/$chatId") {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            // Clear the extra only after successful navigation attempt or if nav controller was init
            // To prevent issues if handleIntentNavigation is called multiple times before nav is ready.
            getIntent().removeExtra("NAVIGATE_TO_CHAT_ID")
        }
    }
    
    private fun checkMicrophonePermission() {
        permissionManager.checkMicrophonePermission()
    }
    
    private fun requestMicrophonePermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    
    override fun onResume() {
        super.onResume()
        // Re-check permission when activity resumes in case it was changed in settings
        permissionManager.checkMicrophonePermission()
        // If NavController is initialized, handle current intent again in case it was delivered while paused
        // and MainActivity wasn't recreated but onNewIntent wasn't called (e.g. returning to app)
        // This is a bit of an edge case, but ensures the navigation occurs if pending.
        if (::navController.isInitialized) {
             handleIntentNavigation(intent) // Process current intent again
        }
    }
}