package com.ca.authframework.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ca.authframework.components.StatusBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(status: String, score: String , currentAuthPercentage: String) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Continuous Auth Demo", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge("Status", status)
                    StatusBadge("Score", score)
                    StatusBadge("AC" , currentAuthPercentage)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}
