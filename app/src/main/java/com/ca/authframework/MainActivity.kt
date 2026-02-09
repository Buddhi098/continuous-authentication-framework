package com.ca.authframework

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.ca.authframework.core.ui.components.AppLockScreen
import com.ca.authframework.core.ui.layout.AppTopBar
import com.ca.authframework.core.ui.layout.BottomNavigationBar
import com.ca.authframework.core.ui.navigation.MainNavHost
import com.ca.authframework.core.ui.theme.AuthframeworkTheme
import com.ca.authframework.features.authentication.AuthenticationViewModel
import com.ca.authframework.features.dashboard.DashboardViewModel
import com.ca.authframework.features.enrollment.EnrollmentViewModel
import com.ca.authframework.features.evaluation.EvaluationViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.TouchEventData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/* ---------------------------------------------------------------------- */
/*                      CONTINUOUS AUTH MANAGER                            */
/* ---------------------------------------------------------------------- */
object ContinuousAuthManager {
    lateinit var continuousAuth: ContinuousAuth
}
/* ---------------------------------------------------------------------- */
/*                              MAIN ACTIVITY                              */
/* ---------------------------------------------------------------------- */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TARGET_SAMPLES = 200

        // 🔐 TDT thresholds
        private const val TDT_LOCK_THRESHOLD = 0.5f
        private const val TDT_UNLOCK_THRESHOLD = 0.6f
    }

    private val isEnableLock = mutableStateOf(false)

    private val touchEventFlow =
            MutableSharedFlow<TouchEventData>(replay = 0, extraBufferCapacity = 256)

    private lateinit var enrollmentViewModel: EnrollmentViewModel
    private lateinit var authenticationViewModel: AuthenticationViewModel
    private lateinit var evaluationViewModel: EvaluationViewModel

    private val isLocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContinuousAuthManager.continuousAuth =
                ContinuousAuth(
                        context = applicationContext,
                        touchEventFlow = touchEventFlow.asSharedFlow(),
                        enrollmentSamples = TARGET_SAMPLES,
                        shouldLogFeatureVector = true,
                        enableLog = true
                )

        enrollmentViewModel =
                EnrollmentViewModel(applicationContext, ContinuousAuthManager.continuousAuth)

        val dashboardViewModel = DashboardViewModel(application)

        authenticationViewModel = AuthenticationViewModel(ContinuousAuthManager.continuousAuth)
        evaluationViewModel =
                EvaluationViewModel(
                        applicationContext,
                        ContinuousAuthManager.continuousAuth,
                        authenticationViewModel
                )

        setContent {
            AuthframeworkTheme {
                val navController = rememberNavController()

                val lastAuthResult = authenticationViewModel.lastAuthResult
                val isAuthRunning = authenticationViewModel.authenticationRunning
                val evaluationRunning = evaluationViewModel.evaluationRunning

                val currentAuthStatus =
                        if ((evaluationRunning || isAuthRunning) && lastAuthResult != null) {
                            if (lastAuthResult.isAuthenticated) "Authenticated" else "Rejected"
                        } else "Unknown"

                val currentScore = lastAuthResult?.score?.let { "%.3f".format(it) } ?: "N/A"

                val currentAuthPercentage =
                        lastAuthResult?.authPercentage?.let { "%.2f%%".format(it) } ?: "N/A"

                Scaffold(
                        topBar = {
                            AppTopBar(
                                    status = currentAuthStatus,
                                    score = currentScore,
                                    currentAuthPercentage = currentAuthPercentage
                            )
                        },
                        bottomBar = { BottomNavigationBar(navController) }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

                        // 🔐 NEW: TDT-BASED AUTO LOCK
                        TdtAutoLockController()

                        MainNavHost(
                                navController = navController,
                                dashboardViewModel = dashboardViewModel,
                                enrollmentViewModel = enrollmentViewModel,
                                authenticationViewModel = authenticationViewModel,
                                evaluationViewModel = evaluationViewModel,
                                targetSamples = TARGET_SAMPLES
                        )

                        if (isEnableLock.value && isLocked.value) {
                            AppLockScreen()
                        }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                  GLOBAL TOUCH INTERCEPTION                          */
    /* ------------------------------------------------------------------ */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val touchData =
                TouchEventData(
                        action = event.actionMasked,
                        timestamp = event.eventTime,
                        downTime = event.downTime,
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

    override fun onResume() {
        super.onResume()
        //        authenticationViewModel.startAuthentication()
    }

    override fun onPause() {
        super.onPause()
        authenticationViewModel.stopAuthentication()
        enrollmentViewModel.pauseCollection()
    }

    /* ------------------------------------------------------------------ */
    /*              🔐 TDT-BASED AUTO LOCK CONTROLLER                      */
    /* ------------------------------------------------------------------ */
    @Composable
    private fun TdtAutoLockController() {
        if (!isEnableLock.value) return

        val tdtAccuracy = authenticationViewModel.tdtAccuracy
        val totalWindows = authenticationViewModel.totalWindows

        LaunchedEffect(tdtAccuracy, totalWindows) {
            if (totalWindows == 0) return@LaunchedEffect

            // 🔒 Lock if trust drops
            if (tdtAccuracy < TDT_LOCK_THRESHOLD) {
                isLocked.value = true
            }

            // 🔓 Unlock if trust recovers
            if (isLocked.value && tdtAccuracy >= TDT_UNLOCK_THRESHOLD) {
                isLocked.value = false
            }
        }
    }
}
