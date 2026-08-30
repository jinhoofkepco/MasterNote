package com.studyink.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAssistantPromptsTest {
    @Test
    fun everySeedIncludesTheEditableConciseResponseFormat() {
        val slots = DefaultAssistantPrompts.slots
        val responseFormat =
            "답변 형식: 머리말과 맺음말 없이 내용만 건조하게 설명해줘. " +
                "전체 답변은 10줄 이내로 작성해줘. " +
                "일반 설명은 평범한 글로 쓰고 수식만 LaTeX 형식으로 작성해줘. " +
                "HTML 태그는 작성하지 마."

        assertEquals(4, slots.size)
        assertTrue(
            "이 부분을 보고 학생에게 설명하기 위해 수학적 깨달음을 얻을 수 있는 포인트를 짚어줘. " +
                "그리고 그 포인트를 단계적으로 설명할 수 있게 하기 위해서 세 단계로 구분해서 설명을 만들어 줘." in
                slots[0].body,
        )
        slots.forEach { slot ->
            assertTrue("질문 ${slot.slotNumber}", slot.body.endsWith(responseFormat))
            assertEquals("질문 ${slot.slotNumber}", 1, slot.body.split(responseFormat).size - 1)
        }
    }

    @Test
    fun onlyExactNeverEditedLegacySeedIsUpgraded() {
        val current = DefaultAssistantPrompts.slots.first()
        val legacy = current.copy(body = current.body.substringBefore("\n\n답변 형식:"))

        assertEquals(current, DefaultAssistantPrompts.upgradeLegacySeed(legacy))
        assertEquals(
            legacy.copy(body = "사용자가 직접 쓴 질문"),
            DefaultAssistantPrompts.upgradeLegacySeed(legacy.copy(body = "사용자가 직접 쓴 질문")),
        )
        assertEquals(
            legacy.copy(revision = 1L),
            DefaultAssistantPrompts.upgradeLegacySeed(legacy.copy(revision = 1L)),
        )
    }
}
