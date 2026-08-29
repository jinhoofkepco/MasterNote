package com.studyink.assistant.webview

internal data class ResponseSnapshot(
    val assistantCount: Int,
    val text: String,
    val hash: String,
    val actionsReady: Boolean,
    val stopVisible: Boolean,
    val uploadingVisible: Boolean,
)

internal object ResponseCompletionDetector {
    fun isNew(previous: ResponseSnapshot, current: ResponseSnapshot): Boolean {
        if (current.text.length < MIN_RESPONSE_CHARS) return false
        return current.assistantCount > previous.assistantCount ||
            (current.hash.isNotBlank() && current.hash != previous.hash) ||
            current.text != previous.text
    }

    fun completion(
        snapshot: ResponseSnapshot,
        hasNewResponse: Boolean,
        quietMs: Long,
        stableMs: Long,
    ): ChatGptCompletion? {
        if (!hasNewResponse || snapshot.uploadingVisible) return null
        if (snapshot.actionsReady) return ChatGptCompletion.ACTIONS_READY
        if (quietMs >= stableMs && !snapshot.stopVisible) return ChatGptCompletion.TEXT_STABLE
        return null
    }

    private const val MIN_RESPONSE_CHARS = 2
}
