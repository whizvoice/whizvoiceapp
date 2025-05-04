package com.example.whiz

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.example.whiz.data.PreloadManager
import com.example.whiz.permissions.PermissionManager
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
        
        // Check for microphone permission at startup
        checkMicrophonePermission()
        
        setContent {
            WhizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    // Get permission state from PermissionManager
                    val hasPermission by permissionManager.microphonePermissionGranted.collectAsState()
                    
                    WhizNavHost(
                        navController = navController,
                        preloadManager = preloadManager,
                        hasPermission = hasPermission,
                        onRequestPermission = { requestMicrophonePermission() }
                    )
                }
            }
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
    }
}