package com.ca.authframework.features.enrollment

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ca.authframework.core.ui.theme.ErrorDark
import com.ca.authframework.core.ui.theme.SuccessDark

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun EnrollmentScreen(viewModel: EnrollmentViewModel, targetSamples: Int = 100) {
        val isCollecting by viewModel.isCollecting.collectAsState()
        val progress by viewModel.progress.collectAsState()
        val collectedCount by viewModel.collectedSampleCount.collectAsState()
        val isPaused by viewModel.isPaused.collectAsState()
        val statusMessage by viewModel.statusMessage.collectAsState()
        // 🔐 Model State: Any threshold > 0 means a model is trained
        val threshold by viewModel.threshold.collectAsState()

        val isCompleted = progress >= 1f
        var showClearDialog by remember { mutableStateOf(false) }
        var showInstructions by remember { mutableStateOf(true) }

        Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {

                // --- Instructions Card ---
                if (showInstructions && !isCompleted) {
                        ElevatedCard(
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        CardDefaults.elevatedCardColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                                .copy(alpha = 0.5f)
                                        )
                        ) {
                                Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                        text = "How it works",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                        text =
                                                                "Interact with your device naturally inside the app. We collect touch patterns to build your secure profile.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )
                                        }
                                        IconButton(
                                                onClick = { showInstructions = false },
                                                modifier = Modifier.size(24.dp)
                                        ) {
                                                Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Dismiss",
                                                        tint =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )
                                        }
                                }
                        }
                }

                // --- Main Progress Section ---
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                )
                ) {
                        Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                                // Header
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                                text = "Enrollment Status",
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                                text =
                                                        when {
                                                                isCompleted -> "Profile Ready"
                                                                isPaused -> "Paused"
                                                                isCollecting -> "Collecting Data..."
                                                                else -> "Ready to Start"
                                                        },
                                                style = MaterialTheme.typography.titleMedium,
                                                color =
                                                        when {
                                                                isCompleted -> SuccessDark
                                                                isResultFailed(statusMessage) ->
                                                                        ErrorDark
                                                                isCollecting ->
                                                                        MaterialTheme.colorScheme
                                                                                .primary
                                                                else ->
                                                                        MaterialTheme.colorScheme
                                                                                .onSurfaceVariant
                                                        }
                                        )
                                }

                                // Circular Progress
                                Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(160.dp)
                                ) {
                                        // Track
                                        CircularProgressIndicator(
                                                progress = 1f,
                                                modifier = Modifier.fillMaxSize(),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                strokeWidth = 12.dp,
                                                strokeCap = StrokeCap.Round
                                        )
                                        // Progress
                                        CircularProgressIndicator(
                                                progress = progress.coerceIn(0f, 1f),
                                                modifier = Modifier.fillMaxSize(),
                                                color =
                                                        if (isCompleted) SuccessDark
                                                        else MaterialTheme.colorScheme.primary,
                                                strokeWidth = 12.dp,
                                                strokeCap = StrokeCap.Round
                                        )

                                        // Text Center
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                        text = "${(progress * 100).toInt()}%",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                        text =
                                                                "${collectedCount} / ${targetSamples}",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .labelMedium,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )
                                        }
                                }

                                // Status Message Box
                                if (statusMessage.isNotEmpty()) {
                                        Surface(
                                                color =
                                                        getStatusColor(statusMessage)
                                                                .copy(alpha = 0.1f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                        ) {
                                                Text(
                                                        text = statusMessage,
                                                        modifier = Modifier.padding(12.dp),
                                                        color = getStatusColor(statusMessage),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        textAlign = TextAlign.Center,
                                                        fontWeight = FontWeight.Medium
                                                )
                                        }
                                }
                        }
                }

                // --- Controls ---
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                        // Start/Pause/Resume Button
                        Button(
                                onClick = {
                                        when {
                                                isCollecting -> viewModel.pauseCollection()
                                                isPaused -> viewModel.resumeCollection()
                                                else -> viewModel.startCollection()
                                        }
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                enabled =
                                        // Disable if complete OR if model already exists (threshold
                                        // > 0)
                                        // Exception: Allow pausing if actively collecting
                                        if (isCollecting) true
                                        else (!isCompleted && threshold <= 0f) || isPaused,
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor =
                                                        if (isCollecting)
                                                                MaterialTheme.colorScheme.secondary
                                                        else MaterialTheme.colorScheme.primary
                                        )
                        ) {
                                Icon(
                                        imageVector =
                                                when {
                                                        isCollecting -> Icons.Default.Pause
                                                        else -> Icons.Default.PlayArrow
                                                },
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(text = if (isCollecting) "Pause" else "Start")
                        }

                        // Clear Button
                        OutlinedButton(
                                onClick = { showClearDialog = true },
                                modifier = Modifier.weight(1f).height(50.dp),
                                colors =
                                        ButtonDefaults.outlinedButtonColors(
                                                contentColor = ErrorDark
                                        )
                        ) {
                                Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("Reset")
                        }
                }
        }

        // Confirmation Dialog
        if (showClearDialog) {
                AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        icon = {
                                Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = ErrorDark
                                )
                        },
                        title = { Text("Reset Enrollment?") },
                        text = {
                                Text(
                                        "This will delete all collected samples for this session. This action cannot be undone."
                                )
                        },
                        confirmButton = {
                                TextButton(
                                        onClick = {
                                                viewModel.clearAll()
                                                showClearDialog = false
                                        },
                                        colors =
                                                ButtonDefaults.textButtonColors(
                                                        contentColor = ErrorDark
                                                )
                                ) { Text("Delete") }
                        },
                        dismissButton = {
                                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                        }
                )
        }
}

private fun isResultFailed(msg: String): Boolean =
        msg.contains("failed", true) || msg.contains("error", true)

private fun getStatusColor(msg: String): Color {
        return when {
                msg.contains("failed", true) || msg.contains("error", true) -> ErrorDark
                msg.contains("trained", true) -> SuccessDark
                else -> Color(0xFF2196F3) // Info Blue
        }
}
