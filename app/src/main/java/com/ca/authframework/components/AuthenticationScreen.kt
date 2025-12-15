package com.ca.authframework.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ca.authframework.viewmodels.AuthenticationViewModel

@Composable
fun AuthenticationScreen(
    viewModel: AuthenticationViewModel
) {

    // -----------------------------
    // Collect ViewModel state
    // -----------------------------
    val isCheckpointExists by viewModel.isCheckpointExists.collectAsState()
    val isRunning by viewModel::authenticationRunning
    val lastAuthResult by viewModel::lastAuthResult
//    val errorMessage by viewModel::errorMessage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Card(
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // -----------------------------
                // Model state
                // -----------------------------
                Text(
                    text = if (isCheckpointExists)
                        "Authentication model ready"
                    else
                        "No enrolled model found",
                    color = if (isCheckpointExists)
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.error
                )

                // -----------------------------
                // Start / Stop Authentication
                // -----------------------------
                Button(
                    onClick = {
                        if (isRunning) {
                            viewModel.stopAuthentication()
                        } else {
                            viewModel.startAuthentication()
                        }
                    },
                    enabled = isCheckpointExists,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isRunning) "Stop Authentication" else "Start Authentication")
                }

                // -----------------------------
                // Error message
                // -----------------------------
//                if (errorMessage.isNotEmpty()) {
//                    Text(
//                        text = errorMessage,
//                        color = MaterialTheme.colorScheme.error
//                    )
//                }

                // -----------------------------
                // Authentication result (only when running)
                // -----------------------------
                if (isRunning && isCheckpointExists) {
                    lastAuthResult?.let { result ->
                        val success = result.isAuthenticated
                        val color = if (success) Color(0xFF4CAF50) else Color(0xFFF44336)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color.copy(alpha = 0.15f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = if (success) "Authenticated" else "Rejected",
                                color = color,
                                style = MaterialTheme.typography.bodyLarge
                            )

                            Text(
                                text = "Score: ${result.score?.let { String.format("%.2f", it) } ?: "N/A"} | Threshold: ${result.threshold?.let { String.format("%.2f", it) } ?: "N/A"}",
                                color = color,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
