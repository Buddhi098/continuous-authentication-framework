package com.ca.authframework.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(0.dp)) {
//        Text("Activity Simulation (WebView)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
        Card(modifier = Modifier.fillMaxSize(), elevation = CardDefaults.cardElevation(0.dp)) {
            WebViewScreen(url = "https://www.google.com/")
        }
    }
}
