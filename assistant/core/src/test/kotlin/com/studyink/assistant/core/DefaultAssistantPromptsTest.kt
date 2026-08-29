package com.studyink.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultAssistantPromptsTest {
    @Test
    fun userPromptIsExactAndOtherMathPromptsHaveSevenSentences() {
        val slots = DefaultAssistantPrompts.slots

        assertEquals(4, slots.size)
        assertEquals(
            "이 부분을 보고 학생에게 설명하기 위해 수학적 깨달음을 얻을 수 있는 포인트를 짚어줘. " +
                "그리고 그 포인트를 단계적으로 설명할 수 있게 하기 위해서 세 단계로 구분해서 설명을 만들어 줘.",
            slots[0].body,
        )
        slots.drop(1).forEach { slot ->
            assertEquals("질문 ${slot.slotNumber}", 7, slot.body.count { it == '.' })
        }
    }
}
