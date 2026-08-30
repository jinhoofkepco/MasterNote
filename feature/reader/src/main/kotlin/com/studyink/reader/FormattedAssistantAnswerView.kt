package com.studyink.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.studyink.assistant.core.TeacherGptAnswerFormat
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Read-only, offline Markdown + TeX display for one saved assistant answer.
 *
 * This view intentionally owns exactly one WebView at a time. The owner should scope it to an
 * opened answer detail, forward host pause/resume, and call [destroyRenderer] when that detail is
 * dismissed. It does not mutate or persist the supplied answer.
 */
class FormattedAssistantAnswerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val fallbackText = TextView(context).apply {
        val horizontal = dp(18)
        val vertical = dp(16)
        setPadding(horizontal, vertical, horizontal, dp(48))
        setTextColor(if (isNightMode()) Color.rgb(238, 240, 233) else Color.rgb(32, 33, 31))
        textSize = 17f
        setLineSpacing(0f, 1.35f)
        setTextIsSelectable(true)
    }
    private val fallbackScroll = ScrollView(context).apply {
        isFillViewport = true
        visibility = View.GONE
        setBackgroundColor(if (isNightMode()) Color.rgb(23, 25, 22) else Color.rgb(255, 253, 248))
        addView(
            fallbackText,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private var webView: WebView? = null
    private var fallbackAnswer = ""
    private var hostPaused = false
    @Volatile
    private var destroyed = false
    @Volatile
    private var renderGeneration = 0L

    init {
        clipToPadding = false
        addView(fallbackScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        runCatching { createWebView() }
    }

    /** Replaces the currently displayed answer. Must be called on the main thread. */
    fun render(
        text: String,
        format: TeacherGptAnswerFormat = TeacherGptAnswerFormat.MARKDOWN_TEX,
    ) {
        checkMainThread()
        check(!destroyed) { "FormattedAssistantAnswerView was already destroyed" }
        fallbackAnswer = FormattedAssistantAnswerDocument.fallbackText(text)
        val generation = renderGeneration + 1L
        renderGeneration = generation
        webView?.visibility = View.GONE
        fallbackText.text = "답변을 정리하는 중…"
        fallbackScroll.visibility = View.VISIBLE
        DOCUMENT_EXECUTOR.execute {
            val document = runCatching { FormattedAssistantAnswerDocument.build(text, format) }
            post {
                if (destroyed || generation != renderGeneration) return@post
                document.onSuccess { html ->
                    val renderer = webView ?: runCatching { createWebView() }.getOrElse {
                        showFallback()
                        return@onSuccess
                    }
                    fallbackScroll.visibility = View.GONE
                    renderer.visibility = View.VISIBLE
                    runCatching {
                        renderer.loadDataWithBaseURL(
                            FormattedAssistantAnswerDocument.LOCAL_BASE_URL,
                            html,
                            HTML_MIME_TYPE,
                            UTF_8,
                            null,
                        )
                        if (hostPaused) renderer.onPause()
                    }.onFailure { showFallback() }
                }.onFailure { showFallback() }
            }
        }
    }

    /** Mirrors the containing Activity/Fragment resume without globally pausing other WebViews. */
    fun onHostResume() {
        checkMainThread()
        if (destroyed) return
        hostPaused = false
        if (isAttachedToWindow && windowVisibility == View.VISIBLE) webView?.onResume()
    }

    /** Mirrors the containing Activity/Fragment pause without globally pausing other WebViews. */
    fun onHostPause() {
        checkMainThread()
        hostPaused = true
        webView?.onPause()
    }

    /** Permanently releases this detail view's renderer. The instance cannot be rendered again. */
    fun destroyRenderer() {
        checkMainThread()
        if (destroyed) return
        destroyed = true
        renderGeneration += 1L
        disposeWebView()
        fallbackAnswer = ""
        fallbackText.text = ""
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!hostPaused && windowVisibility == View.VISIBLE) webView?.onResume()
    }

    override fun onDetachedFromWindow() {
        webView?.onPause()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (destroyed) return
        if (visibility == View.VISIBLE && !hostPaused) webView?.onResume() else webView?.onPause()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun createWebView(): WebView {
        checkMainThread()
        check(!destroyed) { "FormattedAssistantAnswerView was already destroyed" }
        webView?.let { return it }
        val renderer = WebView(context).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.TRANSPARENT)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            webViewClient = LocalAssetClient(
                assets = context.applicationContext.assets,
                onRendererGone = { failedView -> handleRendererGone(failedView) },
                onMainFrameFailure = { showFallback() },
            )
            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                blockNetworkLoads = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                domStorageEnabled = false
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_DEFAULT
                loadsImagesAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = false
                offscreenPreRaster = false
                defaultTextEncodingName = UTF_8
            }
            removeJavascriptInterface("searchBoxJavaBridge_")
            removeJavascriptInterface("accessibility")
            removeJavascriptInterface("accessibilityTraversal")
        }
        webView = renderer
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return renderer
    }

    private fun handleRendererGone(failedView: WebView) {
        post {
            if (webView !== failedView || destroyed) return@post
            disposeWebView()
            showFallback()
        }
    }

    private fun showFallback() {
        if (destroyed) return
        webView?.visibility = View.GONE
        fallbackText.text = fallbackAnswer
        fallbackScroll.scrollTo(0, 0)
        fallbackScroll.visibility = View.VISIBLE
    }

    private fun disposeWebView() {
        val renderer = webView ?: return
        webView = null
        (renderer.parent as? ViewGroup)?.removeView(renderer)
        renderer.stopLoading()
        renderer.webViewClient = WebViewClient()
        renderer.removeAllViews()
        renderer.destroy()
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "FormattedAssistantAnswerView must be used on the main thread"
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun isNightMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private class LocalAssetClient(
        private val assets: AssetManager,
        private val onRendererGone: (WebView) -> Unit,
        private val onMainFrameFailure: () -> Unit,
    ) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?,
        ): WebResourceResponse {
            val uri = request?.url ?: return blockedResponse()
            val relativePath = allowedAssetPath(uri) ?: return blockedResponse()
            return runCatching {
                val mimeType = when {
                    relativePath.endsWith(".css") -> "text/css"
                    relativePath.endsWith(".js") -> "application/javascript"
                    relativePath.endsWith(".woff2") -> "font/woff2"
                    else -> "application/octet-stream"
                }
                WebResourceResponse(
                    mimeType,
                    if (mimeType.startsWith("text/") || mimeType.contains("javascript")) UTF_8 else null,
                    HTTP_OK,
                    "OK",
                    mapOf(
                        "Cache-Control" to "public, max-age=31536000, immutable",
                        "X-Content-Type-Options" to "nosniff",
                    ),
                    assets.open("gpt_answer/$relativePath", AssetManager.ACCESS_STREAMING),
                )
            }.getOrElse { blockedResponse() }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame == true) onMainFrameFailure()
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            view?.let(onRendererGone)
            return true
        }

        private fun allowedAssetPath(uri: Uri): String? {
            if (uri.scheme != "https" || uri.host != LOCAL_HOST || uri.port != -1) return null
            val segments = uri.pathSegments ?: return null
            if (segments.size < 3 || segments.first() != LOCAL_DIRECTORY ||
                segments[1] != ASSET_VERSION
            ) {
                return null
            }
            val relative = segments.drop(2).joinToString("/")
            return when {
                relative in STATIC_ASSETS -> relative
                FONT_ASSET.matches(relative) -> relative
                else -> null
            }
        }

        private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            UTF_8,
            HTTP_FORBIDDEN,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

        companion object {
            private const val LOCAL_HOST = "appassets.androidplatform.net"
            private const val LOCAL_DIRECTORY = "gpt-answer"
            private const val ASSET_VERSION = "katex-0.18.1-r1"
            private val STATIC_ASSETS = setOf(
                "katex.min.css",
                "katex.min.js",
                "reader.css",
                "renderer.js",
            )
            private val FONT_ASSET = Regex("fonts/KaTeX_[A-Za-z0-9_-]+\\.woff2")
        }
    }

    private companion object {
        const val HTML_MIME_TYPE = "text/html"
        const val UTF_8 = "UTF-8"
        const val HTTP_OK = 200
        const val HTTP_FORBIDDEN = 403
        val DOCUMENT_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "MasterNote-GptAnswer").apply { isDaemon = true }
        }
    }
}
