package com.studyink.reader

import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.Attempt
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
}
