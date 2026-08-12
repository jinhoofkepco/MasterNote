package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface AnnotationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocument(entity: AnnotationDocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPage(entity: AnnotationPageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLayer(entity: AnnotationLayerEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStroke(entity: StrokeAssetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLayerStroke(entity: LayerStrokeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(entity: AnnotationOperationEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM annotation_documents WHERE documentId = :documentId)")
    suspend fun hasDocument(documentId: String): Boolean

    @Query("SELECT * FROM annotation_documents WHERE documentId = :documentId")
    suspend fun document(documentId: String): AnnotationDocumentEntity?

    @Query("SELECT * FROM annotation_pages WHERE documentId = :documentId ORDER BY pageNumber")
    suspend fun pages(documentId: String): List<AnnotationPageEntity>

    @Query("SELECT * FROM annotation_layers WHERE layerId = :layerId")
    suspend fun layer(layerId: String): AnnotationLayerEntity?

    @Query(
        """
        SELECT DISTINCT stroke_assets.* FROM stroke_assets
        INNER JOIN layer_strokes ON layer_strokes.strokeId = stroke_assets.strokeId
        INNER JOIN annotation_layers ON annotation_layers.layerId = layer_strokes.layerId
        INNER JOIN annotation_pages ON annotation_pages.pageId = annotation_layers.pageId
        WHERE annotation_pages.documentId = :documentId
          AND annotation_layers.layerType = 'STUDENT_WORKING'
        ORDER BY layer_strokes.zOrder
        """
    )
    suspend fun studentStrokeAssets(documentId: String): List<StrokeAssetEntity>

    @Query(
        """
        SELECT layer_strokes.* FROM layer_strokes
        INNER JOIN annotation_layers ON annotation_layers.layerId = layer_strokes.layerId
        INNER JOIN annotation_pages ON annotation_pages.pageId = annotation_layers.pageId
        WHERE annotation_pages.documentId = :documentId
          AND annotation_layers.layerType = 'STUDENT_WORKING'
        ORDER BY layer_strokes.zOrder
        """
    )
    suspend fun studentLayerStrokes(documentId: String): List<LayerStrokeEntity>

    @Query(
        """
        SELECT DISTINCT stroke_assets.* FROM stroke_assets
        INNER JOIN layer_strokes ON layer_strokes.strokeId = stroke_assets.strokeId
        INNER JOIN annotation_layers ON annotation_layers.layerId = layer_strokes.layerId
        WHERE annotation_layers.attemptId = :attemptId
        ORDER BY layer_strokes.zOrder
        """
    )
    suspend fun attemptStrokeAssets(attemptId: String): List<StrokeAssetEntity>

    @Query(
        """
        SELECT layer_strokes.* FROM layer_strokes
        INNER JOIN annotation_layers ON annotation_layers.layerId = layer_strokes.layerId
        WHERE annotation_layers.attemptId = :attemptId
        ORDER BY layer_strokes.zOrder
        """
    )
    suspend fun attemptLayerStrokes(attemptId: String): List<LayerStrokeEntity>

    @Query(
        """
        SELECT annotation_pages.pageNumber AS pageNumber,
               annotation_layers.currentRevision AS currentRevision
        FROM annotation_layers
        INNER JOIN annotation_pages ON annotation_pages.pageId = annotation_layers.pageId
        WHERE annotation_layers.attemptId = :attemptId
        """
    )
    suspend fun attemptLayerRevisions(attemptId: String): List<LayerPageRevisionRow>

    @Query("SELECT COALESCE(MAX(zOrder), -1) FROM layer_strokes WHERE layerId = :layerId")
    suspend fun maxZOrder(layerId: String): Long

    @Query(
        """
        UPDATE layer_strokes
        SET active = 0, unlinkedByOperationId = :operationId
        WHERE layerId = :layerId AND strokeId = :strokeId AND active = 1
        """
    )
    suspend fun deactivateStroke(layerId: String, strokeId: String, operationId: String): Int

    @Query(
        """
        UPDATE layer_strokes
        SET active = 1, linkedByOperationId = :operationId, unlinkedByOperationId = NULL
        WHERE layerId = :layerId AND strokeId = :strokeId
        """
    )
    suspend fun reactivateStroke(layerId: String, strokeId: String, operationId: String): Int

    @Query(
        """
        UPDATE annotation_pages SET currentRevision = :resultRevision
        WHERE pageId = :pageId AND currentRevision = :baseRevision
        """
    )
    suspend fun advancePageRevision(pageId: String, baseRevision: Long, resultRevision: Long): Int

    @Query(
        """
        UPDATE annotation_layers SET currentRevision = :resultRevision
        WHERE layerId = :layerId AND currentRevision = :baseRevision
        """
    )
    suspend fun advanceLayerRevision(layerId: String, baseRevision: Long, resultRevision: Long): Int

    @Query(
        """
        UPDATE annotation_documents SET currentRevision = :resultRevision
        WHERE documentId = :documentId AND currentRevision = :baseRevision
        """
    )
    suspend fun advanceDocumentRevision(documentId: String, baseRevision: Long, resultRevision: Long): Int

    @Query("UPDATE annotation_pages SET currentRevision = currentRevision + 1 WHERE pageId = :pageId")
    suspend fun incrementPageRevision(pageId: String): Int

    @Query("UPDATE annotation_documents SET currentRevision = currentRevision + 1 WHERE documentId = :documentId")
    suspend fun incrementDocumentRevision(documentId: String): Int

    @Query("SELECT COUNT(*) FROM annotation_operations WHERE pageId = :pageId")
    suspend fun operationCount(pageId: String): Int

    @Query("SELECT * FROM layer_strokes WHERE layerId = :layerId ORDER BY zOrder")
    suspend fun layerStrokes(layerId: String): List<LayerStrokeEntity>

    @Query("UPDATE stroke_assets SET encodedInput = :payload WHERE strokeId = :strokeId")
    suspend fun replaceEncodedInputForTest(strokeId: String, payload: ByteArray)
}
