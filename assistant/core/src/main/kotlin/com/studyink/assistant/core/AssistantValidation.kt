package com.studyink.assistant.core

import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PageBounds
import java.nio.ByteBuffer
import java.security.MessageDigest

internal object AssistantValidation {
    const val MAX_RETIRED_AUTHORITY_EPOCHS = 128
    private const val MAX_ID_UTF8_BYTES = 256
    private const val MAX_TITLE_UTF8_BYTES = 1_024
    private const val MAX_PROVIDER_UTF8_BYTES = 256
    private const val MAX_CANONICAL_PAGE_HEIGHT = 1_000_000f
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun prompt(slot: AssistantPromptSlot, limits: AssistantStorageLimits) {
        require(slot.slotNumber in 1..DefaultAssistantPrompts.SLOT_COUNT)
        text(slot.title, "prompt title", MAX_TITLE_UTF8_BYTES)
        text(slot.body, "prompt body", limits.maxPromptBodyUtf8Bytes)
        require(slot.revision >= 0L) { "Prompt revision is negative" }
        require(slot.updatedAtEpochMillis >= 0L) { "Prompt timestamp is negative" }
    }

    fun teacherPage(
        expectedPage: AssistantPageKey,
        resources: List<TeacherGptResource>,
        limits: AssistantStorageLimits,
    ) {
        require(resources.size <= limits.maxTeacherResourcesPerPage) {
            "Too many teacher GPT resources on one page"
        }
        val resourceIds = hashSetOf<String>()
        val revisionIds = hashSetOf<String>()
        resources.forEach { resource ->
            require(resource.page == expectedPage) { "Teacher GPT resource page mismatch" }
            id(resource.resourceId, "resourceId")
            require(resourceIds.add(resource.resourceId)) { "Duplicate teacher GPT resource ID" }
            text(resource.title, "resource title", MAX_TITLE_UTF8_BYTES)
            require(resource.createdAtEpochMillis >= 0L)
            require(resource.revisions.isNotEmpty()) { "Teacher GPT resource has no revision" }
            require(resource.revisions.size <= limits.maxRevisionsPerResource) {
                "Too many revisions for one teacher GPT resource"
            }
            require(resource.currentRevisionId == resource.revisions.last().revisionId) {
                "Current teacher GPT revision is not the newest revision"
            }
            var priorTimestamp = resource.createdAtEpochMillis
            resource.revisions.forEachIndexed { index, revision ->
                id(revision.revisionId, "revisionId")
                require(revisionIds.add(revision.revisionId)) { "Duplicate teacher GPT revision ID" }
                require(revision.revisionNumber == index + 1L) {
                    "Teacher GPT revision numbers are not contiguous"
                }
                require(revision.promptSlotNumber in 1..DefaultAssistantPrompts.SLOT_COUNT)
                text(revision.promptTitle, "saved prompt title", MAX_TITLE_UTF8_BYTES)
                text(revision.promptBody, "saved prompt body", limits.maxPromptBodyUtf8Bytes)
                bounds(revision.selectionBounds)
                text(revision.answerText, "answer text", limits.maxAnswerTextUtf8Bytes)
                revision.answerHtml?.let {
                    optionalText(it, "answer HTML", limits.maxAnswerHtmlUtf8Bytes)
                }
                revision.providerName?.let { optionalText(it, "provider name", MAX_PROVIDER_UTF8_BYTES) }
                require(revision.createdAtEpochMillis >= priorTimestamp) {
                    "Teacher GPT revision timestamp moved backwards"
                }
                priorTimestamp = revision.createdAtEpochMillis
            }
        }
    }

    fun studentLayer(layer: StudentExplanationLayer, limits: AssistantStorageLimits) {
        require(layer.revision >= 0L) { "Student explanation revision is negative" }
        require(SHA256.matches(layer.digestSha256)) { "Student explanation digest is invalid" }
        id(layer.authorityEpoch, "student explanation authority epoch")
        require(layer.retiredAuthorityEpochs.size <= MAX_RETIRED_AUTHORITY_EPOCHS) {
            "Too many retired student explanation authorities"
        }
        layer.retiredAuthorityEpochs.forEach {
            id(it, "retired student explanation authority epoch")
        }
        require(layer.authorityEpoch !in layer.retiredAuthorityEpochs) {
            "Current student explanation authority is retired"
        }
        require(layer.cards.size <= limits.maxCardsPerLayer) {
            "Too many student explanation cards"
        }
        val ids = hashSetOf<String>()
        layer.cards.forEach { card ->
            card(card, limits)
            require(ids.add(card.cardId)) { "Duplicate student explanation card ID" }
        }
        require(layer.cards == layer.cards.sortedBy(StudentExplanationCard::cardId)) {
            "Student explanation cards are not in canonical order"
        }
        require(layer.digestSha256 == StudentExplanationDigest.sha256(layer.target, layer.cards)) {
            "Student explanation digest does not match its payload"
        }
        if (layer.revision == 0L) {
            require(layer.cards.isEmpty()) { "Revision zero must be an empty student layer" }
        }
    }

