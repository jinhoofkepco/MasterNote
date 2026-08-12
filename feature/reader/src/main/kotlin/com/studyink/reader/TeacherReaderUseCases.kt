package com.studyink.reader

import com.studyink.annotation.storage.LearningRepository
import com.studyink.annotation.storage.TeacherPreparationRepository
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptStatus
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import com.studyink.core.model.TeacherId

class OpenTeacherPreparationUseCase(
    private val repository: TeacherPreparationRepository,
) {
    suspend operator fun invoke(
        teacherId: TeacherId,
        revisionId: BookRevisionId,
        initialPageId: PageId? = null,
    ): ReaderScene {
        val session = repository.getPreparationSession(teacherId, revisionId, initialPageId)
        return ReaderScene.teacherPreparation(
            teacherId = teacherId,
            revisionId = revisionId,
            pageId = session.initialPageId,
        )
    }
}

class OpenAttemptObservationUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(attemptId: AttemptId, initialPageId: PageId? = null): ReaderScene {
        val session = repository.getAttemptSession(attemptId)
        check(session.attempt.status == AttemptStatus.IN_PROGRESS) {
            "Only an in-progress attempt can be observed as a live layer"
        }
        val pageId = initialPageId
            ?.takeIf { candidate -> session.pages.any { it.pageId == candidate } }
            ?: session.initialPageId
        return ReaderScene.attemptObservation(session.attempt.revisionId, attemptId, pageId)
    }
}
