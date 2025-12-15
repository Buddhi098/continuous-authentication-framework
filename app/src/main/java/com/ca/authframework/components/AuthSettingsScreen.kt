package com.ca.authframework.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ca.authframework.viewmodels.AuthenticationViewModel
import com.ca.authframework.viewmodels.EnrollmentViewModel

@Composable
fun AuthSettingsScreen(
    enrollmentViewModel: EnrollmentViewModel,
    authenticationViewModel: AuthenticationViewModel,
    targetSamples: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Authentication Controls",
                    style = MaterialTheme.typography.titleLarge
                )
                Divider(Modifier.padding(vertical = 8.dp))
                AuthenticationScreen(viewModel = authenticationViewModel)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Enrollment Controls",
                    style = MaterialTheme.typography.titleLarge
                )
                Divider(Modifier.padding(vertical = 8.dp))
                EnrollmentScreen(
                    viewModel = enrollmentViewModel,
                    targetSamples = targetSamples
                )
            }
        }
    }
}

