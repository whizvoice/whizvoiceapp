package com.example.whiz

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject
import com.example.whiz.data.auth.AuthRepository

@AndroidEntryPoint
class AssistantActivity : AppCompatActivity() {

    private val TAG = "AssistantActivity"
    private val chatsListViewModel: ChatsListViewModel by viewModels()
    private var isHandlingLaunch = false
    private var isFinishing = false

    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - Intent: $intent")

        val isAssistantLaunch = intent.getBooleanExtra("IS_ASSISTANT_LAUNCH", false)
        val enableVoiceMode = intent.getBooleanExtra("ENABLE_VOICE_MODE", false)
        val transcription = intent.getStringExtra("TRANSCRIPTION")
        Log.d(TAG, "IS_ASSISTANT_LAUNCH: $isAssistantLaunch, ENABLE_VOICE_MODE: $enableVoiceMode, TRANSCRIPTION: $transcription")

        // Always redirect to MainActivity with appropriate settings - no overlay mode needed
        if (!isHandlingLaunch && !isFinishing) {
            isHandlingLaunch = true
            lifecycleScope.launch {
                try {
                    // Add a small delay to ensure activity is fully initialized
                    delay(100)

                    if (isAssistantLaunch) {
                        // Check if user is authenticated before creating chat
                        val isAuthenticated = authRepository.isSignedIn()
                        Log.d(TAG, "Assistant launch - user authenticated: $isAuthenticated")

                        if (isAuthenticated) {
                            Log.d(TAG, "User is authenticated, creating new optimistic chat for assistant launch...")
                            val newChatId = chatsListViewModel.createNewChatOptimistic("Assistant Chat")
                            Log.d(TAG, "New optimistic chat created with ID: $newChatId")
                            if (newChatId != -1L) { // Optimistic chats have negative IDs, so check for failure (-1)
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
                                startMainActivityAndFinish(intent)
                            } else {
                                Log.e(TAG, "Failed to create new chat from assistant. Starting MainActivity with voice mode enabled instead.")
                                // Fallback: Start MainActivity with voice mode enabled and let it handle creating a chat
                                val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    putExtra("FROM_ASSISTANT", true)
                                    putExtra("ENABLE_VOICE_MODE", true)
                                    putExtra("CREATE_NEW_CHAT_ON_START", true)
                                    // Add transcription if available
                                    transcription?.let { putExtra("INITIAL_TRANSCRIPTION", it) }
                                }
                                Log.d(TAG, "Starting MainActivity with voice mode and create_new_chat flag")
                                startMainActivityAndFinish(intent)
                            }
                        } else {
                            Log.d(TAG, "User not authenticated, starting MainActivity without creating chat")
                            // User not authenticated - start MainActivity with voice flags but don't create chat
                            // MainActivity and WhizNavHost will handle showing login screen
                            val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra("FROM_ASSISTANT", true)
                                putExtra("ENABLE_VOICE_MODE", true)
                                // Add transcription if available - can be used after login
                                transcription?.let { putExtra("INITIAL_TRANSCRIPTION", it) }
                                // Don't set CREATE_NEW_CHAT_ON_START or NAVIGATE_TO_CHAT_ID
                                // Let the normal auth flow handle navigation
                            }
                            Log.d(TAG, "Starting MainActivity for unauthenticated voice launch")
                            startMainActivityAndFinish(intent)
                        }
                    } else {
                        // Non-assistant launch - still redirect to MainActivity but without special voice settings
                        Log.d(TAG, "Non-assistant launch, redirecting to MainActivity")
                        val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            // Pass through any transcription that might exist
                            transcription?.let { 
                                putExtra("INITIAL_TRANSCRIPTION", it)
                                putExtra("CREATE_NEW_CHAT_ON_START", true) // If we have transcription, create a chat
                            }
                            if (enableVoiceMode) {
                                putExtra("ENABLE_VOICE_MODE", true)
                            }
                        }
                        Log.d(TAG, "Starting MainActivity for non-assistant launch")
                        startMainActivityAndFinish(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating new chat or starting MainActivity: ", e)
                    // Fallback to home screen
                    val intent = Intent(this@AssistantActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    Log.d(TAG, "Error occurred, falling back to home screen")
                    startMainActivityAndFinish(intent)
                }
            }
        }
    }
    
    private fun startMainActivityAndFinish(intent: Intent) {
        if (!isFinishing) {
            isFinishing = true
            startActivity(intent)
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent - Received Intent: $intent")
        setIntent(intent)
        // If we get a new intent while handling the launch, ignore it to prevent loops
        if (!isHandlingLaunch && !isFinishing) {
            Log.d(TAG, "Recreating activity due to new intent")
            recreate()
        } else {
            Log.d(TAG, "Ignoring new intent - isHandlingLaunch: $isHandlingLaunch, isFinishing: $isFinishing")
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - Assistant Activity Paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy - isHandlingLaunch: $isHandlingLaunch, isFinishing: $isFinishing")
        isHandlingLaunch = false
        isFinishing = false
    }
}