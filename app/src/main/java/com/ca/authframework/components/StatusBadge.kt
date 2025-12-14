package com.ca.authframework.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StatusBadge(
    label: String,
    value: String
) {
    val isAuthenticated = value.equals("Authenticated", ignoreCase = true)

    val backgroundColor = if (isAuthenticated) {
        MaterialTheme.colorScheme.tertiaryContainer   // green-ish
    } else {
        MaterialTheme.colorScheme.errorContainer     // red
    }

    val contentColor = if (isAuthenticated) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = backgroundColor,
        contentColor = contentColor
    ) {
        Text(
            text = "$label: $value",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
