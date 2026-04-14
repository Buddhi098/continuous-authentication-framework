package com.ca.authframework.features.dashboard

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebViewScreen(webView: WebView) {
    // Handle hardware back button press
    BackHandler(enabled = webView.canGoBack()) { webView.goBack() }

    // Wrap in a Column to stack the button and the WebView vertically
    Column(modifier = Modifier.fillMaxSize()) {

        // VISIBLE UI BACK BUTTON
        Button(
            onClick = {
                if (webView.canGoBack()) {
                    webView.goBack()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Text("Go Back")
        }

        // WEBVIEW CONTAINER
        AndroidView(
            factory = {
                // Check if the WebView already has a parent (from a previous composition)
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView
            },
            // Use weight(1f) so the WebView takes up all remaining space below the button
            modifier = Modifier.weight(1f)
        )
    }
}