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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReaderPageSnapshot(
    val pageId: String,
    val pageNumber: Int,
    val revision: Long,
    val strokes: List<com.studyink.remote.protocol.RemoteStrokeAsset>,
)

internal class ServiceReaderSink(
    private val sessionId: String,
    private val deviceId: String,
    val publisher: RemoteLivePublisher,
) : ReaderRemoteEventSink {
    private var pageRevision = 0L
    private val pageIds = mutableMapOf<Int, String>()
    private val _pageSnapshot = MutableStateFlow<ReaderPageSnapshot?>(null)
    val pageSnapshot = _pageSnapshot.asStateFlow()

    private fun pageId(pageNumber: Int) = pageIds[pageNumber] ?: "page-$pageNumber"
    override fun onStrokePreview(previewId: String, pageNumber: Int, points: List<PagePoint>, eventTimeMillis: Long) {
        publisher.offerStroke(previewId, pageId(pageNumber), points.map {
            RemoteStrokePoint(it.x, it.y, it.pressure, it.elapsedTimeMillis)
        }, eventTimeMillis)
    }
    override fun onStrokeFinished(previewId: String) = publisher.finishStroke(previewId)
    override fun onPageChanged(pageNumber: Int) = publisher.updatePage(
        RemotePageState(pageId(pageNumber), pageNumber, ++pageRevision)
    )
    override fun onViewportChanged(state: PdfViewportState) = publisher.updateViewport(
        RemoteViewportState(pageId(state.pageNumber), state.normalizedCenterX, state.normalizedCenterY,
            state.zoomScale, state.viewportWidthRatio, state.viewportHeightRatio)
    )
    override fun onPageSnapshot(
        pageId: String,
        pageNumber: Int,
        revision: Long,
        strokes: List<com.studyink.core.model.StrokeAsset>,
    ) {
        pageIds[pageNumber] = pageId
        _pageSnapshot.value = ReaderPageSnapshot(pageId, pageNumber, revision, strokes.map { asset ->
            com.studyink.remote.protocol.RemoteStrokeAsset(
                asset.id.value, asset.pageNumber, asset.tool.ordinal, asset.colorArgb, asset.width,
                asset.points.map { com.studyink.remote.protocol.RemoteStrokePoint(it.x, it.y, it.pressure, it.elapsedTimeMillis) },
            )
        })
    }
    override fun outboxRequest() = RemoteOutboxRequest(
        sessionId, deviceId, UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), System.currentTimeMillis(),
    )
}
