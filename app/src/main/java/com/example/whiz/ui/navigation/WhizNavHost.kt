package com.example.whiz.ui.navigation

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
import com.example.whiz.ui.screens.ChatScreen
import com.example.whiz.ui.screens.ChatsListScreen
import com.example.whiz.ui.screens.LoginScreen
import com.example.whiz.ui.screens.SettingsScreen
import com.example.whiz.ui.viewmodels.AuthViewModel
import kotlinx.coroutines.delay

// Constants for animation
private const val ANIMATION_DURATION = 300
private const val PRELOAD_DELAY = 50L  // Short delay to start preloading

@Composable
fun WhizNavHost(
    navController: NavHostController,
    preloadManager: PreloadManager,
    hasPermission: Boolean = false,
    onRequestPermission: () -> Unit = {}
) {
    // Get authentication state
    val authViewModel: AuthViewModel = hiltViewModel()
    val isAuthenticated by authViewModel.isAuthenticated.collectAsState()

    // Check if we're coming from assistant
    val fromAssistant = navController.previousBackStackEntry?.arguments?.getBoolean("FROM_ASSISTANT") ?: false
    val chatId = navController.previousBackStackEntry?.arguments?.getLong("NAVIGATE_TO_CHAT_ID") ?: -1L

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

    NavHost(
        navController = navController,
        startDestination = if (isAuthenticated) {
            if (fromAssistant && chatId > 0) {
                "chat/$chatId"
            } else {
                Screen.Home.route
            }
        } else {
            Screen.Login.route
        }
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
                delay(PRELOAD_DELAY)
                preloadManager.preloadChatsList()
            }

            ChatsListScreen(
                onChatSelected = { chatId ->
                    // Preload chat data before navigating
                    preloadManager.preloadChat(chatId)
                    navController.navigate("chat/$chatId")
                },
                onNewChatClick = {
                    navController.navigate(Screen.AssistantChat.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                },
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission
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
        ) {
            val chatId = it.arguments?.getLong("chatId") ?: -1L

            // Preload chat messages if not already done
            LaunchedEffect(chatId) {
                delay(PRELOAD_DELAY)
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
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission,
                navController = navController
            )
        }

        composable(
            route = Screen.AssistantChat.route,
            enterTransition = { null },
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
        ) {
            ChatScreen(
                chatId = -1L, // -1 indicates a new chat
                onChatsListClick = {
                    // Preload chats list before navigating
                    preloadManager.preloadChatsList()
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                hasPermission = hasPermission,
                onRequestPermission = onRequestPermission,
                navController = navController
            )
        }
    }
} 