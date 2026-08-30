package com.studyink.assistant.core

import com.studyink.core.model.PageBounds

/** Stable page identity. It deliberately does not depend on the annotation or library catalog. */
data class AssistantPageKey(
    val bookId: String,
    val pageNumber: Int,
) {
    init {
        require(bookId.isNotBlank()) { "bookId is blank" }
        require(bookId.toByteArray(Charsets.UTF_8).size <= 512) { "bookId is too long" }
        require(pageNumber in 0..100_000) { "pageNumber is out of range" }
    }
}

/** One of four fixed-position, editable prompt choices. Slots cannot be inserted or removed. */
data class AssistantPromptSlot(
    val slotNumber: Int,
    val title: String,
    val body: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
)

/** Storage/rendering contract for one saved teacher answer revision. */
enum class TeacherGptAnswerFormat {
    /** Legacy answers and callers that do not opt in to richer formatting. */
    PLAIN_TEXT,

    /** Markdown text that may also contain TeX math delimiters. */
    MARKDOWN_TEX,
}

/** A saved GPT response revision. Old revisions remain readable after a new one is appended. */
data class TeacherGptResourceRevision(
    val revisionId: String,
    val revisionNumber: Long,
    val promptSlotNumber: Int,
    /** Snapshot of the editable prompt at request time. */
    val promptTitle: String,
    val promptBody: String,
    val selectionBounds: PageBounds,
    val answerText: String,
    val answerHtml: String?,
    val providerName: String?,
    val createdAtEpochMillis: Long,
    /** Defaults to the legacy behavior so existing Kotlin construction remains source-compatible. */
    val answerFormat: TeacherGptAnswerFormat = TeacherGptAnswerFormat.PLAIN_TEXT,
    /** Optional block visibility only; [answerText] always remains the immutable original. */
    val answerMask: TeacherGptAnswerMask? = null,
)

data class TeacherGptResource(
    val resourceId: String,
    val page: AssistantPageKey,
    val title: String,
    val createdAtEpochMillis: Long,
    val currentRevisionId: String,
    val revisions: List<TeacherGptResourceRevision>,
) {
    val currentRevision: TeacherGptResourceRevision
        get() = checkNotNull(revisions.firstOrNull { it.revisionId == currentRevisionId }) {
            "Current teacher GPT revision is missing"
        }
}

data class TeacherGptResourceSummary(
    val resourceId: String,
    val title: String,
    val currentRevisionId: String,
    val revisionCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Exact destination for a student-visible card layer. Attempts are intentionally not optional. */
data class StudentExplanationTarget(
    val page: AssistantPageKey,
    val attemptNo: Int,
) {
    init {
        require(attemptNo in 1..10_000) { "attemptNo is out of range" }
    }
}

/**
 * A teacher-edited excerpt shown as an openable card on one student attempt.
 *
 * [sourceResourceId] and [sourceResourceRevisionId] make a delayed publish auditable, while the
 * copied text keeps the card readable if the teacher later archives or removes the source answer.
 */
data class StudentExplanationCard(
    val cardId: String,
    val sourceResourceId: String,
    val sourceResourceRevisionId: String,
    val title: String,
    val text: String,
    val anchorBounds: PageBounds,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Full-state replace unit for exactly one page and one attempt. */
data class StudentExplanationLayer(
    val target: StudentExplanationTarget,
    val revision: Long,
    val digestSha256: String,
    val cards: List<StudentExplanationCard>,
    /** Teacher-installation authority. Local editable layers use [LOCAL_EXPLANATION_AUTHORITY]. */
    val authorityEpoch: String = LOCAL_EXPLANATION_AUTHORITY,
    /** Receiver-owned replay fence; never copied into an outgoing teacher publication. */
    val retiredAuthorityEpochs: Set<String> = emptySet(),
)

const val LOCAL_EXPLANATION_AUTHORITY: String = "local"

/** Durable latest-wins publication intent. The layer file remains the authoritative body. */
data class PendingStudentExplanationPublication(
    val publicationId: String,
    val target: StudentExplanationTarget,
    val revision: Long,
    val digestSha256: String,
    val queuedAtEpochMillis: Long,
    /** Rotated before obsolete Telegram transfers are cancelled during a route/pair handoff. */
    val deliveryAttempt: Long = 0L,
    /**
     * Exact application acknowledgement tombstone. Keeping one tombstone per target lets startup
     * distinguish an acknowledged layer from a layer committed just before a process crash.
     */
    val resolvedAtEpochMillis: Long? = null,
)

enum class StudentLayerApplyStatus {
    APPLIED,
    ALREADY_CURRENT,
    STALE,
    CONFLICT,
}

data class StudentLayerApplyResult(
    val status: StudentLayerApplyStatus,
    /** The locally authoritative layer after the operation. */
    val current: StudentExplanationLayer,
)

/** Explicitly bounded storage limits; defaults are intentionally generous for normal page use. */
data class AssistantStorageLimits(
    val maxPromptFileBytes: Int = 512 * 1024,
    val maxTeacherPageFileBytes: Int = 8 * 1024 * 1024,
    val maxStudentLayerFileBytes: Int = 4 * 1024 * 1024,
    val maxPendingPublicationFileBytes: Int = 2 * 1024 * 1024,
    val maxTeacherResourcesPerPage: Int = 128,
    val maxRevisionsPerResource: Int = 128,
    val maxCardsPerLayer: Int = 128,
    val maxPendingPublications: Int = 1_024,
    val maxPromptBodyUtf8Bytes: Int = 64 * 1024,
    val maxAnswerTextUtf8Bytes: Int = 512 * 1024,
    val maxAnswerHtmlUtf8Bytes: Int = 1024 * 1024,
    val maxStudentCardTextUtf8Bytes: Int = 256 * 1024,
) {
    init {
        require(maxPromptFileBytes in 1..16 * 1024 * 1024)
        require(maxTeacherPageFileBytes in 1..32 * 1024 * 1024)
        require(maxStudentLayerFileBytes in 1..16 * 1024 * 1024)
        require(maxPendingPublicationFileBytes in 1..8 * 1024 * 1024)
        require(maxTeacherResourcesPerPage in 1..1_024)
        require(maxRevisionsPerResource in 1..1_024)
        require(maxCardsPerLayer in 1..1_024)
        require(maxPendingPublications in 1..16_384)
        require(maxPromptBodyUtf8Bytes in 1..1024 * 1024)
        require(maxAnswerTextUtf8Bytes in 1..4 * 1024 * 1024)
        require(maxAnswerHtmlUtf8Bytes in 1..8 * 1024 * 1024)
        require(maxStudentCardTextUtf8Bytes in 1..2 * 1024 * 1024)
    }
}
