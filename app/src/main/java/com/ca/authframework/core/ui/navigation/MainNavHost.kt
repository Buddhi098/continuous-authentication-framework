package com.ca.authframework.core.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ca.authframework.features.authentication.AuthenticationViewModel
import com.ca.authframework.features.dashboard.DashboardScreen
import com.ca.authframework.features.enrollment.EnrollmentViewModel
import com.ca.authframework.features.evaluation.EvaluationViewModel
import com.ca.authframework.features.report.ReportScreen
import com.ca.authframework.features.settings.AuthSettingsScreen

@Composable
fun MainNavHost(
        navController: NavHostController,
        dashboardViewModel: com.ca.authframework.features.dashboard.DashboardViewModel,
        enrollmentViewModel: EnrollmentViewModel,
        authenticationViewModel: AuthenticationViewModel,
        evaluationViewModel: EvaluationViewModel,
        targetSamples: Int,
        tdtLockEnabled: Boolean = false,
        onTdtLockToggle: (Boolean) -> Unit = {}
) {
    NavHost(navController, startDestination = Destination.Home.route) {
        composable(Destination.Home.route) { DashboardScreen(viewModel = dashboardViewModel) }
        composable(Destination.Settings.route) {
            AuthSettingsScreen(
                    enrollmentViewModel = enrollmentViewModel,
                    authenticationViewModel = authenticationViewModel,
                    evaluationViewModel = evaluationViewModel,
                    targetSamples = targetSamples,
                    tdtLockEnabled = tdtLockEnabled,
                    onTdtLockToggle = onTdtLockToggle
            )
        }
        composable(Destination.Report.route) { ReportScreen() }
    }
}
