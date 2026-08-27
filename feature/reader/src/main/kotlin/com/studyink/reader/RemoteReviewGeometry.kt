package com.studyink.reader

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class RemoteReviewRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val shorterSide: Float get() = min(width, height)

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** Shared page fitting and hit geometry for the editor and the read-only student overlay. */
object RemoteReviewGeometry {
    fun fitCenter(
        containerWidth: Float,
        containerHeight: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): RemoteReviewRect {
        if (
            containerWidth <= 0f || containerHeight <= 0f ||
            pageWidth <= 0f || pageHeight <= 0f ||
            !containerWidth.isFinite() || !containerHeight.isFinite() ||
            !pageWidth.isFinite() || !pageHeight.isFinite()
        ) return RemoteReviewRect(0f, 0f, 0f, 0f)
        val scale = min(containerWidth / pageWidth, containerHeight / pageHeight)
        val width = pageWidth * scale
        val height = pageHeight * scale
        val left = (containerWidth - width) / 2f
        val top = (containerHeight - height) / 2f
        return RemoteReviewRect(left, top, left + width, top + height)
    }

    fun viewToNormalized(
        viewX: Float,
        viewY: Float,
        page: RemoteReviewRect,
    ): RemoteNormalizedPoint? {
        if (page.width <= 0f || page.height <= 0f || !page.contains(viewX, viewY)) return null
        return RemoteNormalizedPoint(
            x = ((viewX - page.left) / page.width).coerceIn(0f, 1f),
            y = ((viewY - page.top) / page.height).coerceIn(0f, 1f),
        )
    }

    fun normalizedToView(point: RemoteNormalizedPoint, page: RemoteReviewRect): ReviewPoint = ReviewPoint(
        x = page.left + point.x.coerceIn(0f, 1f) * page.width,
        y = page.top + point.y.coerceIn(0f, 1f) * page.height,
    )

    fun widthToView(widthFraction: Float, page: RemoteReviewRect): Float =
        max(1f, widthFraction.coerceAtLeast(0f) * page.shorterSide)

    /**
     * Tests an eraser corridor against a teacher trace in page pixels. The normalized X and Y axes
     * are intentionally scaled independently so portrait pages do not turn a circular eraser into
     * an ellipse.
     */
    fun intersectsEraser(
        stroke: RemoteFeedbackStroke,
        eraserPath: List<RemoteNormalizedPoint>,
        eraserRadiusFraction: Float,
        pageWidthPx: Int,
        pageHeightPx: Int,
    ): Boolean = intersectingStrokeIds(
        strokes = listOf(stroke),
        eraserPath = eraserPath,
        eraserRadiusFraction = eraserRadiusFraction,
        pageWidthPx = pageWidthPx,
        pageHeightPx = pageHeightPx,
    ).isNotEmpty()

    /**
     * Exact whole-trace hit testing with one reusable spatial index for the eraser path. This keeps
     * the previous segment-distance semantics while avoiding a new pair list and a full eraser-path
     * scan for every teacher segment.
     */
    internal fun intersectingStrokeIds(
        strokes: List<RemoteFeedbackStroke>,
        eraserPath: List<RemoteNormalizedPoint>,
        eraserRadiusFraction: Float,
        pageWidthPx: Int,
        pageHeightPx: Int,
    ): Set<String> {
        if (strokes.isEmpty() || eraserPath.isEmpty() || !eraserRadiusFraction.isFinite()) return emptySet()
        if (eraserPath.any { point -> !point.x.isFinite() || !point.y.isFinite() }) return emptySet()
        val shortSide = min(pageWidthPx, pageHeightPx).coerceAtLeast(1).toFloat()
        val xScale = pageWidthPx.coerceAtLeast(1) / shortSide
        val yScale = pageHeightPx.coerceAtLeast(1) / shortSide
        val index = EraserSegmentIndex(
            points = eraserPath,
            xScale = xScale,
            yScale = yScale,
            preferredCellSize = eraserRadiusFraction.coerceAtLeast(MIN_INDEX_CELL_SIZE),
        )
        val hits = linkedSetOf<String>()
        for (stroke in strokes) {
            if (Thread.currentThread().isInterrupted) return emptySet()
            if (stroke.points.isEmpty()) continue
            val threshold = eraserRadiusFraction.coerceAtLeast(0f) +
                stroke.widthFraction.coerceAtLeast(0f) / 2f
            if (!threshold.isFinite()) continue
            if (index.intersects(stroke.points, xScale, yScale, threshold)) {
                hits += stroke.id
            }
        }
        return hits
    }

