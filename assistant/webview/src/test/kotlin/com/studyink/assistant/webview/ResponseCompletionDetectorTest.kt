package com.studyink.assistant.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseCompletionDetectorTest {
    private val before = snapshot(count = 1, text = "old")

    @Test
    fun newResponseUsesCountHashOrTextChange() {
        assertTrue(ResponseCompletionDetector.isNew(before, snapshot(count = 2, text = "answer")))
        assertTrue(ResponseCompletionDetector.isNew(before, snapshot(count = 1, text = "changed")))
        assertTrue(
            ResponseCompletionDetector.isNew(
                before,
                snapshot(count = 1, text = "old", hash = "different"),
            )
        )
        assertFalse(ResponseCompletionDetector.isNew(before, before))
    }

    @Test
    fun tinyOrEmptyDomFragmentsAreNotResponses() {
        assertFalse(ResponseCompletionDetector.isNew(before, snapshot(count = 2, text = "")))
        assertFalse(ResponseCompletionDetector.isNew(before, snapshot(count = 2, text = "x")))
    }

    @Test
    fun actionsReadyCompletesImmediately() {
        val current = snapshot(count = 2, text = "answer", actions = true)
        assertEquals(
            ChatGptCompletion.ACTIONS_READY,
            ResponseCompletionDetector.completion(current, true, 0, 4_000),
        )
    }

    @Test
    fun stableTextCompletesOnlyWhenNotUploadingOrGenerating() {
        assertEquals(
            ChatGptCompletion.TEXT_STABLE,
            ResponseCompletionDetector.completion(snapshot(2, "answer"), true, 4_000, 4_000),
        )
        assertNull(
            ResponseCompletionDetector.completion(
                snapshot(2, "answer", stop = true),
                true,
                8_000,
                4_000,
            )
        )
        assertNull(
            ResponseCompletionDetector.completion(
                snapshot(2, "answer", uploading = true),
                true,
                8_000,
                4_000,
            )
        )
        assertNull(
            ResponseCompletionDetector.completion(snapshot(2, "answer"), false, 8_000, 4_000)
        )
    }

    @Test
    fun partialResponseRequiresANewAnswerAndPrefersPortableMarkdown() {
        assertEquals("", preferredPartialResponse(false, "old markdown", "old text"))
        assertEquals(
            "답은 ${'$'}\\frac{1}{2}${'$'}",
            preferredPartialResponse(true, "답은 ${'$'}\\frac{1}{2}${'$'}", "답은 1/2"),
        )
        assertEquals("새 답변", preferredPartialResponse(true, "", "새 답변"))
    }

    private fun snapshot(
        count: Int,
        text: String,
        hash: String = "${text.length}:$text",
        actions: Boolean = false,
        stop: Boolean = false,
        uploading: Boolean = false,
    ) = ResponseSnapshot(
        assistantCount = count,
        text = text,
        hash = hash,
        actionsReady = actions,
        stopVisible = stop,
        uploadingVisible = uploading,
    )
}
