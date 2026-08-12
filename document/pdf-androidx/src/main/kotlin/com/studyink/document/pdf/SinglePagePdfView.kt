/*
 * The low-resolution background plus visible high-resolution tile strategy is adapted from
 * AndroidX PDF's BitmapFetcher and TileBoard implementation.
 * Copyright 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0.
 */
package com.studyink.document.pdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class PdfPageSize(val width: Int, val height: Int)

@OptIn(ExperimentalPdfApi::class)
class SinglePagePdfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var onViewportChanged: (() -> Unit)? = null
    var onRenderError: ((Throwable) -> Unit)? = null

    var activePage: Int = 0
        private set

    val displayScale: Float
        get() = pageSize()?.let(::fitScaleFor)?.times(zoomFactor) ?: 1f

    val pageBounds: RectF?
        get() = pageSize()?.let(::calculatePageBounds)?.let(::RectF)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val backgroundPaint = Paint().apply { color = Color.rgb(225, 226, 231) }
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var document: PdfDocument? = null
    private var pageSizes: Map<Int, PdfPageSize> = emptyMap()
    private var bitmapSource: PdfDocument.BitmapSource? = null
    private var backgroundBitmap: Bitmap? = null
    private var backgroundSize: Size? = null
    private var tileBoard: TileBoard? = null
    private var backgroundJob: Job? = null
    private var tileJob: Job? = null
    private var pageGeneration = 0L
    private var zoomFactor = MIN_ZOOM
    private var translationX = 0f
    private var translationY = 0f
    private val tileRefresh = Runnable(::refreshTiles)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (pageSize() == null) return false
                zoomAround(detector.focusX, detector.focusY, zoomFactor * detector.scaleFactor)
                scheduleTileRefresh(TILE_REFRESH_DELAY_MS)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scheduleTileRefresh(0L)
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                translationX -= distanceX
                translationY -= distanceY
                clampTranslation()
                notifyViewportChanged()
                scheduleTileRefresh(TILE_REFRESH_DELAY_MS)
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                val target = if (zoomFactor < DOUBLE_TAP_ZOOM_THRESHOLD) DOUBLE_TAP_ZOOM else MIN_ZOOM
                zoomAround(event.x, event.y, target)
                scheduleTileRefresh(0L)
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean = performClick()
        },
    )

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "단일 PDF 페이지"
    }

    fun setDocument(document: PdfDocument, pageSizes: Map<Int, PdfPageSize>) {
        require(pageSizes.isNotEmpty()) { "PDF has no pages" }
        clearDocument()
        this.document = document
        this.pageSizes = pageSizes
        activePage = 0
        zoomFactor = MIN_ZOOM
        translationX = 0f
        translationY = 0f
        openPage(0)
    }

    fun showPage(pageNumber: Int) {
        val target = pageNumber.coerceIn(0, (document?.pageCount ?: 1) - 1)
        if (target == activePage && bitmapSource != null) return
        openPage(target)
    }

    fun resetZoom() {
        zoomFactor = MIN_ZOOM
        translationX = 0f
        translationY = 0f
        clearTiles()
        notifyViewportChanged()
    }

    fun setZoom(zoom: Float) {
        zoomAround(width / 2f, height / 2f, zoom)
        scheduleTileRefresh(0L)
    }

    fun restoreViewport(normalizedCenterX: Float, normalizedCenterY: Float, displayScale: Float) {
        val size = pageSize() ?: return
        val fitScale = fitScaleFor(size)
        zoomFactor = (displayScale / fitScale).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = this.displayScale
        translationX = width / 2f - normalizedCenterX.coerceIn(0f, 1f) * size.width * scale - (width - size.width * scale) / 2f
        translationY = height / 2f - normalizedCenterY.coerceIn(0f, 1f) * size.height * scale - (height - size.height * scale) / 2f
        clampTranslation()
        notifyViewportChanged()
        scheduleTileRefresh(0L)
    }

    fun viewToPdfPoint(x: Float, y: Float): PointF? {
        val size = pageSize() ?: return null
        val bounds = calculatePageBounds(size)
        if (!bounds.contains(x, y)) return null
        return PointF(
            ((x - bounds.left) / bounds.width() * size.width).coerceIn(0f, size.width.toFloat()),
            ((y - bounds.top) / bounds.height() * size.height).coerceIn(0f, size.height.toFloat()),
        )
    }

    fun pdfToViewPoint(pageNumber: Int, point: PointF): PointF? {
        if (pageNumber != activePage) return null
        val size = pageSize() ?: return null
        val bounds = calculatePageBounds(size)
        return PointF(
            bounds.left + point.x / size.width * bounds.width(),
            bounds.top + point.y / size.height * bounds.height(),
        )
    }

    fun release() {
        removeCallbacks(tileRefresh)
        clearDocument()
        renderScope.cancel()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clampTranslation()
        requestBackground()
        scheduleTileRefresh(0L)
        notifyViewportChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val bounds = pageBounds ?: return
        canvas.drawRect(bounds, pagePaint)
        canvas.save()
        canvas.clipRect(bounds)
        backgroundBitmap?.let { canvas.drawBitmap(it, null, bounds, bitmapPaint) }
        drawTiles(canvas, bounds)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (document == null || event.containsStylus()) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            scheduleTileRefresh(0L)
        }
        return scaled || gestured || event.actionMasked != MotionEvent.ACTION_DOWN
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun openPage(pageNumber: Int) {
        val currentDocument = document ?: return
        require(pageSizes[pageNumber] != null) { "Missing size for PDF page $pageNumber" }
        pageGeneration++
        cancelRenderJobs()
        bitmapSource?.close()
        bitmapSource = currentDocument.getPageBitmapSource(pageNumber)
        recycle(backgroundBitmap)
        backgroundBitmap = null
        backgroundSize = null
        clearTiles()
        activePage = pageNumber
        translationX = 0f
        translationY = 0f
        requestBackground()
        scheduleTileRefresh(0L)
        notifyViewportChanged()
    }

    private fun requestBackground() {
        val size = pageSize() ?: return
        val source = bitmapSource ?: return
        if (width <= 0 || height <= 0) return
        val fitScale = fitScaleFor(size)
        val target = Size(
            (size.width * fitScale).roundToInt().coerceAtLeast(1),
            (size.height * fitScale).roundToInt().coerceAtLeast(1),
        )
        if (backgroundBitmap != null && backgroundSize == target) return

        val generation = pageGeneration
        backgroundJob?.cancel()
        backgroundJob = renderScope.launch {
            var rendered: Bitmap? = null
            try {
                rendered = source.getBitmap(target)
                ensureActive()
                if (generation != pageGeneration || source !== bitmapSource) return@launch
                recycle(backgroundBitmap)
                backgroundBitmap = rendered
                rendered = null
                backgroundSize = target
                invalidate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == pageGeneration) onRenderError?.invoke(error)
            } finally {
                recycle(rendered)
            }
        }
    }

    private fun scheduleTileRefresh(delayMillis: Long) {
        removeCallbacks(tileRefresh)
        postDelayed(tileRefresh, delayMillis)
    }

    private fun refreshTiles() {
        val size = pageSize() ?: return
        val source = bitmapSource ?: return
        if (width <= 0 || height <= 0) return
        if (zoomFactor <= TILE_ZOOM_THRESHOLD) {
            clearTiles()
            invalidate()
            return
        }

        val bounds = calculatePageBounds(size)
        val scaledPage = Size(
            (size.width * displayScale).roundToInt().coerceAtLeast(1),
            (size.height * displayScale).roundToInt().coerceAtLeast(1),
        )
        val visible = visiblePixelArea(bounds, scaledPage)
        val requested = PdfTileGrid.visibleTiles(scaledPage.width, scaledPage.height, visible)
        val requestedKeys = requested.mapTo(mutableSetOf()) { it.key }

        var board = tileBoard
        if (board == null || board.pageSize != scaledPage) {
            clearTiles()
            board = TileBoard(scaledPage)
            tileBoard = board
        } else {
            val obsolete = board.tiles.keys - requestedKeys
            obsolete.forEach { key -> recycle(board.tiles.remove(key)) }
        }

        tileJob?.cancel()
        val missing = requested.filter { it.key !in board.tiles }
        if (missing.isEmpty()) {
            invalidate()
            return
        }

        val generation = pageGeneration
        val targetBoard = board
        tileJob = renderScope.launch {
            for (tile in missing) {
                ensureActive()
                var rendered: Bitmap? = null
                try {
                    rendered = source.getBitmap(
                        targetBoard.pageSize,
                        Rect(tile.rect.left, tile.rect.top, tile.rect.right, tile.rect.bottom),
                    )
                    ensureActive()
                    if (
                        generation != pageGeneration ||
                        source !== bitmapSource ||
                        targetBoard !== tileBoard ||
                        tile.key !in requestedKeys
                    ) {
                        return@launch
                    }
                    recycle(targetBoard.tiles.put(tile.key, rendered))
                    rendered = null
                    invalidate()
                } finally {
                    recycle(rendered)
                }
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException && generation == pageGeneration) {
                    post { onRenderError?.invoke(error) }
                }
            }
        }
    }

    private fun drawTiles(canvas: Canvas, bounds: RectF) {
        val board = tileBoard ?: return
        val pageWidth = board.pageSize.width.toFloat()
        val pageHeight = board.pageSize.height.toFloat()
        board.tiles.forEach { (key, bitmap) ->
            val leftPx = key.column * PdfTileGrid.TILE_SIZE_PX
            val topPx = key.row * PdfTileGrid.TILE_SIZE_PX
            val rightPx = minOf(leftPx + bitmap.width, board.pageSize.width)
            val bottomPx = minOf(topPx + bitmap.height, board.pageSize.height)
            val destination = RectF(
                bounds.left + leftPx / pageWidth * bounds.width(),
                bounds.top + topPx / pageHeight * bounds.height(),
                bounds.left + rightPx / pageWidth * bounds.width(),
                bounds.top + bottomPx / pageHeight * bounds.height(),
            )
            canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
        }
    }

    private fun visiblePixelArea(bounds: RectF, scaledPage: Size): PixelRect {
        val visibleLeft = maxOf(0f, -bounds.left)
        val visibleTop = maxOf(0f, -bounds.top)
        val visibleRight = minOf(bounds.width(), width - bounds.left)
        val visibleBottom = minOf(bounds.height(), height - bounds.top)
        return PixelRect(
            left = floor(visibleLeft / bounds.width() * scaledPage.width).toInt(),
            top = floor(visibleTop / bounds.height() * scaledPage.height).toInt(),
            right = ceil(visibleRight / bounds.width() * scaledPage.width).toInt(),
            bottom = ceil(visibleBottom / bounds.height() * scaledPage.height).toInt(),
        )
    }

    private fun zoomAround(focusX: Float, focusY: Float, requestedZoom: Float) {
        val size = pageSize() ?: return
        val oldBounds = calculatePageBounds(size)
        val oldScale = oldBounds.width() / size.width
        val pdfFocusX = (focusX - oldBounds.left) / oldScale
        val pdfFocusY = (focusY - oldBounds.top) / oldScale

        zoomFactor = requestedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val newScale = displayScale
        translationX = focusX - pdfFocusX * newScale - (width - size.width * newScale) / 2f
        translationY = focusY - pdfFocusY * newScale - (height - size.height * newScale) / 2f
        clampTranslation()
        notifyViewportChanged()
    }

    private fun clampTranslation() {
        val size = pageSize() ?: return
        val scale = displayScale
        val maxX = maxOf(0f, (size.width * scale - width) / 2f)
        val maxY = maxOf(0f, (size.height * scale - height) / 2f)
        translationX = translationX.coerceIn(-maxX, maxX)
        translationY = translationY.coerceIn(-maxY, maxY)
    }

    private fun calculatePageBounds(size: PdfPageSize): RectF {
        val scale = fitScaleFor(size) * zoomFactor
        val pageWidth = size.width * scale
        val pageHeight = size.height * scale
        val left = (width - pageWidth) / 2f + translationX
        val top = (height - pageHeight) / 2f + translationY
        return RectF(left, top, left + pageWidth, top + pageHeight)
    }

    private fun fitScaleFor(size: PdfPageSize): Float {
        if (width <= 0 || height <= 0) return 1f
        return minOf(width.toFloat() / size.width, height.toFloat() / size.height)
    }

    private fun pageSize(): PdfPageSize? = pageSizes[activePage]

    private fun notifyViewportChanged() {
        invalidate()
        onViewportChanged?.invoke()
    }

    private fun cancelRenderJobs() {
        removeCallbacks(tileRefresh)
        backgroundJob?.cancel()
        backgroundJob = null
        tileJob?.cancel()
        tileJob = null
    }

    private fun clearTiles() {
        tileJob?.cancel()
        tileJob = null
        tileBoard?.tiles?.values?.forEach(::recycle)
        tileBoard = null
    }

    private fun clearDocument() {
        pageGeneration++
        cancelRenderJobs()
        bitmapSource?.close()
        bitmapSource = null
        recycle(backgroundBitmap)
        backgroundBitmap = null
        backgroundSize = null
        clearTiles()
        document?.close()
        document = null
        pageSizes = emptyMap()
    }

    private fun recycle(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun MotionEvent.containsStylus(): Boolean =
        (0 until pointerCount).any { index ->
            val type = getToolType(index)
            type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
        }

    private data class TileBoard(
        val pageSize: Size,
        val tiles: MutableMap<TileKey, Bitmap> = linkedMapOf(),
    )

    companion object {
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 6f
        private const val DOUBLE_TAP_ZOOM = 2.5f
        private const val DOUBLE_TAP_ZOOM_THRESHOLD = 1.5f
        private const val TILE_ZOOM_THRESHOLD = 1.05f
        private const val TILE_REFRESH_DELAY_MS = 90L
    }
}
