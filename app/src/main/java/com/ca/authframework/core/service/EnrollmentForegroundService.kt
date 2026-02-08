package com.ca.authframework.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.ca.authframework.ContinuousAuthManager
import com.ca.authframework.MainActivity
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.EnrollmentResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

class EnrollmentForegroundService : Service() {

    companion object {
        private const val TAG = "CAFramework"

        const val CHANNEL_ID = "enrollment_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_ENROLLMENT"
        const val ACTION_STOP = "ACTION_STOP_ENROLLMENT"

        const val EXTRA_CHECKPOINT = "checkpointFile"
        const val EXTRA_THRESHOLD = "thresholdFile"

        @RequiresApi(Build.VERSION_CODES.O)
        fun start(context: Context, checkpointPath: String, thresholdPath: String) {
            Log.i(TAG, "Starting EnrollmentForegroundService")
            val intent = Intent(context, EnrollmentForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CHECKPOINT, checkpointPath)
                putExtra(EXTRA_THRESHOLD, thresholdPath)
            }
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> runEnrollment(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun runEnrollment(intent: Intent) {
        val checkpointPath = intent.getStringExtra(EXTRA_CHECKPOINT)
        val thresholdPath = intent.getStringExtra(EXTRA_THRESHOLD)

        if (checkpointPath == null || thresholdPath == null) {
            Log.e(TAG, "Missing checkpoint or threshold path")
            serviceScope.launch { emitFailure("Missing checkpoint or threshold path") }
            stopSelf()
            return
        }

        Log.i(TAG, "Enrollment started | checkpoint=$checkpointPath | threshold=$thresholdPath")

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Training model…")
        )

        serviceScope.launch {
            val startTime = SystemClock.elapsedRealtime()
            try {
                val auth = ContinuousAuthManager.continuousAuth
                val sampleCount = auth.collectedSamplesCount.value
                Log.i(TAG, "Collected samples count = $sampleCount")

                if (sampleCount <= 0) {
                    emitFailure("No samples available for enrollment")
                    return@launch
                }

                val result = withTimeout(30 * 60 * 1000) { suspendEnrollment(auth) }

                val elapsed = SystemClock.elapsedRealtime() - startTime
                Log.i(TAG, "Enrollment finished in ${elapsed}ms | success=${result.success}")

                if (result.success) {
                    EnrollmentResultBus.emit(result)
                    updateNotification("Training completed")
                } else {
                    emitFailure(result.message ?: "Unknown training failure")
                }

            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Enrollment timed out", e)
                emitFailure("Training timed out after 5 minutes", e)

            } catch (e: Exception) {
                Log.e(TAG, "Enrollment error", e)
                emitFailure("Enrollment error: ${e.message}", e)

            } finally {
                Log.i(TAG, "Stopping service")
                delay(3000)
                stopSelf()
            }
        }
    }

    private suspend fun emitFailure(message: String, e: Exception? = null) {
        Log.e(TAG, message, e)
        EnrollmentResultBus.emit(
            EnrollmentResult(
                success = false,
                threshold = null,
                message = message
            )
        )
        updateNotification("Training failed")
    }

    private suspend fun suspendEnrollment(auth: ContinuousAuth): EnrollmentResult =
        suspendCancellableCoroutine { cont ->
            Log.d(TAG, "Calling ContinuousAuth.startEnrollment()")
            auth.startEnrollment { result ->
                Log.d(TAG, "Enrollment callback received | success=${result.success}")
                if (cont.isActive) cont.resume(result)
            }

            cont.invokeOnCancellation {
                Log.w(TAG, "Enrollment coroutine cancelled")
            }
        }

    private fun updateNotification(text: String) {
        Log.d(TAG, "Notification update: $text")
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enrollment")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Enrollment",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel created")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
