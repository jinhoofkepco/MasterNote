package com.studyink.annotation.storage

import android.content.Context
import androidx.room.withTransaction
import com.studyink.core.model.ActivityPage
import com.studyink.core.model.ActivityProgress
import com.studyink.core.model.AnswerType
import com.studyink.core.model.Attempt
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptSession
import com.studyink.core.model.AttemptStatus
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.DraftAnswer
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.LearningContentSeed
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionAnswer
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.SubmissionSnapshot
import com.studyink.core.model.SubmissionStroke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

fun interface LearningIdGenerator {
    fun nextId(): String
}

fun interface LearningClock {
    fun nowEpochMillis(): Long
}

class RoomLearningRepository internal constructor(
    private val database: AnnotationDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: LearningClock = LearningClock(System::currentTimeMillis),
    private val idGenerator: LearningIdGenerator = LearningIdGenerator { UUID.randomUUID().toString() },
) : LearningRepository {
    private val dao = database.learningDao()
    private val annotationDao = database.annotationDao()

    override suspend fun ensureContent(seed: LearningContentSeed) = withContext(dispatcher) {
        database.withTransaction {
            dao.insertProfile(seed.profile.toEntity())
            dao.insertBookRevision(seed.bookRevision.toEntity())
            seed.activities.forEach { activitySeed ->
                check(activitySeed.activity.revisionId == seed.bookRevision.revisionId)
                dao.insertActivity(activitySeed.activity.toEntity())
                dao.insertActivityPages(
                    activitySeed.pages.map { page ->
                        ActivityPageRefEntity(
                            activityId = activitySeed.activity.activityId.value,
                            pageId = page.pageId.value,
                            pageNumber = page.pageNumber,
                            pageOrder = page.pageOrder,
                        )
                    }
                )
            }
        }
    }

    override fun observeActivitiesWithProgress(
        profileId: ProfileId,
        revisionId: BookRevisionId,
    ): Flow<List<ActivityProgress>> = dao.observeActivityProgress(profileId.value, revisionId.value)
        .map { rows -> rows.map(ActivityProgressRow::toDomain) }

    override suspend fun getOrCreateActiveAttempt(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession = withContext(dispatcher) {
        database.withTransaction {
            dao.activeAttempt(profileId.value, activityId.value)?.let { return@withTransaction session(it) }
            createAttempt(profileId, activityId)
        }
    }

    override suspend fun startNewAttempt(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession = withContext(dispatcher) {
        database.withTransaction {
            check(dao.activeAttempt(profileId.value, activityId.value) == null) {
                "An IN_PROGRESS attempt already exists for ${activityId.value}"
            }
            createAttempt(profileId, activityId)
        }
    }

    override suspend fun getAttemptSession(attemptId: AttemptId): AttemptSession = withContext(dispatcher) {
        database.withTransaction {
            session(requireNotNull(dao.attempt(attemptId.value)) { "Unknown attempt ${attemptId.value}" })
        }
    }

    override suspend fun updateResumePage(attemptId: AttemptId, pageId: PageId) = withContext(dispatcher) {
        database.withTransaction {
            val attempt = requireNotNull(dao.attempt(attemptId.value))
            check(dao.activityPages(attempt.activityId).any { it.pageId == pageId.value }) {
                "Page ${pageId.value} does not belong to activity ${attempt.activityId}"
            }
            ensureAttemptPage(attempt, pageId.value, clock.nowEpochMillis())
            check(dao.updateResumePage(attemptId.value, pageId.value, clock.nowEpochMillis()) == 1) {
                "Only an IN_PROGRESS attempt can update its resume page"
            }
        }
    }

    override suspend fun prepareAttemptPage(attemptId: AttemptId, pageId: PageId) = withContext(dispatcher) {
        database.withTransaction {
            val attempt = requireNotNull(dao.attempt(attemptId.value))
            check(attempt.status == AttemptStatus.IN_PROGRESS.name)
            check(dao.activityPages(attempt.activityId).any { it.pageId == pageId.value })
            ensureAttemptPage(attempt, pageId.value, clock.nowEpochMillis())
            Unit
        }
    }

    override suspend fun abandonAttempt(attemptId: AttemptId) = withContext(dispatcher) {
        check(dao.abandonAttempt(attemptId.value, clock.nowEpochMillis()) == 1) {
            "Only an IN_PROGRESS attempt can be abandoned"
        }
    }

    override suspend fun upsertDraftAnswer(answer: DraftAnswer) = withContext(dispatcher) {
        val attempt = requireNotNull(dao.attempt(answer.attemptId.value))
        check(attempt.status == AttemptStatus.IN_PROGRESS.name) { "Submitted answers are immutable" }
        dao.upsertDraftAnswer(
            DraftAnswerEntity(
                attemptId = answer.attemptId.value,
                fieldId = answer.fieldId,
                answerType = answer.answerType.name,
                valueJson = answer.valueJson,
                updatedAtEpochMillis = answer.updatedAtEpochMillis,
            )
        )
    }

    override suspend fun submitAttempt(attemptId: AttemptId): SubmissionId = withContext(dispatcher) {
        database.withTransaction {
            dao.submissionForAttempt(attemptId.value)?.let { return@withTransaction SubmissionId(it.submissionId) }
            val attempt = requireNotNull(dao.attempt(attemptId.value)) { "Unknown attempt ${attemptId.value}" }
            check(attempt.status == AttemptStatus.IN_PROGRESS.name) { "Attempt is not submit-ready" }
            val now = clock.nowEpochMillis()
            val submissionId = SubmissionId(idGenerator.nextId())
            dao.insertSubmission(
                SubmissionEntity(
                    submissionId = submissionId.value,
                    attemptId = attemptId.value,
                    submittedAtEpochMillis = now,
                    annotationRevision = dao.annotationRevisionForAttempt(attemptId.value),
                )
            )
            val strokes = dao.activeStrokesForAttempt(attemptId.value).map { row ->
                SubmissionStrokeRefEntity(submissionId.value, row.pageId, row.strokeId, row.zOrder)
            }
            if (strokes.isNotEmpty()) dao.insertSubmissionStrokeRefs(strokes)
            val answers = dao.draftAnswers(attemptId.value).map { answer ->
                SubmissionAnswerEntity(submissionId.value, answer.fieldId, answer.answerType, answer.valueJson)
            }
            if (answers.isNotEmpty()) dao.insertSubmissionAnswers(answers)
            check(dao.markAttemptSubmitted(attemptId.value, now) == 1)
            submissionId
        }
    }

    override suspend fun getSubmission(submissionId: SubmissionId): SubmissionSnapshot = withContext(dispatcher) {
        database.withTransaction {
            val submission = requireNotNull(dao.submission(submissionId.value))
            SubmissionSnapshot(
                submissionId = submissionId,
                attemptId = AttemptId(submission.attemptId),
                submittedAtEpochMillis = submission.submittedAtEpochMillis,
                annotationRevision = submission.annotationRevision,
                strokes = dao.submissionStrokeRefs(submissionId.value).map { row ->
                    SubmissionStroke(PageId(row.pageId), com.studyink.core.model.StrokeId(row.strokeId), row.zOrder)
                },
                answers = dao.submissionAnswers(submissionId.value).map { row ->
                    SubmissionAnswer(row.fieldId, AnswerType.valueOf(row.answerType), row.valueJson)
                },
            )
        }
    }

    fun close() = database.close()

    private suspend fun createAttempt(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession {
        val activity = requireNotNull(dao.activity(activityId.value)) { "Unknown activity ${activityId.value}" }
        val pages = dao.activityPages(activityId.value)
        require(pages.isNotEmpty()) { "Activity ${activityId.value} has no pages" }
        val now = clock.nowEpochMillis()
        val entity = AttemptEntity(
            attemptId = idGenerator.nextId(),
            profileId = profileId.value,
            activityId = activityId.value,
            revisionId = activity.revisionId,
            attemptNumber = dao.maxAttemptNumber(profileId.value, activityId.value) + 1,
            status = AttemptStatus.IN_PROGRESS.name,
            lastVisitedPageId = pages.first().pageId,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
            submittedAtEpochMillis = null,
        )
        dao.insertAttempt(entity)
        ensureAttemptPage(entity, pages.first().pageId, now)
        return session(entity)
    }

    private suspend fun ensureAttemptPage(attempt: AttemptEntity, pageId: String, now: Long): AttemptPageEntity {
        dao.attemptPage(attempt.attemptId, pageId)?.let {
            dao.touchAttemptPage(attempt.attemptId, pageId, now)
            return it
        }
        val revision = requireNotNull(dao.bookRevision(attempt.revisionId))
        val page = requireNotNull(dao.activityPages(attempt.activityId).firstOrNull { it.pageId == pageId })
        annotationDao.insertDocument(AnnotationDocumentEntity(revision.documentId, 0L, now))
        annotationDao.insertPage(AnnotationPageEntity(pageId, revision.documentId, page.pageNumber, 0L, now))
        val layerId = AnnotationIds.attemptLayerId(pageId, attempt.attemptId)
        annotationDao.insertLayer(
            AnnotationLayerEntity(
                layerId = layerId,
                pageId = pageId,
                attemptId = attempt.attemptId,
                layerType = com.studyink.core.model.AnnotationLayerType.STUDENT_WORKING.name,
                ownerType = com.studyink.core.model.AnnotationOwnerType.STUDENT.name,
                currentRevision = 0L,
                createdAtEpochMillis = now,
            )
        )
        val attemptPage = AttemptPageEntity(attempt.attemptId, pageId, layerId, now)
        dao.insertAttemptPage(attemptPage)
        return attemptPage
    }

    private suspend fun session(attempt: AttemptEntity): AttemptSession {
        val activity = requireNotNull(dao.activity(attempt.activityId))
        val revision = requireNotNull(dao.bookRevision(activity.revisionId))
        val pages = dao.activityPages(activity.activityId).map {
            ActivityPage(PageId(it.pageId), it.pageNumber, it.pageOrder)
        }
        val initial = attempt.lastVisitedPageId?.takeIf { id -> pages.any { it.pageId.value == id } }
            ?: pages.first().pageId.value
        return AttemptSession(attempt.toDomain(), revision.documentId, PageId(initial), pages)
    }

    companion object {
        suspend fun open(context: Context): RoomLearningRepository = withContext(Dispatchers.IO) {
            RoomLearningRepository(AnnotationDatabase.open(context))
        }
    }
}

internal object AnnotationIds {
    fun attemptLayerId(pageId: String, attemptId: String): String = "$pageId:attempt:$attemptId:student-working"
}

private fun com.studyink.core.model.LearnerProfile.toEntity() = LearnerProfileEntity(
    profileId.value, displayName, createdAtEpochMillis
)

private fun com.studyink.core.model.BookRevision.toEntity() = BookRevisionEntity(
    revisionId.value, bookId, documentId, revisionNumber, contentHash, title, createdAtEpochMillis
)

private fun com.studyink.core.model.LearningActivity.toEntity() = LearningActivityEntity(
    activityId.value, revisionId.value, title, sortOrder, submissionMode.name
)

private fun AttemptEntity.toDomain() = Attempt(
    attemptId = AttemptId(attemptId),
    profileId = ProfileId(profileId),
    activityId = LearningActivityId(activityId),
    revisionId = BookRevisionId(revisionId),
    attemptNumber = attemptNumber,
    status = AttemptStatus.valueOf(status),
    lastVisitedPageId = lastVisitedPageId?.let(::PageId),
    startedAtEpochMillis = startedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    submittedAtEpochMillis = submittedAtEpochMillis,
)

private fun ActivityProgressRow.toDomain() = ActivityProgress(
    activityId = LearningActivityId(activityId),
    title = title,
    sortOrder = sortOrder,
    attemptCount = attemptCount,
    submissionCount = submissionCount,
    hasDraft = hasDraft,
    latestAttemptId = latestAttemptId?.let(::AttemptId),
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    lastSubmittedAtEpochMillis = lastSubmittedAtEpochMillis,
)
