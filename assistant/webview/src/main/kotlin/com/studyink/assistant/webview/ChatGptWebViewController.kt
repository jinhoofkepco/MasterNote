package com.studyink.assistant.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min

/**
 * Owns one in-app ChatGPT [WebView]. Add [view] to the reader's UI and call [destroy] when its
 * lifecycle owner is destroyed. This class does not read or write any MasterNote data store.
 */
class ChatGptWebViewController(
    context: Context,
    listener: ChatGptWebViewListener = object : ChatGptWebViewListener {},
) {
    val view: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.rgb(10, 13, 16))
        visibility = View.GONE
    }

    var listener: ChatGptWebViewListener = listener

    val state: ChatGptWebViewState
        get() = currentState

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()
    private var webView: WebView? = null
    private var currentState = ChatGptWebViewState.IDLE
    private var initialLoadRequested = false
    private var rendererGeneration = 0L
    private var queryInProgress = false
    private var destroyed = false

    @Volatile
    private var manualResponse: CompletableDeferred<ChatGptResult>? = null

    /** Displays the retained WebView and loads ChatGPT on first use. */
    fun open() {
        runOnMain { openOnMain() }
    }

    /** Hides the WebView without discarding the signed-in cookie/session. */
    fun hide() {
        runOnMain {
            if (destroyed) return@runOnMain
            webView?.onPause()
            view.visibility = View.GONE
            cookieManager.flush()
            setState(ChatGptWebViewState.HIDDEN)
        }
    }

    /** Reloads only the fixed ChatGPT entry URL. */
    fun reload() {
        runOnMain {
            checkNotDestroyed()
            val current = ensureWebView()
            initialLoadRequested = true
            setState(ChatGptWebViewState.LOADING)
            current.loadUrl(CHATGPT_URL)
        }
    }

    /** Returns the currently matched selectors, mainly for diagnostics and graceful UI fallback. */
    suspend fun inspectSelectors(): ChatGptSelectorStatus = withContext(Dispatchers.Main.immediate) {
        checkNotDestroyed()
        openOnMain()
        if (!isAutomationOrigin()) return@withContext ChatGptSelectorStatus(null, null)
        val raw = evaluateJavascript(ChatGptScripts.selectorStatus())
        parseSelectorStatus(raw) ?: ChatGptSelectorStatus(null, null)
    }

    /**
     * Sends [query] and waits for a new completed assistant response. Only one query may be active.
     * If ChatGPT changes its DOM, [ChatGptWebViewListener.onManualFallback] is invoked while the
     * same visible WebView remains available for manual input or answer copying.
     */
    suspend fun query(
        query: ChatGptQuery,
        timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    ): ChatGptResult = withContext(Dispatchers.Main.immediate) {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        checkNotDestroyed()
        check(!queryInProgress) { "A ChatGPT query is already in progress" }

        queryInProgress = true
        val manual = CompletableDeferred<ChatGptResult>()
        manualResponse = manual
        var latestText = ""
        var extractionFailures = 0
        var responseFallbackSent = false
        val deadline = elapsedRealtime() + timeoutMs

        try {
            openOnMain()
            ensureAutomationOrigin()
            val expectedGeneration = rendererGeneration
            val composerReady = waitForVisibleComposer(deadline, expectedGeneration)
            ensureRendererGeneration(expectedGeneration)

            val before = readResponseSnapshot()
            if (before == null) {
                notifyManualFallback(
                    ChatGptManualFallback.Response(
                        reason = "기존 응답 기준점을 읽지 못했습니다. 답변을 복사해 직접 붙여넣어 주세요.",
                        partialText = "",
                    )
                )
                responseFallbackSent = true
            }

            if (!composerReady) {
                notifyManualFallback(
                    ChatGptManualFallback.Send(
                        query = query,
                        reason = "ChatGPT 입력창을 자동으로 찾지 못했습니다. 질문을 직접 붙여넣어 주세요.",
                    )
                )
            } else {
                setState(ChatGptWebViewState.SENDING)
                val injection = injectAndWait(query, deadline, expectedGeneration)
                if (!injection.sent) {
                    notifyManualFallback(
                        ChatGptManualFallback.Send(
                            query = query,
                            reason = injection.reason,
                        )
                    )
                }
            }

            setState(ChatGptWebViewState.WAITING_FOR_RESPONSE)
            delayWithinDeadline(RESPONSE_INITIAL_DELAY_MS, deadline)
            var lastChangeAt = elapsedRealtime()

            while (elapsedRealtime() < deadline) {
                if (manual.isCompleted) return@withContext manual.await()
                ensureRendererGeneration(expectedGeneration)

                val snapshot = readResponseSnapshot()
                val now = elapsedRealtime()
                if (snapshot == null) {
                    extractionFailures += 1
                    if (extractionFailures >= EXTRACTION_FAILURES_BEFORE_FALLBACK &&
                        !responseFallbackSent
                    ) {
                        notifyManualFallback(
                            ChatGptManualFallback.Response(
                                reason = "ChatGPT 답변을 자동으로 읽지 못했습니다. 답변을 복사해 직접 붙여넣어 주세요.",
                                partialText = latestText,
                            )
                        )
                        responseFallbackSent = true
                    }
                } else {
                    extractionFailures = 0
                    if (snapshot.text != latestText) {
                        latestText = snapshot.text
                        lastChangeAt = now
                    }
                    val hasNewResponse = before?.let {
                        ResponseCompletionDetector.isNew(it, snapshot)
                    } ?: false
                    val completion = ResponseCompletionDetector.completion(
                        snapshot = snapshot,
                        hasNewResponse = hasNewResponse,
                        quietMs = now - lastChangeAt,
                        stableMs = RESPONSE_STABLE_MS,
                    )
                    if (completion != null) {
                        val html = readLatestResponseHtml().orEmpty().ifBlank { null }
                        setState(ChatGptWebViewState.READY)
                        return@withContext ChatGptResult(
                            text = snapshot.text,
                            html = html,
                            assistantMessageCount = snapshot.assistantCount,
                            completion = completion,
                        )
                    }
                }
                delayWithinDeadline(RESPONSE_POLL_MS, deadline)
            }

            if (manual.isCompleted) return@withContext manual.await()
            if (!responseFallbackSent) {
                notifyManualFallback(
                    ChatGptManualFallback.Response(
                        reason = "응답 완료를 확인하지 못했습니다. 완성된 답변을 복사해 직접 붙여넣어 주세요.",
                        partialText = latestText,
                    )
                )
            }
            throw ChatGptResponseTimeoutException(latestText)
        } finally {
            if (manualResponse === manual) manualResponse = null
            queryInProgress = false
            if (!destroyed &&
                currentState != ChatGptWebViewState.HIDDEN &&
                currentState != ChatGptWebViewState.LOADING &&
                currentState != ChatGptWebViewState.RECOVERING_RENDERER &&
                isAutomationOrigin()
            ) {
                setState(ChatGptWebViewState.READY)
            }
        }
    }

    /** Completes the active request with an answer that the user copied from the visible WebView. */
    fun provideManualResponse(text: String, html: String? = null): Boolean {
        if (text.isBlank()) return false
        val pending = manualResponse ?: return false
        return pending.complete(
            ChatGptResult(
                text = text.trim(),
                html = html?.trim()?.ifBlank { null },
                assistantMessageCount = 0,
                completion = ChatGptCompletion.MANUAL,
            )
        )
    }

    /** Permanently releases this controller's WebView. The controller cannot be reopened. */
    fun destroy() {
        runOnMain {
            if (destroyed) return@runOnMain
            destroyed = true
            manualResponse?.completeExceptionally(ChatGptGatewayDestroyedException())
            manualResponse = null
            view.removeAllViews()
            webView?.apply {
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
            webView = null
            cookieManager.flush()
            setState(ChatGptWebViewState.DESTROYED)
        }
    }

    private fun openOnMain() {
        checkNotDestroyed()
        val current = ensureWebView()
        view.visibility = View.VISIBLE
        current.onResume()
        if (!initialLoadRequested) {
            initialLoadRequested = true
            setState(ChatGptWebViewState.LOADING)
            current.loadUrl(CHATGPT_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val next = WebView(view.context).apply {
            setBackgroundColor(Color.rgb(10, 13, 16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                setGeolocationEnabled(false)
                safeBrowsingEnabled = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            webViewClient = secureWebViewClient()
            webChromeClient = secureChromeClient()
        }
        cookieManager.setAcceptCookie(true)
        // ChatGPT's interactive sign-in can cross its identity provider before returning to the
        // exact allowlisted ChatGPT host. Keep the user's normal WebView cookie session, matching
        // the proven GPTOverlay behavior, while main-frame navigation remains tightly allowlisted.
        cookieManager.setAcceptThirdPartyCookies(next, true)
        view.removeAllViews()
        view.addView(next)
        webView = next
        return next
    }

    private fun secureWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            blockIfDisallowed(request.url)

        @Deprecated("Compatibility override")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            blockIfDisallowed(Uri.parse(url))

        override fun onPageFinished(view: WebView, url: String) {
            if (!ChatGptNavigationPolicy.allows(url)) {
                view.stopLoading()
                notifyNavigationBlocked(url)
                return
            }
            cookieManager.flush()
            if (!queryInProgress && ChatGptNavigationPolicy.allowsAutomation(url)) {
                setState(ChatGptWebViewState.READY)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) notifyPageError(error.description.toString())
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame) {
                notifyPageError("ChatGPT HTTP ${errorResponse.statusCode}")
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            handler.cancel()
            notifyPageError("ChatGPT SSL 오류(${error.primaryError})")
        }

        override fun onRenderProcessGone(
            rendererView: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            rendererGeneration += 1L
            setState(ChatGptWebViewState.RECOVERING_RENDERER)
            view.removeView(rendererView)
            rendererView.destroy()
            webView = null
            initialLoadRequested = false
            val recovered = ensureWebView()
            initialLoadRequested = true
            recovered.loadUrl(CHATGPT_URL)
            runCatching { listener.onRendererRecovered(detail.didCrash()) }
            return true
        }
    }

    private fun secureChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message?,
        ): Boolean = false

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback,
        ) {
            callback.invoke(origin, false, false)
        }
    }

    private fun blockIfDisallowed(uri: Uri): Boolean {
        val url = uri.toString()
        if (ChatGptNavigationPolicy.allows(url)) return false
        notifyNavigationBlocked(url)
        return true
    }

    private suspend fun waitForVisibleComposer(deadline: Long, generation: Long): Boolean {
        repeat(COMPOSER_WAIT_ATTEMPTS) {
            if (elapsedRealtime() >= deadline) return false
            ensureRendererGeneration(generation)
            ensureAutomationOrigin()
            if (evaluateJavascript(ChatGptScripts.visibleComposer()) == "true") return true
            delayWithinDeadline(COMPOSER_WAIT_DELAY_MS, deadline)
        }
        return false
    }

    private suspend fun injectAndWait(
        query: ChatGptQuery,
        deadline: Long,
        generation: Long,
    ): InjectionOutcome {
        val token = UUID.randomUUID().toString()
        // PNG -> Base64 -> JavaScript creates several temporary copies. Build those copies away
        // from the UI thread so a detailed worksheet crop cannot stall pen/menu input.
        val injectionScript = withContext(Dispatchers.Default) {
            val imageBase64 = query.imagePng?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            ChatGptScripts.inject(query.prompt, imageBase64, token)
        }
        ensureAutomationOrigin()
        val startRaw = evaluateJavascript(injectionScript)
        val start = decodeJavascriptString(startRaw).orEmpty()
        if (start != "started") {
            return InjectionOutcome(false, "질문 자동 입력을 시작하지 못했습니다($start). 직접 붙여넣어 주세요.")
        }

        val sendDeadline = min(deadline, elapsedRealtime() + SEND_TIMEOUT_MS)
        var missingCount = 0
        while (elapsedRealtime() < sendDeadline) {
            ensureRendererGeneration(generation)
            ensureAutomationOrigin()
            val status = parseInjectionStatus(
                evaluateJavascript(ChatGptScripts.injectionStatus(token))
            )
            when (status?.first) {
                "sent" -> return InjectionOutcome(true, "")
                "error" -> return InjectionOutcome(
                    false,
                    when (status.second) {
                        "image attachment not confirmed" ->
                            "문제 이미지 첨부를 확인하지 못해 질문을 보내지 않았습니다. 다시 시도해 주세요."
                        else -> status.second.ifBlank {
                            "질문 자동 전송에 실패했습니다. 직접 붙여넣어 주세요."
                        }
                    },
                )
                "missing", null -> {
                    missingCount += 1
                    if (missingCount >= MAX_MISSING_INJECTION_POLLS) {
                        return InjectionOutcome(
                            false,
                            "질문 전송 상태를 확인하지 못했습니다. 직접 붙여넣어 주세요.",
                        )
                    }
                }
                else -> missingCount = 0
            }
            delayWithinDeadline(INJECTION_POLL_MS, sendDeadline)
        }
        return InjectionOutcome(false, "전송 버튼이 준비되지 않았습니다. 질문을 직접 붙여넣어 주세요.")
    }

    private suspend fun readResponseSnapshot(): ResponseSnapshot? {
        ensureAutomationOrigin()
        val json = decodeJavascriptString(
            evaluateJavascript(ChatGptScripts.responseSnapshot)
        ) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            ResponseSnapshot(
                assistantCount = obj.optInt("assistantCount"),
                text = obj.optString("text"),
                hash = obj.optString("hash"),
                actionsReady = obj.optBoolean("actionsReady"),
                stopVisible = obj.optBoolean("stopVisible"),
                uploadingVisible = obj.optBoolean("uploadingVisible"),
            )
        }.getOrNull()
    }

    private suspend fun readLatestResponseHtml(): String? {
        ensureAutomationOrigin()
        return decodeJavascriptString(evaluateJavascript(ChatGptScripts.latestResponseHtml))
    }

    private suspend fun evaluateJavascript(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            val current = webView
            if (current == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            runCatching {
                current.evaluateJavascript(script) { raw ->
                    if (continuation.isActive) continuation.resume(raw)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun parseSelectorStatus(raw: String?): ChatGptSelectorStatus? {
        val json = decodeJavascriptString(raw) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            ChatGptSelectorStatus(
                inputSelector = obj.optStringOrNull("input"),
                sendSelector = obj.optStringOrNull("send"),
            )
        }.getOrNull()
    }

    private fun parseInjectionStatus(raw: String?): Pair<String, String>? {
        val json = decodeJavascriptString(raw) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            obj.optString("status") to obj.optString("error")
        }.getOrNull()
    }

    private fun decodeJavascriptString(raw: String?): String? {
        if (raw == null || raw == "null" || raw == "undefined") return null
        return runCatching {
            when (val parsed = JSONTokener(raw).nextValue()) {
                JSONObject.NULL -> null
                is String -> parsed
                else -> parsed?.toString()
            }
        }.getOrNull()
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key).trim().ifBlank { null }
    }

    private fun ensureRendererGeneration(expected: Long) {
        if (rendererGeneration != expected) throw ChatGptRendererRestartedException()
    }

    private fun isAutomationOrigin(): Boolean =
        ChatGptNavigationPolicy.allowsAutomation(webView?.url)

    private fun ensureAutomationOrigin() {
        if (!isAutomationOrigin()) {
            throw ChatGptGatewayException(
                "ChatGPT 로그인을 마치고 대화 화면으로 돌아온 뒤 다시 질문해 주세요.",
            )
        }
    }

    private fun checkNotDestroyed() {
        if (destroyed) throw ChatGptGatewayDestroyedException()
    }

    private suspend fun delayWithinDeadline(delayMs: Long, deadline: Long) {
        val remaining = deadline - elapsedRealtime()
        if (remaining > 0L) delay(min(delayMs, remaining))
    }

    private fun notifyManualFallback(fallback: ChatGptManualFallback) {
        runCatching { listener.onManualFallback(fallback) }
    }

    private fun notifyNavigationBlocked(url: String) {
        runCatching { listener.onNavigationBlocked(url) }
    }

    private fun notifyPageError(description: String) {
        runCatching { listener.onPageError(description) }
    }

    private fun setState(next: ChatGptWebViewState) {
        if (currentState == next) return
        currentState = next
        runCatching { listener.onStateChanged(next) }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun elapsedRealtime(): Long = android.os.SystemClock.elapsedRealtime()

    private data class InjectionOutcome(
        val sent: Boolean,
        val reason: String,
    )

    private companion object {
        const val CHATGPT_URL = "https://chatgpt.com/"
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 300_000L
        const val SEND_TIMEOUT_MS = 120_000L
        const val COMPOSER_WAIT_DELAY_MS = 500L
        const val COMPOSER_WAIT_ATTEMPTS = 60
        const val INJECTION_POLL_MS = 500L
        const val MAX_MISSING_INJECTION_POLLS = 3
        const val RESPONSE_INITIAL_DELAY_MS = 1_800L
        const val RESPONSE_POLL_MS = 700L
        const val RESPONSE_STABLE_MS = 4_000L
        const val EXTRACTION_FAILURES_BEFORE_FALLBACK = 3
    }
}
