package com.studyink.remote.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.studyink.remote.protocol.wire.Ack
import com.studyink.remote.protocol.wire.CheckpointChunk
import com.studyink.remote.protocol.wire.CheckpointRequest
import com.studyink.remote.protocol.wire.DeviceRole
import com.studyink.remote.protocol.wire.DurableOperation
import com.studyink.remote.protocol.wire.DurableOperationBatch
import com.studyink.remote.protocol.wire.Envelope
import com.studyink.remote.protocol.wire.Hello
import com.studyink.remote.protocol.wire.HelloAccepted
import com.studyink.remote.protocol.wire.Lane
import com.studyink.remote.protocol.wire.LiveStrokePreview
import com.studyink.remote.protocol.wire.Nack
import com.studyink.remote.protocol.wire.OperationType
import com.studyink.remote.protocol.wire.PageDigest
import com.studyink.remote.protocol.wire.PageGeometry
import com.studyink.remote.protocol.wire.PageState
import com.studyink.remote.protocol.wire.Ping
import com.studyink.remote.protocol.wire.Pong
import com.studyink.remote.protocol.wire.ProtocolError
import com.studyink.remote.protocol.wire.SessionRejected
import com.studyink.remote.protocol.wire.StrokeAsset
import com.studyink.remote.protocol.wire.StrokePoint
import com.studyink.remote.protocol.wire.ViewportState
import com.studyink.remote.protocol.wire.ResourceOffer
import com.studyink.remote.protocol.wire.ResourceNeed
import com.studyink.remote.protocol.wire.ResourceChunk
import com.studyink.remote.protocol.wire.ResourceReady
import com.studyink.remote.protocol.wire.PresentResource
import com.studyink.remote.protocol.wire.DismissResource

interface RemoteMessageCodec {
    fun encode(envelope: RemoteEnvelope): ByteArray
    fun decode(bytes: ByteArray): RemoteEnvelope
}

class ProtobufRemoteMessageCodec : RemoteMessageCodec {
    override fun encode(envelope: RemoteEnvelope): ByteArray {
        val bytes = envelope.toWire().toByteArray()
        if (bytes.size > MAX_MESSAGE_BYTES) throw RemoteProtocolException("Message exceeds $MAX_MESSAGE_BYTES bytes")
        if (envelope.payload !is RemoteCheckpointChunk && envelope.payload !is RemoteResourceChunk && bytes.size > MAX_GENERAL_MESSAGE_BYTES) {
            throw RemoteProtocolException("General message exceeds $MAX_GENERAL_MESSAGE_BYTES bytes")
        }
        return bytes
    }

    override fun decode(bytes: ByteArray): RemoteEnvelope {
        if (bytes.size > MAX_MESSAGE_BYTES) throw RemoteProtocolException("Message exceeds $MAX_MESSAGE_BYTES bytes")
        val wire = try { Envelope.parseFrom(bytes) } catch (error: InvalidProtocolBufferException) {
            throw RemoteProtocolException("Corrupt remote payload", error)
        }
        if (wire.protocolVersion <= 0) throw RemoteProtocolException("Missing protocol version")
        return wire.toDomain().also {
            if (it.payload !is RemoteCheckpointChunk && it.payload !is RemoteResourceChunk && bytes.size > MAX_GENERAL_MESSAGE_BYTES) {
                throw RemoteProtocolException("General message exceeds $MAX_GENERAL_MESSAGE_BYTES bytes")
            }
        }
    }
}

/** Binary checkpoint body. Generated protobuf types remain private to this module. */
object RemoteStrokeAssetListCodec {
    fun encode(strokes: List<RemoteStrokeAsset>): ByteArray = DurableOperationBatch.newBuilder()
        .addOperations(
            DurableOperation.newBuilder()
                .setOperationId("checkpoint")
                .setType(OperationType.SESSION_CONTROL)
                .addAllAddedStrokes(strokes.map(RemoteStrokeAsset::toWire))
                .build()
        )
        .build()
        .toByteArray()

    fun decode(bytes: ByteArray): List<RemoteStrokeAsset> = try {
        DurableOperationBatch.parseFrom(bytes).operationsList.singleOrNull()
            ?.addedStrokesList?.map(StrokeAsset::toDomain)
            ?: throw RemoteProtocolException("Invalid checkpoint body")
    } catch (error: InvalidProtocolBufferException) {
        throw RemoteProtocolException("Corrupt checkpoint body", error)
    }
}

