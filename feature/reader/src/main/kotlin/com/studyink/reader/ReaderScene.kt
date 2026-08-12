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
    }
}

object ReaderSceneIntentCodec {
    fun put(intent: Intent, scene: ReaderScene): Intent {
        intent.putExtra(KEY_REVISION, scene.documentRevisionId.value)
        intent.putExtra(KEY_PAGE, scene.initialPageId.value)
        intent.putExtra(KEY_POLICY, scene.interactionPolicy.name)
        val source = scene.visibleLayerSources.singleOrNull()
        when (source) {
            is EditableLiveLayer -> putLive(intent, "EDITABLE", source.target)
            is ReadOnlyLiveLayer -> putLive(intent, "READ_ONLY_LIVE", source.target)
            is ReadOnlySnapshot -> {
                intent.putExtra(KEY_SOURCE_KIND, "SNAPSHOT")
                when (val target = source.target) {
                    is SnapshotTarget.StudentSubmission -> intent.putExtra(KEY_TARGET_KIND, "SUBMISSION").putExtra(KEY_TARGET_ID, target.submissionId.value)
                    is SnapshotTarget.PublishedReview -> intent.putExtra(KEY_TARGET_KIND, "PUBLISHED_REVIEW").putExtra(KEY_TARGET_ID, target.reviewId.value)
                }
            }
            null -> intent.putExtra(KEY_SOURCE_KIND, "NONE")
        }
        return intent
    }

    fun from(intent: Intent): ReaderScene? {
        val revision = intent.getStringExtra(KEY_REVISION) ?: return null
        val page = intent.getStringExtra(KEY_PAGE) ?: return null
        val policy = ReaderInteractionPolicy.valueOf(intent.getStringExtra(KEY_POLICY) ?: return null)
        val source = when (intent.getStringExtra(KEY_SOURCE_KIND)) {
            "EDITABLE" -> EditableLiveLayer(readLive(intent))
            "READ_ONLY_LIVE" -> ReadOnlyLiveLayer(readLive(intent))
            "SNAPSHOT" -> ReadOnlySnapshot(when (intent.getStringExtra(KEY_TARGET_KIND)) {
                "SUBMISSION" -> SnapshotTarget.StudentSubmission(SubmissionId(requireNotNull(intent.getStringExtra(KEY_TARGET_ID))))
                "PUBLISHED_REVIEW" -> SnapshotTarget.PublishedReview(ReviewId(requireNotNull(intent.getStringExtra(KEY_TARGET_ID))))
                else -> error("Unknown snapshot target")
            })
            "NONE" -> null
            else -> error("Unknown Reader scene source")
        }
        return ReaderScene.create(BookRevisionId(revision), PageId(page), listOfNotNull(source), policy)
    }

    private fun putLive(intent: Intent, kind: String, target: LiveLayerTarget) {
        intent.putExtra(KEY_SOURCE_KIND, kind)
        when (target) {
            is LiveLayerTarget.StudentAttempt -> intent.putExtra(KEY_TARGET_KIND, "ATTEMPT").putExtra(KEY_TARGET_ID, target.attemptId.value)
            is LiveLayerTarget.TeacherPreparation -> intent.putExtra(KEY_TARGET_KIND, "PREPARATION")
                .putExtra(KEY_TARGET_ID, target.teacherId.value)
            is LiveLayerTarget.TeacherFeedback -> intent.putExtra(KEY_TARGET_KIND, "FEEDBACK").putExtra(KEY_TARGET_ID, target.reviewId.value)
        }
    }

    private fun readLive(intent: Intent): LiveLayerTarget = when (intent.getStringExtra(KEY_TARGET_KIND)) {
        "ATTEMPT" -> LiveLayerTarget.StudentAttempt(AttemptId(requireNotNull(intent.getStringExtra(KEY_TARGET_ID))))
        "PREPARATION" -> LiveLayerTarget.TeacherPreparation(
            TeacherId(requireNotNull(intent.getStringExtra(KEY_TARGET_ID))),
            BookRevisionId(requireNotNull(intent.getStringExtra(KEY_REVISION))),
        )
        "FEEDBACK" -> LiveLayerTarget.TeacherFeedback(ReviewId(requireNotNull(intent.getStringExtra(KEY_TARGET_ID))))
        else -> error("Unknown live target")
    }

    private const val KEY_REVISION = "com.studyink.reader.SCENE_REVISION"
    private const val KEY_PAGE = "com.studyink.reader.SCENE_PAGE"
    private const val KEY_POLICY = "com.studyink.reader.SCENE_POLICY"
    private const val KEY_SOURCE_KIND = "com.studyink.reader.SCENE_SOURCE_KIND"
    private const val KEY_TARGET_KIND = "com.studyink.reader.SCENE_TARGET_KIND"
    private const val KEY_TARGET_ID = "com.studyink.reader.SCENE_TARGET_ID"
}
