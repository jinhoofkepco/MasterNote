package com.studyink.library.data

import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint

data class TeacherGradeDraftCommitInput(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val groupId: String,
    val anchor: PagePoint,
    val color: MarkColor,
    val hidden: Boolean,
    val appendMark: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

internal data class TeacherGradeDraftCommitResult(
    val markGroups: List<MarkGroup>,
    val committedGroup: MarkGroup,
    val changed: Boolean,
)

/** Executes the single durable side effect required by a changed pure merge result. */
internal fun applyTeacherGradeDraftCommit(
    result: TeacherGradeDraftCommitResult,
    install: (List<MarkGroup>) -> Unit,
    rollback: () -> Unit,
    persist: () -> Unit,
    emit: (MarkGroup) -> Unit,
): MarkGroup {
    if (!result.changed) return result.committedGroup
    install(result.markGroups)
    try {
        persist()
    } catch (error: Throwable) {
        rollback()
        throw error
    }
    emit(result.committedGroup)
    return result.committedGroup
}

/** Pure state transition behind [LibraryRepository.commitTeacherGradeDraft]. */
internal fun mergeTeacherGradeDraftCommit(
    markGroups: List<MarkGroup>,
    attempts: List<Attempt>,
    bookId: String,
    pageNumber: Int,
    pageCount: Int,
    attemptNo: Int,
    groupId: String,
    anchor: PagePoint,
    color: MarkColor,
    hidden: Boolean,
    appendMark: Boolean,
    createdAtEpochMillis: Long,
    updatedAtEpochMillis: Long,
    deviceId: String,
): TeacherGradeDraftCommitResult {
    require(bookId.isNotBlank()) { "채점 대상 교재 ID가 비어 있습니다." }
    require(pageNumber in 0 until pageCount) { "채점 대상 페이지가 교재 범위를 벗어납니다." }
    require(groupId.isNotBlank() && groupId.toByteArray(Charsets.UTF_8).size <= MAX_GROUP_ID_BYTES) {
        "채점 표시 ID가 올바르지 않습니다."
    }
    require(deviceId.isNotBlank() && deviceId.length <= MAX_DEVICE_ID_CHARACTERS) {
        "채점 표시 변경 기기가 올바르지 않습니다."
    }
    require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
        "채점 초안 시간이 올바르지 않습니다."
    }
    require(
        anchor.x.isFinite() && anchor.x in 0f..MAX_CANONICAL_PAGE_X &&
            anchor.y.isFinite() && anchor.y in 0f..MAX_CANONICAL_PAGE_Y &&
            anchor.pressure.isFinite() && anchor.pressure in 0f..1f
    ) { "채점 표시 위치가 올바르지 않습니다." }
    require(isValidMarkAttemptTarget(bookId, pageNumber, attemptNo, attempts)) {
        "채점 대상 풀이 회차가 없습니다."
    }

    val matchingGroups = markGroups.filter { it.id == groupId }
    require(matchingGroups.size <= 1) { "같은 ID의 채점 표시가 중복되어 있습니다." }
    val existing = matchingGroups.singleOrNull()
    require(existing == null || existing.bookId == bookId && existing.pageNumber == pageNumber) {
        "같은 ID의 채점 표시가 다른 교재 또는 페이지에 있습니다."
    }
    require(existing == null || isCompatibleMarkGroupTarget(existing, attemptNo)) {
        "페이지 표시와 학생 풀이 채점을 같은 표시 묶음에 섞을 수 없습니다."
    }

    val exactMarker = Mark(
        attemptNo = attemptNo,
        color = color,
        gradedAtEpochMillis = updatedAtEpochMillis,
    )
    if (existing == null) {
        val inserted = MarkGroup(
            id = groupId,
            bookId = bookId,
            pageNumber = pageNumber,
            anchor = anchor,
            marks = listOf(exactMarker),
            createdAtEpochMillis = createdAtEpochMillis,
            hiddenAtEpochMillis = updatedAtEpochMillis.takeIf { hidden },
            syncRevision = 1L,
            lastModifiedByDeviceId = deviceId,
        )
        return TeacherGradeDraftCommitResult(
            markGroups = markGroups + inserted,
            committedGroup = inserted,
            changed = true,
        )
    }

    val desiredMarks = if (appendMark) {
        val alreadyContainsExactMarker = existing.marks.any { mark ->
            mark.attemptNo == attemptNo && mark.color == color &&
                mark.gradedAtEpochMillis == updatedAtEpochMillis
        }
        if (alreadyContainsExactMarker) existing.marks else existing.marks + exactMarker
    } else {
        val latestVisibleIndex = existing.marks.indexOfLast { mark ->
            mark.attemptNo == attemptNo && mark.hiddenAtEpochMillis == null
        }
        when {
            latestVisibleIndex < 0 -> existing.marks + exactMarker
            existing.marks[latestVisibleIndex].color == color -> existing.marks
            else -> existing.marks.mapIndexed { index, mark ->
                if (index == latestVisibleIndex) mark.copy(color = color) else mark
            }
        }
    }
    require(desiredMarks.size <= MAX_MARKS_PER_GROUP) { "채점 표시 이력이 너무 큽니다." }

    val desired = existing.copy(
        anchor = anchor,
        marks = desiredMarks,
        hiddenAtEpochMillis = updatedAtEpochMillis.takeIf { hidden },
    )
    if (desired == existing) {
        return TeacherGradeDraftCommitResult(markGroups, existing, changed = false)
    }

    check(existing.syncRevision < Long.MAX_VALUE) { "채점 표시 변경 번호가 한도를 초과했습니다." }
    val committed = desired.copy(
        syncRevision = existing.syncRevision + 1L,
        lastModifiedByDeviceId = deviceId,
    )
    return TeacherGradeDraftCommitResult(
        markGroups = markGroups.map { group -> if (group.id == groupId) committed else group },
        committedGroup = committed,
        changed = true,
    )
}

private const val MAX_GROUP_ID_BYTES = 256
private const val MAX_DEVICE_ID_CHARACTERS = 256
private const val MAX_CANONICAL_PAGE_X = 1_000f
private const val MAX_CANONICAL_PAGE_Y = 1_000_000f
private const val MAX_MARKS_PER_GROUP = 4_096