private fun RemoteEnvelope.toWire(): Envelope {
    val builder = Envelope.newBuilder()
        .setProtocolVersion(protocolVersion)
        .setSessionId(sessionId).setSenderDeviceId(senderDeviceId).setMessageId(messageId)
        .setLane(if (lane == RemoteLane.DURABLE) Lane.DURABLE else Lane.EPHEMERAL)
        .setDurableSequence(durableSequence).setSentElapsedRealtimeMs(sentElapsedRealtimeMs)
    when (val value = payload) {
        is RemoteHello -> builder.hello = value.toWire()
        is RemoteHelloAccepted -> builder.helloAccepted = HelloAccepted.newBuilder()
            .setProtocolVersion(value.protocolVersion).setExpectedSequence(value.expectedSequence).build()
        is RemoteSessionRejected -> builder.sessionRejected = SessionRejected.newBuilder().setReason(value.reason).build()
        is RemoteDurableOperationBatch -> builder.durableOperationBatch = DurableOperationBatch.newBuilder()
            .addAllOperations(value.operations.map(RemoteDurableOperation::toWire)).build()
        is RemoteLiveStrokePreview -> builder.liveStrokePreview = LiveStrokePreview.newBuilder()
            .setPreviewId(value.previewId).setPageId(value.pageId).addAllPoints(value.points.map(RemoteStrokePoint::toWire)).build()
        is RemotePageState -> builder.pageState = PageState.newBuilder().setPageId(value.pageId)
            .setPageOrder(value.pageOrder).setStateRevision(value.stateRevision).build()
        is RemoteViewportState -> builder.viewportState = ViewportState.newBuilder().setPageId(value.pageId)
            .setNormalizedCenterX(value.normalizedCenterX).setNormalizedCenterY(value.normalizedCenterY)
            .setZoomScale(value.zoomScale).setViewportWidthRatio(value.viewportWidthRatio)
            .setViewportHeightRatio(value.viewportHeightRatio).build()
        is RemoteAck -> builder.ack = Ack.newBuilder().setHighestContiguousSequence(value.highestContiguousSequence).build()
        is RemoteNack -> builder.nack = Nack.newBuilder().setExpectedSequence(value.expectedSequence).build()
        is RemotePageDigest -> builder.pageDigest = PageDigest.newBuilder().setPageId(value.pageId)
            .setLayerRevision(value.layerRevision).setActiveStrokeCount(value.activeStrokeCount)
            .setSortedStrokeIdHash(ByteString.copyFrom(value.sortedStrokeIdHash)).build()
        is RemoteCheckpointRequest -> builder.checkpointRequest = CheckpointRequest.newBuilder()
            .setPageId(value.pageId).setExpectedRevision(value.expectedRevision).build()
        is RemoteCheckpointChunk -> builder.checkpointChunk = CheckpointChunk.newBuilder()
            .setSnapshotId(value.snapshotId).setPageId(value.pageId).setLayerRevision(value.layerRevision)
            .setChunkIndex(value.chunkIndex).setChunkCount(value.chunkCount)
            .setStrokeAssets(ByteString.copyFrom(value.strokeAssets)).setPayloadHash(ByteString.copyFrom(value.payloadHash)).build()
        is RemotePing -> builder.ping = Ping.newBuilder().setNonce(value.nonce).build()
        is RemotePong -> builder.pong = Pong.newBuilder().setNonce(value.nonce).build()
        is RemoteProtocolError -> builder.protocolError = ProtocolError.newBuilder().setCode(value.code).setMessage(value.message).build()
        is RemoteResourceOffer -> builder.resourceOffer = ResourceOffer.newBuilder().setAssetHash(value.assetHash).setMimeType(value.mimeType).setTitle(value.title).setByteSize(value.byteSize).build()
        is RemoteResourceNeed -> builder.resourceNeed = ResourceNeed.newBuilder().setAssetHash(value.assetHash).build()
        is RemoteResourceChunk -> builder.resourceChunk = ResourceChunk.newBuilder().setTransferId(value.transferId).setAssetHash(value.assetHash).setChunkIndex(value.chunkIndex).setChunkCount(value.chunkCount).setData(ByteString.copyFrom(value.data)).build()
        is RemoteResourceReady -> builder.resourceReady = ResourceReady.newBuilder().setAssetHash(value.assetHash).build()
        is RemotePresentResource -> builder.presentResource = PresentResource.newBuilder().setAssetHash(value.assetHash).setTitle(value.title).setTextContent(value.textContent).setMimeType(value.mimeType).build()
        is RemoteDismissResource -> builder.dismissResource = DismissResource.newBuilder().setAssetHash(value.assetHash).build()
    }
    return builder.build()
}

