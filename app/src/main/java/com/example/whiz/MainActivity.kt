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
import com.example.whiz.ui.theme.WhizTheme
import com.example.whiz.ui.viewmodels.ChatsListViewModel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Flag to prevent multiple voice launch navigations from the same intent
    // Use SharedPreferences to persist across Activity recreations
    private var voiceLaunchNavigationCompleted: Boolean
        get() = getSharedPreferences("voice_launch", MODE_PRIVATE).getBoolean("navigation_completed", false)
        set(value) = getSharedPreferences("voice_launch", MODE_PRIVATE).edit().putBoolean("navigation_completed", value).apply()
    
    private var lastIntentTraceId: Long?
        get() {
            val prefs = getSharedPreferences("voice_launch", MODE_PRIVATE)
            val id = prefs.getLong("last_trace_id", -1L)
            return if (id == -1L) null else id
        }
        set(value) = getSharedPreferences("voice_launch", MODE_PRIVATE).edit().putLong("last_trace_id", value ?: -1L).apply()

    @Inject
    lateinit var preloadManager: PreloadManager
    
    @Inject
    lateinit var permissionManager: PermissionManager
    
    @Inject
    lateinit var ttsManager: com.example.whiz.services.TTSManager
    
    @Inject
    lateinit var authRepository: com.example.whiz.data.auth.AuthRepository
    
    @Inject
    lateinit var voiceManager: com.example.whiz.ui.viewmodels.VoiceManager
    
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
        
        // Enhanced intent logging to detect launch source
        logDetailedIntentInfo(intent, "onCreate")
        
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
                        permissionManager = permissionManager,
                        voiceManager = voiceManager,
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
        logDetailedIntentInfo(intent, "onNewIntent")
        setIntent(intent)
        // Don't call handleIntentNavigation here, it will be handled by LaunchedEffect
    }

    private fun logDetailedIntentInfo(intent: Intent?, source: String) {
        Log.d(TAG, "=== INTENT ANALYSIS ($source) ===")
        Log.d(TAG, "Basic Intent: $intent")
        
        intent?.let { i ->
            // Basic intent info
            Log.d(TAG, "Action: ${i.action}")
            Log.d(TAG, "Categories: ${i.categories}")
            Log.d(TAG, "Package: ${i.`package`}")
            Log.d(TAG, "Component: ${i.component}")
            Log.d(TAG, "Flags: ${Integer.toHexString(i.flags)}")
            
            // Check all extras
            val extras = i.extras
            if (extras != null && !extras.isEmpty) {
                Log.d(TAG, "Intent Extras:")
                for (key in extras.keySet()) {
                    val value = extras.get(key)
                    Log.d(TAG, "  $key: $value (${value?.javaClass?.simpleName})")
                }
            } else {
                Log.d(TAG, "No intent extras")
            }
            
            // Check referrer information
            try {
                val referrer = getReferrer()
                Log.d(TAG, "Referrer: $referrer")
            } catch (e: Exception) {
                Log.d(TAG, "Could not get referrer: ${e.message}")
            }
            
            // Check calling package/activity info
            try {
                val callingPackage = callingActivity?.packageName
                val callingActivity = callingActivity?.className
                Log.d(TAG, "Calling Package: $callingPackage")
                Log.d(TAG, "Calling Activity: $callingActivity")
            } catch (e: Exception) {
                Log.d(TAG, "Could not get calling info: ${e.message}")
            }
            
            // Check if this might be a voice launch
            val isLikelyVoiceLaunch = detectVoiceLaunch()
            Log.d(TAG, "Likely voice launch: $isLikelyVoiceLaunch")
            
            if (isLikelyVoiceLaunch && source == "onCreate") {
                Log.d(TAG, "🎤 VOICE LAUNCH DETECTED - adding assistant flags")
                // Add the assistant flags that would normally come from AssistantActivity
                i.putExtra("FROM_ASSISTANT", true)
                i.putExtra("ENABLE_VOICE_MODE", true) 
                i.putExtra("CREATE_NEW_CHAT_ON_START", true)
                // Update the activity's intent so the flags are accessible
                setIntent(i)
                Log.d(TAG, "🎤 Updated activity intent with voice launch flags")
            }
        }
        Log.d(TAG, "=== END INTENT ANALYSIS ===")
    }
    
    private fun detectVoiceLaunch(): Boolean {
        return try {
            // PRIMARY DETECTION: Intent extras analysis (MOST RELIABLE!)
            // Voice launches ALWAYS have tracing_intent_id, manual launches NEVER do
            val traceId = intent?.extras?.getLong("tracing_intent_id")
            val hasTraceId = traceId != null && traceId > 0
            
                    // SECONDARY DETECTION: Intent flags analysis  
        // Voice: 0x10000000, Manual: 0x10200000
        val intentFlags = intent?.flags ?: 0
        val hasVoiceFlags = (intentFlags and 0x10000000) != 0 && (intentFlags and 0x00200000) == 0
            
            // TERTIARY DETECTION: Bounds analysis
            // Manual launches have bounds (app icon position), voice launches don't
            val sourceBounds = intent?.sourceBounds
            val noBounds = sourceBounds == null
            
            Log.d(TAG, "Voice launch detection:")
            Log.d(TAG, "  Has trace ID: $hasTraceId (traceId: $traceId) ← PRIMARY")
            Log.d(TAG, "  Voice flags: $hasVoiceFlags (flags: ${String.format("0x%08X", intentFlags)}) ← SECONDARY")  
            Log.d(TAG, "  No bounds: $noBounds (bounds: $sourceBounds) ← TERTIARY")
            
            // Voice launch if PRIMARY indicator is present, or SECONDARY + TERTIARY both match
            val isVoiceLaunch = hasTraceId || (hasVoiceFlags && noBounds)
            
            Log.d(TAG, "  FINAL DECISION: Voice launch = $isVoiceLaunch")
            isVoiceLaunch
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting voice launch", e)
            false
        }
    }

    private fun handleIntentNavigation(intent: Intent?) {
        // Always use getIntent() to ensure we're working with the current activity intent
        val currentIntent = getIntent()
        Log.d(TAG, "🔍 handleIntentNavigation called - using getIntent(): $currentIntent")
        
        // 🕵️ DEBUG: Check if intent extras are actually cleared
        val createNewChat = currentIntent?.getBooleanExtra("CREATE_NEW_CHAT_ON_START", false) ?: false
        Log.d(TAG, "🔍 DEBUG: CREATE_NEW_CHAT_ON_START flag at start of handleIntentNavigation: $createNewChat")
        
        // Reset voice launch flag ONLY if this is a genuinely new intent (different trace ID)
        val currentTraceId = currentIntent?.getLongExtra("tracing_intent_id", -1L)
        Log.d(TAG, "🔍 TRACE ID DEBUG: currentTraceId = $currentTraceId, lastIntentTraceId = $lastIntentTraceId, voiceLaunchNavigationCompleted = $voiceLaunchNavigationCompleted")
        
        if (currentTraceId != null && currentTraceId != -1L) {
            // Only reset flag if we have a DIFFERENT valid trace ID (new voice launch)
            if (lastIntentTraceId != null && currentTraceId != lastIntentTraceId) {
                Log.d(TAG, "🔄 NEW voice launch detected (trace ID: $currentTraceId, previous: $lastIntentTraceId) - resetting voice launch flag")
                voiceLaunchNavigationCompleted = false
            } else if (lastIntentTraceId == null) {
                Log.d(TAG, "🔍 First time seeing trace ID $currentTraceId - no flag reset needed")
            } else {
                Log.d(TAG, "🔍 Same trace ID ($currentTraceId) - keeping voiceLaunchNavigationCompleted as $voiceLaunchNavigationCompleted")
            }
            lastIntentTraceId = currentTraceId
        } else {
            // No valid trace ID - DON'T reset the flag, just keep current state
            Log.d(TAG, "🔍 TRACE ID DEBUG: No valid trace ID found - preserving voiceLaunchNavigationCompleted as $voiceLaunchNavigationCompleted")
        }
        
        // 🕵️ DEBUG: Track why we're processing this intent
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.getOrNull(3)?.methodName ?: "unknown"
        Log.d(TAG, "🔍 handleIntentNavigation called from: $caller")
        
        // Handle sign-out action first
        val actionSignOut = currentIntent?.getStringExtra("action")
        if (actionSignOut == "sign_out") {
            Log.d(TAG, "Sign-out action detected - triggering Firebase sign-out")
            lifecycleScope.launch {
                try {
                    // Use injected AuthRepository to call signOut
                    authRepository.signOut()
                    Log.d(TAG, "Sign-out completed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during programmatic sign-out", e)
                }
            }
            return // Don't process other intent extras after sign-out
        }
        
        val createNewChatOnStart = currentIntent?.getBooleanExtra("CREATE_NEW_CHAT_ON_START", false) ?: false
        val fromAssistant = currentIntent?.getBooleanExtra("FROM_ASSISTANT", false) ?: false
        val fromPowerButton = currentIntent?.getBooleanExtra("FROM_POWER_BUTTON", false) ?: false
        val enableVoiceMode = currentIntent?.getBooleanExtra("ENABLE_VOICE_MODE", false) ?: false
        val initialTranscription = currentIntent?.getStringExtra("INITIAL_TRANSCRIPTION")
        
        if (createNewChatOnStart && ::navController.isInitialized) {
            // Prevent multiple voice launch navigations from the same intent - CHECK THIS FIRST!
            if (voiceLaunchNavigationCompleted) {
                Log.d(TAG, "🚨 Voice launch navigation already completed - ignoring duplicate call")
                return
            }
            
            Log.d(TAG, "🚨 CREATE_NEW_CHAT_ON_START flag detected, navigating to new chat screen")
            
            // Mark navigation as completed IMMEDIATELY to prevent race conditions
            voiceLaunchNavigationCompleted = true
            Log.d(TAG, "🔒 DUPLICATE PREVENTION: Set voiceLaunchNavigationCompleted = true")
            
            Log.d(TAG, "🚨 Voice launch navigation trigger - this should only happen once per voice launch")
            Log.d(TAG, "🚨 Note: Not pre-creating optimistic chat - ChatViewModel will create it when message is sent")
            Log.d(TAG, "🚨 Current nav destination before navigation: ${navController.currentDestination?.route}")
            
            // Navigate to new chat screen without pre-creating optimistic chat
            // This makes voice launch consistent with manual launch (clicking "New Chat" button)
            Log.d(TAG, "🚨 About to call navigateWhenReady for ${Screen.AssistantChat.route}")
            navigateWhenReady(Screen.AssistantChat.route) {
                Log.d(TAG, "🚨 Inside navigateWhenReady callback - navigation should be complete")
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
                Log.d(TAG, "🧹 CLEARING intent extras after new chat navigation")
                getIntent().removeExtra("CREATE_NEW_CHAT_ON_START")
                getIntent().removeExtra("FROM_ASSISTANT")
                getIntent().removeExtra("ENABLE_VOICE_MODE")
                getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                Log.d(TAG, "🧹 Intent extras cleared - future resume should not create new chat")
            }
        } else {
            currentIntent?.getLongExtra("NAVIGATE_TO_CHAT_ID", -1L)?.takeIf { it > 0 }?.let { chatId ->
                Log.d(TAG, "Intent to navigate to chat ID: $chatId")
                if (::navController.isInitialized) {
                val fromAssistant = currentIntent.getBooleanExtra("FROM_ASSISTANT", false)
                val forceNavigation = currentIntent.getBooleanExtra("FORCE_NAVIGATION", false)
                val delayNavigation = currentIntent.getBooleanExtra("DELAY_NAVIGATION", false)
                val initialTranscription = currentIntent.getStringExtra("INITIAL_TRANSCRIPTION")
                
                Log.d(TAG, "Navigation params - fromAssistant: $fromAssistant, forceNavigation: $forceNavigation, enableVoiceMode: $enableVoiceMode, delayNavigation: $delayNavigation, initialTranscription: $initialTranscription")
                
                if (fromAssistant && forceNavigation) {
                    // If coming from assistant with force navigation, clear everything and go to chat
                    navigateWhenReady("chat/$chatId", clearBackStack = true) {
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
            val transcription = currentIntent?.getStringExtra("TRANSCRIPTION")
            
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
                            navigateWhenReady("chat/$newChatId", clearBackStack = true) {
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
                            }
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
    
    /**
     * Navigate when NavController is actually ready, replacing arbitrary delays
     */
    private fun navigateWhenReady(
        route: String, 
        clearBackStack: Boolean = false,
        onNavigated: () -> Unit = {}
    ) {
        lifecycleScope.launch {
            // Ensure we're in a valid lifecycle state
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    // Wait for NavController to be in a stable state
                    if (::navController.isInitialized && navController.currentDestination != null) {
                        Log.d(TAG, "🚨 NAVIGATE: About to navigate to $route (clearBackStack: $clearBackStack)")
                        Log.d(TAG, "🚨 NAVIGATE: Current destination before nav: ${navController.currentDestination?.route}")
                        
                        if (clearBackStack) {
                            navController.navigate(route) {
                                popUpTo(0) { inclusive = true } // Clear entire back stack
                                launchSingleTop = true
                            }
                        } else {
                            navController.navigate(route) {
                                launchSingleTop = true
                            }
                        }
                        
                        Log.d(TAG, "🚨 NAVIGATE: Navigation completed, current destination: ${navController.currentDestination?.route}")
                        // Execute callback after successful navigation
                        onNavigated()
                    } else {
                        Log.w(TAG, "NavController not ready for navigation to $route")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error navigating to $route", e)
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
        
        // 🕵️ DEBUG: Log intent state when resuming to understand chat ID issue
        Log.d(TAG, "🔍 RESUME DEBUG: Checking intent state after returning from background")
        val currentIntent = intent
        Log.d(TAG, "🔍 RESUME DEBUG: Intent = $currentIntent")
        if (currentIntent != null) {
            val createNewChat = currentIntent.getBooleanExtra("CREATE_NEW_CHAT_ON_START", false)
            val fromAssistant = currentIntent.getBooleanExtra("FROM_ASSISTANT", false) 
            val enableVoice = currentIntent.getBooleanExtra("ENABLE_VOICE_MODE", false)
            val navToChatId = currentIntent.getLongExtra("NAVIGATE_TO_CHAT_ID", -1L)
            Log.d(TAG, "🔍 RESUME DEBUG: CREATE_NEW_CHAT_ON_START = $createNewChat")
            Log.d(TAG, "🔍 RESUME DEBUG: FROM_ASSISTANT = $fromAssistant")
            Log.d(TAG, "🔍 RESUME DEBUG: ENABLE_VOICE_MODE = $enableVoice")
            Log.d(TAG, "🔍 RESUME DEBUG: NAVIGATE_TO_CHAT_ID = $navToChatId")
            
            // Also log current navigation state
            if (::navController.isInitialized) {
                val currentDestination = navController.currentDestination?.route
                Log.d(TAG, "🔍 RESUME DEBUG: Current nav destination = $currentDestination")
            }
        }
        
        // Re-check permission when activity resumes in case it was changed in settings
        permissionManager.checkMicrophonePermission()
        // If NavController is initialized, handle current intent again in case it was delivered while paused
        // and MainActivity wasn't recreated but onNewIntent wasn't called (e.g. returning to app)
        // This is a bit of an edge case, but ensures the navigation occurs if pending.
        if (::navController.isInitialized) {
             Log.d(TAG, "🔍 RESUME DEBUG: About to call handleIntentNavigation() - this might create new chat!")
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
        // Stop TTS when app is backgrounded to prevent speech continuing off-screen
        try {
            ttsManager.stop()
            Log.d("MainActivity", "TTS stopped due to app backgrounding")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping TTS on pause", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "Main Activity Destroyed")
    }
}