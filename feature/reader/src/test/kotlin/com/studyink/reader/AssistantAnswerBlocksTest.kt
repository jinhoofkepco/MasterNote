package com.studyink.reader

import com.studyink.assistant.core.TeacherGptAnswerMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAnswerBlocksTest {
    @Test
    fun parse_keepsDelimitedStructuresAtomic() {
        val source = """
            첫 줄

            \[
            x = \frac{1}{2}
            \]

            ```text
            a < b
            ```

            | 값 | 답 |
            | --- | --- |
            | x | 1 |
        """.trimIndent()

        val blocks = AssistantAnswerBlocks.parse(source)

        assertEquals(4, blocks.size)
        assertTrue(blocks[1].source.contains("\\frac{1}{2}"))
        assertTrue(blocks[2].source.startsWith("```text"))
        assertTrue(blocks[3].source.contains("| --- | --- |"))
    }

    @Test
    fun visibleSource_removesWholeBlockWithoutCuttingTex() {
        val source = "첫 설명\n\n수식은 \\(x^2\\) 입니다.\n\n마지막 설명"

        val visible = AssistantAnswerBlocks.visibleSource(source, setOf(1))

        assertEquals("첫 설명\n\n마지막 설명", visible)
        assertFalse(visible.contains("\\("))
    }

    @Test
    fun listIsOneAtomicStructureSoNestedMarkdownCannotBeStranded() {
        val source = "1. 첫째\n2. 둘째\n3. 셋째\n\n목록 뒤 설명"

        assertEquals(2, AssistantAnswerBlocks.parse(source).size)
        assertEquals("목록 뒤 설명", AssistantAnswerBlocks.visibleSource(source, setOf(0)))
    }

    @Test
    fun visibleSource_keepsPlainTextLineSpacingAfterOneLineIsHidden() {
        val source = "첫 줄\n둘째 줄\n셋째 줄"

        assertEquals("첫 줄\n셋째 줄", AssistantAnswerBlocks.visibleSource(source, setOf(1)))
    }

    @Test
    fun invalidMaskFailsOpenToOriginalAnswer() {
        val source = "보여야 하는 원문\n\n두 번째 줄"
        val invalid = TeacherGptAnswerMask.forAnswer("다른 원문", setOf(0))

        assertEquals(source, AssistantAnswerBlocks.visibleSource(source, invalid))
    }

    @Test
    fun emptyOrInapplicableMaskReturnsExactLegacySource() {
        val source = "첫 줄\r\n둘째 줄\u2028같은 원문 줄"

        assertEquals(source, AssistantAnswerBlocks.visibleSource(source, emptySet()))
        assertEquals(source, AssistantAnswerBlocks.visibleSource(source, setOf(999)))
    }

    @Test
    fun editorAndPersistenceShareOrdinalsBeforeDisplayNormalization() {
        val source = "A\u2028B\n\nC"

        assertEquals(3, AssistantAnswerBlocks.parse(source).size)
        assertEquals("A\n\nC", AssistantAnswerBlocks.visibleSource(source, setOf(1)))
        val editor = FormattedAssistantAnswerDocument.buildEditor(source)
        assertEquals(3, Regex("class=\"edit-block\"").findAll(editor).count())
    }

    @Test
    fun tableGrammarMatchesRendererWithoutOuterPipes() {
        val source = "항목 | 값\n--- | ---\n답 | 3\n\n설명"

        val blocks = AssistantAnswerBlocks.parse(source)

        assertEquals(2, blocks.size)
        assertEquals(AssistantAnswerBlockKind.STRUCTURE, blocks.first().kind)
        assertEquals("설명", AssistantAnswerBlocks.visibleSource(source, setOf(0)))
    }

    @Test
    fun displayLineSeparatorCannotCreateHalfOfAFencedBlock() {
        val source = "앞말\u2028```text\nX\n```\n\n뒷말"
        val blocks = AssistantAnswerBlocks.parse(source)

        assertEquals(3, blocks.size)
        assertTrue(blocks[1].source.startsWith("```text"))
        assertTrue(blocks[1].source.endsWith("```"))
        assertEquals("앞말\n\n뒷말", AssistantAnswerBlocks.visibleSource(source, setOf(1)))
    }
}
