package com.studyink.library.data

import com.studyink.core.model.Attempt
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO

/** The person whose library data is being viewed stays independent from the viewing role. */
enum class LibraryPerspective {
    STUDENT,
    TEACHER,
}

/**
 * Identifies one real student's library and the way it should be presented.
 *
 * Keeping the perspective separate avoids creating duplicate "student-teacher" students or books.
 */
data class LibraryContext(
    val studentId: String,
    val perspective: LibraryPerspective,
)

enum class AttemptProgressStatus {
    IN_PROGRESS,
    SUBMITTED,
    /** Review marks exist, but the current catalog cannot prove that feedback was published. */
    REVIEW_IN_PROGRESS,
}

enum class PageProgressStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUBMITTED,
    /** Review marks exist on the latest attempt; this does not mean feedback was published. */
    REVIEW_IN_PROGRESS,
    /** Teacher marks exist at page level before any student submission exists. */
    TEACHER_MARKED,
}

data class AttemptProgressSummary(
    val attemptNo: Int,
    val status: AttemptProgressStatus,
    val markCount: Int,
    val startedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
    val latestMarkAtEpochMillis: Long?,
)

/** One problem's latest visible grade in one attempt. */
data class AttemptGradeSummary(
    val attemptNo: Int,
    val color: MarkColor,
    val gradedAtEpochMillis: Long,
)

/**
 * Read-only grade history for one mark group (normally one problem on the paper).
 *
 * A group is kept as a stable problem identity while its marks hold the attempt history. The
 * catalog format remains unchanged; this projection only keeps information the library UI needs.
 */
data class ProblemGradeSummary(
    val groupId: String,
    val pageLevel: Boolean,
    val history: List<AttemptGradeSummary>,
)

/** A near-square page bundle can render this without knowing catalog or MarkGroup details. */
data class ProblemGradeCell(
    val groupId: String,
    val color: MarkColor,
    val previousColors: List<MarkColor>,
    val pageLevel: Boolean,
)

data class PageGradeSnapshot(
    val attemptNo: Int,
    val cells: List<ProblemGradeCell>,
) {
    val pageLevel: Boolean get() = cells.isNotEmpty() && cells.all(ProblemGradeCell::pageLevel)
    val pageLevelCount: Int get() = cells.count(ProblemGradeCell::pageLevel)
    val correctCount: Int get() = cells.count { !it.pageLevel && it.color == MarkColor.BLUE }
    val wrongCount: Int get() = cells.count { !it.pageLevel && it.color == MarkColor.RED }
    val unansweredCount: Int get() = cells.count { !it.pageLevel && it.color == MarkColor.GRAY }
}

/**
 * Read-only projection of the catalog's existing attempt and mark data.
 * [pageNumber] uses the reader's existing zero-based page index.
 */
data class PageProgressSummary(
    val pageNumber: Int,
    val status: PageProgressStatus,
    val attempts: List<AttemptProgressSummary>,
    val latestAttemptNo: Int?,
    val attemptCount: Int,
    val submittedAttemptCount: Int,
    val markCount: Int,
    val pageLevelTeacherMarkCount: Int,
    val latestActivityAtEpochMillis: Long?,
    val problemGrades: List<ProblemGradeSummary> = emptyList(),
) {
    val hasReviewActivity: Boolean get() = markCount > 0 || pageLevelTeacherMarkCount > 0

    /** The newest immutable submission stays reviewable even while a newer student draft exists. */
    val latestSubmittedAttemptNo: Int?
        get() = attempts.lastOrNull { it.status != AttemptProgressStatus.IN_PROGRESS }?.attemptNo

    /**
     * Teacher workflow is projected from the newest submitted attempt, not the newest student
     * attempt. Otherwise starting a fresh draft would make an earlier submission disappear from
     * the review queue.
     */
    val teacherStatus: PageProgressStatus
        get() = when (attempts.lastOrNull { it.status != AttemptProgressStatus.IN_PROGRESS }?.status) {
            AttemptProgressStatus.SUBMITTED -> PageProgressStatus.SUBMITTED
            AttemptProgressStatus.REVIEW_IN_PROGRESS -> PageProgressStatus.REVIEW_IN_PROGRESS
            AttemptProgressStatus.IN_PROGRESS -> error("A submitted attempt cannot be in progress")
            null -> when {
                pageLevelTeacherMarkCount > 0 -> PageProgressStatus.TEACHER_MARKED
                attempts.any { it.status == AttemptProgressStatus.IN_PROGRESS } -> PageProgressStatus.IN_PROGRESS
                else -> PageProgressStatus.NOT_STARTED
            }
        }

    fun statusFor(perspective: LibraryPerspective): PageProgressStatus = when (perspective) {
        LibraryPerspective.STUDENT -> status
        LibraryPerspective.TEACHER -> teacherStatus
    }

    /**
     * The newest student attempt is primary for the student. Teachers see the newest immutable
     * submission, or their separate page-level review marks when no submission exists.
     * Missing marks in a newer attempt become neutral cells while older colors stay as hints.
     */
    fun gradeSnapshotFor(perspective: LibraryPerspective): PageGradeSnapshot? {
        val attemptNo = when (perspective) {
            LibraryPerspective.STUDENT -> latestAttemptNo
            LibraryPerspective.TEACHER -> latestSubmittedAttemptNo
                ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO.takeIf {
                    problemGrades.any(ProblemGradeSummary::pageLevel)
                }
        } ?: return null
        val pageLevel = attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
        // Page-level teacher notes are a different review target from a submitted student
        // attempt. Mixing both into one color mosaic would make a page look more right/wrong than
        // the selected submission actually was and would disagree with Reader's isolated layers.
        val relevant = problemGrades.filter { problem -> problem.pageLevel == pageLevel }
        if (relevant.isEmpty()) return null
        return PageGradeSnapshot(
            attemptNo = attemptNo,
            cells = relevant.map { problem ->
                val cellAttemptNo = if (problem.pageLevel) TEACHER_PAGE_REVIEW_ATTEMPT_NO else attemptNo
                val current = problem.history.lastOrNull { it.attemptNo == cellAttemptNo }
                ProblemGradeCell(
                    groupId = problem.groupId,
                    color = current?.color ?: MarkColor.GRAY,
                    previousColors = if (problem.pageLevel) emptyList() else {
                        problem.history.asSequence()
                            .filter { it.attemptNo < attemptNo }
                            .map(AttemptGradeSummary::color)
                            .toList()
                            .takeLast(2)
                    },
                    pageLevel = problem.pageLevel,
                )
            },
        )
    }
}

