package com.example.whiz.ui.navigation

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object Settings : Screen("settings") {
        const val routeWithArgs = "settings?focusSection={focusSection}"
        const val focusSectionArg = "focusSection"

        fun createRoute(focusSection: String? = null): String {
            return if (focusSection != null) {
                "settings?$focusSectionArg=$focusSection"
            } else {
                "settings" // Base route without optional arg
            }
        }
    }
} 