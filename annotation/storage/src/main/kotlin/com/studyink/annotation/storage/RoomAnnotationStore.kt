package com.studyink.annotation.storage

import android.content.Context
import androidx.room.withTransaction
import com.studyink.core.model.AnnotationLayerType
import com.studyink.core.model.AnnotationMutation
import com.studyink.core.model.AnnotationOwnerType
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PageBounds
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

fun interface AnnotationTransactionFaultInjector {
    fun afterLayerLinksChanged(mutation: AnnotationMutation)

    companion object {
        val NONE = AnnotationTransactionFaultInjector {}
    }
}

class RoomAnnotationStore internal constructor(
    private val database: AnnotationDatabase,
    private val legacyStore: AtomicAnnotationStore? = null,
    private val faultInjector: AnnotationTransactionFaultInjector = AnnotationTransactionFaultInjector.NONE,
) {
    private val dao = database.annotationDao()
    private val learningDao = database.learningDao()

    suspend fun load(documentId: String, attemptId: String? = null): AnnotationSnapshot = withContext(Dispatchers.IO) {
        if (!dao.hasDocument(documentId) && legacyStore?.exists(documentId) == true) {
            importLegacySnapshot(legacyStore.load(documentId))
        }
        database.withTransaction {
            if (attemptId == null) loadRoomSnapshot(documentId) else loadAttemptSnapshot(documentId, attemptId)
        }
    }

    suspend fun applyMutation(mutation: AnnotationMutation, attemptId: String? = null) =
        applyMutationInternal(mutation, attemptId, null)

    suspend fun applyMutationToLayer(mutation: AnnotationMutation, layerId: String) =
        applyMutationInternal(mutation, null, layerId)

    private suspend fun applyMutationInternal(
        mutation: AnnotationMutation,
        attemptId: String?,
        explicitLayerId: String?,
    ) = withContext(Dispatchers.IO) {
        val snapshot = mutation.snapshot
        val operation = mutation.operation
        val documentId = snapshot.documentId
        val pageId = pageId(documentId, operation.pageNumber)
        val layerId = explicitLayerId ?: attemptId?.let { AnnotationIds.attemptLayerId(pageId, it) } ?: layerId(pageId)
        val now = operation.createdAtEpochMillis

        database.withTransaction {
            if (attemptId != null) {
                check(learningDao.attempt(attemptId)?.status == com.studyink.core.model.AttemptStatus.IN_PROGRESS.name) {
                    "Submitted or abandoned attempts are immutable"
                }
            }
            if (explicitLayerId != null) {
                val layer = requireNotNull(dao.layer(explicitLayerId))
                check(!layer.locked) { "Published layers are immutable" }
                check(layer.pageId == pageId) { "Editable layer does not belong to the stroke page" }
            }
            dao.insertDocument(
                AnnotationDocumentEntity(
                    documentId = documentId,
                    currentRevision = snapshot.revision - 1L,
                    createdAtEpochMillis = now,
                )
            )
            dao.insertPage(
                AnnotationPageEntity(
                    pageId = pageId,
                    documentId = documentId,
                    pageNumber = operation.pageNumber,
                    currentRevision = operation.baseRevision,
                    createdAtEpochMillis = now,
                )
            )
            if (attemptId == null && explicitLayerId == null) {
                dao.insertLayer(
                    AnnotationLayerEntity(
                        layerId = layerId,
                        pageId = pageId,
                        attemptId = null,
                        layerType = AnnotationLayerType.STUDENT_WORKING.name,
                        ownerType = AnnotationOwnerType.STUDENT.name,
                        currentRevision = operation.baseRevision,
                        createdAtEpochMillis = now,
                    )
                )
            } else if (attemptId != null) {
                check(dao.layer(layerId)?.attemptId == attemptId) {
                    "Attempt page must be opened before writing annotations"
                }
            }

            mutation.addedAssets.forEach { asset -> dao.insertStroke(asset.toEntity(documentId)) }
            operation.removedStrokeIds.forEach { strokeId ->
                dao.deactivateStroke(layerId, strokeId.value, operation.id.value)
            }

            var nextZOrder = dao.maxZOrder(layerId) + 1L
            operation.addedStrokeIds.forEach { strokeId ->
                if (dao.reactivateStroke(layerId, strokeId.value, operation.id.value) == 0) {
                    dao.insertLayerStroke(
                        LayerStrokeEntity(
                            layerId = layerId,
                            strokeId = strokeId.value,
                            zOrder = nextZOrder++,
                            active = true,
                            linkedByOperationId = operation.id.value,
                            unlinkedByOperationId = null,
                        )
                    )
                }
            }

            faultInjector.afterLayerLinksChanged(mutation)

            dao.insertOperation(
                AnnotationOperationEntity(
                    operationId = operation.id.value,
                    pageId = pageId,
                    layerId = layerId,
                    operationType = operation.operationType.name,
                    baseRevision = operation.baseRevision,
                    resultRevision = operation.resultRevision,
                    payloadJson = operationPayload(
                        operation.removedStrokeIds.map(StrokeId::value),
                        operation.addedStrokeIds.map(StrokeId::value),
                    ),
                    createdAtEpochMillis = operation.createdAtEpochMillis,
                )
            )

            check(dao.advanceLayerRevision(layerId, operation.baseRevision, operation.resultRevision) == 1) {
                "Layer revision conflict for $layerId"
            }
            if (attemptId == null && explicitLayerId == null) {
                check(dao.advancePageRevision(pageId, operation.baseRevision, operation.resultRevision) == 1) {
                    "Page revision conflict for $pageId"
                }
                check(
                    dao.advanceDocumentRevision(
                        documentId,
                        snapshot.revision - 1L,
                        snapshot.revision,
                    ) == 1
                ) { "Document revision conflict for $documentId" }
            } else {
                check(dao.incrementPageRevision(pageId) == 1)
                check(dao.incrementDocumentRevision(documentId) == 1)
            }
        }
    }

    suspend fun loadLayers(documentId: String, layerIds: List<String>): AnnotationSnapshot = withContext(Dispatchers.IO) {
        if (layerIds.isEmpty()) return@withContext AnnotationSnapshot.empty(documentId)
        database.withTransaction {
            val assets = linkedMapOf<StrokeId, StrokeAsset>()
            dao.strokeAssetsForLayers(layerIds).forEach { entity ->
                runCatching { entity.toDomain() }.onSuccess { assets[it.id] = it }
            }
            val activeIds = dao.strokesForLayers(layerIds).asSequence()
                .filter(LayerStrokeEntity::active)
                .map { StrokeId(it.strokeId) }
                .filter(assets::containsKey)
                .toSet()
            val revisions = dao.revisionsForLayers(layerIds).groupBy(LayerPageRevisionRow::pageNumber)
                .mapValues { (_, rows) -> rows.sumOf(LayerPageRevisionRow::currentRevision) }
            AnnotationSnapshot(documentId, revisions.values.sum(), revisions, assets, activeIds)
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.query("SELECT 1").use { cursor -> cursor.moveToFirst() }
    }

    suspend fun loadSubmission(documentId: String, submissionId: String): AnnotationSnapshot =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val assets = linkedMapOf<StrokeId, StrokeAsset>()
                dao.submissionStrokeAssets(submissionId).forEach { entity ->
                    runCatching { entity.toDomain() }.onSuccess { assets[it.id] = it }
                }
                val submission = requireNotNull(learningDao.submission(submissionId))
                AnnotationSnapshot(
                    documentId = documentId,
                    revision = submission.annotationRevision,
                    assets = assets,
                    activeStrokeIds = assets.keys,
                )
            }
        }

    fun close() = database.close()

    private suspend fun loadRoomSnapshot(documentId: String): AnnotationSnapshot {
        val document = dao.document(documentId) ?: return AnnotationSnapshot.empty(documentId)
        val decodedAssets = linkedMapOf<StrokeId, StrokeAsset>()
        dao.studentStrokeAssets(documentId).forEach { entity ->
            runCatching { entity.toDomain() }
                .onSuccess { decodedAssets[it.id] = it }
        }
        val activeIds = dao.studentLayerStrokes(documentId)
            .asSequence()
            .filter(LayerStrokeEntity::active)
            .map { StrokeId(it.strokeId) }
            .filter(decodedAssets::containsKey)
            .toSet()
        return AnnotationSnapshot(
            documentId = documentId,
            revision = document.currentRevision,
            pageRevisions = dao.pages(documentId).associate { it.pageNumber to it.currentRevision },
            assets = decodedAssets,
            activeStrokeIds = activeIds,
        )
    }

    private suspend fun loadAttemptSnapshot(documentId: String, attemptId: String): AnnotationSnapshot {
        if (!dao.hasDocument(documentId)) return AnnotationSnapshot.empty(documentId)
        val decodedAssets = linkedMapOf<StrokeId, StrokeAsset>()
        dao.attemptStrokeAssets(attemptId).forEach { entity ->
            runCatching { entity.toDomain() }.onSuccess { decodedAssets[it.id] = it }
        }
        val activeIds = dao.attemptLayerStrokes(attemptId)
            .asSequence()
            .filter(LayerStrokeEntity::active)
            .map { StrokeId(it.strokeId) }
            .filter(decodedAssets::containsKey)
            .toSet()
        val pageRevisions = dao.attemptLayerRevisions(attemptId)
            .associate { it.pageNumber to it.currentRevision }
        return AnnotationSnapshot(
            documentId = documentId,
            revision = pageRevisions.values.sum(),
            pageRevisions = pageRevisions,
            assets = decodedAssets,
            activeStrokeIds = activeIds,
        )
    }

    private suspend fun importLegacySnapshot(snapshot: AnnotationSnapshot) {
        if (snapshot.assets.isEmpty() && snapshot.revision == 0L) return
        database.withTransaction {
            val createdAt = snapshot.assets.values.minOfOrNull(StrokeAsset::createdAtEpochMillis)
                ?: System.currentTimeMillis()
            dao.insertDocument(AnnotationDocumentEntity(snapshot.documentId, snapshot.revision, createdAt))
            snapshot.assets.values.groupBy(StrokeAsset::pageNumber).forEach { (pageNumber, assets) ->
                val pageId = pageId(snapshot.documentId, pageNumber)
                val layerId = layerId(pageId)
                val pageRevision = snapshot.pageRevisions[pageNumber] ?: 0L
                dao.insertPage(AnnotationPageEntity(pageId, snapshot.documentId, pageNumber, pageRevision, createdAt))
                dao.insertLayer(
                    AnnotationLayerEntity(
                        layerId,
                        pageId,
                        null,
                        AnnotationLayerType.STUDENT_WORKING.name,
                        AnnotationOwnerType.STUDENT.name,
                        pageRevision,
                        createdAt,
                    )
                )
                assets.sortedBy(StrokeAsset::createdAtEpochMillis).forEachIndexed { index, asset ->
                    dao.insertStroke(asset.toEntity(snapshot.documentId))
                    dao.insertLayerStroke(
                        LayerStrokeEntity(
                            layerId = layerId,
                            strokeId = asset.id.value,
                            zOrder = index.toLong(),
                            active = asset.id in snapshot.activeStrokeIds,
                            linkedByOperationId = null,
                            unlinkedByOperationId = null,
                        )
                    )
                }
            }
        }
    }

    private fun StrokeAsset.toEntity(documentId: String): StrokeAssetEntity = StrokeAssetEntity(
        strokeId = id.value,
        pageId = pageId(documentId, pageNumber),
        pageNumber = pageNumber,
        encodedInput = InkStrokeCodec.encode(points),
        brushPresetId = tool.name,
        colorArgb = colorArgb,
        brushSize = width,
        boundsLeft = bounds.left,
        boundsTop = bounds.top,
        boundsRight = bounds.right,
        boundsBottom = bounds.bottom,
        parentStrokeId = parentStrokeId?.value,
        formatVersion = formatVersion,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun StrokeAssetEntity.toDomain(): StrokeAsset = StrokeAsset(
        id = StrokeId(strokeId),
        pageNumber = pageNumber,
        tool = StrokeTool.valueOf(brushPresetId),
        colorArgb = colorArgb,
        width = brushSize,
        points = InkStrokeCodec.decode(encodedInput),
        bounds = PageBounds(boundsLeft, boundsTop, boundsRight, boundsBottom),
        createdAtEpochMillis = createdAtEpochMillis,
        parentStrokeId = parentStrokeId?.let(::StrokeId),
        formatVersion = formatVersion,
    )

    companion object {
        suspend fun open(context: Context): RoomAnnotationStore = withContext(Dispatchers.IO) {
            RoomAnnotationStore(
                database = AnnotationDatabase.open(context),
                legacyStore = AtomicAnnotationStore(context.applicationContext),
            )
        }

        internal fun pageId(documentId: String, pageNumber: Int): String = "$documentId:page:$pageNumber"
        internal fun layerId(pageId: String): String = "$pageId:student-working"

        private fun operationPayload(removed: List<String>, added: List<String>): String =
            JSONObject()
                .put("removed", JSONArray(removed))
                .put("added", JSONArray(added))
                .toString()
    }
}
