package com.ca.authframework.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ca.authframework.components.*
import com.ca.authframework.viewmodels.AuthenticationViewModel
import com.ca.authframework.viewmodels.EnrollmentViewModel

@Composable
fun MainNavHost(
    navController: NavHostController,
    enrollmentViewModel: EnrollmentViewModel,
    authenticationViewModel: AuthenticationViewModel,
    targetSamples: Int
) {
    NavHost(navController, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) { DashboardScreen() }
        composable(Destination.Settings.route) {
            AuthSettingsScreen(
                enrollmentViewModel = enrollmentViewModel,
                authenticationViewModel = authenticationViewModel,
                targetSamples = targetSamples
            )
        }
        composable(Destination.Report.route) { ReportScreen() }
    }
}
