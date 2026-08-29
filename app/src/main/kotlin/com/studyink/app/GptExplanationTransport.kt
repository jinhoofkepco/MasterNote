package com.studyink.app

import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.StudentExplanationCard
import com.studyink.assistant.core.StudentExplanationDigest
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.core.model.PageBounds
import com.studyink.monitor.core.GptExplanationLayerEnvelope
import com.studyink.monitor.core.RemoteExplanationBounds
import com.studyink.monitor.core.RemoteExplanationCard
import com.studyink.monitor.core.remoteExplanationLayerDigestSha256

internal fun StudentExplanationLayer.toRemoteEnvelope(
    pageToken: String,
    transferId: String,
    createdAtEpochMs: Long,
    authorityEpoch: String = this.authorityEpoch,
): GptExplanationLayerEnvelope {
    val remoteCards = cards.sortedBy(StudentExplanationCard::cardId).map { card ->
        RemoteExplanationCard(
            cardId = card.cardId,
            sourceResourceId = card.sourceResourceId,
            sourceResourceRevisionId = card.sourceResourceRevisionId,
            title = card.title,
            text = card.text,
            anchor = RemoteExplanationBounds(
                left = card.anchorBounds.left,
                top = card.anchorBounds.top,
                right = card.anchorBounds.right,
                bottom = card.anchorBounds.bottom,
            ),
            createdAtEpochMs = card.createdAtEpochMillis,
            updatedAtEpochMs = card.updatedAtEpochMillis,
        )
    }
    val wirePageNumber = target.page.pageNumber + 1
    return GptExplanationLayerEnvelope(
        transferId = transferId,
        createdAtEpochMs = createdAtEpochMs,
        pageToken = pageToken,
        pageNumber = wirePageNumber,
        attemptNo = target.attemptNo,
        layerRevision = revision,
        layerDigestSha256 = remoteExplanationLayerDigestSha256(
            pageToken = pageToken,
            pageNumber = wirePageNumber,
            attemptNo = target.attemptNo,
            cards = remoteCards,
            authorityEpoch = authorityEpoch,
        ),
        cards = remoteCards,
        authorityEpoch = authorityEpoch,
    )
}

/** Remaps only the local UUID; exact remote page/attempt identity is checked before this call. */
internal fun GptExplanationLayerEnvelope.toLocalLayer(localBookId: String): StudentExplanationLayer {
    val target = StudentExplanationTarget(
        page = AssistantPageKey(localBookId, pageNumber - 1),
        attemptNo = attemptNo,
    )
    val localCards = cards.sortedBy(RemoteExplanationCard::cardId).map { card ->
        StudentExplanationCard(
            cardId = card.cardId,
            sourceResourceId = card.sourceResourceId,
            sourceResourceRevisionId = card.sourceResourceRevisionId,
            title = card.title,
            text = card.text,
            anchorBounds = PageBounds(
                left = card.anchor.left,
                top = card.anchor.top,
                right = card.anchor.right,
                bottom = card.anchor.bottom,
            ),
            createdAtEpochMillis = card.createdAtEpochMs,
            updatedAtEpochMillis = card.updatedAtEpochMs,
        )
    }
    return StudentExplanationLayer(
        target = target,
        revision = layerRevision,
        digestSha256 = StudentExplanationDigest.sha256(target, localCards),
        cards = localCards,
        authorityEpoch = authorityEpoch,
    )
}
