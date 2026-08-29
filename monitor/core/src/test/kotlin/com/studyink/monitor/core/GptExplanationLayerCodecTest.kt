package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GptExplanationLayerCodecTest {
    @Test
    fun exactAttemptLayerRoundTrips() {
        val cards = listOf(
            RemoteExplanationCard(
                cardId = "card-00000001",
                sourceResourceId = "resource-00000001",
                sourceResourceRevisionId = "revision-00000001",
                title = "핵심 설명",
                text = "이 회차에서만 보이는 교사 설명입니다.",
                anchor = RemoteExplanationBounds(100f, 200f, 420f, 360f),
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_000_100L,
            ),
        )
        val original = GptExplanationLayerEnvelope(
            transferId = "gpt-transfer-0001",
            createdAtEpochMs = 1_700_000_000_000L,
            pageToken = "page-token-00000001",
            pageNumber = 94,
            attemptNo = 4,
            layerRevision = 7L,
            layerDigestSha256 = remoteExplanationLayerDigestSha256(
                "page-token-00000001",
                94,
                4,
                cards,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ),
            cards = cards,
            authorityEpoch = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        val encoded = RemoteReviewDocumentCodec.encode(original)
        val decoded = RemoteReviewDocumentCodec.decode(encoded.copyBytes())
            .envelope as GptExplanationLayerEnvelope

        assertEquals(RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER, decoded.type)
        assertEquals(original, decoded)
    }

    @Test
    fun legacyFrameWithoutAuthorityExtensionRemainsReadable() {
        val card = RemoteExplanationCard(
            cardId = "card-legacy-0001",
            sourceResourceId = "resource-legacy-0001",
            sourceResourceRevisionId = "revision-legacy-0001",
            title = "설명",
            text = "기존 프레임",
            anchor = RemoteExplanationBounds(0f, 0f, 1000f, 100f),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        val envelope = GptExplanationLayerEnvelope(
            transferId = "gpt-legacy-0001",
            createdAtEpochMs = 1L,
            pageToken = "page-token-legacy",
            pageNumber = 1,
            attemptNo = 1,
            layerRevision = 1L,
            layerDigestSha256 = remoteExplanationLayerDigestSha256(
                "page-token-legacy", 1, 1, listOf(card),
            ),
            cards = listOf(card),
        )

        val decoded = RemoteReviewDocumentCodec.decode(
            RemoteReviewDocumentCodec.encode(envelope).copyBytes(),
        ).envelope
        assertEquals(envelope, decoded)
    }

    @Test
    fun layerRejectsInvalidAnchorAndDuplicateCardIds() {
        assertThrows(RemoteReviewValidationException::class.java) {
            RemoteExplanationBounds(10f, 20f, 10f, 30f)
        }
        assertThrows(RemoteReviewValidationException::class.java) {
            RemoteExplanationBounds(10f, 20f, 1000.01f, 30f)
        }
        val card = RemoteExplanationCard(
            cardId = "card-00000001",
            sourceResourceId = "resource-00000001",
            sourceResourceRevisionId = "revision-00000001",
            title = "설명",
            text = "내용",
            anchor = RemoteExplanationBounds(10f, 20f, 30f, 40f),
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        assertThrows(RemoteReviewValidationException::class.java) {
            GptExplanationLayerEnvelope(
                transferId = "gpt-transfer-0001",
                createdAtEpochMs = 1L,
                pageToken = "page-token-00000001",
                pageNumber = 1,
                attemptNo = 1,
                layerRevision = 1L,
                layerDigestSha256 = remoteExplanationLayerDigestSha256(
                    "page-token-00000001",
                    1,
                    1,
                    listOf(card, card.copy(text = "다른 내용")),
                ),
                cards = listOf(card, card.copy(text = "다른 내용")),
            )
        }
    }
}
