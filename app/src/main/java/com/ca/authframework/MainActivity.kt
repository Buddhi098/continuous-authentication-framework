package com.ca.authframework

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.compose.rememberNavController
import com.ca.authframework.navigation.MainNavHost
import com.ca.authframework.ui.AppTopBar
import com.ca.authframework.ui.BottomNavigationBar
import com.ca.authframework.ui.theme.AuthframeworkTheme
import com.ca.authframework.viewmodels.AuthenticationViewModel
import com.ca.authframework.viewmodels.EnrollmentViewModel
import com.ca.continuousauth.ContinuousAuth

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CAFramework"
        private const val TARGET_SAMPLES = 100
    }

    private val continuousAuth by lazy {
        Log.d(TAG, "Initializing ContinuousAuth")
        ContinuousAuth(
            context = this,
            enrollmentSamples = TARGET_SAMPLES,
            shouldLogFeatureVector = true,
            enableLog = true
        ).also { Log.d(TAG, "ContinuousAuth initialized.") }
    }

    private val enrollmentViewModel by lazy { EnrollmentViewModel(this, continuousAuth) }
    private val authenticationViewModel by lazy { AuthenticationViewModel(continuousAuth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enrollmentViewModel.resumeCollection()
        authenticationViewModel.startAuthentication()

        setContent {
            AuthframeworkTheme {
                val navController = rememberNavController()
                val lastAuthResult = authenticationViewModel.lastAuthResult
                val isAuthRunning = authenticationViewModel.authenticationRunning

                // Only show result if authentication has run
                val currentAuthStatus = if (isAuthRunning && lastAuthResult != null) {
                    if (lastAuthResult.isAuthenticated) "Authenticated" else "Rejected"
                } else {
                    "Unknown"
                }

                val currentScore = if (isAuthRunning && lastAuthResult != null) {
                    "%.3f".format(lastAuthResult.score)
                } else {
                    "N/A"
                }

                androidx.compose.material3.Scaffold(
                    topBar = { AppTopBar(status = currentAuthStatus, score = currentScore) },
                    bottomBar = { BottomNavigationBar(navController) }
                ) { innerPadding ->
                    Surface(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainNavHost(
                            navController = navController,
                            enrollmentViewModel = enrollmentViewModel,
                            authenticationViewModel = authenticationViewModel,
                            targetSamples = TARGET_SAMPLES
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        enrollmentViewModel.pauseCollection()
        authenticationViewModel.stopAuthentication()
    }

    override fun onDestroy() {
        super.onDestroy()
        enrollmentViewModel.pauseCollection()
        authenticationViewModel.stopAuthentication()
    }
}
