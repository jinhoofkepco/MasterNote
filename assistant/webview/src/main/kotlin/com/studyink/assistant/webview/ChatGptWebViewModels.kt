package com.studyink.assistant.webview

/** A request sent through the signed-in ChatGPT WebView session. */
data class ChatGptQuery(
    val prompt: String,
    val imagePng: ByteArray? = null,
) {
    init {
        require(prompt.isNotBlank()) { "prompt must not be blank" }
    }
}

data class ChatGptResult(
    val text: String,
    val html: String? = null,
    val assistantMessageCount: Int,
    val completion: ChatGptCompletion,
    val textFormat: ChatGptTextFormat = ChatGptTextFormat.PLAIN_TEXT,
)

enum class ChatGptTextFormat {
    PLAIN_TEXT,
    MARKDOWN_TEX,
}

enum class ChatGptCompletion {
    ACTIONS_READY,
    TEXT_STABLE,
    MANUAL,
}

data class ChatGptSelectorStatus(
    val inputSelector: String?,
    val sendSelector: String?,
) {
    val automationReady: Boolean
        get() = inputSelector != null && sendSelector != null
}

enum class ChatGptWebViewState {
    IDLE,
    LOADING,
    READY,
    SENDING,
    WAITING_FOR_RESPONSE,
    RECOVERING_RENDERER,
    HIDDEN,
    DESTROYED,
}

sealed interface ChatGptManualFallback {
    /**
     * The WebView remains visible. The host can expose [query.prompt] for copy/paste and let the
     * user submit it in ChatGPT. If the DOM becomes readable again, the pending query still
     * completes automatically.
     */
    data class Send(
        val query: ChatGptQuery,
        val reason: String,
    ) : ChatGptManualFallback

    /**
     * The host should offer a paste field and pass the copied answer to
     * [ChatGptWebViewController.provideManualResponse].
     */
    data class Response(
        val reason: String,
        val partialText: String,
    ) : ChatGptManualFallback
}

interface ChatGptWebViewListener {
    fun onStateChanged(state: ChatGptWebViewState) = Unit

    fun onManualFallback(fallback: ChatGptManualFallback) = Unit

    fun onNavigationBlocked(url: String) = Unit

    fun onPageError(description: String) = Unit

    fun onRendererRecovered(didCrash: Boolean) = Unit
}

open class ChatGptGatewayException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class ChatGptResponseTimeoutException(
    val partialResponse: String,
) : ChatGptGatewayException("ChatGPT 응답 완료를 제한 시간 안에 확인하지 못했습니다.")

class ChatGptRendererRestartedException :
    ChatGptGatewayException("ChatGPT WebView가 복구되었습니다. 질문을 다시 보내 주세요.")

class ChatGptGatewayDestroyedException :
    ChatGptGatewayException("ChatGPT WebView가 이미 종료되었습니다.")
