package com.studyink.reader

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.Attempt
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWorkflowTest {
    @Test
    fun defaultWorkflowMatchesReaderRole() {
        assertEquals(ReaderWorkflow.STUDY, ReaderWorkflow.defaultFor(ReaderRole.STUDENT))
        assertEquals(ReaderWorkflow.REVIEW, ReaderWorkflow.defaultFor(ReaderRole.TEACHER_TABLET))
        assertEquals(ReaderWorkflow.LIVE_MONITOR, ReaderWorkflow.defaultFor(ReaderRole.TEACHER_PHONE))
    }

    @Test
    fun teacherPageTargetUsesPageLabelInReviewAndLiveMonitor() {
        listOf(ReaderWorkflow.REVIEW, ReaderWorkflow.LIVE_MONITOR).forEach { workflow ->
            val state = ReaderUiState(
                attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                role = ReaderRole.TEACHER_TABLET,
                workflow = workflow,
            )

            assertTrue(state.isTeacherPageTarget)
            assertEquals("페이지 표시", state.attemptDisplayLabel)
        }
    }

    @Test
    fun actualAttemptKeepsNumberedLabel() {
        val state = ReaderUiState(
            attemptNo = 2,
            role = ReaderRole.TEACHER_TABLET,
            workflow = ReaderWorkflow.REVIEW,
        )

        assertFalse(state.isTeacherPageTarget)
        assertEquals("2회", state.attemptDisplayLabel)
    }

    @Test
    fun phoneReviewMatchesTabletReviewWhileLiveMonitorKeepsLiveActions() {
        val review = ReaderCapabilities.forSession(
            ReaderRole.TEACHER_PHONE,
            ReaderWorkflow.REVIEW,
            attemptNo = 1,
        )
        val live = ReaderCapabilities.forSession(
            ReaderRole.TEACHER_PHONE,
            ReaderWorkflow.LIVE_MONITOR,
            attemptNo = 1,
        )
        val pageLevelLive = ReaderCapabilities.forSession(
            ReaderRole.TEACHER_PHONE,
            ReaderWorkflow.LIVE_MONITOR,
            attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
        )

        assertFalse(review.showsStudentLocation)
        assertFalse(review.canPublishTeacherInk)
        assertTrue(live.showsStudentLocation)
        assertTrue(live.canPublishTeacherInk)
        assertFalse(pageLevelLive.canPublishTeacherInk)
    }

    @Test
    fun liveMonitorUsesTheAttemptObservedInRemoteStrokeData() {
        assertEquals(
            2,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = emptyList(),
                observedStudentAttemptNos = setOf(1, 2),
            ),
        )
    }

    @Test
    fun liveMonitorWithoutAttemptMetadataOrObservedInkUsesPageLevelTarget() {
        assertEquals(
            TEACHER_PAGE_REVIEW_ATTEMPT_NO,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = emptyList(),
                observedStudentAttemptNos = emptySet(),
            ),
        )
    }

    @Test
    fun submitAndPublishStayDisabledUntilTheMatchingDocumentIsReady() {
        val submitState = ReaderUiState(
            documentReady = false,
            role = ReaderRole.STUDENT,
            currentAttemptWritable = true,
            capabilities = ReaderCapabilities.forSession(
                ReaderRole.STUDENT,
                ReaderWorkflow.STUDY,
                attemptNo = 1,
            ),
        )
        val publishState = ReaderUiState(
            documentReady = false,
            attemptNo = 1,
            role = ReaderRole.TEACHER_PHONE,
            workflow = ReaderWorkflow.LIVE_MONITOR,
            capabilities = ReaderCapabilities.forSession(
                ReaderRole.TEACHER_PHONE,
                ReaderWorkflow.LIVE_MONITOR,
                attemptNo = 1,
            ),
        )

        assertFalse(submitState.canSubmitNow)
        assertTrue(submitState.copy(documentReady = true).canSubmitNow)
        assertFalse(submitState.copy(documentReady = true, storageAvailable = false).canSubmitNow)
        assertFalse(publishState.canPublishTeacherInkNow)
        assertTrue(publishState.copy(documentReady = true).canPublishTeacherInkNow)
        assertFalse(
            publishState.copy(
                documentReady = true,
                attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                capabilities = ReaderCapabilities.forSession(
                    ReaderRole.TEACHER_PHONE,
                    ReaderWorkflow.LIVE_MONITOR,
                    attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                ),
            ).canPublishTeacherInkNow
        )
    }

    @Test
    fun teacherUndoRedoSyncsActualAttemptsButNotPrivatePageLevelWork() {
        val actualAttempt = ReaderUiState(
            attemptNo = 1,
            role = ReaderRole.TEACHER_PHONE,
            workflow = ReaderWorkflow.LIVE_MONITOR,
        )
        val pageLevel = actualAttempt.copy(attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO)

        assertTrue(actualAttempt.shouldForceSyncTeacherUndoRedo)
        assertFalse(pageLevel.shouldForceSyncTeacherUndoRedo)
    }

    @Test
    fun remotePageCallbackMustMatchTheLatestBookAndPage() {
        val state = ReaderUiState(bookId = "book-a", pageNumber = 3)

        assertTrue(state.matchesRemotePage("book-a", 3))
        assertFalse(state.matchesRemotePage("book-a", 2))
        assertFalse(state.matchesRemotePage("book-b", 3))
    }

    @Test
    fun liveMonitorFollowsOnlyAnotherPageInTheSameBook() {
        val live = ReaderUiState(
            bookId = "book-a",
            pageNumber = 3,
            role = ReaderRole.TEACHER_PHONE,
            workflow = ReaderWorkflow.LIVE_MONITOR,
            capabilities = ReaderCapabilities.forSession(
                ReaderRole.TEACHER_PHONE,
                ReaderWorkflow.LIVE_MONITOR,
                attemptNo = 1,
            ),
        )

        assertTrue(live.shouldFollowRemoteStudentPage("book-a", 4, 1))
        assertFalse(live.shouldFollowRemoteStudentPage("book-a", 3, 1))
        assertTrue(live.shouldFollowRemoteStudentPage("book-a", 3, 2))
        assertFalse(live.shouldFollowRemoteStudentPage("book-b", 4, 1))
        assertFalse(
            live.copy(
                workflow = ReaderWorkflow.REVIEW,
                capabilities = ReaderCapabilities.forSession(
                    ReaderRole.TEACHER_PHONE,
                    ReaderWorkflow.REVIEW,
                    attemptNo = 1,
                ),
            ).shouldFollowRemoteStudentPage("book-a", 4, 1)
        )
    }

    @Test
    fun liveMonitorChoosesTheNewestEvidenceRegardlessOfMessageOrder() {
        val storedAttempt1 = Attempt(bookId = "book", pageNumber = 0, attemptNo = 1)

        assertEquals(
            2,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = listOf(storedAttempt1),
                observedStudentAttemptNos = setOf(1, 2),
            ),
        )
        assertEquals(
            2,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = listOf(storedAttempt1),
                observedStudentAttemptNos = setOf(1),
                liveStudentAttemptNo = 2,
            ),
        )
        assertEquals(
            2,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = listOf(storedAttempt1),
                observedStudentAttemptNos = setOf(1, 2),
                liveStudentAttemptNo = 1,
            ),
        )
    }

    @Test
    fun liveMonitorIgnoresAnEmptyOpenAttemptNewerThanTheStudentCursor() {
        val submitted = Attempt(
            bookId = "book",
            pageNumber = 0,
            attemptNo = 1,
            locked = true,
            lockedAtEpochMillis = System.currentTimeMillis(),
        )
        val orphan = Attempt(bookId = "book", pageNumber = 0, attemptNo = 2)

        assertEquals(
            1,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = listOf(submitted, orphan),
                observedStudentAttemptNos = setOf(1),
                liveStudentAttemptNo = 1,
            ),
        )
        assertEquals(
            1,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.LIVE_MONITOR,
                selectedAttemptNo = null,
                attempts = listOf(submitted, orphan),
                observedStudentAttemptNos = setOf(1),
                liveStudentAttemptNo = null,
            ),
        )
    }

    @Test
    fun pendingMarkMoveIsBoundToBookPageAndAttemptAndRequiresReadyStorage() {
        val state = ReaderUiState(
            bookId = "book-a",
            pageNumber = 3,
            attemptNo = 2,
            documentReady = true,
            storageAvailable = true,
        )
        val move = PendingMarkMove("mark-1", state.annotationTarget())

        assertTrue(move.canApply(state))
        assertFalse(move.canApply(state.copy(bookId = "book-b")))
        assertFalse(move.canApply(state.copy(pageNumber = 4)))
        assertFalse(move.canApply(state.copy(attemptNo = 1)))
        assertFalse(move.canApply(state.copy(documentReady = false)))
        assertFalse(move.canApply(state.copy(storageAvailable = false)))
    }

    @Test
    fun studentMutationRequiresTheExactOpenAttemptShownOnScreen() {
        val state = ReaderUiState(
            attemptNo = 2,
            role = ReaderRole.STUDENT,
            currentAttemptWritable = true,
        )

        assertTrue(state.canMutateStudentAttempt(2))
        assertFalse(state.canMutateStudentAttempt(1))
        assertFalse(state.canMutateStudentAttempt(null))
        assertFalse(state.copy(currentAttemptWritable = false).canMutateStudentAttempt(2))
        assertTrue(
            state.copy(role = ReaderRole.TEACHER_PHONE).canMutateStudentAttempt(null),
        )
    }

    @Test
    fun reviewCanReturnToPageLevelMarksAfterAStudentSubmissionExists() {
        val submitted = Attempt(
            bookId = "book",
            pageNumber = 0,
            attemptNo = 1,
            locked = true,
            lockedAtEpochMillis = 200,
        )

        assertEquals(
            TEACHER_PAGE_REVIEW_ATTEMPT_NO,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.REVIEW,
                selectedAttemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                attempts = listOf(submitted),
            ),
        )
        assertEquals(
            1,
            resolveReaderAttemptNo(
                workflow = ReaderWorkflow.REVIEW,
                selectedAttemptNo = null,
                attempts = listOf(submitted),
            ),
        )
    }

    @Test
    fun studyKeepsTheSubmittedAttemptOnScreenUntilANewOneIsOpened() {
        val submitted = Attempt(bookId = "book", pageNumber = 0, attemptNo = 1, locked = true)
        val reopened = Attempt(bookId = "book", pageNumber = 0, attemptNo = 2)

        // A page nobody has written on yet still starts at attempt 1.
        assertEquals(
            1,
            resolveReaderAttemptNo(ReaderWorkflow.STUDY, null, emptyList()),
        )
        // Submitting locks attempt 1 without opening attempt 2. Targeting the unwritten 2 here is
        // what made a student's own submitted ink disappear with no way to get back to it.
        assertEquals(
            1,
            resolveReaderAttemptNo(ReaderWorkflow.STUDY, null, listOf(submitted)),
        )
        // Once the next stroke opens attempt 2, that is the one being written and shown.
        assertEquals(
            2,
            resolveReaderAttemptNo(
                ReaderWorkflow.STUDY,
                null,
                listOf(submitted, reopened),
                observedStudentAttemptNos = setOf(1, 2),
            ),
        )
    }

    @Test
    fun emptyOrphanAttemptDoesNotHideTheLastSubmittedWork() {
        val submitted = Attempt(bookId = "book", pageNumber = 0, attemptNo = 1, locked = true)
        val orphan = Attempt(bookId = "book", pageNumber = 0, attemptNo = 2, locked = false)

        assertEquals(
            1,
            resolveReaderAttemptNo(
                ReaderWorkflow.STUDY,
                null,
                listOf(submitted, orphan),
                observedStudentAttemptNos = setOf(1),
            ),
        )
    }

    @Test
    fun submittedInkStaysVisibleToTheStudentThatWroteIt() {
        val submitted = Attempt(bookId = "book", pageNumber = 0, attemptNo = 1, locked = true)
        val ink = strokeAsset(authorId = "student", attemptNo = 1)
        val snapshot = snapshotOf(ink)

        val attemptNo = resolveReaderAttemptNo(ReaderWorkflow.STUDY, null, listOf(submitted))

        assertEquals(listOf(ink), snapshot.visibleStrokes(attemptNo))
    }

    @Test
    fun observedStudentAttemptsIgnoreTeacherInkAndThePageLevelSlot() {
        val snapshot = snapshotOf(
            strokeAsset(authorId = "student", attemptNo = 1),
            strokeAsset(authorId = "student", attemptNo = 3),
            strokeAsset(authorId = "teacher", attemptNo = 2),
            strokeAsset(authorId = "teacher", attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO),
        )

        assertEquals(setOf(1, 3), snapshot.studentAttemptNos())
    }

    @Test
    fun erasedStudentInkStillProvesTheCurrentAttemptExisted() {
        val erased = strokeAsset(authorId = "student", attemptNo = 2)
        val snapshot = AnnotationSnapshot(
            bookId = "book",
            pageNumber = 0,
            revision = 2,
            assets = mapOf(erased.id to erased),
            activeStrokeIds = emptySet(),
        )

        assertEquals(setOf(2), snapshot.studentAttemptNos())
    }

    private fun strokeAsset(authorId: String, attemptNo: Int) = StrokeAsset(
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xFF000000.toInt(),
        width = 3f,
        points = listOf(PagePoint(1f, 1f), PagePoint(2f, 2f)),
        authorId = authorId,
        attemptNo = attemptNo,
    )

    private fun snapshotOf(vararg strokes: StrokeAsset) = AnnotationSnapshot(
        bookId = "book",
        pageNumber = 0,
        revision = strokes.size.toLong(),
        assets = strokes.associateBy { it.id },
        activeStrokeIds = strokes.map { it.id }.toSet(),
    )
}
