package com.ca.authframework.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ca.authframework.viewmodels.AuthenticationViewModel

@Composable
fun EvaluationScreen(viewModel: AuthenticationViewModel) {
    var sampleInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "Authentication Evaluation",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Number of samples input
        OutlinedTextField(
            value = sampleInput,
            onValueChange = { sampleInput = it },
            label = { Text("Number of test samples") },
            singleLine = true,
            enabled = !viewModel.evaluationRunning,
            modifier = Modifier.fillMaxWidth()
        )

        // Start / Stop buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    sampleInput.toIntOrNull()?.let {
                        viewModel.startEvaluation(it)
                    }
                },
                enabled = !viewModel.evaluationRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start", fontSize = 14.sp)
            }

            Button(
                onClick = { viewModel.stopEvaluation() },
                enabled = viewModel.evaluationRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF5350),
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop", fontSize = 14.sp)
            }
        }

        Divider(color = Color.Gray, thickness = 1.dp)

        // Progress
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Progress: ${viewModel.processedSamples} / ${viewModel.targetSamples}",
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
            LinearProgressIndicator(
                progress = if (viewModel.targetSamples > 0)
                    viewModel.processedSamples.toFloat() / viewModel.targetSamples
                else 0f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Authentication Percentage Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Authentication Confidence",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${"%.2f".format(viewModel.authPercentage)} %",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                viewModel.lastAuthResult?.score?.let { lastScore ->
                    Text(
                        text = "Last Score: ${"%.4f".format(lastScore)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Gray
                    )
                }
            }
        }

        // Evaluation Complete
        if (!viewModel.evaluationRunning && viewModel.processedSamples == viewModel.targetSamples && viewModel.targetSamples > 0) {
            Text(
                text = "Evaluation Complete",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            )
        }
    }
}