private fun RemoteHello.toWire() = Hello.newBuilder()
    .setMinProtocolVersion(minProtocolVersion).setMaxProtocolVersion(maxProtocolVersion)
    .setAppVersion(appVersion).setDeviceRole(if (deviceRole == RemoteDeviceRole.STUDENT) DeviceRole.STUDENT else DeviceRole.TEACHER)
    .setDeviceId(deviceId).setBookRevisionId(bookRevisionId).setDocumentContentHash(documentContentHash)
    .setPageCount(pageCount).setAttemptId(attemptId).setCurrentPageId(currentPageId)
    .setLastAckedDurableSequence(lastAckedDurableSequence)
    .addAllPages(pages.map { PageGeometry.newBuilder().setPageId(it.pageId).setWidth(it.width).setHeight(it.height).build() }).build()

private fun RemoteDurableOperation.toWire() = DurableOperation.newBuilder()
    .setOperationId(operationId).setType(type.toWire()).setPageId(pageId).setPageRevision(pageRevision)
    .addAllRemovedStrokeIds(removedStrokeIds).addAllAddedStrokes(addedStrokes.map(RemoteStrokeAsset::toWire)).build()
private fun RemoteOperationType.toWire() = when (this) {
    RemoteOperationType.ADD_STROKE -> OperationType.ADD_STROKE
    RemoteOperationType.REMOVE_STROKES -> OperationType.REMOVE_STROKES
    RemoteOperationType.REPLACE_STROKES -> OperationType.REPLACE_STROKES
    RemoteOperationType.SUBMISSION_CHANGED -> OperationType.SUBMISSION_CHANGED
    RemoteOperationType.SESSION_CONTROL -> OperationType.SESSION_CONTROL
}
private fun RemoteStrokeAsset.toWire() = StrokeAsset.newBuilder().setStrokeId(strokeId).setPageNumber(pageNumber)
    .setTool(tool).setColorArgb(colorArgb).setWidth(width).addAllPoints(points.map(RemoteStrokePoint::toWire)).build()
private fun RemoteStrokePoint.toWire() = StrokePoint.newBuilder().setX(x).setY(y).setPressure(pressure).setElapsedMs(elapsedMs).build()

