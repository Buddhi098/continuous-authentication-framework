package com.ca.authframework.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ca.authframework.viewmodels.EnrollmentViewModel

@Composable
fun EnrollmentScreen(
    viewModel: EnrollmentViewModel,
    targetSamples: Int = 100
) {
    val isCollecting by viewModel.isCollecting.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val collectedCount by viewModel.collectedSampleCount.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val isCompleted = progress >= 1f

    // Clear confirmation dialog state
    var showClearDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // -----------------------
        // Enrollment Card
        // -----------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                Text(
                    text = "User Enrollment",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = when {
                        isCompleted -> "Training dataset collection finished"
                        isPaused -> "Collection paused"
                        isCollecting -> "Collecting samples..."
                        else -> "Ready to start enrollment"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(
                            color = Color.LightGray,
                            shape = RoundedCornerShape(5.dp)
                        )
                )

                Text(
                    text = "${(progress * 100).toInt()}%",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.End)
                )

                Text(
                    text = "Collected: $collectedCount / $targetSamples",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        color = when {
                            statusMessage.contains("failed", true) -> Color.Red
                            statusMessage.contains("trained", true) -> Color(0xFF4CAF50)
                            else -> Color(0xFF2196F3)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // -----------------------
        // Control Buttons
        // -----------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            if (!isCollecting && !isPaused && !isCompleted) {
                Button(
                    onClick = { viewModel.startCollection(targetSamples) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }
            }

            if (isCollecting) {
                Button(
                    onClick = { viewModel.pauseCollection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFC107)
                    )
                ) {
                    Text("Pause")
                }
            }

            if (isPaused) {
                Button(
                    onClick = { viewModel.resumeCollection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Text("Resume")
                }
            }

            Button(
                onClick = { showClearDialog = true }, // open dialog
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                )
            ) {
                Text("Clear", color = Color.White)
            }
        }
    }

    // -----------------------
    // Clear Confirmation Dialog
    // -----------------------
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(text = "Clear Enrollment Data?")
            },
            text = {
                Text(
                    text = "This will permanently remove all collected samples and reset the enrollment process. This action cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearCollection()
                    }
                ) {
                    Text("Clear", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
