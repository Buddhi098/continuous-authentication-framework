package com.ca.authframework.features.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ca.authframework.core.ui.theme.SuccessDark
import com.ca.authframework.features.evalhistory.EvaluatorLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationScreen(viewModel: EvaluationViewModel) {
        var sampleInput by remember { mutableStateOf("") }
        var evaluatorName by remember { mutableStateOf("") }
        var selectedLabel by remember { mutableStateOf(EvaluatorLabel.LEGITIMATE) }
        var dropdownExpanded by remember { mutableStateOf(false) }

        // Derived state for button enabled
        val isRunning = viewModel.evaluationRunning
        val processed = viewModel.processedSamples
        val target = viewModel.targetSamples

        // Check if start button should be enabled
        val canStart =
                !isRunning &&
                        sampleInput.toIntOrNull()?.let { it > 0 } == true &&
                        evaluatorName.isNotBlank()

        Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

                // --- Control Panel ---
                ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                ) {
                        Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                                Text(
                                        text = "Evaluation Config",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                )

                                // Evaluator Name Field
                                OutlinedTextField(
                                        value = evaluatorName,
                                        onValueChange = { evaluatorName = it },
                                        label = { Text("Evaluator Name") },
                                        singleLine = true,
                                        enabled = !isRunning,
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp)
                                )

                                // Evaluator Label Dropdown
                                ExposedDropdownMenuBox(
                                        expanded = dropdownExpanded,
                                        onExpandedChange = { if (!isRunning) dropdownExpanded = it }
                                ) {
                                        OutlinedTextField(
                                                value =
                                                        selectedLabel.name.lowercase()
                                                                .replaceFirstChar {
                                                                        it.uppercase()
                                                                },
                                                onValueChange = {},
                                                label = { Text("Evaluator Label") },
                                                readOnly = true,
                                                enabled = !isRunning,
                                                trailingIcon = {
                                                        ExposedDropdownMenuDefaults.TrailingIcon(
                                                                expanded = dropdownExpanded
                                                        )
                                                },
                                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                                shape = RoundedCornerShape(8.dp)
                                        )
                                        ExposedDropdownMenu(
                                                expanded = dropdownExpanded,
                                                onDismissRequest = { dropdownExpanded = false }
                                        ) {
                                                EvaluatorLabel.entries.forEach { label ->
                                                        DropdownMenuItem(
                                                                text = {
                                                                        Text(
                                                                                label.name
                                                                                        .lowercase()
                                                                                        .replaceFirstChar {
                                                                                                it.uppercase()
                                                                                        }
                                                                        )
                                                                },
                                                                onClick = {
                                                                        selectedLabel = label
                                                                        dropdownExpanded = false
                                                                }
                                                        )
                                                }
                                        }
                                }

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        OutlinedTextField(
                                                value = sampleInput,
                                                onValueChange = { sampleInput = it },
                                                label = { Text("Count") },
                                                singleLine = true,
                                                enabled = !isRunning,
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(8.dp)
                                        )

                                        Button(
                                                onClick = {
                                                        if (isRunning) viewModel.stopEvaluation()
                                                        else
                                                                sampleInput.toIntOrNull()?.let {
                                                                        viewModel.startEvaluation(
                                                                                it,
                                                                                evaluatorName,
                                                                                selectedLabel
                                                                        )
                                                                }
                                                },
                                                enabled = isRunning || canStart,
                                                modifier = Modifier.weight(1f).height(56.dp),
                                                colors =
                                                        ButtonDefaults.buttonColors(
                                                                containerColor =
                                                                        if (isRunning)
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                        else
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                        ),
                                                shape = RoundedCornerShape(8.dp)
                                        ) { Text(if (isRunning) "STOP" else "START") }
                                }

                                // Progress Bar
                                if (isRunning || processed > 0) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.SpaceBetween
                                                ) {
                                                        Text(
                                                                text = "Progress",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelMedium
                                                        )
                                                        Text(
                                                                text = "$processed / $target",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelMedium
                                                        )
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                LinearProgressIndicator(
                                                        progress = {
                                                                if (target > 0)
                                                                        processed.toFloat() / target
                                                                else 0f
                                                        },
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .height(8.dp)
                                                                        .clip(
                                                                                RoundedCornerShape(
                                                                                        4.dp
                                                                                )
                                                                        )
                                                )
                                        }
                                }
                        }
                }

                // --- Metrics Grid ---
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        // Confidence Card
                        ElevatedCard(
                                modifier = Modifier.weight(1f).height(140.dp),
                                colors =
                                        CardDefaults.elevatedCardColors(
                                                containerColor =
                                                        MaterialTheme.colorScheme.primaryContainer
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                ) {
                                        Text(
                                                "Avg Confidence",
                                                style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                                text =
                                                        "${"%.1f".format(viewModel.authPercentage)}%",
                                                style = MaterialTheme.typography.displaySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                }
                        }

                        // TDT Accuracy Card
                        ElevatedCard(
                                modifier = Modifier.weight(1f).height(140.dp),
                                colors =
                                        CardDefaults.elevatedCardColors(
                                                containerColor = SuccessDark.copy(alpha = 0.2f)
                                        )
                        ) {
                                Column(
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                ) {
                                        Text(
                                                "TDT Accuracy",
                                                style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                                text =
                                                        "${"%.1f".format(viewModel.tdtAccuracy * 100)}%",
                                                style = MaterialTheme.typography.displaySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = SuccessDark
                                        )
                                        Text(
                                                text = "Last Window",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        }
                }

                // --- Detailed Stats ---
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                        text = "Score Statistics",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                )

                                StatRow("Average Score", "%.4f".format(viewModel.averageScore))
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                StatRow("Median Score", "%.4f".format(viewModel.medianScore))
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                StatRow(
                                        "Last Score",
                                        viewModel.lastAuthResult?.authenticationScore?.let { "%.4f".format(it) }
                                                ?: "-"
                                )
                        }
                }

                Spacer(Modifier.weight(1f))

                if (!isRunning && processed == target && target > 0) {
                        Text(
                                "Evaluation Completed Successfully",
                                color = SuccessDark,
                                fontWeight = FontWeight.Bold
                        )
                }
        }
}

@Composable
fun StatRow(label: String, value: String) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
                Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                )
        }
}
