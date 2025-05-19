package com.example.whiz

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface // Keep Surface if needed for theme application
import androidx.compose.runtime.LaunchedEffect // Remove if not needed
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Import Color
import androidx.hilt.navigation.compose.hiltViewModel // Keep if ViewModel is used directly here
import androidx.lifecycle.lifecycleScope // Ensure this import is present
import com.example.whiz.ui.screens.AssistantOverlayUi // Import the new UI
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {

    private val TAG = "AssistantActivity"
    private val chatsListViewModel: ChatsListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate - Intent: $intent")

        val isAssistantLaunch = intent.getBooleanExtra("IS_ASSISTANT_LAUNCH", false)
        Log.d(TAG, "IS_ASSISTANT_LAUNCH: $isAssistantLaunch")

        if (isAssistantLaunch) {
            lifecycleScope.launch {
                try {
                    val newChatId = chatsListViewModel.createNewChat("Assistant Chat")
                    Log.d(TAG, "New chat created with ID: $newChatId")
                    if (newChatId > 0) {
                        val mainActivityIntent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
                            putExtra("NAVIGATE_TO_CHAT_ID", newChatId)
                        }
                        startActivity(mainActivityIntent)
                    } else {
                        Log.e(TAG, "Failed to create new chat from assistant.")
                        // Optionally, launch MainActivity to home if chat creation fails
                        val mainActivityIntent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
                        }
                        startActivity(mainActivityIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating new chat or starting MainActivity: ", e)
                    // Fallback to launching MainActivity to home
                     val mainActivityIntent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
                    }
                    startActivity(mainActivityIntent)
                }
                finish() // Finish AssistantActivity after attempting to launch MainActivity
            }
        } else {
            // Standard display of AssistantOverlayUi if not an assistant launch for new chat
            // This path might be deprecated if IS_ASSISTANT_LAUNCH is always true from the session
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
        // Avoid finishing if it's due to configuration change or if already finishing
        if (!isChangingConfigurations && !isFinishing) {
             // Consider if finishing here is always desired if it wasn't an assistant launch that created a chat.
             // If it was an assistant launch, it should already be finishing.
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}