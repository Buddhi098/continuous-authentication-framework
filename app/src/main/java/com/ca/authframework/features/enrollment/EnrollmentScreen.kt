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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val isPaused by viewModel.isPaused.collectAsState()
        val statusMessage by viewModel.statusMessage.collectAsState()
        // 🔐 Model State: Any threshold > 0 means a model is trained
        val threshold by viewModel.threshold.collectAsState()
        val trainedSampleCount by viewModel.trainedSampleCount.collectAsState()

        val isCompleted by remember(viewModel) {
            viewModel.progress.map { it >= 1f }.distinctUntilChanged()
        }.collectAsState(initial = viewModel.progress.value >= 1f)
        var showClearDialog by remember { mutableStateOf(false) }
        var showInstructions by remember { mutableStateOf(true) }

        fun isResultFailed(msg: String): Boolean =
                msg.contains("failed", true) || msg.contains("error", true)

        fun getStatusColor(msg: String): Color {
                return when {
                        msg.contains("failed", true) || msg.contains("error", true) -> ErrorDark
                        msg.contains("trained", true) -> SuccessDark
                        else -> Color(0xFF2196F3) // Info Blue
                }
        }

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

                                        // Trained Sample Count
                                        if (threshold > 0f) {
                                                Text(
                                                        text =
                                                                "Trained on ${trainedSampleCount ?: "N/A"} samples",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                // Auth Mode Indicator
                                                val isFusionReady by
                                                        viewModel.isFusionModelReady
                                                                .collectAsState()
                                                Surface(
                                                        color =
                                                                if (isFusionReady)
                                                                        SuccessDark.copy(
                                                                                alpha = 0.15f
                                                                        )
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .primary.copy(
                                                                                alpha = 0.15f
                                                                        ),
                                                        shape = RoundedCornerShape(16.dp),
                                                        modifier = Modifier.padding(top = 4.dp)
                                                ) {
                                                        Text(
                                                                text =
                                                                        if (isFusionReady)
                                                                                "Multi-modal (Sensor + Touch)"
                                                                        else "Sensor-only",
                                                                modifier =
                                                                        Modifier.padding(
                                                                                horizontal = 12.dp,
                                                                                vertical = 4.dp
                                                                        ),
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelSmall,
                                                                color =
                                                                        if (isFusionReady)
                                                                                SuccessDark
                                                                        else
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary,
                                                                fontWeight = FontWeight.Medium
                                                        )
                                                }
                                        }
                                }

                                // --- Threshold Info ---
                                if (threshold > 0f) {
                                        val fusionThreshold by
                                                viewModel.fusionThreshold.collectAsState()
                                        val isFusionReady by
                                                viewModel.isFusionModelReady.collectAsState()

                                        Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                Text(
                                                        text = "Thresholds",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                )
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.spacedBy(8.dp)
                                                ) {
                                                        // Sensor Threshold
                                                        Surface(
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant
                                                                                .copy(alpha = 0.5f),
                                                                shape = RoundedCornerShape(8.dp),
                                                                modifier = Modifier.weight(1f)
                                                        ) {
                                                                Column(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        12.dp
                                                                                ),
                                                                        horizontalAlignment =
                                                                                Alignment
                                                                                        .CenterHorizontally
                                                                ) {
                                                                        Text(
                                                                                text =
                                                                                        "%.4f".format(
                                                                                                threshold
                                                                                        ),
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .titleMedium,
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Bold,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .primary
                                                                        )
                                                                        Text(
                                                                                text = "Sensor Only",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .labelSmall,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                        }

                                                        // Fusion Threshold
                                                        Surface(
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant
                                                                                .copy(alpha = 0.5f),
                                                                shape = RoundedCornerShape(8.dp),
                                                                modifier = Modifier.weight(1f)
                                                        ) {
                                                                Column(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        12.dp
                                                                                ),
                                                                        horizontalAlignment =
                                                                                Alignment
                                                                                        .CenterHorizontally
                                                                ) {
                                                                        Text(
                                                                                text =
                                                                                        if (isFusionReady &&
                                                                                                        fusionThreshold >
                                                                                                                0f
                                                                                        )
                                                                                                "%.4f".format(
                                                                                                        fusionThreshold
                                                                                                )
                                                                                        else "N/A",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .titleMedium,
                                                                                fontWeight =
                                                                                        FontWeight
                                                                                                .Bold,
                                                                                color =
                                                                                        if (isFusionReady
                                                                                        )
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .primary
                                                                                        else
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .onSurfaceVariant
                                                                        )
                                                                        Text(
                                                                                text = "Multi-Model",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .labelSmall,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                        }
                                                }
                                        }
                                }
                                // --- Continuous Learning Card ---
                                val storedVectorCount by
                                        viewModel.storedVectorCount.collectAsState()
                                val maxStoredVectors = viewModel.maxStoredVectorCount
                                val isReEnrollmentReady = storedVectorCount >= maxStoredVectors

                                if (threshold > 0f) {
                                        Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors =
                                                        CardDefaults.cardColors(
                                                                containerColor =
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant
                                                                                .copy(alpha = 0.3f)
                                                        ),
                                                shape = RoundedCornerShape(12.dp)
                                        ) {
                                                Column(
                                                        modifier = Modifier.padding(16.dp),
                                                        verticalArrangement =
                                                                Arrangement.spacedBy(12.dp)
                                                ) {
                                                        Column(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                verticalArrangement =
                                                                        Arrangement.SpaceBetween,
                                                                horizontalAlignment =
                                                                        Alignment.CenterHorizontally
                                                        ) {
                                                                Text(
                                                                        text =
                                                                                "Continuous Learning",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .titleSmall,
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                                Text(
                                                                        text =
                                                                                "$storedVectorCount / $maxStoredVectors samples",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .labelSmall,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurfaceVariant
                                                                )
                                                        }

                                                        LinearProgressIndicator(
                                                                progress =
                                                                        (storedVectorCount
                                                                                        .toFloat() /
                                                                                        maxStoredVectors)
                                                                                .coerceIn(0f, 1f),
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .height(8.dp),
                                                                trackColor =
                                                                        MaterialTheme.colorScheme
                                                                                .surfaceVariant,
                                                                color =
                                                                        if (isReEnrollmentReady)
                                                                                SuccessDark
                                                                        else
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary,
                                                                strokeCap = StrokeCap.Round
                                                        )

                                                        Button(
                                                                onClick = { viewModel.reEnroll() },
                                                                enabled = isReEnrollmentReady,
                                                                modifier = Modifier.fillMaxWidth(),
                                                                colors =
                                                                        ButtonDefaults.buttonColors(
                                                                                containerColor =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .primaryContainer,
                                                                                contentColor =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onPrimaryContainer,
                                                                                disabledContainerColor =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .surfaceVariant,
                                                                                disabledContentColor =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.38f
                                                                                                )
                                                                        )
                                                        ) { Text("Adapt Model (Re-enroll)") }
                                                }
                                        }
                                }

                                // Circular Progress
                                if (threshold <= 0f) {
                                        EnrollmentProgressBox(
                                                viewModel = viewModel,
                                                targetSamples = targetSamples,
                                                isCompleted = isCompleted
                                        )
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

@Composable
fun EnrollmentProgressBox(
    viewModel: EnrollmentViewModel,
    targetSamples: Int,
    isCompleted: Boolean
) {
    val progress by viewModel.progress.collectAsState()
    val collectedCount by viewModel.collectedSampleCount.collectAsState()
    val currentFrequency by viewModel.currentFrequency.collectAsState()
    val isCollecting by viewModel.isCollecting.collectAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp)
    ) {
        // Track
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 12.dp,
            strokeCap = StrokeCap.Round
        )
        // Progress
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxSize(),
            color = if (isCompleted) SuccessDark else MaterialTheme.colorScheme.primary,
            strokeWidth = 12.dp,
            strokeCap = StrokeCap.Round
        )

        // Text Center
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${collectedCount} / ${targetSamples}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isCollecting) {
                Text(
                    text = "100Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
