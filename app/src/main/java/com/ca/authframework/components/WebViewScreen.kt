package com.ca.authframework.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "WebViewScreen"

@SuppressLint("SetJavaScriptEnabled") // Suppress lint for setting JS enabled
@Composable
fun WebViewScreen(url: String) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                // --- Settings: Use apply block for cleaner configuration ---
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadsImagesAutomatically = true
                    // Best practice: Use the recommended mixed content setting for security
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    // Optional: Improve performance/UX
                    cacheMode = WebSettings.LOAD_DEFAULT // Load from cache if available
                    // Enable viewport meta tag support (essential for mobile layouts)
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                // --- WebViewClient: Handle URL Loading ---
                webViewClient = object : WebViewClient() {
                    /**
                     * Deprecated in API 24 (Android 7.0), but still used for compatibility.
                     * This method handles redirects/new links *before* the request is made.
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        // The correct way to handle this is to let the WebView load the URL.
                        // Returning 'false' means the WebView should handle the request itself,
                        // which is the desired behavior for keeping navigation inside the WebView.
                        // Returning 'true' here tells the host app (you) that you handled it,
                        // and since you call view.loadUrl, you are essentially handling it.
                        // The simplified (and often preferred) logic is:
                        if (request?.url != null) {
                            view?.loadUrl(request.url.toString())
                            return true // We handled the loading manually.
                        }
                        return false // Let the WebView handle other cases.
                    }

                    // You might need to override the older signature for older Android versions, though WebResourceRequest is better
                    // @Suppress("DEPRECATION")
                    // override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    //    view?.loadUrl(url.toString())
                    //    return true
                    // }
                }

                Log.d(TAG, "Loading URL: $url")
                loadUrl(url)
            }
        },
        // The key parameter ensures that a new WebView is created only when the URL changes.
        // If the URL is meant to be constant, you can omit the key.
        // If it changes, this helps re-initialize the view.
        // key = url,
        modifier = Modifier.fillMaxSize()
    )
}