package com.studyink.reader

import android.content.Intent
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import com.studyink.core.model.ReviewId
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.TeacherId
import com.studyink.core.model.AttemptId

enum class ReaderInteractionPolicy { EDIT, OBSERVE, REVIEW }

sealed interface LiveLayerTarget {
    data class StudentAttempt(val attemptId: AttemptId) : LiveLayerTarget
    data class TeacherPreparation(val teacherId: TeacherId, val revisionId: BookRevisionId) : LiveLayerTarget
    data class TeacherFeedback(val reviewId: ReviewId) : LiveLayerTarget
}

sealed interface SnapshotTarget {
    data class StudentSubmission(val submissionId: SubmissionId) : SnapshotTarget
    data class PublishedReview(val reviewId: ReviewId) : SnapshotTarget
}

sealed interface ReaderLayerSource { val visibleByDefault: Boolean }
data class EditableLiveLayer(val target: LiveLayerTarget, override val visibleByDefault: Boolean = true) : ReaderLayerSource
data class ReadOnlyLiveLayer(val target: LiveLayerTarget, override val visibleByDefault: Boolean = true) : ReaderLayerSource
data class ReadOnlySnapshot(val target: SnapshotTarget, override val visibleByDefault: Boolean = true) : ReaderLayerSource
data class ReadOnlyRemoteLayer(val remoteSessionId: String, override val visibleByDefault: Boolean = true) : ReaderLayerSource

class ReaderScene private constructor(
    val documentRevisionId: BookRevisionId,
    val initialPageId: PageId,
    val visibleLayerSources: List<ReaderLayerSource>,
    val interactionPolicy: ReaderInteractionPolicy,
) {
    val editableLayerSource: EditableLiveLayer? = visibleLayerSources.filterIsInstance<EditableLiveLayer>().singleOrNull()

    companion object {
        fun create(
            documentRevisionId: BookRevisionId,
            initialPageId: PageId,
            visibleLayerSources: List<ReaderLayerSource>,
            interactionPolicy: ReaderInteractionPolicy,
        ): ReaderScene {
            require(visibleLayerSources.count { it is EditableLiveLayer } <= 1) {
                "A ReaderScene can contain at most one editable layer"
            }
            require(interactionPolicy == ReaderInteractionPolicy.EDIT || visibleLayerSources.none { it is EditableLiveLayer }) {
                "Read-only scenes cannot contain an editable layer"
            }
            return ReaderScene(documentRevisionId, initialPageId, visibleLayerSources, interactionPolicy)
        }

        fun teacherPreparation(teacherId: TeacherId, revisionId: BookRevisionId, pageId: PageId) = create(
            revisionId, pageId,
            listOf(EditableLiveLayer(LiveLayerTarget.TeacherPreparation(teacherId, revisionId))),
            ReaderInteractionPolicy.EDIT,
        )

        fun attemptObservation(revisionId: BookRevisionId, attemptId: AttemptId, pageId: PageId) = create(
            revisionId, pageId,
            listOf(ReadOnlyLiveLayer(LiveLayerTarget.StudentAttempt(attemptId))),
            ReaderInteractionPolicy.OBSERVE,
        )

        fun submissionReview(
            revisionId: BookRevisionId,
            submissionId: SubmissionId,
            reviewId: ReviewId,
            pageId: PageId,
            teacherId: TeacherId? = null,
        ) = create(
            revisionId,
            pageId,
            buildList {
                add(ReadOnlySnapshot(SnapshotTarget.StudentSubmission(submissionId)))
                teacherId?.let {
                    add(ReadOnlyLiveLayer(LiveLayerTarget.TeacherPreparation(it, revisionId), visibleByDefault = false))
                }
                add(EditableLiveLayer(LiveLayerTarget.TeacherFeedback(reviewId)))
            },
            ReaderInteractionPolicy.EDIT,
        )

        fun publishedReview(
            revisionId: BookRevisionId,
            submissionId: SubmissionId,
            reviewId: ReviewId,
            pageId: PageId,
        ) = create(
            revisionId,
            pageId,
            listOf(
                ReadOnlySnapshot(SnapshotTarget.StudentSubmission(submissionId)),
                ReadOnlySnapshot(SnapshotTarget.PublishedReview(reviewId)),
            ),
            ReaderInteractionPolicy.REVIEW,
        )

        fun remoteObservation(revisionId: BookRevisionId, remoteSessionId: String, pageId: PageId) = create(
            revisionId, pageId, listOf(ReadOnlyRemoteLayer(remoteSessionId)), ReaderInteractionPolicy.OBSERVE,
        )
    }
}

