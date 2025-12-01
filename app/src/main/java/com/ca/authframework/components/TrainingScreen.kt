package com.ca.authframework.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ca.authframework.viewmodels.TrainingViewModel

@Composable
fun TrainingScreen(
    viewModel: TrainingViewModel,
    targetSamples: Int // Pass the same targetSamples as used in collection
) {
    // Directly read ViewModel state
    val isTraining = viewModel.isTraining
    val trainingStatus = viewModel.trainingStatus
    val trainingProgress = viewModel.trainingProgress
    val collectedSamples = viewModel.collectedSamples
    val errorMessage = viewModel.errorMessage

    // Enable training only if collection is complete and training is not running
    val canTrain = collectedSamples.size >= targetSamples && !isTraining

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Model Training", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("Samples available: ${collectedSamples.size} / $targetSamples")
                Spacer(Modifier.height(12.dp))

                if (isTraining) {
                    LinearProgressIndicator(
                        progress = trainingProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Training in progress: ${(trainingProgress * 100).toInt()}%")
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.startTraining() },
                    enabled = canTrain,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isTraining) "Training..." else "Start Training")
                }

                Spacer(Modifier.height(8.dp))

                if (trainingStatus.isNotEmpty()) {
                    Text("Status: $trainingStatus")
                }

                if (errorMessage.isNotEmpty()) {
                    Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error)
                }

                if (isTraining) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 4.dp)
                }
            }
        }
    }
}
