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
        
        // Test callback for capturing navigation-scoped ViewModels
        @Volatile
        var testViewModelCallback: ((com.example.whiz.ui.viewmodels.ChatViewModel) -> Unit)? = null
    }
    
    // No longer needed - using idempotent navigation instead of duplicate prevention

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
    
    @Inject
    lateinit var appLifecycleService: com.example.whiz.services.AppLifecycleService
    
    private lateinit var navController: NavHostController
    private val chatsListViewModel: ChatsListViewModel by viewModels()
    
    // Expose NavController for testing
    fun getNavController(): NavHostController? = if (::navController.isInitialized) navController else null
    
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
        
        // Check for all permissions at startup
        checkAllPermissions()
        
        setContent {
            WhizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    navController = rememberNavController()
                    
                    // Check if this is a voice launch
                    val isVoiceLaunch = intent?.getBooleanExtra("FROM_ASSISTANT", false) ?: false
                    
                    // Observe authentication state
                    val isAuthenticated by authRepository.isAuthenticated.collectAsState()
                    
                    // Observe which permission is needed next
                    val nextRequiredPermission by permissionManager.nextRequiredPermission.collectAsState()
                    
                    WhizNavHost(
                        navController = navController,
                        preloadManager = preloadManager,
                        permissionManager = permissionManager,
                        voiceManager = voiceManager,
                        hasPermission = permissionManager.microphonePermissionGranted.collectAsState().value,
                        onRequestPermission = { requestMicrophonePermission() },
                        isVoiceLaunch = isVoiceLaunch,
                        onChatViewModelReady = { vm ->
                            // Allow tests to capture the ViewModel
                            testViewModelCallback?.invoke(vm)
                        }
                    )
                    
                    // Only show permission dialogs if user is authenticated
                    // Login takes priority over all permissions
                    if (isAuthenticated) {
                        // Show appropriate permission dialog based on what's needed
                        when (nextRequiredPermission) {
                            PermissionManager.PermissionType.MICROPHONE -> {
                                com.example.whiz.ui.components.MicrophonePermissionDialog(
                                    onDismiss = { /* User dismissed the dialog */ },
                                    onRequestPermission = { requestMicrophonePermission() }
                                )
                            }
                            PermissionManager.PermissionType.ACCESSIBILITY -> {
                                com.example.whiz.ui.components.AccessibilityPermissionDialog(
                                    onDismiss = { /* User dismissed the dialog */ },
                                    onOpenSettings = { openAccessibilitySettings() }
                                )
                            }
                            null -> {
                                // All permissions granted, no dialog needed
                            }
                        }
                    }
                    
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
        setIntent(intent) // Set intent first so logDetailedIntentInfo can use it
        logDetailedIntentInfo(intent, "onNewIntent")
        
        // If navController is already initialized, LaunchedEffect(navController) won't trigger
        // So we need to manually call handleIntentNavigation
        if (::navController.isInitialized) {
            Log.d(TAG, "🎯 NavController already initialized - manually calling handleIntentNavigation")
            handleIntentNavigation(intent)
        } else {
            Log.d(TAG, "🎯 NavController not initialized - LaunchedEffect will handle navigation")
        }
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
            
            if (isLikelyVoiceLaunch) {
                Log.d(TAG, "🎤 VOICE LAUNCH DETECTED ($source) - adding assistant flags")
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
            val traceId = intent?.extras?.getLong("tracing_intent_id", 0L) ?: 0L
            val hasTraceId = traceId > 0
            
                    // SECONDARY DETECTION: Intent flags analysis  
        // Voice: 0x10000000 (FLAG_ACTIVITY_NEW_TASK only)
        // Manual: 0x10200000 (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Test issue: 0x10008000 (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP)
        val intentFlags = intent?.flags ?: 0
        val hasExactVoiceFlags = intentFlags == 0x10000000  // Exact match for voice launch flags
            
            // TERTIARY DETECTION: Bounds analysis
            // Manual launches have bounds (app icon position), voice launches don't
            val sourceBounds = intent?.sourceBounds
            val noBounds = sourceBounds == null
            
            Log.d(TAG, "Voice launch detection:")
            Log.d(TAG, "  Has trace ID: $hasTraceId (traceId: $traceId) ← PRIMARY")
            Log.d(TAG, "  Exact voice flags: $hasExactVoiceFlags (flags: ${String.format("0x%08X", intentFlags)}) ← SECONDARY")  
            Log.d(TAG, "  No bounds: $noBounds (bounds: $sourceBounds) ← TERTIARY")
            
            // Voice launch if PRIMARY indicator is present, OR if we have exact voice flags + no bounds
            // This handles both standard voice launches (with tracing_intent_id) and edge cases
            val isVoiceLaunch = hasTraceId || (hasExactVoiceFlags && noBounds)
            
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
        
        // No longer need complex trace ID logic - using idempotent navigation instead
        
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
            Log.d(TAG, "🚨 CREATE_NEW_CHAT_ON_START flag detected, navigating to new chat screen")
            
            // Check if we're already at the target destination (idempotent navigation)
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.AssistantChat.route) {
                Log.d(TAG, "🔄 Already at assistant_chat screen - navigation is idempotent, no action needed")
                
                // Still need to clear intent extras and set voice mode flags even if we don't navigate
                if (enableVoiceMode) {
                    Log.d(TAG, "Setting ENABLE_VOICE_MODE to true in savedStateHandle (idempotent)")
                    navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                }
                initialTranscription?.let {
                    Log.d(TAG, "Setting INITIAL_TRANSCRIPTION in savedStateHandle (idempotent): $it")
                    navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", it)
                }
                
                // Clear the extras to prevent future duplicate processing
                Log.d(TAG, "🧹 CLEARING intent extras after idempotent navigation")
                getIntent().removeExtra("CREATE_NEW_CHAT_ON_START")
                getIntent().removeExtra("FROM_ASSISTANT")
                getIntent().removeExtra("ENABLE_VOICE_MODE")
                getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                return
            }
            
            Log.d(TAG, "🚨 Voice launch navigation needed - navigating from $currentRoute to ${Screen.AssistantChat.route}")
            Log.d(TAG, "🚨 Note: Not pre-creating optimistic chat - ChatViewModel will create it when message is sent")
            
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
    
    private fun checkAllPermissions() {
        permissionManager.checkAllPermissions()
    }
    
    private fun openAccessibilitySettings() {
        // Try to open the specific accessibility service settings for our app
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        
        // On some devices, we can jump directly to our service settings
        // by adding the component name as an extra
        val componentName = android.content.ComponentName(
            packageName,
            "com.example.whiz.accessibility.WhizAccessibilityService"
        )
        
        // Try to highlight our specific service (this may not work on all devices)
        intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
        intent.putExtra(":settings:show_fragment_args", Bundle().apply {
            putString(":settings:fragment_args_key", componentName.flattenToString())
        })
        
        startActivity(intent)
        
        // Log instructions for the user
        Log.d(TAG, "Opening Accessibility Settings. User should look for 'WhizVoice' under 'Downloaded apps' or 'Installed services'")
    }
    
    private fun requestMicrophonePermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    
    override fun onResume() {
        super.onResume()
        Log.d("MainActivity", "Main Activity Resumed")
        // Notify that app is returning to foreground
        appLifecycleService.notifyAppForegrounded()
        Log.d("MainActivity", "Notified app foregrounded")
        
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
        
        // Re-check permissions when activity resumes in case they were changed in settings
        permissionManager.checkAllPermissions()
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
        // Notify that app is going to background
        appLifecycleService.notifyAppBackgrounded()
        Log.d("MainActivity", "Notified app backgrounded")
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