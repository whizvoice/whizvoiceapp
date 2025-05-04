package com.example.whiz

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
// import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface // Keep Surface if needed for theme application
import androidx.compose.runtime.LaunchedEffect // Remove if not needed
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Import Color
import androidx.hilt.navigation.compose.hiltViewModel // Keep if ViewModel is used directly here
import com.example.whiz.ui.screens.AssistantOverlayUi // Import the new UI
import com.example.whiz.ui.theme.WhizTheme
// import com.example.whiz.ui.viewmodels.ChatViewModel // Only if needed directly
import dagger.hilt.android.AndroidEntryPoint

import androidx.activity.enableEdgeToEdge

@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {

    private val TAG = "AssistantActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate - Intent: $intent")

        // Theme should handle translucency (Theme.Whiz.Assistant or Theme.AppCompat.Translucent)

        setContent {
            WhizTheme { // Apply your app's Compose theme
                // Surface might be needed to apply theme colors/typography correctly
                // Make it transparent so the Activity theme's transparency shows through
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent // Set Surface color to Transparent
                ) {
                    // Call the new Assistant Overlay UI
                    AssistantOverlayUi(
                        // Pass ViewModel instance if needed directly,
                        // but it's better if OverlayUi uses hiltViewModel() internally
                        // viewModel = hiltViewModel(), // Example if passing VM
                        onDismiss = { finish() } // Provide lambda to finish activity
                    )
                }
            }
        }
    }

    // onNewIntent - keep as is, might be needed later
    /*override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent - Received Intent: $intent")
        if (intent != null) {
            setIntent(intent)
            // Potentially notify ViewModel or UI about the new intent
        } else {
            Log.w(TAG, "onNewIntent received a null intent.")
        }
    }*/


    // onPause - Keep finish() commented out unless testing proves otherwise
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - Assistant Activity Paused")
        // if (!isChangingConfigurations) {
        //     finish()
        // }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}