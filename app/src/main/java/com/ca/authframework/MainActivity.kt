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
import com.ca.authframework.features.tdtlock.TdtComputer
import com.ca.authframework.features.tdtlock.TdtLockConfig
import com.ca.authframework.features.tdtlock.TdtLockFeature
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
    }

    private val touchEventFlow =
            MutableSharedFlow<TouchEventData>(replay = 0, extraBufferCapacity = 256)

    private lateinit var enrollmentViewModel: EnrollmentViewModel
    private lateinit var authenticationViewModel: AuthenticationViewModel
    private lateinit var evaluationViewModel: EvaluationViewModel

    // TDT Lock Feature (plug-and-play)
    private val tdtLockConfig =
            TdtLockConfig(windowSize = 10, lockThreshold = 0.5f, unlockThreshold = 0.6f)
    private val tdtLockFeature = TdtLockFeature(tdtLockConfig)

    // Shared TDT computer for global state
    private val globalTdtComputer = TdtComputer(tdtLockConfig)

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

        authenticationViewModel =
                AuthenticationViewModel(ContinuousAuthManager.continuousAuth, globalTdtComputer)
        evaluationViewModel =
                EvaluationViewModel(
                        applicationContext,
                        ContinuousAuthManager.continuousAuth,
                        authenticationViewModel
                )

        // Enable TDT lock feature (can be toggled via settings)
        //        tdtLockFeature.enable()
        tdtLockFeature.disable()

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

                // Collect TDT lock state
                val isLocked by tdtLockFeature.isLocked.collectAsState()
                val isFeatureEnabled by tdtLockFeature.isEnabled.collectAsState()

                // Forward auth results to TDT lock feature
                LaunchedEffect(lastAuthResult) {
                    lastAuthResult?.let { result ->
                        tdtLockFeature.processResult(result.isAuthenticated)
                    }
                }

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
                        MainNavHost(
                                navController = navController,
                                dashboardViewModel = dashboardViewModel,
                                enrollmentViewModel = enrollmentViewModel,
                                authenticationViewModel = authenticationViewModel,
                                evaluationViewModel = evaluationViewModel,
                                targetSamples = TARGET_SAMPLES,
                                tdtLockEnabled = isFeatureEnabled,
                                onTdtLockToggle = { enabled ->
                                    if (enabled) tdtLockFeature.enable()
                                    else tdtLockFeature.disable()
                                }
                        )

                        // Show lock screen if feature is enabled and locked
                        if (isFeatureEnabled && isLocked) {
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
    /*              TDT Lock Feature Controls                              */
    /* ------------------------------------------------------------------ */

    /** Enable TDT-based auto-lock feature */
    fun enableTdtLock() {
        tdtLockFeature.enable()
    }

    /** Disable TDT-based auto-lock feature */
    fun disableTdtLock() {
        tdtLockFeature.disable()
    }

    /** Check if TDT lock feature is enabled */
    fun isTdtLockEnabled(): Boolean = tdtLockFeature.isEnabled.value

    /** Reset TDT lock feature state */
    fun resetTdtLock() {
        tdtLockFeature.reset()
    }
}
