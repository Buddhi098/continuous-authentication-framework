package com.ca.authframework.features.evalhistory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ca.authframework.core.ui.theme.SuccessDark
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun EvalHistoryScreen(repository: EvaluationRepository) {
    var records by remember { mutableStateOf(repository.getAllRecords()) }
    var showClearDialog by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<EvaluationRecord?>(null) }

    // Refresh records when screen is displayed
    LaunchedEffect(Unit) { records = repository.getAllRecords() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = "Evaluation History",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
            )

            if (records.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear History",
                            tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            // Empty State
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                            text = "No Evaluations Yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                            text = "Complete an evaluation to see it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Records List
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(records, key = { it.id }) { record ->
                    EvaluationRecordCard(record = record, onDelete = { recordToDelete = record })
                }
            }
        }
    }

    // Clear All Confirmation Dialog
    if (showClearDialog) {
        AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("Clear History") },
                text = {
                    Text(
                            "Are you sure you want to delete all evaluation records? This action cannot be undone."
                    )
                },
                confirmButton = {
                    TextButton(
                            onClick = {
                                repository.clearRecords()
                                records = emptyList()
                                showClearDialog = false
                            }
                    ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
                }
        )
    }

    // Delete Single Record Confirmation Dialog
    recordToDelete?.let { record ->
        AlertDialog(
                onDismissRequest = { recordToDelete = null },
                title = { Text("Delete Record") },
                text = { Text("Delete evaluation record for \"${record.evaluatorName}\"?") },
                confirmButton = {
                    TextButton(
                            onClick = {
                                repository.deleteRecord(record.id)
                                records = repository.getAllRecords()
                                recordToDelete = null
                            }
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { recordToDelete = null }) { Text("Cancel") }
                }
        )
    }
}

@Composable
private fun EvaluationRecordCard(record: EvaluationRecord, onDelete: () -> Unit) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(record.timestamp))

    val labelColor =
            when (record.evaluatorLabel) {
                EvaluatorLabel.IMPOSTOR -> MaterialTheme.colorScheme.error
                EvaluatorLabel.LEGITIMATE -> SuccessDark
            }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Name & Label & Delete
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                ) {
                    Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                    )
                    Text(
                            text = record.evaluatorName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                        text =
                                                record.evaluatorLabel.name.lowercase()
                                                        .replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = labelColor
                                )
                            }
                    )

                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Timestamp
            Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Metrics Row
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(label = "Confidence", value = "%.1f%%".format(record.avgConfidence))
                MetricItem(
                        label = "TDT Accuracy",
                        value = "%.1f%%".format(record.tdtAccuracy * 100)
                )
                MetricItem(label = "Samples", value = record.samplesProcessed.toString())
            }

            // Score Metrics Row
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(label = "Average Score", value = "%.4f".format(record.averageScore))
                MetricItem(label = "Median Score", value = "%.4f".format(record.medianScore))
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
        )
        Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
