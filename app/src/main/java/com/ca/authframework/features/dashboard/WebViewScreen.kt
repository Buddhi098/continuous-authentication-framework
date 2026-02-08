package com.ca.authframework.features.dashboard

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(webView: WebView) {
    // Handle back button press
    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    AndroidView(
            factory = {
                // Check if the WebView already has a parent (from a previous composition)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            modifier = Modifier.fillMaxSize()
    )
}
