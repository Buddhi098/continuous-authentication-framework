package com.ca.authframework.features.dashboard

import androidx.compose.runtime.Composable

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
        WebViewScreen(webView = viewModel.getWebView())
}
