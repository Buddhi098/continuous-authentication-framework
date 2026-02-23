package com.ca.authframework.core.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String, val title: String, val icon: ImageVector) {
    object Home : Destination("home", "Dashboard", Icons.Default.Home)
    object Settings : Destination("auth_settings", "Auth Settings", Icons.Default.Settings)
    object EvalHistory :
            Destination("eval_history", "Eval History", Icons.AutoMirrored.Filled.List)
}
