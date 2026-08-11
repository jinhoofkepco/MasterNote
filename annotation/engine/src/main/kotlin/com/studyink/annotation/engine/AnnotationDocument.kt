package com.studyink.annotation.engine

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AnnotationMutation
import com.studyink.core.model.AnnotationOperationType
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import java.util.UUID

class AnnotationDocument(initial: AnnotationSnapshot) {
    private var documentId = initial.documentId
    private var revision = initial.revision
    private val pageRevisions = initial.pageRevisions.toMutableMap()
    private val assets = initial.assets.toMutableMap()
    private val active = initial.activeStrokeIds.toMutableSet()
    private val undo = initial.undoStack.toMutableList()
    private val redo = initial.redoStack.toMutableList()

    @Synchronized
    fun snapshot(): AnnotationSnapshot = AnnotationSnapshot(
        documentId = documentId,
        revision = revision,
        pageRevisions = pageRevisions.toMap(),
        assets = assets.toMap(),
        activeStrokeIds = active.toSet(),
        undoStack = undo.toList(),
        redoStack = redo.toList(),
    )

    @Synchronized
    fun addStroke(stroke: StrokeAsset): AnnotationMutation {
        assets[stroke.id] = stroke
        val operation = newOperation(
            pageNumber = stroke.pageNumber,
            removedStrokeIds = emptySet(),
            addedStrokeIds = setOf(stroke.id),
        )
        applyNewOperation(operation)
        return AnnotationMutation(snapshot(), operation, listOf(stroke))
    }

    @Synchronized
    fun erase(page: Int, path: List<PagePoint>, radius: Float, wholeStroke: Boolean): AnnotationMutation? {
        val current = active.mapNotNull(assets::get)
        val result = if (wholeStroke) {
            EraseEngine.wholeStrokeErase(current, page, path, radius)
        } else {
            EraseEngine.partialErase(current, page, path, radius)
        }
        if (result.removedStrokeIds.isEmpty()) return null
        result.fragments.forEach { assets[it.id] = it }
        val operation = newOperation(
            pageNumber = page,
            removedStrokeIds = result.removedStrokeIds,
            addedStrokeIds = result.fragments.mapTo(mutableSetOf()) { it.id },
        )
        applyNewOperation(operation)
        return AnnotationMutation(snapshot(), operation, result.fragments)
    }

    @Synchronized
    fun undo(): AnnotationMutation? {
        val operation = undo.removeLastOrNull() ?: return null
        active.removeAll(operation.addedStrokeIds)
        active.addAll(operation.removedStrokeIds)
        redo += operation
        val inverse = newOperation(
            pageNumber = operation.pageNumber,
            removedStrokeIds = operation.addedStrokeIds,
            addedStrokeIds = operation.removedStrokeIds,
        )
        advanceRevision(inverse)
        return AnnotationMutation(snapshot(), inverse, emptyList())
    }

    @Synchronized
    fun redo(): AnnotationMutation? {
        val operation = redo.removeLastOrNull() ?: return null
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        undo += operation
        val replay = newOperation(
            pageNumber = operation.pageNumber,
            removedStrokeIds = operation.removedStrokeIds,
            addedStrokeIds = operation.addedStrokeIds,
        )
        advanceRevision(replay)
        return AnnotationMutation(snapshot(), replay, emptyList())
    }

    private fun applyNewOperation(operation: AssetOperation) {
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        undo += operation
        redo.clear()
        advanceRevision(operation)
    }

    private fun advanceRevision(operation: AssetOperation) {
        revision++
        pageRevisions[operation.pageNumber] = operation.resultRevision
    }

    private fun newOperation(
        pageNumber: Int,
        removedStrokeIds: Set<StrokeId>,
        addedStrokeIds: Set<StrokeId>,
    ): AssetOperation {
        val baseRevision = pageRevisions[pageNumber] ?: 0L
        return AssetOperation(
            id = OperationId(UUID.randomUUID().toString()),
            pageNumber = pageNumber,
            operationType = operationType(removedStrokeIds, addedStrokeIds),
            baseRevision = baseRevision,
            removedStrokeIds = removedStrokeIds,
            addedStrokeIds = addedStrokeIds,
        )
    }

    private fun operationType(
        removedStrokeIds: Set<StrokeId>,
        addedStrokeIds: Set<StrokeId>,
    ): AnnotationOperationType = when {
        removedStrokeIds.isEmpty() -> AnnotationOperationType.ADD_STROKE
        addedStrokeIds.isEmpty() -> AnnotationOperationType.REMOVE_STROKES
        else -> AnnotationOperationType.REPLACE_STROKES
    }
}
