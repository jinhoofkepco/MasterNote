package com.studyink.assistant.core

/** The four product-owned seeds. Persisted edits always win over these defaults. */
object DefaultAssistantPrompts {
    const val SLOT_COUNT: Int = 4

    val slots: List<AssistantPromptSlot>
        get() = seedSlots.toList()

    /** Upgrades only an exact, never-edited pre-suffix seed held in an older prompt file. */
    internal fun upgradeLegacySeed(slot: AssistantPromptSlot): AssistantPromptSlot {
        val current = seedSlots.getOrNull(slot.slotNumber - 1) ?: return slot
        val legacyBody = current.body.removeSuffix("\n\n$RESPONSE_FORMAT_SUFFIX")
        return if (
            slot.revision == 0L && slot.updatedAtEpochMillis == 0L &&
            slot.title == current.title && slot.body == legacyBody
        ) {
            current
        } else {
            slot
        }
    }

    private val seedSlots = listOf(
        AssistantPromptSlot(
            slotNumber = 1,
            title = "수학적 깨달음 3단계",
            body = "이 부분을 보고 학생에게 설명하기 위해 수학적 깨달음을 얻을 수 있는 포인트를 짚어줘. 그리고 그 포인트를 단계적으로 설명할 수 있게 하기 위해서 세 단계로 구분해서 설명을 만들어 줘.\n\n" +
                RESPONSE_FORMAT_SUFFIX,
            revision = 0L,
            updatedAtEpochMillis = 0L,
        ),
        AssistantPromptSlot(
            slotNumber = 2,
            title = "핵심 개념과 오개념",
            body = "선택한 수학 문제에서 학생이 반드시 이해해야 할 핵심 개념을 찾아줘. 문제의 조건들이 그 개념과 어떻게 연결되는지 설명해줘. 학생이 자주 빠질 수 있는 오개념이나 계산 실수를 짚어줘. 각 오개념이 왜 틀렸는지 반례나 간단한 확인 방법으로 보여줘. 올바른 사고로 전환하기 위한 질문을 순서대로 제시해줘. 교사가 학생에게 사용할 수 있는 쉬운 비유가 있다면 하나 덧붙여줘. 마지막에는 학생이 스스로 이해했는지 확인할 짧은 질문 두 개를 만들어줘.\n\n" +
                RESPONSE_FORMAT_SUFFIX,
            revision = 0L,
            updatedAtEpochMillis = 0L,
        ),
        AssistantPromptSlot(
            slotNumber = 3,
            title = "문제 직접 풀이",
            body = "선택한 수학 문제를 직접 풀어줘. 먼저 주어진 조건과 구하려는 값을 명확하게 정리해줘. 적용할 핵심 공식이나 정리를 고르고 그 이유를 설명해줘. 계산과 논리 전개를 중간 단계를 생략하지 말고 순서대로 보여줘. 각 단계에서 학생이 확인해야 할 부호와 단위 또는 정의역을 함께 점검해줘. 가능하다면 다른 풀이 한 가지를 짧게 제시하고 두 방법의 차이를 알려줘. 마지막에는 최종 답과 답이 조건을 만족하는지 검산한 결과를 분명하게 적어줘.\n\n" +
                RESPONSE_FORMAT_SUFFIX,
            revision = 0L,
            updatedAtEpochMillis = 0L,
        ),
        AssistantPromptSlot(
            slotNumber = 4,
            title = "풀이 전략과 확장",
            body = "선택한 수학 문제를 학생의 풀이 관점에서 분석해줘. 처음 문제를 보았을 때 발견해야 할 단서들을 우선순위대로 정리해줘. 가능한 풀이 전략을 두 가지 제시하고 각각 언제 유리한지 설명해줘. 가장 효율적인 전략을 골라 핵심 전환점을 중심으로 풀이 흐름을 안내해줘. 숫자나 조건이 달라져도 유지되는 일반적인 원리를 찾아줘. 같은 개념을 확인할 수 있는 변형 문제를 하나 만들고 힌트도 제공해줘. 마지막에는 이 문제를 통해 다음 문제에 적용할 수 있는 한 문장짜리 학습 원칙을 적어줘.\n\n" +
                RESPONSE_FORMAT_SUFFIX,
            revision = 0L,
            updatedAtEpochMillis = 0L,
        ),
    )

    /** Seed text only: it stays visible/editable and is never injected again when a request is sent. */
    private const val RESPONSE_FORMAT_SUFFIX =
        "답변 형식: 머리말과 맺음말 없이 내용만 건조하게 설명해줘. " +
            "전체 답변은 10줄 이내로 작성해줘. " +
            "일반 설명은 평범한 글로 쓰고 수식만 LaTeX 형식으로 작성해줘. " +
            "HTML 태그는 작성하지 마."
}
