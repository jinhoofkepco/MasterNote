package com.studyink.reader

import com.studyink.assistant.core.TeacherGptAnswerFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResourceUiTextTest {
    @Test
    fun promptLabelsStayShortAndUseSuperscriptNumbers() {
        assertEquals("¹", superscript(1))
        assertEquals("¹²", superscript(12))
        assertEquals("깨달음", compactPromptLabel(1, "수학적 깨달음 찾기"))
        assertEquals("개념", compactPromptLabel(2, "오개념 진단"))
        assertEquals("풀이", compactPromptLabel(3, "직접 풀이"))
        assertEquals("확장", compactPromptLabel(4, "다른 풀이 전략과 확장"))
    }

    @Test
    fun studentExcerptKeepsMeaningWithoutMarkdownControlMarks() {
        val source = """
            ## 핵심
            - **분수**는 ${'$'}\frac{1}{2}${'$'}입니다.
            - [설명](https://example.com)을 봅니다.
        """.trimIndent()

        val text = assistantStudentText(source)

        assertTrue(text.contains("핵심"))
        assertTrue(text.contains("• 분수"))
        assertTrue(text.contains("(1)/(2)"))
        assertTrue(text.contains("설명"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("https://"))
    }

    @Test
    fun legacyPlainExcerptPreservesLiteralMathLikeCharacters() {
        val source = "x_1과 y_2 · ${'$'}5~${'$'}10 · **별표**"

        assertEquals(
            source,
            assistantStudentText(source, TeacherGptAnswerFormat.PLAIN_TEXT),
        )
    }

    @Test
    fun markdownExcerptRestoresEscapedProsePunctuation() {
        val extracted =
            "x\\_1 · \\${'$'}5\\~\\${'$'}10 · \\*literal\\* · \\[보기\\] · \\# 제목 · 1\\. 항목"

        assertEquals(
            "x_1 · ${'$'}5~${'$'}10 · *literal* · [보기] · # 제목 · 1. 항목",
            assistantStudentText(extracted, TeacherGptAnswerFormat.MARKDOWN_TEX),
        )
    }

    @Test
    fun answerCapUsesReadableBoundaryNoticeAndKeepsEmojiWhole() {
        val source = "문장입니다. ".repeat(30) + "😀"

        val bounded = boundedAssistantAnswer(source, 96)

        assertTrue(bounded.truncated)
        assertTrue(bounded.text.contains("이후 내용은 생략했습니다"))
        assertTrue(bounded.text.length <= 96)
        assertFalse(bounded.text.any { Character.isSurrogate(it) })
    }
}
