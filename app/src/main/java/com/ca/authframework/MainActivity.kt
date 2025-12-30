package com.ca.authframework

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.ca.authframework.navigation.MainNavHost
import com.ca.authframework.ui.AppTopBar
import com.ca.authframework.ui.BottomNavigationBar
import com.ca.authframework.ui.theme.AuthframeworkTheme
import com.ca.authframework.viewmodels.AuthenticationViewModel
import com.ca.authframework.viewmodels.EnrollmentViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.TouchEventData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

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
        private const val TARGET_SAMPLES = 2000
        private const val AUTH_THRESHOLD = 0.08f
        private const val SCORE_WINDOW = 6
    }

    /* 🔐 Feature flag */
    private val isEnableLock = mutableStateOf(true)

    private val touchEventFlow = MutableSharedFlow<TouchEventData>(
        replay = 0,
        extraBufferCapacity = 256
    )

    private lateinit var enrollmentViewModel: EnrollmentViewModel
    private lateinit var authenticationViewModel: AuthenticationViewModel

    /* 🔒 Lock state (ONLY used when isEnableLock = true) */
    private val isLocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContinuousAuthManager.continuousAuth = ContinuousAuth(
            context = applicationContext,
            touchEventFlow = touchEventFlow.asSharedFlow(),
            enrollmentSamples = TARGET_SAMPLES,
            shouldLogFeatureVector = true,
            enableLog = true
        )

        enrollmentViewModel =
            EnrollmentViewModel(applicationContext, ContinuousAuthManager.continuousAuth)

        authenticationViewModel =
            AuthenticationViewModel(ContinuousAuthManager.continuousAuth)

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

                /* ---------------- MAIN APP UI (UNCHANGED) ---------------- */
                Scaffold(
                    topBar = { AppTopBar(status = currentAuthStatus, score = currentScore , currentAuthPercentage = currentAuthPercentage) },
                    bottomBar = { BottomNavigationBar(navController) }
                ) { innerPadding ->

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AutoLockController()

                        /* App content always visible */
                        MainNavHost(
                            navController = navController,
                            enrollmentViewModel = enrollmentViewModel,
                            authenticationViewModel = authenticationViewModel,
                            targetSamples = TARGET_SAMPLES
                        )

                        /* 🔒 Lock screen overlay ONLY if enabled + locked */
                        if (isEnableLock.value && isLocked.value) {
                            AppLockScreen(
                                authenticationViewModel = authenticationViewModel,
                                threshold = AUTH_THRESHOLD,
                                scoreWindow = SCORE_WINDOW,
                                onUnlocked = { isLocked.value = false }
                            )
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

    override fun onResume() {
        super.onResume()
        authenticationViewModel.startAuthentication()
    }

    override fun onPause() {
        super.onPause()
        authenticationViewModel.stopAuthentication()
        enrollmentViewModel.pauseCollection()
    }

    /* ------------------------------------------------------------------ */
    /*      AUTO-LOCK DECISION (CONTINUOUS, INVISIBLE)                     */
    /* ------------------------------------------------------------------ */
    @Composable
    private fun AutoLockController() {
        if (!isEnableLock.value) return

        val lastAuthResult = authenticationViewModel.lastAuthResult
        val scoreBuffer = remember { ArrayDeque<Float>() }

        LaunchedEffect(lastAuthResult) {
            lastAuthResult?.let {
                scoreBuffer.addLast(it.score)
                if (scoreBuffer.size > SCORE_WINDOW) {
                    scoreBuffer.removeFirst()
                }

                if (scoreBuffer.size == SCORE_WINDOW) {
                    val meanScore = scoreBuffer.average().toFloat()

                    /* 🔒 Auto-lock */
                    if (meanScore >= AUTH_THRESHOLD) {
                        isLocked.value = true
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------- */
/*                           LOCK SCREEN UI                                */
/* ---------------------------------------------------------------------- */
@Composable
fun AppLockScreen(
    authenticationViewModel: AuthenticationViewModel,
    threshold: Float,
    scoreWindow: Int,
    onUnlocked: () -> Unit
) {
    val lastAuthResult = authenticationViewModel.lastAuthResult
    val scoreBuffer = remember { ArrayDeque<Float>() }

    LaunchedEffect(lastAuthResult) {
        lastAuthResult?.let {
            scoreBuffer.addLast(it.score)
            if (scoreBuffer.size > scoreWindow) {
                scoreBuffer.removeFirst()
            }

            if (scoreBuffer.size == scoreWindow) {
                val meanScore = scoreBuffer.average().toFloat()

                /* 🔓 Unlock */
                if (meanScore < threshold) {
                    onUnlocked()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Spacer(modifier = Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.Center) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Interact naturally",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}