object ReaderSceneIntentCodec {
    fun put(intent: Intent, scene: ReaderScene): Intent {
        intent.putExtra(KEY_REVISION, scene.documentRevisionId.value)
        intent.putExtra(KEY_PAGE, scene.initialPageId.value)
        intent.putExtra(KEY_POLICY, scene.interactionPolicy.name)
        intent.putStringArrayListExtra(KEY_SOURCES, ArrayList(scene.visibleLayerSources.map(::encodeSource)))
        return intent
    }

    fun from(intent: Intent): ReaderScene? {
        val revision = intent.getStringExtra(KEY_REVISION) ?: return null
        val page = intent.getStringExtra(KEY_PAGE) ?: return null
        val policy = ReaderInteractionPolicy.valueOf(intent.getStringExtra(KEY_POLICY) ?: return null)
        val revisionId = BookRevisionId(revision)
        val sources = intent.getStringArrayListExtra(KEY_SOURCES)?.map { decodeSource(it, revisionId) }.orEmpty()
        return ReaderScene.create(revisionId, PageId(page), sources, policy)
    }

    private fun encodeSource(source: ReaderLayerSource): String = when (source) {
        is EditableLiveLayer -> "E:${encodeLive(source.target)}:${source.visibleByDefault}"
        is ReadOnlyLiveLayer -> "L:${encodeLive(source.target)}:${source.visibleByDefault}"
        is ReadOnlySnapshot -> when (val target = source.target) {
            is SnapshotTarget.StudentSubmission -> "S:SUBMISSION:${target.submissionId.value}:${source.visibleByDefault}"
            is SnapshotTarget.PublishedReview -> "S:REVIEW:${target.reviewId.value}:${source.visibleByDefault}"
        }
        is ReadOnlyRemoteLayer -> "R:REMOTE:${source.remoteSessionId}:${source.visibleByDefault}"
    }

    private fun encodeLive(target: LiveLayerTarget): String = when (target) {
        is LiveLayerTarget.StudentAttempt -> "ATTEMPT:${target.attemptId.value}"
        is LiveLayerTarget.TeacherPreparation -> "PREPARATION:${target.teacherId.value}"
        is LiveLayerTarget.TeacherFeedback -> "FEEDBACK:${target.reviewId.value}"
    }

    private fun decodeSource(encoded: String, revisionId: BookRevisionId): ReaderLayerSource {
        val parts = encoded.split(':', limit = 4)
        require(parts.size == 4)
        val visible = parts[3].toBooleanStrict()
        val targetId = parts[2]
        return when (parts[0]) {
            "E" -> EditableLiveLayer(decodeLive(parts[1], targetId, revisionId), visible)
            "L" -> ReadOnlyLiveLayer(decodeLive(parts[1], targetId, revisionId), visible)
            "S" -> ReadOnlySnapshot(
                when (parts[1]) {
                    "SUBMISSION" -> SnapshotTarget.StudentSubmission(SubmissionId(targetId))
                    "REVIEW" -> SnapshotTarget.PublishedReview(ReviewId(targetId))
                    else -> error("Unknown snapshot target")
                }, visible,
            )
            "R" -> ReadOnlyRemoteLayer(targetId, visible)
            else -> error("Unknown Reader layer source")
        }
    }

    private fun decodeLive(kind: String, id: String, revisionId: BookRevisionId): LiveLayerTarget = when (kind) {
        "ATTEMPT" -> LiveLayerTarget.StudentAttempt(AttemptId(id))
        "PREPARATION" -> LiveLayerTarget.TeacherPreparation(TeacherId(id), revisionId)
        "FEEDBACK" -> LiveLayerTarget.TeacherFeedback(ReviewId(id))
        else -> error("Unknown live target")
    }

    private const val KEY_REVISION = "com.studyink.reader.SCENE_REVISION"
    private const val KEY_PAGE = "com.studyink.reader.SCENE_PAGE"
    private const val KEY_POLICY = "com.studyink.reader.SCENE_POLICY"
    private const val KEY_SOURCES = "com.studyink.reader.SCENE_SOURCES"
}
