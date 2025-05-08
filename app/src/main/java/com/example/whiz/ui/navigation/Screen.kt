package com.example.whiz.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object AssistantChat : Screen("assistant_chat")
    object Settings : Screen("settings")
} 