    /** Grid entries point at eraser segments; queries are exact after the cell-level rejection. */
    private class EraserSegmentIndex(
        points: List<RemoteNormalizedPoint>,
        private val xScale: Float,
        private val yScale: Float,
        preferredCellSize: Float,
    ) {
        private val pointCount = points.size
        private val segmentCount = max(1, pointCount - 1)
        private val xs = FloatArray(pointCount) { index -> points[index].x * xScale }
        private val ys = FloatArray(pointCount) { index -> points[index].y * yScale }
        private val minX = xs.minOrNull() ?: 0f
        private val maxX = xs.maxOrNull() ?: minX
        private val minY = ys.minOrNull() ?: 0f
        private val maxY = ys.maxOrNull() ?: minY
        private val grid: Grid = chooseGrid(preferredCellSize)
        private val cells: Array<IntArray?>
        private val visited = IntArray(segmentCount)
        private var visitStamp = 0

        init {
            val builders = arrayOfNulls<MutableList<Int>>(grid.columns * grid.rows)
            for (segmentIndex in 0 until segmentCount) {
                abortIfInterrupted()
                val start = segmentStart(segmentIndex)
                val end = segmentEnd(segmentIndex)
                val left = grid.column(min(xs[start], xs[end]))
                val right = grid.column(max(xs[start], xs[end]))
                val top = grid.row(min(ys[start], ys[end]))
                val bottom = grid.row(max(ys[start], ys[end]))
                for (row in top..bottom) {
                    val rowOffset = row * grid.columns
                    for (column in left..right) {
                        val cellIndex = rowOffset + column
                        val bucket = builders[cellIndex] ?: mutableListOf<Int>().also { builders[cellIndex] = it }
                        bucket += segmentIndex
                    }
                }
            }
            cells = Array(builders.size) { index -> builders[index]?.toIntArray() }
        }

        fun intersects(
            strokePoints: List<RemoteNormalizedPoint>,
            strokeXScale: Float,
            strokeYScale: Float,
            threshold: Float,
        ): Boolean {
            if (strokePoints.isEmpty()) return false
            if (strokePoints.any { point -> !point.x.isFinite() || !point.y.isFinite() }) return false
            val boundsPadding = threshold + EPSILON
            var strokeMinX = Float.POSITIVE_INFINITY
            var strokeMaxX = Float.NEGATIVE_INFINITY
            var strokeMinY = Float.POSITIVE_INFINITY
            var strokeMaxY = Float.NEGATIVE_INFINITY
            for (point in strokePoints) {
                val x = point.x * strokeXScale
                val y = point.y * strokeYScale
                strokeMinX = min(strokeMinX, x)
                strokeMaxX = max(strokeMaxX, x)
                strokeMinY = min(strokeMinY, y)
                strokeMaxY = max(strokeMaxY, y)
            }
            if (
                strokeMaxX + boundsPadding < minX || strokeMinX - boundsPadding > maxX ||
                strokeMaxY + boundsPadding < minY || strokeMinY - boundsPadding > maxY
            ) return false

            val strokeSegmentCount = max(1, strokePoints.size - 1)
            for (strokeSegmentIndex in 0 until strokeSegmentCount) {
                if (Thread.currentThread().isInterrupted) return false
                val startIndex = if (strokePoints.size == 1) 0 else strokeSegmentIndex
                val endIndex = if (strokePoints.size == 1) 0 else strokeSegmentIndex + 1
                val start = strokePoints[startIndex]
                val end = strokePoints[endIndex]
                if (
                    querySegment(
                        ax = start.x * strokeXScale,
                        ay = start.y * strokeYScale,
                        bx = end.x * strokeXScale,
                        by = end.y * strokeYScale,
                        threshold = threshold,
                    )
                ) return true
            }
            return false
        }

        private fun querySegment(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
            threshold: Float,
        ): Boolean {
            val boundsPadding = threshold + EPSILON
            val queryLeft = min(ax, bx) - boundsPadding
            val queryRight = max(ax, bx) + boundsPadding
            val queryTop = min(ay, by) - boundsPadding
            val queryBottom = max(ay, by) + boundsPadding
            if (queryRight < minX || queryLeft > maxX || queryBottom < minY || queryTop > maxY) return false

            val left = grid.column(queryLeft)
            val right = grid.column(queryRight)
            val top = grid.row(queryTop)
            val bottom = grid.row(queryBottom)
            val stamp = nextVisitStamp()
            val thresholdSquared = threshold * threshold
            for (row in top..bottom) {
                val rowOffset = row * grid.columns
                for (column in left..right) {
                    val bucket = cells[rowOffset + column] ?: continue
                    for (eraserSegmentIndex in bucket) {
                        if (visited[eraserSegmentIndex] == stamp) continue
                        visited[eraserSegmentIndex] = stamp
                        val start = segmentStart(eraserSegmentIndex)
                        val end = segmentEnd(eraserSegmentIndex)
                        if (
                            segmentDistanceSquared(
                                ax,
                                ay,
                                bx,
                                by,
                                xs[start],
                                ys[start],
                                xs[end],
                                ys[end],
                            ) <= thresholdSquared
                        ) return true
                    }
                }
            }
            return false
        }

        private fun nextVisitStamp(): Int {
            if (visitStamp == Int.MAX_VALUE) {
                visited.fill(0)
                visitStamp = 1
            } else {
                visitStamp += 1
            }
            return visitStamp
        }

        private fun chooseGrid(initialCellSize: Float): Grid {
            var cellSize = initialCellSize.coerceAtLeast(MIN_INDEX_CELL_SIZE)
            repeat(MAX_GRID_RESIZE_STEPS) {
                val candidate = Grid(minX, minY, maxX, maxY, cellSize)
                if (candidate.cellCount > MAX_INDEX_CELLS) {
                    cellSize *= 2f
                    return@repeat
                }
                var references = 0L
                for (segmentIndex in 0 until segmentCount) {
                    abortIfInterrupted()
                    val start = segmentStart(segmentIndex)
                    val end = segmentEnd(segmentIndex)
                    val columns = candidate.column(max(xs[start], xs[end])) -
                        candidate.column(min(xs[start], xs[end])) + 1
                    val rows = candidate.row(max(ys[start], ys[end])) -
                        candidate.row(min(ys[start], ys[end])) + 1
                    references += columns.toLong() * rows.toLong()
                    if (references > MAX_INDEX_REFERENCES) break
                }
                if (references <= MAX_INDEX_REFERENCES) return candidate
                cellSize *= 2f
            }
            return Grid(minX, minY, maxX, maxY, max(maxX - minX, maxY - minY).coerceAtLeast(1f))
        }

        private fun segmentStart(index: Int): Int = if (pointCount == 1) 0 else index

        private fun segmentEnd(index: Int): Int = if (pointCount == 1) 0 else index + 1

        private fun abortIfInterrupted() {
            if (Thread.currentThread().isInterrupted) {
                throw java.util.concurrent.CancellationException("Eraser calculation cancelled")
            }
        }
    }

