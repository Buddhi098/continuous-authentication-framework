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
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // -----------------------------
        // Input & Controls
        // -----------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OutlinedTextField(
                    value = sampleInput,
                    onValueChange = { sampleInput = it },
                    label = { Text("Number of test samples") },
                    singleLine = true,
                    enabled = !viewModel.evaluationRunning,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        Text("Start")
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
                        Text("Stop")
                    }
                }
            }
        }

        // -----------------------------
        // Progress
        // -----------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {

                Text(
                    text = "Progress",
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${viewModel.processedSamples} / ${viewModel.targetSamples}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                LinearProgressIndicator(
                    progress =
                        if (viewModel.targetSamples > 0)
                            viewModel.processedSamples.toFloat() / viewModel.targetSamples
                        else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }

        // -----------------------------
        // ⭐ CENTERED AUTH RESULT
        // -----------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                Text(
                    text = "Authentication Confidence",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${"%.2f".format(viewModel.authPercentage)} %",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Divider(thickness = 1.dp)

                viewModel.lastAuthResult?.score?.let {
                    Text(
                        text = "Last Score: ${"%.4f".format(it)}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }

                Text(
                    text = "Average Score: ${"%.4f".format(viewModel.averageScore)}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Text(
                    text = "Median Score: ${"%.4f".format(viewModel.medianScore)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF009688)
                )
            }
        }

        // -----------------------------
        // TDT Accuracy
        // -----------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {

                Text(
                    text = "Window-based TDT Accuracy",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "${"%.2f".format(viewModel.tdtAccuracy * 100)} %",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )

                Text(
                    text = "Authenticated Windows: ${viewModel.authenticatedWindows}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Text(
                    text = "Total Windows: ${viewModel.totalWindows}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // -----------------------------
        // Evaluation Complete
        // -----------------------------
        if (
            !viewModel.evaluationRunning &&
            viewModel.processedSamples == viewModel.targetSamples &&
            viewModel.targetSamples > 0
        ) {
            Text(
                text = "Evaluation Complete",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
