package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.ActivityPage
import com.studyink.core.model.AnswerType
import com.studyink.core.model.AttemptStatus
import com.studyink.core.model.BookRevision
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.DraftAnswer
import com.studyink.core.model.LearnerProfile
import com.studyink.core.model.LearningActivity
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.LearningActivitySeed
import com.studyink.core.model.LearningContentSeed
import com.studyink.core.model.PageId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.ProfileId
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.core.model.SubmissionMode
import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteDurableOperationBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoomLearningRepositoryTest {
    private lateinit var database: AnnotationDatabase
    private val now = AtomicLong(1_000L)

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AnnotationDatabase::class.java).build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun firstOpenCreatesAttemptOneAndSecondOpenReusesIt() = runTest {
        val repository = repository("attempt-1")
        repository.ensureContent(seed())

        val first = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val second = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)

        assertEquals("attempt-1", first.attempt.attemptId.value)
        assertEquals(1, first.attempt.attemptNumber)
        assertEquals(first, second)
        assertEquals(AttemptStatus.IN_PROGRESS, first.attempt.status)
    }

    @Test
    fun submittedAndAbandonedAttemptsAreNotResumed() = runTest {
        val repository = repository("attempt-1", "submission-1", "attempt-2", "attempt-3")
        repository.ensureContent(seed())
        val first = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        repository.submitAttempt(first.attempt.attemptId)

        val second = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        assertEquals(2, second.attempt.attemptNumber)
        repository.abandonAttempt(second.attempt.attemptId)

        val third = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        assertEquals(3, third.attempt.attemptNumber)
        assertNotEquals(second.attempt.attemptId, third.attempt.attemptId)
    }

    @Test
    fun differentProfilesReceiveDifferentAttempts() = runTest {
        val repository = repository("attempt-student-1", "attempt-student-2")
        repository.ensureContent(seed(PROFILE))
        repository.ensureContent(seed(ProfileId("sibling")))

        val student = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val sibling = repository.getOrCreateActiveAttempt(ProfileId("sibling"), ACTIVITY)

        assertNotEquals(student.attempt.attemptId, sibling.attempt.attemptId)
        assertEquals(1, student.attempt.attemptNumber)
        assertEquals(1, sibling.attempt.attemptNumber)
    }

    @Test
    fun resumePageIsLazyCreatedAndRestored() = runTest {
        val repository = repository("attempt-1")
        repository.ensureContent(seed())
        val session = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        assertEquals(1, database.learningDao().attemptPages(session.attempt.attemptId.value).size)

        repository.updateResumePage(session.attempt.attemptId, PAGE_TWO)

        val restored = repository.getAttemptSession(session.attempt.attemptId)
        assertEquals(PAGE_TWO, restored.initialPageId)
        assertEquals(2, database.learningDao().attemptPages(session.attempt.attemptId.value).size)
    }

    @Test
    fun workingLayersAreIsolatedBetweenAttempts() = runTest {
        val repository = repository("attempt-1", "submission-1", "attempt-2")
        repository.ensureContent(seed())
        val annotationStore = RoomAnnotationStore(database)
        val first = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val firstDocument = AnnotationDocument(
            annotationStore.load(DOCUMENT_ID, first.attempt.attemptId.value)
        )
        annotationStore.applyMutation(
            firstDocument.addStroke(stroke()),
            first.attempt.attemptId.value,
        )
        repository.submitAttempt(first.attempt.attemptId)

        val second = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val firstRestored = annotationStore.load(DOCUMENT_ID, first.attempt.attemptId.value)
        val secondRestored = annotationStore.load(DOCUMENT_ID, second.attempt.attemptId.value)

        assertEquals(1, firstRestored.activeStrokes.size)
        assertTrue(secondRestored.activeStrokes.isEmpty())
    }

    @Test
    fun duplicateSubmitReturnsOneImmutableSnapshotWithoutCopyingStrokeBlobs() = runTest {
        val repository = repository("attempt-1", "submission-1", "attempt-2")
        repository.ensureContent(seed())
        val annotationStore = RoomAnnotationStore(database)
        val first = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val document = AnnotationDocument(annotationStore.load(DOCUMENT_ID, first.attempt.attemptId.value))
        annotationStore.applyMutation(document.addStroke(stroke()), first.attempt.attemptId.value)
        annotationStore.applyMutation(
            document.addStroke(stroke()),
            first.attempt.attemptId.value,
        )
        repository.upsertDraftAnswer(
            DraftAnswer(first.attempt.attemptId, "answer-1", AnswerType.TEXT, "\"first\"", 2_000L)
        )
        val blobCountBefore = database.learningDao().strokeAssetCount()

        val submitted = repository.submitAttempt(first.attempt.attemptId)
        val duplicate = repository.submitAttempt(first.attempt.attemptId)

        assertEquals(submitted, duplicate)
        assertEquals(1, database.learningDao().submissionCountForAttempt(first.attempt.attemptId.value))
        assertEquals(2, database.learningDao().submissionStrokeRefCount(submitted.value))
        assertEquals(1, database.learningDao().submissionAnswerCount(submitted.value))
        assertEquals(blobCountBefore, database.learningDao().strokeAssetCount())
        val snapshot = repository.getSubmission(submitted)
        assertEquals("\"first\"", snapshot.answers.single().valueJson)
        assertEquals(2, snapshot.strokes.size)

        val second = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        repository.upsertDraftAnswer(
            DraftAnswer(second.attempt.attemptId, "answer-1", AnswerType.TEXT, "\"second\"", 3_000L)
        )
        assertEquals("\"first\"", repository.getSubmission(submitted).answers.single().valueJson)
    }

    @Test
    fun everyInjectedSubmissionFailureRollsBackTheWholeTransaction() = runTest {
        val repository = repository("attempt-1")
        repository.ensureContent(seed())
        val attempt = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        repository.upsertDraftAnswer(
            DraftAnswer(attempt.attempt.attemptId, "answer", AnswerType.TEXT, "\"draft\"", 2_000L)
        )
        val annotationStore = RoomAnnotationStore(database)
        val document = AnnotationDocument(annotationStore.load(DOCUMENT_ID, attempt.attempt.attemptId.value))
        annotationStore.applyMutation(document.addStroke(stroke()), attempt.attempt.attemptId.value)

        SubmissionFailurePoint.entries.forEachIndexed { index, failurePoint ->
            val failing = repository(
                "submission-failure-$index",
                injector = SubmissionFaultInjector { point ->
                    if (point == failurePoint) throw IOException("injected $point")
                },
            )
            val error = runCatching { failing.submitAttempt(attempt.attempt.attemptId) }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(0, database.learningDao().submissionCountForAttempt(attempt.attempt.attemptId.value))
            assertEquals(AttemptStatus.IN_PROGRESS, repository.getAttemptSession(attempt.attempt.attemptId).attempt.status)
        }
    }

    @Test
    fun submittedLayerRejectsFurtherMutationAndReadOnlySnapshotKeepsOriginalStroke() = runTest {
        val repository = repository("attempt-1", "submission-1")
        repository.ensureContent(seed())
        val annotationStore = RoomAnnotationStore(database)
        val attempt = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val document = AnnotationDocument(annotationStore.load(DOCUMENT_ID, attempt.attempt.attemptId.value))
        val original = stroke()
        annotationStore.applyMutation(document.addStroke(original), attempt.attempt.attemptId.value)
        val submissionId = repository.submitAttempt(attempt.attempt.attemptId)

        val mutationAfterSubmit = document.addStroke(stroke())
        val failure = runCatching {
            annotationStore.applyMutation(mutationAfterSubmit, attempt.attempt.attemptId.value)
        }.exceptionOrNull()
        val submittedSnapshot = annotationStore.loadSubmission(DOCUMENT_ID, submissionId.value)

        assertTrue(failure is IllegalStateException)
        assertEquals(setOf(original.id), submittedSnapshot.activeStrokeIds)
    }

    @Test
    fun flushFailurePreventsSubmissionTransactionFromStarting() = runTest {
        val repository = repository("attempt-1", "unused-submission")
        repository.ensureContent(seed())
        val attempt = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)

        val failure = runCatching {
            SubmitAttemptUseCase(repository).invoke(attempt.attempt.attemptId) {
                throw IOException("flush failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, database.learningDao().submissionCountForAttempt(attempt.attempt.attemptId.value))
        assertEquals(AttemptStatus.IN_PROGRESS, repository.getAttemptSession(attempt.attempt.attemptId).attempt.status)
    }

    @Test
    fun annotationMutationAndRemoteOutboxAreCommittedAndRolledBackTogether() = runTest {
        val repository = repository("attempt-remote")
        repository.ensureContent(seed())
        val attempt = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val annotationStore = RoomAnnotationStore(database)
        val document = AnnotationDocument(
            annotationStore.load(DOCUMENT_ID, attempt.attempt.attemptId.value)
        )
        val request = RemoteOutboxRequest(
            sessionId = "remote-session",
            senderDeviceId = "student-device",
            messageId = "message-1",
            sentElapsedRealtimeMs = 10L,
            createdAtEpochMillis = 2_000L,
        )

        val sequence = annotationStore.applyMutationAndEnqueueRemote(
            document.addStroke(stroke()), attempt.attempt.attemptId.value, request,
        )

        assertEquals(1L, sequence)
        val stored = database.remoteDao().pending("remote-session", 64).single()
        val envelope = ProtobufRemoteMessageCodec().decode(stored.encodedEnvelope)
        val batch = envelope.payload as RemoteDurableOperationBatch
        assertEquals(1, batch.operations.size)
        assertEquals(stored.operationId, batch.operations.single().operationId)

        val failingStore = RoomAnnotationStore(
            database,
            faultInjector = AnnotationTransactionFaultInjector { throw IOException("injected") },
        )
        val failure = runCatching {
            failingStore.applyMutationAndEnqueueRemote(
                document.addStroke(stroke()),
                attempt.attempt.attemptId.value,
                request.copy(messageId = "message-2"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(1, database.remoteDao().pendingCount("remote-session"))
        assertEquals(1, annotationStore.load(DOCUMENT_ID, attempt.attempt.attemptId.value).activeStrokes.size)
    }

    @Test
    fun progressIsProjectedFromAttemptsAndSubmissionsWithoutMutableCounters() = runTest {
        val repository = repository(
            "attempt-1", "submission-1",
            "attempt-2", "submission-2",
            "attempt-3", "submission-3",
        )
        repository.ensureContent(seed())

        fun progress() = repository.observeActivitiesWithProgress(PROFILE, REVISION)
        assertEquals(0, progress().first().single().submissionCount)
        assertTrue(!progress().first().single().hasDraft)

        val first = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        assertEquals(0, progress().first().single().submissionCount)
        assertTrue(progress().first().single().hasDraft)
        repository.submitAttempt(first.attempt.attemptId)
        assertEquals(1, progress().first().single().submissionCount)
        assertTrue(!progress().first().single().hasDraft)

        val second = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val submittedAndDraft = progress().first().single()
        assertEquals(1, submittedAndDraft.submissionCount)
        assertTrue(submittedAndDraft.hasDraft)
        repository.submitAttempt(second.attempt.attemptId)

        val third = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        repository.submitAttempt(third.attempt.attemptId)
        val final = progress().first().single()
        assertEquals(3, final.attemptCount)
        assertEquals(3, final.submissionCount)
        assertTrue(!final.hasDraft)
    }

    private fun repository(
        vararg ids: String,
        injector: SubmissionFaultInjector = SubmissionFaultInjector.NONE,
    ) = RoomLearningRepository(
        database = database,
        dispatcher = Dispatchers.Unconfined,
        clock = LearningClock { now.getAndIncrement() },
        idGenerator = LearningIdGenerator(ids.iterator()::next),
        submissionFaultInjector = injector,
    )

    private fun seed(profileId: ProfileId = PROFILE): LearningContentSeed = LearningContentSeed(
        profile = LearnerProfile(profileId, "학생", 1L),
        bookRevision = BookRevision(
            REVISION,
            "book",
            DOCUMENT_ID,
            1,
            "hash",
            "테스트 책",
            1L,
        ),
        activities = listOf(
            LearningActivitySeed(
                LearningActivity(ACTIVITY, REVISION, "Unit", 0, SubmissionMode.INK_ONLY),
                listOf(
                    ActivityPage(PAGE_ONE, 0, 0),
                    ActivityPage(PAGE_TWO, 1, 1),
                ),
            )
        ),
    )

    private fun stroke() = StrokeAsset(
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 4f,
        points = listOf(PagePoint(10f, 10f), PagePoint(20f, 20f)),
    )

    private companion object {
        val PROFILE = ProfileId("default-student")
        val REVISION = BookRevisionId("revision-1")
        val ACTIVITY = LearningActivityId("activity-1")
        const val DOCUMENT_ID = "document-1"
        val PAGE_ONE = PageId("$DOCUMENT_ID:page:0")
        val PAGE_TWO = PageId("$DOCUMENT_ID:page:1")
    }
}
