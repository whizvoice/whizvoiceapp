package com.example.whiz

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.example.whiz.ui.screens.AssistantOverlayUi
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@AndroidEntryPoint
class AssistantActivity : AppCompatActivity() {

    private val TAG = "AssistantActivity"
    private val chatsListViewModel: ChatsListViewModel by viewModels()
    private var isHandlingAssistantLaunch = false
    private var isFinishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d(TAG, "onCreate - Intent: $intent")

        // Set the app icon in the action bar
        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.whiz_icon)
        }

        val isAssistantLaunch = intent.getBooleanExtra("IS_ASSISTANT_LAUNCH", false)
        val enableVoiceMode = intent.getBooleanExtra("ENABLE_VOICE_MODE", false)
        val transcription = intent.getStringExtra("TRANSCRIPTION")
        Log.d(TAG, "IS_ASSISTANT_LAUNCH: $isAssistantLaunch, ENABLE_VOICE_MODE: $enableVoiceMode, TRANSCRIPTION: $transcription")

        if (isAssistantLaunch && !isHandlingAssistantLaunch && !isFinishing) {
            isHandlingAssistantLaunch = true
            lifecycleScope.launch {
                try {
                    // Add a small delay to ensure activity is fully initialized
                    delay(100)
                    
                    Log.d(TAG, "Creating new chat...")
                    val newChatId = chatsListViewModel.createNewChat("Assistant Chat")
                    Log.d(TAG, "New chat created with ID: $newChatId")
                    if (newChatId > 0) {
                        // Navigate directly to the chat screen
                        val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            putExtra("NAVIGATE_TO_CHAT_ID", newChatId)
                            putExtra("FROM_ASSISTANT", true)
                            putExtra("FORCE_NAVIGATION", true)
                            putExtra("ENABLE_VOICE_MODE", true)
                            // Add transcription if available
                            transcription?.let { putExtra("INITIAL_TRANSCRIPTION", it) }
                        }
                        Log.d(TAG, "Starting MainActivity with chat ID: $newChatId")
                        if (!isFinishing) {
                            isFinishing = true
                            startActivity(intent)
                            finish()
                        }
                    } else {
                        Log.e(TAG, "Failed to create new chat from assistant.")
                        // Fallback to home screen
                        val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        Log.d(TAG, "Falling back to home screen")
                        startActivity(intent)
                        isFinishing = true
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating new chat or starting MainActivity: ", e)
                    // Fallback to home screen
                    val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    Log.d(TAG, "Error occurred, falling back to home screen")
                    startActivity(intent)
                    isFinishing = true
                    finish()
                }
            }
        } else {
            // Standard display of AssistantOverlayUi if not an assistant launch
            Log.d(TAG, "Displaying standard AssistantOverlayUi")
            setContent {
                WhizTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent
                    ) {
                        AssistantOverlayUi(
                            onDismiss = { 
                                Log.d(TAG, "AssistantOverlayUi dismissed")
                                isFinishing = true
                                finish() 
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent - Received Intent: $intent")
        setIntent(intent)
        // If we get a new intent while handling the assistant launch, ignore it
        if (!isHandlingAssistantLaunch && !isFinishing) {
            Log.d(TAG, "Recreating activity due to new intent")
            recreate()
        } else {
            Log.d(TAG, "Ignoring new intent - isHandlingAssistantLaunch: $isHandlingAssistantLaunch, isFinishing: $isFinishing")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - Assistant Activity Paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - isHandlingAssistantLaunch: $isHandlingAssistantLaunch, isFinishing: $isFinishing")
        isHandlingAssistantLaunch = false
        isFinishing = false
    }
}