package com.example.whiz

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Field
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.example.whiz.BuildConfig
import com.example.whiz.data.api.ApiService
import com.example.whiz.services.BubbleOverlayService
import com.example.whiz.services.ListeningMode
import org.json.JSONObject
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"

        // Test callback for capturing navigation-scoped ViewModels
        @Volatile
        var testViewModelCallback: ((com.example.whiz.ui.viewmodels.ChatViewModel) -> Unit)? = null

        // Callback for self-close tool to finish the activity and remove from recents
        @Volatile
        var finishAndRemoveTaskCallback: (() -> Unit)? = null
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

    @Inject
    lateinit var apiService: ApiService

    // ViewModel for creating new chats on assistant relaunch
    private val chatsListViewModel: ChatsListViewModel by viewModels()

    private lateinit var navController: NavHostController
    private var testTranscriptionReceiver: BroadcastReceiver? = null
    private var closeAppReceiver: BroadcastReceiver? = null

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

        // Upload any pending crash report from previous session
        uploadPendingCrashReport()

        // Setup close app receiver BEFORE setContent (needs to work even when activity is stopped)
        setupCloseAppReceiver()

        // Set up static callback for self-close tool (more reliable than broadcast)
        finishAndRemoveTaskCallback = {
            Log.d(TAG, "finishAndRemoveTaskCallback invoked - calling finishAndRemoveTask()")
            finishAndRemoveTask()
        }

        setContent {
            WhizTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    navController = rememberNavController()

                    // Check if this is a voice launch
                    val isVoiceLaunch = intent?.getBooleanExtra("FROM_ASSISTANT", false) ?: false

                    // Get AuthViewModel to observe authentication state
                    val authViewModel: com.example.whiz.ui.viewmodels.AuthViewModel = hiltViewModel()
                    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

                    // Setup test broadcast receiver for debug builds
                    if (BuildConfig.DEBUG) {
                        setupTestTranscriptionReceiver()
                    }

                    // Ensure stable reference to permissionManager within Composable context
                    val stablePermissionManager = remember { permissionManager }

                    // Observe which step is needed next
                    // Collect from the stable reference to ensure proper recomposition
                    val nextRequiredStep by stablePermissionManager.nextRequiredStep.collectAsState()

                    // Add diagnostic logging for recomposition
                    Log.d("MainActivity", "🔄 RECOMPOSITION: nextRequiredStep=$nextRequiredStep, " +
                        "isResumed=${lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}, " +
                        "thread=${Thread.currentThread().name}, " +
                        "threadId=${Thread.currentThread().id}, " +
                        "timestamp=${System.currentTimeMillis()}")

                    // Debug: Force recomposition when state changes
                    LaunchedEffect(nextRequiredStep) {
                        Log.d("MainActivity", "LaunchedEffect triggered: nextRequiredStep changed to $nextRequiredStep, " +
                            "lifecycle=${lifecycle.currentState}, " +
                            "isMainThread=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()}")
                    }
                    
                    // Handle lifecycle events within Compose context to ensure proper recomposition
                    DisposableEffect(Unit) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                                val observerThread = Thread.currentThread().name
                                val observerId = Thread.currentThread().id
                                Log.d("MainActivity", "Lifecycle ON_RESUME detected in Compose on thread: $observerThread (id=$observerId)")
                                // Recheck permissions when returning from Settings
                                // This will update nextRequiredStep to show the correct dialog or none
                                // Ensure this runs on main thread for proper Compose recomposition
                                lifecycleScope.launch {
                                    // Small delay to ensure Compose UI is ready after activity restart
                                    kotlinx.coroutines.delay(100)
                                    Log.d("MainActivity", "checkAllPermissions called on thread: ${Thread.currentThread().name} (id=${Thread.currentThread().id})")
                                    stablePermissionManager.checkAllPermissions()
                                }
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose {
                            lifecycle.removeObserver(observer)
                        }
                    }
                    
                    WhizNavHost(
                        navController = navController,
                        preloadManager = preloadManager,
                        permissionManager = stablePermissionManager,
                        voiceManager = voiceManager,
                        hasPermission = stablePermissionManager.microphonePermissionGranted.collectAsState().value,
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
                        // Log thread info when collecting StateFlow
                        val threadName = Thread.currentThread().name
                        val threadId = Thread.currentThread().id
                        val currentTime = System.currentTimeMillis()
                        val lifecycleState = lifecycle.currentState
                        Log.d("MainActivity", "📊 DIALOG RENDER: thread=$threadName (id=$threadId), " +
                            "nextRequiredStep=$nextRequiredStep, " +
                            "lifecycle=$lifecycleState, " +
                            "time=$currentTime")

                        // Show appropriate dialog based on what's needed
                        // Removed key() wrapper - the when expression will recompose naturally when nextRequiredStep changes
                        Log.d("MainActivity", "🎨 RENDERING DIALOG: nextRequiredStep=$nextRequiredStep, " +
                            "isActivityResumed=${lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)}")
                        when (nextRequiredStep) {
                            PermissionManager.RequiredStep.MICROPHONE -> {
                                com.example.whiz.ui.components.MicrophonePermissionDialog(
                                    onDismiss = { /* User dismissed the dialog */ },
                                    onRequestPermission = { requestMicrophonePermission() }
                                )
                            }
                            PermissionManager.RequiredStep.ACCESSIBILITY -> {
                                com.example.whiz.ui.components.AccessibilityPermissionDialog(
                                    onDismiss = { /* User dismissed the dialog */ },
                                    onOpenSettings = { openAccessibilitySettings() }
                                )
                            }
                            PermissionManager.RequiredStep.OVERLAY -> {
                                com.example.whiz.ui.components.OverlayPermissionDialog(
                                    onDismiss = { /* User dismissed the dialog */ },
                                    onRequestPermission = {
                                        // Open system settings for overlay permission
                                        // Note: Android 16+ strips package name from URI due to security restrictions
                                        // Users will need to manually find the app in the list
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

                                        try {
                                            startActivity(intent)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Failed to open overlay settings", e)
                                        }
                                    }
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
                // Set transitioning flag to prevent old ChatViewModel from disabling continuous listening
                com.example.whiz.ui.viewmodels.ChatViewModel.isTransitioning = true
                Log.d(TAG, "🎤 Updated activity intent with voice launch flags, isTransitioning=true")
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
        val enableVoiceMode = currentIntent?.getBooleanExtra("ENABLE_VOICE_MODE", false) ?: false
        val initialTranscription = currentIntent?.getStringExtra("INITIAL_TRANSCRIPTION")
        
        if (createNewChatOnStart && ::navController.isInitialized) {
            Log.d(TAG, "🚨 CREATE_NEW_CHAT_ON_START flag detected, navigating to new chat screen")
            
            // Check if we're already at the target destination
            val currentRoute = navController.currentDestination?.route
            if (currentRoute == Screen.AssistantChat.route || currentRoute?.startsWith("chat/") == true) {
                if (fromAssistant) {
                    // FROM_ASSISTANT means user long-pressed again while app is open
                    // Create a new chat and navigate to it
                    Log.d(TAG, "🔄 Already at chat screen but FROM_ASSISTANT - creating new chat")
                    lifecycleScope.launch {
                        val newChatId = chatsListViewModel.createNewChatOptimistic("Assistant Chat")
                        Log.d(TAG, "🔄 Created new optimistic chat with ID: $newChatId")
                        if (newChatId != -1L) {
                            navigateWhenReady("chat/$newChatId", clearBackStack = true) {
                                if (enableVoiceMode) {
                                    navController.currentBackStackEntry?.savedStateHandle?.set("ENABLE_VOICE_MODE", true)
                                }
                                initialTranscription?.let {
                                    navController.currentBackStackEntry?.savedStateHandle?.set("INITIAL_TRANSCRIPTION", it)
                                }
                                // Clear the extras
                                getIntent().removeExtra("CREATE_NEW_CHAT_ON_START")
                                getIntent().removeExtra("FROM_ASSISTANT")
                                getIntent().removeExtra("ENABLE_VOICE_MODE")
                                getIntent().removeExtra("INITIAL_TRANSCRIPTION")
                            }
                        }
                    }
                    return
                } else {
                    // Not from assistant - truly idempotent, no action needed
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

        // Stop bubble overlay when MainActivity comes to foreground
        // This is more reliable than listening to app lifecycle events, which can fire
        // incorrectly when transitioning between apps (e.g., opening WhatsApp)
        if (BubbleOverlayService.isActive) {
            Log.d("MainActivity", "Stopping bubble overlay service - MainActivity resumed")
            BubbleOverlayService.stop(this)
        }

        // Removed appLifecycleService.notifyAppForegrounded() to fix bubble service issue
        // ProcessLifecycleOwner in WhizApplication handles foreground detection more reliably
        // This prevents false foreground events during app-to-app transitions
        
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
        
        // Permission checking is now handled by DisposableEffect in the Compose UI
        // This ensures proper recomposition when returning from Settings
        Log.d("MainActivity", "onResume called - permission check will be handled by Compose")
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
        // Note: App lifecycle is now automatically tracked by ProcessLifecycleOwner in AppLifecycleService

        // Check if bubble overlay is active and in TTS mode before stopping TTS
        val bubbleActive = BubbleOverlayService.isActive
        val bubbleMode = BubbleOverlayService.bubbleListeningMode
        val shouldKeepTTS = bubbleActive && bubbleMode == ListeningMode.TTS_WITH_LISTENING

        // Stop microphone immediately if bubble isn't active
        // This prevents the mic from continuing to listen while the app is backgrounding
        if (!bubbleActive && voiceManager.isListening.value) {
            Log.d("MainActivity", "Stopping microphone immediately - app pausing without bubble")
            voiceManager.stopListening()
        }

        // Stop TTS when app is backgrounded, UNLESS bubble is in Speaking Mode
        if (!shouldKeepTTS) {
            try {
                ttsManager.stop()
                Log.d("MainActivity", "TTS stopped due to app backgrounding (bubble not in TTS mode)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error stopping TTS on pause", e)
            }
        } else {
            Log.d("MainActivity", "Keeping TTS active - bubble overlay is in Speaking Mode")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "Main Activity Destroyed")

        // Clear the static callback
        finishAndRemoveTaskCallback = null

        // Unregister test broadcast receiver if it was registered
        if (BuildConfig.DEBUG && testTranscriptionReceiver != null) {
            try {
                unregisterReceiver(testTranscriptionReceiver)
                Log.d(TAG, "Test transcription receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering test receiver", e)
            }
        }

        // Unregister close app receiver
        if (closeAppReceiver != null) {
            try {
                unregisterReceiver(closeAppReceiver)
                Log.d(TAG, "Close app receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering close app receiver", e)
            }
        }
    }

    private fun setupTestTranscriptionReceiver() {
        if (!BuildConfig.DEBUG) {
            Log.d(TAG, "Not setting up test transcription receiver - not a debug build")
            return
        }

        Log.d(TAG, "Setting up test transcription receiver for debug build - BuildConfig.DEBUG = ${BuildConfig.DEBUG}")

        // Create the broadcast receiver
        testTranscriptionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d(TAG, "BroadcastReceiver.onReceive called with action: ${intent?.action}")

                if (intent?.action != "com.example.whiz.TEST_TRANSCRIPTION") {
                    Log.d(TAG, "Ignoring non-TEST_TRANSCRIPTION action: ${intent?.action}")
                    return
                }

                val text = intent.getStringExtra("text") ?: ""
                val fromVoice = intent.getBooleanExtra("fromVoice", true)
                val autoSend = intent.getBooleanExtra("autoSend", true)

                Log.d(TAG, "Test transcription received: text='$text', fromVoice=$fromVoice, autoSend=$autoSend")

                // First try to use VoiceManager (works in bubble mode)
                val voiceManager = com.example.whiz.ui.viewmodels.VoiceManager.instance

                if (voiceManager != null && fromVoice) {
                    Log.d(TAG, "Using VoiceManager to simulate voice transcription")

                    try {
                        // Use reflection to get the transcriptionCallback field
                        val transcriptionCallbackField: Field = voiceManager.javaClass.getDeclaredField("transcriptionCallback")
                        transcriptionCallbackField.isAccessible = true
                        val callback = transcriptionCallbackField.get(voiceManager) as? ((String) -> Unit)

                        if (callback != null) {
                            // Simulate transcription through VoiceManager's callback
                            CoroutineScope(Dispatchers.Main).launch {
                                Log.d(TAG, "Invoking transcription callback with: '$text'")
                                callback.invoke(text)
                                Log.d(TAG, "Test transcription processed through VoiceManager")
                            }
                            return  // Early return when successfully using VoiceManager
                        } else {
                            Log.w(TAG, "VoiceManager transcription callback is not set")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accessing VoiceManager transcription callback", e)
                    }
                }

                // Fallback to ChatViewModel approach (debug only, no-op in release)
                com.example.whiz.test.processTestTranscriptionFallback(
                    text, fromVoice, autoSend, ::processTestTranscription
                )
            }
        }

        // Register the receiver - use EXPORTED for testing from ADB
        val filter = IntentFilter("com.example.whiz.TEST_TRANSCRIPTION")
        Log.d(TAG, "Creating IntentFilter for action: com.example.whiz.TEST_TRANSCRIPTION")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Log.d(TAG, "Registering receiver with RECEIVER_EXPORTED flag (API 33+)")
                // Use EXPORTED flag to allow ADB broadcasts to reach the receiver
                registerReceiver(testTranscriptionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                Log.d(TAG, "Registering receiver without RECEIVER_EXPORTED flag (API < 33)")
                registerReceiver(testTranscriptionReceiver, filter)
            }
            Log.d(TAG, "Test transcription receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register test transcription receiver", e)
        }
    }

    private fun setupCloseAppReceiver() {
        Log.d(TAG, "Setting up close app receiver")

        closeAppReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.whiz.CLOSE_APP") {
                    Log.d(TAG, "Close app broadcast received - calling finishAndRemoveTask()")
                    finishAndRemoveTask()
                }
            }
        }

        val filter = IntentFilter("com.example.whiz.CLOSE_APP")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(closeAppReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(closeAppReceiver, filter)
            }
            Log.d(TAG, "Close app receiver registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register close app receiver", e)
        }
    }

    private fun processTestTranscription(
        viewModel: com.example.whiz.ui.viewmodels.ChatViewModel,
        text: String,
        fromVoice: Boolean,
        autoSend: Boolean
    ) {
        try {
            // Update the input text with the transcription
            viewModel.updateInputText(text, fromVoice = fromVoice)

            // If autoSend is true, automatically send the message
            if (autoSend && text.isNotBlank()) {
                viewModel.sendUserInput(text)
                Log.d(TAG, "Test transcription sent: '$text'")
            } else {
                Log.d(TAG, "Test transcription set to input field: '$text'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing test transcription", e)
        }
    }

    /**
     * Upload any pending crash report from a previous app crash.
     * Called on app startup to send crash data to Supabase.
     */
    private fun uploadPendingCrashReport() {
        val crashFile = File(filesDir, "pending_crash.json")
        if (!crashFile.exists()) return

        Log.i(TAG, "Found pending crash report, uploading...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val crashData = JSONObject(crashFile.readText())
                val stackTrace = crashData.optString("stack_trace", "Unknown")
                val firstLine = stackTrace.lineSequence().firstOrNull() ?: "Unknown crash"

                val request = ApiService.UiDumpCreate(
                    dumpReason = "app_crash",
                    errorMessage = firstLine,
                    uiHierarchy = null,
                    packageName = packageName,
                    deviceModel = crashData.optString("device_model"),
                    deviceManufacturer = crashData.optString("device_manufacturer"),
                    androidVersion = crashData.optString("android_version"),
                    screenWidth = null,
                    screenHeight = null,
                    appVersion = crashData.optString("app_version"),
                    conversationId = null,
                    recentActions = null,
                    screenAgentContext = mapOf(
                        "thread_name" to crashData.optString("thread_name"),
                        "stack_trace" to stackTrace,
                        "crash_timestamp" to crashData.optLong("timestamp")
                    )
                )

                apiService.uploadUiDump(request)
                crashFile.delete()  // Only delete after successful upload
                Log.i(TAG, "Crash report uploaded successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to upload crash report (will retry on next launch)", e)
                // Keep file for retry on next launch
            }
        }
    }
}