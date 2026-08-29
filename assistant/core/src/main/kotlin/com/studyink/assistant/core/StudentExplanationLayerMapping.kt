package com.studyink.assistant.core

/** Rebinds transport content to a previously verified local page identity. */
fun StudentExplanationLayer.remapTo(target: StudentExplanationTarget): StudentExplanationLayer {
    val orderedCards = cards.sortedBy(StudentExplanationCard::cardId)
    return StudentExplanationLayer(
        target = target,
        revision = revision,
        digestSha256 = StudentExplanationDigest.sha256(target, orderedCards),
        cards = orderedCards,
        authorityEpoch = authorityEpoch,
        retiredAuthorityEpochs = emptySet(),
    )
}

/** Freezes an outgoing copy in the authenticated teacher's revision namespace. */
fun StudentExplanationLayer.withAuthorityEpoch(authorityEpoch: String): StudentExplanationLayer =
    copy(authorityEpoch = authorityEpoch, retiredAuthorityEpochs = emptySet())
