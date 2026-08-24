package com.studyink.monitor.render

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal const val STUDENT_AUTHOR_ID = "student"

internal data class RenderSize(val width: Int, val height: Int) {
    val pixelCount: Long get() = width.toLong() * height.toLong()
}

internal data class RasterPoint(val x: Float, val y: Float)

/** Android-free drawing description, kept deterministic for geometry tests. */
internal data class RasterStroke(
    val points: List<RasterPoint>,
    val widthPixels: Float,
    val colorArgb: Int,
    val alpha: Int,
    val tool: StrokeTool,
)

internal fun calculateRenderSize(
    pdfWidth: Int,
    pdfHeight: Int,
    limits: PageRenderLimits,
): RenderSize {
    require(pdfWidth > 0 && pdfHeight > 0) { "PDF page dimensions must be positive" }
    var width = limits.targetWidthPixels.coerceIn(limits.minimumWidthPixels, limits.maximumWidthPixels)
    var height = ceil(width.toDouble() * pdfHeight.toDouble() / pdfWidth.toDouble()).toInt().coerceAtLeast(1)
    val requestedPixels = width.toLong() * height.toLong()
    if (requestedPixels > limits.maximumPixelCount) {
        // The memory ceiling takes precedence over the preferred minimum width for pathological
        // page aspect ratios. Both dimensions retain their original aspect ratio.
        val reduction = sqrt(limits.maximumPixelCount.toDouble() / requestedPixels.toDouble())
        width = floor(width * reduction).toInt().coerceAtLeast(1)
        height = floor(height * reduction).toInt().coerceAtLeast(1)
        while (width.toLong() * height.toLong() > limits.maximumPixelCount) {
            if (height >= width) height-- else width--
        }
    }
    return RenderSize(width, height)
}

internal fun selectStudentStrokes(
    snapshot: AnnotationSnapshot,
    pageNumber: Int,
    attemptNo: Int?,
): List<StrokeAsset> {
    if (attemptNo == null) return emptyList()
    return snapshot.activeStrokes.filter { stroke ->
        stroke.pageNumber == pageNumber &&
            stroke.authorId == STUDENT_AUTHOR_ID &&
            stroke.attemptNo == attemptNo
    }
}

internal fun rasterizeStudentStrokes(
    strokes: List<StrokeAsset>,
    outputWidthPixels: Int,
): Sequence<RasterStroke> {
    require(outputWidthPixels > 0) { "Output width must be positive" }
    val scale = outputWidthPixels / CANONICAL_PAGE_WIDTH
    // Keep this lazy: a workbook page can contain many points, and production rendering only
    // needs one transformed stroke at a time while the original immutable snapshot is retained.
    return strokes.asSequence().mapNotNull { stroke ->
        if (!stroke.width.isFinite() || stroke.width <= 0f) return@mapNotNull null
        val points = stroke.points.mapNotNull { point ->
            if (point.x.isFinite() && point.y.isFinite()) {
                RasterPoint(point.x * scale, point.y * scale)
            } else {
                null
            }
        }
        if (points.isEmpty()) return@mapNotNull null
        RasterStroke(
            points = points,
            widthPixels = (stroke.width * scale).coerceAtLeast(1f),
            colorArgb = stroke.colorArgb,
            alpha = if (stroke.tool == StrokeTool.HIGHLIGHTER) {
                95
            } else {
                (stroke.colorArgb ushr 24) and 0xff
            },
            tool = stroke.tool,
        )
    }
}

internal fun safeTelegramDisplayName(value: String, fallback: String): String {
    val cleaned = value
        .replace(Regex("[\\p{Cc}\\\\/:*?\"<>|]"), "_")
        .trim()
        .trim('.', '_')
        .take(80)
    return cleaned.ifBlank { fallback }
}
