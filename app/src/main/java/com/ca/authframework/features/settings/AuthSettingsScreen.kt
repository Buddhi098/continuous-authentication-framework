package com.ca.authframework.features.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
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

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun AuthSettingsScreen(
    enrollmentViewModel: EnrollmentViewModel,
    authenticationViewModel: AuthenticationViewModel,
    evaluationViewModel: EvaluationViewModel,
    targetSamples: Int
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {

        // Authentication Controls Group
        SettingsGroup(title = "Authentication", icon = Icons.Default.Security) {
            AuthenticationScreen(viewModel = authenticationViewModel)
        }

        // Enrollment Controls Group
        SettingsGroup(title = "Enrollment", icon = Icons.Default.PersonAdd) {
            EnrollmentScreen(
                viewModel = enrollmentViewModel,
                targetSamples = targetSamples
            )
        }

        // Evaluation Section Group
        SettingsGroup(title = "Evaluation", icon = Icons.Default.Analytics) {
            EvaluationScreen(viewModel = evaluationViewModel)
        }
    }
}

@Composable
fun SettingsGroup(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
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