private fun Envelope.toDomain() = RemoteEnvelope(
    protocolVersion.toInt(), sessionId, senderDeviceId, messageId,
    when (lane) { Lane.DURABLE -> RemoteLane.DURABLE; Lane.EPHEMERAL -> RemoteLane.EPHEMERAL; else -> throw RemoteProtocolException("Missing lane") },
    durableSequence, sentElapsedRealtimeMs,
    when (payloadCase) {
        Envelope.PayloadCase.HELLO -> hello.toDomain()
        Envelope.PayloadCase.HELLO_ACCEPTED -> RemoteHelloAccepted(helloAccepted.protocolVersion.toInt(), helloAccepted.expectedSequence)
        Envelope.PayloadCase.SESSION_REJECTED -> RemoteSessionRejected(sessionRejected.reason)
        Envelope.PayloadCase.DURABLE_OPERATION_BATCH -> RemoteDurableOperationBatch(durableOperationBatch.operationsList.map(DurableOperation::toDomain))
        Envelope.PayloadCase.LIVE_STROKE_PREVIEW -> RemoteLiveStrokePreview(liveStrokePreview.previewId, liveStrokePreview.pageId, liveStrokePreview.pointsList.map(StrokePoint::toDomain))
        Envelope.PayloadCase.PAGE_STATE -> RemotePageState(pageState.pageId, pageState.pageOrder, pageState.stateRevision)
        Envelope.PayloadCase.VIEWPORT_STATE -> RemoteViewportState(viewportState.pageId, viewportState.normalizedCenterX, viewportState.normalizedCenterY, viewportState.zoomScale, viewportState.viewportWidthRatio, viewportState.viewportHeightRatio)
        Envelope.PayloadCase.ACK -> RemoteAck(ack.highestContiguousSequence)
        Envelope.PayloadCase.NACK -> RemoteNack(nack.expectedSequence)
        Envelope.PayloadCase.PAGE_DIGEST -> RemotePageDigest(pageDigest.pageId, pageDigest.layerRevision, pageDigest.activeStrokeCount, pageDigest.sortedStrokeIdHash.toByteArray())
        Envelope.PayloadCase.CHECKPOINT_REQUEST -> RemoteCheckpointRequest(checkpointRequest.pageId, checkpointRequest.expectedRevision)
        Envelope.PayloadCase.CHECKPOINT_CHUNK -> RemoteCheckpointChunk(checkpointChunk.snapshotId, checkpointChunk.pageId, checkpointChunk.layerRevision, checkpointChunk.chunkIndex, checkpointChunk.chunkCount, checkpointChunk.strokeAssets.toByteArray(), checkpointChunk.payloadHash.toByteArray())
        Envelope.PayloadCase.PING -> RemotePing(ping.nonce)
        Envelope.PayloadCase.PONG -> RemotePong(pong.nonce)
        Envelope.PayloadCase.PROTOCOL_ERROR -> RemoteProtocolError(protocolError.code, protocolError.message)
        Envelope.PayloadCase.RESOURCE_OFFER -> RemoteResourceOffer(resourceOffer.assetHash, resourceOffer.mimeType, resourceOffer.title, resourceOffer.byteSize)
        Envelope.PayloadCase.RESOURCE_NEED -> RemoteResourceNeed(resourceNeed.assetHash)
        Envelope.PayloadCase.RESOURCE_CHUNK -> RemoteResourceChunk(resourceChunk.transferId, resourceChunk.assetHash, resourceChunk.chunkIndex, resourceChunk.chunkCount, resourceChunk.data.toByteArray())
        Envelope.PayloadCase.RESOURCE_READY -> RemoteResourceReady(resourceReady.assetHash)
        Envelope.PayloadCase.PRESENT_RESOURCE -> RemotePresentResource(presentResource.assetHash, presentResource.title, presentResource.textContent, presentResource.mimeType)
        Envelope.PayloadCase.DISMISS_RESOURCE -> RemoteDismissResource(dismissResource.assetHash)
        else -> throw RemoteProtocolException("Missing or unknown payload")
    },
)

private fun Hello.toDomain() = RemoteHello(minProtocolVersion.toInt(), maxProtocolVersion.toInt(), appVersion,
    when (deviceRole) { DeviceRole.STUDENT -> RemoteDeviceRole.STUDENT; DeviceRole.TEACHER -> RemoteDeviceRole.TEACHER; else -> throw RemoteProtocolException("Missing role") },
    deviceId, bookRevisionId, documentContentHash, pageCount, attemptId, currentPageId, lastAckedDurableSequence,
    pagesList.map { RemotePageGeometry(it.pageId, it.width, it.height) })
private fun DurableOperation.toDomain() = RemoteDurableOperation(operationId, when (type) {
    OperationType.ADD_STROKE -> RemoteOperationType.ADD_STROKE
    OperationType.REMOVE_STROKES -> RemoteOperationType.REMOVE_STROKES
    OperationType.REPLACE_STROKES -> RemoteOperationType.REPLACE_STROKES
    OperationType.SUBMISSION_CHANGED -> RemoteOperationType.SUBMISSION_CHANGED
    OperationType.SESSION_CONTROL -> RemoteOperationType.SESSION_CONTROL
    else -> throw RemoteProtocolException("Missing operation type")
}, pageId, pageRevision, removedStrokeIdsList, addedStrokesList.map(StrokeAsset::toDomain))
private fun StrokeAsset.toDomain() = RemoteStrokeAsset(strokeId, pageNumber, tool, colorArgb, width, pointsList.map(StrokePoint::toDomain))
private fun StrokePoint.toDomain() = RemoteStrokePoint(x, y, pressure, elapsedMs)