    private data class Grid(
        val originX: Float,
        val originY: Float,
        val maximumX: Float,
        val maximumY: Float,
        val cellSize: Float,
    ) {
        val columns: Int = (floor((maximumX - originX).coerceAtLeast(0f) / cellSize).toInt() + 1)
            .coerceAtLeast(1)
        val rows: Int = (floor((maximumY - originY).coerceAtLeast(0f) / cellSize).toInt() + 1)
            .coerceAtLeast(1)
        val cellCount: Long = columns.toLong() * rows.toLong()

        fun column(x: Float): Int = floor((x - originX) / cellSize).toInt().coerceIn(0, columns - 1)

        fun row(y: Float): Int = floor((y - originY) / cellSize).toInt().coerceIn(0, rows - 1)
    }

    private fun segmentDistanceSquared(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
    ): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return minOf(
            pointSegmentDistanceSquared(ax, ay, cx, cy, dx, dy),
            pointSegmentDistanceSquared(bx, by, cx, cy, dx, dy),
            pointSegmentDistanceSquared(cx, cy, ax, ay, bx, by),
            pointSegmentDistanceSquared(dx, dy, ax, ay, bx, by),
        )
    }

    private fun segmentsIntersect(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
        dx: Float,
        dy: Float,
    ): Boolean {
        val o1 = orientation(ax, ay, bx, by, cx, cy)
        val o2 = orientation(ax, ay, bx, by, dx, dy)
        val o3 = orientation(cx, cy, dx, dy, ax, ay)
        val o4 = orientation(cx, cy, dx, dy, bx, by)
        if (o1 * o2 < 0f && o3 * o4 < 0f) return true
        return (abs(o1) <= EPSILON && onSegment(ax, ay, bx, by, cx, cy)) ||
            (abs(o2) <= EPSILON && onSegment(ax, ay, bx, by, dx, dy)) ||
            (abs(o3) <= EPSILON && onSegment(cx, cy, dx, dy, ax, ay)) ||
            (abs(o4) <= EPSILON && onSegment(cx, cy, dx, dy, bx, by))
    }

    private fun orientation(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
        px >= min(ax, bx) - EPSILON && px <= max(ax, bx) + EPSILON &&
            py >= min(ay, by) - EPSILON && py <= max(ay, by) + EPSILON

    private fun pointSegmentDistanceSquared(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= EPSILON) return distanceSquared(px, py, ax, ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distanceSquared(px, py, ax + t * dx, ay + t * dy)
    }

    private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private const val MIN_INDEX_CELL_SIZE = 0.025f
    private const val MAX_INDEX_CELLS = 32_768L
    private const val MAX_INDEX_REFERENCES = 400_000L
    private const val MAX_GRID_RESIZE_STEPS = 12
    private const val EPSILON = 0.000001f
}

data class ReviewPoint(val x: Float, val y: Float)
