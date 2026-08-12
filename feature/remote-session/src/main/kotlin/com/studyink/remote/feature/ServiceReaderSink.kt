package com.studyink.remote.feature

import android.os.SystemClock
import com.studyink.annotation.storage.RemoteOutboxRequest
import com.studyink.core.model.PagePoint
import com.studyink.document.pdf.PdfViewportState
import com.studyink.reader.ReaderRemoteEventSink
import com.studyink.remote.protocol.RemotePageState
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.protocol.RemoteViewportState
import com.studyink.remote.sync.RemoteLivePublisher
import java.util.UUID

internal class ServiceReaderSink(
    private val sessionId: String,
    private val deviceId: String,
    val publisher: RemoteLivePublisher,
) : ReaderRemoteEventSink {
    private var pageRevision = 0L
    override fun onStrokePreview(previewId: String, pageNumber: Int, points: List<PagePoint>, eventTimeMillis: Long) {
        publisher.offerStroke(previewId, "page-$pageNumber", points.map {
            RemoteStrokePoint(it.x, it.y, it.pressure, it.elapsedTimeMillis)
        }, eventTimeMillis)
    }
    override fun onStrokeFinished(previewId: String) = publisher.finishStroke(previewId)
    override fun onPageChanged(pageNumber: Int) = publisher.updatePage(
        RemotePageState("page-$pageNumber", pageNumber, ++pageRevision)
    )
    override fun onViewportChanged(state: PdfViewportState) = publisher.updateViewport(
        RemoteViewportState("page-${state.pageNumber}", state.normalizedCenterX, state.normalizedCenterY,
            state.zoomScale, state.viewportWidthRatio, state.viewportHeightRatio)
    )
    override fun outboxRequest() = RemoteOutboxRequest(
        sessionId, deviceId, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
    )
}
