package com.ca.authframework.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ca.authframework.core.ui.theme.AuthenticatedColor
import com.ca.authframework.core.ui.theme.NeutralColor
import com.ca.authframework.core.ui.theme.RejectedColor

@Composable
fun StatusBadge(label: String, value: String, modifier: Modifier = Modifier) {
    // Determine badge colors based on authentication status
    val (backgroundColor, contentColor) =
        when {
            value.equals("Authenticated", ignoreCase = true) -> {
                AuthenticatedColor.copy(alpha = 0.15f) to AuthenticatedColor
            }
            value.equals("Rejected", ignoreCase = true) -> {
                RejectedColor.copy(alpha = 0.15f) to RejectedColor
            }
            else -> {
                NeutralColor.copy(alpha = 0.15f) to NeutralColor
            }
        }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp // reduced font size
            ),
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}