package com.studyink.reader

import com.studyink.assistant.core.TeacherGptAnswerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattedAssistantAnswerDocumentTest {
    @Test
    fun build_escapesHtmlAndDropsMarkdownLinkTargets() {
        val document = FormattedAssistantAnswerDocument.build(
            """
            <script>alert('unsafe')</script>
            [설명](javascript:alert('unsafe'))
            """.trimIndent(),
        )

        assertTrue(document.contains("&lt;script&gt;alert(&#39;unsafe&#39;)&lt;/script&gt;"))
        assertTrue(document.contains("설명"))
        assertFalse(document.contains("<script>alert"))
        assertFalse(document.contains("javascript:alert"))
        assertFalse(document.contains("<a "))
    }

    @Test
    fun build_preservesReadableMarkdownStructure() {
        val document = FormattedAssistantAnswerDocument.build(
            """
            # 핵심

            **중요한 설명**입니다.

            1. 첫 단계
            2. 둘째 단계

            | 항목 | 값 |
            | --- | --- |
            | 답 | 3 |
            """.trimIndent(),
        )

        assertTrue(document.contains("<h1>핵심</h1>"))
        assertTrue(document.contains("<strong>중요한 설명</strong>"))
        assertTrue(document.contains("<ol><li>첫 단계</li><li>둘째 단계</li></ol>"))
        assertTrue(document.contains("<div class=\"table-scroll\"><table>"))
        assertTrue(document.contains("<th>항목</th>"))
        assertTrue(document.contains("<td>3</td>"))
    }

    @Test
    fun build_preservesNestedListsAndLongInlineCodeFences() {
        val document = FormattedAssistantAnswerDocument.build(
            """
            - 바깥
              1. 안쪽 첫째
              2. 안쪽 둘째
            - 다시 바깥

            `` `표시할 코드` ``
            """.trimIndent(),
        )

        assertTrue(document.contains("<ul><li>바깥<ol><li>안쪽 첫째</li><li>안쪽 둘째</li></ol></li>"))
        assertTrue(document.contains("<li>다시 바깥</li></ul>"))
        assertTrue(document.contains("<code>`표시할 코드`</code>"))
    }

    @Test
    fun build_marksInlineAndDisplayTexWithoutInterpretingItAsHtml() {
        val document = FormattedAssistantAnswerDocument.build(
            """
            피타고라스는 \(a^2+b^2=c^2\) 입니다.

            \[
            x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}
            \]
            """.trimIndent(),
        )

        assertTrue(document.contains("<span class=\"math-inline\">\\(a^2+b^2=c^2\\)</span>"))
        assertTrue(document.contains("<div class=\"math-display\">"))
        assertTrue(document.contains("\\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}"))
    }

    @Test
    fun build_rendersDisplayDelimiterCompactedInsideAListItem() {
        val document = FormattedAssistantAnswerDocument.build(
            "- 대입: ${'$'}${'$'} \\frac{1}{2} ${'$'}${'$'}",
        )

        assertTrue(document.contains("<li>대입: <span class=\"math-inline\">"))
        assertTrue(document.contains("\\(\\frac{1}{2}\\)"))
        assertFalse(document.contains("${'$'}${'$'} \\frac{1}{2} ${'$'}${'$'}"))
    }

    @Test
    fun build_removesExtractionArtifactsButKeepsLineBreaks() {
        val document = FormattedAssistantAnswerDocument.build("첫째\u200b\u200c\u200d\u2060\ufeff\r\n둘째\u202e\ufffd")

        assertTrue(document.contains("<p>첫째<br>둘째</p>"))
        assertFalse(document.contains("\u200b"))
        assertFalse(document.contains("\u200c"))
        assertFalse(document.contains("\u200d"))
        assertFalse(document.contains("\u2060"))
        assertFalse(document.contains("\ufeff"))
        assertFalse(document.contains("\u202e"))
        assertFalse(document.contains("\ufffd"))
    }

    @Test
    fun build_usesOnlyLocalResourcesAndRestrictivePolicy() {
        val document = FormattedAssistantAnswerDocument.build("답변")

        assertTrue(document.contains("default-src 'none'"))
        assertTrue(document.contains("connect-src 'none'"))
        assertTrue(document.contains("script-src 'self'"))
        assertTrue(document.contains("src=\"katex.min.js\""))
        assertFalse(document.contains("src=\"http"))
        assertFalse(document.contains("href=\"http"))
    }

    @Test
    fun build_capsPathologicalAnswersWithVisibleNotice() {
        val source = "가".repeat(FormattedAssistantAnswerDocument.MAX_SOURCE_CHARS + 40)

        val document = FormattedAssistantAnswerDocument.build(source)

        assertTrue(document.contains("답변이 너무 길어 앞부분만 표시했습니다."))
        assertFalse(document.contains("가".repeat(FormattedAssistantAnswerDocument.MAX_SOURCE_CHARS + 1)))
    }

    @Test
    fun legacyPlainTextNeverBecomesMarkdownOrMath() {
        val document = FormattedAssistantAnswerDocument.build(
            "x_1과 y_2, ${'$'}5~${'$'}10, **별표**",
            TeacherGptAnswerFormat.PLAIN_TEXT,
        )

        assertTrue(document.contains("x_1과 y_2, ${'$'}5~${'$'}10, **별표**"))
        assertFalse(document.contains("<em>"))
        assertFalse(document.contains("math-inline"))
        assertFalse(document.contains("<strong>"))
    }

    @Test
    fun utf16CapNeverLeavesHalfAnEmoji() {
        val source = "가".repeat(9) + "😀"

        val capped = validUtf16Prefix(source, 10)

        assertEquals(9, capped.length)
        assertFalse(capped.any(Character::isSurrogate))
    }

    @Test
    fun pathologicalMathCreatesOnlyBoundedKatexTargets() {
        val source = ("${'$'}x${'$'} ").repeat(400)

        val document = FormattedAssistantAnswerDocument.build(source)

        assertEquals(256, Regex("class=\"math-inline\"").findAll(document).count())
        assertTrue(document.contains("\\(x\\)"))
    }

    @Test
    fun escapedProseMarkersStayLiteral() {
        val document = FormattedAssistantAnswerDocument.build(
            "x\\_1 · \\${'$'}5\\~\\${'$'}10 · \\# 제목 아님 · \\> 인용 아님",
        )

        assertTrue(document.contains("x_1 · ${'$'}5~${'$'}10 · # 제목 아님 · &gt; 인용 아님"))
        assertFalse(document.contains("<em>"))
        assertFalse(document.contains("math-inline"))
        assertFalse(document.contains("<blockquote>"))
    }

    @Test
    fun editorWrapsOnlyVisibleWholeBlocksAndLoadsLocalEditorScript() {
        val document = FormattedAssistantAnswerDocument.buildEditor(
            "첫 설명\n\n수식은 \\(x^2\\) 입니다.\n\n마지막 설명",
            hiddenBlockOrdinals = setOf(1),
        )

        assertTrue(document.contains("src=\"editor.js\""))
        assertTrue(document.contains("data-block-ordinal=\"0\""))
        assertFalse(document.contains("data-block-ordinal=\"1\""))
        assertTrue(document.contains("data-block-ordinal=\"2\""))
        assertFalse(document.contains("x^2"))
        assertEquals(2, Regex("class=\"edit-block\"").findAll(document).count())
    }

    @Test
    fun editorKeepsOneDocumentWideMathBudgetAcrossManyBlocks() {
        val source = (1..400).joinToString("\n\n") { index -> "${'$'}x_$index${'$'}" }

        val document = FormattedAssistantAnswerDocument.buildEditor(source)

        assertEquals(256, Regex("class=\"math-inline\"").findAll(document).count())
    }
}
