package com.ca.authframework.features.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ca.authframework.features.authentication.AuthenticationScreen
import com.ca.authframework.features.authentication.AuthenticationViewModel
import com.ca.authframework.features.enrollment.EnrollmentScreen
import com.ca.authframework.features.enrollment.EnrollmentViewModel
import com.ca.authframework.features.evaluation.EvaluationScreen
import com.ca.authframework.features.evaluation.EvaluationViewModel

@Composable
fun AuthSettingsScreen(
        enrollmentViewModel: EnrollmentViewModel,
        authenticationViewModel: AuthenticationViewModel,
        evaluationViewModel: EvaluationViewModel,
        targetSamples: Int,
        tdtLockEnabled: Boolean = false,
        onTdtLockToggle: (Boolean) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    
    Column(
            modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // TDT Lock Settings Group
        SettingsGroup(title = "Security", icon = Icons.Default.Lock) {
            TdtLockToggle(enabled = tdtLockEnabled, onToggle = onTdtLockToggle)
        }

        // Authentication Controls Group
        SettingsGroup(title = "Authentication", icon = Icons.Default.Security) {
            AuthenticationScreen(viewModel = authenticationViewModel)
        }

        // Enrollment Controls Group
        SettingsGroup(title = "Enrollment", icon = Icons.Default.PersonAdd) {
            EnrollmentScreen(viewModel = enrollmentViewModel, targetSamples = targetSamples)
        }

        // Evaluation Section Group
        SettingsGroup(title = "Evaluation", icon = Icons.Default.Analytics) {
            EvaluationScreen(viewModel = evaluationViewModel)
        }
    }
}

@Composable
fun TdtLockToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = "TDT Auto-Lock",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                    text =
                            if (enabled) "Device will lock when trust drops below threshold"
                            else "Auto-lock is disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
fun SettingsGroup(title: String, icon: ImageVector, content: @Composable () -> Unit) {
    OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            colors =
                    CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                    )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                )
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
            content()
        }
    }
}
