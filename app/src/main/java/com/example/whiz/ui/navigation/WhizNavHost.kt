package com.example.whiz.ui.navigation

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.whiz.data.PreloadManager
import com.example.whiz.permissions.PermissionManager
import com.example.whiz.ui.screens.ChatScreen
import com.example.whiz.ui.screens.ChatsListScreen
import com.example.whiz.ui.screens.LoginScreen
import com.example.whiz.ui.screens.SettingsScreen
import com.example.whiz.ui.viewmodels.AuthViewModel
import com.example.whiz.ui.viewmodels.VoiceManager
import com.example.whiz.ui.viewmodels.registerForTestTranscription

import kotlinx.coroutines.delay

// Constants for animation
private const val ANIMATION_DURATION = 300

@Composable
fun WhizNavHost(
    navController: NavHostController,
    preloadManager: PreloadManager,
    permissionManager: PermissionManager,
    voiceManager: VoiceManager,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {},
    isVoiceLaunch: Boolean = false,
    isDeveloperMode: Boolean = false,
    onBugReportClick: () -> Unit = {},
    onChatViewModelReady: ((com.example.whiz.ui.viewmodels.ChatViewModel) -> Unit)? = null // Test hook
) {
    // Get authentication state
    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    // Check if we're coming from assistant or voice launch
    val currentBackStackEntry = navController.currentBackStackEntry
    val previousFromAssistant = navController.previousBackStackEntry?.arguments?.getBoolean("FROM_ASSISTANT") ?: false
    val currentVoiceMode = currentBackStackEntry?.savedStateHandle?.get<Boolean>("ENABLE_VOICE_MODE") == true
    val currentFromAssistant = currentBackStackEntry?.arguments?.getBoolean("FROM_ASSISTANT") == true
    val fromAssistant = isVoiceLaunch || previousFromAssistant || currentVoiceMode || currentFromAssistant
    val chatId = navController.previousBackStackEntry?.arguments?.getLong("NAVIGATE_TO_CHAT_ID") ?: -1L
    
    // Debug logging for voice launch detection
    Log.d("WhizNavHost", "🔍 Voice launch detection:")
    Log.d("WhizNavHost", "  isVoiceLaunch (parameter): $isVoiceLaunch")
    Log.d("WhizNavHost", "  previousFromAssistant: $previousFromAssistant")
    Log.d("WhizNavHost", "  currentVoiceMode: $currentVoiceMode") 
    Log.d("WhizNavHost", "  currentFromAssistant: $currentFromAssistant")
    Log.d("WhizNavHost", "  final fromAssistant: $fromAssistant")
    Log.d("WhizNavHost", "  chatId: $chatId")

    // Monitor authentication state and navigate to login if user becomes unauthenticated
    LaunchedEffect(isAuthenticated) {
        Log.d("WhizNavHost", "🔐 Authentication state changed: isAuthenticated = $isAuthenticated")
        val currentRoute = navController.currentDestination?.route
        Log.d("WhizNavHost", "🔐 Current route: $currentRoute, fromAssistant: $fromAssistant")
        
        if (!isAuthenticated) {
            // Only navigate to login if we're not already there
            if (currentRoute != Screen.Login.route) {
                Log.d("WhizNavHost", "🔐 Authentication lost, navigating to login screen (from $currentRoute)")
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true } // Clear entire back stack
                    launchSingleTop = true
                }
            } else {
                Log.d("WhizNavHost", "🔐 Already on login screen, no navigation needed")
            }
        } else {
            Log.d("WhizNavHost", "🔐 User is authenticated, staying on current screen: $currentRoute")
        }
    }

    // Custom extension to preload data before navigating
    fun NavHostController.preloadAndNavigate(route: String) {
        when {
            route.startsWith("chat/") && route != "chat/new" -> {
                val chatId = route.substringAfter("chat/").toLong()
                preloadManager.preloadChat(chatId)
            }
            route == "home" -> {
                preloadManager.preloadChatsList()
            }
        }
        navigate(route)
    }

    // Determine start destination with detailed logging
    val startDestination = if (isAuthenticated) {
        Log.d("WhizNavHost", "🎯 User is authenticated, determining start destination:")
        if (fromAssistant && chatId > 0) {
            Log.d("WhizNavHost", "  ✅ Voice launch with specific chat ID: chat/$chatId")
            "chat/$chatId"
        } else if (fromAssistant) {
            Log.d("WhizNavHost", "  🎤 Voice launch to new chat - using chat/-1")
            "chat/-1"
        } else {
            Log.d("WhizNavHost", "  🏠 Regular launch - using home screen")
            Screen.Home.route
        }
    } else {
        Log.d("WhizNavHost", "🔐 User not authenticated - using login screen")
        Screen.Login.route
    }
    
    Log.d("WhizNavHost", "🎯 Final startDestination: $startDestination")

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Login Screen
        composable(
            route = Screen.Login.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None }
        ) {
            LoginScreen(navController = navController)
        }
        
        // Home Screen (Chats List)
        composable(
            route = Screen.Home.route,
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            }
        ) {
            // Preload any relevant data
            LaunchedEffect(Unit) {
                delay(50L)
                preloadManager.preloadChatsList()
            }

            ChatsListScreen(
                onChatSelected = { chatId ->
                    // Preload chat data before navigating
                    preloadManager.preloadChat(chatId)
                    navController.navigate("chat/$chatId")
                },
                onNewChatClick = {
                    navController.navigate("chat/-1")
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission,
                isDeveloperMode = isDeveloperMode,
                onBugReportClick = onBugReportClick
            )
        }

        // Settings Screen
        composable(
            route = Screen.Settings.routeWithArgs,
            arguments = listOf(navArgument(Screen.Settings.focusSectionArg) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(ANIMATION_DURATION)
                )
            }
        ) { backStackEntry ->
            val focusSection = backStackEntry.arguments?.getString(Screen.Settings.focusSectionArg)
            SettingsScreen(
                navController = navController,
                focusSection = focusSection
            )
        }

        composable(
            route = "chat/{chatId}",
            arguments = listOf(navArgument("chatId") { type = NavType.LongType }),
            enterTransition = {
                when (initialState.destination.route) {
                    Screen.Home.route -> {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(ANIMATION_DURATION)
                        )
                    }
                    else -> null
                }
            },
            exitTransition = {
                when (targetState.destination.route) {
                    Screen.Home.route -> {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(ANIMATION_DURATION)
                        )
                    }
                    else -> null
                }
            },
            popEnterTransition = { null },
            popExitTransition = { null }
        ) { backStackEntry ->
            val chatId = backStackEntry.arguments?.getLong("chatId") ?: -1L

            // For voice launch, enable voice mode
            if (isVoiceLaunch) {
                backStackEntry.savedStateHandle["ENABLE_VOICE_MODE"] = true
            }

            // Preload chat messages if not already done
            LaunchedEffect(chatId) {
                delay(50L)
                preloadManager.preloadChat(chatId)
            }

            ChatScreen(
                chatId = chatId,
                onChatsListClick = {
                    // Preload chats list before navigating
                    preloadManager.preloadChatsList()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                permissionManager = permissionManager,
                voiceManager = voiceManager,
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission,
                isDeveloperMode = isDeveloperMode,
                onBugReportClick = onBugReportClick,
                navController = navController,
                onViewModelReady = { viewModel ->
                    // Call the test callback if provided
                    onChatViewModelReady?.invoke(viewModel)

                    // Register for test transcription (no-op in release builds)
                    viewModel.registerForTestTranscription()
                }
            )
        }

    }
} 