package com.studyink.annotation.engine

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import java.util.UUID

data class AnnotationChange(
    val snapshot: AnnotationSnapshot,
    val operation: AssetOperation,
    val addedAssets: List<StrokeAsset>,
)

/** A single-page editor. Its undo/redo history never crosses the process boundary. */
class AnnotationDocument(initial: AnnotationSnapshot) {
    private val bookId = initial.bookId
    private val pageNumber = initial.pageNumber
    private var revision = initial.revision
    private var logicalClock = initial.activeStrokes.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L
    private val assets = initial.assets.toMutableMap()
    private val active = initial.activeStrokeIds.toMutableSet()
    private val appliedOperationIds = initial.appliedOperationIds.toMutableSet()
    private val undo = mutableListOf<AssetOperation>()
    private val redo = mutableListOf<AssetOperation>()

    val canUndo: Boolean @Synchronized get() = undo.isNotEmpty()
    val canRedo: Boolean @Synchronized get() = redo.isNotEmpty()

    @Synchronized
    fun snapshot(): AnnotationSnapshot = AnnotationSnapshot(
        bookId = bookId,
        pageNumber = pageNumber,
        revision = revision,
        assets = assets.toMap(),
        activeStrokeIds = active.toSet(),
        appliedOperationIds = appliedOperationIds.toSet(),
    )

    @Synchronized
    fun addStroke(stroke: StrokeAsset): AnnotationChange {
        require(stroke.pageNumber == pageNumber) { "Stroke belongs to another page" }
        val persisted = stroke.copy(logicalClock = nextClock(stroke.logicalClock))
        assets[persisted.id] = persisted
        return applyLocal(
            operation = AssetOperation(
                addedStrokeIds = setOf(persisted.id),
                removedStrokeIds = emptySet(),
                logicalClock = persisted.logicalClock,
                deviceId = persisted.deviceId,
            ),
            addedAssets = listOf(persisted),
        )
    }

    @Synchronized
    fun erase(
        page: Int,
        path: List<PagePoint>,
        radius: Float,
        wholeStroke: Boolean,
        authorId: String,
        attemptNo: Int,
        deviceId: String,
    ): AnnotationChange? {
        val editable = active.asSequence().mapNotNull(assets::get)
            .filter { it.authorId == authorId && it.attemptNo == attemptNo }
            .toList()
        val result = if (wholeStroke) {
            EraseEngine.wholeStrokeErase(editable, page, path, radius)
        } else {
            EraseEngine.partialErase(editable, page, path, radius)
        }
        if (result.removedStrokeIds.isEmpty()) return null
        val clock = nextClock(0L)
        val fragments = result.fragments.map { fragment ->
            // EraseEngine.copy already inherits author/attempt/item ownership. Only event metadata changes.
            fragment.copy(logicalClock = clock, deviceId = deviceId)
        }
        fragments.forEach { assets[it.id] = it }
        return applyLocal(
            operation = AssetOperation(
                id = OperationId(UUID.randomUUID().toString()),
                removedStrokeIds = result.removedStrokeIds,
                addedStrokeIds = fragments.mapTo(mutableSetOf()) { it.id },
                logicalClock = clock,
                deviceId = deviceId,
            ),
            addedAssets = fragments,
        )
    }

    /** Remote and published teacher operations are deliberately excluded from local undo. */
    @Synchronized
    fun applyRemote(operation: AssetOperation, addedAssets: List<StrokeAsset>): AnnotationSnapshot {
        if (!appliedOperationIds.add(operation.id)) return snapshot()
        logicalClock = maxOf(logicalClock, operation.logicalClock)
        addedAssets.forEach { assets[it.id] = it }
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        revision++
        return snapshot()
    }

    @Synchronized
    fun publishTeacherDrafts(attemptNo: Int, deviceId: String): AnnotationChange? {
        val drafts = active.asSequence().mapNotNull(assets::get)
            .filter { it.authorId == "teacher" && it.attemptNo == attemptNo && it.publishedAtEpochMillis == null }
            .toList()
        if (drafts.isEmpty()) return null
        val clock = nextClock(0L)
        val publishedAt = System.currentTimeMillis()
        val replacements = drafts.map { draft ->
            draft.copy(
                id = StrokeId(UUID.randomUUID().toString()),
                parentStrokeId = draft.id,
                logicalClock = clock,
                deviceId = deviceId,
                publishedAtEpochMillis = publishedAt,
            )
        }
        replacements.forEach { assets[it.id] = it }
        val operation = AssetOperation(
            removedStrokeIds = drafts.mapTo(mutableSetOf(), StrokeAsset::id),
            addedStrokeIds = replacements.mapTo(mutableSetOf(), StrokeAsset::id),
            logicalClock = clock,
            deviceId = deviceId,
        )
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        appliedOperationIds += operation.id
        revision++
        // Publishing is a boundary, not a student/editor undoable gesture.
        undo.clear()
        redo.clear()
        return AnnotationChange(snapshot(), operation, replacements)
    }

    @Synchronized
    fun undo(deviceId: String): AnnotationChange? {
        val original = undo.removeLastOrNull() ?: return null
        val inverse = AssetOperation(
            removedStrokeIds = original.addedStrokeIds,
            addedStrokeIds = original.removedStrokeIds,
            logicalClock = nextClock(0L),
            deviceId = deviceId,
        )
        active.removeAll(inverse.removedStrokeIds)
        active.addAll(inverse.addedStrokeIds)
        appliedOperationIds += inverse.id
        redo += original
        revision++
        return AnnotationChange(snapshot(), inverse, emptyList())
    }

    @Synchronized
    fun redo(deviceId: String): AnnotationChange? {
        val original = redo.removeLastOrNull() ?: return null
        val replay = original.copy(
            id = OperationId(UUID.randomUUID().toString()),
            logicalClock = nextClock(0L),
            deviceId = deviceId,
        )
        active.removeAll(replay.removedStrokeIds)
        active.addAll(replay.addedStrokeIds)
        appliedOperationIds += replay.id
        undo += original
        revision++
        return AnnotationChange(snapshot(), replay, emptyList())
    }

    private fun applyLocal(operation: AssetOperation, addedAssets: List<StrokeAsset>): AnnotationChange {
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        appliedOperationIds += operation.id
        undo += operation
        redo.clear()
        revision++
        return AnnotationChange(snapshot(), operation, addedAssets)
    }

    private fun nextClock(received: Long): Long {
        logicalClock = maxOf(logicalClock, received) + 1L
        return logicalClock
    }
}
