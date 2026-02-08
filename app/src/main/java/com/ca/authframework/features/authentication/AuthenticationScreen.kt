package com.ca.authframework.features.authentication

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ca.authframework.core.ui.theme.ErrorDark
import com.ca.authframework.core.ui.theme.SuccessDark

// AuthenticationViewModel is in the same package

@Composable
fun AuthenticationScreen(viewModel: AuthenticationViewModel) {

        // -----------------------------
        // Collect ViewModel state
        // -----------------------------
        val isCheckpointExists by viewModel.isCheckpointExists.collectAsState()
        val isRunning by viewModel::authenticationRunning
        val lastAuthResult by viewModel::lastAuthResult

        Column(
                modifier = Modifier.fillMaxWidth().padding(0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

                // --- Status Header ---
                Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                                CardDefaults.cardColors(
                                        containerColor =
                                                if (isCheckpointExists)
                                                        SuccessDark.copy(alpha = 0.1f)
                                                else ErrorDark.copy(alpha = 0.1f)
                                )
                ) {
                        Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                        ) {
                                Icon(
                                        imageVector =
                                                if (isCheckpointExists) Icons.Default.CheckCircle
                                                else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isCheckpointExists) SuccessDark else ErrorDark,
                                        modifier = Modifier.size(20.dp).padding(end = 8.dp)
                                )
                                Text(
                                        text =
                                                if (isCheckpointExists) "Model Ready"
                                                else "Model Not Found",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isCheckpointExists) SuccessDark else ErrorDark,
                                        fontWeight = FontWeight.Bold
                                )
                        }
                }

                // --- Main Pulse/Status Display ---
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                        val primaryColor = MaterialTheme.colorScheme.primary

                        // Determine detailed status color and icon
                        val (statusColor, statusIcon) =
                                when {
                                        !isRunning ->
                                                MaterialTheme.colorScheme.surfaceVariant to
                                                        Icons.Default.Lock
                                        lastAuthResult?.isAuthenticated == true ->
                                                SuccessDark to Icons.Default.Fingerprint
                                        lastAuthResult?.isAuthenticated == false ->
                                                ErrorDark to Icons.Default.Warning
                                        else -> primaryColor to Icons.Default.Fingerprint
                                }

                        // Pulse Animation
                        if (isRunning) {
                                val infiniteTransition = rememberInfiniteTransition()
                                val scale by
                                        infiniteTransition.animateFloat(
                                                initialValue = 1f,
                                                targetValue = 1.4f,
                                                animationSpec =
                                                        infiniteRepeatable(
                                                                animation = tween(1500),
                                                                repeatMode = RepeatMode.Restart
                                                        )
                                        )
                                val alpha by
                                        infiniteTransition.animateFloat(
                                                initialValue = 0.5f,
                                                targetValue = 0f,
                                                animationSpec =
                                                        infiniteRepeatable(
                                                                animation = tween(1500),
                                                                repeatMode = RepeatMode.Restart
                                                        )
                                        )

                                Box(
                                        modifier =
                                                Modifier.size(
                                                                100.dp
                                                        ) // Base size matches inner circle
                                                        .scale(scale)
                                                        .clip(CircleShape)
                                                        .background(statusColor.copy(alpha = alpha))
                                )
                        }

                        // Central Icon Circle
                        Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                border =
                                        androidx.compose.foundation.BorderStroke(4.dp, statusColor),
                                modifier = Modifier.size(120.dp),
                                shadowElevation = 8.dp
                        ) {
                                Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                                imageVector = statusIcon,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = statusColor
                                        )
                                }
                        }
                }

                // --- Metrics Grid ---
                if (isRunning && lastAuthResult != null) {
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                                MetricCard(
                                        label = "Score",
                                        value = String.format("%.3f", lastAuthResult!!.score),
                                        modifier = Modifier.weight(1f)
                                )
                                MetricCard(
                                        label = "Confidence",
                                        value =
                                                lastAuthResult!!.authPercentage?.let {
                                                        "%.1f%%".format(it)
                                                }
                                                        ?: "N/A",
                                        modifier = Modifier.weight(1f)
                                )
                        }
                } else {
                        Text(
                                text = "Start authentication to see live metrics",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }

                // --- Controls ---
                Button(
                        onClick = {
                                if (isRunning) viewModel.stopAuthentication()
                                else viewModel.startAuthentication()
                        },
                        enabled = isCheckpointExists,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                if (isRunning) ErrorDark
                                                else MaterialTheme.colorScheme.primary
                                ),
                        shape = RoundedCornerShape(16.dp)
                ) {
                        Text(
                                text =
                                        if (isRunning) "STOP AUTHENTICATION"
                                        else "START AUTHENTICATION",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                        )
                }
        }
}

@Composable
fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
        ElevatedCard(
                modifier = modifier,
                colors =
                        CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                        )
        ) {
                Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        Text(
                                text = value,
                                style = MaterialTheme.typography.titleLarge,
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
}
