package com.smarttask.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smarttask.app.ui.screens.create.CreateReminderScreen
import com.smarttask.app.ui.screens.edit.EditReminderScreen
import com.smarttask.app.ui.screens.history.HistoryScreen
import com.smarttask.app.ui.screens.home.HomeScreen
import com.smarttask.app.ui.screens.permission.PermissionScreen
import com.smarttask.app.ui.screens.settings.SettingsScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Create : Screen("create")
    object Edit : Screen("edit/{reminderId}") {
        fun createRoute(reminderId: Long) = "edit/$reminderId"
    }
    object History : Screen("history")
    object Settings : Screen("settings")
    object Permission : Screen("permission")
}

@Composable
fun SmartTaskNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToCreate = { navController.navigate(Screen.Create.route) },
                onNavigateToEdit = { id -> navController.navigate(Screen.Edit.createRoute(id)) },
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Create.route) {
            CreateReminderScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            route = Screen.Edit.route,
            arguments = listOf(navArgument("reminderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val reminderId = backStackEntry.arguments?.getLong("reminderId") ?: 0L
            EditReminderScreen(
                reminderId = reminderId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.History.route) {
            HistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(Screen.Permission.route) {
            PermissionScreen(
                onAllGranted = { navController.popBackStack() }
            )
        }
    }
}
