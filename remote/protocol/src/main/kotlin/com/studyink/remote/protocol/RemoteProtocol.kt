package com.studyink.remote.protocol

const val CURRENT_PROTOCOL_VERSION = 1
const val MAX_GENERAL_MESSAGE_BYTES = 32 * 1024
const val MAX_MESSAGE_BYTES = 128 * 1024
const val CHECKPOINT_CHUNK_BYTES = 64 * 1024

enum class RemoteLane { DURABLE, EPHEMERAL }
enum class RemoteDeviceRole { STUDENT, TEACHER }
enum class RemoteOperationType { ADD_STROKE, REMOVE_STROKES, REPLACE_STROKES, SUBMISSION_CHANGED, SESSION_CONTROL }

data class RemoteEnvelope(
    val protocolVersion: Int = CURRENT_PROTOCOL_VERSION,
    val sessionId: String,
    val senderDeviceId: String,
    val messageId: String,
    val lane: RemoteLane,
    val durableSequence: Long = 0,
    val sentElapsedRealtimeMs: Long,
    val payload: RemotePayload,
) {
    init {
        require(protocolVersion > 0)
        require(sessionId.isNotBlank() && senderDeviceId.isNotBlank() && messageId.isNotBlank())
        require(lane == RemoteLane.EPHEMERAL || durableSequence > 0)
    }
}

sealed interface RemotePayload

data class RemoteHello(
    val minProtocolVersion: Int,
    val maxProtocolVersion: Int,
    val appVersion: String,
    val deviceRole: RemoteDeviceRole,
    val deviceId: String,
    val bookRevisionId: String,
    val documentContentHash: String,
    val pageCount: Int,
    val attemptId: String,
    val currentPageId: String,
    val lastAckedDurableSequence: Long,
    val pages: List<RemotePageGeometry>,
) : RemotePayload
data class RemotePageGeometry(val pageId: String, val width: Float, val height: Float)
data class RemoteHelloAccepted(val protocolVersion: Int, val expectedSequence: Long) : RemotePayload
data class RemoteSessionRejected(val reason: String) : RemotePayload

data class RemoteDurableOperationBatch(val operations: List<RemoteDurableOperation>) : RemotePayload
data class RemoteDurableOperation(
    val operationId: String,
    val type: RemoteOperationType,
    val pageId: String,
    val pageRevision: Long,
    val removedStrokeIds: List<String> = emptyList(),
    val addedStrokes: List<RemoteStrokeAsset> = emptyList(),
)
data class RemoteStrokeAsset(
    val strokeId: String,
    val pageNumber: Int,
    val tool: Int,
    val colorArgb: Int,
    val width: Float,
    val points: List<RemoteStrokePoint>,
)
data class RemoteStrokePoint(val x: Float, val y: Float, val pressure: Float, val elapsedMs: Long)

data class RemoteLiveStrokePreview(
    val previewId: String,
    val pageId: String,
    val points: List<RemoteStrokePoint>,
) : RemotePayload {
    init { require(points.size <= 24) }
}
data class RemotePageState(val pageId: String, val pageOrder: Int, val stateRevision: Long) : RemotePayload
data class RemoteViewportState(
    val pageId: String,
    val normalizedCenterX: Float,
    val normalizedCenterY: Float,
    val zoomScale: Float,
    val viewportWidthRatio: Float,
    val viewportHeightRatio: Float,
) : RemotePayload
data class RemoteAck(val highestContiguousSequence: Long) : RemotePayload
data class RemoteNack(val expectedSequence: Long) : RemotePayload
data class RemotePageDigest(
    val pageId: String,
    val layerRevision: Long,
    val activeStrokeCount: Int,
    val sortedStrokeIdHash: ByteArray,
) : RemotePayload {
    override fun equals(other: Any?) = other is RemotePageDigest && pageId == other.pageId &&
        layerRevision == other.layerRevision && activeStrokeCount == other.activeStrokeCount &&
        sortedStrokeIdHash.contentEquals(other.sortedStrokeIdHash)
    override fun hashCode() = 31 * pageId.hashCode() + sortedStrokeIdHash.contentHashCode()
}
data class RemoteCheckpointRequest(val pageId: String, val expectedRevision: Long) : RemotePayload
data class RemoteCheckpointChunk(
    val snapshotId: String,
    val pageId: String,
    val layerRevision: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val strokeAssets: ByteArray,
    val payloadHash: ByteArray,
) : RemotePayload {
    init { require(strokeAssets.size <= CHECKPOINT_CHUNK_BYTES) }
    override fun equals(other: Any?) = other is RemoteCheckpointChunk && snapshotId == other.snapshotId &&
        pageId == other.pageId && layerRevision == other.layerRevision && chunkIndex == other.chunkIndex &&
        chunkCount == other.chunkCount && strokeAssets.contentEquals(other.strokeAssets) &&
        payloadHash.contentEquals(other.payloadHash)
    override fun hashCode() = snapshotId.hashCode()
}
data class RemotePing(val nonce: Long) : RemotePayload
data class RemotePong(val nonce: Long) : RemotePayload
data class RemoteProtocolError(val code: String, val message: String) : RemotePayload
data class RemoteResourceOffer(val assetHash: String, val mimeType: String, val title: String, val byteSize: Long) : RemotePayload
data class RemoteResourceNeed(val assetHash: String) : RemotePayload
data class RemoteResourceChunk(val transferId: String, val assetHash: String, val chunkIndex: Int, val chunkCount: Int, val data: ByteArray) : RemotePayload {
    init { require(data.size <= CHECKPOINT_CHUNK_BYTES); require(chunkIndex in 0 until chunkCount) }
    override fun equals(other: Any?) = other is RemoteResourceChunk && transferId == other.transferId && assetHash == other.assetHash && chunkIndex == other.chunkIndex && chunkCount == other.chunkCount && data.contentEquals(other.data)
    override fun hashCode() = 31 * transferId.hashCode() + chunkIndex
}
data class RemoteResourceReady(val assetHash: String) : RemotePayload
data class RemotePresentResource(val assetHash: String, val title: String, val textContent: String, val mimeType: String) : RemotePayload
data class RemoteDismissResource(val assetHash: String) : RemotePayload

class RemoteProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

fun negotiateProtocol(localMin: Int, localMax: Int, remoteMin: Int, remoteMax: Int): Int {
    val selected = minOf(localMax, remoteMax)
    if (selected < maxOf(localMin, remoteMin)) throw RemoteProtocolException("No compatible protocol version")
    return selected
}
