package com.ca.authframework.features.dashboard

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    @SuppressLint("StaticFieldLeak") // WebView holds context, but here it's Application context
    private var webView: WebView? = null

    fun getWebView(): WebView {
        if (webView == null) {
            webView =
                    WebView(getApplication()).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadsImagesAutomatically = true
                            mixedContentMode =
                                    android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        if (request?.url != null) {
                                            view?.loadUrl(request.url.toString())
                                            return true
                                        }
                                        return false
                                    }
                                }
                        loadUrl("https://www.google.com/")
                    }
        }
        return webView!!
    }

    override fun onCleared() {
        super.onCleared()
        webView?.destroy()
        webView = null
    }
}
