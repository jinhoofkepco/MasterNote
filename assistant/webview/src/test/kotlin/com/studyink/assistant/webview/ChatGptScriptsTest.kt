package com.studyink.assistant.webview

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptScriptsTest {
    @Test
    fun imageQueryRequiresAttachmentEvidenceBeforeClickingSend() {
        val script = ChatGptScripts.inject(
            prompt = "문제를 풀어줘",
            imageBase64 = "AA==",
            token = "test-token",
        )

        assertTrue(script.contains("attachmentEvidence() > attachmentBaseline"))
        assertTrue(script.contains("hasPrompt(activeComposer) && imageConfirmed"))
        assertTrue(script.contains("image attachment not confirmed"))
    }

    @Test
    fun injectionQuotesSelectorsAsJavaScriptStrings() {
        val script = ChatGptScripts.inject(
            prompt = "문제를 풀어줘",
            imageBase64 = null,
            token = "test-token",
        )

        assertTrue(script.contains("pick(${JSONObject.quote(ChatGptScripts.INPUT)})"))
        assertTrue(script.contains("pick(${JSONObject.quote(ChatGptScripts.SEND)})"))
    }
}
