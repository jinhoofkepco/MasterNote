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

/** Preserves pasted/timeout TeX and obvious Markdown instead of flattening it as plain text. */
fun inferChatGptTextFormat(text: String): ChatGptTextFormat {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val delimitedMath = hasDelimitedPair(normalized, "\\(", "\\)") ||
        hasDelimitedPair(normalized, "\\[", "\\]") ||
        hasUnescapedDollarMath(normalized)
    val markdownBlock = normalized.lineSequence().any { line ->
        val trimmed = line.trimStart()
        line.matches(Regex("^\\s{0,3}#{1,6}\\s+.+$")) ||
            line.matches(Regex("^\\s*[-+*]\\s+.+$")) ||
            line.matches(Regex("^\\s*\\d+[.)]\\s+.+$")) ||
            trimmed.startsWith("```") || trimmed.startsWith("~~~") ||
            trimmed.startsWith("> ")
    }
    return if (delimitedMath || markdownBlock) {
        ChatGptTextFormat.MARKDOWN_TEX
    } else {
        ChatGptTextFormat.PLAIN_TEXT
    }
}

private fun hasDelimitedPair(text: String, open: String, close: String): Boolean {
    var start = text.indexOf(open)
    while (start >= 0) {
        val end = text.indexOf(close, start + open.length)
        if (end > start + open.length && text.substring(start + open.length, end).isNotBlank()) {
            return true
        }
        start = text.indexOf(open, start + open.length)
    }
    return false
}

private fun hasUnescapedDollarMath(text: String): Boolean {
    var cursor = 0
    while (cursor < text.length) {
        if (text[cursor] != '$' || text.isEscapedAt(cursor)) {
            cursor += 1
            continue
        }
        val delimiterLength = if (text.getOrNull(cursor + 1) == '$') 2 else 1
        val delimiter = "$".repeat(delimiterLength)
        var end = text.indexOf(delimiter, cursor + delimiterLength)
        while (end >= 0 && text.isEscapedAt(end)) {
            end = text.indexOf(delimiter, end + delimiterLength)
        }
        if (end > cursor + delimiterLength &&
            text.substring(cursor + delimiterLength, end).isNotBlank()
        ) {
            return true
        }
        cursor += delimiterLength
    }
    return false
}

private fun String.isEscapedAt(index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashes += 1
        cursor -= 1
    }
    return slashes % 2 == 1
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
