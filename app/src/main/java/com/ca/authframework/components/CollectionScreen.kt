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
import com.ca.authframework.viewmodels.CollectionViewModel

@Composable
fun CollectionScreen(viewModel: CollectionViewModel, targetSamples: Int = 100) {

    val isCollecting by viewModel.isCollecting.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val collectedCount by viewModel.collectedSampleCount.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // -----------------------
        // Collection Card
        // -----------------------
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Sample Collection",
                    style = MaterialTheme.typography.headlineSmall
                )

                Text(
                    text = when {
                        isPaused -> "Paused"
                        isCollecting -> "Collecting..."
                        else -> "Ready to Collect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                if (isCollecting) {
                    LinearProgressIndicator(
                        progress = progress,
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
                }

                Text(
                    text = "Collected: $collectedCount / $targetSamples",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // -----------------------
        // Control Buttons
        // -----------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            if (!isCollecting && !isPaused) {
                Button(
                    onClick = { viewModel.startCollection(targetSamples) },
                    modifier = Modifier.weight(1f)
                ) { Text("Start") }
            }

            if (isCollecting) {
                Button(
                    onClick = { viewModel.pauseCollection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107))
                ) { Text("Pause") }
            }

            if (isPaused) {
                Button(
                    onClick = { viewModel.resumeCollection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Resume") }
            }

            Button(
                onClick = { viewModel.clearCollection() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) { Text("Clear", color = Color.White) }
        }
    }
}
