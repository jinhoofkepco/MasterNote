package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "annotation_documents", primaryKeys = ["documentId"])
data class AnnotationDocumentEntity(
    val documentId: String,
    val currentRevision: Long,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "annotation_pages",
    primaryKeys = ["pageId"],
    foreignKeys = [
        ForeignKey(
            entity = AnnotationDocumentEntity::class,
            parentColumns = ["documentId"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index(value = ["documentId", "pageNumber"], unique = true)],
)
data class AnnotationPageEntity(
    val pageId: String,
    val documentId: String,
    val pageNumber: Int,
    val currentRevision: Long,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "annotation_layers",
    primaryKeys = ["layerId"],
    foreignKeys = [
        ForeignKey(
            entity = AnnotationPageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId"), Index("attemptId")],
)
data class AnnotationLayerEntity(
    val layerId: String,
    val pageId: String,
    val attemptId: String?,
    val layerType: String,
    val ownerType: String,
    val currentRevision: Long,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "stroke_assets",
    primaryKeys = ["strokeId"],
    foreignKeys = [
        ForeignKey(
            entity = AnnotationPageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId"), Index("parentStrokeId")],
)
data class StrokeAssetEntity(
    val strokeId: String,
    val pageId: String,
    val pageNumber: Int,
    val encodedInput: ByteArray,
    val brushPresetId: String,
    val colorArgb: Int,
    val brushSize: Float,
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsRight: Float,
    val boundsBottom: Float,
    val parentStrokeId: String?,
    val formatVersion: Int,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "layer_strokes",
    primaryKeys = ["layerId", "strokeId"],
    foreignKeys = [
        ForeignKey(
            entity = AnnotationLayerEntity::class,
            parentColumns = ["layerId"],
            childColumns = ["layerId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StrokeAssetEntity::class,
            parentColumns = ["strokeId"],
            childColumns = ["strokeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("strokeId"), Index(value = ["layerId", "active"])],
)
data class LayerStrokeEntity(
    val layerId: String,
    val strokeId: String,
    val zOrder: Long,
    val active: Boolean,
    val linkedByOperationId: String?,
    val unlinkedByOperationId: String?,
)

@Entity(
    tableName = "annotation_operations",
    primaryKeys = ["operationId"],
    foreignKeys = [
        ForeignKey(
            entity = AnnotationPageEntity::class,
            parentColumns = ["pageId"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AnnotationLayerEntity::class,
            parentColumns = ["layerId"],
            childColumns = ["layerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId"), Index("layerId"), Index(value = ["layerId", "resultRevision"], unique = true)],
)
data class AnnotationOperationEntity(
    val operationId: String,
    val pageId: String,
    val layerId: String,
    val operationType: String,
    val baseRevision: Long,
    val resultRevision: Long,
    val payloadJson: String,
    val createdAtEpochMillis: Long,
)

internal data class LayerPageRevisionRow(
    val pageNumber: Int,
    val currentRevision: Long,
)