internal fun projectBookPageProgress(
    pageCount: Int,
    attempts: List<Attempt>,
    markGroups: List<MarkGroup>,
): List<PageProgressSummary> {
    require(pageCount >= 0)
    val attemptsByPage = attempts.groupBy(Attempt::pageNumber)
    val visibleGroupsByPage = markGroups.asSequence()
        .filter { it.hiddenAtEpochMillis == null }
        .sortedWith(compareBy<MarkGroup>({ it.pageNumber }, { it.anchor.y }, { it.anchor.x }, { it.id }))
        .groupBy(MarkGroup::pageNumber)
    val visibleMarksByPageAndAttempt = visibleGroupsByPage.values.asSequence()
        .flatten()
        .flatMap { group ->
            group.marks.asSequence()
                .filter { it.hiddenAtEpochMillis == null }
                .map { mark -> (group.pageNumber to mark.attemptNo) to mark }
        }
        .groupBy({ it.first }, { it.second })

    return List(pageCount) { pageNumber ->
        val pageAttempts = attemptsByPage[pageNumber].orEmpty()
            .filter { it.attemptNo != TEACHER_PAGE_REVIEW_ATTEMPT_NO }
            .sortedBy(Attempt::attemptNo)
        val pageLevelTeacherMarks = visibleMarksByPageAndAttempt[
            pageNumber to TEACHER_PAGE_REVIEW_ATTEMPT_NO
        ].orEmpty()
        val problemGrades = visibleGroupsByPage[pageNumber].orEmpty().mapNotNull { group ->
            val history = group.marks.asSequence()
                .filter { it.hiddenAtEpochMillis == null }
                .groupBy { it.attemptNo }
                .mapNotNull { (attemptNo, marks) ->
                    marks.withIndex().maxWithOrNull(
                        compareBy<IndexedValue<com.studyink.core.model.Mark>>(
                            { it.value.gradedAtEpochMillis },
                            IndexedValue<com.studyink.core.model.Mark>::index,
                        )
                    )?.value?.let { mark ->
                        AttemptGradeSummary(attemptNo, mark.color, mark.gradedAtEpochMillis)
                    }
                }
                .sortedWith(compareBy(AttemptGradeSummary::attemptNo, AttemptGradeSummary::gradedAtEpochMillis))
            history.takeIf(List<*>::isNotEmpty)?.let {
                ProblemGradeSummary(
                    groupId = group.id,
                    pageLevel = history.all { grade ->
                        grade.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
                    },
                    history = history,
                )
            }
        }
        val attemptSummaries = pageAttempts.map { attempt ->
            val marks = visibleMarksByPageAndAttempt[pageNumber to attempt.attemptNo].orEmpty()
            val status = when {
                !attempt.locked -> AttemptProgressStatus.IN_PROGRESS
                marks.isNotEmpty() -> AttemptProgressStatus.REVIEW_IN_PROGRESS
                else -> AttemptProgressStatus.SUBMITTED
            }
            AttemptProgressSummary(
                attemptNo = attempt.attemptNo,
                status = status,
                markCount = marks.size,
                startedAtEpochMillis = attempt.startedAtEpochMillis,
                submittedAtEpochMillis = attempt.lockedAtEpochMillis,
                latestMarkAtEpochMillis = marks.maxOfOrNull { it.gradedAtEpochMillis },
            )
        }
        val latestAttempt = attemptSummaries.lastOrNull()
        val pageMarks = attemptSummaries.sumOf(AttemptProgressSummary::markCount)
        val activityTimes = buildList {
            pageAttempts.forEach { attempt ->
                add(attempt.startedAtEpochMillis)
                attempt.lockedAtEpochMillis?.let(::add)
            }
            attemptSummaries.mapNotNullTo(this) { it.latestMarkAtEpochMillis }
            pageLevelTeacherMarks.maxOfOrNull { it.gradedAtEpochMillis }?.let(::add)
        }
        PageProgressSummary(
            pageNumber = pageNumber,
            status = when (latestAttempt?.status) {
                null -> PageProgressStatus.NOT_STARTED
                AttemptProgressStatus.IN_PROGRESS -> PageProgressStatus.IN_PROGRESS
                AttemptProgressStatus.SUBMITTED -> PageProgressStatus.SUBMITTED
                AttemptProgressStatus.REVIEW_IN_PROGRESS -> PageProgressStatus.REVIEW_IN_PROGRESS
            },
            attempts = attemptSummaries,
            latestAttemptNo = latestAttempt?.attemptNo,
            attemptCount = attemptSummaries.size,
            submittedAttemptCount = pageAttempts.count(Attempt::locked),
            markCount = pageMarks,
            pageLevelTeacherMarkCount = pageLevelTeacherMarks.size,
            latestActivityAtEpochMillis = activityTimes.maxOrNull(),
            problemGrades = problemGrades,
        )
    }
}