    fun card(card: StudentExplanationCard, limits: AssistantStorageLimits) {
        id(card.cardId, "cardId")
        id(card.sourceResourceId, "sourceResourceId")
        id(card.sourceResourceRevisionId, "sourceResourceRevisionId")
        text(card.title, "student card title", MAX_TITLE_UTF8_BYTES)
        text(card.text, "student card text", limits.maxStudentCardTextUtf8Bytes)
        bounds(card.anchorBounds)
        require(card.createdAtEpochMillis >= 0L)
        require(card.updatedAtEpochMillis >= card.createdAtEpochMillis)
    }

    fun pendingPublication(value: PendingStudentExplanationPublication) {
        require(SHA256.matches(value.publicationId)) { "Student explanation publication ID is invalid" }
        require(value.revision > 0L) { "Student explanation publication revision is not positive" }
        require(SHA256.matches(value.digestSha256)) { "Student explanation publication digest is invalid" }
        require(value.queuedAtEpochMillis >= 0L) { "Student explanation publication time is negative" }
        require(value.deliveryAttempt >= 0L) { "Student explanation delivery attempt is negative" }
        value.resolvedAtEpochMillis?.let { resolvedAt ->
            require(resolvedAt >= value.queuedAtEpochMillis) {
                "Student explanation publication resolution predates its queue time"
            }
        }
    }

    /** The two transports intentionally share this smaller, predictable publication budget. */
    fun publishableStudentLayer(layer: StudentExplanationLayer, encodedCheckpoint: ByteArray) {
        studentLayer(layer, AssistantStorageLimits())
        require(layer.revision > 0L) { "An empty student explanation layer cannot be published" }
        var totalTextBytes = 0L
        layer.cards.forEach { card ->
            require(card.title.hasWellFormedSurrogatePairs() && card.text.hasWellFormedSurrogatePairs()) {
                "Student explanation contains malformed Unicode"
            }
            require(card.title.hasOnlySupportedControls() && card.text.hasOnlySupportedControls()) {
                "Student explanation contains an unsupported control character"
            }
            totalTextBytes += card.text.toByteArray(Charsets.UTF_8).size.toLong()
            require(totalTextBytes <= AssistantPublicationLimits.MAX_TOTAL_CARD_TEXT_UTF8_BYTES) {
                "Student explanation text exceeds the publication limit"
            }
        }
        require(encodedCheckpoint.size <= AssistantPublicationLimits.MAX_CHECKPOINT_BYTES) {
            "Student explanation checkpoint exceeds the publication limit"
        }
    }

    fun bounds(value: PageBounds) {
        require(value.left.isFinite() && value.top.isFinite() &&
            value.right.isFinite() && value.bottom.isFinite()
        ) { "Canonical bounds contain a non-finite value" }
        require(value.left >= 0f && value.right <= CANONICAL_PAGE_WIDTH) {
            "Canonical bounds are outside the page width"
        }
        require(value.top >= 0f && value.bottom <= MAX_CANONICAL_PAGE_HEIGHT) {
            "Canonical bounds are outside the supported page height"
        }
        require(value.left < value.right && value.top < value.bottom) {
            "Canonical bounds are empty or inverted"
        }
    }

    fun id(value: String, label: String) {
        text(value, label, MAX_ID_UTF8_BYTES)
    }

    fun text(value: String, label: String, maximumUtf8Bytes: Int) {
        require(value.isNotBlank()) { "$label is blank" }
        require(value.toByteArray(Charsets.UTF_8).size <= maximumUtf8Bytes) { "$label is too long" }
    }

    private fun optionalText(value: String, label: String, maximumUtf8Bytes: Int) {
        require(value.toByteArray(Charsets.UTF_8).size <= maximumUtf8Bytes) { "$label is too long" }
    }
}

object AssistantPublicationLimits {
    const val MAX_CHECKPOINT_BYTES: Int = 512 * 1024
    const val MAX_TOTAL_CARD_TEXT_UTF8_BYTES: Int = 512 * 1024
}

private fun String.hasWellFormedSurrogatePairs(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(current) -> return false
            else -> index++
        }
    }
    return true
}

private fun String.hasOnlySupportedControls(): Boolean = none {
    it.isISOControl() && it != '\n' && it != '\r' && it != '\t'
}

/** Content digest deliberately excludes the monotonic revision and is stable across JSON codecs. */
object StudentExplanationDigest {
    fun sha256(
        target: StudentExplanationTarget,
        cards: List<StudentExplanationCard>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.putText("masternote-student-explanation-layer-v1")
        digest.putText(target.page.bookId)
        digest.putInt(target.page.pageNumber)
        digest.putInt(target.attemptNo)
        val ordered = cards.sortedBy(StudentExplanationCard::cardId)
        digest.putInt(ordered.size)
        ordered.forEach { card ->
            digest.putText(card.cardId)
            digest.putText(card.sourceResourceId)
            digest.putText(card.sourceResourceRevisionId)
            digest.putText(card.title)
            digest.putText(card.text)
            digest.putFloat(card.anchorBounds.left)
            digest.putFloat(card.anchorBounds.top)
            digest.putFloat(card.anchorBounds.right)
            digest.putFloat(card.anchorBounds.bottom)
            digest.putLong(card.createdAtEpochMillis)
            digest.putLong(card.updatedAtEpochMillis)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

private fun MessageDigest.putText(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    putInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.putInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.putLong(value: Long) {
    update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
}

private fun MessageDigest.putFloat(value: Float) = putInt(value.toRawBits())
