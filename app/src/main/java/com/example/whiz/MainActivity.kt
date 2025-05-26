package com.example.whiz

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.whiz.data.PreloadManager
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.navigation.Screen
import com.example.whiz.ui.navigation.WhizNavHost
import com.example.whiz.ui.screens.AssistantOverlayUi
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var preloadManager: PreloadManager
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    private lateinit var navController: NavHostController
    private val chatsListViewModel: ChatsListViewModel by viewModels()
    
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
        enableEdgeToEdge()
        Log.d("MainActivity", "onCreate - Intent: $intent")
        
        // Check for microphone permission at startup
        checkMicrophonePermission()
        
        setContent {
            WhizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    navController = rememberNavController()
                    WhizNavHost(
                        navController = navController,
                        preloadManager = preloadManager,
                        hasPermission = permissionManager.microphonePermissionGranted.collectAsState().value,
                        onRequestPermission = { requestMicrophonePermission() }
                    )
                    
                    // Handle navigation after navController is initialized
                    LaunchedEffect(navController) {
                        handleIntentNavigation(intent)
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent - Received Intent: $intent")
        setIntent(intent)
        // Don't call handleIntentNavigation here, it will be handled by LaunchedEffect
    }

    private fun handleIntentNavigation(intent: Intent?) {
        Log.d(TAG, "handleIntentNavigation called with intent: $intent")
        Log.d(TAG, "navController initialized: ${::navController.isInitialized}")
        val enableVoiceMode = intent?.getBooleanExtra("ENABLE_VOICE_MODE", false) ?: false
        val fromPowerButton = intent?.getBooleanExtra("FROM_POWER_BUTTON", false) ?: false
        Log.d(TAG, "[LOG] ENABLE_VOICE_MODE: $enableVoiceMode, FROM_POWER_BUTTON: $fromPowerButton")
        
        // Check if we need to create a new chat on start (fallback from AssistantActivity)
        val createNewChatOnStart = intent?.getBooleanExtra("CREATE_NEW_CHAT_ON_START", false) ?: false
        val initialTranscription = intent?.getStringExtra("INITIAL_TRANSCRIPTION")
        
        if (createNewChatOnStart && ::navController.isInitialized) {
            Log.d(TAG, "CREATE_NEW_CHAT_ON_START flag detected, creating new chat")
            lifecycleScope.launch {
                try {
                    val newChatId = chatsListViewModel.createNewChat("Assistant Chat")
                    Log.d(TAG, "New chat created with ID: $newChatId for CREATE_NEW_CHAT_ON_START")
                    if (newChatId > 0) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(TAG, "Navigating to new chat/$newChatId from CREATE_NEW_CHAT_ON_START")
                            navController.navigate("chat/$newChatId") {
                                popUpTo(0) { inclusive = true } // Clear entire back stack
                                launchSingleTop = true
                            }
                            // Pass the voice mode flag and initial transcription to the ChatScreen
                            if (enableVoiceMode) {
                                Log.d(TAG, "Setting ENABLE_VOICE_MODE to true in savedStateHandle")
                                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                            }
                            initialTranscription?.let {
                                Log.d(TAG, "Setting INITIAL_TRANSCRIPTION in savedStateHandle: $it")
                                navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", it)
                            }
                            // Clear the extras
                            getIntent().removeExtra("CREATE_NEW_CHAT_ON_START")
                            getIntent().removeExtra("FROM_ASSISTANT")
                            getIntent().removeExtra("ENABLE_VOICE_MODE")
                            getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                        }, 500) // 500ms delay
                    } else {
                        Log.e(TAG, "Failed to create new chat from CREATE_NEW_CHAT_ON_START, staying on home screen")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating new chat from CREATE_NEW_CHAT_ON_START", e)
                }
            }
        } else {
            intent?.getLongExtra("NAVIGATE_TO_CHAT_ID", -1L)?.takeIf { it > 0 }?.let { chatId ->
                Log.d(TAG, "Intent to navigate to chat ID: $chatId")
                if (::navController.isInitialized) {
                val fromAssistant = intent.getBooleanExtra("FROM_ASSISTANT", false)
                val forceNavigation = intent.getBooleanExtra("FORCE_NAVIGATION", false)
                val delayNavigation = intent.getBooleanExtra("DELAY_NAVIGATION", false)
                val initialTranscription = intent.getStringExtra("INITIAL_TRANSCRIPTION")
                
                Log.d(TAG, "Navigation params - fromAssistant: $fromAssistant, forceNavigation: $forceNavigation, enableVoiceMode: $enableVoiceMode, delayNavigation: $delayNavigation, initialTranscription: $initialTranscription")
                
                if (fromAssistant && forceNavigation) {
                    // If coming from assistant with force navigation, clear everything and go to chat
                    if (delayNavigation) {
                        Log.d(TAG, "Using delayed navigation with 500ms delay")
                        // Use a post delayed handler instead of coroutines
                        Handler(Looper.getMainLooper()).postDelayed({
                            Log.d(TAG, "Executing delayed navigation to chat/$chatId")
                            navController.navigate("chat/$chatId") {
                                popUpTo(0) { inclusive = true } // Clear entire back stack
                                launchSingleTop = true
                            }
                            // Pass the voice mode flag and initial transcription to the ChatScreen
                            if (enableVoiceMode) {
                                Log.d(TAG, "Setting ENABLE_VOICE_MODE to true in savedStateHandle")
                                navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                            }
                            initialTranscription?.let {
                                Log.d(TAG, "Setting INITIAL_TRANSCRIPTION in savedStateHandle: $it")
                                navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", it)
                            }
                            // Clear the extras after navigation
                            getIntent().removeExtra("NAVIGATE_TO_CHAT_ID")
                            getIntent().removeExtra("FROM_ASSISTANT")
                            getIntent().removeExtra("FORCE_NAVIGATION")
                            getIntent().removeExtra("ENABLE_VOICE_MODE")
                            getIntent().removeExtra("DELAY_NAVIGATION")
                            getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                        }, 500) // 500ms delay
                    } else {
                        Log.d(TAG, "Executing immediate navigation to chat/$chatId")
                        navController.navigate("chat/$chatId") {
                            popUpTo(0) { inclusive = true } // Clear entire back stack
                            launchSingleTop = true
                        }
                        // Pass the voice mode flag and initial transcription to the ChatScreen
                        if (enableVoiceMode) {
                            Log.d(TAG, "Setting ENABLE_VOICE_MODE to true in savedStateHandle")
                            navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                        }
                        initialTranscription?.let {
                            Log.d(TAG, "Setting INITIAL_TRANSCRIPTION in savedStateHandle: $it")
                            navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", it)
                        }
                        // Clear the extras after navigation
                        getIntent().removeExtra("NAVIGATE_TO_CHAT_ID")
                        getIntent().removeExtra("FROM_ASSISTANT")
                        getIntent().removeExtra("FORCE_NAVIGATION")
                        getIntent().removeExtra("ENABLE_VOICE_MODE")
                        getIntent().removeExtra("DELAY_NAVIGATION")
                        getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                    }
                } else {
                    // Normal navigation
                    Log.d(TAG, "Executing normal navigation to chat/$chatId")
                    navController.navigate("chat/$chatId") {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                    // Clear the extras
                    getIntent().removeExtra("NAVIGATE_TO_CHAT_ID")
                    getIntent().removeExtra("FROM_ASSISTANT")
                    getIntent().removeExtra("ENABLE_VOICE_MODE")
                    getIntent().removeExtra("DELAY_NAVIGATION")
                    getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                }
            } else {
                Log.e(TAG, "navController not initialized when trying to navigate to chat ID: $chatId")
            }
        } ?: run {
            // Handle power button transcription
            val transcription = intent?.getStringExtra("TRANSCRIPTION")
            
            Log.d(TAG, "Power button handling - fromPowerButton: $fromPowerButton, transcription: $transcription, enableVoiceMode: $enableVoiceMode")
            
            if (fromPowerButton && transcription != null && ::navController.isInitialized) {
                Log.d(TAG, "Handling power button transcription: $transcription")
                // Create a new chat and navigate to it
                lifecycleScope.launch {
                    try {
                        Log.d(TAG, "Creating new chat from power button transcription")
                        val newChatId = chatsListViewModel.createNewChat("Assistant Chat")
                        Log.d(TAG, "New chat created with ID: $newChatId")
                        if (newChatId > 0) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                Log.d(TAG, "Navigating to new chat/$newChatId")
                                navController.navigate("chat/$newChatId") {
                                    popUpTo(0) { inclusive = true } // Clear entire back stack
                                    launchSingleTop = true
                                }
                                // Pass the voice mode flag and transcription to the ChatScreen
                                if (enableVoiceMode) {
                                    Log.d(TAG, "Setting ENABLE_VOICE_MODE to true in savedStateHandle")
                                    navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                                }
                                Log.d(TAG, "Setting INITIAL_TRANSCRIPTION in savedStateHandle: $transcription")
                                navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", transcription)
                                // Clear the extras
                                getIntent().removeExtra("FROM_POWER_BUTTON")
                                getIntent().removeExtra("TRANSCRIPTION")
                                getIntent().removeExtra("ENABLE_VOICE_MODE")
                            }, 500) // 500ms delay
                        } else {
                            Log.e(TAG, "Failed to create new chat from power button transcription")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating new chat from power button", e)
                    }
                }
                            } else {
                    Log.d(TAG, "Skipping power button handling - fromPowerButton: $fromPowerButton, transcription: $transcription, navController initialized: ${::navController.isInitialized}")
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
        Log.d("MainActivity", "Main Activity Resumed")
        // Re-check permission when activity resumes in case it was changed in settings
        permissionManager.checkMicrophonePermission()
        // If NavController is initialized, handle current intent again in case it was delivered while paused
        // and MainActivity wasn't recreated but onNewIntent wasn't called (e.g. returning to app)
        // This is a bit of an edge case, but ensures the navigation occurs if pending.
        if (::navController.isInitialized) {
             handleIntentNavigation(intent) // Process current intent again
             
             // Check if we're currently in a chat screen and potentially restart continuous listening
             val currentDestination = navController.currentDestination?.route
             if (currentDestination?.startsWith("chat/") == true) {
                 Log.d("MainActivity", "Resumed in chat screen - continuous listening may need to be restarted by ChatViewModel")
                 // The ChatViewModel will handle this through its own lifecycle and state management
                 // We don't need to do anything specific here since the ChatViewModel observes app state
             }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d("MainActivity", "Main Activity Paused")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "Main Activity Destroyed")
    }
}