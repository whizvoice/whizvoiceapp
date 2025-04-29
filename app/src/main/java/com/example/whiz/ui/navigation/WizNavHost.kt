package com.example.whiz.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.whiz.ui.screens.SettingsScreen
import kotlinx.coroutines.delay

sealed class Screen(val route: String) {
    object ChatsList : Screen("chats_list")
    object Chat : Screen("chat/{chatId}") {
        fun createRoute(chatId: Long) = "chat/$chatId"
    }
    object NewChat : Screen("chat/new")
    object Settings : Screen("settings")
}

// Constants for animation
private const val ANIMATION_DURATION = 300
private const val PRELOAD_DELAY = 50L  // Short delay to start preloading

@Composable
fun WizNavHost(
    navController: NavHostController,
    preloadManager: PreloadManager
) {
    // Custom extension to preload data before navigating
    fun NavHostController.preloadAndNavigate(route: String) {
        when {
            route.startsWith("chat/") && route != "chat/new" -> {
                val chatId = route.substringAfter("chat/").toLong()
                preloadManager.preloadChat(chatId)
            }
            route == Screen.ChatsList.route -> {
                preloadManager.preloadChatsList()
            }
        }
        navigate(route)
    }

    NavHost(
        navController = navController,
        startDestination = Screen.NewChat.route
    ) {
        composable(
            route = Screen.ChatsList.route,
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
                    navController.navigate(Screen.Chat.createRoute(chatId))
                },
                onNewChatClick = {
                    navController.navigate(Screen.NewChat.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType }),
            enterTransition = {
                when (initialState.destination.route) {
                    Screen.ChatsList.route -> {
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
                    Screen.ChatsList.route -> {
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
                    navController.navigate(Screen.ChatsList.route) {
                        popUpTo(Screen.ChatsList.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.NewChat.route,
            enterTransition = { null },
            exitTransition = {
                when (targetState.destination.route) {
                    Screen.ChatsList.route -> {
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
                    navController.navigate(Screen.ChatsList.route) {
                        popUpTo(Screen.ChatsList.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Settings.route,
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
            SettingsScreen(
                onBackClick = {
                    navController.navigateUp()
                }
            )
        }
    }
}