package com.studyink.reader

import com.studyink.core.model.PagePoint

/** Installed by the remote-session owner; Reader remains fully functional when this is null. */
object ReaderRemoteBridge {
    @Volatile var sink: ReaderRemoteEventSink? = null
}

interface ReaderRemoteEventSink {
    fun onStrokePreview(previewId: String, pageNumber: Int, points: List<PagePoint>, eventTimeMillis: Long)
    fun onStrokeFinished(previewId: String)
    fun onPageChanged(pageNumber: Int)
    fun onViewportChanged(state: com.studyink.document.pdf.PdfViewportState)
    fun onPageSnapshot(pageId: String, pageNumber: Int, revision: Long, strokes: List<com.studyink.core.model.StrokeAsset>) = Unit
    fun outboxRequest(): com.studyink.annotation.storage.RemoteOutboxRequest? = null
}

class RemoteLiveReaderSink(
    private val publisher: com.studyink.remote.sync.RemoteLivePublisher,
    private val pageId: (Int) -> String,
) : ReaderRemoteEventSink {
    private var pageRevision = 0L

    override fun onStrokePreview(previewId: String, pageNumber: Int, points: List<PagePoint>, eventTimeMillis: Long) {
        publisher.offerStroke(
            previewId, pageId(pageNumber),
            points.map { com.studyink.remote.protocol.RemoteStrokePoint(it.x, it.y, it.pressure, it.elapsedTimeMillis) },
            eventTimeMillis,
        )
    }

    override fun onStrokeFinished(previewId: String) = publisher.finishStroke(previewId)

    override fun onPageChanged(pageNumber: Int) {
        publisher.updatePage(com.studyink.remote.protocol.RemotePageState(pageId(pageNumber), pageNumber, ++pageRevision))
    }

    override fun onViewportChanged(state: com.studyink.document.pdf.PdfViewportState) {
        publisher.updateViewport(com.studyink.remote.protocol.RemoteViewportState(
            pageId(state.pageNumber), state.normalizedCenterX, state.normalizedCenterY,
            state.zoomScale, state.viewportWidthRatio, state.viewportHeightRatio,
        ))
    }
}
