package com.studyink.lab.assistantwebview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object AssistantHostPolicy {
    private val hosts = setOf("chatgpt.com", "www.chatgpt.com")
    fun isAllowed(uri: Uri): Boolean = isAllowed(uri.toString())
    fun isAllowed(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.host?.lowercase() in hosts && uri.userInfo == null
    }.getOrDefault(false)
}

/** Lab-only WebView. It exposes no JavaScript bridge and is not registered in main navigation. */
class SecureAssistantWebView(context: Context) : WebView(context) {
    init { configure() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !AssistantHostPolicy.isAllowed(request.url)
        }
    }

    fun open(url: Uri) {
        require(AssistantHostPolicy.isAllowed(url))
        loadUrl(url.toString())
    }
}
