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

    @Test
    fun responsePollingPreservesLineBreaksWhileHashNormalizesWhitespace() {
        val script = ChatGptScripts.responseSnapshot

        assertTrue(script.contains("const text = preserved"))
        assertTrue(script.contains("hash: hash(norm(text))"))
        assertTrue(!script.contains("extractStudyInkMarkdown(last)"))
    }

    @Test
    fun responseExtractionPrefersTexBeforeRemovingHiddenMathMarkup() {
        val script = ChatGptScripts.latestResponseMarkdown

        assertTrue(script.contains("annotation[encoding='application/x-tex']"))
        assertTrue(script.contains("[data-tex],[data-latex],[data-math],[data-formula]"))
        assertTrue(script.indexOf("replaceStudyInkMath(clone)") < script.indexOf("removeStudyInkArtifacts(clone)"))
        assertTrue(script.contains("STUDYINK_DOLLAR + STUDYINK_DOLLAR"))
        assertTrue(script.contains("node.closest(\"pre,code\")"))
    }

    @Test
    fun responseExtractionKeepsSemanticMarkdownBlocks() {
        val script = ChatGptScripts.latestResponseMarkdown

        assertTrue(script.contains("studyInkList(node"))
        assertTrue(script.contains("studyInkTable(node)"))
        assertTrue(script.contains("const fence = studyInkFence(raw)"))
        assertTrue(script.contains("\"#\".repeat(level)"))
        assertTrue(script.contains("return \"> \" + line"))
    }

    @Test
    fun responseExtractionDropsHiddenDuplicatesAndControlCharacters() {
        val script = ChatGptScripts.latestResponseMarkdown

        assertTrue(script.contains("[hidden],[aria-hidden='true']"))
        assertTrue(script.contains("\\u200b-\\u200d"))
        assertTrue(script.contains("\\u202a-\\u202e"))
        assertTrue(script.contains("\\u2066-\\u2069"))
        assertTrue(script.contains("\\ufffd"))
    }

    @Test
    fun responseExtractionSeparatesTexFromEscapedProse() {
        val script = ChatGptScripts.latestResponseMarkdown

        assertTrue(script.contains("escapeStudyInkMarkdownText(node.nodeValue)"))
        assertTrue(script.contains("data-studyink-math"))
        assertTrue(script.contains("data-studyink-tex"))
        assertTrue(script.contains("Text nodes are prose, not Markdown source"))
        assertTrue(script.contains("MathML textContent/aria-label is not TeX"))
        assertTrue(!script.contains("getAttribute(\"data-mathml\")"))
    }

    @Test
    fun sanitizedHtmlUsesSameMathAndArtifactPreparation() {
        val script = ChatGptScripts.latestResponseHtml

        assertTrue(script.contains("const clone = prepareStudyInkClone(message)"))
        assertTrue(script.contains("replaceStudyInkMath(clone)"))
        assertTrue(script.contains("removeStudyInkArtifacts(clone)"))
        assertTrue(script.contains("el.removeAttribute(attr.name)"))
        assertTrue(script.contains("/^https:\\/\\//i.test"))
    }
}
