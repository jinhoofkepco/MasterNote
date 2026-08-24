package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentVoiceMessageControllerTest {
    @Test
    fun wakePhraseRequiresDadAsItsOwnUtterance() {
        assertTrue(isKoreanDadWakePhrase("아빠"))
        assertTrue(isKoreanDadWakePhrase(" 아빠! "))
        assertFalse(isKoreanDadWakePhrase("아빠 오늘 문제 어려워"))
        assertFalse(isKoreanDadWakePhrase("아파"))
    }

    @Test
    fun destroyedWakeSessionCannotActOnTheFollowingDictation() {
        val gate = StudentVoiceRecognitionGate()
        val wake = gate.begin(StudentVoiceRecognitionMode.WAKE)
        gate.invalidate()
        val dictation = gate.begin(StudentVoiceRecognitionMode.DICTATION)

        assertFalse(gate.isCurrent(wake))
        assertTrue(gate.isCurrent(dictation))
    }

    @Test
    fun lateDeliveryCompletionCannotFinishAnotherMessage() {
        val gate = StudentVoiceDeliveryGate()
        assertTrue(gate.begin("student-dictation:first"))
        assertFalse(gate.begin("student-dictation:second"))
        assertFalse(gate.finish("student-dictation:stale"))
        assertTrue(gate.hasPending)
        assertTrue(gate.finish("student-dictation:first"))
        assertFalse(gate.hasPending)
    }

    @Test
    fun dictationIsTrimmedAndInternalWhitespaceIsCollapsed() {
        assertEquals("오늘 12쪽 어려워요", normalizeStudentDictation("  오늘\n  12쪽\t어려워요  "))
        assertEquals("", normalizeStudentDictation(" \n\t "))
    }

    @Test
    fun dictationLimitDoesNotSplitASurrogatePair() {
        val normalized = normalizeStudentDictation("가".repeat(3_499) + "😀" + "끝")

        assertEquals(3_500, normalized.codePointCount(0, normalized.length))
        assertTrue(normalized.endsWith("😀"))
    }
}
