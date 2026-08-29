package com.studyink.app

import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.StudentExplanationCard
import com.studyink.assistant.core.StudentExplanationDigest
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.assistant.core.PendingStudentExplanationPublication
import com.studyink.core.model.PageBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GptExplanationTransportTest {
    @Test
    fun wireIdentityKeepsExactPageAndAttemptWhileLocalBookIdIsRemapped() {
        val sourceTarget = StudentExplanationTarget(AssistantPageKey("teacher-book", 93), 4)
        val cards = listOf(
            StudentExplanationCard(
                cardId = "card-00000001",
                sourceResourceId = "resource-00000001",
                sourceResourceRevisionId = "revision-00000001",
                title = "설명",
                text = "정확한 4회차에만 표시됩니다.",
                anchorBounds = PageBounds(100f, 200f, 300f, 340f),
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 11L,
            ),
        )
        val source = StudentExplanationLayer(
            target = sourceTarget,
            revision = 7L,
            digestSha256 = StudentExplanationDigest.sha256(sourceTarget, cards),
            cards = cards,
        )

        val envelope = source.toRemoteEnvelope(
            pageToken = "page-token-00000001",
            transferId = "gpt-transfer-00000001",
            createdAtEpochMs = 12L,
            authorityEpoch = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        val received = envelope.toLocalLayer("student-book")

        assertEquals(94, envelope.pageNumber)
        assertEquals(4, envelope.attemptNo)
        assertEquals(StudentExplanationTarget(AssistantPageKey("student-book", 93), 4), received.target)
        assertEquals(source.revision, received.revision)
        assertEquals(source.cards, received.cards)
        assertEquals(envelope.authorityEpoch, received.authorityEpoch)
        assertNotEquals(source.digestSha256, received.digestSha256)
        assertEquals(
            StudentExplanationDigest.sha256(received.target, received.cards),
            received.digestSha256,
        )
    }

    @Test
    fun telegramPublicationRequiresExactRemoteAttemptEvenWhenOpen() {
        val page = TeacherPageSyncRecord(
            syncGeneration = 7L,
            pageToken = "page-token",
            workbookToken = "workbook-token",
            contentSha256 = "a".repeat(64),
            studentLayerSha256 = "b".repeat(64),
            workbookLabel = "교재",
            localBookId = "teacher-book",
            pageNumber = 93,
            attemptNos = listOf(3, 4),
            submittedAttemptNos = listOf(3),
            sourceRevision = 10L,
            appliedRevision = 10L,
            appliedStudentLayerSha256 = "b".repeat(64),
            lastChangedAtEpochMs = 1L,
            approximateBytes = 1L,
        )

        assertTrue(page.authorizesGptExplanation("teacher-book", 93, 4, "a".repeat(64)))
        assertFalse(page.authorizesGptExplanation("teacher-book", 93, 5, "a".repeat(64)))
        assertFalse(page.authorizesGptExplanation("other-copy", 93, 4, "a".repeat(64)))
    }

    @Test
    fun routeHandoffRotatesTelegramTransferWithoutChangingLogicalPublication() {
        val target = StudentExplanationTarget(AssistantPageKey("teacher-book", 93), 4)
        val pending = PendingStudentExplanationPublication(
            publicationId = "c".repeat(64),
            target = target,
            revision = 2L,
            digestSha256 = "d".repeat(64),
            queuedAtEpochMillis = 1L,
        )
        val first = gptTelegramTransferId(pending, "pair", "page-token")
        val repeated = gptTelegramTransferId(pending, "pair", "page-token")
        val afterLan = gptTelegramTransferId(
            pending.copy(deliveryAttempt = 1L),
            "pair",
            "page-token",
        )
        assertEquals(first, repeated)
        assertNotEquals(first, afterLan)
        assertEquals(pending.publicationId, pending.copy(deliveryAttempt = 1L).publicationId)
    }

    @Test
    fun telegramFallbackClearsLanRetryGateImmediately() {
        assertTrue(
            shouldClearGptPublicationRetryGate(
                GlobalPageSyncTransportRoute.LAN_GRACE,
                GlobalPageSyncTransportRoute.TELEGRAM,
            ),
        )
        assertTrue(
            shouldClearGptPublicationRetryGate(
                GlobalPageSyncTransportRoute.LAN_OWNS,
                GlobalPageSyncTransportRoute.TELEGRAM,
            ),
        )
        assertFalse(
            shouldClearGptPublicationRetryGate(
                GlobalPageSyncTransportRoute.TELEGRAM,
                GlobalPageSyncTransportRoute.TELEGRAM,
            ),
        )
    }
}
