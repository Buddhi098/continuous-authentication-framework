package com.ca.authframework

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.ca.authframework.navigation.MainNavHost
import com.ca.authframework.ui.AppTopBar
import com.ca.authframework.ui.BottomNavigationBar
import com.ca.authframework.ui.theme.AuthframeworkTheme
import com.ca.authframework.viewmodels.AuthenticationViewModel
import com.ca.authframework.viewmodels.EnrollmentViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.TouchEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object ContinuousAuthManager {
    lateinit var continuousAuth: ContinuousAuth
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CAFramework"
        private const val TARGET_SAMPLES = 1200
    }
    /* ---------------------------------------------------------------------- */
    /*                        TOUCH EVENT STREAM                               */
    /* ---------------------------------------------------------------------- */
    private val touchEventFlow = MutableSharedFlow<TouchEventData>(
        replay = 0,
        extraBufferCapacity = 256
    )
    private lateinit var enrollmentViewModel: EnrollmentViewModel
    private lateinit var authenticationViewModel: AuthenticationViewModel


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        /* ------------------------------------------------------------------ */
        /*                   INITIALIZE CONTINUOUS AUTH                        */
        /* ------------------------------------------------------------------ */
        ContinuousAuthManager.continuousAuth = ContinuousAuth(
            context = applicationContext,
            touchEventFlow = touchEventFlow.asSharedFlow(),
            enrollmentSamples = TARGET_SAMPLES,
            shouldLogFeatureVector = true,
            enableLog = true
        )

        enrollmentViewModel = EnrollmentViewModel(applicationContext, ContinuousAuthManager.continuousAuth)
        authenticationViewModel = AuthenticationViewModel(ContinuousAuthManager.continuousAuth)

//        if (authenticationViewModel.isCheckpointExists.value) {
//            authenticationViewModel.startAuthentication()
//        }

        if (enrollmentViewModel.isPaused.value) {
            authenticationViewModel.stopAuthentication()
            enrollmentViewModel.clearEnrollmentFiles()
            enrollmentViewModel.resumeCollection()
        }

        setContent {
            AuthframeworkTheme {
                val navController = rememberNavController()
                val lastAuthResult = authenticationViewModel.lastAuthResult
                val isAuthRunning = authenticationViewModel.authenticationRunning
                val evaluationRunning = authenticationViewModel.evaluationRunning

                val currentAuthStatus =
                    if ((evaluationRunning || isAuthRunning) && lastAuthResult != null) {
                        if (lastAuthResult.isAuthenticated) "Authenticated" else "Rejected"
                    } else "Unknown"

                val currentScore =
                    if ((evaluationRunning || isAuthRunning) && lastAuthResult != null) {
                        "%.3f".format(lastAuthResult.score)
                    } else "N/A"

                val currentAuthPercentage =
                    if ((evaluationRunning || isAuthRunning) && lastAuthResult?.authPercentage != null) {
                        "%.2f%%".format(lastAuthResult.authPercentage)
                    } else "N/A"

                Scaffold(
                    topBar = { AppTopBar(status = currentAuthStatus, score = currentScore , currentAuthPercentage = currentAuthPercentage) },
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

    /* ---------------------------------------------------------------------- */
    /*                  GLOBAL TOUCH INTERCEPTION                              */
    /* ---------------------------------------------------------------------- */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {

        val touchData = TouchEventData(
            action = event.actionMasked,
            timestamp = event.eventTime,
            x = event.x,
            y = event.y,
            pressure = event.pressure,
            size = event.size,
            orientation = event.orientation,
            touchMajor = event.touchMajor,
            touchMinor = event.touchMinor,
            pointerCount = event.pointerCount
        )

        touchEventFlow.tryEmit(touchData)
        return super.dispatchTouchEvent(event)
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
