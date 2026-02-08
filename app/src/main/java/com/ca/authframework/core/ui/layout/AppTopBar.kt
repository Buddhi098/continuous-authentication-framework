package com.ca.authframework.core.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ca.authframework.core.ui.components.StatusBadge

@Composable
fun AppTopBar(status: String, score: String, currentAuthPercentage: String) {
        Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp
        ) {
                Column(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .windowInsetsPadding(
                                                WindowInsets.statusBars
                                        ), // Safely clear status bar
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        // 1. Title Row
                        Text(
                                text = "Continuous Auth",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                        )

                        // 2. Status Row
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                // Use a FlowRow-like behavior or just a simple Row with spacing
                                // For now, a Row with generous spacing works well
                                Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        StatusBadge("Status", status)
                                        StatusBadge("Score", score)
                                        StatusBadge("AC", currentAuthPercentage)
                                }
                        }
                }
        }
}
